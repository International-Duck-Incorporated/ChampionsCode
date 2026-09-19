package dev.champions.core.module;

import java.util.List;
import java.util.Locale;

/** Safe summary of one server-advertised Brigadier root command. */
public record CommandDescriptor(String root, boolean executableWithoutArguments, List<String> literalChildren) {
	public CommandDescriptor {
		root = root == null ? "" : root.toLowerCase(Locale.ROOT);
		literalChildren = literalChildren == null ? List.of() : literalChildren.stream()
			.map(value -> value.toLowerCase(Locale.ROOT)).distinct().sorted().toList();
	}

	public boolean hasLiteral(String name) {
		return literalChildren.contains(name.toLowerCase(Locale.ROOT));
	}
}
