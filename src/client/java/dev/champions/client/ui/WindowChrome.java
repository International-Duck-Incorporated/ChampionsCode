package dev.champions.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared desktop-style title-bar controls for ChampionsCode windows. */
public final class WindowChrome {
	public static final int BUTTON_WIDTH = 22;
	public static final int CONTROL_COUNT = 3;

	private WindowChrome() {}

	public enum Control { NONE, MINIMIZE, MAXIMIZE, CLOSE }

	public static int controlsLeft(int windowX, int windowWidth) {
		return windowX + windowWidth - BUTTON_WIDTH * CONTROL_COUNT;
	}

	public static boolean overControls(int windowX, int windowY, int windowWidth, int titleHeight,
		double mouseX, double mouseY) {
		return controlAt(windowX, windowY, windowWidth, titleHeight, mouseX, mouseY) != Control.NONE;
	}

	public static Control controlAt(int windowX, int windowY, int windowWidth, int titleHeight,
		double mouseX, double mouseY) {
		int left = controlsLeft(windowX, windowWidth);
		if (mouseY < windowY || mouseY >= windowY + titleHeight || mouseX < left || mouseX >= windowX + windowWidth) {
			return Control.NONE;
		}
		int index = ((int) mouseX - left) / BUTTON_WIDTH;
		return switch (index) {
			case 0 -> Control.MINIMIZE;
			case 1 -> Control.MAXIMIZE;
			case 2 -> Control.CLOSE;
			default -> Control.NONE;
		};
	}

	public static void render(GuiGraphicsExtractor graphics, Font font, int windowX, int windowY,
		int windowWidth, int titleHeight, int mouseX, int mouseY, int accentColor, boolean maximized) {
		int left = controlsLeft(windowX, windowWidth);
		Control hovered = controlAt(windowX, windowY, windowWidth, titleHeight, mouseX, mouseY);
		Control[] controls = {Control.MINIMIZE, Control.MAXIMIZE, Control.CLOSE};
		String[] labels = {"_", maximized ? "<>" : "[]", "x"};
		for (int i = 0; i < controls.length; i++) {
			int x = left + i * BUTTON_WIDTH;
			int background = controls[i] == hovered
				? controls[i] == Control.CLOSE ? 0xFFD84A4A : 0xFF3A3A42
				: 0x00000000;
			if (background != 0) graphics.fill(x, windowY, x + BUTTON_WIDTH, windowY + titleHeight, background);
			String label = labels[i];
			int color = controls[i] == hovered && controls[i] == Control.CLOSE ? 0xFFFFFFFF : accentColor;
			graphics.text(font, label, x + (BUTTON_WIDTH - font.width(label)) / 2,
				windowY + Math.max(5, (titleHeight - 8) / 2), color, false);
		}
	}
}
