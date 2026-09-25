package dev.champions.core.module;

import java.util.List;
import java.util.Objects;

/**
 * One module option. {@code choices}, when non-empty, restricts the accepted
 * values (compared case-insensitively and stored in the canonical spelling);
 * UIs render such options as dropdowns instead of free-text fields.
 */
public record ModuleOption(String name, String defaultValue, boolean required, boolean secret, String description,
		List<String> choices) {
	public ModuleOption {
		name = Objects.requireNonNull(name).toUpperCase();
		defaultValue = Objects.requireNonNullElse(defaultValue, "");
		description = Objects.requireNonNullElse(description, "");
		choices = choices == null ? List.of() : List.copyOf(choices);
	}

	public ModuleOption(String name, String defaultValue, boolean required, boolean secret, String description) {
		this(name, defaultValue, required, secret, description, List.of());
	}

	/** True when this option accepts only the listed choices. */
	public boolean constrained() {
		return !choices.isEmpty();
	}

	/**
	 * Resolves user input against the choices: the canonical spelling of the
	 * matching choice, or null when the input matches none.
	 */
	public String resolveChoice(String input) {
		String candidate = input == null ? "" : input.strip();
		for (String choice : choices) {
			if (choice.equalsIgnoreCase(candidate)) return choice;
		}
		return null;
	}
}
