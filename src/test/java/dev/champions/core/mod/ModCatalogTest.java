package dev.champions.core.mod;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The catalogue is only useful if its ids are the ids the loader reports.
 *
 * <p>The failure this prevents is silent: a profile under a project name never
 * matches an installed mod, the report says nothing about a family that is right in
 * front of it, and nobody notices because "not recognised" looks the same as "not
 * installed". Three families were in exactly that state before this check existed.</p>
 */
class ModCatalogTest {

	private static Path docs() {
		return Path.of("docs", "mod-catalogue.md");
	}

	@Test void everyFamilyHasAReviewAndACaveat() {
		for (ModCatalog.Profile profile : ModCatalog.profiles()) {
			assertFalse(profile.review().isBlank(), profile.id() + " has no review text");
			assertFalse(profile.caveat().isBlank(), profile.id() + " has no caveat");
			assertFalse(profile.focus() == null, profile.id() + " has no focus");
			assertFalse(profile.focus().question().isBlank(), profile.focus() + " has no question");
		}
	}

	@Test void idsAreUniqueLowercaseAscii() {
		Set<String> seen = new HashSet<>();
		for (ModCatalog.Profile profile : ModCatalog.profiles()) {
			assertTrue(profile.id().matches("[a-z0-9._-]+"),
				"mod id is not lowercase ascii: " + profile.id());
			assertTrue(seen.add(profile.id()), "duplicate catalogue id: " + profile.id());
		}
	}

	@Test void everyAliasResolvesToAFamilyThatExists() {
		for (ModCatalog.Profile profile : ModCatalog.profiles()) {
			if (!profile.isAlias()) continue;
			ModCatalog.Profile target = ModCatalog.canonical(profile.aliasOf()).orElse(null);
			assertTrue(target != null && !target.isAlias(),
				profile.id() + " points at " + profile.aliasOf() + ", which is not a family");
			assertEquals(target.id(), ModCatalog.canonical(profile.id()).orElseThrow().id(),
				profile.id() + " does not resolve through to its family");
		}
	}

	@Test void everyFamilyHasADatedCheckUpstream() {
		List<String> missing = ModCatalog.families().stream()
			.map(ModCatalog.Profile::id)
			.filter(id -> ModVerification.all().get(id) == null)
			.toList();
		assertTrue(missing.isEmpty(), "families with no upstream check: " + missing);
		assertEquals(ModCatalog.families().size(), ModVerification.all().size(),
			"the verification table has entries that are not families");
	}

	@Test void everyCheckIsDatedAndNoneIsInTheFuture() {
		for (var entry : ModVerification.all().entrySet()) {
			LocalDate date = entry.getValue().date();
			assertTrue(date != null, entry.getKey() + " has no parseable check date: "
				+ entry.getValue().checkedOn());
			assertFalse(date.isAfter(LocalDate.now()), entry.getKey() + " is dated in the future");
		}
		assertTrue(ModVerification.undated().isEmpty(), "undated entries: " + ModVerification.undated());
	}

	@Test void everyEntryNamesTheProjectItWasReadFrom() {
		for (var entry : ModVerification.all().entrySet()) {
			String slug = entry.getValue().slug();
			assertFalse(slug.isBlank() || slug.equals("unknown"),
				entry.getKey() + " does not name a project page");
			assertTrue(ModVerification.sourceUrl(entry.getValue()).startsWith("https://modrinth.com/mod/"),
				entry.getKey() + " produces no usable source url");
		}
	}

	@Test void aFamilyWithoutACurrentBuildSaysSo() {
		List<String> withoutBuild = ModVerification.withoutCurrentBuild();
		assertFalse(withoutBuild.isEmpty(), "this test would pass vacuously if every family had a build");
		for (String id : withoutBuild) {
			assertTrue(ModCatalog.forMod(id).isPresent(), id + " is unbuilt but not in the catalogue");
			assertFalse(ModVerification.all().get(id).version().isBlank(),
				id + " is unbuilt and also has no version recorded");
		}
	}

	@Test void theLoaderIdsAreTheVerifiedOnes() {
		// These three were wrong or duplicated in the previous catalogue. If a future
		// edit renames a profile back to the project name, the profile matches nothing.
		assertEquals("roughlyenoughitems", ModCatalog.canonical("roughlyenoughitems").orElseThrow().id());
		assertEquals("roughlyenoughitems", ModCatalog.canonical("rei").orElseThrow().id());
		assertEquals("xaerominimap", ModCatalog.canonical("xaerominimap").orElseThrow().id());
		assertEquals("xaerominimap", ModCatalog.canonical("xaeros-minimap").orElseThrow().id());
		assertEquals("voicechat", ModCatalog.canonical("voicechat").orElseThrow().id());
		assertEquals("voicechat", ModCatalog.canonical("simple-voice-chat").orElseThrow().id());
	}

	@Test void theNewFamiliesMatchTheIdsTheirJarsReport() {
		// Read out of each published jar on 2026-10-26; the project slug is not the id.
		record Case(String installedId, String familyId) {}
		List<Case> cases = List.of(
			new Case("geyser-fabric", "geyser"),
			new Case("floodgate", "floodgate"),
			new Case("fabricproxy-lite", "fabricproxy-lite"),
			new Case("viafabricplus", "viafabricplus"),
			new Case("journeymap_webmap", "journeymap_webmap"),
			new Case("computercraft", "computercraft"),
			new Case("openpartiesandclaims", "openpartiesandclaims"),
			new Case("fabric-permissions-api-v0", "fabric-permissions-api-v0"),
			new Case("nochatreports", "nochatreports"),
			new Case("essential-container", "essential-container"),
			new Case("customskinloader-bootstrap", "customskinloader-bootstrap"),
			new Case("no-telemetry", "no-telemetry"));
		for (Case testCase : cases) {
			ModCatalog.Profile profile = ModCatalog.canonical(testCase.installedId()).orElse(null);
			if (profile == null) fail("installed id " + testCase.installedId() + " matches no profile");
			assertEquals(testCase.familyId(), profile.id(),
				testCase.installedId() + " resolved to the wrong family");
		}
	}

	@Test void theDocumentationListsEveryFamily() throws IOException {
		Path docs = docs();
		assertTrue(Files.exists(docs), "docs/mod-catalogue.md is missing");
		String text = Files.readString(docs, StandardCharsets.UTF_8);
		List<String> absent = ModCatalog.profiles().stream().map(ModCatalog.Profile::id)
			.filter(id -> !text.contains("`" + id + "`")).toList();
		assertTrue(absent.isEmpty(), "families missing from docs/mod-catalogue.md: " + absent);
		assertTrue(text.contains(ModVerification.CHECKED_ON),
			"the page does not carry the date of the check");
	}

	@Test void theStalenessRuleUsesTheDateItIsGiven() {
		LocalDate checked = LocalDate.parse(ModVerification.CHECKED_ON);
		assertTrue(ModVerification.staleAt(checked, 180).isEmpty(),
			"nothing should be stale on the day it was checked");
		assertTrue(ModVerification.staleAt(checked.plusDays(179), 180).isEmpty(),
			"a check five months old is still inside a six-month window");
		assertEquals(ModVerification.all().size(), ModVerification.staleAt(checked.plusDays(400), 180).size(),
			"every entry should be stale once the window has clearly passed");
	}

	@Test void theCatalogueIsBigEnoughToBeWorthCallingOne() {
		assertTrue(ModCatalog.families().size() >= 45,
			"the catalogue shrank to " + ModCatalog.families().size() + " families");
		assertTrue(ModCatalog.knownIds().size() > ModCatalog.families().size(),
			"aliases are part of the catalogue too");
	}

	@Test void everyFocusInUseHasAQuestion() {
		Set<ModCatalog.Focus> used = ModCatalog.profiles().stream().map(ModCatalog.Profile::focus)
			.collect(Collectors.toSet());
		for (ModCatalog.Focus focus : used) {
			assertFalse(focus.question().isBlank(), focus + " is used but asks nothing");
		}
	}
}
