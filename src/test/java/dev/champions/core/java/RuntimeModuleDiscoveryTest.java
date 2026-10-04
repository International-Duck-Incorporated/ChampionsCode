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

import static org.junit.jupiter.api.Assertions.*;

class RuntimeModuleDiscoveryTest {
	@TempDir Path temporary;

	@Test void compilesAndDiscoversDropInResearchModuleWithoutManifest() throws Exception {
		Path workspace = Files.createDirectories(temporary.resolve("external-modules"));
		Path nested = Files.createDirectories(workspace.resolve("research/example"));
		Path source = nested.resolve("ExampleModule.java");
		Files.writeString(source, """
			package research.example;
			import dev.champions.core.module.Finding;
			import dev.champions.core.module.ModuleContext;
			import dev.champions.core.module.ModuleOption;
			import dev.champions.core.module.ModuleResult;
			import dev.champions.core.module.ResearchModule;
			import java.util.List;
			import java.util.Map;
			public final class ExampleModule implements ResearchModule {
			    public String id() { return "auxiliary/custom/example"; }
			    public String name() { return "External example"; }
			    public String description() { return "Test source loaded without a project rebuild."; }
			    public List<ModuleOption> options() { return List.of(); }
			    public ModuleResult run(ModuleContext context, Map<String, String> options) {
			        return new ModuleResult("loaded", List.of(new Finding(Finding.Severity.PASS, "Test", "ok", "")));
			    }
			}
			""");

		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		Path classes = temporary.resolve("classes");
		List<Path> classpath = Arrays.stream(System.getProperty("java.class.path", "")
			.split(java.util.regex.Pattern.quote(File.pathSeparator)))
			.filter(value -> !value.isBlank()).map(Path::of).filter(Files::exists).toList();
		RuntimeJavaCompiler.CompilationResult result = RuntimeJavaCompiler.compile(sourceSet.sources(), classes, classpath);
		assertTrue(result.success(), () -> String.join("\n", result.diagnostics()));

		try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, ResearchModule.class.getClassLoader())) {
			List<Class<? extends ResearchModule>> discovered = RuntimeModuleDiscovery.discover(classes, loader);
			assertEquals(List.of("research.example.ExampleModule"), discovered.stream().map(Class::getName).toList());
			ResearchModule module = discovered.getFirst().getConstructor().newInstance();
			assertEquals("auxiliary/custom/example", module.id());
			assertTrue(module instanceof ResearchModule);
		}
	}

	@Test void rejectsACompiledBundleWithoutAnyResearchModules() throws Exception {
		Path classes = Files.createDirectories(temporary.resolve("classes"));
		try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, ResearchModule.class.getClassLoader())) {
			assertThrows(java.io.IOException.class, () -> RuntimeModuleDiscovery.discover(classes, loader));
		}
	}
}
