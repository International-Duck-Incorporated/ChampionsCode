package dev.champions.desktop;

import dev.champions.api.java.RuntimeJavaCommands;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ExploitWarningPrompt;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.update.UpdateService;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

	/**
	 * Crisp text before any AWT/Swing class is touched.
	 *
	 * <p>These are the properties that decide whether the window is drawn at the
	 * display's real resolution or bitmap-stretched by Windows at 150 %: with
	 * {@code dpiaware} off the whole window (including its native title bar) is
	 * upscaled and looks blurry. {@code lcd} turns on subpixel antialiasing,
	 * which is what makes small monospace text look sharp instead of grey.</p>
	 *
	 * <p>{@code --ui-scale 1|1.25|1.5|2} forces a scale for setups where the JVM
	 * guesses wrong; {@code auto} keeps the JVM's own per-monitor detection.</p>
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

	private static final Map<String, JFrame> FRAMES = new ConcurrentHashMap<>();
	private static volatile ConsolePort port;
	private static volatile Path configDirectory;

	private DesktopMain() {}

	public static void main(String[] args) {
		Map<String, String> options = parse(args);
		configureRendering(options.getOrDefault("ui-scale", "auto"));
		if (options.containsKey("self-test")) {
			System.exit(SelfTest.run());
			return;
		}
		configDirectory = options.containsKey("config")
			? Path.of(options.get("config")).toAbsolutePath().normalize()
			: defaultConfigDirectory();
		try {
			if (options.containsKey("connect")) {
				int portNumber = Integer.parseInt(options.get("connect"));
				String token = options.getOrDefault("token", "");
				port = RemoteConsolePort.connect("127.0.0.1", portNumber, token, APP_VERSION);
				System.out.println("Attached to the game on 127.0.0.1:" + portNumber);
			} else {
				boolean demo = options.containsKey("demo");
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

	private static void buildUi(Map<String, String> options) {
		Theme.install();
		boolean demo = options.containsKey("demo");
		boolean attached = port instanceof RemoteConsolePort;

		if (demo) {
			port.buffer().append(dev.champions.core.console.ConsoleLine.warning(
				"DEMO HARNESS: the simulated target " + DemoContext.DEMO_TARGET
					+ " is not a real server and cannot resolve. Nothing leaves this machine."));
		}

		JFrame terminal = frame("ChampionsCode — Terminal", new TerminalPanel(port));
		JFrame modules = frame("ChampionsCode — Modules", new ModulesPanel(port));
		JFrame code = frame("ChampionsCode — Code Space",
			new CodeSpacePanel(port, port instanceof LocalConsolePort ? runtime() : null, moduleFolder()));

		FRAMES.put("terminal", terminal);
		FRAMES.put("modules", modules);
		FRAMES.put("codespace", code);

		terminal.setJMenuBar(menu());
		layout(terminal, modules, code);

		terminal.addWindowListener(exitWhenAllHidden());
		modules.addWindowListener(exitWhenAllHidden());
		code.addWindowListener(exitWhenAllHidden());

		terminal.setVisible(true);
		modules.setVisible(true);
		code.setVisible(true);

		if (port instanceof RemoteConsolePort remote) {
			remote.onExploitPrompt(() -> answerPrompts(remote));
		}
		if (demo) runDemoScript();
		System.out.println("ChampionsCode desktop " + APP_VERSION + " started in "
			+ (attached ? "attached" : "standalone") + " mode; config: " + configDirectory);
	}

	private static StandaloneRuntime RUNTIME;
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
		return new ChampionConsole(configDirectory, context,
			ActiveModuleRuntime.disabled(), RuntimeJavaCommands.disabled(),
			() -> raiseWindow("modules"), () -> raiseWindow("modules"), () -> raiseWindow("terminal"),
			prompt, UpdateService.disabled(), () -> System.exit(0));
	}

	private static JFrame frame(String title, javax.swing.JComponent content) {
		JFrame frame = new JFrame(title);
		frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		frame.setContentPane(content);
		frame.setBackground(Theme.BACKGROUND);
		frame.getContentPane().setBackground(Theme.BACKGROUND);
		frame.setMinimumSize(new Dimension(560, 340));
		return frame;
	}

	private static JMenuBar menu() {
		JMenuBar bar = new JMenuBar();
		JMenu windows = new JMenu("Windows");
		windows.add(menuItem("Terminal", "terminal"));
		windows.add(menuItem("Modules", "modules"));
		windows.add(menuItem("Code space", "codespace"));
		bar.add(windows);
		JMenu help = new JMenu("Help");
		JMenuItem about = new JMenuItem("About");
		about.addActionListener(event -> JOptionPane.showMessageDialog(null,
			"ChampionsCode desktop " + APP_VERSION + "\n"
				+ "Mode: " + port.mode() + "\n"
				+ "Config: " + configDirectory + "\n\n"
				+ "Commands run in the same gated console as the in-game terminal:\n"
				+ "authorization allowlist, one-run CONFIRM, per-run exploit warning, audit log.",
			"About ChampionsCode", JOptionPane.INFORMATION_MESSAGE));
		help.add(about);
		bar.add(help);
		return bar;
	}

	private static JMenuItem menuItem(String label, String windowId) {
		JMenuItem item = new JMenuItem(label);
		item.addActionListener(event -> raiseWindow(windowId));
		return item;
	}

	/**
	 * Tiles the three windows inside the usable screen area, in logical pixels so
	 * Windows display scaling (150 %, 200 %) never pushes a window off-screen.
	 * Restores the previous geometry when one was saved.
	 */
	private static void layout(JFrame terminal, JFrame modules, JFrame code) {
		java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
			.getMaximumWindowBounds();
		int gap = Math.max(8, Math.round(12 * uiScale(screen)));
		int width = screen.width - gap * 3;
		int height = screen.height - gap * 3;

		// Saved geometry wins when it still fits on this screen.
		java.util.Properties saved = loadWindowBounds();
		boolean restored = restore(terminal, saved, "terminal", screen)
			&& restore(modules, saved, "modules", screen)
			&& restore(code, saved, "codespace", screen);
		if (restored) return;

		// The modules window needs room for the list plus an option editor, so it
		// keeps a floor of 430 logical pixels; the terminal gives up width first.
		int leftWidth = Math.max(480, Math.round(width * 0.60f));
		int rightWidth = Math.max(430, width - leftWidth);
		while (leftWidth > 480 && leftWidth + rightWidth > width) {
			leftWidth -= 20;
			rightWidth = Math.max(430, width - leftWidth);
		}
		int topHeight = Math.max(360, Math.round(height * 0.60f));
		int bottomHeight = Math.max(240, height - topHeight);

		terminal.setBounds(screen.x + gap, screen.y + gap, leftWidth, topHeight);
		modules.setBounds(screen.x + gap * 2 + leftWidth, screen.y + gap, rightWidth,
			Math.min(screen.height - gap * 2, topHeight + bottomHeight + gap));
		code.setBounds(screen.x + gap, screen.y + gap * 2 + topHeight, leftWidth, bottomHeight);
	}

	private static float uiScale(java.awt.Rectangle screen) {
		java.awt.GraphicsConfiguration configuration = java.awt.GraphicsEnvironment
			.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
		float scale = (float) configuration.getDefaultTransform().getScaleX();
		return scale <= 0 ? 1f : scale;
	}

	private static java.util.Properties loadWindowBounds() {
		java.util.Properties properties = new java.util.Properties();
		Path file = configDirectory.resolve("desktop-windows.properties");
		if (!Files.exists(file)) return properties;
		try (var reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException ignored) {
			// fall back to the default tiling
		}
		return properties;
	}

	private static boolean restore(JFrame frame, java.util.Properties properties, String key,
			java.awt.Rectangle screen) {
		String value = properties.getProperty(key);
		if (value == null) return false;
		String[] parts = value.split(",");
		if (parts.length != 4) return false;
		try {
			java.awt.Rectangle bounds = new java.awt.Rectangle(
				Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
				Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
			// Ignore geometry from a monitor that is no longer attached.
			if (!screen.intersects(bounds)) return false;
			frame.setBounds(bounds);
			return true;
		} catch (NumberFormatException invalid) {
			return false;
		}
	}

	private static void saveWindowBounds() {
		java.util.Properties properties = new java.util.Properties();
		FRAMES.forEach((key, frame) -> {
			if (!frame.isVisible() || (frame.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) return;
			java.awt.Rectangle bounds = frame.getBounds();
			properties.setProperty(key, bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height);
		});
		try {
			Files.createDirectories(configDirectory);
			Path file = configDirectory.resolve("desktop-windows.properties");
			try (var writer = Files.newBufferedWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
				properties.store(writer, "ChampionsCode desktop window geometry (delete to re-tile)");
			}
		} catch (IOException ignored) {
			// geometry is a convenience, never a failure
		}
	}

	private static WindowAdapter exitWhenAllHidden() {
		return new WindowAdapter() {
			@Override public void windowClosing(WindowEvent event) {
				SwingUtilities.invokeLater(() -> {
					boolean anyVisible = FRAMES.values().stream().anyMatch(JFrame::isVisible);
					if (anyVisible) return;
					saveWindowBounds();
					System.exit(0);
				});
			}

			@Override public void windowOpened(WindowEvent event) {
				Runtime.getRuntime().addShutdownHook(new Thread(DesktopMain::saveWindowBounds, "champions-bounds-save"));
			}
		};
	}

	/** Called from the game over the bridge, and from the app's own menus. */
	public static void raiseWindow(String windowId) {
		SwingUtilities.invokeLater(() -> {
			JFrame frame = FRAMES.get(windowId);
			if (frame == null) frame = FRAMES.get("terminal");
			if (frame == null) return;
			frame.setVisible(true);
			frame.toFront();
			frame.requestFocus();
		});
	}

	private static void answerPrompts(RemoteConsolePort remote) {
		for (Map<String, Object> prompt : remote.pendingExploitPrompts()) {
			String requestId = String.valueOf(prompt.get("requestId"));
			int choice = JOptionPane.showConfirmDialog(null,
				"<html><b>" + prompt.get("name") + "</b><br>"
					+ "Target: " + prompt.get("target") + "<br><br>"
					+ prompt.get("risk") + "<br><br>"
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
			"run",
			"clickgui"
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
			if (command.equals("clickgui")) raiseWindow("modules");
		});
		timer.setInitialDelay(600);
		timer.start();
	}

	private static Map<String, String> parse(String[] args) {
		Map<String, String> options = new java.util.LinkedHashMap<>();
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
