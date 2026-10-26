package dev.champions.core.mod;

import dev.champions.core.module.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mod inventory analysis, driven from fixed inputs.
 *
 * <p>The point of these tests is the boundary: an inventory of this client's mods
 * must never be reported as an inventory of the server's, and a channel identifier
 * must never be reported as more than "mods on the other end are talking".</p>
 */
class ModPostureTest {

	private static ModPosture.InstalledMod mod(String id, String version) {
		return new ModPosture.InstalledMod(id, id, version, "mod");
	}

	private static List<String> titles(ModPosture.Result result) {
		return result.findings().stream().map(Finding::title).toList();
	}

	private static List<String> details(ModPosture.Result result) {
		return result.findings().stream().map(Finding::detail).toList();
	}

	@Test void aStockSetupIsReportedWithoutInventingAFinding() {
		ModPosture.Result result = ModPosture.analyse(List.of(
			mod("fabric-api", "0.161.0+26.3"),
			mod("sodium", "0.6.9"),
			mod("modmenu", "13.0.0")), Set.of(), "fabric");

		assertTrue(titles(result).stream().anyMatch(title -> title.startsWith("Client-side only")));
		// 0.161.0+26.3 is a published Fabric API release: the suffix names the game
		// version, so it must not be reported as a working-tree build.
		assertFalse(titles(result).stream().anyMatch(title -> title.contains("working tree")),
			"a published +mcversion suffix was mistaken for a local build");
		assertTrue(result.findings().stream().noneMatch(finding -> finding.severity() == Finding.Severity.HIGH));
		assertTrue(titles(result).stream().anyMatch(title -> title.startsWith("What this inventory is")));
	}

	@Test void theServerIsNotDescribedByTheClientsModList() {
		ModPosture.Result result = ModPosture.analyse(List.of(mod("cobblemon", "1.8.0")), Set.of(), "fabric");
		Finding boundary = result.findings().stream()
			.filter(finding -> finding.title().startsWith("What this inventory is"))
			.findFirst().orElseThrow();
		assertTrue(boundary.detail().contains("not visible to a client"), boundary.detail());
		assertTrue(boundary.recommendation().contains("server"));
	}

	@Test void aChannelIdentifierIsTheOnlyEvidenceAboutTheFarEnd() {
		ModPosture.Result result = ModPosture.analyse(
			List.of(mod("cobblemon", "1.8.0")),
			Set.of("cobblemon:main", "minecraft:brand", "fabric:custom_payload"), "fabric");

		Finding channels = result.findings().stream()
			.filter(finding -> finding.title().startsWith("The server is talking"))
			.findFirst().orElseThrow();
		assertTrue(channels.detail().contains("cobblemon:main"));
		assertFalse(channels.detail().contains("minecraft:brand"), "vanilla channels must not be reported as mods");
		assertFalse(channels.detail().contains("fabric:custom_payload"), "loader channels must not be reported as mods");
	}

	@Test void unrecognisedChannelsAreSaidToBeUnrecognised() {
		ModPosture.Result result = ModPosture.analyse(List.of(mod("modmenu", "13.0.0")),
			Set.of("somefork:thing", "another:thing"), "paper");
		assertTrue(titles(result).stream().anyMatch(title -> title.startsWith("No mod channels recognised")));
	}

	@Test void twoCopiesOfOneModInvalidateVersionClaims() {
		ModPosture.Result result = ModPosture.analyse(List.of(
			mod("cobblemon", "1.8.0"), mod("cobblemon", "1.7.2")), Set.of(), "fabric");

		Finding duplicate = result.findings().stream()
			.filter(finding -> finding.title().contains("Two copies"))
			.findFirst().orElseThrow();
		assertEquals(Finding.Severity.MEDIUM, duplicate.severity());
		assertTrue(duplicate.detail().contains("cobblemon"));
	}

	@Test void aWorkingTreeBuildIsFlaggedButNotAccused() {
		ModPosture.Result result = ModPosture.analyse(List.of(
			mod("cobblemon", "1.8.0+git.4a1b2c3"), mod("luckperms", "5.5-SNAPSHOT")), Set.of(), "fabric");

		Finding unpinned = result.findings().stream()
			.filter(finding -> finding.title().contains("working tree"))
			.findFirst().orElseThrow();
		assertEquals(Finding.Severity.LOW, unpinned.severity());
		assertTrue(unpinned.detail().contains("luckperms@5.5-SNAPSHOT"));
		assertTrue(unpinned.recommendation().contains("release"));
	}

	@Test void servicesAreRaisedAboveOrdinaryServerSideMods() {
		ModPosture.Result result = ModPosture.analyse(List.of(
			mod("voicechat", "2.5.18"), mod("cobblemon", "1.8.0")), Set.of(), "fabric");

		Finding service = result.findings().stream()
			.filter(finding -> finding.title().startsWith("Services reachable"))
			.findFirst().orElseThrow();
		assertEquals(Finding.Severity.MEDIUM, service.severity());
		assertTrue(service.detail().contains("voicechat"));
		assertTrue(titles(result).stream().anyMatch(title -> title.startsWith("Changes what the server accepts")));
	}

	@Test void anUnknownModIsListedAsUnreviewedNotAsUnsafe() {
		ModPosture.Result result = ModPosture.analyse(List.of(mod("mysterymod", "2.0.0")), Set.of(), "");
		Finding unknown = result.findings().stream()
			.filter(finding -> finding.title().startsWith("Not in this build's catalogue"))
			.findFirst().orElseThrow();
		assertTrue(unknown.detail().contains("mysterymod"));
		assertTrue(unknown.recommendation().contains("not the same as unsafe"));
	}

	@Test void aFamilyProfileMatchesTheIdAndItsVariants() {
		assertTrue(ModCatalog.forMod("cobblemon").isPresent());
		assertTrue(ModCatalog.forMod("cobblemon-fabric").isPresent());
		assertTrue(ModCatalog.forMod("xaeros-minimap").isPresent());
		assertTrue(ModCatalog.forMod("not-a-mod-we-know").isEmpty());
		assertTrue(ModCatalog.knownIds().contains("cobblemon"));
	}

	@Test void theReportSaysNothingWasSent() {
		ModPosture.Result result = ModPosture.analyse(List.of(mod("sodium", "0.6.9")), Set.of(), "fabric");
		assertTrue(result.evidence().stream().anyMatch(node ->
			node.label().equals("Commands sent") && node.value().equals("none")));
	}
}
