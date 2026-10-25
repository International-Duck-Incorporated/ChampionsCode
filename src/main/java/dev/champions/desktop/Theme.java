package dev.champions.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The ChampionsCode palette and widget kit, mirroring the in-game terminal
 * colours exactly.
 *
 * <p>Everything here stays in logical pixels: the JVM's own DPI scaling turns
 * them into device pixels, so the app is crisp on a 150 % or 200 % Windows
 * display instead of being bitmap-stretched.</p>
 */
public final class Theme {
	public static final Color BACKGROUND = new Color(0x090909);
	public static final Color PANEL = new Color(0x141414);
	public static final Color TITLE = new Color(0x252525);
	public static final Color BORDER = new Color(0x353535);
	public static final Color FIELD = new Color(0x1B1B1B);
	public static final Color TEXT = new Color(0xF0F0F0);
	public static final Color DIM = new Color(0x9A9A9A);
	public static final Color FAINT = new Color(0x6A6A6A);
	public static final Color GREEN = new Color(0x55D65A);
	public static final Color GREEN_BRIGHT = new Color(0x58D66B);
	public static final Color YELLOW = new Color(0xE5C35A);
	public static final Color RED = new Color(0xFF6868);
	public static final Color BLUE = new Color(0x5F9DFF);
	public static final Color SELECTION = new Color(0x223452);
	public static final Color HOVER = new Color(0x33343A);

	private Theme() {}

	public static Font mono(int size) {
		return Fonts.mono(size);
	}

	public static Font monoBold(int size) {
		return Fonts.monoBold(size);
	}

	public static Font sans(int size, boolean bold) {
		return Fonts.sans(size, bold);
	}

	public static Color tone(dev.champions.core.console.ConsoleLine.Tone tone) {
		return switch (tone) {
			case MUTED -> DIM;
			case INFO -> TEXT;
			case SUCCESS -> GREEN;
			case WARNING -> YELLOW;
			case ERROR -> RED;
			case ACCENT -> BLUE;
		};
	}

	/** Flat dark border used for cards, fields and lists. */
	public static Border line() {
		return BorderFactory.createLineBorder(BORDER);
	}

	public static Border padding(int top, int left, int bottom, int right) {
		return BorderFactory.createEmptyBorder(top, left, bottom, right);
	}

	/**
	 * A flat dark button with a hover state. {@code accent} colours the label
	 * (use {@link #GREEN} for the primary action, {@link #DIM} for secondary).
	 */
	public static JButton button(String label, Color accent, ActionListener action) {
		JButton button = new JButton(label) {
			@Override
			protected void paintComponent(java.awt.Graphics graphics) {
				java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
				try {
					g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
						java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
					boolean hot = getModel().isRollover() && isEnabled();
					g.setColor(hot ? HOVER : new Color(0x242424));
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
					g.setColor(hot ? new Color(0x4A4A52) : BORDER);
					g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
				} finally {
					g.dispose();
				}
				super.paintComponent(graphics);
			}
		};
		button.setFont(mono(12));
		button.setForeground(accent);
		button.setBackground(new Color(0x242424));
		button.setContentAreaFilled(false);
		button.setBorderPainted(false);
		button.setFocusPainted(false);
		button.setOpaque(false);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setBorder(padding(6, 12, 6, 12));
		button.setMargin(new Insets(0, 0, 0, 0));
		if (action != null) button.addActionListener(action);
		return button;
	}

	/** A small monospace label with the given colour. */
	public static javax.swing.JLabel label(String text, Color color, int size, boolean bold) {
		javax.swing.JLabel label = new javax.swing.JLabel(text);
		label.setFont(bold ? monoBold(size) : mono(size));
		label.setForeground(color);
		return label;
	}

	/** Wraps a component in a dark panel with padding, for consistent gutters. */
	public static javax.swing.JPanel card(JComponent content, int gap) {
		javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout());
		panel.setBackground(BACKGROUND);
		panel.setBorder(padding(gap, gap, gap, gap));
		panel.add(content, java.awt.BorderLayout.CENTER);
		return panel;
	}

	/** Hover highlight for clickable non-button surfaces (list rows, tree rows). */
	public static void hoverHighlight(Component component) {
		component.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent event) {
				component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			}
		});
	}

	public static Dimension pref(int width, int height) {
		return new Dimension(width, height);
	}

	/** Dark defaults for the Swing components the app uses. */
	public static void install() {
		UIManager.put("Panel.background", BACKGROUND);
		UIManager.put("Viewport.background", BACKGROUND);
		UIManager.put("List.background", PANEL);
		UIManager.put("List.foreground", TEXT);
		UIManager.put("List.selectionBackground", SELECTION);
		UIManager.put("List.selectionForeground", Color.WHITE);
		UIManager.put("Tree.background", PANEL);
		UIManager.put("Tree.foreground", TEXT);
		UIManager.put("Tree.selectionBackground", SELECTION);
		UIManager.put("Tree.selectionForeground", Color.WHITE);
		UIManager.put("Tree.hash", BORDER);
		UIManager.put("TextField.background", FIELD);
		UIManager.put("TextField.foreground", TEXT);
		UIManager.put("TextField.caretForeground", GREEN);
		UIManager.put("TextField.selectionBackground", SELECTION);
		UIManager.put("TextArea.background", FIELD);
		UIManager.put("TextArea.foreground", TEXT);
		UIManager.put("TextArea.caretForeground", GREEN);
		UIManager.put("TextArea.selectionBackground", SELECTION);
		UIManager.put("TextPane.background", FIELD);
		UIManager.put("TextPane.foreground", TEXT);
		UIManager.put("ComboBox.background", FIELD);
		UIManager.put("ComboBox.foreground", TEXT);
		UIManager.put("ComboBox.selectionBackground", SELECTION);
		UIManager.put("ComboBox.selectionForeground", Color.WHITE);
		UIManager.put("Button.background", new Color(0x242424));
		UIManager.put("Button.foreground", TEXT);
		UIManager.put("Label.foreground", TEXT);
		UIManager.put("ScrollPane.background", BACKGROUND);
		UIManager.put("ScrollBar.thumb", new Color(0x3A3A42));
		UIManager.put("ScrollBar.track", PANEL);
		UIManager.put("ScrollBar.width", 10);
		UIManager.put("SplitPane.background", BACKGROUND);
		UIManager.put("SplitPane.dividerSize", 6);
		UIManager.put("SplitPaneDivider.bg", BACKGROUND);
		UIManager.put("MenuBar.background", TITLE);
		UIManager.put("MenuBar.foreground", TEXT);
		UIManager.put("Menu.background", TITLE);
		UIManager.put("Menu.foreground", TEXT);
		UIManager.put("MenuItem.background", TITLE);
		UIManager.put("MenuItem.foreground", TEXT);
		UIManager.put("PopupMenu.background", TITLE);
		UIManager.put("ToolTip.background", TITLE);
		UIManager.put("ToolTip.foreground", TEXT);
		UIManager.put("OptionPane.background", PANEL);
		UIManager.put("OptionPane.messageForeground", TEXT);
		UIManager.put("ComboBox.font", mono(12));
		UIManager.put("Label.font", sans(12, false));
	}
}
