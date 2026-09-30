package dev.champions.core.module;

import java.util.List;
import java.util.Locale;

/** Bounded summary of one server-advertised Brigadier root and its descendants. */
public record CommandDescriptor(
	String root,
	boolean executableWithoutArguments,
	List<String> literalChildren,
	List<CommandPathDescriptor> descendants
) {
	public CommandDescriptor {
		root = root == null ? "" : root.toLowerCase(Locale.ROOT);
		literalChildren = literalChildren == null ? List.of() : literalChildren.stream()
			.map(value -> value.toLowerCase(Locale.ROOT)).distinct().sorted().toList();
		descendants = descendants == null ? List.of() : List.copyOf(descendants);
	}

	/** Compatibility constructor for shallow snapshots and tests. */
	public CommandDescriptor(String root, boolean executableWithoutArguments, List<String> literalChildren) {
		this(root, executableWithoutArguments, literalChildren,
			literalChildren == null ? List.of() : literalChildren.stream()
				.map(name -> new CommandPathDescriptor(List.of(name), CommandPathDescriptor.NodeKind.LITERAL, true, false, false))
				.toList());
	}

	public boolean hasLiteral(String name) {
		return literalChildren.contains(name.toLowerCase(Locale.ROOT));
	}
}
