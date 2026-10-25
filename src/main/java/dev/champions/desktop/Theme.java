package dev.champions.desktop;

import dev.champions.core.console.ConsoleLine;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/** The ChampionsCode palette, mirroring the in-game terminal colours exactly. */
public final class Theme {
	public static final Color BACKGROUND = new Color(0x090909);
	public static final Color PANEL = new Color(0x141414);
	public static final Color TITLE = new Color(0x252525);
	public static final Color BORDER = new Color(0x353535);
	public static final Color FIELD = new Color(0x1B1B1B);
	public static final Color TEXT = new Color(0xF0F0F0);
	public static final Color DIM = new Color(0x9A9A9A);
	public static final Color GREEN = new Color(0x55D65A);
	public static final Color GREEN_BRIGHT = new Color(0x58D66B);
	public static final Color YELLOW = new Color(0xE5C35A);
	public static final Color RED = new Color(0xFF6868);
	public static final Color BLUE = new Color(0x5F9DFF);

	private Theme() {}

	public static Font mono(int size) {
		return new Font(Font.MONOSPACED, Font.PLAIN, size);
	}

	public static Font monoBold(int size) {
		return new Font(Font.MONOSPACED, Font.BOLD, size);
	}

	public static Font sans(int size, boolean bold) {
		return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size);
	}

	public static Color tone(ConsoleLine.Tone tone) {
		return switch (tone) {
			case MUTED -> DIM;
			case INFO -> TEXT;
			case SUCCESS -> GREEN;
			case WARNING -> YELLOW;
			case ERROR -> RED;
			case ACCENT -> BLUE;
		};
	}

	/** Dark defaults for the handful of Swing components the app uses. */
	public static void install() {
		UIManager.put("Panel.background", BACKGROUND);
		UIManager.put("Viewport.background", BACKGROUND);
		UIManager.put("List.background", PANEL);
		UIManager.put("List.foreground", TEXT);
		UIManager.put("List.selectionBackground", new Color(0x2C3E5A));
		UIManager.put("List.selectionForeground", Color.WHITE);
		UIManager.put("Tree.background", PANEL);
		UIManager.put("Tree.foreground", TEXT);
		UIManager.put("Tree.selectionBackground", new Color(0x2C3E5A));
		UIManager.put("Tree.selectionForeground", Color.WHITE);
		UIManager.put("TextField.background", FIELD);
		UIManager.put("TextField.foreground", TEXT);
		UIManager.put("TextField.caretForeground", GREEN);
		UIManager.put("TextField.selectionBackground", new Color(0x2C3E5A));
		UIManager.put("TextArea.background", FIELD);
		UIManager.put("TextArea.foreground", TEXT);
		UIManager.put("TextArea.caretForeground", GREEN);
		UIManager.put("TextPane.background", FIELD);
		UIManager.put("TextPane.foreground", TEXT);
		UIManager.put("ComboBox.background", FIELD);
		UIManager.put("ComboBox.foreground", TEXT);
		UIManager.put("Button.background", new Color(0x2A2A2A));
		UIManager.put("Button.foreground", TEXT);
		UIManager.put("Label.foreground", TEXT);
		UIManager.put("ScrollPane.background", BACKGROUND);
		UIManager.put("SplitPane.background", BACKGROUND);
		UIManager.put("SplitPane.dividerSize", 6);
		UIManager.put("MenuBar.background", TITLE);
		UIManager.put("MenuBar.foreground", TEXT);
		UIManager.put("Menu.background", TITLE);
		UIManager.put("Menu.foreground", TEXT);
		UIManager.put("MenuItem.background", TITLE);
		UIManager.put("MenuItem.foreground", TEXT);
		UIManager.put("ToolTip.background", TITLE);
		UIManager.put("ToolTip.foreground", TEXT);
	}
}
