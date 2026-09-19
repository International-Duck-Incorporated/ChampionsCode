package dev.champions.core.module;

import java.util.List;

/** Broad, non-verdict motion telemetry for one remotely rendered player. */
public record PlayerMotionObservation(
	String playerName,
	long samples,
	double lastHorizontalDelta,
	double lastVerticalDelta,
	double maximumHorizontalDelta,
	long suddenDisplacements,
	long pitchBoundaryEvents,
	List<String> notes
) {
	public PlayerMotionObservation {
		playerName = playerName == null || playerName.isBlank() ? "unknown" : playerName;
		notes = notes == null ? List.of() : List.copyOf(notes);
	}
}
