package dev.champions.core.update;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MiniJsonTest {
	@Test void parsesScalars() {
		assertEquals("hello", MiniJson.parse("\"hello\""));
		assertEquals(42L, MiniJson.parse("42"));
		assertEquals(-7L, MiniJson.parse("-7"));
		assertEquals(3.5, (Double) MiniJson.parse("3.5"), 1e-9);
		assertEquals(Boolean.TRUE, MiniJson.parse("true"));
		assertEquals(Boolean.FALSE, MiniJson.parse("false"));
		assertNull(MiniJson.parse("null"));
	}

	@Test void parsesNestedStructures() {
		Object parsed = MiniJson.parse("""
			{"name": "beta.1", "count": 3, "nested": {"on": true, "list": [1, "two", null]}}
			""");
		@SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) parsed;
		assertEquals("beta.1", root.get("name"));
		assertEquals(3L, root.get("count"));
		@SuppressWarnings("unchecked") Map<String, Object> nested = (Map<String, Object>) root.get("nested");
		assertEquals(Boolean.TRUE, nested.get("on"));
		@SuppressWarnings("unchecked") List<Object> list = (List<Object>) nested.get("list");
		assertEquals(java.util.Arrays.asList(1L, "two", null), list);
	}

	@Test void decodesStringEscapes() {
		Object parsed = MiniJson.parse("\"line\\n\\t\\\"quoted\\\" \\u0041\\/\\\\\"");
		assertEquals("line\n\t\"quoted\" A/\\", parsed);
	}

	@Test void toleratesEmptyContainersAndWhitespace() {
		assertEquals(Map.of(), MiniJson.parse("  { }  "));
		assertEquals(List.of(), MiniJson.parse("\t[\n]\r\n"));
	}

	@Test void rejectsMalformedDocuments() {
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"key\": }"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[1, 2,, 3]"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("\"unterminated"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("tru"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{} trailing"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(null));
	}

	@Test void preservesObjectKeyOrder() {
		@SuppressWarnings("unchecked") Map<String, Object> parsed = (Map<String, Object>) MiniJson.parse("{\"z\": 1, \"a\": 2}");
		assertEquals(List.of("z", "a"), List.copyOf(parsed.keySet()));
	}
}
