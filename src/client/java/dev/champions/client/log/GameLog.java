package dev.champions.client.log;

import dev.champions.core.log.LogTail;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Shared, lazily opened view of the running game's output log
 * ({@code logs/latest.log} in the game directory - the same file the launcher's
 * "Open Log" button shows).
 *
 * One tail instance is shared by every window that reads it, so closing and
 * reopening the viewer keeps the buffered history and its read position. The
 * file is polled through {@link #refresh()}, which is rate limited so a busy
 * log can never cost more than one short read per frame budget.
 */
public final class GameLog {
	/** Lines kept buffered; older lines are dropped as the log grows. */
	public static final int MAX_LINES = 4_000;
	/** Minimum gap between two reads of the file. */
	private static final long POLL_INTERVAL_MILLIS = 400L;

	private static LogTail tail;
	private static long lastPoll;
	private static boolean polledOnce;

	private GameLog() {}

	/** The shared tail for this game session, created on first use. */
	public static synchronized LogTail tail() {
		if (tail == null) {
			Path file = FabricLoader.getInstance().getGameDir()
				.resolve("logs")
				.resolve("latest.log");
			tail = new LogTail(file, MAX_LINES);
			lastPoll = 0L;
			polledOnce = false;
		}
		return tail;
	}

	/**
	 * Reads whatever the game appended since the last call, at most once per
	 * {@link #POLL_INTERVAL_MILLIS}.
	 *
	 * @return true when the buffered lines changed
	 */
	public static synchronized boolean refresh() {
		LogTail current = tail();
		long now = System.currentTimeMillis();
		if (polledOnce && now - lastPoll < POLL_INTERVAL_MILLIS) return false;
		polledOnce = true;
		lastPoll = now;
		return current.poll();
	}
}
