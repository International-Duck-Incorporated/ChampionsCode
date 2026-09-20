package dev.champions.client.java;

import dev.champions.api.java.ChampionRuntimeAddon;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import dev.champions.core.java.JavaAddonManifest;
import dev.champions.core.java.JavaSourceSet;
import dev.champions.core.java.RuntimeJavaCompiler;
import dev.champions.core.module.ModuleContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Explicitly opted-in, unrestricted runtime Java compiler and addon lifecycle. */
public final class JavaRuntimeManager implements AutoCloseable {
	private final Path configDirectory;
	private final Path workspace;
	private final Path buildRoot;
	private final JavaRuntimeSettings settings;
	private final Supplier<ModuleContext> contextSupplier;
	private final ChampionConsole console;
	private final ExecutorService compilerExecutor;
	private List<String> lastDiagnostics = List.of();
	private LoadedAddon active;
	private boolean busy;
	private boolean closed;

	public JavaRuntimeManager(Path configDirectory, Supplier<ModuleContext> contextSupplier, ChampionConsole console) {
		this.configDirectory = configDirectory.toAbsolutePath().normalize();
		this.workspace = this.configDirectory.resolve("java");
		this.buildRoot = workspace.resolve(".build");
		this.contextSupplier = contextSupplier;
		this.console = console;
		this.settings = new JavaRuntimeSettings(this.configDirectory);
		this.compilerExecutor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "champions-java-compiler");
			thread.setDaemon(true);
			return thread;
		});
		initializeWorkspace();
	}

	public synchronized List<String> editableFiles() throws IOException {
		if (Files.notExists(workspace)) return List.of();
		try (var paths = Files.walk(workspace)) {
			return paths
				.filter(Files::isRegularFile)
				.filter(path -> !path.startsWith(buildRoot))
				.filter(path -> path.getFileName().toString().endsWith(".java")
					|| path.equals(workspace.resolve("champion-java.json")))
				.map(path -> workspace.relativize(path).toString().replace('\\', '/'))
				.sorted()
				.toList();
		}
	}

	public synchronized String readEditableFile(String relative) throws IOException {
		Path path = editablePath(relative);
		if (!Files.isRegularFile(path)) throw new IOException("Runtime Java file does not exist: " + relative);
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	public synchronized void saveEditableFile(String relative, String content) throws IOException {
		Path path = editablePath(relative);
		String value = content == null ? "" : content;
		if (value.getBytes(StandardCharsets.UTF_8).length > 512 * 1024) {
			throw new IOException("Runtime Java editor limit is 512 KiB per file");
		}
		Files.createDirectories(path.getParent());
		boolean existed = Files.exists(path);
		String previous = existed ? Files.readString(path, StandardCharsets.UTF_8) : "";
		Files.writeString(path, value, StandardCharsets.UTF_8);
		JavaSourceSet sourceSet;
		try {
			sourceSet = JavaSourceSet.capture(workspace);
		} catch (IOException | RuntimeException error) {
			if (existed) Files.writeString(path, previous, StandardCharsets.UTF_8);
			else Files.deleteIfExists(path);
			throw new IOException("Save rejected because the workspace would be invalid: " + safeMessage(error), error);
		}
		emit(ConsoleLine.success("Saved runtime Java file " + relative));
		emit(ConsoleLine.warning("Review and confirm the new source hash before compiling: " + sourceSet.sha256()));
		console.recordAudit("java_source_edit", "client", sourceSet.manifest().id(), "saved",
			relative + " hash=" + sourceSet.sha256());
	}

	public synchronized String currentSourceHash() throws IOException {
		return JavaSourceSet.capture(workspace).sha256();
	}

	private Path editablePath(String relative) throws IOException {
		if (relative == null || relative.isBlank()) throw new IOException("Runtime Java file name is missing");
		Path path = workspace.resolve(relative).normalize();
		if (!path.startsWith(workspace) || path.startsWith(buildRoot)) {
			throw new IOException("Runtime Java file escaped the editable workspace");
		}
		boolean editable = path.getFileName().toString().endsWith(".java")
			|| path.equals(workspace.resolve("champion-java.json"));
		if (!editable) throw new IOException("Only .java files and champion-java.json are editable");
		return path;
	}

	public synchronized void submit(String rawCommand) {
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
				case "classpath" -> classpath();
				case "hash" -> hash();
				case "confirm" -> confirm(argument);
				case "settings" -> settings(argument);
				case "compile" -> beginCompile(false);
				case "start", "run", "reload" -> beginCompile(true);
				case "stop" -> stopActive("manual");
				case "diagnostics" -> diagnostics();
				default -> emit(ConsoleLine.error("Unknown Java runtime command: " + operation + ". Try java help."));
			}
		} catch (Exception e) {
			emit(ConsoleLine.error("Java runtime command failed: " + safeMessage(e)));
		}
	}

	private void help() {
		emit(ConsoleLine.accent("Runtime Java addon commands"));
		emit(ConsoleLine.info("  java status | sources | classpath | hash | diagnostics"));
		emit(ConsoleLine.info("  java settings | settings reload"));
		emit(ConsoleLine.info("  java confirm <current-sha256>"));
		emit(ConsoleLine.info("  java compile | start | reload | stop"));
		emit(ConsoleLine.warning("This feature executes unrestricted Java inside Minecraft. It is not sandboxed."));
	}

	private void status() {
		settings.reload();
		emit(ConsoleLine.accent("Runtime Java status"));
		emit(ConsoleLine.info("  compiler=" + (RuntimeJavaCompiler.available() ? "JDK available" : "unavailable")));
		emit(ConsoleLine.info("  developer_mode=" + settings.developerMode()));
		emit(ConsoleLine.info("  settings=" + settings.file()));
		emit(ConsoleLine.info("  workspace=" + workspace));
		try {
			JavaSourceSet sourceSet = JavaSourceSet.capture(workspace);
			emit(ConsoleLine.info("  source_hash=" + sourceSet.sha256()));
			emit(ConsoleLine.info("  hash_confirmed=" + sourceSet.sha256().equals(settings.confirmedSourceHash())));
			emit(ConsoleLine.info("  entrypoint=" + sourceSet.manifest().entrypoint()));
		} catch (IOException e) {
			emit(ConsoleLine.error("  source_error=" + safeMessage(e)));
		}
		emit(ConsoleLine.info("  state=" + (busy ? "compiling" : active == null ? "inactive" : "active")));
		if (active != null) emit(ConsoleLine.info("  active=" + active.manifest().id() + "@" + active.manifest().version() + " hash=" + active.sourceHash()));
	}

	private void sources() throws IOException {
		JavaSourceSet sourceSet = JavaSourceSet.capture(workspace);
		emit(ConsoleLine.accent("Runtime Java sources"));
		emit(ConsoleLine.info("  champion-java.json"));
		for (Path source : sourceSet.sources()) emit(ConsoleLine.info("  " + workspace.relativize(source).toString().replace('\\', '/')));
		emit(ConsoleLine.muted("  " + sourceSet.sources().size() + " file(s), " + sourceSet.totalBytes() + " byte(s)"));
	}

	private void classpath() {
		List<Path> entries = runtimeClasspath();
		emit(ConsoleLine.accent("Runtime Java compiler classpath"));
		for (Path entry : entries) emit(ConsoleLine.info("  " + entry));
		emit(ConsoleLine.muted("  " + entries.size() + " classpath entries"));
	}

	private void hash() throws IOException {
		JavaSourceSet sourceSet = JavaSourceSet.capture(workspace);
		emit(ConsoleLine.accent("Current source-set SHA-256"));
		emit(ConsoleLine.info(sourceSet.sha256()));
		emit(ConsoleLine.muted("Confirm exactly this hash before compiling changed sources."));
	}

	private void confirm(String supplied) throws IOException {
		settings.reload();
		if (!settings.developerMode()) {
			emit(ConsoleLine.error("Developer mode is disabled. Set developerMode=true in " + settings.file()));
			return;
		}
		JavaSourceSet sourceSet = JavaSourceSet.capture(workspace);
		if (!sourceSet.sha256().equalsIgnoreCase(supplied)) {
			emit(ConsoleLine.error("Confirmation denied. The supplied hash does not equal the current source hash."));
			emit(ConsoleLine.info("Current hash: " + sourceSet.sha256()));
			return;
		}
		settings.confirm(sourceSet.sha256());
		emit(ConsoleLine.success("Confirmed runtime Java source hash " + sourceSet.sha256()));
		console.recordAudit("java_hash_confirmed", "client", sourceSet.manifest().id(), "confirmed", sourceSet.sha256());
	}

	private void settings(String argument) {
		if (argument.equalsIgnoreCase("reload")) {
			settings.reload();
			emit(ConsoleLine.success("Reloaded runtime Java settings. developer_mode=" + settings.developerMode()));
		} else {
			emit(ConsoleLine.info("Runtime Java settings: " + settings.file()));
			emit(ConsoleLine.muted("Set developerMode=true manually, then run: java settings reload"));
		}
	}

	private void diagnostics() {
		if (lastDiagnostics.isEmpty()) {
			emit(ConsoleLine.muted("No compiler diagnostics are available."));
			return;
		}
		emit(ConsoleLine.accent("Last runtime Java compiler diagnostics"));
		lastDiagnostics.forEach(value -> emit(ConsoleLine.info(value)));
	}

	private void beginCompile(boolean activate) throws IOException {
		if (closed) { emit(ConsoleLine.error("Runtime Java manager is closed.")); return; }
		if (busy) { emit(ConsoleLine.error("A runtime Java compilation is already in progress.")); return; }
		settings.reload();
		JavaSourceSet sourceSet = JavaSourceSet.capture(workspace);
		if (!settings.developerMode()) {
			emit(ConsoleLine.error("Developer mode is disabled. Edit " + settings.file() + " and set developerMode=true."));
			return;
		}
		if (!sourceSet.sha256().equals(settings.confirmedSourceHash())) {
			emit(ConsoleLine.error("Source hash is not confirmed. Review the files, then run:"));
			emit(ConsoleLine.warning("  java confirm " + sourceSet.sha256()));
			return;
		}
		if (!RuntimeJavaCompiler.available()) {
			emit(ConsoleLine.error("No system Java compiler is available. Configure Minecraft to launch with a full JDK 25."));
			return;
		}

		busy = true;
		emit(ConsoleLine.warning("Compiling unrestricted runtime Java: " + sourceSet.manifest().id() + " hash=" + sourceSet.sha256()));
		console.recordAudit("java_compile", "client", sourceSet.manifest().id(), "started", sourceSet.sha256());
		compilerExecutor.execute(() -> compile(sourceSet, activate));
	}

	private void compile(JavaSourceSet sourceSet, boolean activate) {
		Path bundleRoot = buildRoot.resolve(Instant.now().toEpochMilli() + "-" + sourceSet.sha256().substring(0, 12));
		Path output = bundleRoot.resolve("classes");
		try {
			JavaSourceSet stagedSourceSet = stageSourceSet(sourceSet, bundleRoot.resolve("sources"));
			RuntimeJavaCompiler.CompilationResult result = RuntimeJavaCompiler.compile(stagedSourceSet, output, runtimeClasspath());
			synchronized (this) { lastDiagnostics = result.diagnostics(); }
			if (!result.success()) {
				emit(ConsoleLine.error("Runtime Java compilation failed. Run java diagnostics for details."));
				result.diagnostics().stream().limit(8).forEach(value -> emit(ConsoleLine.error(value)));
				console.recordAudit("java_compile", "client", sourceSet.manifest().id(), "error", "Compilation failed");
				deleteRecursively(bundleRoot);
				setBusy(false);
				return;
			}
			URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, ChampionRuntimeAddon.class.getClassLoader());
			Class<?> type;
			try {
				type = Class.forName(sourceSet.manifest().entrypoint(), false, loader);
				if (!ChampionRuntimeAddon.class.isAssignableFrom(type)) throw new IllegalArgumentException("Entrypoint does not implement ChampionRuntimeAddon");
			} catch (Throwable error) {
				loader.close();
				throw error;
			}
			if (!activate) {
				loader.close();
				emit(ConsoleLine.success("Runtime Java compilation and entrypoint validation succeeded for " + sourceSet.manifest().entrypoint()));
				console.recordAudit("java_compile", "client", sourceSet.manifest().id(), "completed", sourceSet.sha256());
				deleteRecursively(bundleRoot);
				setBusy(false);
				return;
			}
			PreparedAddon prepared = new PreparedAddon(sourceSet, bundleRoot, loader, type.asSubclass(ChampionRuntimeAddon.class));
			Minecraft.getInstance().execute(() -> activate(prepared));
		} catch (Throwable error) {
			emit(ConsoleLine.error("Runtime Java load failed: " + safeMessage(error)));
			console.recordAudit("java_compile", "client", sourceSet.manifest().id(), "error", safeMessage(error));
			deleteRecursively(bundleRoot);
			setBusy(false);
		}
	}

	private synchronized void activate(PreparedAddon prepared) {
		ChampionJavaContextImpl context = null;
		try {
			if (closed) throw new IllegalStateException("Runtime Java manager closed before activation");
			settings.reload();
			if (!settings.developerMode() || !prepared.sourceSet().sha256().equals(settings.confirmedSourceHash())) {
				throw new IllegalStateException("Developer mode or source confirmation was revoked before activation");
			}
			ChampionRuntimeAddon instance = prepared.type().getDeclaredConstructor().newInstance();
			stopActive("reload");
			context = new ChampionJavaContextImpl(contextSupplier, workspace, prepared.sourceSet().sha256(), console::emit);
			instance.start(context);
			active = new LoadedAddon(prepared.sourceSet().manifest(), prepared.sourceSet().sha256(), prepared.outputRoot(), prepared.loader(), instance, context);
			emit(ConsoleLine.success("Started runtime Java addon " + active.manifest().id() + "@" + active.manifest().version()));
			console.recordAudit("java_addon", "client", active.manifest().id(), "started", active.sourceHash());
			cleanupStaleBuilds(active.outputRoot());
		} catch (Throwable error) {
			if (context != null) context.runCleanup();
			try { prepared.loader().close(); } catch (IOException ignored) {}
			deleteRecursively(prepared.outputRoot());
			emit(ConsoleLine.error("Runtime Java start failed: " + safeMessage(error)));
			console.recordAudit("java_addon", "client", prepared.sourceSet().manifest().id(), "error", safeMessage(error));
		} finally {
			busy = false;
		}
	}

	private synchronized void stopActive(String reason) {
		if (active == null) {
			if (reason.equals("manual")) emit(ConsoleLine.muted("No runtime Java addon is active."));
			return;
		}
		LoadedAddon stopping = active;
		active = null;
		List<Throwable> failures = new ArrayList<>();
		try { stopping.instance().stop(); }
		catch (Throwable error) { failures.add(error); }
		failures.addAll(stopping.context().runCleanup());
		try { stopping.loader().close(); }
		catch (IOException error) { failures.add(error); }
		deleteRecursively(stopping.outputRoot());
		if (failures.isEmpty()) emit(ConsoleLine.success("Stopped runtime Java addon " + stopping.manifest().id()));
		else emit(ConsoleLine.warning("Stopped runtime Java addon with " + failures.size() + " cleanup error(s): " + safeMessage(failures.getFirst())));
		console.recordAudit("java_addon", "client", stopping.manifest().id(), failures.isEmpty() ? "stopped" : "cleanup_error", reason);
	}

	private static JavaSourceSet stageSourceSet(JavaSourceSet original, Path stagedWorkspace) throws IOException {
		Files.createDirectories(stagedWorkspace);
		Files.copy(original.manifestFile(), stagedWorkspace.resolve("champion-java.json"));
		for (Path source : original.sources()) {
			Path relative = original.workspace().relativize(source);
			Path target = stagedWorkspace.resolve(relative).normalize();
			if (!target.startsWith(stagedWorkspace)) throw new IOException("Source path escaped staging directory");
			Files.createDirectories(target.getParent());
			Files.copy(source, target);
		}
		JavaSourceSet staged = JavaSourceSet.capture(stagedWorkspace);
		if (!staged.sha256().equals(original.sha256())) {
			throw new IOException("Source files changed after hash confirmation; review and confirm the new hash");
		}
		return staged;
	}

	private List<Path> runtimeClasspath() {
		Set<Path> result = new LinkedHashSet<>();
		String raw = System.getProperty("java.class.path", "");
		for (String entry : raw.split(java.util.regex.Pattern.quote(File.pathSeparator))) addClasspath(result, Path.of(entry));
		FabricLoader.getInstance().getAllMods().forEach(mod -> mod.getOrigin().getPaths().forEach(path -> addClasspath(result, path)));
		addCodeSource(result, ChampionRuntimeAddon.class);
		addCodeSource(result, Minecraft.class);
		addCodeSource(result, FabricLoader.class);
		return List.copyOf(result);
	}

	private void addCodeSource(Set<Path> paths, Class<?> type) {
		try {
			CodeSource source = type.getProtectionDomain().getCodeSource();
			if (source != null) addClasspath(paths, Path.of(source.getLocation().toURI()));
		} catch (Exception ignored) {}
	}

	private void addClasspath(Set<Path> values, Path candidate) {
		try {
			if (!candidate.getFileSystem().equals(FileSystems.getDefault())) {
				if (!Files.isRegularFile(candidate) || !candidate.toString().endsWith(".jar")) return;
				Path cache = buildRoot.resolve(".classpath");
				Files.createDirectories(cache);
				String key = Integer.toUnsignedString(candidate.toUri().toString().hashCode(), 16);
				Path extracted = cache.resolve(key + "-" + candidate.getFileName());
				Files.copy(candidate, extracted, StandardCopyOption.REPLACE_EXISTING);
				values.add(extracted.toAbsolutePath().normalize());
				return;
			}
			Path path = candidate.toAbsolutePath().normalize();
			if (Files.exists(path) && (Files.isDirectory(path) || path.toString().endsWith(".jar"))) values.add(path);
		} catch (IOException | RuntimeException ignored) {}
	}

	private void initializeWorkspace() {
		try {
			Files.createDirectories(workspace);
			Path manifest = workspace.resolve("champion-java.json");
			if (Files.notExists(manifest)) Files.writeString(manifest, """
				{
				  "id": "local-runtime",
				  "name": "Local Runtime Addon",
				  "version": "0.1.0",
				  "entrypoint": "champions.runtime.Example"
				}
				""", StandardCharsets.UTF_8);
			Path example = workspace.resolve("Example.java");
			if (Files.notExists(example)) Files.writeString(example, """
				package champions.runtime;

				import dev.champions.api.java.ChampionJavaContext;
				import dev.champions.api.java.ChampionRuntimeAddon;

				public final class Example implements ChampionRuntimeAddon {
				    @Override
				    public void start(ChampionJavaContext context) {
				        context.info("Runtime Java started for " + context.snapshot().serverAddress());
				        context.onStop(() -> context.info("Runtime Java cleanup completed"));
				    }

				    @Override
				    public void stop() {
				        // Release listeners, threads, and other resources here.
				    }
				}
				""", StandardCharsets.UTF_8);
			Files.createDirectories(buildRoot);
			cleanupStaleBuilds(null);
		} catch (IOException e) {
			throw new IllegalStateException("Could not initialize runtime Java workspace", e);
		}
	}

	private void cleanupStaleBuilds(Path keep) {
		try {
			if (Files.notExists(buildRoot)) return;
			try (var stream = Files.list(buildRoot)) {
				stream.filter(Files::isDirectory)
					.filter(path -> keep == null || !path.equals(keep))
					.forEach(JavaRuntimeManager::deleteRecursively);
			}
		} catch (IOException ignored) {}
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
		} catch (IOException ignored) {}
	}

	private synchronized void setBusy(boolean value) { busy = value; }
	private void emit(ConsoleLine line) { console.emit(line); }

	@Override public synchronized void close() {
		if (closed) return;
		closed = true;
		stopActive("client_shutdown");
		compilerExecutor.shutdownNow();
	}

	private static String safeMessage(Throwable error) {
		Throwable value = error;
		while (value.getCause() != null && value.getCause() != value) value = value.getCause();
		String message = value.getMessage();
		if (message == null || message.isBlank()) message = value.getClass().getSimpleName();
		message = message.replace('\r', ' ').replace('\n', ' ');
		return message.length() <= 500 ? message : message.substring(0, 500) + "...";
	}

	private record PreparedAddon(JavaSourceSet sourceSet, Path outputRoot, URLClassLoader loader,
		Class<? extends ChampionRuntimeAddon> type) {}

	private record LoadedAddon(JavaAddonManifest manifest, String sourceHash, Path outputRoot,
		URLClassLoader loader, ChampionRuntimeAddon instance, ChampionJavaContextImpl context) {}
}
