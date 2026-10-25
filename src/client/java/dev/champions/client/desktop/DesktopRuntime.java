package dev.champions.client.desktop;

import dev.champions.bridge.DesktopBridge;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ExploitWarningPrompt;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.net.PlayerIdentityEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Owns the external-interface side of the mod: settings, the loopback bridge,
 * and the app process.
 *
 * <p>It is the single place that decides whether a "open the UI" request is
 * served by the in-game workspace, the external app, or both.</p>
 */
public final class DesktopRuntime implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("champions-desktop");
	private static final long ATTACH_TIMEOUT_MILLIS = 15_000L;

	private final DesktopSettings settings;
	private final DesktopLauncher launcher;
	private final ChampionConsole console;
	private final Supplier<ModuleContext> context;
	private final Supplier<List<String>> players;
	private final Supplier<String> version;
	private final java.util.concurrent.Executor mainThread;
	private final Path configDirectory;
	private volatile DesktopBridge bridge;

	public DesktopRuntime(Path configDirectory, ChampionConsole console, Supplier<ModuleContext> context,
			Supplier<List<String>> players, Supplier<String> version,
			java.util.concurrent.Executor mainThread) {
		this.configDirectory = configDirectory;
		this.settings = new DesktopSettings(configDirectory);
		this.launcher = new DesktopLauncher(configDirectory);
		this.console = console;
		this.context = context;
		this.players = players;
		this.version = version;
		this.mainThread = mainThread;
	}

	public DesktopSettings settings() {
		return settings;
	}

	public boolean isAttached() {
		return bridge != null && bridge.isAttached();
	}

	/** Starts the loopback bridge. Safe to call once during client init. */
	public synchronized void start() {
		if (bridge != null || !settings.usesExternal()) return;
		try {
			bridge = new DesktopBridge(console, context, players, version, mainThread, configDirectory);
			bridge.start();
		} catch (IOException | RuntimeException error) {
			LOG.warn("external interface unavailable ({}); the in-game windows still work.", error.toString());
			bridge = null;
		}
	}

	/**
	 * Routes an "open this window" request.
	 *
	 * @param windowId one of terminal, modules, codespace
	 * @param openInGame fallback used when the external app is off or unavailable
	 */
	public void show(String windowId, Runnable openInGame) {
		if (!settings.usesExternal()) {
			if (openInGame != null) openInGame.run();
			return;
		}
		if (openInGame != null && settings.usesInGame()) openInGame.run();
		start();
		if (bridge == null) return;
		if (bridge.isAttached()) {
			bridge.showWindow(windowId);
			return;
		}
		if (!settings.spawnOnOpen()) return;
		if (launcher.spawn(bridge.port(), bridge.token(), configDirectory.toString())) {
			waitForAttachmentAndShow(windowId);
		}
	}

	private void waitForAttachmentAndShow(String windowId) {
		Thread waiter = new Thread(() -> {
			long deadline = System.currentTimeMillis() + ATTACH_TIMEOUT_MILLIS;
			while (System.currentTimeMillis() < deadline) {
				if (bridge != null && bridge.isAttached()) {
					bridge.showWindow(windowId);
					return;
				}
				try {
					Thread.sleep(250L);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			LOG.warn("the desktop app did not attach within {}s; see {}",
				ATTACH_TIMEOUT_MILLIS / 1000, configDirectory.resolve("desktop.log"));
		}, "champions-desktop-attach");
		waiter.setDaemon(true);
		waiter.start();
	}

	/**
	 * Offers the exploit-research warning to the desktop app first.
	 *
	 * @return true when the app took the prompt; false means the caller must show
	 *         the in-game screen instead
	 */
	public boolean offerExploitPrompt(ExploitWarningPrompt.Request request) {
		DesktopBridge current = bridge;
		return current != null && current.offerExploitPrompt(request);
	}

	/** Printed by {@code desktop} console commands so users can start the app by hand. */
	public String commandPreview() {
		if (bridge == null) return "external interface is disabled in " + settings.file();
		return String.join(" ", launcher.commandPreview(bridge.port()));
	}

	@Override
	public void close() {
		if (bridge != null) bridge.close();
		launcher.close();
		PlayerIdentityEvents.clearListener();
	}
}
