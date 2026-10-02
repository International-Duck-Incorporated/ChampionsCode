package dev.champions.core.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, incremental reader for a log file another process is writing, such
 * as the running game's {@code logs/latest.log}.
 *
 * The tail remembers a byte position, so {@link #poll()} only decodes what was
 * appended since the previous call, and it decodes whole lines only: a line
 * that is still being written waits for its newline, and a multi-byte character
 * is never split across two decodes. The buffered line count is capped; once
 * the cap is reached the oldest lines are dropped. When the file is replaced or
 * truncated (the log rotates on every launch), the reader restarts at the
 * beginning and keeps the lines it already had, so the window never blanks out.
 *
 * All state is guarded by the instance monitor, so a UI thread can take
 * snapshots while another thread polls.
 */
public final class LogTail {
	/** Bytes decoded per poll; larger backlogs drain over several polls. */
	private static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;
	/** Default start-of-read window for a log that already exists when opened. */
	public static final long DEFAULT_INITIAL_READ_BYTES = 2 * 1024 * 1024;
	/** Shown in place of the missing lines when the log rotates. */
	private static final String ROTATION_MARKER = "--- log file rotated: re-reading from the start ---";

	private final Path file;
	private final int maxLines;
	private final long initialReadBytes;
	private final Deque<LogLine> lines = new ArrayDeque<>();
	private long position;
	private long linesRead;
	private long droppedLines;
	private boolean filePresent;
	private boolean everRead;
	private boolean reading;
	private boolean skipPartialFirstLine;
	private Object fileKey;
	private String problem = "";

	public LogTail(Path file, int maxLines) {
		this(file, maxLines, DEFAULT_INITIAL_READ_BYTES);
	}

	/**
	 * @param initialReadBytes when the log already exists at open time, reading
	 *                         starts this many bytes before its end instead of
	 *                         at byte zero, so opening the window on a long log
	 *                         never decodes the whole file
	 */
	public LogTail(Path file, int maxLines, long initialReadBytes) {
		this.file = Objects.requireNonNull(file, "file");
		this.maxLines = Math.max(1, maxLines);
		this.initialReadBytes = Math.max(0, initialReadBytes);
	}

	/**
	 * Reads whatever has been appended since the last poll.
	 *
	 * @return true when the buffered lines changed, so callers can mark their
	 *         view dirty without comparing snapshots
	 */
	public synchronized boolean poll() {
		BasicFileAttributes attributes;
		try {
			attributes = Files.readAttributes(file, BasicFileAttributes.class);
		} catch (IOException error) {
			// Not created yet, rotated away, or unreadable: stay quiet and try
			// again on the next poll instead of reporting a permanent failure.
			filePresent = Files.exists(file);
			reading = false;
			if (!filePresent) problem = "";
			else problem = describe(error);
			return false;
		}
		if (!attributes.isRegularFile()) {
			filePresent = false;
			reading = false;
			problem = "";
			return false;
		}
		filePresent = true;
		problem = "";

		long size = attributes.size();
		Object key = attributes.fileKey();
		// A log rotation can hand us a file that is already longer than the
		// position we remember; the size test alone would then read from the
		// middle of the new file. The file identity catches that case too.
		boolean replaced = everRead && key != null && fileKey != null && !key.equals(fileKey);
		fileKey = key;

		boolean changed = false;
		if (!everRead) {
			// First read: start at the end of the log unless it is short enough
			// to read in full, so opening the window on a long log stays cheap.
			position = size > initialReadBytes ? size - initialReadBytes : 0;
			skipPartialFirstLine = position > 0;
			everRead = true;
		} else if (!reading || replaced || size < position) {
			// The file was replaced, truncated, or came back after a rotation:
			// read the new log from the start and keep what we already showed,
			// separated by a marker, so the window never blanks out.
			position = 0;
			skipPartialFirstLine = false;
			if (!lines.isEmpty()) {
				lines.addLast(new LogLine(ROTATION_MARKER, LogLine.Level.UNKNOWN));
				changed = true;
			}
		}
		reading = true;
		if (size <= position) return changed;

		byte[] chunk;
		try {
			chunk = readFrom(position, (int) Math.min(size - position, MAX_CHUNK_BYTES));
		} catch (IOException error) {
			problem = describe(error);
			return changed;
		}
		problem = "";

		int start = 0;
		if (skipPartialFirstLine) {
			int firstNewline = indexOfNewline(chunk, 0);
			if (firstNewline < 0) {
				// The whole window is the tail of one line: nothing to show yet.
				position += chunk.length;
				return changed;
			}
			start = firstNewline + 1;
			skipPartialFirstLine = false;
		}
		int lastNewline = lastIndexOfNewline(chunk);
		if (lastNewline < start) {
			// Only the beginning of a line has been written so far; wait for it.
			return changed;
		}

		String text = new String(chunk, start, lastNewline - start, StandardCharsets.UTF_8);
		position += lastNewline + 1;
		for (String raw : text.split("\n", -1)) {
			if (raw.endsWith("\r")) raw = raw.substring(0, raw.length() - 1);
			append(LogLine.of(raw));
			changed = true;
		}
		return changed;
	}

	private void append(LogLine line) {
		lines.addLast(line);
		linesRead++;
		while (lines.size() > maxLines) {
			lines.removeFirst();
			droppedLines++;
		}
	}

	private byte[] readFrom(long offset, int length) throws IOException {
		byte[] buffer = new byte[length];
		try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
			channel.position(offset);
			ByteBuffer target = ByteBuffer.wrap(buffer);
			int read = 0;
			while (target.hasRemaining()) {
				int step = channel.read(target);
				if (step < 0) break;
				read += step;
			}
			if (read == length) return buffer;
			byte[] shorter = new byte[read];
			System.arraycopy(buffer, 0, shorter, 0, read);
			return shorter;
		}
	}

	private static int indexOfNewline(byte[] chunk, int from) {
		for (int i = from; i < chunk.length; i++) {
			if (chunk[i] == '\n') return i;
		}
		return -1;
	}

	private static int lastIndexOfNewline(byte[] chunk) {
		for (int i = chunk.length - 1; i >= 0; i--) {
			if (chunk[i] == '\n') return i;
		}
		return -1;
	}

	/** Snapshot of the buffered lines, oldest first. */
	public synchronized List<LogLine> lines() {
		return new ArrayList<>(lines);
	}

	/** Drops the buffered lines; reading continues from the current position. */
	public synchronized void clear() {
		lines.clear();
		linesRead = 0;
		droppedLines = 0;
	}

	public synchronized int bufferedLines() {
		return lines.size();
	}

	/** Lines appended since construction, including lines already dropped. */
	public synchronized long linesRead() {
		return linesRead;
	}

	/** How many lines the cap has evicted, so the UI can say the tail is partial. */
	public synchronized long droppedLines() {
		return droppedLines;
	}

	/**
	 * Line number of the oldest buffered line (1-based, counting every line read
	 * this session). Numbers derived from it stay stable while the log grows,
	 * which lets a selection and a match marker survive new lines and the
	 * eviction of old ones.
	 */
	public synchronized long numberOffset() {
		return linesRead - lines.size();
	}

	public synchronized int maxLines() {
		return maxLines;
	}

	public Path file() {
		return file;
	}

	/** True once the log file has been seen on disk at least once. */
	public synchronized boolean filePresent() {
		return filePresent;
	}

	/** Human-readable read failure, or an empty string while reading normally. */
	public synchronized String problem() {
		return problem;
	}

	private static String describe(IOException error) {
		String message = error.getMessage();
		if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
		return message.replace('\n', ' ').replace('\r', ' ');
	}
}
