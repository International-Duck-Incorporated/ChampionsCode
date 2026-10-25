package dev.champions.desktop;

import dev.champions.core.console.ChampionConsole;
import dev.champions.core.java.ModuleFolderWatcher;
import dev.champions.core.java.RuntimeJavaCompiler;
import dev.champions.core.java.RuntimeModuleDiscovery;
import dev.champions.core.java.RuntimeModuleSourceSet;
import dev.champions.core.module.ResearchModule;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The drop-in module compiler for standalone mode.
 *
 * <p>The game process has {@code JavaModuleRuntime} doing this with the game's
 * own classpath. Outside the game the same core classes are used unchanged:
 * identical source bounds (128 files / 2 MiB / no symlinks / {@code .build}
 * excluded), identical deterministic SHA-256, identical discovery rules, and
 * the same "unrestricted Java, not a sandbox" warning.</p>
 */
public final class StandaloneRuntime {
	public record Result(boolean success, String message, List<String> moduleIds) {}

	private final Path folder;
	private final Path buildRoot;
	private final ChampionConsole console;
	private final ModuleFolderWatcher watcher;
	private List<String> loadedIds = List.of();
	private URLClassLoader loader;

	public StandaloneRuntime(Path configDirectory, ChampionConsole console) {
		this.folder = configDirectory.toAbsolutePath().normalize().resolve("modules");
		this.buildRoot = folder.resolve(".build");
		this.console = console;
		this.watcher = new ModuleFolderWatcher(folder);
	}

	public Path folder() {
		return folder;
	}

	public List<String> loadedIds() {
		return loadedIds;
	}

	/** Compiles and registers every {@code .java} file below the drop folder. */
	public Result reload() {
		try {
			Files.createDirectories(folder);
			RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(folder);
			if (sourceSet.sources().isEmpty()) {
				return unload("The module folder contains no .java files");
			}
			Files.createDirectories(buildRoot);
			RuntimeJavaCompiler.CompilationResult compilation =
				RuntimeJavaCompiler.compile(sourceSet.sources(), buildRoot, classpath());
			if (!compilation.success()) {
				return new Result(false, "Compilation failed: " + String.join(" | ", compilation.diagnostics()), loadedIds);
			}
			URLClassLoader nextLoader = new URLClassLoader(new URL[]{buildRoot.toUri().toURL()},
				ResearchModule.class.getClassLoader());
			List<Class<? extends ResearchModule>> types = RuntimeModuleDiscovery.discover(buildRoot, nextLoader);
			List<ResearchModule> instances = new ArrayList<>();
			List<String> ids = new ArrayList<>();
			for (Class<? extends ResearchModule> type : types) {
				ResearchModule module = type.getDeclaredConstructor().newInstance();
				instances.add(module);
				ids.add(module.id());
			}
			console.replaceExternalModules(loadedIds, instances);
			closeLoader();
			loader = nextLoader;
			loadedIds = List.copyOf(ids);
			return new Result(true, "Loaded " + ids.size() + " module(s) from SHA-256 "
				+ sourceSet.sha256().substring(0, 16) + "… (" + sourceSet.sources().size() + " file(s), "
				+ sourceSet.totalBytes() + " bytes): " + String.join(", ", ids), loadedIds);
		} catch (ReflectiveOperationException | IOException | RuntimeException error) {
			return new Result(false, "Load failed: " + error.getClass().getSimpleName() + ": " + error.getMessage(), loadedIds);
		}
	}

	public Result unload(String reason) {
		if (!loadedIds.isEmpty()) console.replaceExternalModules(loadedIds, List.of());
		loadedIds = List.of();
		closeLoader();
		return new Result(true, reason + (reason.endsWith(".") ? "" : ".") + " No external modules are loaded.", List.of());
	}

	/** The local classpath the drop-in sources compile against. */
	private List<Path> classpath() {
		List<Path> paths = new ArrayList<>();
		String classpath = System.getProperty("java.class.path");
		if (classpath != null && !classpath.isBlank()) {
			Arrays.stream(classpath.split(File.pathSeparator))
				.filter(entry -> !entry.isBlank())
				.map(Path::of)
				.filter(Files::exists)
				.forEach(paths::add);
		}
		return paths;
	}

	private void closeLoader() {
		if (loader == null) return;
		try {
			loader.close();
		} catch (IOException ignored) {
			// the classes are already registered; a leak here only matters on reload loops
		}
		loader = null;
	}

	public ModuleFolderWatcher watcher() {
		return watcher;
	}
}
