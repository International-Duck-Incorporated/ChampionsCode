package dev.champions.core.repo;

import dev.champions.core.update.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed module repository index.
 *
 * <p>The format is deliberately flat so the collection can be maintained by hand
 * or by a script: one JSON document listing every module, with the hash of the
 * file it ships. Unknown keys are ignored, so the index can grow without
 * breaking older clients; entries that are missing an id or a file are dropped
 * rather than failing the whole fetch.</p>
 */
public record ModuleIndex(int schema, String name, String updated, List<RepoEntry> entries) {
	/**
	 * The module API level this build understands; entries above it are refused.
	 *
	 * <p>Level 2 added {@code ChatCommandRequest.proof(root, timeoutTicks,
	 * maxMessages)} so a payload can declare a command that takes no arguments
	 * — {@code /seed}, {@code /list} — which level 1 could only express by
	 * reaching past the factory. A module that needs it declares {@code api: 2}
	 * and an older client refuses it with an update message instead of failing
	 * to compile it.</p>
	 */
	public static final int SUPPORTED_API = 2;

	public ModuleIndex {
		entries = List.copyOf(entries);
	}

	public static ModuleIndex empty() {
		return new ModuleIndex(1, "", "", List.of());
	}

	public Optional<RepoEntry> find(String id) {
		return entries.stream().filter(entry -> entry.id().equalsIgnoreCase(id)).findFirst();
	}

	public List<RepoEntry> search(List<String> words) {
		return entries.stream().filter(entry -> entry.matches(words)).toList();
	}

	/**
	 * @throws IllegalArgumentException when the document is not a JSON object or
	 *         has no {@code modules} array — a 404 page or an HTML error from the
	 *         server must not look like an empty, healthy repository.
	 */
	public static ModuleIndex parse(String json) {
		Object root = MiniJson.parse(json);
		if (!(root instanceof Map<?, ?> document)) {
			throw new IllegalArgumentException("module index is not a JSON object");
		}
		if (!(document.get("modules") instanceof List<?> rawEntries)) {
			throw new IllegalArgumentException("module index has no 'modules' array");
		}
		List<RepoEntry> entries = new ArrayList<>();
		for (Object raw : rawEntries) {
			if (!(raw instanceof Map<?, ?> map)) continue;
			RepoEntry entry = RepoEntry.fromMap(map);
			if (entry.id().isBlank() || entry.file().isBlank()) continue;
			entries.add(entry);
		}
		int schema = document.get("schema") instanceof Number number ? number.intValue() : 1;
		return new ModuleIndex(
				schema,
				document.get("name") instanceof String name ? name : "",
				document.get("updated") instanceof String updated ? updated : "",
				entries);
	}
}
