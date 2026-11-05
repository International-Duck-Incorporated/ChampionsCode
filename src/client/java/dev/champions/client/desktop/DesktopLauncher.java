package dev.champions.client.desktop;

import dev.champions.desktop.DesktopMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Starts the external ChampionsCode app.
 *
 * <p>The app is this same mod JAR executed as a program, so no second download,
 * install or dependency exists. It is launched with the JVM that is already
 * running Minecraft (guaranteed to be the required Java version). The bridge
 * port is passed as an argument and the token through the child's environment,
 * not in the process arguments or logs.</p>
 *
 * <p>Every failure is non-fatal and is logged so the caller can keep using
 * the in-game windows.</p>
 */
public final class DesktopLauncher {
	private static final Logger LOG = LoggerFactory.getLogger("champions-desktop");

	private final Path logFile;
	private volatile Process process;

	public DesktopLauncher(Path configDirectory) {
		this.logFile = configDirectory.resolve("desktop.log");
	}

	public boolean isRunning() {
		Process current = process;
		return current != null && current.isAlive();
	}

	/** A command for manual launch, with a placeholder for the token. */
	public List<String> commandPreview(int port) {
		List<String> command = new ArrayList<>();
		command.add(javaBinary());
		command.addAll(renderingFlags());
		command.addAll(launchArguments(port));
		// A manual command can still use --token; the automatic process never does.
		command.add("--token");
		command.add("<token>");
		return List.copyOf(command);
	}

	/**
	 * Starts the desktop app attached to the given bridge.
	 *
	 * @return true when a process was started (it may still fail to connect)
	 */
	public synchronized boolean spawn(int port, String token, String configDirectory) {
		if (isRunning()) return true;
		Optional<Path> jar = ownArchive();
		if (jar.isEmpty()) {
			LOG.warn("desktop app not started: the mod is not running from a JAR, so there is no "
				+ "application to launch. Run the installed mod JAR to use the external interface.");
			return false;
		}
		List<String> command = new ArrayList<>();
		command.add(javaBinary());
		command.addAll(renderingFlags());
		command.addAll(launchArguments(port));
		if (configDirectory != null && !configDirectory.isBlank()) {
			command.add("--config");
			command.add(configDirectory);
		}
		try {
			Path parent = logFile.getParent();
			if (parent != null) Files.createDirectories(parent);
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true);
			builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
			builder.environment().put("CHAMPIONS_DESKTOP_TOKEN", token);
			builder.environment().put("CHAMPIONS_DESKTOP_PORT", Integer.toString(port));
			process = builder.start();
			LOG.info("ChampionsCode desktop app started (pid {}), output: {}", process.pid(), logFile);
			return true;
		} catch (IOException | RuntimeException error) {
			LOG.warn("could not start the desktop app: {}. In-game windows remain available.", error.getMessage());
			return false;
		}
	}

	/**
	 * Crisp text on scaled displays. A process launched without these can be
	 * rendered at 1x and bitmap-stretched by Windows at 150 %, which looks
	 * blurry; the app also sets them itself, this makes sure they are in place
	 * before the JVM initialises the graphics environment.
	 */
	private static List<String> renderingFlags() {
		return List.of(
			"-Dawt.useSystemAAFontSettings=lcd",
			"-Dswing.aatext=true",
			"-Dsun.java2d.dpiaware=true",
			"-Dsun.java2d.uiScale.enabled=true"
		);
	}

	/** {@code -jar <this mod jar> --connect <port>} (or the dev classes dir). */
	private List<String> launchArguments(int port) {
		List<String> arguments = new ArrayList<>();
		Path archive = ownArchive().orElse(null);
		if (archive != null && Files.isDirectory(archive)) {
			// Loom keeps the client and common source sets in different output
			// directories; the launcher directory alone does not contain the app.
			Path mainArchive = codeSource(DesktopMain.class).orElse(archive);
			String classpath = mainArchive.toAbsolutePath() + File.pathSeparator + archive.toAbsolutePath();
			String inherited = System.getProperty("java.class.path", "");
			if (!inherited.isBlank()) classpath += File.pathSeparator + inherited;
			arguments.add("-cp");
			arguments.add(classpath);
			arguments.add(DesktopMain.class.getName());
		} else if (archive != null) {
			arguments.add("-jar");
			arguments.add(archive.toAbsolutePath().toString());
		} else {
			arguments.add("-cp");
			arguments.add(System.getProperty("java.class.path", "."));
			arguments.add("dev.champions.desktop.DesktopMain");
		}
		arguments.add("--connect");
		arguments.add(Integer.toString(port));
		return arguments;
	}

	/** The JVM running Minecraft: always new enough, since the mod requires Java 25. */
	private static String javaBinary() {
		Optional<String> running = ProcessHandle.current().info().command();
		if (running.isPresent() && !running.get().isBlank()) return running.get();
		String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
			? "java.exe" : "java";
		return Path.of(System.getProperty("java.home", "."), "bin", executable).toString();
	}

	/** The file or directory this class was loaded from. */
	private static Optional<Path> ownArchive() {
		return codeSource(DesktopLauncher.class);
	}

	private static Optional<Path> codeSource(Class<?> type) {
		try {
			var source = type.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) return Optional.empty();
			return Optional.of(Path.of(source.getLocation().toURI()));
		} catch (Exception unavailable) {
			return Optional.empty();
		}
	}

	public void close() {
		Process current = process;
		if (current == null) return;
		current.destroy();
		process = null;
	}
}
