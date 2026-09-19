package dev.champions.core.module;

import java.util.List;

public record ModuleResult(String summary, List<Finding> findings) {
	public ModuleResult {
		summary = summary == null ? "Completed" : summary;
		findings = findings == null ? List.of() : List.copyOf(findings);
	}
}
