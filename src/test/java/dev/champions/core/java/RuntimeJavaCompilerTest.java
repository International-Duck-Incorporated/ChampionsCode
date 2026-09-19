package dev.champions.core.java;

import dev.champions.api.java.ChampionRuntimeAddon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeJavaCompilerTest {
	@TempDir Path temporary;

	@Test void capturesHashesAndCompilesRuntimeAddon() throws Exception {
		Files.writeString(temporary.resolve("champion-java.json"), """
			{
			  "id": "test-addon",
			  "name": "Test Addon",
			  "version": "1.0.0",
			  "entrypoint": "test.RuntimeExample"
			}
			""");
		Path source = temporary.resolve("RuntimeExample.java");
		Files.writeString(source, """
			package test;
			import dev.champions.api.java.ChampionJavaContext;
			import dev.champions.api.java.ChampionRuntimeAddon;
			import net.minecraft.client.Minecraft;
			public final class RuntimeExample implements ChampionRuntimeAddon {
			    public void start(ChampionJavaContext context) { context.info("started"); }
			    public Class<?> minecraftType() { return Minecraft.class; }
			}
			""");

		JavaSourceSet first = JavaSourceSet.capture(temporary);
		assertEquals("test-addon", first.manifest().id());
		assertEquals(1, first.sources().size());
		assertEquals(64, first.sha256().length());

		Path output = temporary.resolve("classes");
		List<Path> classpath = Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
			.map(Path::of).filter(Files::exists).toList();
		var result = RuntimeJavaCompiler.compile(first, output, classpath);
		assertTrue(result.success(), () -> String.join("\n", result.diagnostics()));

		try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, ChampionRuntimeAddon.class.getClassLoader())) {
			Class<?> loaded = Class.forName(first.manifest().entrypoint(), false, loader);
			assertTrue(ChampionRuntimeAddon.class.isAssignableFrom(loaded));
			assertInstanceOf(ChampionRuntimeAddon.class, loaded.getDeclaredConstructor().newInstance());
		}

		Files.writeString(source, Files.readString(source) + "\n// changed\n");
		JavaSourceSet changed = JavaSourceSet.capture(temporary);
		assertNotEquals(first.sha256(), changed.sha256());
	}

	@Test void rejectsInvalidManifestAndMissingSources() throws Exception {
		Files.writeString(temporary.resolve("champion-java.json"), "{\"id\":\"INVALID\",\"name\":\"x\",\"version\":\"1\",\"entrypoint\":\"x.Main\"}");
		assertThrows(java.io.IOException.class, () -> JavaSourceSet.capture(temporary));
	}
}
