package dev.champions.client.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Explicit opt-in and source-hash confirmation for unrestricted runtime Java. */
final class JavaRuntimeSettings {
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
	private final Path file;
	private boolean developerMode;
	private String confirmedSourceHash = "";

	JavaRuntimeSettings(Path configDirectory) {
		file = configDirectory.resolve("java-runtime.properties");
		initialize();
		reload();
	}

	synchronized void reload() {
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException e) {
			throw new IllegalStateException("Could not read " + file, e);
		}
		developerMode = Boolean.parseBoolean(properties.getProperty("developerMode", "false"));
		String hash = properties.getProperty("confirmedSourceHash", "").strip().toLowerCase(Locale.ROOT);
		confirmedSourceHash = HASH.matcher(hash).matches() ? hash : "";
	}

	synchronized void confirm(String hash) throws IOException {
		String normalized = hash == null ? "" : hash.strip().toLowerCase(Locale.ROOT);
		if (!HASH.matcher(normalized).matches()) throw new IOException("Expected a 64-character SHA-256 value");
		Properties properties = new Properties();
		properties.setProperty("developerMode", Boolean.toString(developerMode));
		properties.setProperty("confirmedSourceHash", normalized);
		try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			properties.store(writer, "Champions unrestricted runtime Java settings");
		}
		confirmedSourceHash = normalized;
	}

	synchronized boolean developerMode() { return developerMode; }
	synchronized String confirmedSourceHash() { return confirmedSourceHash; }
	Path file() { return file; }

	private void initialize() {
		if (Files.exists(file)) return;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, """
				# WARNING: runtime Java is unrestricted code execution inside Minecraft.
				# Set developerMode=true only if you understand and accept that risk.
				developerMode=false
				confirmedSourceHash=
				""", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Could not create " + file, e);
		}
	}
}
