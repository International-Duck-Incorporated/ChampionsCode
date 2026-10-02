package dev.champions.core.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogLineTest {
	@Test void readsLevelFromTheThreadPrefix() {
		assertEquals(LogLine.Level.INFO, LogLine.parseLevel("[12:34:56] [Render thread/INFO]: Loading Minecraft 26.3"));
		assertEquals(LogLine.Level.WARN, LogLine.parseLevel("[12:34:56] [main/WARN]: Option not found"));
		assertEquals(LogLine.Level.ERROR, LogLine.parseLevel("[12:34:56] [Render thread/ERROR]: Boom"));
		assertEquals(LogLine.Level.DEBUG, LogLine.parseLevel("[12:34:56] [Worker-Main-1/DEBUG]: details"));
		assertEquals(LogLine.Level.TRACE, LogLine.parseLevel("[12:34:56] [main/TRACE]: very chatty"));
		assertEquals(LogLine.Level.FATAL, LogLine.parseLevel("[12:34:56] [Server thread/FATAL]: out of memory"));
	}

	@Test void readsLevelFromOlderBracketFormat() {
		assertEquals(LogLine.Level.INFO, LogLine.parseLevel("[12:34:56] [Client thread/INFO] [minecraft/Minecraft]: Setting user"));
		assertEquals(LogLine.Level.WARN, LogLine.parseLevel("[12:34:56] [Server thread/WARN]: Can't keep up"));
	}

	@Test void keepsStackTracesAndBannersWithoutALevel() {
		assertEquals(LogLine.Level.UNKNOWN, LogLine.of("	at net.minecraft.client.Main.main(Main.java:1)").level());
		assertEquals(LogLine.Level.UNKNOWN, LogLine.of("Caused by: java.lang.NullPointerException").level());
		assertEquals(LogLine.Level.UNKNOWN, LogLine.of("").level());
		// A word that merely starts with a level name is not a level marker.
		assertEquals(LogLine.Level.UNKNOWN, LogLine.parseLevel("[12:34:56] [main/INFOX]: nope"));
		assertEquals(LogLine.Level.UNKNOWN, LogLine.parseLevel("no slash anywhere INFO"));
		// Levels past the prefix window are ignored: this is message text.
		assertEquals(LogLine.Level.UNKNOWN, LogLine.parseLevel("message ".repeat(30) + "[x/ERROR]: late"));
	}

	@Test void levelFiltersKeepContinuationLinesVisible() {
		assertTrue(LogLine.Level.UNKNOWN.atLeast(LogLine.Level.ERROR));
		assertTrue(LogLine.Level.ERROR.atLeast(LogLine.Level.WARN));
		assertTrue(LogLine.Level.TRACE.atLeast(LogLine.Level.TRACE));
		assertFalse(LogLine.Level.INFO.atLeast(LogLine.Level.WARN));
		assertFalse(LogLine.Level.DEBUG.atLeast(LogLine.Level.INFO));
	}

	@Test void normalizesControlCharactersWithoutLosingIndentation() {
		LogLine line = LogLine.of("[12:34:56] [main/INFO]:\ttabbed\r\n");
		assertFalse(line.text().contains("\t"));
		assertFalse(line.text().endsWith("\r"));
		// A stack-trace indent survives the tab-to-space normalization.
		assertEquals(" at foo", LogLine.of("\tat foo").text());
		assertEquals("[main/INFO]: spaced", LogLine.of("[main/INFO]: spaced   ").text());
	}

	@Test void ofIsNotNullSafe() {
		assertEquals("", LogLine.of(null).text());
		assertEquals(LogLine.Level.UNKNOWN, LogLine.of(null).level());
	}
}
