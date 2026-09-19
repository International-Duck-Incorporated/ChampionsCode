package dev.champions.core.module.builtin;

import dev.champions.core.module.CommandDescriptor;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.active.ActiveModuleRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPluginEnumeratorModuleTest {
	@Test void silentMarksCandidatesWithoutSendingAnything() {
		AtomicInteger calls = new AtomicInteger();
		ActiveModuleRuntime runtime = request -> {
			calls.incrementAndGet();
			throw new AssertionError("SILENT must not call the active runtime");
		};
		var result = new ChatPluginEnumeratorModule().runActive(context(), Map.of("MODE", "silent"), runtime)
			.toCompletableFuture().join();
		assertEquals(0, calls.get());
		assertTrue(result.findings().stream().anyMatch(finding -> finding.detail().contains("luckperms")));
		assertTrue(result.findings().stream().anyMatch(finding -> finding.detail().contains("SILENT")));
	}

	@Test void aggressiveUsesOnlySafeAdvertisedProbePathsAndFiltersDefaults() {
		List<String> wires = new ArrayList<>();
		ActiveModuleRuntime runtime = request -> {
			wires.add(request.wireCommand());
			String response = request.root().equals("luckperms") ? "LuckPerms version 5.4.151" : "WorldEdit help";
			return CompletableFuture.completedFuture(new ActiveModuleRuntime.ChatTranscript(
				request, List.of(response), ActiveModuleRuntime.Status.COMPLETED, "done"));
		};
		var result = new ChatPluginEnumeratorModule().runActive(context(), Map.of(
			"MODE", "agresive", "TIMEOUT_TICKS", "30", "MAX_MESSAGES", "4", "MAX_COMMANDS", "8", "INCLUDE_RAW", "false"), runtime)
			.toCompletableFuture().join();

		assertEquals(List.of("luckperms version", "worldedit", "worldedit v"), wires);
		assertTrue(wires.stream().noneMatch(command -> command.startsWith("help")));
		assertTrue(result.findings().stream().anyMatch(finding -> finding.detail().contains("5.4.151")));
	}

	@Test void extractsLabeledAndBareVersionTokens() {
		assertEquals("5.4.151", ChatPluginEnumeratorModule.extractVersion("LuckPerms version 5.4.151"));
		assertEquals("7.3.0", ChatPluginEnumeratorModule.extractVersion("WorldEdit 7.3.0"));
	}

	private static ModuleContext context() {
		List<CommandDescriptor> details = List.of(
			new CommandDescriptor("help", true, List.of()),
			new CommandDescriptor("luckperms", true, List.of("version", "user")),
			new CommandDescriptor("worldedit", false, List.of("v"))
		);
		return new ModuleContext(true, false, false, true, "lab.test", "Paper", "26.3", 1, 10, 1,
			"enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1,
			List.of("help", "luckperms", "worldedit"), details);
	}
}
