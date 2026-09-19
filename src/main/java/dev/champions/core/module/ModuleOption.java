package dev.champions.core.module;

import java.util.Objects;

public record ModuleOption(String name, String defaultValue, boolean required, boolean secret, String description) {
	public ModuleOption {
		name = Objects.requireNonNull(name).toUpperCase();
		defaultValue = Objects.requireNonNullElse(defaultValue, "");
		description = Objects.requireNonNullElse(description, "");
	}
}
