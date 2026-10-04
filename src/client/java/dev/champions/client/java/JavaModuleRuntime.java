package dev.champions.client.java;

import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import dev.champions.core.java.RuntimeJavaCompiler;
import dev.champions.core.java.RuntimeModuleDiscovery;
import dev.champions.core.java.RuntimeModuleSourceSet;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ResearchModule;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Hash-gated compiler and lifecycle for ResearchModule source files outside src/. */
final class JavaModuleRuntime implements AutoCloseable {
	private static final String AUDIT_ID = "external-java-modules";
	private static final Pattern MODULE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*");
	private static final Pattern OPTION_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
	private final Path workspace;
	private final Path buildRoot;
	private final JavaRuntimeSettings settings;
	private final ChampionConsole console;
	private final Executor compilerExecutor;
	private final Supplier<List<Path>> classpathSupplier;
	private RuntimeModules active;
	private List<String> lastDiagnostics = List.of();
	private boolean busy;
	private boolean closed;

	JavaModuleRuntime(Path configDirectory, JavaRuntimeSettings settings, ChampionConsole console,
		Executor compilerExecutor, Supplier<List<Path>> classpathSupplier) {
		this.workspace = configDirectory.toAbsolutePath().normalize().resolve("modules");
		this.buildRoot = workspace.resolve(".build");
		this.settings = settings;
		this.console = console;
		this.compilerExecutor = compilerExecutor;
		this.classpathSupplier = classpathSupplier;
		initializeWorkspace();
	}

	synchronized void submit(String rawCommand) {
		String command = rawCommand == null ? "" : rawCommand.strip();
		if (command.isEmpty() || command.equalsIgnoreCase("help")) {
			help();
			return;
		}
		String[] parts = command.split("\\s+", 2);
		String operation = parts[0].toLowerCase(Locale.ROOT);
		String argument = parts.length == 2 ? parts[1].strip() : "";
		try {
			switch (operation) {
				case "status" -> status();
				case "sources", "list" -> sources();
				case "hash" -> hash();
				case "confirm" -> confirm(argument);
				case "compile" -> beginCompile(false);
				case "reload", "load", "start" -> beginCompile(true);
				case "unload", "stop" -> unload();
				case "diagnostics" -> diagnostics();
				default -> emit(ConsoleLine.error("Unknown module command: " + operation + ". Try java modules help."));
			}
		} catch (Exception error) {
			emit(ConsoleLine.error("Runtime module command failed: " + safeMessage(error)));
		}
	}

	private void help() {
		emit(ConsoleLine.accent("External Java research modules"));
		emit(ConsoleLine.info("  java modules status | sources | hash | diagnostics"));
		emit(ConsoleLine.info("  java modules confirm <current-sha256>"));
		emit(ConsoleLine.info("  java modules compile | reload | unload"));
		emit(ConsoleLine.muted("Drop .java files anywhere below " + workspace));
		emit(ConsoleLine.warning("External Java is unrestricted code in Minecraft, not a sandbox. Review every file."));
	}

	private void status() throws IOException {
		settings.reload();
		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		emit(ConsoleLine.accent("External Java module status"));
		emit(ConsoleLine.info("  workspace=" + workspace));
		emit(ConsoleLine.info("  developer_mode=" + settings.developerMode()));
		emit(ConsoleLine.info("  source_files=" + sourceSet.sources().size() + " bytes=" + sourceSet.totalBytes()));
		emit(ConsoleLine.info("  source_hash=" + sourceSet.sha256()));
		emit(ConsoleLine.info("  hash_confirmed=" + sourceSet.sha256().equals(settings.confirmedModuleHash())));
		emit(ConsoleLine.info("  state=" + (busy ? "compiling" : active == null ? "inactive" : "loaded")));
		if (active != null) {
			emit(ConsoleLine.info("  loaded_hash=" + active.sourceHash()));
			emit(ConsoleLine.info("  loaded_modules=" + String.join(", ", active.ids())));
			if (!sourceSet.sha256().equals(active.sourceHash())) {
				emit(ConsoleLine.warning("  source files changed; the previously confirmed modules remain loaded until reload or unload."));
			}
		}
	}

	private void sources() throws IOException {
		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		emit(ConsoleLine.accent("External Java module sources"));
		if (sourceSet.sources().isEmpty()) {
			emit(ConsoleLine.muted("  No .java files found. Add one or more files below " + workspace));
			return;
		}
		for (Path source : sourceSet.sources()) {
			emit(ConsoleLine.info("  " + workspace.relativize(source).toString().replace('\\', '/')));
		}
		emit(ConsoleLine.muted("  " + sourceSet.sources().size() + " file(s), " + sourceSet.totalBytes() + " byte(s)"));
	}

	private void hash() throws IOException {
		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		emit(ConsoleLine.accent("Current external-module source-set SHA-256"));
		emit(ConsoleLine.info(sourceSet.sha256()));
		emit(ConsoleLine.muted("Review the listed files, then confirm this exact hash before compiling or loading."));
	}

	private void confirm(String supplied) throws IOException {
		settings.reload();
		if (!settings.developerMode()) {
			emit(ConsoleLine.error("Developer mode is disabled. Set developerMode=true in " + settings.file()));
			return;
		}
		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		if (!sourceSet.sha256().equalsIgnoreCase(supplied)) {
			emit(ConsoleLine.error("Confirmation denied. The supplied hash does not equal the current module source hash."));
			emit(ConsoleLine.info("Current hash: " + sourceSet.sha256()));
			return;
		}
		settings.confirmModuleHash(sourceSet.sha256());
		emit(ConsoleLine.success("Confirmed external module source hash " + sourceSet.sha256()));
		console.recordAudit("java_module_hash_confirmed", "client", AUDIT_ID, "confirmed", sourceSet.sha256());
	}

	private void beginCompile(boolean activate) throws IOException {
		if (closed) { emit(ConsoleLine.error("Runtime module manager is closed.")); return; }
		if (busy) { emit(ConsoleLine.error("A runtime Java compilation is already in progress.")); return; }
		settings.reload();
		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		if (!settings.developerMode()) {
			emit(ConsoleLine.error("Developer mode is disabled. Edit " + settings.file() + " and set developerMode=true."));
			return;
		}
		if (!sourceSet.sha256().equals(settings.confirmedModuleHash())) {
			emit(ConsoleLine.error("External module source hash is not confirmed. Review the files, then run:"));
			emit(ConsoleLine.warning("  java modules confirm " + sourceSet.sha256()));
			return;
		}
		if (sourceSet.sources().isEmpty()) {
			emit(ConsoleLine.error("No .java files were found below " + workspace));
			return;
		}
		if (!RuntimeJavaCompiler.available()) {
			emit(ConsoleLine.error("No system Java compiler is available. Launch Minecraft with a full JDK 25."));
			return;
		}

		busy = true;
		emit(ConsoleLine.warning("Compiling " + sourceSet.sources().size() + " external Java source file(s); source hash=" + sourceSet.sha256()));
		console.recordAudit("java_module_compile", "client", AUDIT_ID, "started", sourceSet.sha256());
		try {
			compilerExecutor.execute(() -> compile(sourceSet, activate));
		} catch (RuntimeException error) {
			busy = false;
			throw error;
		}
	}

	private void compile(RuntimeModuleSourceSet sourceSet, boolean activate) {
		Path bundleRoot = null;
		URLClassLoader loader = null;
		try {
			if (Files.isSymbolicLink(buildRoot)) throw new IOException("Generated module build directory cannot be a symbolic link");
			Files.createDirectories(buildRoot);
			bundleRoot = Files.createTempDirectory(buildRoot,
				Instant.now().toEpochMilli() + "-" + sourceSet.sha256().substring(0, 12) + "-");
			Path output = bundleRoot.resolve("classes");
			RuntimeModuleSourceSet staged = stageSourceSet(sourceSet, bundleRoot.resolve("sources"));
			RuntimeJavaCompiler.CompilationResult result = RuntimeJavaCompiler.compile(staged.sources(), output, classpathSupplier.get());
			synchronized (this) { lastDiagnostics = result.diagnostics(); }
			if (!result.success()) {
				compilationFailed(sourceSet, bundleRoot, result.diagnostics());
				return;
			}

			loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, ResearchModule.class.getClassLoader());
			List<Class<? extends ResearchModule>> types = RuntimeModuleDiscovery.discover(output, loader);
			if (!activate) {
				loader.close();
				loader = null;
				emit(ConsoleLine.success("Compilation and module discovery succeeded for " + types.size() + " class(es): "
					+ String.join(", ", types.stream().map(Class::getName).toList())));
				console.recordAudit("java_module_compile", "client", AUDIT_ID, "completed", sourceSet.sha256());
				deleteRecursively(bundleRoot);
				setBusy(false);
				return;
			}

			PreparedModules prepared = new PreparedModules(sourceSet, bundleRoot, loader, types);
			Minecraft.getInstance().execute(() -> activate(prepared));
			loader = null; // The scheduled client-thread activation now owns it.
		} catch (Throwable error) {
			if (loader != null) closeLoader(loader);
			emit(ConsoleLine.error("Runtime module compilation/load failed: " + safeMessage(error)));
			console.recordAudit("java_module_compile", "client", AUDIT_ID, "error", safeMessage(error));
			deleteRecursively(bundleRoot);
			setBusy(false);
		}
	}

	private void compilationFailed(RuntimeModuleSourceSet sourceSet, Path bundleRoot, List<String> diagnostics) {
		emit(ConsoleLine.error("Runtime module compilation failed. Run java modules diagnostics for details."));
		diagnostics.stream().limit(8).forEach(value -> emit(ConsoleLine.error(value)));
		console.recordAudit("java_module_compile", "client", AUDIT_ID, "error", "Compilation failed for " + sourceSet.sha256());
		deleteRecursively(bundleRoot);
		setBusy(false);
	}

	private synchronized void activate(PreparedModules prepared) {
		boolean swapped = false;
		try {
			if (closed) throw new IllegalStateException("Runtime module manager closed before activation");
			settings.reload();
			if (!settings.developerMode() || !prepared.sourceSet().sha256().equals(settings.confirmedModuleHash())) {
				throw new IllegalStateException("Developer mode or source confirmation was revoked before activation");
			}
			RuntimeModuleSourceSet latest = RuntimeModuleSourceSet.capture(workspace);
			if (!latest.sha256().equals(prepared.sourceSet().sha256())) {
				throw new IllegalStateException("Module source files changed during compilation; review and confirm the new hash");
			}

			List<ResearchModule> instances = new ArrayList<>();
			List<String> replacementIds = new ArrayList<>();
			Set<String> uniqueIds = new HashSet<>();
			for (Class<? extends ResearchModule> type : prepared.types()) {
				ResearchModule module = type.getConstructor().newInstance();
				String id = validateModule(module);
				if (!uniqueIds.add(id)) throw new IllegalArgumentException("Duplicate id among external modules: " + id);
				instances.add(module);
				replacementIds.add(id);
			}

			List<String> previousIds = active == null ? List.of() : active.ids();
			console.replaceExternalModules(previousIds, instances);
			RuntimeModules previous = active;
			RuntimeModules replacement = new RuntimeModules(prepared.sourceSet().sha256(), prepared.outputRoot(),
				prepared.loader(), replacementIds);
			active = replacement;
			swapped = true;
			console.emit(ConsoleLine.success("Loaded " + instances.size() + " external research module(s): " + String.join(", ", replacement.ids())));
			console.recordAudit("java_modules", "client", AUDIT_ID, "loaded",
				"source_hash=" + replacement.sourceHash() + " ids=" + String.join(",", replacement.ids()));
			closeLoaded(previous);
			cleanupStaleBuilds(replacement.outputRoot());
		} catch (Throwable error) {
			if (!swapped) {
				closeLoader(prepared.loader());
				deleteRecursively(prepared.outputRoot());
			}
			emit(ConsoleLine.error("Runtime module activation failed: " + safeMessage(error)));
			console.recordAudit("java_modules", "client", AUDIT_ID, "error", safeMessage(error));
		} finally {
			busy = false;
		}
	}

	private static String validateModule(ResearchModule module) {
		if (module == null) throw new IllegalArgumentException("Module constructor returned null");
		String id = module.id();
		if (id == null || id.length() > 128 || !MODULE_ID.matcher(id).matches()) {
			throw new IllegalArgumentException("Module id must be lower-case path text (1-128 characters): " + safeText(id, 140));
		}
		validateText(module.name(), "module name", 120);
		validateText(module.description(), "module description", 1_000);
		Collection<ModuleOption> options = module.options();
		if (options == null || options.size() > 64) throw new IllegalArgumentException("Module must declare at most 64 non-null options");
		Set<String> optionNames = new HashSet<>();
		for (ModuleOption option : options) {
			if (option == null || !OPTION_NAME.matcher(option.name()).matches()) {
				throw new IllegalArgumentException("Module option names must match [A-Z][A-Z0-9_]{0,31}");
			}
			if (!optionNames.add(option.name())) throw new IllegalArgumentException("Duplicate module option: " + option.name());
			validateOptionalText(option.description(), "option description", 500);
			if (option.defaultValue().length() > 512) throw new IllegalArgumentException("Default value is too long for option " + option.name());
			if (option.choices().size() > 64) throw new IllegalArgumentException("Too many choices for option " + option.name());
			for (String choice : option.choices()) validateText(choice, "option choice", 120);
		}
		return id;
	}

	private static void validateText(String value, String label, int maximum) {
		if (value == null || value.isBlank() || value.length() > maximum) {
			throw new IllegalArgumentException("Invalid " + label + " (must be 1-" + maximum + " characters)");
		}
		for (int i = 0; i < value.length(); i++) {
			if (Character.isISOControl(value.charAt(i)) && value.charAt(i) != '\n' && value.charAt(i) != '\t') {
				throw new IllegalArgumentException("Invalid control character in " + label);
			}
		}
	}

	private static void validateOptionalText(String value, String label, int maximum) {
		if (value == null || value.length() > maximum) throw new IllegalArgumentException("Invalid " + label);
		for (int i = 0; i < value.length(); i++) {
			if (Character.isISOControl(value.charAt(i)) && value.charAt(i) != '\n' && value.charAt(i) != '\t') {
				throw new IllegalArgumentException("Invalid control character in " + label);
			}
		}
	}

	private static String safeText(String value, int maximum) {
		if (value == null) return "<null>";
		String clean = value.replace('\r', ' ').replace('\n', ' ');
		return clean.length() <= maximum ? clean : clean.substring(0, maximum) + "...";
	}

	private void unload() {
		if (busy) {
			emit(ConsoleLine.warning("Wait for the current runtime-module compilation before unloading."));
			return;
		}
		if (active == null) {
			emit(ConsoleLine.muted("No external Java research modules are loaded."));
			return;
		}
		RuntimeModules stopping = active;
		try {
			console.replaceExternalModules(stopping.ids(), List.of());
		} catch (RuntimeException error) {
			emit(ConsoleLine.error("Could not unload runtime modules: " + safeMessage(error)));
			return;
		}
		active = null;
		closeLoaded(stopping);
		emit(ConsoleLine.success("Unloaded external research modules: " + String.join(", ", stopping.ids())));
		console.recordAudit("java_modules", "client", AUDIT_ID, "unloaded", "source_hash=" + stopping.sourceHash());
	}

	private void diagnostics() {
		if (lastDiagnostics.isEmpty()) {
			emit(ConsoleLine.muted("No external-module compiler diagnostics are available."));
			return;
		}
		emit(ConsoleLine.accent("Last external-module compiler diagnostics"));
		lastDiagnostics.forEach(value -> emit(ConsoleLine.info(value)));
	}

	private static RuntimeModuleSourceSet stageSourceSet(RuntimeModuleSourceSet original, Path stagedWorkspace) throws IOException {
		Files.createDirectories(stagedWorkspace);
		long total = 0;
		for (Path source : original.sources()) {
			if (Files.isSymbolicLink(source) || !Files.isRegularFile(source)) {
				throw new IOException("Module source changed to a non-regular file during staging: " + source);
			}
			Path relative = original.workspace().relativize(source);
			Path target = stagedWorkspace.resolve(relative).normalize();
			if (!target.startsWith(stagedWorkspace)) throw new IOException("Module source path escaped staging directory");
			Files.createDirectories(target.getParent());
			long remaining = RuntimeModuleSourceSet.MAX_TOTAL_BYTES - total;
			if (Files.size(source) > remaining) throw new IOException("Module source set exceeded the 2 MiB staging limit");
			long copied = 0;
			try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
				OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = input.read(buffer)) != -1) {
					copied += read;
					if (copied > remaining) throw new IOException("Module source set exceeded the 2 MiB staging limit");
					output.write(buffer, 0, read);
				}
			}
			total += copied;
		}
		RuntimeModuleSourceSet staged = RuntimeModuleSourceSet.capture(stagedWorkspace);
		if (!staged.sha256().equals(original.sha256())) {
			throw new IOException("Module source files changed after hash confirmation; review and confirm the new hash");
		}
		return staged;
	}

	private void closeLoaded(RuntimeModules loaded) {
		if (loaded == null) return;
		closeLoader(loaded.loader());
		deleteRecursively(loaded.outputRoot());
	}

	private void cleanupStaleBuilds(Path keep) {
		try {
			if (Files.notExists(buildRoot)) return;
			try (var stream = Files.list(buildRoot)) {
				stream.filter(Files::isDirectory)
					.filter(path -> keep == null || !path.equals(keep))
					.forEach(JavaModuleRuntime::deleteRecursively);
			}
		} catch (IOException failure) {
			emit(ConsoleLine.warning("Could not clean stale runtime module builds: " + safeMessage(failure)));
		}
	}

	private static void closeLoader(URLClassLoader loader) {
		try { loader.close(); }
		catch (IOException error) { /* best-effort; no live loader references should remain */ }
	}

	private static void deleteRecursively(Path root) {
		if (root == null || Files.notExists(root)) return;
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}
				@Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {
			// Generated class files are best-effort cleanup after the loader closes.
		}
	}

	private void initializeWorkspace() {
		try {
			if (Files.exists(workspace) && Files.isSymbolicLink(workspace)) {
				throw new IOException("External module workspace cannot be a symbolic link: " + workspace);
			}
			Files.createDirectories(workspace);
			if (Files.exists(buildRoot, LinkOption.NOFOLLOW_LINKS)
				&& (Files.isSymbolicLink(buildRoot) || !Files.isDirectory(buildRoot, LinkOption.NOFOLLOW_LINKS))) {
				throw new IOException("Generated module build path must be a regular directory: " + buildRoot);
			}
			Files.createDirectories(buildRoot);
			cleanupStaleBuilds(null);
		} catch (IOException error) {
			throw new IllegalStateException("Could not initialize external Java module workspace", error);
		}
	}

	private void setBusy(boolean value) { synchronized (this) { busy = value; } }
	private void emit(ConsoleLine line) { console.emit(line); }

	@Override public synchronized void close() {
		if (closed) return;
		closed = true;
		if (active == null) return;
		RuntimeModules stopping = active;
		try {
			console.replaceExternalModules(stopping.ids(), List.of());
			active = null;
			closeLoaded(stopping);
		} catch (RuntimeException error) {
			emit(ConsoleLine.warning("Runtime modules remain registered during shutdown because one is still running: " + safeMessage(error)));
		}
	}

	private static String safeMessage(Throwable error) {
		Throwable value = error;
		while (value.getCause() != null && value.getCause() != value) value = value.getCause();
		String message = value.getMessage();
		if (message == null || message.isBlank()) message = value.getClass().getSimpleName();
		message = message.replace('\r', ' ').replace('\n', ' ');
		return message.length() <= 500 ? message : message.substring(0, 500) + "...";
	}

	private record PreparedModules(RuntimeModuleSourceSet sourceSet, Path outputRoot, URLClassLoader loader,
		List<Class<? extends ResearchModule>> types) {
		private PreparedModules {
			types = List.copyOf(types);
		}
	}

	private record RuntimeModules(String sourceHash, Path outputRoot, URLClassLoader loader, List<String> ids) {
		private RuntimeModules {
			ids = List.copyOf(ids);
		}
	}
}
