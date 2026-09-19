package dev.champions.core.module;

import java.util.Objects;

public record Finding(Severity severity, String title, String detail, String recommendation) {
	public enum Severity { INFO, PASS, LOW, MEDIUM, HIGH }

	public Finding {
		Objects.requireNonNull(severity);
		title = Objects.requireNonNullElse(title, "Finding");
		detail = Objects.requireNonNullElse(detail, "");
		recommendation = Objects.requireNonNullElse(recommendation, "");
	}
}
