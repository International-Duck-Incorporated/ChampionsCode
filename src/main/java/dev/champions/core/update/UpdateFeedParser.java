package dev.champions.core.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the public GitLab releases feed into an {@link UpdateCheck}. All
 * published releases are considered and the newest valid tag wins, so feed
 * ordering does not matter. A release only yields a downloadable JAR when its
 * asset links contain the exact {@code champions-mod-<version>.jar} and a
 * {@code SHA256SUMS.txt}.
 */
public final class UpdateFeedParser {
	private UpdateFeedParser() {}

	public static Optional<UpdateCheck> parse(String feedJson, String currentVersion) {
		Object root = MiniJson.parse(feedJson);
		List<Object> releases = new ArrayList<>();
		if (root instanceof List<?> list) releases.addAll(list);
		else if (root instanceof Map<?,?> single) releases.add(single);

		String latestVersion = null;
		String releasePage = null;
		String jarUrl = null;
		String checksumsUrl = null;

		for (Object entry : releases) {
			if (!(entry instanceof Map<?,?> release)) continue;
			String tagName = text(release.get("tag_name"));
			if (tagName == null || !tagName.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?")) continue;
			String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
			if (latestVersion != null && UpdateVersions.compare(version, latestVersion) <= 0) continue;

			String page = null;
			if (release.get("_links") instanceof Map<?,?> links) page = text(links.get("self"));
			String foundJar = null;
			String foundSums = null;
			if (release.get("assets") instanceof Map<?,?> assets && assets.get("links") instanceof List<?> links) {
				for (Object linkEntry : links) {
					if (!(linkEntry instanceof Map<?,?> link)) continue;
					String url = text(link.get("direct_asset_url"));
					if (url == null) url = text(link.get("url"));
					if (url == null) continue;
					if (url.endsWith("champions-mod-" + version + ".jar")) foundJar = url;
					else if (url.endsWith("SHA256SUMS.txt")) foundSums = url;
				}
			}
			latestVersion = version;
			releasePage = page;
			jarUrl = foundJar;
			checksumsUrl = foundSums;
		}

		if (latestVersion == null) return Optional.empty();
		return Optional.of(new UpdateCheck(currentVersion, latestVersion, releasePage, jarUrl, checksumsUrl));
	}

	private static String text(Object value) {
		return value instanceof String string ? string : null;
	}
}
