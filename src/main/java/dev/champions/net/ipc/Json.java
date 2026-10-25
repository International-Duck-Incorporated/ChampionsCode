package dev.champions.net.ipc;

import java.util.Collection;
import java.util.Map;

/**
 * Minimal, dependency-free JSON writer used by the desktop bridge. The reader
 * side already exists as {@link dev.champions.core.update.MiniJson}; this is
 * its write counterpart and follows the same deliberately small philosophy.
 *
 * <p>Objects are built as {@code obj("type", "hello", "id", 3L)}; keys must be
 * strings, values may be strings, numbers, booleans, null, maps, collections,
 * or arrays of those.</p>
 */
public final class Json {
	private Json() {}

	public static String obj(Object... keyValuePairs) {
		if (keyValuePairs.length % 2 != 0) throw new IllegalArgumentException("obj() needs key/value pairs");
		StringBuilder out = new StringBuilder(64).append('{');
		for (int i = 0; i < keyValuePairs.length; i += 2) {
			if (!(keyValuePairs[i] instanceof String key)) throw new IllegalArgumentException("object keys must be strings");
			if (i > 0) out.append(',');
			quoteInto(out, key);
			out.append(':');
			value(out, keyValuePairs[i + 1]);
		}
		return out.append('}').toString();
	}

	/** Builds a nested object; use this instead of {@link #obj} inside lists. */
	public static Map<String, Object> map(Object... keyValuePairs) {
		if (keyValuePairs.length % 2 != 0) throw new IllegalArgumentException("map() needs key/value pairs");
		Map<String, Object> map = new java.util.LinkedHashMap<>();
		for (int i = 0; i < keyValuePairs.length; i += 2) {
			map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
		}
		return map;
	}

	/** Serializes an existing map, preserving iteration order. */
	public static String of(Map<String, ?> values) {
		StringBuilder out = new StringBuilder(64).append('{');
		boolean first = true;
		for (Map.Entry<String, ?> entry : values.entrySet()) {
			if (!first) out.append(',');
			first = false;
			quoteInto(out, entry.getKey());
			out.append(':');
			value(out, entry.getValue());
		}
		return out.append('}').toString();
	}

	public static String arr(Collection<?> values) {
		StringBuilder out = new StringBuilder(32).append('[');
		boolean first = true;
		for (Object value : values) {
			if (!first) out.append(',');
			first = false;
			value(out, value);
		}
		return out.append(']').toString();
	}

	public static String quote(String value) {
		StringBuilder out = new StringBuilder(value == null ? 4 : value.length() + 2);
		quoteInto(out, value);
		return out.toString();
	}

	private static void value(StringBuilder out, Object value) {
		switch (value) {
			case null -> out.append("null");
			case String text -> quoteInto(out, text);
			case Boolean flag -> out.append(flag);
			case Number number -> {
				if (number instanceof Double d && (d.isNaN() || d.isInfinite())) out.append("null");
				else out.append(number);
			}
			case Map<?, ?> map -> {
				out.append('{');
				boolean first = true;
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (!first) out.append(',');
					first = false;
					quoteInto(out, String.valueOf(entry.getKey()));
					out.append(':');
					value(out, entry.getValue());
				}
				out.append('}');
			}
			case Collection<?> collection -> out.append(arr(collection));
			case Object[] array -> out.append(arr(java.util.Arrays.asList(array)));
			default -> quoteInto(out, String.valueOf(value));
		}
	}

	private static void quoteInto(StringBuilder out, String value) {
		if (value == null) {
			out.append("null");
			return;
		}
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				default -> {
					if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
					else out.append(c);
				}
			}
		}
		out.append('"');
	}
}
