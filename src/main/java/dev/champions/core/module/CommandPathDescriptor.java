package dev.champions.core.module;

import java.util.List;
import java.util.Locale;

/** One bounded descendant in a server-advertised Brigadier command tree. */
public record CommandPathDescriptor(
	List<String> segments,
	NodeKind kind,
	boolean literalOnly,
	boolean executable,
	boolean redirects
) {
	public CommandPathDescriptor {
		segments = segments == null ? List.of() : segments.stream()
			.map(value -> value == null ? "" : value.strip().toLowerCase(Locale.ROOT))
			.filter(value -> !value.isEmpty())
			.toList();
		kind = kind == null ? NodeKind.ARGUMENT : kind;
	}

	public String path() { return String.join(" ", segments); }
	public String leaf() { return segments.isEmpty() ? "" : segments.get(segments.size() - 1); }

	public enum NodeKind { LITERAL, ARGUMENT }
}
