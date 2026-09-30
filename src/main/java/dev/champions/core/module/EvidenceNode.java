package dev.champions.core.module;

import java.util.List;

/** Structured, source-labelled evidence that can be rendered as text, UI, or JSON. */
public record EvidenceNode(
	String label,
	String value,
	Source source,
	Confidence confidence,
	String limitation,
	List<EvidenceNode> children
) {
	public EvidenceNode {
		label = label == null || label.isBlank() ? "Evidence" : label;
		value = value == null ? "" : value;
		source = source == null ? Source.DERIVED : source;
		confidence = confidence == null ? Confidence.UNKNOWN : confidence;
		limitation = limitation == null ? "" : limitation;
		children = children == null ? List.of() : List.copyOf(children);
	}

	public static EvidenceNode observed(String label, String value, String limitation) {
		return new EvidenceNode(label, value, Source.OBSERVED, Confidence.HIGH, limitation, List.of());
	}

	public static EvidenceNode advertised(String label, String value, String limitation) {
		return new EvidenceNode(label, value, Source.ADVERTISED, Confidence.MEDIUM, limitation, List.of());
	}

	public static EvidenceNode group(String label, List<EvidenceNode> children) {
		return new EvidenceNode(label, "", Source.DERIVED, Confidence.UNKNOWN, "", children);
	}

	public enum Source { OBSERVED, ADVERTISED, DERIVED, THIRD_PARTY, ACTIVELY_TESTED, USER_SUPPLIED }
	public enum Confidence { HIGH, MEDIUM, LOW, UNKNOWN }
}
