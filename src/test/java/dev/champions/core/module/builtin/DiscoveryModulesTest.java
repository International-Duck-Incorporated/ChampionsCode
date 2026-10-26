package dev.champions.core.module.builtin;

import dev.champions.core.module.CommandDescriptor;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four discovery modules, driven from a fixed command surface.
 *
 * <p>Every one of them reads the tree the server already delivered. These tests
 * pin what they say about a known surface, and -- just as important -- what they
 * refuse to say: none of them may turn a missing root into a claim that something
 * is not installed, or a present root into a claim about a version.</p>
 */
class DiscoveryModulesTest {

	private static ModuleContext session(List<String> commands, boolean onlineMode) {
		return new ModuleContext(true, false, false, onlineMode, "example.net", "paper", "26.3",
			770, 40L, 12, "enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, commands);
	}

	private static ModuleContext session(List<String> commands, List<CommandDescriptor> details, boolean onlineMode) {
		return new ModuleContext(true, false, false, onlineMode, "example.net", "paper", "26.3",
			770, 40L, 12, "enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, commands, details);
	}

	private static List<String> details(ModuleResult result) {
		return result.findings().stream().map(Finding::detail).toList();
	}

	private static List<String> titles(ModuleResult result) {
		return result.findings().stream().map(Finding::title).toList();
	}

	// ---- administrative exposure -------------------------------------------

	@Test void namesTheCapabilityGroupsThatWereOffered() {
		ModuleContext context = session(List.of("help", "list", "msg", "op", "gamemode",
			"papi", "essentials:msg", "worldedit"), true);
		ModuleResult result = new AdminCommandExposureModule().run(context, Map.of("FOCUS", "all"));

		assertTrue(titles(result).stream().anyMatch(title -> title.startsWith("Offered: authority")),
			"an op root was advertised and not reported");
		assertTrue(titles(result).stream().anyMatch(title -> title.startsWith("Offered: code")),
			"a placeholder root was advertised and not reported");
		assertTrue(details(result).stream().anyMatch(detail -> detail.contains("papi")));
		assertTrue(result.summary().contains("capability group"));
	}

	@Test void aTightSurfaceIsAPassNotSilence() {
		ModuleContext context = session(List.of("help", "list", "msg"), true);
		ModuleResult result = new AdminCommandExposureModule().run(context, Map.of());

		assertEquals(1, result.findings().size());
		assertEquals(Finding.Severity.PASS, result.findings().getFirst().severity());
		assertTrue(result.findings().getFirst().detail().contains("not proof"),
			"absence must not be reported as a guarantee");
	}

	@Test void focusLimitsTheReportWithoutChangingWhatWasRead() {
		ModuleContext context = session(List.of("op", "worldedit", "papi"), true);
		ModuleResult onlyWorld = new AdminCommandExposureModule().run(context, Map.of("FOCUS", "world"));
		ModuleResult onlyCode = new AdminCommandExposureModule().run(context, Map.of("FOCUS", "code"));

		assertTrue(titles(onlyWorld).stream().anyMatch(title -> title.contains("world")));
		assertFalse(titles(onlyWorld).stream().anyMatch(title -> title.contains("authority")));
		assertTrue(titles(onlyCode).stream().anyMatch(title -> title.contains("code")));
	}

	@Test void privilegedChildrenBelowAnOrdinaryRootAreReported() {
		ModuleContext context = session(List.of("plugin"),
			List.of(new CommandDescriptor("plugin", true, List.of("list", "op", "setblock"))), true);
		ModuleResult result = new AdminCommandExposureModule().run(context, Map.of());

		assertTrue(details(result).stream().anyMatch(detail -> detail.contains("plugin op")),
			"a privileged literal below a neutral root was not reported");
		assertTrue(titles(result).stream().anyMatch(title -> title.contains("Administrative subcommands")));
	}

	@Test void withoutAConnectionNothingIsInvented() {
		ModuleResult result = new AdminCommandExposureModule().run(
			new ModuleContext(false, false, false, true, "unknown", "unknown", "26.3", -1, -1L, 0,
				"not applicable", "Tester", "0, 0, 0", "minecraft:overworld", 60, 0, 0, List.of()),
			Map.of());
		assertTrue(result.summary().startsWith("No connection"));
	}

	// ---- alias shadowing ----------------------------------------------------

	@Test void twoPluginsClaimingOneNameIsACollision() {
		ModuleContext context = session(List.of("msg", "essentials:msg", "another:msg", "essentials:pay"), true);
		ModuleResult result = new AliasShadowingModule().run(context, Map.of());

		assertTrue(titles(result).stream().anyMatch(title -> title.contains("Contested name: msg")),
			"two plugin namespaces claimed msg and it was not reported");
		assertTrue(details(result).stream().anyMatch(detail -> detail.contains("essentials")
			&& detail.contains("another")));
	}

	@Test void aPluginAnsweringAVanillaAdministrativeNameIsMedium() {
		ModuleContext context = session(List.of("op", "essentials:op"), true);
		ModuleResult result = new AliasShadowingModule().run(context, Map.of());

		Finding finding = result.findings().stream()
			.filter(item -> item.title().contains("Sensitive vanilla name"))
			.findFirst().orElseThrow();
		assertEquals(Finding.Severity.MEDIUM, finding.severity());
		assertTrue(finding.detail().contains("op"));
	}

	@Test void vanillaKeepingItsOwnNamesIsNotACollision() {
		ModuleContext context = session(List.of("minecraft:msg", "minecraft:list", "essentials:pay"), true);
		ModuleResult result = new AliasShadowingModule().run(context, Map.of());

		assertEquals(1, result.findings().size());
		assertEquals(Finding.Severity.PASS, result.findings().getFirst().severity());
	}

	@Test void aNamespaceSuffixIsNotMistakenForAPlugin() {
		// A colon inside an argument path must not create a phantom provider.
		ModuleContext context = session(List.of("msg", "essentials:msg"), true);
		ModuleResult result = new AliasShadowingModule().run(context, Map.of());
		assertTrue(titles(result).stream().noneMatch(title -> title.contains("Contested")),
			"a bare name plus its own namespaced form is one provider, not a collision");
	}

	// ---- trust posture ------------------------------------------------------

	@Test void offlineModeIsHighAndSaysWhatItMeans() {
		ModuleResult result = new ClientTrustPostureModule().run(session(List.of("help"), false), Map.of());

		Finding finding = result.findings().stream()
			.filter(item -> item.title().contains("does not authenticate"))
			.findFirst().orElseThrow();
		assertEquals(Finding.Severity.HIGH, finding.severity());
		assertTrue(result.summary().contains("authentication"));
	}

	@Test void anAuthenticatedServerIsAPassOnThatRow() {
		ModuleResult result = new ClientTrustPostureModule().run(session(List.of("help"), true), Map.of());
		assertTrue(titles(result).stream().anyMatch(title -> title.contains("authenticated")));
		assertTrue(result.findings().stream().noneMatch(item -> item.severity() == Finding.Severity.HIGH));
	}

	@Test void singleplayerIsRecognisedInsteadOfAudited() {
		ModuleContext context = new ModuleContext(true, true, true, true, "singleplayer", "vanilla", "26.3",
			770, 1L, 1, "enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
		ModuleResult result = new ClientTrustPostureModule().run(context, Map.of());
		assertTrue(result.summary().contains("Singleplayer"));
	}

	@Test void anOptionalResourcePackIsCalledOut() {
		ModuleContext context = new ModuleContext(true, false, false, true, "example.net", "paper", "26.3",
			770, 40L, 12, "prompt", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
		ModuleResult result = new ClientTrustPostureModule().run(context, Map.of());
		assertTrue(titles(result).stream().anyMatch(title -> title.contains("Resource pack is optional")));
	}

	// ---- fingerprint --------------------------------------------------------

	@Test void theFingerprintIsStableAndSurfaceDependent() {
		ModuleContext first = session(List.of("msg", "essentials:msg", "skript", "plus"), true);
		ModuleContext reordered = session(List.of("plus", "skript", "essentials:msg", "msg"), true);
		ModuleContext changed = session(List.of("msg", "essentials:msg", "skript"), true);

		String a = fingerprintOf(first);
		String b = fingerprintOf(reordered);
		String c = fingerprintOf(changed);

		assertEquals(a, b, "the same surface in a different order must fingerprint the same");
		assertNotEquals(a, c, "a removed root must change the fingerprint");
		assertEquals(16, a.length());
	}

	@Test void namespacesDoNotChangeTheFingerprint() {
		ModuleContext namespaced = session(List.of("essentials:msg", "skript"), true);
		ModuleContext bare = session(List.of("msg", "skript"), true);
		assertEquals(fingerprintOf(namespaced), fingerprintOf(bare),
			"the fingerprint is over bare roots, so a plugin gaining a namespace does not look like a change");
	}

	@Test void theInventoryNamesFamiliesAndCatalogueEntries() {
		ModuleContext context = session(List.of("essentials", "worldedit", "skript", "pl"), true);
		ModuleResult result = new PluginFingerprintModule().run(context, Map.of());

		assertTrue(titles(result).stream().anyMatch(title -> title.contains("EssentialsX")));
		assertTrue(titles(result).stream().anyMatch(title -> title.contains("WorldEdit")));
		assertTrue(titles(result).stream().anyMatch(title -> title.contains("Catalogued plugins detected")),
			"a surface with catalogued plugins must point at the catalogue entries");
		assertTrue(result.evidence().stream().anyMatch(node -> node.label().equals("Fingerprint")));
		assertTrue(result.evidence().stream().anyMatch(node -> node.label().equals("Commands sent")
			&& node.value().equals("none")));
	}

	@Test void anUnknownSurfaceSaysNothingWasRecognisedNotThatNothingIsInstalled() {
		ModuleContext context = session(List.of("help", "list", "totallynotaplugin"), true);
		ModuleResult result = new PluginFingerprintModule().run(context, Map.of());

		Finding finding = result.findings().stream()
			.filter(item -> item.severity() == Finding.Severity.PASS)
			.findFirst().orElseThrow();
		assertTrue(finding.detail().contains("filtered"),
			"the pass has to admit that a filtered tree looks the same as a clean one");
	}

	private static String fingerprintOf(ModuleContext context) {
		return new PluginFingerprintModule().run(context, Map.of()).evidence().stream()
			.filter(node -> node.label().equals("Fingerprint"))
			.findFirst().orElseThrow().value();
	}
}
