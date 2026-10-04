package dev.champions.client.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Explicit opt-in and source-hash confirmations for unrestricted runtime Java. */
final class JavaRuntimeSettings {
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
	private final Path file;
	private boolean developerMode;
	private String confirmedSourceHash = "";
	private String confirmedModuleHash = "";

	JavaRuntimeSettings(Path configDirectory) {
		file = configDirectory.resolve("java-runtime.properties");
		initialize();
		reload();
	}

	synchronized void reload() {
		Properties properties;
		try {
			properties = readProperties();
		} catch (IOException e) {
			throw new IllegalStateException("Could not read " + file, e);
		}
		developerMode = Boolean.parseBoolean(properties.getProperty("developerMode", "false"));
		confirmedSourceHash = validHash(properties.getProperty("confirmedSourceHash", ""));
		confirmedModuleHash = validHash(properties.getProperty("confirmedModuleHash", ""));
	}

	synchronized void confirm(String hash) throws IOException {
		String normalized = requireHash(hash);
		Properties properties = readProperties();
		properties.setProperty("confirmedSourceHash", normalized);
		writeProperties(properties);
		confirmedSourceHash = normalized;
	}

	synchronized void confirmModuleHash(String hash) throws IOException {
		String normalized = requireHash(hash);
		Properties properties = readProperties();
		properties.setProperty("confirmedModuleHash", normalized);
		writeProperties(properties);
		confirmedModuleHash = normalized;
	}

	synchronized boolean developerMode() { return developerMode; }
	synchronized String confirmedSourceHash() { return confirmedSourceHash; }
	synchronized String confirmedModuleHash() { return confirmedModuleHash; }
	Path file() { return file; }

	private Properties readProperties() throws IOException {
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		return properties;
	}

	private void writeProperties(Properties properties) throws IOException {
		try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			properties.store(writer, "Champions unrestricted runtime Java settings");
		}
	}

	private static String validHash(String value) {
		String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
		return HASH.matcher(normalized).matches() ? normalized : "";
	}

	private static String requireHash(String value) throws IOException {
		String normalized = validHash(value);
		if (normalized.isEmpty()) throw new IOException("Expected a 64-character SHA-256 value");
		return normalized;
	}

	private void initialize() {
		if (Files.exists(file)) return;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, """
				# WARNING: runtime Java and external Java modules execute unrestricted code inside Minecraft.
				# Set developerMode=true only if you understand and accept that risk.
				developerMode=false
				confirmedSourceHash=
				confirmedModuleHash=
				""", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Could not create " + file, e);
		}
	}
}
