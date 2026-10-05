package dev.champions.client.java;

import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import dev.champions.core.java.ModuleFolderWatcher;
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

/**
 * Plug-and-play compiler and lifecycle for ResearchModule source files outside src/.
 *
 * <p>With plug-and-play enabled (the default) every `.java` file below the module
 * drop folder, at any depth, is compiled and registered automatically: once at
 * startup and, unless the folder watcher is told otherwise, again whenever the
 * folder changes. No hash confirmation and no rebuild are involved. When the
 * user turns it off, the previous manual workflow applies unchanged: opt in with
 * `developerMode=true`, review the files, confirm the printed SHA-256, then run
 * `java modules reload`.</p>
 */
final class JavaModuleRuntime implements AutoCloseable {
	private static final String AUDIT_ID = "external-java-modules";
	private static final Pattern MODULE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*");
	private static final Pattern OPTION_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
	/** The drop folder is polled once per second, which is far below any perceptible delay. */
	private static final int POLL_INTERVAL_TICKS = 20;
	private final Path workspace;
	private final Path buildRoot;
	private final JavaRuntimeSettings settings;
	private final ChampionConsole console;
	private final Executor compilerExecutor;
	private final Supplier<List<Path>> classpathSupplier;
	private final ModuleFolderWatcher watcher;
	private RuntimeModules active;
	private List<String> lastDiagnostics = List.of();
	/** Fingerprint of a state that already failed, so a broken file is not retried every poll. */
	private RuntimeModuleSourceSet.FolderFingerprint failedFingerprint;
	/** Hash the user explicitly unloaded, so plug-and-play does not immediately load it again. */
	private String suppressedHash;
	/** Fingerprint being compiled right now, used to record a failed state accurately. */
	private RuntimeModuleSourceSet.FolderFingerprint attemptFingerprint;
	private int tickCounter;
	private int autoLoads;
	private boolean autoAnnounced;
	private boolean autoLoadAttempted;
	private boolean compilerWarningEmitted;
	private boolean runtimeWarningEmitted;
	private boolean deferralNoticeEmitted;
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
		this.watcher = new ModuleFolderWatcher(this.workspace);
		initializeWorkspace();
	}

	Path workspace() { return workspace; }

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
				case "auto", "plugandplay", "plug-and-play" -> auto(argument);
				case "folder", "path", "where" -> folder();
				case "compile" -> beginCompile(false, false);
				case "reload", "load", "start" -> beginCompile(true, false);
				case "unload", "stop" -> unload();
				case "diagnostics" -> diagnostics();
				default -> emit(ConsoleLine.error("Unknown module command: " + operation + ". Try java modules help."));
			}
		} catch (Exception error) {
			emit(ConsoleLine.error("Runtime module command failed: " + safeMessage(error)));
		}
	}

	private synchronized void help() {
		try {
			settings.reload();
		} catch (RuntimeException ignored) {
			// Options below fall back to the last values read; commands report read failures.
		}
		emit(ConsoleLine.accent("External Java research modules"));
		emit(ConsoleLine.info("  java modules status | sources | folder | diagnostics"));
		emit(ConsoleLine.info("  java modules auto | auto on | auto off | auto now | auto watch on|off"));
		emit(ConsoleLine.info("  java modules compile | reload | unload"));
		emit(ConsoleLine.info("  java modules hash | confirm <current-sha256>   (manual mode only)"));
		emit(ConsoleLine.muted("Drop .java files anywhere below " + workspace + " (subfolders are searched recursively)."));
		if (settings.plugAndPlay()) {
			emit(ConsoleLine.success("Plug-and-play is on: files are compiled and registered automatically."));
		} else {
			emit(ConsoleLine.warning("Plug-and-play is off: review the files, then confirm the printed hash before loading."));
		}
		emit(ConsoleLine.warning("External Java is unrestricted code in Minecraft, not a sandbox. Review every file."));
	}

	private synchronized void folder() throws IOException {
		RuntimeModuleSourceSet.FolderFingerprint fingerprint = RuntimeModuleSourceSet.fingerprint(workspace);
		emit(ConsoleLine.accent("Module drop folder"));
		emit(ConsoleLine.info("  " + workspace));
		emit(ConsoleLine.info("  exists=" + !fingerprint.absent()
			+ " source_files=" + fingerprint.fileCount() + " bytes=" + fingerprint.totalBytes()));
		emit(ConsoleLine.muted("  Any .java file below this folder, at any depth, is discovered automatically."));
	}

	private synchronized void auto(String argument) throws IOException {
		String value = argument == null ? "" : argument.strip().toLowerCase(Locale.ROOT);
		switch (value) {
			case "" -> autoStatus();
			case "on", "enable", "true", "start" -> setPlugAndPlay(true);
			case "off", "disable", "false", "stop" -> setPlugAndPlay(false);
			case "now", "reload", "sync", "load" -> forceReconcile();
			case "watch on", "watch true", "autoreload on" -> setAutoReload(true);
			case "watch off", "watch false", "autoreload off" -> setAutoReload(false);
			default -> {
				emit(ConsoleLine.error("Unknown auto option: " + value));
				emit(ConsoleLine.info("  java modules auto | auto on | auto off | auto now | auto watch on|off"));
			}
		}
	}

	private synchronized void autoStatus() {
		settings.reload();
		emit(ConsoleLine.accent("Plug-and-play modules"));
		emit(ConsoleLine.info("  plug_and_play=" + settings.plugAndPlay() + "  auto_reload=" + settings.autoReload()));
		emit(ConsoleLine.info("  folder=" + workspace));
		emit(ConsoleLine.info("  watching=" + watching()));
		emit(ConsoleLine.info("  auto_loads=" + autoLoads + "  loaded=" + (active == null ? "none" : String.join(", ", active.ids()))));
		if (failedFingerprint != null) {
			emit(ConsoleLine.warning("  failed_state=the current files failed to load; fix them, or run java modules auto now"));
		}
		if (suppressedHash != null) {
			emit(ConsoleLine.info("  unloaded_hash=" + shortHash(suppressedHash) + " (reloads when the files change)"));
		}
		emit(ConsoleLine.info("  settings=" + settings.file()));
		emit(ConsoleLine.muted("  Drops are picked up within about two seconds; nothing has to be typed."));
	}

	private synchronized void setPlugAndPlay(boolean enabled) throws IOException {
		settings.setPlugAndPlay(enabled);
		failedFingerprint = null;
		suppressedHash = null;
		deferralNoticeEmitted = false;
		console.recordAudit("java_module_plug_and_play", "client", AUDIT_ID, enabled ? "enabled" : "disabled", workspace.toString());
		if (!enabled) {
			emit(ConsoleLine.warning("Plug-and-play is off. Already loaded modules stay registered; the drop folder is no longer watched."));
			emit(ConsoleLine.info("  Use java modules hash + java modules confirm <sha256> + java modules reload to load changes manually."));
			return;
		}
		emit(ConsoleLine.success("Plug-and-play is on: .java files below the drop folder are compiled and registered automatically."));
		emit(ConsoleLine.warning("  Only keep files there that you wrote or have fully reviewed; they run with the permissions of Minecraft."));
		watcher.invalidate();
		autoLoadAttempted = false;
	}

	private synchronized void setAutoReload(boolean enabled) throws IOException {
		settings.setAutoReload(enabled);
		console.recordAudit("java_module_auto_reload", "client", AUDIT_ID, enabled ? "enabled" : "disabled", workspace.toString());
		if (enabled) {
			emit(ConsoleLine.success("The drop folder is watched: changes load automatically while the game runs."));
			watcher.invalidate();
		} else {
			emit(ConsoleLine.success("Automatic reload is off. Modules load once at startup; use java modules auto now for a manual reload."));
		}
	}

	/** Re-reads the folder immediately instead of waiting for the next poll. */
	private synchronized void forceReconcile() throws IOException {
		settings.reload();
		if (!settings.plugAndPlay()) {
			emit(ConsoleLine.error("Plug-and-play is off. Run java modules reload after confirming the current hash."));
			return;
		}
		failedFingerprint = null;
		suppressedHash = null;
		deferralNoticeEmitted = false;
		reconcile(RuntimeModuleSourceSet.fingerprint(workspace), true);
	}

	private synchronized void status() throws IOException {
		settings.reload();
		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		emit(ConsoleLine.accent("External Java module status"));
		emit(ConsoleLine.info("  workspace=" + workspace));
		emit(ConsoleLine.info("  plug_and_play=" + settings.plugAndPlay() + "  auto_reload=" + settings.autoReload()));
		emit(ConsoleLine.info("  watching=" + watching()));
		emit(ConsoleLine.info("  developer_mode=" + settings.developerMode()));
		emit(ConsoleLine.info("  source_files=" + sourceSet.sources().size() + " bytes=" + sourceSet.totalBytes()));
		emit(ConsoleLine.info("  source_hash=" + sourceSet.sha256()));
		if (settings.plugAndPlay()) {
			emit(ConsoleLine.info("  hash_confirmed=automatic (plug-and-play records each loaded hash in the audit log)"));
		} else {
			emit(ConsoleLine.info("  hash_confirmed=" + sourceSet.sha256().equals(settings.confirmedModuleHash())));
		}
		emit(ConsoleLine.info("  state=" + (busy ? "compiling" : active == null ? "inactive" : "loaded")));
		emit(ConsoleLine.info("  auto_loads=" + autoLoads));
		if (active != null) {
			emit(ConsoleLine.info("  loaded_hash=" + active.sourceHash()));
			emit(ConsoleLine.info("  loaded_modules=" + String.join(", ", active.ids())));
			if (!sourceSet.sha256().equals(active.sourceHash())) {
				emit(ConsoleLine.warning(settings.plugAndPlay()
					? "  source files changed; the new files load automatically on the next check."
					: "  source files changed; the previously confirmed modules remain loaded until reload or unload."));
			}
		}
		if (failedFingerprint != null) {
			emit(ConsoleLine.warning("  failed_state=the current files did not load; java modules diagnostics shows the compiler output."));
		}
		if (suppressedHash != null) {
			emit(ConsoleLine.info("  unloaded_hash=" + shortHash(suppressedHash) + " (stays unloaded until the files change)"));
		}
	}

	/**
	 * Runs once per game tick from the client entrypoint. Every second it checks
	 * whether the drop folder changed and, when it did, loads the new state.
	 * This is what makes the folder plug-and-play: the user only ever writes files.
	 */
	synchronized void tick() {
		if (closed) return;
		if (++tickCounter < POLL_INTERVAL_TICKS) return;
		tickCounter = 0;
		try {
			settings.reload();
		} catch (RuntimeException error) {
			if (!runtimeWarningEmitted) {
				runtimeWarningEmitted = true;
				emit(ConsoleLine.warning("Could not read " + settings.file() + ": " + safeMessage(error)));
			}
			return;
		}
		if (!settings.plugAndPlay()) {
			autoAnnounced = false;
			return;
		}
		if (!autoAnnounced) {
			autoAnnounced = true;
			emit(ConsoleLine.accent("Plug-and-play modules"));
			emit(ConsoleLine.info("  folder=" + workspace + " (searched recursively)"));
			emit(ConsoleLine.warning("  .java files dropped there are compiled and registered automatically."));
			emit(ConsoleLine.muted("  java modules status | java modules auto | java modules unload"));
		}
		if (busy) return;
		if (!settings.autoReload() && autoLoadAttempted) return;
		if (!console.runningModuleId().isEmpty()) {
			// Never swap the module group under a run that is still using it. The
			// watcher keeps its pending change, so the next poll picks it up.
			if (!deferralNoticeEmitted) {
				try {
					if (watcher.hasUnreportedChange()) {
						deferralNoticeEmitted = true;
						emit(ConsoleLine.info("The drop folder changed. " + console.runningModuleId()
							+ " is running, so the new files load when it finishes."));
					}
				} catch (IOException ignored) {
					// A folder that cannot be read right now is reported by the poll below.
				}
			}
			return;
		}
		deferralNoticeEmitted = false;
		RuntimeModuleSourceSet.FolderFingerprint change;
		try {
			var polled = watcher.poll();
			if (polled.isEmpty()) return;
			change = polled.get();
		} catch (IOException | RuntimeException error) {
			if (!runtimeWarningEmitted) {
				runtimeWarningEmitted = true;
				emit(ConsoleLine.warning("Could not inspect the module drop folder: " + safeMessage(error)));
			}
			return;
		}
		autoLoadAttempted = true;
		reconcile(change, false);
	}

	/** Brings the registry in line with the folder state that the watcher reported. */
	private synchronized void reconcile(RuntimeModuleSourceSet.FolderFingerprint fingerprint, boolean forced) {
		if (failedFingerprint != null && failedFingerprint.equals(fingerprint)) return;
		if (fingerprint.absent()) {
			if (active != null) {
				emit(ConsoleLine.warning("The module drop folder is gone; the loaded modules stay registered until it returns or you run java modules unload."));
			}
			failedFingerprint = fingerprint;
			return;
		}
		RuntimeModuleSourceSet sourceSet;
		try {
			sourceSet = RuntimeModuleSourceSet.capture(workspace);
		} catch (IOException error) {
			emit(ConsoleLine.error("Could not read the module drop folder: " + safeMessage(error)));
			failedFingerprint = fingerprint;
			return;
		}
		if (suppressedHash != null && suppressedHash.equals(sourceSet.sha256())) return;
		if (active != null && sourceSet.sha256().equals(active.sourceHash())) {
			// The folder matches what is loaded, so whatever failed before is resolved
			// (for example the user reverted a broken edit or deleted a clashing file).
			failedFingerprint = null;
			if (forced) emit(ConsoleLine.muted("The drop folder is unchanged; the loaded modules are already current."));
			return;
		}
		if (sourceSet.sources().isEmpty()) {
			if (active == null) return;
			emit(ConsoleLine.info("No .java files are left in the drop folder; unloading the previous module group."));
			unload();
			return;
		}
		if (!RuntimeJavaCompiler.available()) {
			if (!compilerWarningEmitted) {
				compilerWarningEmitted = true;
				emit(ConsoleLine.error("No system Java compiler is available, so plug-and-play cannot compile the drop folder."));
				emit(ConsoleLine.warning("  Launch Minecraft with a full JDK 25 and restart, or run the mod's own build."));
			}
			failedFingerprint = fingerprint;
			return;
		}
		if (busy) return;
		if (!console.runningModuleId().isEmpty()) {
			emit(ConsoleLine.warning("Not reloading while " + console.runningModuleId()
				+ " is running; the change is applied when it finishes."));
			return;
		}
		emit(ConsoleLine.accent("Drop folder changed: " + sourceSet.sources().size() + " source file(s), hash=" + shortHash(sourceSet.sha256())));
		try {
			beginCompile(true, true);
		} catch (IOException error) {
			emit(ConsoleLine.error("Could not start compiling the drop folder: " + safeMessage(error)));
			failedFingerprint = fingerprint;
		}
	}

	private synchronized boolean watching() {
		return settings.plugAndPlay() && (settings.autoReload() || !autoLoadAttempted);
	}

	private static String shortHash(String hash) {
		return hash == null || hash.length() <= 12 ? String.valueOf(hash) : hash.substring(0, 12);
	}

	private synchronized void sources() throws IOException {
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

	private synchronized void hash() throws IOException {
		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		emit(ConsoleLine.accent("Current external-module source-set SHA-256"));
		emit(ConsoleLine.info(sourceSet.sha256()));
		emit(ConsoleLine.muted("Review the listed files, then confirm this exact hash before compiling or loading."));
	}

	private synchronized void confirm(String supplied) throws IOException {
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

	/**
	 * Starts one compilation of the drop folder.
	 *
	 * @param activate whether to register the compiled modules after a successful build
	 * @param automatic true for a plug-and-play pass, which skips the manual hash
	 *        confirmation because the user already opted in by keeping the files there
	 */
	private synchronized void beginCompile(boolean activate, boolean automatic) throws IOException {
		if (closed) { emit(ConsoleLine.error("Runtime module manager is closed.")); return; }
		if (busy) { emit(ConsoleLine.error("A runtime Java compilation is already in progress.")); return; }
		settings.reload();
		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		if (!settings.plugAndPlay()) {
			if (!settings.developerMode()) {
				emit(ConsoleLine.error("Developer mode is disabled. Edit " + settings.file() + " and set developerMode=true."));
				return;
			}
			if (!sourceSet.sha256().equals(settings.confirmedModuleHash())) {
				emit(ConsoleLine.error("External module source hash is not confirmed. Review the files, then run:"));
				emit(ConsoleLine.warning("  java modules confirm " + sourceSet.sha256()));
				emit(ConsoleLine.info("  Or enable plug-and-play with: java modules auto on"));
				return;
			}
		}
		if (sourceSet.sources().isEmpty()) {
			emit(ConsoleLine.error("No .java files were found below " + workspace));
			return;
		}
		if (!RuntimeJavaCompiler.available()) {
			emit(ConsoleLine.error("No system Java compiler is available. Launch Minecraft with a full JDK 25."));
			return;
		}

		attemptFingerprint = RuntimeModuleSourceSet.fingerprint(workspace);
		failedFingerprint = null;
		busy = true;
		emit(ConsoleLine.warning("Compiling " + sourceSet.sources().size() + " external Java source file(s)"
			+ (automatic ? " (plug-and-play)" : "") + "; source hash=" + sourceSet.sha256()));
		console.recordAudit("java_module_compile", "client", AUDIT_ID, "started",
			sourceSet.sha256() + (automatic ? " auto=true" : ""));
		try {
			compilerExecutor.execute(() -> compile(sourceSet, activate, automatic));
		} catch (RuntimeException error) {
			busy = false;
			attemptFingerprint = null;
			throw error;
		}
	}

	private void compile(RuntimeModuleSourceSet sourceSet, boolean activate, boolean automatic) {
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
				compilationFailed(sourceSet, bundleRoot, result.diagnostics(), automatic);
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

			PreparedModules prepared = new PreparedModules(sourceSet, bundleRoot, loader, types, automatic);
			Minecraft.getInstance().execute(() -> activate(prepared));
			loader = null; // The scheduled client-thread activation now owns it.
		} catch (Throwable error) {
			if (loader != null) closeLoader(loader);
			emit(ConsoleLine.error("Runtime module compilation/load failed: " + safeMessage(error)));
			console.recordAudit("java_module_compile", "client", AUDIT_ID, "error", safeMessage(error));
			recordFailedState();
			deleteRecursively(bundleRoot);
			setBusy(false);
		}
	}

	private void compilationFailed(RuntimeModuleSourceSet sourceSet, Path bundleRoot, List<String> diagnostics, boolean automatic) {
		emit(ConsoleLine.error("Runtime module compilation failed. Fix the reported files"
			+ (automatic ? "; the folder is reloaded on the next change." : ". Run java modules diagnostics for details.")));
		diagnostics.stream().limit(8).forEach(value -> emit(ConsoleLine.error(value)));
		console.recordAudit("java_module_compile", "client", AUDIT_ID, "error",
			"Compilation failed for " + sourceSet.sha256() + (automatic ? " auto=true" : ""));
		recordFailedState();
		deleteRecursively(bundleRoot);
		setBusy(false);
	}

	/**
	 * Remembers a folder state that failed, so plug-and-play does not retry it on
	 * every poll and flood the console. Any edit changes the fingerprint and
	 * clears the block, and the manual commands clear it too.
	 */
	private synchronized void recordFailedState() {
		if (attemptFingerprint != null) failedFingerprint = attemptFingerprint;
		attemptFingerprint = null;
	}

	private synchronized void activate(PreparedModules prepared) {
		boolean swapped = false;
		try {
			if (closed) throw new IllegalStateException("Runtime module manager closed before activation");
			settings.reload();
			if (prepared.automatic()) {
				if (!settings.plugAndPlay()) {
					throw new IllegalStateException("Plug-and-play was disabled before activation");
				}
			} else if (!settings.developerMode() || !prepared.sourceSet().sha256().equals(settings.confirmedModuleHash())) {
				throw new IllegalStateException("Developer mode or source confirmation was revoked before activation");
			}
			RuntimeModuleSourceSet latest = RuntimeModuleSourceSet.capture(workspace);
			if (!latest.sha256().equals(prepared.sourceSet().sha256())) {
				// A file was written while the sources compiled. The watcher already
				// sees the newer state, so let it drive the next pass instead of
				// registering a group that no longer matches the folder.
				closeLoader(prepared.loader());
				deleteRecursively(prepared.outputRoot());
				attemptFingerprint = null;
				emit(ConsoleLine.warning("Module sources changed while compiling; the newer files load next."));
				return;
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
			if (prepared.automatic()) {
				autoLoads++;
				emit(ConsoleLine.success("Plug-and-play loaded " + instances.size() + " external research module(s): "
					+ String.join(", ", replacement.ids())));
				emit(ConsoleLine.muted("  source_hash=" + replacement.sourceHash() + " - now available to show modules, use, clickgui, and categorygui"));
			} else {
				console.emit(ConsoleLine.success("Loaded " + instances.size() + " external research module(s): " + String.join(", ", replacement.ids())));
			}
			console.recordAudit("java_modules", "client", AUDIT_ID, "loaded",
				"source_hash=" + replacement.sourceHash() + " ids=" + String.join(",", replacement.ids())
					+ (prepared.automatic() ? " auto=true" : ""));
			closeLoaded(previous);
			cleanupStaleBuilds(replacement.outputRoot());
		} catch (Throwable error) {
			if (!swapped) {
				closeLoader(prepared.loader());
				deleteRecursively(prepared.outputRoot());
				recordFailedState();
			}
			emit(ConsoleLine.error("Runtime module activation failed: " + safeMessage(error)));
			console.recordAudit("java_modules", "client", AUDIT_ID, "error", safeMessage(error));
		} finally {
			attemptFingerprint = null;
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

	private synchronized void unload() {
		if (busy) {
			emit(ConsoleLine.warning("Wait for the current runtime-module compilation before unloading."));
			return;
		}
		if (active == null) {
			emit(ConsoleLine.muted("No external Java research modules are loaded."));
			suppressedHash = null;
			attemptFingerprint = null;
			failedFingerprint = null;
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
		suppressedHash = stopping.sourceHash();
		attemptFingerprint = null;
		failedFingerprint = null;
		closeLoaded(stopping);
		emit(ConsoleLine.success("Unloaded external research modules: " + String.join(", ", stopping.ids())));
		console.recordAudit("java_modules", "client", AUDIT_ID, "unloaded", "source_hash=" + stopping.sourceHash());
		if (settings.plugAndPlay()) {
			emit(ConsoleLine.muted("  They stay unloaded until the files change; java modules auto now loads them again."));
		}
		try {
			watcher.markHandled();
		} catch (IOException failure) {
			watcher.reset();
		}
	}

	private synchronized void diagnostics() {
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
			writeFolderReadme();
			cleanupStaleBuilds(null);
		} catch (IOException error) {
			throw new IllegalStateException("Could not initialize external Java module workspace", error);
		}
	}

	/**
	 * Leaves a short self-describing note in the drop folder the first time it is
	 * created, so the folder explains itself without opening the documentation.
	 */
	private void writeFolderReadme() throws IOException {
		Path readme = workspace.resolve("README.txt");
		if (Files.exists(readme)) return;
		Files.writeString(readme, """
			ChampionsCode drop-in modules
			=============================

			Put .java files in this folder (or in any subfolder - the whole tree is
			searched recursively) and they are picked up automatically: ChampionsCode
			compiles them in game and lists them in the F8 console, the ClickGUI, and
			the category GUI. Nothing has to be typed and the mod does not need to be
			rebuilt.

			  - the file must contain a public class implementing
			    dev.champions.core.module.ResearchModule (or ExploitResearchModule)
			    with a public no-argument constructor and a unique lower-case id()
			  - helper classes and helper source files in the same folder are fine
			  - generated classes go to the hidden .build folder and are ignored

			WARNING: a drop-in file is unrestricted Java running inside Minecraft with
			the permissions of the game process. It is not sandboxed. Only keep files
			here that you wrote or have fully reviewed, because they run automatically.

			Console commands (F8):
			  java modules status              show folder, state, and loaded modules
			  java modules auto                show plug-and-play state
			  java modules auto off            stop loading automatically (manual mode)
			  java modules auto now            reload the folder immediately
			  java modules unload              unregister the loaded modules

			With plug-and-play off, the manual workflow applies: set developerMode=true
			in ../java-runtime.properties, review every file, then
			java modules hash -> java modules confirm <sha256> -> java modules reload.

			Example: .../src/../../examples/modules/ExampleResearchModule.java in the
			project repository, or the mod's docs/external-java-modules.md.
			""", java.nio.charset.StandardCharsets.UTF_8);
	}

	private void setBusy(boolean value) { synchronized (this) { busy = value; } }
	private void emit(ConsoleLine line) { console.emit(line); }

	@Override public synchronized void close() {
		if (closed) return;
		closed = true;
		watcher.reset();
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
		List<Class<? extends ResearchModule>> types, boolean automatic) {
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
