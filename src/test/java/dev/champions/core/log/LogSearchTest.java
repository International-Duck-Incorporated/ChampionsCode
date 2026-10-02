package dev.champions.core.log;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogSearchTest {
	private static List<LogLine> lines(String... texts) {
		List<LogLine> result = new ArrayList<>();
		for (String text : texts) result.add(LogLine.of(text));
		return result;
	}

	@Test void inactiveWithoutAQuery() {
		LogSearch search = new LogSearch("   ", false);
		assertFalse(search.active());
		assertFalse(search.matches("anything"));
		assertTrue(search.occurrences("anything", 10).isEmpty());
		assertTrue(search.hits(lines("a", "b")).isEmpty());
	}

	@Test void matchesCaseInsensitivelyByDefault() {
		LogSearch search = new LogSearch("warn", false);
		assertTrue(search.active());
		assertTrue(search.matches("[12:00:00] [main/WARN]: watch out"));
		assertFalse(search.matches("nothing here"));
	}

	@Test void honoursCaseSensitivity() {
		LogSearch search = new LogSearch("WARN", true);
		assertTrue(search.matches("[main/WARN]: yes"));
		assertFalse(search.matches("[main/warn]: no"));
	}

	@Test void reportsEveryOccurrenceInALine() {
		LogSearch search = new LogSearch("ab", false);
		List<int[]> found = search.occurrences("abABab", 10);
		assertEquals(3, found.size());
		assertArrayEquals(new int[] {0, 2}, found.get(0));
		assertArrayEquals(new int[] {2, 4}, found.get(1));
		assertArrayEquals(new int[] {4, 6}, found.get(2));
		// The limit caps the per-line work on a line that matches constantly.
		assertEquals(2, search.occurrences("abababab", 2).size());
	}

	@Test void collectsHitsInReadingOrderAndFlagsTruncation() {
		LogSearch search = new LogSearch("x", false);
		LogSearch.Hits hits = search.hits(lines("xax", "none", "xx"));
		assertFalse(hits.truncated());
		assertEquals(4, hits.count());
		assertEquals(new LogSearch.Hit(0, 0, 1), hits.hits().get(0));
		assertEquals(new LogSearch.Hit(0, 2, 3), hits.hits().get(1));
		assertEquals(new LogSearch.Hit(2, 0, 1), hits.hits().get(2));
		assertEquals(new LogSearch.Hit(2, 1, 2), hits.hits().get(3));

		StringBuilder spam = new StringBuilder();
		spam.append("x".repeat(100));
		List<LogLine> many = new ArrayList<>();
		for (int i = 0; i < 40; i++) many.add(LogLine.of(spam.toString()));
		LogSearch.Hits capped = new LogSearch("x", false).hits(many);
		assertTrue(capped.truncated());
		assertEquals(LogSearch.MAX_HITS, capped.count());
	}

	@Test void nextAndPreviousWalkAndWrapAround() {
		LogSearch search = new LogSearch("e", false);
		// Hits for "e": "one" at 2, "three" at 3 and at 4 (the second "e").
		LogSearch.Hits hits = search.hits(lines("one", "two", "three"));
		assertEquals(3, hits.count());
		assertEquals(new LogSearch.Hit(0, 2, 3), hits.hits().get(0));
		assertEquals(new LogSearch.Hit(2, 3, 4), hits.hits().get(1));
		assertEquals(new LogSearch.Hit(2, 4, 5), hits.hits().get(2));

		// Both directions are strict, so walking from the current match never
		// returns it again.
		assertEquals(hits.hits().get(0), search.next(hits.hits(), 0, 0));
		assertEquals(hits.hits().get(1), search.next(hits.hits(), 0, 2));
		assertEquals(hits.hits().get(1), search.next(hits.hits(), 0, 3));
		assertEquals(hits.hits().get(0), search.next(hits.hits(), 2, 4), "wraps to the top");

		assertEquals(hits.hits().get(2), search.previous(hits.hits(), 2, 5));
		assertEquals(hits.hits().get(1), search.previous(hits.hits(), 2, 4));
		assertEquals(hits.hits().get(0), search.previous(hits.hits(), 0, 3));
		assertEquals(hits.hits().get(2), search.previous(hits.hits(), 0, 2), "wraps to the bottom");
		assertNull(search.next(List.of(), 0, 0));
		assertNull(search.previous(List.of(), 0, 0));
	}
}
