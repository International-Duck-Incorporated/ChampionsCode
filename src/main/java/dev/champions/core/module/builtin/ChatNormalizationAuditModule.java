package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Defensive-only canonicalization review. It never generates bypass variants. */
public final class ChatNormalizationAuditModule implements ResearchModule {
	private static final Set<Integer> INVISIBLE = Set.of(
		0x00AD, 0x034F, 0x061C, 0x180E, 0x200B, 0x200C, 0x200D,
		0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0xFEFF
	);
	private static final Map<Integer, Integer> CONFUSABLES = Map.ofEntries(
		Map.entry((int) 'а', (int) 'a'), Map.entry((int) 'А', (int) 'a'),
		Map.entry((int) 'е', (int) 'e'), Map.entry((int) 'Е', (int) 'e'),
		Map.entry((int) 'о', (int) 'o'), Map.entry((int) 'О', (int) 'o'),
		Map.entry((int) 'с', (int) 'c'), Map.entry((int) 'С', (int) 'c'),
		Map.entry((int) 'р', (int) 'p'), Map.entry((int) 'Р', (int) 'p'),
		Map.entry((int) 'х', (int) 'x'), Map.entry((int) 'Х', (int) 'x'),
		Map.entry((int) 'у', (int) 'y'), Map.entry((int) 'У', (int) 'y'),
		Map.entry((int) 'ѕ', (int) 's'), Map.entry((int) 'Ѕ', (int) 's'),
		Map.entry((int) 'і', (int) 'i'), Map.entry((int) 'І', (int) 'i'),
		Map.entry((int) 'п', (int) 'n'), Map.entry((int) 'П', (int) 'n'),
		Map.entry((int) 'м', (int) 'm'), Map.entry((int) 'М', (int) 'm')
	);
	private static final Map<Integer, Integer> SUBSTITUTIONS = Map.ofEntries(
		Map.entry((int) '0', (int) 'o'), Map.entry((int) '1', (int) 'i'),
		Map.entry((int) '3', (int) 'e'), Map.entry((int) '4', (int) 'a'),
		Map.entry((int) '5', (int) 's'), Map.entry((int) '7', (int) 't'),
		Map.entry((int) '@', (int) 'a')
	);

	@Override public String id() { return "auxiliary/audit/chat_normalization"; }
	@Override public String name() { return "Chat normalization audit"; }
	@Override public String description() {
		return "Canonicalizes one local sample for defensive filter testing by removing invisible controls, formatting codes, combining marks, selected Cyrillic lookalikes, and common substitutions. It never sends chat or creates bypass text.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("SAMPLE", "", false, false, "Local sample to canonicalize, limited to 512 code points")
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		String sample = options.getOrDefault("SAMPLE", "");
		if (sample.isBlank()) {
			return new ModuleResult("Chat normalization audit needs a local sample", List.of(
				new Finding(Finding.Severity.INFO, "No sample supplied",
					"SAMPLE is empty. Nothing was read from chat history and nothing was sent.",
					"Provide a synthetic or redacted test string. Do not paste private messages, tokens, or personal data.")
			));
		}

		NormalizationResult normalized = normalize(sample, 512);
		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO, "Defensive canonical form",
			normalized.canonical().isBlank() ? "<empty after normalization>" : normalized.canonical(),
			"Compare deny-list and phrase rules against a canonical form, while preserving the original only where moderation policy and privacy rules allow."));
		if (normalized.truncated()) {
			findings.add(new Finding(Finding.Severity.LOW, "Sample length bound",
				"Only the first 512 code points were reviewed.",
				"Apply the same normalization incrementally or reject overlong input server-side."));
		}
		if (normalized.invisibleCount() > 0) {
			findings.add(new Finding(Finding.Severity.MEDIUM, "Invisible or default-ignorable characters",
				normalized.invisibleCount() + " characters were removed before comparison.",
				"Strip or explicitly reject unexpected invisible format characters before moderation checks."));
		}
		if (normalized.mixedLatinCyrillic()) {
			findings.add(new Finding(Finding.Severity.MEDIUM, "Mixed Latin and Cyrillic scripts",
				"The sample mixed scripts commonly involved in visual-confusable text.",
				"Apply a maintained confusable-skeleton policy and review legitimate multilingual use before blocking."));
		}
		if (normalized.confusableCount() > 0 || normalized.substitutionCount() > 0) {
			findings.add(new Finding(Finding.Severity.LOW, "Visual substitutions",
				normalized.confusableCount() + " selected confusables and " + normalized.substitutionCount() + " common substitutions were folded.",
				"Test policy rules against canonical text and retain human review for ambiguous language."));
		}
		if (normalized.formattingCount() > 0 || normalized.combiningMarkCount() > 0) {
			findings.add(new Finding(Finding.Severity.LOW, "Formatting or combining marks",
				normalized.formattingCount() + " Minecraft formatting pairs and " + normalized.combiningMarkCount() + " combining marks were removed.",
				"Normalize Unicode consistently before matching and log only the minimum moderation evidence needed."));
		}
		if (findings.size() == 1) {
			findings.add(new Finding(Finding.Severity.PASS, "Selected normalization checks",
				"No selected invisible, mixed-script, confusable, substitution, formatting, or combining-mark signal was found.",
				"Keep server-side normalization libraries current and test false positives with the languages your community uses."));
		}
		return new ModuleResult("Local chat normalization audit completed", findings);
	}

	static NormalizationResult normalize(String input, int maxCodePoints) {
		int total = input.codePointCount(0, input.length());
		int allowed = Math.min(total, maxCodePoints);
		int end = input.offsetByCodePoints(0, allowed);
		String bounded = input.substring(0, end);
		boolean latin = false;
		boolean cyrillic = false;
		int invisible = 0;
		int formatting = 0;
		StringBuilder firstPass = new StringBuilder();
		for (int index = 0; index < bounded.length();) {
			int codePoint = bounded.codePointAt(index);
			index += Character.charCount(codePoint);
			Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
			latin |= script == Character.UnicodeScript.LATIN;
			cyrillic |= script == Character.UnicodeScript.CYRILLIC;
			if (INVISIBLE.contains(codePoint) || isBidiControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
				invisible++;
				continue;
			}
			if (codePoint == '§' && index < bounded.length()) {
				int next = bounded.codePointAt(index);
				if (isMinecraftFormatCode(next)) {
					index += Character.charCount(next);
					formatting++;
					continue;
				}
			}
			if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
				invisible++;
				continue;
			}
			firstPass.appendCodePoint(codePoint);
		}

		String decomposed = Normalizer.normalize(firstPass, Normalizer.Form.NFKD);
		StringBuilder canonical = new StringBuilder();
		int combining = 0;
		int confusable = 0;
		int substitutions = 0;
		boolean lastWhitespace = false;
		for (int index = 0; index < decomposed.length();) {
			int codePoint = decomposed.codePointAt(index);
			index += Character.charCount(codePoint);
			int type = Character.getType(codePoint);
			if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK) {
				combining++;
				continue;
			}
			Integer confusableReplacement = CONFUSABLES.get(codePoint);
			if (confusableReplacement != null) {
				codePoint = confusableReplacement;
				confusable++;
			}
			Integer substitution = SUBSTITUTIONS.get(codePoint);
			if (substitution != null) {
				codePoint = substitution;
				substitutions++;
			}
			if (Character.isWhitespace(codePoint)) {
				if (!lastWhitespace && !canonical.isEmpty()) canonical.append(' ');
				lastWhitespace = true;
				continue;
			}
			lastWhitespace = false;
			canonical.appendCodePoint(Character.toLowerCase(codePoint));
		}
		String result = canonical.toString().strip();
		return new NormalizationResult(result, total > maxCodePoints, invisible, formatting, combining,
			confusable, substitutions, latin && cyrillic);
	}

	private static boolean isBidiControl(int codePoint) {
		return codePoint == 0x200E || codePoint == 0x200F || (codePoint >= 0x202A && codePoint <= 0x202E) ||
			(codePoint >= 0x2066 && codePoint <= 0x2069);
	}

	private static boolean isMinecraftFormatCode(int codePoint) {
		char value = Character.toLowerCase((char) codePoint);
		return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f') ||
			value == 'k' || value == 'l' || value == 'm' || value == 'n' || value == 'o' || value == 'r';
	}

	record NormalizationResult(String canonical, boolean truncated, int invisibleCount, int formattingCount,
		int combiningMarkCount, int confusableCount, int substitutionCount, boolean mixedLatinCyrillic) {}
}
