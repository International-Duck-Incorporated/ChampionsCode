package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Classifies a supplied game prompt without solving it or interacting with chat. */
public final class ChatGameFairnessModule implements ResearchModule {
	private static final int MAX_CODE_POINTS = 512;
	private static final Pattern VARIABLE_EQUATION = Pattern.compile(".*[a-z]\\s*[+*/-].*=\\s*-?\\d+.*");
	private static final Pattern NUMBER_EQUATION = Pattern.compile(".*-?\\d+\\s*[+*/-]\\s*-?\\d+.*");

	@Override public String id() { return "auxiliary/audit/chat_game_fairness"; }
	@Override public String name() { return "Chat game fairness review"; }
	@Override public String description() {
		return "Classifies one synthetic or redacted chat-game announcement and reports automation exposure. It does not compute an answer, monitor live chat, or send messages.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("SAMPLE", "", false, false, "Synthetic or redacted game announcement, limited to 512 code points")
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		String raw = options.getOrDefault("SAMPLE", "");
		if (raw.isBlank()) {
			return new ModuleResult("Chat game fairness review needs a sample", List.of(
				new Finding(Finding.Severity.INFO, "No sample supplied",
					"Nothing was read from live chat and no response was generated.",
					"Provide a synthetic or redacted announcement that does not contain player messages or personal data.")
			));
		}

		BoundedSample sample = bound(stripFormatting(raw), MAX_CODE_POINTS);
		GameType type = detect(sample.text());
		Finding exposure = exposure(type);
		List<Finding> findings = sample.truncated() ? List.of(
			new Finding(Finding.Severity.INFO, "Prompt classification", type.label,
				"Treat this as a heuristic classification, not proof of the installed plugin or configuration."),
			exposure,
			new Finding(Finding.Severity.LOW, "Sample length bound", "Only the first 512 code points were classified.",
				"Keep announcements bounded and avoid embedding secrets or player-controlled configuration."),
			boundaryFinding()
		) : List.of(
			new Finding(Finding.Severity.INFO, "Prompt classification", type.label,
				"Treat this as a heuristic classification, not proof of the installed plugin or configuration."),
			exposure,
			boundaryFinding()
		);
		return new ModuleResult("Local chat game fairness review completed", findings);
	}

	static GameType detect(String raw) {
		String line = stripFormatting(raw).toLowerCase(Locale.ROOT).replace('`', ' ').replaceAll("\\s+", " ").strip();
		if (line.contains("to unscramble")) return GameType.UNSCRAMBLE;
		if (line.contains("to unreverse")) return GameType.UNREVERSE;
		if (line.contains("to write out the word")) return GameType.REACTION;
		if (line.contains("to fill in")) return GameType.FILL_OUT;
		if (line.contains("to write out")) return GameType.RANDOM_COPY;
		if (line.contains("to solve for") || VARIABLE_EQUATION.matcher(line).matches()) return GameType.VARIABLE;
		if (line.contains("to solve") || NUMBER_EQUATION.matcher(line).matches()) return GameType.MATH;
		if (line.contains("question:")) return GameType.TRIVIA;
		return GameType.UNKNOWN;
	}

	private static Finding exposure(GameType type) {
		return switch (type) {
			case UNREVERSE, RANDOM_COPY, MATH, VARIABLE -> new Finding(Finding.Severity.MEDIUM,
				"Directly derivable response pattern",
				"This prompt family exposes enough structured information for fast deterministic automation. No answer was computed.",
				"Use per-player challenges, server-side minimum response times, bounded reward frequency, and anomaly review rather than speed alone.");
			case REACTION -> new Finding(Finding.Severity.MEDIUM, "Latency-dominated competition",
				"A first-to-repeat design rewards automation and network latency more than knowledge.",
				"Prefer per-player prompts or randomized acceptance windows and rate-limit repeated rewards.");
			case UNSCRAMBLE, FILL_OUT -> new Finding(Finding.Severity.LOW, "Dictionary-automation exposure",
				"A finite word source can be indexed or solved mechanically even though no answer was derived here.",
				"Rotate large word sets, use per-player prompts, and avoid high-value rewards based only on response speed.");
			case TRIVIA -> new Finding(Finding.Severity.LOW, "Answer-bank exposure",
				"Static question and answer sets can be learned or shared by automated clients.",
				"Rotate questions, avoid predictable IDs, and cap rewards while preserving accessible response times.");
			case UNKNOWN -> new Finding(Finding.Severity.INFO, "Unrecognized prompt family",
				"The bounded classifier did not match a known default-style announcement.",
				"Review the server-side game rules and reward design manually; do not broaden client automation to compensate.");
		};
	}

	private static Finding boundaryFinding() {
		return new Finding(Finding.Severity.PASS, "Non-interaction boundary",
			"The module classified only the supplied option. It did not monitor chat, solve the challenge, or send a response.",
			"Keep fairness assessment server-side and preserve clear player-facing rules.");
	}

	private static String stripFormatting(String value) {
		StringBuilder out = new StringBuilder();
		for (int index = 0; index < value.length();) {
			int codePoint = value.codePointAt(index);
			index += Character.charCount(codePoint);
			if (codePoint == '§' && index < value.length()) {
				int next = value.codePointAt(index);
				if (isFormatCode(next)) {
					index += Character.charCount(next);
					continue;
				}
			}
			out.appendCodePoint(codePoint);
		}
		return out.toString();
	}

	private static boolean isFormatCode(int codePoint) {
		char value = Character.toLowerCase((char) codePoint);
		return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f') ||
			value == 'k' || value == 'l' || value == 'm' || value == 'n' || value == 'o' || value == 'r';
	}

	private static BoundedSample bound(String value, int maxCodePoints) {
		int count = value.codePointCount(0, value.length());
		if (count <= maxCodePoints) return new BoundedSample(value, false);
		return new BoundedSample(value.substring(0, value.offsetByCodePoints(0, maxCodePoints)), true);
	}

	enum GameType {
		UNSCRAMBLE("unscramble"), UNREVERSE("unreverse"), REACTION("reaction copy"),
		FILL_OUT("fill-out word"), RANDOM_COPY("random-string copy"), MATH("arithmetic"),
		VARIABLE("variable equation"), TRIVIA("trivia"), UNKNOWN("unknown");

		private final String label;
		GameType(String label) { this.label = label; }
	}

	private record BoundedSample(String text, boolean truncated) {}
}
