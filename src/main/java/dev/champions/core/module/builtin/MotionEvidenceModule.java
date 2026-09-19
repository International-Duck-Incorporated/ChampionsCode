package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.PlayerMotionObservation;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Passive remote-player motion evidence with intentionally broad, non-verdict thresholds. */
public final class MotionEvidenceModule implements ResearchModule {
	@Override public String id() { return "auxiliary/observe/motion_evidence"; }
	@Override public String name() { return "Motion Evidence observer"; }
	@Override public String description() {
		return "Summarizes broad remote-player displacement and pitch-boundary observations. It does not label, punish, report, or help bypass anticheat checks.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("MAX_PLAYERS", "10", false, false, "Maximum observations shown, 1-40"),
			new ModuleOption("ONLY_NOTED", "false", false, false, "Show only players with broad telemetry notes")
		);
	}

	@Override public ModuleResult run(ModuleContext context, Map<String, String> options) {
		int max = boundedInt(options.get("MAX_PLAYERS"), 10, 1, 40);
		boolean onlyNoted = Boolean.parseBoolean(options.getOrDefault("ONLY_NOTED", "false"));
		List<PlayerMotionObservation> observations = context.playerMotion().stream()
			.filter(observation -> !onlyNoted || !observation.notes().isEmpty())
			.sorted(Comparator.comparingLong(PlayerMotionObservation::suddenDisplacements).reversed()
				.thenComparing(Comparator.comparingDouble(PlayerMotionObservation::maximumHorizontalDelta).reversed()))
			.limit(max).toList();

		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO, "Observer scope",
			context.playerMotion().size() + " remote player model(s); showing " + observations.size(),
			"Client observations are lossy and affected by lag, interpolation, teleports, vehicles, and unloaded chunks."));
		for (PlayerMotionObservation observation : observations) {
			String detail = String.format(Locale.ROOT,
				"samples=%d lastHorizontal=%.3f lastVertical=%.3f maxHorizontal=%.3f sudden=%d pitchBoundary=%d",
				observation.samples(), observation.lastHorizontalDelta(), observation.lastVerticalDelta(),
				observation.maximumHorizontalDelta(), observation.suddenDisplacements(), observation.pitchBoundaryEvents());
			Finding.Severity severity = observation.notes().isEmpty() ? Finding.Severity.INFO : Finding.Severity.LOW;
			findings.add(new Finding(severity, observation.playerName(), detail,
				observation.notes().isEmpty() ? "No broad note in the current local sample." : String.join("; ", observation.notes())));
		}
		if (observations.isEmpty()) findings.add(new Finding(Finding.Severity.INFO, "Motion evidence", "No matching remote observations yet.",
			"Observe an authorized test session for several seconds, then run the module again."));
		return new ModuleResult("Motion Evidence summary completed", findings);
	}

	private static int boundedInt(String value, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
