package dev.champions.surface;

import dev.champions.core.console.ConsoleLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Toolkit-neutral terminal model shared by the in-game console screen and the
 * external desktop terminal.
 *
 * <p>It owns the bounded scrollback (the same 1 000-line cap the in-game
 * console uses), the command history and its cursor, and a monotonically
 * increasing revision counter that lets either view repaint only when
 * something actually changed. It contains no rendering code and no Minecraft
 * imports, so it is unit-testable headlessly and usable in both hosts.</p>
 */
public final class ConsoleBuffer {
	/** Same bound as {@code ChampionConsole.MAX_LINES}. */
	public static final int MAX_LINES = 1_000;

	private final List<ConsoleLine> lines = new ArrayList<>();
	private final List<String> history = new ArrayList<>();
	private int historyCursor;
	private long revision;

	public synchronized long revision() {
		return revision;
	}

	public synchronized int size() {
		return lines.size();
	}

	public synchronized List<ConsoleLine> snapshot() {
		return List.copyOf(lines);
	}

	/** Lines appended after {@code index}; {@code index} is clamped to the buffer. */
	public synchronized List<ConsoleLine> since(int index) {
		int from = Math.max(0, Math.min(index, lines.size()));
		return List.copyOf(lines.subList(from, lines.size()));
	}

	public synchronized ConsoleLine last() {
		return lines.isEmpty() ? null : lines.get(lines.size() - 1);
	}

	public synchronized void append(ConsoleLine line) {
		lines.add(Objects.requireNonNull(line, "line"));
		trim();
		revision++;
	}

	public synchronized void appendAll(List<ConsoleLine> batch) {
		if (batch == null || batch.isEmpty()) return;
		int before = lines.size();
		for (ConsoleLine line : batch) lines.add(Objects.requireNonNull(line, "line"));
		if (lines.size() == before) return;
		trim();
		revision++;
	}

	/**
	 * Replaces the whole buffer, used when an attached session resynchronises
	 * after a reconnect. No-op when the content is identical, so a poll that
	 * changes nothing never forces a repaint.
	 */
	public synchronized void reset(List<ConsoleLine> replacement) {
		List<ConsoleLine> next = replacement == null ? List.of() : List.copyOf(replacement);
		if (next.equals(lines)) return;
		lines.clear();
		lines.addAll(next);
		trim();
		revision++;
	}

	public synchronized void clear() {
		if (lines.isEmpty()) return;
		lines.clear();
		revision++;
	}

	/** Appends the "N lines dropped" marker used when a remote delta overflows. */
	public synchronized void noteDropped(int count) {
		if (count <= 0) return;
		lines.add(ConsoleLine.warning("[... " + count + " line(s) dropped by the session bridge ...]"));
		trim();
		revision++;
	}

	// ---- history ---------------------------------------------------------

	public synchronized void recordCommand(String command) {
		String value = command == null ? "" : command.strip();
		if (value.isEmpty()) return;
		if (history.isEmpty() || !history.getLast().equals(value)) history.add(value);
		historyCursor = history.size();
		revision++;
	}

	public synchronized List<String> history() {
		return List.copyOf(history);
	}

	public synchronized int historyCursor() {
		return historyCursor;
	}

	/** Returns the previous history entry, or null when already at the oldest. */
	public synchronized String previousCommand() {
		if (history.isEmpty() || historyCursor <= 0) return null;
		historyCursor--;
		return history.get(historyCursor);
	}

	/** Returns the next history entry, or "" when stepping past the newest one. */
	public synchronized String nextCommand() {
		if (history.isEmpty()) return null;
		if (historyCursor >= history.size() - 1) {
			historyCursor = history.size();
			return "";
		}
		historyCursor++;
		return history.get(historyCursor);
	}

	public synchronized void resetHistoryCursor() {
		historyCursor = history.size();
	}

	private void trim() {
		int excess = lines.size() - MAX_LINES;
		if (excess > 0) lines.subList(0, excess).clear();
	}
}
