package dev.champions.core.catalog;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue has to say what it is still worth.
 *
 * <p>The failure this prevents is not a crash. It is a catalogue that keeps
 * describing a bug the upstream project fixed two years ago, in the same confident
 * voice, and an operator who reports it. Every entry must carry a version verdict,
 * and an entry that claims to be fixed must name the version that fixed it and say
 * what the claim rests on.</p>
 */
class CatalogApplicabilityTest {

	private static Set<String> cataloguedIds() {
		return ExploitCatalog.entries().stream().map(ExploitCatalog.Entry::id).collect(Collectors.toSet());
	}

	@Test void everyCatalogueEntryHasAVerdict() {
		Set<String> expected = cataloguedIds();
		Set<String> covered = CatalogApplicability.all().keySet();
		assertEquals(expected, covered,
			"an entry with no verdict would be reported with unearned confidence");
	}

	@Test void thereAreNoVerdictsForEntriesThatDoNotExist() {
		Set<String> ids = cataloguedIds();
		for (String id : CatalogApplicability.all().keySet()) {
			assertTrue(ids.contains(id), id + " has a verdict but no catalogue entry");
		}
	}

	@Test void aFixedEntryNamesTheVersionThatFixedIt() {
		for (var entry : CatalogApplicability.all().entrySet()) {
			var verdict = entry.getValue();
			if (verdict.status() != CatalogApplicability.Status.FIXED) continue;
			assertFalse(verdict.fixedIn().isBlank(),
				entry.getKey() + " is FIXED but does not say which version fixed it");
			assertFalse(verdict.affected().equals("unspecified"),
				entry.getKey() + " is FIXED but does not say what it affected");
			assertFalse(verdict.evidence().isBlank(),
				entry.getKey() + " is FIXED but does not say what the claim rests on");
		}
	}

	@Test void anObsoleteEntryNamesTheVersionRangeItWorkedIn() {
		for (var entry : CatalogApplicability.all().entrySet()) {
			var verdict = entry.getValue();
			if (verdict.status() != CatalogApplicability.Status.OBSOLETE) continue;
			assertFalse(verdict.affected().equals("any") || verdict.affected().equals("unspecified"),
				entry.getKey() + " is OBSOLETE but does not name the versions it applied to");
			assertFalse(verdict.fixedIn().isBlank(),
				entry.getKey() + " is OBSOLETE but does not say from which version it stopped applying");
		}
	}

	@Test void everyVerdictIsDatedAndNoneIsInTheFuture() {
		for (var entry : CatalogApplicability.all().entrySet()) {
			String checked = entry.getValue().checkedOn();
			assertFalse(checked.isBlank(), entry.getKey() + " has no check date");
			try {
				LocalDate date = LocalDate.parse(checked);
				assertFalse(date.isAfter(LocalDate.now().plusDays(1)),
					entry.getKey() + " claims to have been checked in the future");
			} catch (DateTimeParseException malformed) {
				throw new AssertionError(entry.getKey() + " has a check date that is not ISO-8601: " + checked);
			}
		}
	}

	@Test void aStandingEntryDoesNotPretendToHaveAFixedVersion() {
		for (var entry : CatalogApplicability.all().entrySet()) {
			var verdict = entry.getValue();
			if (verdict.status() != CatalogApplicability.Status.STANDING) continue;
			assertTrue(verdict.fixedIn().isBlank(),
				entry.getKey() + " is a standing design class but names a fixed version: " + verdict.fixedIn());
		}
	}

	@Test void anUnverifiedEntrySaysSoInWordsTheOperatorCanActOn() {
		for (var entry : CatalogApplicability.all().entrySet()) {
			var verdict = entry.getValue();
			if (verdict.status() != CatalogApplicability.Status.UNVERIFIED) continue;
			assertFalse(verdict.patchedInCurrentBuilds(),
				entry.getKey() + " is unverified but is treated as patched");
			assertTrue(verdict.describe().contains("not confirmed"),
				entry.getKey() + " does not tell the operator it is unconfirmed");
		}
	}

	@Test void theGeyserFindingCarriesTheAdvisoryThatFixedIt() {
		// Verified against the upstream advisory on 2026-10-26: affected <= 2.9.2,
		// patched 2.9.3, CVE-2026-42188 / GHSA-xcfg-fcr5-gw9r.
		var verdict = CatalogApplicability.forEntry("geyser_skin_ssrf").orElseThrow();
		assertEquals(CatalogApplicability.Status.FIXED, verdict.status());
		assertEquals("<= 2.9.2", verdict.affected());
		assertEquals("2.9.3", verdict.fixedIn());
		assertTrue(verdict.evidence().contains("CVE-2026-42188"));
		assertTrue(verdict.describe().contains("will not reproduce"));
	}

	@Test void theWorldEditFindingIsKeptAsHistoryNotAsATest() {
		var verdict = CatalogApplicability.forEntry("worldedit_schematic").orElseThrow();
		assertEquals(CatalogApplicability.Status.OBSOLETE, verdict.status());
		assertEquals("< 7.4.4", verdict.affected());
		assertEquals("7.4.4", verdict.fixedIn());
		// It is catalogue-only, so there is no artifact to mislead anyone with.
		assertFalse(ExploitCatalog.byId("worldedit_schematic").orElseThrow().hasArtifact());
	}

	@Test void theListOfWhatNoLongerReproducesIsAccurate() {
		Set<String> stale = new HashSet<>(CatalogApplicability.notReproducibleOnCurrentBuilds());
		assertTrue(stale.contains("geyser_skin_ssrf"));
		assertTrue(stale.contains("worldedit_schematic"));
		for (String id : stale) {
			assertNotNull(ExploitCatalog.byId(id).orElse(null), id + " is listed as stale but does not exist");
		}
	}

	@Test void theWorldEditEntryCarriesTheReleaseThatWasActuallyCurrent() {
		// Re-checking this means editing the table and this assertion together, which is
		// the point: a stale "latest seen" should be a failing test, not a surprise.
		var verdict = CatalogApplicability.forEntry("worldedit_schematic").orElseThrow();
		assertEquals("7.4.6", verdict.latestSeen());
	}
}
