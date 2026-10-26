package dev.champions.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * User preference for where the ChampionsCode interfaces live.
 *
 * <pre>
 * ui=both        in-game windows and the external app (default)
 * ui=external    only the external app
 * ui=in-game     only the in-game workspace windows (the 0.1.x behaviour)
 * ingameWindows=true keep the in-game windows working alongside the external app
 * spawnOnOpen=true   start the desktop app automatically the first time a UI is opened
 * </pre>
 */
public final class DesktopSettings {
	public enum Mode { BOTH, EXTERNAL, IN_GAME }

	private final Path file;
	private volatile Mode mode = Mode.BOTH;
	private volatile boolean spawnOnOpen = true;
	private volatile boolean inGameWindows = true;
	/** Stamp of the file the values above were read from, for {@link #refreshIfChanged()}. */
	private volatile FileTime stamp;
	private volatile long stampSize = -1L;

	public DesktopSettings(Path configDirectory) {
		this.file = configDirectory.resolve("desktop.properties");
		initialize(configDirectory);
		reload();
	}

	/**
	 * Re-reads the file when something other than this object changed it.
	 *
	 * <p>The desktop app writes {@code desktop.properties} directly, and a user
	 * may edit it in a text editor. Without this, those changes only took effect
	 * after a restart, so a mode chosen in the external app's Settings tab did
	 * nothing until the game was relaunched -- which is exactly what "F8 opens
	 * both windows no matter what" looked like.</p>
	 *
	 * @return true when the values were reloaded
	 */
	public synchronized boolean refreshIfChanged() {
		FileTime current;
		long size;
		try {
			current = Files.getLastModifiedTime(file);
			size = Files.size(file);
		} catch (IOException missing) {
			return false;
		}
		if (current.equals(stamp) && size == stampSize) return false;
		reload();
		return true;
	}

	public synchronized void reload() {
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException unreadable) {
			return;
		}
		stamp();
		if (properties.isEmpty()) return;
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
		String inGame = properties.getProperty("ingameWindows");
		if (inGame != null) inGameWindows = Boolean.parseBoolean(inGame.strip());
	}

	/** Rewrites {@code ui=} while keeping the explanatory comments in the file. */
	public synchronized Mode setMode(Mode value) {
		mode = value == null ? Mode.BOTH : value;
		write("ui", mode.name().toLowerCase(Locale.ROOT).replace('_', '-'));
		return mode;
	}

	public synchronized void setSpawnOnOpen(boolean value) {
		spawnOnOpen = value;
		write("spawnOnOpen", Boolean.toString(value));
	}

	public synchronized void setInGameWindows(boolean value) {
		inGameWindows = value;
		write("ingameWindows", Boolean.toString(value));
	}

	public boolean inGameWindows() {
		return inGameWindows;
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

	/** In-game windows stay available unless the user asked for the app only. */
	public boolean usesInGame() {
		return inGameWindows && mode != Mode.EXTERNAL;
	}

	public Path file() {
		return file;
	}

	/** Sets {@code key=value}, creating the file with defaults if needed. */
	private void write(String key, String value) {
		initialize(file.getParent());
		List<String> lines = new ArrayList<>();
		try {
			lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
		} catch (IOException unreadable) {
			// start from an empty file
		}
		boolean replaced = false;
		for (int index = 0; index < lines.size(); index++) {
			String trimmed = lines.get(index).strip();
			if (trimmed.startsWith(key + "=") || trimmed.startsWith(key + " =")) {
				lines.set(index, key + "=" + value);
				replaced = true;
			}
		}
		if (!replaced) lines.add(key + "=" + value);
		try {
			Files.write(file, lines, StandardCharsets.UTF_8);
			stamp();
		} catch (IOException unwritable) {
			// the in-memory value still applies for this session
		}
	}

	/** Records the current file stamp so a later write by us is not seen as foreign. */
	private void stamp() {
		try {
			stamp = Files.getLastModifiedTime(file);
			stampSize = Files.size(file);
		} catch (IOException unreadable) {
			stamp = null;
			stampSize = -1L;
		}
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
