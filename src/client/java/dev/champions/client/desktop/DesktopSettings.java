package dev.champions.client.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * User preference for where the ChampionsCode interfaces live.
 *
 * <pre>
 * ui=both        in-game windows and the external app (default)
 * ui=external    only the external app
 * ui=in-game     only the in-game workspace windows (the 0.1.x behaviour)
 * spawnOnOpen=true   start the desktop app automatically the first time a UI is opened
 * </pre>
 */
public final class DesktopSettings {
	public enum Mode { BOTH, EXTERNAL, IN_GAME }

	private final Path file;
	private volatile Mode mode = Mode.BOTH;
	private volatile boolean spawnOnOpen = true;

	public DesktopSettings(Path configDirectory) {
		this.file = configDirectory.resolve("desktop.properties");
		initialize(configDirectory);
		reload();
	}

	public synchronized void reload() {
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException unreadable) {
			return;
		}
		String value = properties.getProperty("ui");
		if (value != null) {
			try {
				mode = Mode.valueOf(value.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
			} catch (IllegalArgumentException unknown) {
				mode = Mode.BOTH;
			}
		}
		String spawn = properties.getProperty("spawnOnOpen");
		if (spawn != null) spawnOnOpen = Boolean.parseBoolean(spawn.strip());
	}

	public Mode mode() {
		return mode;
	}

	public boolean spawnOnOpen() {
		return spawnOnOpen;
	}

	public boolean usesExternal() {
		return mode != Mode.IN_GAME;
	}

	public boolean usesInGame() {
		return mode != Mode.EXTERNAL;
	}

	public Path file() {
		return file;
	}

	private void initialize(Path configDirectory) {
		if (Files.exists(file)) return;
		try {
			Files.createDirectories(configDirectory);
			Files.writeString(file, """
				# ChampionsCode interface mode.
				#
				# ui=both      in-game windows AND the external desktop app (default)
				# ui=external  external desktop app only (in-game windows stay closed)
				# ui=in-game   in-game workspace windows only, no external process
				#
				# The external app is this same mod JAR started as a desktop program.
				# It talks to the running game over loopback only and can never act
				# without the console's authorization, CONFIRM phrase and audit log.
				ui=both
				# Start the desktop app automatically the first time a UI is opened.
				spawnOnOpen=true
				""", StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Defaults apply when the file cannot be written.
		}
	}
}
