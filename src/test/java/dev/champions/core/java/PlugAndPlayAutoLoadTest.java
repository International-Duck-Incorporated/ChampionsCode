package dev.champions.core.java;

import dev.champions.core.module.ResearchModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end check of the plug-and-play promise at the level the client runtime
 * uses: a .java file written anywhere below the drop folder is discovered,
 * compiled, and instantiated without a manifest, a registration line, or a
 * rebuild - and a later edit or deletion is picked up the same way.
 */
class PlugAndPlayAutoLoadTest {
	private static final long QUIET = 600L;
	@TempDir Path temporary;
	private final AtomicLong clock = new AtomicLong(50_000L);

	@Test void droppedNestedFileLoadsWithoutWiringAndReloadsAndUnloadsOnChange() throws Exception {
		Path workspace = Files.createDirectories(temporary.resolve("config/champions-mod/modules"));
		ModuleFolderWatcher watcher = new ModuleFolderWatcher(workspace, QUIET, clock::get);

		// The user copies a module into a subfolder of the drop folder.
		Path nested = Files.createDirectories(workspace.resolve("my actions/combat"));
		Path source = nested.resolve("DroppedModule.java");
		Files.writeString(source, moduleSource("auxiliary/custom/dropped", "Dropped module"));

		waitForQuiet(watcher);
		Load first = load(workspace);
		assertEquals(List.of("auxiliary/custom/dropped"), first.ids());
		assertTrue(first.hash().matches("[0-9a-f]{64}"));

		// An edit while the game runs is reported as a new state and reloads.
		Files.writeString(source, moduleSource("auxiliary/custom/dropped", "Dropped module, second revision"));
		assertEquals(1, waitForQuiet(watcher).fileCount());
		Load second = load(workspace);
		assertEquals(List.of("auxiliary/custom/dropped"), second.ids());
		assertNotEquals(first.hash(), second.hash(), "edited sources must produce a new source hash");

		// A helper source file next to the module compiles with it, and the total
		// file count the runtime reports includes every file in the tree.
		Files.writeString(nested.resolve("DroppedSupport.java"), "package my.actions.combat;\nfinal class DroppedSupport { static int value() { return 2; } }\n");
		Files.writeString(source, moduleSource("auxiliary/custom/dropped", "Dropped module with helper"));
		assertEquals(2, waitForQuiet(watcher).fileCount());
		assertEquals(List.of("auxiliary/custom/dropped"), load(workspace).ids());

		// Removing every source file leaves the folder empty, which the runtime reads
		// as "unregister what was loaded".
		Files.delete(source);
		Files.delete(nested.resolve("DroppedSupport.java"));
		assertTrue(waitForQuiet(watcher).empty());
		assertTrue(RuntimeModuleSourceSet.capture(workspace).sources().isEmpty());
	}

	@Test void aCompanionHelperFileAloneIsNotAModuleAndIsReported() throws Exception {
		Path workspace = Files.createDirectories(temporary.resolve("modules"));
		Files.writeString(workspace.resolve("Helper.java"), "final class Helper { static int value() { return 1; } }\n");
		assertThrows(java.io.IOException.class, () -> load(workspace),
			"a drop folder without any ResearchModule must fail with a clear error instead of loading nothing silently");
	}

	@Test void fingerprintTracksDeeplyNestedEditsAndIgnoresGeneratedOutput() throws Exception {
		Path workspace = Files.createDirectories(temporary.resolve("modules"));
		Path deep = Files.createDirectories(workspace.resolve("a/b/c/d"));
		Files.writeString(deep.resolve("Deep.java"), "package a.b.c.d;\nclass Deep {}\n");
		RuntimeModuleSourceSet.FolderFingerprint before = RuntimeModuleSourceSet.fingerprint(workspace);
		assertEquals(1, before.fileCount());

		Files.createDirectories(workspace.resolve(".build/2026/classes"));
		Files.writeString(workspace.resolve(".build/2026/classes/Deep.class"), "not really a class file\n");
		assertEquals(before, RuntimeModuleSourceSet.fingerprint(workspace), "generated .build output must not affect the fingerprint");

		Files.writeString(deep.resolve("Deep.java"), "package a.b.c.d;\nclass Deep { int changed = 1; }\n");
		assertNotEquals(before, RuntimeModuleSourceSet.fingerprint(workspace), "an edit four folders deep must be detected");
	}

	/**
	 * Models the client tick loop: poll, advance one interval, poll. The watcher
	 * only reports a change once the folder has stayed unchanged for the quiet
	 * period, so a change is visible on a later poll than the one that saw it.
	 */
	private RuntimeModuleSourceSet.FolderFingerprint waitForQuiet(ModuleFolderWatcher watcher) throws Exception {
		for (int attempt = 0; attempt < 4; attempt++) {
			clock.addAndGet(1_000L);
			var reported = watcher.poll();
			if (reported.isPresent()) return reported.get();
		}
		throw new AssertionError("the drop folder change should have been reported");
	}

	/** Mirrors what JavaModuleRuntime does after the watcher reports a change. */
	private Load load(Path workspace) throws Exception {
		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		Path classes = Files.createDirectories(
			Files.createTempDirectory(Files.createDirectories(temporary.resolve("classes")), "bundle").resolve("classes"));
		RuntimeJavaCompiler.CompilationResult result = RuntimeJavaCompiler.compile(sourceSet.sources(), classes, classpath());
		assertTrue(result.success(), () -> String.join("\n", result.diagnostics()));
		try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, ResearchModule.class.getClassLoader())) {
			List<String> ids = RuntimeModuleDiscovery.discover(classes, loader).stream()
				.map(type -> {
					try {
						return type.getConstructor().newInstance().id();
					} catch (ReflectiveOperationException error) {
						throw new AssertionError(error);
					}
				})
				.toList();
			return new Load(ids, sourceSet.sha256());
		}
	}

	private static List<Path> classpath() {
		return Arrays.stream(System.getProperty("java.class.path", "")
				.split(java.util.regex.Pattern.quote(File.pathSeparator)))
			.filter(value -> !value.isBlank())
			.map(Path::of)
			.filter(Files::exists)
			.toList();
	}

	private static String moduleSource(String id, String name) {
		return """
			package my.actions.combat;
			import dev.champions.core.module.Finding;
			import dev.champions.core.module.ModuleContext;
			import dev.champions.core.module.ModuleOption;
			import dev.champions.core.module.ModuleResult;
			import dev.champions.core.module.ResearchModule;
			import java.util.List;
			import java.util.Map;
			public final class DroppedModule implements ResearchModule {
			    public String id() { return "%s"; }
			    public String name() { return "%s"; }
			    public String description() { return "Dropped straight into the config folder."; }
			    public List<ModuleOption> options() { return List.of(); }
			    public ModuleResult run(ModuleContext context, Map<String, String> options) {
			        return new ModuleResult("reviewed", List.of(new Finding(Finding.Severity.INFO, "Session", "ok", "observe only")));
			    }
			}
			""".formatted(id, name);
	}

	private record Load(List<String> ids, String hash) {
		private Load {
			ids = List.copyOf(ids);
		}
	}
}
