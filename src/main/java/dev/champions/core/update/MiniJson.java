package dev.champions.core.update;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal read-only JSON parser. Produces {@code Map<String, Object>},
 * {@code List<Object>}, {@link String}, {@link Long}, {@link Double},
 * {@link Boolean}, and {@code null}; object order is preserved. The parser is
 * deliberately small and dependency-free, like the rest of the console core,
 * and rejects malformed documents with an {@link IllegalArgumentException}.
 */
public final class MiniJson {
	private final String text;
	private int position;

	private MiniJson(String text) {
		this.text = text;
	}

	public static Object parse(String json) {
		if (json == null) throw new IllegalArgumentException("JSON document is null");
		MiniJson parser = new MiniJson(json);
		parser.skipWhitespace();
		Object value = parser.parseValue();
		parser.skipWhitespace();
		if (parser.position != parser.text.length()) throw parser.fail("unexpected trailing content");
		return value;
	}

	private Object parseValue() {
		if (position >= text.length()) throw fail("unexpected end of document");
		char current = text.charAt(position);
		return switch (current) {
			case '{' -> parseObject();
			case '[' -> parseArray();
			case '"' -> parseString();
			case 't' -> parseLiteral("true", Boolean.TRUE);
			case 'f' -> parseLiteral("false", Boolean.FALSE);
			case 'n' -> parseLiteral("null", null);
			default -> parseNumber();
		};
	}

	private Map<String, Object> parseObject() {
		expect('{');
		Map<String, Object> result = new LinkedHashMap<>();
		skipWhitespace();
		if (consume('}')) return result;
		while (true) {
			skipWhitespace();
			if (peek() != '"') throw fail("expected an object key string");
			String key = parseString();
			skipWhitespace();
			expect(':');
			skipWhitespace();
			result.put(key, parseValue());
			skipWhitespace();
			if (consume('}')) return result;
			expect(',');
		}
	}

	private List<Object> parseArray() {
		expect('[');
		List<Object> result = new ArrayList<>();
		skipWhitespace();
		if (consume(']')) return result;
		while (true) {
			skipWhitespace();
			result.add(parseValue());
			skipWhitespace();
			if (consume(']')) return result;
			expect(',');
		}
	}

	private String parseString() {
		expect('"');
		StringBuilder result = new StringBuilder();
		while (position < text.length()) {
			char current = text.charAt(position++);
			if (current == '"') return result.toString();
			if (current == '\n') throw fail("unterminated string");
			if (current != '\\') {
				result.append(current);
				continue;
			}
			if (position >= text.length()) throw fail("unterminated escape sequence");
			char escaped = text.charAt(position++);
			switch (escaped) {
				case '"' -> result.append('"');
				case '\\' -> result.append('\\');
				case '/' -> result.append('/');
				case 'b' -> result.append('\b');
				case 'f' -> result.append('\f');
				case 'n' -> result.append('\n');
				case 'r' -> result.append('\r');
				case 't' -> result.append('\t');
				case 'u' -> result.append(parseUnicodeEscape());
				default -> throw fail("unsupported escape \\" + escaped);
			}
		}
		throw fail("unterminated string");
	}

	private char parseUnicodeEscape() {
		if (position + 4 > text.length()) throw fail("truncated unicode escape");
		String digits = text.substring(position, position + 4);
		position += 4;
		try {
			return (char) Integer.parseInt(digits, 16);
		} catch (NumberFormatException invalid) {
			throw fail("invalid unicode escape \\u" + digits);
		}
	}

	private Object parseNumber() {
		int start = position;
		while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
		String raw = text.substring(start, position);
		if (raw.isEmpty()) throw fail("expected a value at position " + start);
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException notIntegral) {
			try {
				return Double.parseDouble(raw);
			} catch (NumberFormatException invalid) {
				throw fail("invalid number '" + raw + "'");
			}
		}
	}

	private Object parseLiteral(String literal, Object value) {
		if (!text.startsWith(literal, position)) throw fail("invalid literal");
		position += literal.length();
		return value;
	}

	private char peek() {
		if (position >= text.length()) throw fail("unexpected end of document");
		return text.charAt(position);
	}

	private void expect(char expected) {
		if (position >= text.length() || text.charAt(position) != expected) {
			throw fail("expected '" + expected + "'");
		}
		position++;
	}

	private boolean consume(char expected) {
		if (position < text.length() && text.charAt(position) == expected) {
			position++;
			return true;
		}
		return false;
	}

	private void skipWhitespace() {
		while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
	}

	private IllegalArgumentException fail(String message) {
		return new IllegalArgumentException("JSON parse error at position " + position + ": " + message);
	}
}
