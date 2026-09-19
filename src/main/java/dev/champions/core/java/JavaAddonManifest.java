package dev.champions.core.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict metadata for one runtime Java source set. */
public record JavaAddonManifest(String id, String name, String version, String entrypoint) {
	private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
	private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

	public JavaAddonManifest {
		if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid addon id");
		if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Invalid addon name");
		if (version == null || version.isBlank() || version.length() > 64) throw new IllegalArgumentException("Invalid addon version");
		if (entrypoint == null || !CLASS_NAME.matcher(entrypoint).matches()) throw new IllegalArgumentException("Invalid addon entrypoint");
	}

	public static JavaAddonManifest read(Path file) throws IOException {
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) throw new IOException("Missing regular manifest: " + file);
		String json = Files.readString(file, StandardCharsets.UTF_8);
		try {
			return new JavaAddonManifest(
				readString(json, "id"),
				readString(json, "name"),
				readString(json, "version"),
				readString(json, "entrypoint")
			);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid champion-java.json: " + e.getMessage(), e);
		}
	}

	private static String readString(String json, String key) {
		Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
		Matcher matcher = pattern.matcher(json);
		if (!matcher.find()) throw new IllegalArgumentException("missing string field " + key);
		return unescape(matcher.group(1));
	}

	private static String unescape(String value) {
		StringBuilder out = new StringBuilder(value.length());
		boolean escaped = false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!escaped) {
				if (c == '\\') escaped = true;
				else out.append(c);
				continue;
			}
			escaped = false;
			switch (c) {
				case '"', '\\', '/' -> out.append(c);
				case 'b' -> out.append('\b');
				case 'f' -> out.append('\f');
				case 'n' -> out.append('\n');
				case 'r' -> out.append('\r');
				case 't' -> out.append('\t');
				default -> throw new IllegalArgumentException("unsupported escape in string field");
			}
		}
		if (escaped) throw new IllegalArgumentException("unterminated escape in string field");
		return out.toString();
	}
}
