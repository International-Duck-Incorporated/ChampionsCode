package dev.champions.client;

import dev.champions.client.java.JavaRuntimeManager;
import dev.champions.client.ui.ChampionsWorkspaceScreen;
import dev.champions.core.console.ChampionConsole;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Supplier;

/**
 * Single entry point for every "open a ChampionsCode UI" trigger (keybinds,
 * console commands, navigation buttons). All UIs live as windows of one shared
 * {@link ChampionsWorkspaceScreen}, so opening one never closes another:
 * if a workspace is already open the window is toggled inside it, otherwise a
 * fresh workspace is created.
 */
public final class ChampionsScreens {
	public static final String TERMINAL = "terminal";
	public static final String CLICKGUI = "clickgui";
	public static final String CATEGORYGUI = "categorygui";
	public static final String JAVASTUDIO = "javastudio";
	public static final String LOGVIEWER = "log";

	private ChampionsScreens() {}

	public static void open(Minecraft client, ChampionConsole console, Supplier<JavaRuntimeManager> javaRuntime,
			KeyMapping openConsoleBinding, KeyMapping openJavaStudioBinding, KeyMapping openLogViewerBinding,
			String windowId) {
		Screen current = client.gui.screen();
		if (current instanceof ChampionsWorkspaceScreen workspace) {
			// Commands and navigation buttons focus an existing window; toggling
			// (close when open) is reserved for the keybinds, which the workspace
			// intercepts itself in keyPressed.
			workspace.show(windowId);
			return;
		}
		client.setScreenAndShow(new ChampionsWorkspaceScreen(console, javaRuntime,
			openConsoleBinding, openJavaStudioBinding, openLogViewerBinding, windowId));
	}

	/** Closes the terminal window (used by the console's exit command). */
	public static void closeTerminal(Minecraft client) {
		Screen current = client.gui.screen();
		if (current instanceof ChampionsWorkspaceScreen workspace) {
			workspace.close(TERMINAL);
		} else {
			client.setScreenAndShow(null);
		}
	}
}
