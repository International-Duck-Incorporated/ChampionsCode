package dev.champions.core.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Append-only local JSONL audit trail. Sensitive option values are never recorded. */
public final class AuditLog {
	private final Path file;

	public AuditLog(Path configDirectory) {
		this.file = configDirectory.resolve("audit.jsonl");
		try { Files.createDirectories(file.getParent()); }
		catch (IOException e) { throw new IllegalStateException("Could not create audit directory", e); }
	}

	public synchronized void record(String event, String target, String module, String outcome, String detail) {
		String json = "{\"time\":\"" + escape(Instant.now().toString())
			+ "\",\"event\":\"" + escape(event)
			+ "\",\"target\":\"" + escape(target)
			+ "\",\"module\":\"" + escape(module)
			+ "\",\"outcome\":\"" + escape(outcome)
			+ "\",\"detail\":\"" + escape(limit(detail, 500)) + "\"}\n";
		try {
			Files.writeString(file, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// Console operation must not fail because local audit storage is unavailable.
		}
	}

	public Path file() { return file; }

	private static String escape(String value) {
		if (value == null) return "";
		StringBuilder out = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> { if (c >= 0x20) out.append(c); }
			}
		}
		return out.toString();
	}

	private static String limit(String value, int max) {
		if (value == null) return "";
		return value.length() <= max ? value : value.substring(0, max) + "…";
	}
}
