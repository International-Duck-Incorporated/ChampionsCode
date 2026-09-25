package dev.champions.core.update;

import java.util.Objects;

/**
 * The outcome of reading the update feed: the running version, the newest
 * release, and where its verified artifacts live. Asset URLs are absent when
 * the feed lists no matching release assets.
 */
public record UpdateCheck(String currentVersion, String latestVersion, String releasePageUrl, String jarUrl, String checksumsUrl) {
	public UpdateCheck {
		Objects.requireNonNull(currentVersion, "currentVersion");
		Objects.requireNonNull(latestVersion, "latestVersion");
		releasePageUrl = Objects.requireNonNullElse(releasePageUrl, "");
		jarUrl = Objects.requireNonNullElse(jarUrl, "");
		checksumsUrl = Objects.requireNonNullElse(checksumsUrl, "");
	}

	public boolean newerAvailable() {
		return UpdateVersions.compare(latestVersion, currentVersion) > 0;
	}

	public boolean matchesCurrent() {
		return UpdateVersions.compare(latestVersion, currentVersion) == 0;
	}
}
