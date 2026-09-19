package dev.champions.core.console;

import java.util.Objects;

/** A single, styled line in the Champions console. */
public record ConsoleLine(Tone tone, String text) {
	public enum Tone { MUTED, INFO, SUCCESS, WARNING, ERROR, ACCENT }

	public ConsoleLine {
		Objects.requireNonNull(tone, "tone");
		text = Objects.requireNonNullElse(text, "")
			.replace('\r', ' ')
			.replace('\t', ' ');
	}

	public static ConsoleLine info(String text) { return new ConsoleLine(Tone.INFO, text); }
	public static ConsoleLine muted(String text) { return new ConsoleLine(Tone.MUTED, text); }
	public static ConsoleLine success(String text) { return new ConsoleLine(Tone.SUCCESS, text); }
	public static ConsoleLine warning(String text) { return new ConsoleLine(Tone.WARNING, text); }
	public static ConsoleLine error(String text) { return new ConsoleLine(Tone.ERROR, text); }
	public static ConsoleLine accent(String text) { return new ConsoleLine(Tone.ACCENT, text); }
}
