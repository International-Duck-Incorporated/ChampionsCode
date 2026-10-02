package dev.champions.core.log;

import java.util.Objects;

/**
 * One line of a Minecraft log file together with the log level detected from
 * its {@code [thread/LEVEL]:} prefix.
 *
 * Lines that carry no recognizable level - stack-trace continuations, banner
 * separators, plugin output - are reported as {@link Level#UNKNOWN}, which
 * sorts above every real level so a level filter never hides them.
 */
public record LogLine(String text, Level level) {
	/** How far into a line the {@code [thread/LEVEL]} prefix is searched for. */
	private static final int PREFIX_SCAN_LIMIT = 160;
	/** Checked in this order so FATAL is not shadowed by a shorter name. */
	private static final Level[] KNOWN_LEVELS = {
		Level.FATAL, Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE
	};

	public enum Level {
		TRACE, DEBUG, INFO, WARN, ERROR, FATAL, UNKNOWN;

		/**
		 * Ordering used by the viewer's minimum-level filter. UNKNOWN ranks
		 * above FATAL on purpose: a continuation line belongs to a record that
		 * may be an error, so it stays visible under every filter.
		 */
		public int rank() {
			return switch (this) {
				case TRACE -> 0;
				case DEBUG -> 1;
				case INFO -> 2;
				case WARN -> 3;
				case ERROR -> 4;
				case FATAL -> 5;
				case UNKNOWN -> 6;
			};
		}

		public boolean atLeast(Level minimum) {
			return rank() >= minimum.rank();
		}
	}

	public LogLine {
		Objects.requireNonNull(level, "level");
		text = Objects.requireNonNullElse(text, "")
			.replace('\r', ' ')
			.replace('\t', ' ');
	}

	/**
	 * Normalizes one raw log line. Tabs become spaces because the game font has
	 * no tab stop, trailing whitespace (including the CR of a CRLF log) is
	 * dropped, and leading indentation is preserved so stack traces stay
	 * readable.
	 */
	public static LogLine of(String text) {
		String normalized = Objects.requireNonNullElse(text, "").stripTrailing();
		return new LogLine(normalized, parseLevel(normalized));
	}

	public boolean atLeast(Level minimum) {
		return level.atLeast(minimum);
	}

	/**
	 * Reads the level from the first {@code /LEVEL} marker in a log prefix
	 * (log4j writes {@code [12:00:00] [Render thread/INFO]: message}, older
	 * releases write {@code [12:00:00] [Client thread/WARN] [minecraft/X]:}).
	 */
	public static Level parseLevel(String text) {
		String candidate = Objects.requireNonNullElse(text, "");
		int limit = Math.min(candidate.length(), PREFIX_SCAN_LIMIT);
		for (int i = 0; i < limit; i++) {
			if (candidate.charAt(i) != '/') continue;
			Level level = levelAt(candidate, i + 1);
			if (level != null) return level;
		}
		return Level.UNKNOWN;
	}

	private static Level levelAt(String text, int at) {
		for (Level level : KNOWN_LEVELS) {
			String name = level.name();
			int end = at + name.length();
			if (end > text.length() || !text.regionMatches(at, name, 0, name.length())) continue;
			// A level marker is followed by the closing bracket of the thread
			// segment or by the separator of the message.
			char terminator = end < text.length() ? text.charAt(end) : ']';
			if (terminator == ']' || terminator == ':' || terminator == ' ') return level;
		}
		return null;
	}
}
