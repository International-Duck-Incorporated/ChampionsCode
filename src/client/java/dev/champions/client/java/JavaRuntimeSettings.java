package dev.champions.client.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Runtime Java options: plug-and-play drop folder, explicit opt-in, and hash confirmations. */
final class JavaRuntimeSettings {
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
	private final Path file;
	private boolean developerMode;
	private boolean plugAndPlay = true;
	private boolean autoReload = true;
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
		plugAndPlay = Boolean.parseBoolean(properties.getProperty("plugAndPlay", "true"));
		autoReload = Boolean.parseBoolean(properties.getProperty("autoReload", "true"));
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

	synchronized void setPlugAndPlay(boolean enabled) throws IOException {
		Properties properties = readProperties();
		properties.setProperty("plugAndPlay", Boolean.toString(enabled));
		writeProperties(properties);
		plugAndPlay = enabled;
	}

	synchronized void setAutoReload(boolean enabled) throws IOException {
		Properties properties = readProperties();
		properties.setProperty("autoReload", Boolean.toString(enabled));
		writeProperties(properties);
		autoReload = enabled;
	}

	synchronized boolean developerMode() { return developerMode; }

	/**
	 * Plug-and-play: every `.java` file below the module drop folder is compiled
	 * and registered automatically, at startup and (with {@link #autoReload()})
	 * whenever the folder changes. No hash confirmation and no rebuild required.
	 */
	synchronized boolean plugAndPlay() { return plugAndPlay; }

	/** Whether the drop folder is watched while the game runs. Requires plug-and-play. */
	synchronized boolean autoReload() { return autoReload; }

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
			writer.write(PROPERTY_HEADER);
			properties.store(writer, null);
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

	/** Shown at the top of the properties file; kept identical on every rewrite. */
	private static final String PROPERTY_HEADER = """
		# ChampionsCode runtime Java settings.
		#
		# WARNING: a .java file below the module drop folder is compiled and executed
		# inside Minecraft with the permissions of the game process. It is not a sandbox.
		# Only put files there that you wrote or have fully reviewed.
		#
		# plugAndPlay=true  Drop-in modules work out of the box: every .java file below
		#                   modules/ (searched recursively, at any depth) is compiled and
		#                   registered automatically. No hash confirmation, no rebuild.
		# autoReload=true   Also reload them while the game runs when the folder changes.
		#                   Set false to load once at startup only.
		# developerMode     Gates the single-entrypoint java/ addon and the manual
		#                   hash-confirmation workflow used when plugAndPlay=false.
		#
		""";

	private void initialize() {
		if (Files.exists(file)) return;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, PROPERTY_HEADER + """
				developerMode=false
				plugAndPlay=true
				autoReload=true
				confirmedSourceHash=
				confirmedModuleHash=
				""", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Could not create " + file, e);
		}
	}
}
