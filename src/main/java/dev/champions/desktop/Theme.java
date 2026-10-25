package dev.champions.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Palette, typography and small widgets for the desktop app.
 *
 * <p>The look follows the project's own mark — the crimson {@code >_} prompt and
 * the double chevron — rather than the template green the first build used:
 * near-black surfaces with a violet cast, crimson accents, one green reserved
 * for success states, and generous spacing so nothing looks cramped.</p>
 *
 * <p>All sizes are logical pixels; the JVM's DPI scaling turns them into device
 * pixels, so the UI stays sharp on a scaled display.</p>
 */
public final class Theme {
	// brand
	public static final Color ACCENT = new Color(0xE8484F);
	public static final Color ACCENT_SOFT = new Color(0xFF6B72);
	public static final Color ACCENT_DIM = new Color(0x7A2A2F);

	// surfaces (warm near-black, faint violet cast like the reference UIs)
	public static final Color BACKGROUND = new Color(0x0E0E12);
	public static final Color PANEL = new Color(0x15151B);
	public static final Color ELEVATED = new Color(0x1B1B23);
	public static final Color TITLE = new Color(0x131318);
	public static final Color BORDER = new Color(0x272731);
	public static final Color BORDER_SOFT = new Color(0x1F1F28);
	public static final Color FIELD = new Color(0x101016);

	// text
	public static final Color TEXT = new Color(0xEDEDF2);
	public static final Color DIM = new Color(0x9A9AA6);
	public static final Color FAINT = new Color(0x63636F);

	// semantic
	public static final Color GREEN = new Color(0x4FCF6A);
	public static final Color YELLOW = new Color(0xE5C35A);
	public static final Color RED = new Color(0xFF6A6A);
	public static final Color BLUE = new Color(0x6E9BFF);

	public static final Color SELECTION = new Color(0x241A22);
	public static final Color HOVER = new Color(0x22222C);

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
			case ACCENT -> ACCENT_SOFT;
		};
	}

	public static Border line() {
		return BorderFactory.createLineBorder(BORDER);
	}

	public static Border softLine() {
		return BorderFactory.createLineBorder(BORDER_SOFT);
	}

	public static Border padding(int top, int left, int bottom, int right) {
		return BorderFactory.createEmptyBorder(top, left, bottom, right);
	}

	/** Rounded card used for every content surface. */
	public static JPanel card(java.awt.LayoutManager layout) {
		JPanel panel = new JPanel(layout) {
			@Override
			protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				try {
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g.setColor(PANEL);
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
					g.setColor(BORDER);
					g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
				} finally {
					g.dispose();
				}
			}
		};
		panel.setOpaque(false);
		return panel;
	}

	/** Section header: title, optional subtitle, right-aligned status chips. */
	public static JPanel header(String title, String subtitle, JComponent... chips) {
		JPanel header = new JPanel(new java.awt.BorderLayout(12, 0));
		header.setOpaque(false);
		header.setBorder(padding(12, 16, 10, 16));

		JPanel text = new JPanel(new java.awt.BorderLayout(0, 2));
		text.setOpaque(false);
		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(sans(14, true));
		titleLabel.setForeground(TEXT);
		text.add(titleLabel, java.awt.BorderLayout.NORTH);
		if (subtitle != null && !subtitle.isBlank()) {
			JLabel subtitleLabel = new JLabel(subtitle);
			subtitleLabel.setFont(mono(10));
			subtitleLabel.setForeground(FAINT);
			text.add(subtitleLabel, java.awt.BorderLayout.CENTER);
		}
		header.add(text, java.awt.BorderLayout.WEST);

		if (chips.length > 0) {
			JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			row.setOpaque(false);
			for (JComponent chip : chips) row.add(chip);
			header.add(row, java.awt.BorderLayout.EAST);
		}
		return header;
	}

	/** Small pill label, e.g. "ATTACHED", "17 modules", "AUTHORIZED". */
	public static JLabel chip(String text, Color colour) {
		JLabel chip = new JLabel(text.toUpperCase(java.util.Locale.ROOT)) {
			@Override
			protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				try {
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					Color base = colour;
					g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 34));
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
					g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 90));
					g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
				} finally {
					g.dispose();
				}
				super.paintComponent(graphics);
			}
		};
		chip.setFont(monoBold(9));
		chip.setForeground(colour);
		chip.setBorder(padding(4, 9, 4, 9));
		return chip;
	}

	/** Flat button; {@code accent} colours the label, the surface is the card colour. */
	public static JButton button(String label, Color accent, ActionListener action) {
		return button(label, accent, false, action);
	}

	/** Solid crimson call-to-action. */
	public static JButton filled(String label, ActionListener action) {
		return button(label, Color.WHITE, true, action);
	}

	public static JButton primary(String label, ActionListener action) {
		return button(label, Color.WHITE, true, action);
	}

	private static JButton button(String label, Color accent, boolean filled, ActionListener action) {
		JButton button = new JButton(label) {
			@Override
			protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				try {
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					boolean hot = getModel().isRollover() && isEnabled();
					Color fill = filled ? (hot ? ACCENT_SOFT : ACCENT) : (hot ? HOVER : ELEVATED);
					if (!isEnabled()) fill = new Color(0x1A1A21);
					g.setColor(fill);
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
					if (!filled) {
						g.setColor(hot ? new Color(0x3A3A46) : BORDER);
						g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
					}
				} finally {
					g.dispose();
				}
				super.paintComponent(graphics);
			}
		};
		button.setFont(sans(12, filled));
		button.setForeground(filled ? Color.WHITE : accent);
		button.setContentAreaFilled(false);
		button.setBorderPainted(false);
		button.setFocusPainted(false);
		button.setOpaque(false);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setBorder(padding(7, 14, 7, 14));
		button.setMargin(new Insets(0, 0, 0, 0));
		if (!button.isEnabled()) button.setForeground(FAINT);
		if (action != null) button.addActionListener(action);
		return button;
	}

	public static JLabel label(String text, Color colour, int size, boolean bold) {
		JLabel label = new JLabel(text);
		label.setFont(bold ? monoBold(size) : mono(size));
		label.setForeground(colour);
		return label;
	}

	public static Dimension pref(int width, int height) {
		return new Dimension(width, height);
	}

	/** Hover-tracking helper for custom-painted rows. */
	public static void onHover(Component component, Runnable repaint) {
		component.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent event) { repaint.run(); }
			@Override public void mouseExited(MouseEvent event) { repaint.run(); }
		});
	}

	public static void install() {
		UIManager.put("Panel.background", BACKGROUND);
		UIManager.put("Viewport.background", BACKGROUND);
		UIManager.put("List.background", PANEL);
		UIManager.put("List.foreground", TEXT);
		UIManager.put("List.selectionBackground", SELECTION);
		UIManager.put("List.selectionForeground", TEXT);
		UIManager.put("Tree.background", PANEL);
		UIManager.put("Tree.foreground", TEXT);
		UIManager.put("Tree.selectionBackground", SELECTION);
		UIManager.put("Tree.selectionForeground", TEXT);
		UIManager.put("TextArea.background", FIELD);
		UIManager.put("TextArea.foreground", TEXT);
		UIManager.put("TextArea.caretForeground", ACCENT_SOFT);
		UIManager.put("TextArea.selectionBackground", SELECTION);
		UIManager.put("TextField.background", FIELD);
		UIManager.put("TextField.foreground", TEXT);
		UIManager.put("TextField.caretForeground", ACCENT_SOFT);
		UIManager.put("TextField.selectionBackground", SELECTION);
		UIManager.put("ComboBox.background", ELEVATED);
		UIManager.put("ComboBox.foreground", TEXT);
		UIManager.put("ComboBox.selectionBackground", SELECTION);
		UIManager.put("ComboBox.selectionForeground", TEXT);
		UIManager.put("ScrollBar.thumb", new Color(0x33333E));
		UIManager.put("ScrollBar.track", BACKGROUND);
		UIManager.put("ScrollBar.width", 10);
		UIManager.put("ScrollPane.background", BACKGROUND);
		UIManager.put("SplitPane.background", BACKGROUND);
		UIManager.put("SplitPane.dividerSize", 6);
		UIManager.put("SplitPaneDivider.bg", BACKGROUND);
		UIManager.put("ToolTip.background", ELEVATED);
		UIManager.put("ToolTip.foreground", TEXT);
		UIManager.put("ToolTip.border", line());
		UIManager.put("OptionPane.background", PANEL);
		UIManager.put("OptionPane.messageForeground", TEXT);
		UIManager.put("Label.font", sans(12, false));
		UIManager.put("Label.foreground", TEXT);
	}
}
