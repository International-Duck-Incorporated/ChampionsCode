package dev.champions.client.ui;

import dev.champions.client.ChampionsScreens;
import dev.champions.client.java.JavaRuntimeManager;
import dev.champions.core.console.ChampionConsole;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Desktop that hosts the ChampionsCode screens (terminal, ClickGUI, category
 * GUI, Java Studio) as independent, simultaneously visible windows.
 *
 * The windows are the existing screens, unchanged: each one is constructed as
 * a delegate, receives this screen as its {@link WorkspaceHost}, and keeps
 * full standalone behavior when no host is set. The workspace owns only
 * window lifecycle, z-order, focus, and input routing:
 * <ul>
 *   <li>mouse events go to the topmost window under the cursor,</li>
 *   <li>a press binds its window until release (drag continuity),</li>
 *   <li>keys and typed characters go to the focused window,</li>
 *   <li>ESC never reaches a window: it closes the whole workspace, and the
 *       console/Java keybinds toggle their windows while the workspace is open.</li>
 * </ul>
 * Closing the last window closes the workspace entirely.
 */
public final class ChampionsWorkspaceScreen extends Screen implements WorkspaceHost {
	private final ChampionConsole console;
	private final Supplier<JavaRuntimeManager> javaRuntime;
	private final KeyMapping openConsoleBinding;
	private final KeyMapping openJavaStudioBinding;
	private final String initialWindow;
	private final Map<String, Screen> windows = new LinkedHashMap<>();
	private final List<String> zOrder = new ArrayList<>();
	private String focusedWindow;
	private String pressWindow;
	private boolean opened;

	public ChampionsWorkspaceScreen(ChampionConsole console, Supplier<JavaRuntimeManager> javaRuntime,
			KeyMapping openConsoleBinding, KeyMapping openJavaStudioBinding, String initialWindow) {
		super(Component.literal("ChampionsCode Workspace"));
		this.console = console;
		this.javaRuntime = javaRuntime;
		this.openConsoleBinding = openConsoleBinding;
		this.openJavaStudioBinding = openJavaStudioBinding;
		this.initialWindow = initialWindow == null ? ChampionsScreens.TERMINAL : initialWindow;
	}

	@Override
	protected void init() {
		if (opened) return; // reposition pass (resize); windows reposition themselves
		opened = true;
		show(initialWindow);
	}

	@Override
	public void resize(int newWidth, int newHeight) {
		super.resize(newWidth, newHeight);
		for (Map.Entry<String, Screen> entry : windows.entrySet()) {
			entry.getValue().init(newWidth, newHeight);
		}
	}

	public boolean isWindowOpen(String windowId) {
		return windows.containsKey(windowId);
	}

	/** Opens the window if absent, otherwise focuses it. */
	public void show(String windowId) {
		Screen existing = windows.get(windowId);
		if (existing != null) {
			focus(windowId);
			return;
		}
		Screen window = createWindow(windowId);
		if (window == null) return;
		if (window instanceof ChampionsConsoleScreen consoleScreen) consoleScreen.host(this);
		if (window instanceof ChampionsClickGuiScreen clickGui) clickGui.host(this);
		if (window instanceof ChampionsCategoryGuiScreen categoryGui) categoryGui.host(this);
		if (window instanceof ChampionsJavaScreen javaScreen) javaScreen.host(this);
		windows.put(windowId, window);
		zOrder.add(windowId);
		window.init(width, height);
		focus(windowId);
	}

	/** Closes the window; when the last one closes, the workspace closes. */
	public void close(String windowId) {
		Screen removed = windows.remove(windowId);
		if (removed == null) return;
		zOrder.remove(windowId);
		if (windowId.equals(pressWindow)) pressWindow = null;
		if (windowId.equals(focusedWindow)) focusedWindow = zOrder.isEmpty() ? null : zOrder.getLast();
		if (windows.isEmpty() && minecraft != null && minecraft.gui.screen() == this) {
			minecraft.setScreenAndShow(null);
		}
	}

	/** Toggles: closes the window when open, opens (and focuses) it otherwise. */
	public void toggle(String windowId) {
		if (windows.containsKey(windowId)) close(windowId);
		else show(windowId);
	}

	private void focus(String windowId) {
		focusedWindow = windowId;
		zOrder.remove(windowId);
		zOrder.add(windowId);
	}

	private Screen createWindow(String windowId) {
		return switch (windowId) {
			case ChampionsScreens.TERMINAL -> new ChampionsConsoleScreen(console);
			case ChampionsScreens.CLICKGUI -> new ChampionsClickGuiScreen(console, null);
			case ChampionsScreens.CATEGORYGUI -> new ChampionsCategoryGuiScreen(console, null);
			case ChampionsScreens.JAVASTUDIO -> new ChampionsJavaScreen(console, javaRuntime.get());
			default -> null;
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		for (String windowId : zOrder) {
			windows.get(windowId).extractRenderState(graphics, mouseX, mouseY, delta);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		String windowId = windowAt(event.x(), event.y());
		if (windowId == null) return false;
		focus(windowId);
		pressWindow = windowId;
		return windows.get(windowId).mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (pressWindow != null && windows.containsKey(pressWindow)) {
			return windows.get(pressWindow).mouseDragged(event, deltaX, deltaY);
		}
		return super.mouseDragged(event, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (pressWindow != null && windows.containsKey(pressWindow)) {
			String windowId = pressWindow;
			pressWindow = null;
			return windows.get(windowId).mouseReleased(event);
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		String windowId = windowAt(mouseX, mouseY);
		if (windowId == null) return false;
		return windows.get(windowId).mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		String windowId = windowAt(mouseX, mouseY);
		if (windowId != null) windows.get(windowId).mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (openConsoleBinding != null && openConsoleBinding.matches(event)) {
			toggle(ChampionsScreens.TERMINAL);
			return true;
		}
		if (openJavaStudioBinding != null && openJavaStudioBinding.matches(event)) {
			toggle(ChampionsScreens.JAVASTUDIO);
			return true;
		}
		if (event.input() == InputConstants.KEY_ESCAPE) {
			// ESC closes the whole workspace at once; windows never see it.
			onClose();
			return true;
		}
		if (focusedWindow != null && windows.containsKey(focusedWindow)) {
			return windows.get(focusedWindow).keyPressed(event);
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (focusedWindow != null && windows.containsKey(focusedWindow)) {
			return windows.get(focusedWindow).charTyped(event);
		}
		return super.charTyped(event);
	}

	private String windowAt(double mouseX, double mouseY) {
		for (int i = zOrder.size() - 1; i >= 0; i--) {
			String windowId = zOrder.get(i);
			Screen window = windows.get(windowId);
			if (window instanceof WorkspaceWindow workspaceWindow && workspaceWindow.coversPoint(mouseX, mouseY)) {
				return windowId;
			}
		}
		return null;
	}

	@Override
	public void onClose() {
		windows.clear();
		zOrder.clear();
		pressWindow = null;
		focusedWindow = null;
		if (minecraft != null) minecraft.setScreenAndShow(null);
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }

	@Override
	public void requestOpen(String windowId) {
		show(windowId);
	}

	@Override
	public void requestClose(String windowId) {
		close(windowId);
	}

	@Override
	public Screen self() {
		return this;
	}
}
