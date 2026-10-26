package dev.champions.core.repo;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One module as advertised by a module repository's {@code index.json}.
 *
 * <p>The entry is metadata only. What actually gets installed is {@link #file()},
 * and it is only installed if the downloaded text hashes to {@link #sha256()},
 * which the index pinned. That is what stops a swapped file from being compiled:
 * a repository can rewrite its index, but it cannot make a file whose bytes differ
 * from the hash the client already read match it.</p>
 */
public record RepoEntry(
		String id,
		String name,
		String description,
		String category,
		List<String> tags,
		int api,
		String version,
		String file,
		String sha256) {

	public RepoEntry {
		tags = tags == null ? List.of() : List.copyOf(tags);
	}

	/** True when the query words all appear in the id, name, description or tags. */
	public boolean matches(List<String> words) {
		if (words.isEmpty()) return true;
		String haystack = (id + " " + name + " " + description + " " + category + " "
				+ String.join(" ", tags)).toLowerCase(Locale.ROOT);
		for (String word : words) {
			if (!haystack.contains(word.toLowerCase(Locale.ROOT))) return false;
		}
		return true;
	}

	static RepoEntry fromMap(Map<?, ?> map) {
		return new RepoEntry(
				text(map.get("id"), ""),
				text(map.get("name"), ""),
				text(map.get("description"), ""),
				text(map.get("category"), ""),
				stringList(map.get("tags")),
				number(map.get("api"), 1),
				text(map.get("version"), ""),
				text(map.get("file"), ""),
				text(map.get("sha256"), "").toLowerCase(Locale.ROOT));
	}

	private static String text(Object value, String fallback) {
		return value instanceof String string && !string.isBlank() ? string : fallback;
	}

	private static int number(Object value, int fallback) {
		return value instanceof Number result ? result.intValue() : fallback;
	}

	private static List<String> stringList(Object value) {
		if (!(value instanceof List<?> list)) return List.of();
		return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
	}
}
