package dev.champions.core.log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Substring search over a snapshot of {@link LogLine}s.
 *
 * The search is case-insensitive unless asked otherwise, it reports every
 * occurrence inside a line (the viewer highlights all of them, not just the
 * first), and it is bounded: past {@link #MAX_HITS} occurrences the result is
 * flagged as truncated so the status bar can say so instead of freezing on a
 * query that matches everything.
 */
public final class LogSearch {
	public static final int MAX_HITS = 2000;
	private static final int MAX_HITS_PER_LINE = 64;

	private final String query;
	private final String needle;
	private final boolean caseSensitive;

	public LogSearch(String rawQuery, boolean caseSensitive) {
		this.query = rawQuery == null ? "" : rawQuery.strip();
		this.caseSensitive = caseSensitive;
		this.needle = caseSensitive ? this.query : this.query.toLowerCase(Locale.ROOT);
	}

	/** An occurrence of the query: {@code [start, end)} inside line {@code lineIndex}. */
	public record Hit(int lineIndex, int start, int end) {
		public int length() {
			return end - start;
		}
	}

	/** Every occurrence in a snapshot, plus whether {@link #MAX_HITS} cut it short. */
	public record Hits(List<Hit> hits, boolean truncated) {
		public int count() {
			return hits.size();
		}

		public boolean isEmpty() {
			return hits.isEmpty();
		}
	}

	public boolean active() {
		return !needle.isEmpty();
	}

	public String query() {
		return query;
	}

	public boolean caseSensitive() {
		return caseSensitive;
	}

	public int length() {
		return needle.length();
	}

	public boolean matches(String text) {
		return indexOf(text, 0) >= 0;
	}

	/** All occurrences inside one line, as {@code [start, end)} pairs. */
	public List<int[]> occurrences(String text, int limit) {
		List<int[]> result = new ArrayList<>();
		if (!active() || limit <= 0) return result;
		String subject = text == null ? "" : text;
		int from = 0;
		while (result.size() < limit) {
			int at = indexOf(subject, from);
			if (at < 0) break;
			result.add(new int[] {at, at + needle.length()});
			from = at + Math.max(1, needle.length());
		}
		return result;
	}

	/** All occurrences across a snapshot, in reading order, bounded by {@link #MAX_HITS}. */
	public Hits hits(List<LogLine> lines) {
		List<Hit> result = new ArrayList<>();
		if (!active()) return new Hits(result, false);
		for (int i = 0; i < lines.size(); i++) {
			for (int[] range : occurrences(lines.get(i).text(), MAX_HITS_PER_LINE)) {
				if (result.size() >= MAX_HITS) return new Hits(result, true);
				result.add(new Hit(i, range[0], range[1]));
			}
		}
		return new Hits(result, false);
	}

	/**
	 * First occurrence strictly after the given position, wrapping to the top of
	 * the snapshot when the search runs off the end. "Strictly" is what keeps a
	 * repeated Enter press moving instead of sticking to the current match.
	 */
	public Hit next(List<Hit> hits, int fromLine, int fromOffset) {
		if (hits.isEmpty()) return null;
		for (Hit hit : hits) {
			if (hit.lineIndex() > fromLine || (hit.lineIndex() == fromLine && hit.start() > fromOffset)) {
				return hit;
			}
		}
		return hits.getFirst();
	}

	/** Last occurrence strictly before the given position, wrapping to the bottom. */
	public Hit previous(List<Hit> hits, int fromLine, int fromOffset) {
		if (hits.isEmpty()) return null;
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.lineIndex() < fromLine || (hit.lineIndex() == fromLine && hit.start() < fromOffset)) {
				return hit;
			}
		}
		return hits.getLast();
	}

	private int indexOf(String text, int from) {
		if (!active()) return -1;
		if (caseSensitive) return text.indexOf(needle, from);
		return text.toLowerCase(Locale.ROOT).indexOf(needle, from);
	}
}
