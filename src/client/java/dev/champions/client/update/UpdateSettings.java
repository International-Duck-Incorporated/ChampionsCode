package dev.champions.client.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Opt-out toggle for the startup update check. The check only reads this
 * project's public GitLab releases feed and never touches the connected
 * server session.
 */
public final class UpdateSettings {
	private final Path file;
	private volatile boolean checkOnStart = true;

	public UpdateSettings(Path configDirectory) {
		file = configDirectory.resolve("update.properties");
		initialize();
		reload();
	}

	public synchronized void reload() {
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException unreadable) {
			return; // keep defaults when the file is missing or unreadable
		}
		String value = properties.getProperty("checkOnStart");
		if (value != null) checkOnStart = Boolean.parseBoolean(value.strip().toLowerCase(Locale.ROOT));
	}

	public boolean checkOnStart() {
		return checkOnStart;
	}

	public Path file() {
		return file;
	}

	private void initialize() {
		if (Files.exists(file)) return;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, """
				# ChampionsCode update checks.
				# The startup check only reads this project's public GitLab releases
				# feed and never touches the connected server session. Set to false
				# to disable the in-game update notice.
				checkOnStart=true
				""", StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// A missing settings file simply means defaults apply.
		}
	}
}
