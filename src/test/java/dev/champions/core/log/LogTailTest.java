package dev.champions.core.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogTailTest {
	@TempDir Path temp;

	private static void append(Path file, String text) throws Exception {
		Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private static List<String> texts(LogTail tail) {
		return tail.lines().stream().map(LogLine::text).toList();
	}

	@Test void readsOnlyCompleteLinesAndKeepsAppending() throws Exception {
		Path file = temp.resolve("latest.log");
		Files.createFile(file);
		LogTail tail = new LogTail(file, 10);

		assertFalse(tail.poll());
		assertTrue(tail.filePresent());

		append(file, "[12:00:00] [Render thread/INFO]: first line\n");
		assertTrue(tail.poll());
		assertEquals(List.of("[12:00:00] [Render thread/INFO]: first line"), texts(tail));
		assertEquals(LogLine.Level.INFO, tail.lines().getFirst().level());

		// A half-written line waits for its newline instead of showing up torn.
		append(file, "[12:00:01] [Render thread/WARN]: second");
		assertFalse(tail.poll());
		assertEquals(1, tail.bufferedLines());

		append(file, " line\n[12:00:02] [main/ERROR]: third\n");
		assertTrue(tail.poll());
		assertEquals(List.of(
			"[12:00:00] [Render thread/INFO]: first line",
			"[12:00:01] [Render thread/WARN]: second line",
			"[12:00:02] [main/ERROR]: third"), texts(tail));
		assertEquals(3, tail.linesRead());

		// Polling with nothing new reports "no change" so views stay still.
		assertFalse(tail.poll());
	}

	@Test void decodesMultiByteCharactersAcrossWrites() throws Exception {
		Path file = temp.resolve("latest.log");
		Files.createFile(file);
		LogTail tail = new LogTail(file, 10);
		byte[] bytes = "[main/INFO]: spelers \u00fc\u00f6\u00e0\u4e2d\u6587\n".getBytes(StandardCharsets.UTF_8);
		// Split in the middle of a multi-byte character, then finish it.
		Files.write(file, java.util.Arrays.copyOfRange(bytes, 0, bytes.length - 4));
		assertFalse(tail.poll());
		Files.write(file, java.util.Arrays.copyOfRange(bytes, bytes.length - 4, bytes.length),
			StandardOpenOption.APPEND);
		assertTrue(tail.poll());
		assertEquals("[main/INFO]: spelers \u00fc\u00f6\u00e0\u4e2d\u6587", texts(tail).getFirst());
	}

	@Test void dropsOldestLinesAtTheCapAndReportsIt() throws Exception {
		Path file = temp.resolve("latest.log");
		Files.createFile(file);
		LogTail tail = new LogTail(file, 3);
		StringBuilder builder = new StringBuilder();
		for (int i = 1; i <= 5; i++) builder.append("[main/INFO]: line ").append(i).append('\n');
		append(file, builder.toString());
		assertTrue(tail.poll());
		assertEquals(List.of("[main/INFO]: line 3", "[main/INFO]: line 4", "[main/INFO]: line 5"), texts(tail));
		assertEquals(5, tail.linesRead());
		assertEquals(2, tail.droppedLines());
		assertEquals(3, tail.maxLines());
		// The window keeps stable numbers: the oldest buffered line is #3.
		assertEquals(2L, tail.numberOffset());
		assertEquals("[main/INFO]: line 3", tail.lines().getFirst().text());
	}

	@Test void restartsAfterTruncationAndMarksTheRotation() throws Exception {
		Path file = temp.resolve("latest.log");
		Files.writeString(file, "[main/INFO]: old one\n[main/INFO]: old two\n");
		LogTail tail = new LogTail(file, 20);
		assertTrue(tail.poll());
		assertEquals(2, tail.bufferedLines());

		// A new game launch truncates the file in place.
		Files.writeString(file, "[main/INFO]: fresh\n");
		assertTrue(tail.poll());
		List<String> lines = texts(tail);
		assertEquals(4, lines.size());
		assertTrue(lines.get(2).startsWith("--- log file rotated"));
		assertEquals("[main/INFO]: fresh", lines.get(3));
	}

	@Test void detectsReplacementByALongerFile() throws Exception {
		Path file = temp.resolve("latest.log");
		Files.writeString(file, "[main/INFO]: old\n");
		LogTail tail = new LogTail(file, 20);
		assertTrue(tail.poll());
		long oldSize = Files.size(file);

		// Replace the running log with a longer one, the way a rotation does when
		// the new file is already past the position remembered from the old one:
		// only the file identity shows that this is a different log.
		Path replacement = temp.resolve("replacement.log");
		Files.writeString(replacement, "[main/INFO]: new one\n[main/INFO]: new two\n[main/INFO]: new three\n");
		assertTrue(Files.size(replacement) > oldSize);
		Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);

		assertTrue(tail.poll());
		List<String> lines = texts(tail);
		assertTrue(lines.stream().anyMatch(line -> line.startsWith("--- log file rotated")));
		assertTrue(lines.contains("[main/INFO]: new one"));
		assertTrue(lines.contains("[main/INFO]: new three"));
	}

	@Test void waitsForAMissingFileThenFollowsIt() throws Exception {
		Path file = temp.resolve("missing.log");
		LogTail tail = new LogTail(file, 10);
		assertFalse(tail.poll());
		assertFalse(tail.filePresent());
		assertEquals(0, tail.bufferedLines());

		Files.writeString(file, "[main/INFO]: appeared\n");
		assertTrue(tail.poll());
		assertTrue(tail.filePresent());
		assertEquals(List.of("[main/INFO]: appeared"), texts(tail));
	}

	@Test void skipsThePartialFirstLineWhenStartingMidFile() throws Exception {
		Path file = temp.resolve("latest.log");
		Files.writeString(file, "0123456789\nsecond\n");
		LogTail tail = new LogTail(file, 10, 10);
		assertTrue(tail.poll());
		assertEquals(List.of("second"), texts(tail));
	}

	@Test void clearKeepsReadingFromTheCurrentPosition() throws Exception {
		Path file = temp.resolve("latest.log");
		Files.writeString(file, "[main/INFO]: before\n");
		LogTail tail = new LogTail(file, 10);
		assertTrue(tail.poll());
		tail.clear();
		assertEquals(0, tail.bufferedLines());
		assertEquals(0, tail.linesRead());

		append(file, "[main/INFO]: after\n");
		assertTrue(tail.poll());
		assertEquals(List.of("[main/INFO]: after"), texts(tail));
	}
}
