package dev.champions.core.module.builtin;

import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyModulePortsTest {
	@Test void pluginRiskReviewUsesAdvertisedRootsAsNonConclusiveLeads() {
		var result = new PluginRiskReviewModule().run(context(List.of("minecraft:help", "geyser", "sk")),
			Map.of("PROFILE", "all", "MAX_FINDINGS", "12"));
		assertTrue(result.findings().stream().anyMatch(finding -> finding.title().contains("Geyser")
			&& finding.detail().contains("not proof")));
		assertTrue(result.findings().stream().anyMatch(finding -> finding.title().contains("Skript")));
		assertTrue(result.findings().stream().noneMatch(finding -> finding.detail().toLowerCase().contains("confirmed vulnerable")));
	}

	@Test void dependencyPostureFlagsSnapshotsConflictsAndUnpinnedEntries() {
		var result = new DependencyPostureModule().run(context(List.of()), Map.of("COMPONENTS",
			"ViaVersion@5.11.0, ViaVersion@5.11.1-SNAPSHOT, Geyser@unknown, CoreProtect@24.0"));
		assertTrue(result.findings().stream().anyMatch(finding -> finding.title().contains("Conflicting")));
		assertTrue(result.findings().stream().anyMatch(finding -> finding.title().contains("Pre-release")));
		assertTrue(result.findings().stream().anyMatch(finding -> finding.title().contains("Missing version")));
	}

	@Test void dependencyParserRejectsUnsafeFreeFormWithoutEchoingIt() {
		var result = new DependencyPostureModule().run(context(List.of()),
			Map.of("COMPONENTS", "GoodPlugin@1.2.3, https://example.invalid/?token=secret value"));
		assertTrue(result.findings().stream().anyMatch(finding -> finding.title().contains("Unparseable")
			&& !finding.detail().contains("secret")));
	}

	@Test void chatNormalizationFoldsDefensiveSignalsWithoutProducingVariants() {
		String sample = "Bаd\u200B W0rd"; // Cyrillic a, invisible separator, and a numeric substitution.
		var normalized = ChatNormalizationAuditModule.normalize(sample, 512);
		assertEquals("bad word", normalized.canonical());
		assertEquals(1, normalized.invisibleCount());
		assertTrue(normalized.mixedLatinCyrillic());
		assertEquals(1, normalized.confusableCount());
		assertEquals(1, normalized.substitutionCount());
	}

	@Test void chatGameReviewClassifiesButDoesNotSolve() {
		String sample = "You have 30 seconds to unreverse the word: `ecar`";
		assertEquals(ChatGameFairnessModule.GameType.UNREVERSE, ChatGameFairnessModule.detect(sample));
		var result = new ChatGameFairnessModule().run(context(List.of()), Map.of("SAMPLE", sample));
		String rendered = result.findings().toString().toLowerCase();
		assertTrue(rendered.contains("no answer was computed"));
		assertFalse(rendered.contains("race"));
	}

	@Test void registryContainsAllBoundedPorts() {
		ModuleRegistry registry = new ModuleRegistry();
		assertTrue(registry.find("auxiliary/audit/plugin_risk_review").isPresent());
		assertTrue(registry.find("auxiliary/audit/dependency_posture").isPresent());
		assertTrue(registry.find("auxiliary/audit/chat_normalization").isPresent());
		assertTrue(registry.find("auxiliary/audit/chat_game_fairness").isPresent());
	}

	private static ModuleContext context(List<String> commands) {
		return new ModuleContext(true, false, false, true, "lab.test", "Paper", "26.3", 1,
			10, 2, "enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 2, 1, commands);
	}
}
