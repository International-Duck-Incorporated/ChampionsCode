package dev.champions.core.console;

import java.util.ArrayList;
import java.util.List;

/** Shell-like tokenizer supporting single/double quotes and backslash escaping. */
public final class CommandTokenizer {
	private CommandTokenizer() {}

	public static List<String> tokenize(String input) {
		List<String> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		char quote = 0;
		boolean escaping = false;
		boolean started = false;

		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (escaping) {
				current.append(c);
				escaping = false;
				started = true;
				continue;
			}
			if (c == '\\') {
				escaping = true;
				started = true;
				continue;
			}
			if (quote != 0) {
				if (c == quote) quote = 0;
				else current.append(c);
				started = true;
				continue;
			}
			if (c == '\'' || c == '"') {
				quote = c;
				started = true;
			} else if (Character.isWhitespace(c)) {
				if (started) {
					tokens.add(current.toString());
					current.setLength(0);
					started = false;
				}
			} else {
				current.append(c);
				started = true;
			}
		}
		if (escaping) current.append('\\');
		if (quote != 0) throw new IllegalArgumentException("Unclosed quote");
		if (started) tokens.add(current.toString());
		return List.copyOf(tokens);
	}
}
