package dev.champions.core.module;

import java.util.List;

public record ModuleResult(String summary, List<Finding> findings, List<EvidenceNode> evidence) {
	public ModuleResult {
		summary = summary == null ? "Completed" : summary;
		findings = findings == null ? List.of() : List.copyOf(findings);
		evidence = evidence == null ? List.of() : List.copyOf(evidence);
	}

	/** Compatibility constructor for modules not yet migrated to structured evidence. */
	public ModuleResult(String summary, List<Finding> findings) {
		this(summary, findings, List.of());
	}
}
