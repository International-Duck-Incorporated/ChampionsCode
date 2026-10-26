package dev.champions.desktop;

import dev.champions.api.java.RuntimeJavaCommands;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.repo.ModuleSourceInstaller;
import dev.champions.core.repo.RepoConfig;
import dev.champions.core.console.ExploitWarningPrompt;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.update.UpdateService;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Entry point of the external ChampionsCode desktop app.
 *
 * <p>Launched by the mod ({@code --connect <port> --token <token>}) it attaches
 * to the running game over loopback; launched on its own it runs the same
 * console against the local configuration, where every passive module still
 * works and nothing can be authorized because there is no connection.</p>
 *
 * <p>It never loads a Minecraft or Fabric class: the mod JAR doubles as the
 * application, and {@code --self-test} proves the isolation in CI.</p>
 */
public final class DesktopMain {
	public static final String APP_VERSION = resolveVersion();

	/** JAR manifest first (Implementation-Version), then the compiled-in default. */
	private static String resolveVersion() {
		try {
			String version = DesktopMain.class.getPackage().getImplementationVersion();
			if (version != null && !version.isBlank()) return version;
		} catch (RuntimeException ignored) {
			// fall through to the default
		}
		return "0.2.0-desktop";
	}

	private static volatile ConsolePort port;
	private static volatile Path configDirectory;
	private static volatile Shell shell;
	private static StandaloneRuntime RUNTIME;

	private DesktopMain() {}

	public static void main(String[] args) {
		Map<String, String> options = parse(args);
		if (options.containsKey("self-test")) {
			System.exit(SelfTest.run());
			return;
		}
		configureRendering(options.getOrDefault("ui-scale", "auto"));
		configDirectory = options.containsKey("config")
			? Path.of(options.get("config")).toAbsolutePath().normalize()
			: defaultConfigDirectory();
		boolean demo = options.containsKey("demo");
		try {
			if (options.containsKey("connect")) {
				int portNumber = Integer.parseInt(options.get("connect"));
				String token = options.getOrDefault("token", "");
				port = RemoteConsolePort.connect("127.0.0.1", portNumber, token, APP_VERSION);
				System.out.println("Attached to the game on 127.0.0.1:" + portNumber);
			} else {
				CONSOLE = buildConsole(configDirectory, demo);
				port = new LocalConsolePort(CONSOLE,
					demo ? DemoContext::capture : DemoContext::offline,
					demo ? "demo harness, no real target" : "no game attached");
			}
		} catch (IOException | RuntimeException error) {
			System.err.println("Could not start: " + error.getMessage());
			System.err.println("Falling back to standalone mode.");
			CONSOLE = buildConsole(configDirectory, false);
			port = new LocalConsolePort(CONSOLE, DemoContext::offline, "no game attached");
		}
		SwingUtilities.invokeLater(() -> buildUi(options));
	}

	// ---- window ----------------------------------------------------------

	private static void buildUi(Map<String, String> options) {
		Theme.install();
		boolean demo = options.containsKey("demo");

		if (demo) {
			port.buffer().append(dev.champions.core.console.ConsoleLine.warning(
				"DEMO HARNESS: the simulated target " + DemoContext.DEMO_TARGET
					+ " is not a real server and cannot resolve. Nothing leaves this machine."));
		}

		shell = new Shell("ChampionsCode " + APP_VERSION);
		shell.setBounds(shellBounds());

		shell.addTab("terminal", Icons.Glyph.TERMINAL, "Terminal", new TerminalPanel(port));
		shell.addTab("modules", Icons.Glyph.MODULES, "Modules", new ModulesPanel(port));
		shell.addTab("codespace", Icons.Glyph.CODE, "Code", new CodeSpacePanel(port,
			port instanceof LocalConsolePort ? runtime() : null, moduleFolder()));
		shell.addTab("settings", Icons.Glyph.SETTINGS, "Settings", new SettingsPanel(port, configDirectory));
		shell.railFooter("v" + APP_VERSION);

		String initial = options.containsKey("window") ? options.get("window")
			: options.containsKey("tab") ? options.get("tab")
			: loadWindowBounds().getProperty("tab", "terminal");
		shell.show(normaliseTab(initial));
		shell.setVisible(true);
		shell.addWindowListener(new WindowAdapter() {
			@Override public void windowClosing(WindowEvent event) {
				saveWindowBounds();
			}
		});

		// One status line for the whole app, fed by the console snapshot.
		new Timer(500, event -> {
			if (shell == null) return;
			ConsolePort.Status status = port.status();
			String capabilities = port.capabilities();
			shell.status(port.mode().toUpperCase(Locale.ROOT) + "   " + status.summary(),
				capabilities.isBlank() ? "" : capabilities);
			shell.setStatusColour(status.connected()
				? (status.authorized() ? Theme.GREEN : Theme.YELLOW) : Theme.DIM);
		}).start();

		if (port instanceof RemoteConsolePort remote) remote.onExploitPrompt(() -> answerPrompts(remote));
		if (demo) runDemoScript();
		System.out.println("ChampionsCode desktop " + APP_VERSION + " started in "
			+ (port instanceof RemoteConsolePort ? "attached" : "standalone") + " mode; config: " + configDirectory);
	}

	/** Accepts the ids the game uses (terminal, modules, codespace, log, settings). */
	private static String normaliseTab(String id) {
		return switch (id == null ? "" : id.toLowerCase(Locale.ROOT)) {
			case "modules", "clickgui", "categorygui" -> "modules";
			case "codespace", "java", "javastudio", "code" -> "codespace";
			case "settings", "config" -> "settings";
			default -> "terminal";
		};
	}

	/** Called from the game over the bridge, and from the app's own rail. */
	public static void raiseWindow(String windowId) {
		SwingUtilities.invokeLater(() -> {
			Shell current = shell;
			if (current == null) return;
			current.show(normaliseTab(windowId));
			if ((current.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) {
				current.setState(java.awt.Frame.NORMAL);
			}
			current.setVisible(true);
			current.toFront();
			current.requestFocus();
		});
	}

	private static java.awt.Rectangle shellBounds() {
		java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
			.getMaximumWindowBounds();
		// The floors match Shell's minimum size, so a first run never opens
		// below the size at which the Settings pane still fits.
		int width = Math.min(1460, Math.max(1040, Math.round(screen.width * 0.84f)));
		int height = Math.min(920, Math.max(720, Math.round(screen.height * 0.86f)));
		java.awt.Rectangle computed = new java.awt.Rectangle(
			screen.x + (screen.width - width) / 2, screen.y + (screen.height - height) / 2, width, height);
		String value = loadWindowBounds().getProperty("shell");
		if (value == null) return computed;
		String[] parts = value.split(",");
		if (parts.length != 4) return computed;
		try {
			java.awt.Rectangle saved = new java.awt.Rectangle(
				Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
				Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
			if (!screen.intersects(saved)) return computed;
			// A geometry saved under an older, smaller minimum would otherwise
			// restore a window the Settings pane does not fit in.
			saved.width = Math.max(saved.width, 1040);
			saved.height = Math.max(saved.height, 720);
			return saved;
		} catch (NumberFormatException invalid) {
			return computed;
		}
	}

	private static Properties loadWindowBounds() {
		Properties properties = new Properties();
		Path file = configDirectory.resolve("desktop-windows.properties");
		if (!Files.exists(file)) return properties;
		try (var reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException ignored) {
			// fall back to the computed geometry
		}
		return properties;
	}

	private static void saveWindowBounds() {
		Shell current = shell;
		if (current == null || !current.isVisible()) return;
		Properties properties = new Properties();
		java.awt.Rectangle bounds = current.getBounds();
		properties.setProperty("shell", bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height);
		properties.setProperty("tab", String.valueOf(current.currentTab()));
		try {
			Files.createDirectories(configDirectory);
			try (var writer = Files.newBufferedWriter(configDirectory.resolve("desktop-windows.properties"),
					java.nio.charset.StandardCharsets.UTF_8)) {
				writer.write("# ChampionsCode desktop window geometry (delete to re-centre)\n");
				properties.store(writer, null);
			}
		} catch (IOException ignored) {
			// geometry is a convenience, never a failure
		}
	}

	/**
	 * Crisp text before any AWT/Swing class is touched.
	 *
	 * <p>These properties decide whether the window is drawn at the display's real
	 * resolution or bitmap-stretched by Windows at 150 %, and whether small
	 * monospace text is subpixel-antialiased. {@code --ui-scale 1|1.25|1.5|2}
	 * overrides detection when the JVM guesses wrong.</p>
	 */
	private static void configureRendering(String uiScale) {
		System.setProperty("awt.useSystemAAFontSettings", "lcd");
		System.setProperty("swing.aatext", "true");
		if (System.getProperty("sun.java2d.dpiaware") == null) {
			System.setProperty("sun.java2d.dpiaware", "true");
		}
		if (System.getProperty("sun.java2d.uiScale.enabled") == null) {
			System.setProperty("sun.java2d.uiScale.enabled", "true");
		}
		if (System.getProperty("sun.java2d.uiScale") == null && !uiScale.equals("auto")) {
			System.setProperty("sun.java2d.uiScale", uiScale);
		}
	}

	// ---- console ---------------------------------------------------------

	private static ChampionConsole CONSOLE;

	private static synchronized StandaloneRuntime runtime() {
		if (RUNTIME == null) RUNTIME = new StandaloneRuntime(configDirectory, CONSOLE);
		return RUNTIME;
	}

	private static Path moduleFolder() {
		return configDirectory.resolve("modules");
	}

	private static Path defaultConfigDirectory() {
		String home = System.getProperty("user.home", ".");
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		Path base = os.contains("win")
			? Path.of(System.getenv().getOrDefault("APPDATA", home))
			: os.contains("mac") ? Path.of(home, "Library", "Application Support") : Path.of(home, ".config");
		return base.resolve("championscode").toAbsolutePath().normalize();
	}

	/** The same console the in-game UI drives, with UI callbacks the app owns. */
	private static ChampionConsole buildConsole(Path configDirectory, boolean demo) {
		Supplier<ModuleContext> context = demo ? DemoContext::capture : DemoContext::offline;
		ExploitWarningPrompt prompt = request -> SwingUtilities.invokeLater(() -> {
			int choice = JOptionPane.showConfirmDialog(null,
				"<html><b>" + request.moduleName() + "</b><br>"
					+ "Target: " + request.target() + "<br><br>"
					+ request.riskSummary() + "<br><br>"
					+ "Acknowledge for this single run?</html>",
				"Exploit research — legal risk", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION) request.confirm();
			else request.cancel();
		});
		ChampionConsole console = new ChampionConsole(configDirectory, context,
			ActiveModuleRuntime.disabled(), RuntimeJavaCommands.disabled(),
			() -> raiseWindow("modules"), () -> raiseWindow("modules"), () -> raiseWindow("terminal"),
			prompt, UpdateService.disabled(), () -> System.exit(0));
		console.setInterfacePreferences(new FileInterfacePreferences(configDirectory, () -> { }));
		// `repo install` writes the verified source into the drop-in folder and
		// lets the same loader that handles hand-dropped files compile it, so a
		// pulled module and a copied one take exactly the same path.
		console.setModuleRepository(new RepoConfig(configDirectory), (id, fileName, source) -> {
			try {
				Path folder = moduleFolder();
				Files.createDirectories(folder);
				Path target = folder.resolve(fileName);
				Files.writeString(target, source, java.nio.charset.StandardCharsets.UTF_8);
				StandaloneRuntime.Result result = runtime().reload();
				if (result.success()) {
					return ModuleSourceInstaller.Outcome.ok("Installed " + fileName
							+ (result.moduleIds().isEmpty() ? " into " + folder
							: "; loaded " + String.join(", ", result.moduleIds())));
				}
				return ModuleSourceInstaller.Outcome.failed("Wrote " + fileName + " but it did not load: "
						+ result.message());
			} catch (IOException | RuntimeException error) {
				return ModuleSourceInstaller.Outcome.failed("Could not install " + fileName + ": " + error.getMessage());
			}
		});
		return console;
	}

	private static void answerPrompts(RemoteConsolePort remote) {
		for (Map<String, Object> request : remote.pendingExploitPrompts()) {
			String requestId = String.valueOf(request.get("requestId"));
			int choice = JOptionPane.showConfirmDialog(null,
				"<html><b>" + request.get("name") + "</b><br>"
					+ "Target: " + request.get("target") + "<br><br>"
					+ request.get("risk") + "<br><br>"
					+ "Acknowledge for this single run in the game?</html>",
				"Exploit research — legal risk", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			remote.answerExploitPrompt(requestId, choice == JOptionPane.YES_OPTION);
		}
	}

	/** Scripted session used for screenshots and for a predictable smoke test. */
	private static void runDemoScript() {
		List<String> script = List.of(
			"help",
			"use auxiliary/audit/plugin_surface",
			"show options",
			"set CONFIRM I_HAVE_AUTHORIZATION",
			"run",
			"authorize current",
			"set CONFIRM I_HAVE_AUTHORIZATION",
			"run",
			"use auxiliary/audit/dependency_posture",
			"set COMPONENTS worldedit@7.3.0,essentialsx@2.20.1,protocollib@5.1.0,libreforge",
			"set CONFIRM I_HAVE_AUTHORIZATION",
			"run"
		);
		Timer timer = new Timer(420, null);
		int[] index = {0};
		timer.addActionListener(event -> {
			if (index[0] >= script.size()) {
				timer.stop();
				return;
			}
			String command = script.get(index[0]++);
			port.submit(command);
		});
		timer.setInitialDelay(600);
		timer.start();
	}

	private static Map<String, String> parse(String[] args) {
		Map<String, String> options = new LinkedHashMap<>();
		for (int index = 0; index < args.length; index++) {
			String arg = args[index];
			if (!arg.startsWith("--")) continue;
			String key = arg.substring(2);
			if (index + 1 < args.length && !args[index + 1].startsWith("--")) options.put(key, args[++index]);
			else options.put(key, "true");
		}
		return options;
	}
}
