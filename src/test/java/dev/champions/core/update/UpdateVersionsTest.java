package dev.champions.core.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateVersionsTest {
	@Test void ordersPrereleasesBelowTheirRelease() {
		assertTrue(UpdateVersions.compare("0.1.0-alpha.16", "0.1.0-beta.1") < 0);
		assertTrue(UpdateVersions.compare("0.1.0-beta.1", "0.1.0-beta.2") < 0);
		assertTrue(UpdateVersions.compare("0.1.0-beta.2", "0.1.0") < 0);
		assertTrue(UpdateVersions.compare("0.1.0", "0.1.0-beta.2") > 0);
	}

	@Test void ordersCoresNumerically() {
		assertTrue(UpdateVersions.compare("0.1.9", "0.2.0") < 0);
		assertTrue(UpdateVersions.compare("0.9.9", "0.10.0") < 0);
		assertTrue(UpdateVersions.compare("1.0.0", "0.9.9") > 0);
		assertTrue(UpdateVersions.compare("0.2.0-beta.1", "0.1.9") > 0);
	}

	@Test void ignoresLeadingVAndCase() {
		assertEquals(0, UpdateVersions.compare("v0.1.0-beta.1", "0.1.0-Beta.1"));
		assertEquals(0, UpdateVersions.compare("V1.2.3", "1.2.3"));
	}

	@Test void followsSemverPrereleaseIdentifierRules() {
		assertTrue(UpdateVersions.compare("1.0.0-alpha", "1.0.0-alpha.1") < 0);
		assertTrue(UpdateVersions.compare("1.0.0-alpha.1", "1.0.0-alpha.2") < 0);
		assertTrue(UpdateVersions.compare("1.0.0-alpha.2", "1.0.0-beta") < 0);
		assertTrue(UpdateVersions.compare("1.0.0-2", "1.0.0-10") < 0);
		assertTrue(UpdateVersions.compare("1.0.0-alpha", "1.0.0-1") > 0);
	}

	@Test void treatsIdenticalVersionsAsEqual() {
		assertEquals(0, UpdateVersions.compare("0.1.0-beta.1", "0.1.0-beta.1"));
		assertEquals(0, UpdateVersions.compare("2.3.4", "v2.3.4"));
	}

	@Test void fallsBackToStringComparisonForNonSemanticVersions() {
		assertEquals("dev".compareTo("unknown"), UpdateVersions.compare("dev", "unknown"));
		assertEquals("dev".compareTo("0.1.0"), UpdateVersions.compare("dev", "0.1.0"));
	}
}
