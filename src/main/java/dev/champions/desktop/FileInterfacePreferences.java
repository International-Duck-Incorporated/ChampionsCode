package dev.champions.desktop;

import dev.champions.core.console.InterfacePreferences;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The standalone app's side of the {@code interfaces} command: it edits the
 * same {@code desktop.properties} the game reads, so a choice made in the
 * terminal here still applies the next time the game starts.
 */
public final class FileInterfacePreferences implements InterfacePreferences {
	private static final List<Mode> MODES = List.of(
		new Mode("both", "in-game windows and the external app (default)"),
		new Mode("external", "external app only, in-game windows stay closed"),
		new Mode("in-game", "in-game windows only, no external process"));

	private final Path file;
	private final Runnable afterChange;

	public FileInterfacePreferences(Path configDirectory, Runnable afterChange) {
		this.file = configDirectory.resolve("desktop.properties");
		this.afterChange = afterChange == null ? () -> { } : afterChange;
	}

	@Override public List<Mode> modes() { return MODES; }

	@Override public String currentMode() {
		String value = read().getProperty("ui", "both").strip().toLowerCase(Locale.ROOT);
		return MODES.stream().anyMatch(mode -> mode.id().equals(value)) ? value : "both";
	}

	@Override public boolean inGameWindows() {
		return Boolean.parseBoolean(read().getProperty("ingameWindows", "true"));
	}

	@Override public boolean spawnOnOpen() {
		return Boolean.parseBoolean(read().getProperty("spawnOnOpen", "true"));
	}

	@Override public boolean desktopAttached() {
		return true; // the command is running inside the app itself
	}

	@Override public String setMode(String id) {
		String value = InterfacePreferences.normalise(id);
		if (MODES.stream().noneMatch(mode -> mode.id().equals(value))) return "Unknown interface '" + id + "'.";
		write("ui", value);
		afterChange.run();
		return "Interfaces set to " + value + " in " + file.getFileName() + ". Applies in the game on next start.";
	}

	@Override public void setInGameWindows(boolean enabled) {
		write("ingameWindows", Boolean.toString(enabled));
		afterChange.run();
	}

	@Override public void setSpawnOnOpen(boolean enabled) {
		write("spawnOnOpen", Boolean.toString(enabled));
		afterChange.run();
	}

	@Override public void reload() {
		afterChange.run();
	}

	private Properties read() {
		Properties properties = new Properties();
		if (!Files.exists(file)) return properties;
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException ignored) {
			// fall back to defaults
		}
		return properties;
	}

	private void write(String key, String value) {
		List<String> lines = new ArrayList<>();
		try {
			if (Files.exists(file)) lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
		} catch (IOException ignored) {
			// start from an empty list
		}
		boolean replaced = false;
		for (int index = 0; index < lines.size(); index++) {
			if (lines.get(index).strip().startsWith(key + "=")) {
				lines.set(index, key + "=" + value);
				replaced = true;
			}
		}
		if (!replaced) lines.add(key + "=" + value);
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// the command still reports success for the session value
		}
	}
}
