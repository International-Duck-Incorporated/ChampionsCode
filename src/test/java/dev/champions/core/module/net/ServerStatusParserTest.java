package dev.champions.core.module.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerStatusParserTest {
	@Test void parsesFullStatusDocument() {
		String json = """
			{
			  "version": {"name": "26.3", "protocol": 771},
			  "players": {"online": 12, "max": 100, "sample": [
			    {"name": "Alice", "id": "aaaa"}, {"name": "Bob", "id": "bbbb"}, {"name": "Carol", "id": "cccc"}
			  ]},
			  "description": {"text": "§aWelcome ", "extra": [{"text": "§ltraveler", "color": "gold"}]},
			  "favicon": "data:image/png;base64,AAAA",
			  "enforcesSecureChat": true,
			  "modinfo": {"type": "FML", "modList": [{"modid": "forge"}, {"modid": "jei"}]}
			}
			""";
		ServerStatusParser.ParsedStatus status = ServerStatusParser.parse(json);
		assertEquals("26.3", status.versionName());
		assertEquals(771, status.protocol());
		assertEquals("Welcome traveler", status.motd());
		assertEquals(12, status.playersOnline());
		assertEquals(100, status.playersMax());
		assertEquals(java.util.List.of("Alice", "Bob", "Carol"), status.sample());
		assertTrue(status.favicon());
		assertEquals(Boolean.TRUE, status.enforcesSecureChat());
		assertNull(status.preventsChatReports());
		assertEquals("FML", status.modPlatform());
		assertEquals(java.util.List.of("forge", "jei"), status.mods());
	}

	@Test void parsesPlainStringMotd() {
		String json = """
			{"version": {"name": "vanilla", "protocol": 47}, "description": "§7A plain §dservers"}
			""";
		ServerStatusParser.ParsedStatus status = ServerStatusParser.parse(json);
		assertEquals("vanilla", status.versionName());
		assertEquals(47, status.protocol());
		assertEquals("A plain servers", status.motd());
	}

	@Test void treatsAbsentFieldsAsNotAdvertised() {
		ServerStatusParser.ParsedStatus status = ServerStatusParser.parse("{}");
		assertEquals("unknown", status.versionName());
		assertEquals(-1, status.protocol());
		assertEquals("", status.motd());
		assertEquals(-1, status.playersOnline());
		assertEquals(-1, status.playersMax());
		assertTrue(status.sample().isEmpty());
		assertFalse(status.favicon());
		assertNull(status.enforcesSecureChat());
		assertNull(status.preventsChatReports());
		assertNull(status.modPlatform());
		assertTrue(status.mods().isEmpty());
	}

	@Test void readsChatReportFlag() {
		ServerStatusParser.ParsedStatus status = ServerStatusParser.parse("{\"preventsChatReports\": true}");
		assertEquals(Boolean.TRUE, status.preventsChatReports());
		assertNull(status.enforcesSecureChat());
	}

	@Test void rejectsNonObjectDocuments() {
		assertThrows(IllegalArgumentException.class, () -> ServerStatusParser.parse("[1, 2]"));
	}
}
