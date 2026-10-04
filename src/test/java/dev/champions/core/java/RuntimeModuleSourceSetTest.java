package dev.champions.core.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeModuleSourceSetTest {
	@TempDir Path temporary;

	@Test void capturesNestedJavaSourcesAndIgnoresGeneratedBuildDirectory() throws Exception {
		Path workspace = temporary.resolve("modules");
		Files.createDirectories(workspace.resolve("research/server"));
		Files.createDirectories(workspace.resolve(".build/generated"));
		Files.writeString(workspace.resolve("RootModule.java"), "class RootModule {}\n");
		Files.writeString(workspace.resolve("research/server/BrandModule.java"), "package research.server; class BrandModule {}\n");
		Files.writeString(workspace.resolve(".build/generated/Generated.java"), "class Generated {}\n");

		RuntimeModuleSourceSet sourceSet = RuntimeModuleSourceSet.capture(workspace);
		assertEquals(2, sourceSet.sources().size());
		assertEquals(List.of("RootModule.java", "research/server/BrandModule.java"), sourceSet.sources().stream()
			.map(sourceSet.workspace()::relativize).map(path -> path.toString().replace('\\', '/')).toList());
		assertEquals(64, sourceSet.sha256().length());
		assertTrue(sourceSet.totalBytes() > 0);

		Files.writeString(workspace.resolve("research/server/BrandModule.java"), "package research.server; class BrandModule { int v = 2; }\n");
		assertNotEquals(sourceSet.sha256(), RuntimeModuleSourceSet.capture(workspace).sha256());
	}

	@Test void emptyWorkspaceHasStableHash() throws Exception {
		Path workspace = Files.createDirectories(temporary.resolve("empty-modules"));
		RuntimeModuleSourceSet first = RuntimeModuleSourceSet.capture(workspace);
		RuntimeModuleSourceSet second = RuntimeModuleSourceSet.capture(workspace);
		assertTrue(first.sources().isEmpty());
		assertEquals(first.sha256(), second.sha256());
	}

	@Test void rejectsSourceFileSymbolicLinks() throws Exception {
		Path workspace = Files.createDirectories(temporary.resolve("modules"));
		Path outside = temporary.resolve("Outside.java");
		Files.writeString(outside, "class Outside {}\n");
		try {
			Files.createSymbolicLink(workspace.resolve("Linked.java"), outside);
		} catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
			org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable on this platform");
		}
		assertThrows(IOException.class, () -> RuntimeModuleSourceSet.capture(workspace));
	}

	@Test void enforcesTheSourceFileCountLimit() throws Exception {
		Path workspace = Files.createDirectories(temporary.resolve("many-modules"));
		for (int i = 0; i <= RuntimeModuleSourceSet.MAX_SOURCE_FILES; i++) {
			Files.writeString(workspace.resolve("Module" + i + ".java"), "class Module" + i + " {}\n");
		}
		assertThrows(IOException.class, () -> RuntimeModuleSourceSet.capture(workspace));
	}

	@Test void enforcesTheTotalSourceByteLimitBeforeLoadingTheFile() throws Exception {
		Path workspace = Files.createDirectories(temporary.resolve("large-modules"));
		Files.write(workspace.resolve("Large.java"), new byte[(int) RuntimeModuleSourceSet.MAX_TOTAL_BYTES + 1]);
		assertThrows(IOException.class, () -> RuntimeModuleSourceSet.capture(workspace));
	}
}
