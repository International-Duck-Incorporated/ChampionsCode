package dev.champions.core.update;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UpdateFeedParserTest {
	private static final String FEED = """
		[
		  {
		    "tag_name": "v0.1.0-beta.1",
		    "name": "0.1.0-beta.1",
		    "_links": {"self": "https://gitlab.com/international-duck-incorporated/ChampionsCode/-/releases/v0.1.0-beta.1"},
		    "assets": {"links": [
		      {"name": "champions-mod-0.1.0-beta.1.jar", "url": "https://gitlab.com/x/y/-/releases/v0.1.0-beta.1/downloads/champions-mod-0.1.0-beta.1.jar", "direct_asset_url": "https://gitlab.com/x/y/-/releases/v0.1.0-beta.1/downloads/champions-mod-0.1.0-beta.1.jar"},
		      {"name": "champions-mod-0.1.0-beta.1-sources.jar", "direct_asset_url": "https://gitlab.com/x/y/-/releases/v0.1.0-beta.1/downloads/champions-mod-0.1.0-beta.1-sources.jar"},
		      {"name": "SHA256SUMS.txt", "direct_asset_url": "https://gitlab.com/x/y/-/releases/v0.1.0-beta.1/downloads/SHA256SUMS.txt"}
		    ]}
		  },
		  {
		    "tag_name": "v0.1.0-alpha.16",
		    "_links": {"self": "https://gitlab.com/international-duck-incorporated/ChampionsCode/-/releases/v0.1.0-alpha.16"},
		    "assets": {"links": [
		      {"name": "champions-mod-0.1.0-alpha.16.jar", "direct_asset_url": "https://gitlab.com/x/y/-/releases/v0.1.0-alpha.16/downloads/champions-mod-0.1.0-alpha.16.jar"},
		      {"name": "SHA256SUMS.txt", "direct_asset_url": "https://gitlab.com/x/y/-/releases/v0.1.0-alpha.16/downloads/SHA256SUMS.txt"}
		    ]}
		  }
		]
		""";

	@Test void picksNewestReleaseRegardlessOfFeedOrder() {
		UpdateCheck check = UpdateFeedParser.parse(FEED, "0.1.0-alpha.16").orElseThrow();
		assertEquals("0.1.0-beta.1", check.latestVersion());
		assertEquals("0.1.0-alpha.16", check.currentVersion());
		assertTrue(check.newerAvailable());
		assertEquals("https://gitlab.com/international-duck-incorporated/ChampionsCode/-/releases/v0.1.0-beta.1", check.releasePageUrl());
		assertTrue(check.jarUrl().endsWith("champions-mod-0.1.0-beta.1.jar"));
		assertFalse(check.jarUrl().contains("sources"));
		assertTrue(check.checksumsUrl().endsWith("SHA256SUMS.txt"));
	}

	@Test void reportsUpToDateWhenCurrentMatchesLatest() {
		UpdateCheck check = UpdateFeedParser.parse(FEED, "0.1.0-beta.1").orElseThrow();
		assertTrue(check.matchesCurrent());
		assertFalse(check.newerAvailable());
	}

	@Test void acceptsTagsWithoutVPrefix() {
		String feed = "[{\"tag_name\": \"0.2.0\", \"assets\": {\"links\": ["
			+ "{\"direct_asset_url\": \"https://gitlab.com/x/y/downloads/champions-mod-0.2.0.jar\"},"
			+ "{\"direct_asset_url\": \"https://gitlab.com/x/y/downloads/SHA256SUMS.txt\"}]}}]";
		UpdateCheck check = UpdateFeedParser.parse(feed, "0.1.0").orElseThrow();
		assertEquals("0.2.0", check.latestVersion());
		assertTrue(check.jarUrl().endsWith("champions-mod-0.2.0.jar"));
	}

	@Test void fallsBackToUrlWhenDirectAssetUrlIsMissing() {
		String feed = "[{\"tag_name\": \"v0.2.0\", \"assets\": {\"links\": ["
			+ "{\"url\": \"https://gitlab.com/x/y/downloads/champions-mod-0.2.0.jar\"}]}}]";
		UpdateCheck check = UpdateFeedParser.parse(feed, "0.1.0").orElseThrow();
		assertTrue(check.jarUrl().endsWith("champions-mod-0.2.0.jar"));
		assertTrue(check.checksumsUrl().isEmpty());
	}

	@Test void leavesAssetUrlsEmptyWhenReleaseListsNone() {
		String feed = "[{\"tag_name\": \"v0.2.0\"}]";
		UpdateCheck check = UpdateFeedParser.parse(feed, "0.1.0").orElseThrow();
		assertTrue(check.jarUrl().isEmpty());
		assertTrue(check.checksumsUrl().isEmpty());
		assertTrue(check.releasePageUrl().isEmpty());
	}

	@Test void ignoresNonReleaseTagsAndNonSemanticEntries() {
		String feed = "[{\"tag_name\": \"nightly-2024\"}, {\"tag_name\": \"v9.9.9\"}]";
		UpdateCheck check = UpdateFeedParser.parse(feed, "0.1.0").orElseThrow();
		assertEquals("9.9.9", check.latestVersion());
	}

	@Test void yieldsNothingForAnEmptyFeed() {
		assertEquals(Optional.empty(), UpdateFeedParser.parse("[]", "0.1.0"));
	}

	@Test void rejectsMalformedFeedJson() {
		assertThrows(IllegalArgumentException.class, () -> UpdateFeedParser.parse("{\"broken\": ", "0.1.0"));
	}
}
