package dev.champions.desktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single-window shell: a frameless window with its own title bar, an icon
 * rail, a card-backed content area and a status bar.
 *
 * <p>Frameless is what makes the app feel modern instead of retro — the whole
 * chrome (brand mark, window controls, accent line) is drawn by the app, so it
 * matches on Windows, macOS and Linux. The trade-off is that the app owns
 * dragging and resizing: the title bar moves the window, the outer 6 logical
 * pixels resize it.</p>
 */
public final class Shell extends JFrame {
	private static final int DRAG_BORDER = 6;

	private final JPanel rail = new JPanel();
	private final JPanel cards = new JPanel(new CardLayout());
	private final JPanel contentHost = new JPanel(new BorderLayout());
	private final JLabel statusLeft = new JLabel(" ");
	private final JLabel statusRight = new JLabel(" ");
	private final Map<String, RailButton> tabs = new LinkedHashMap<>();
	private String current;
	private Point dragOffset;
	private Rectangle restoreBounds;

	public Shell(String title) {
		super(title);
		setUndecorated(true);
		setBackground(Theme.BACKGROUND);
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setMinimumSize(new Dimension(880, 540));

		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(Theme.BACKGROUND);
		root.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		setContentPane(root);

		root.add(titleBar(), BorderLayout.NORTH);

		rail.setBackground(Theme.TITLE);
		rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
		rail.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER),
			BorderFactory.createEmptyBorder(10, 8, 10, 8)));
		rail.setPreferredSize(new Dimension(96, 600));
		root.add(rail, BorderLayout.WEST);

		contentHost.setBackground(Theme.BACKGROUND);
		contentHost.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
		contentHost.add(cards, BorderLayout.CENTER);
		root.add(contentHost, BorderLayout.CENTER);

		root.add(statusBar(), BorderLayout.SOUTH);
		new WindowResizer(this, DRAG_BORDER).install();
	}

	// ---- chrome ----------------------------------------------------------

	private JComponent titleBar() {
		JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(Theme.TITLE);
		bar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
			BorderFactory.createEmptyBorder(8, 14, 8, 8)));

		JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		brand.setOpaque(false);
		brand.add(new JComponent() {
			@Override protected void paintComponent(Graphics graphics) {
				Icons.paint((Graphics2D) graphics.create(), Icons.Glyph.BRAND, 2, 1, 20, Theme.ACCENT);
			}

			@Override public Dimension getPreferredSize() { return new Dimension(24, 22); }
		});
		JLabel wordmark = new JLabel("ChampionsCode");
		wordmark.setFont(Theme.sans(13, true));
		wordmark.setForeground(Theme.TEXT);
		brand.add(wordmark);
		JLabel tag = new JLabel("SECURITY RESEARCH CONSOLE");
		tag.setFont(Theme.mono(9));
		tag.setForeground(Theme.FAINT);
		tag.setBorder(Theme.padding(3, 8, 0, 0));
		brand.add(tag);
		bar.add(brand, BorderLayout.WEST);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		controls.setOpaque(false);
		controls.add(windowButton("–", "Minimize", this::minimize));
		controls.add(windowButton("▢", "Maximize", this::toggleMaximise));
		controls.add(windowButton("✕", "Close", this::closeWindow));
		bar.add(controls, BorderLayout.EAST);

		// Drag anywhere on the bar that is not a button.
		MouseAdapter drag = new MouseAdapter() {
			@Override public void mousePressed(MouseEvent event) {
				dragOffset = event.getPoint();
			}

			@Override public void mouseReleased(MouseEvent event) {
				dragOffset = null;
			}
		};
		MouseMotionAdapter move = new MouseMotionAdapter() {
			@Override public void mouseDragged(MouseEvent event) {
				if (dragOffset == null) return;
				Point location = event.getLocationOnScreen();
				setLocation(location.x - dragOffset.x, location.y - dragOffset.y);
			}
		};
		bar.addMouseListener(drag);
		bar.addMouseMotionListener(move);
		for (Component child : bar.getComponents()) {
			if (child instanceof JPanel panel && !(child == controls)) {
				panel.addMouseListener(drag);
				panel.addMouseMotionListener(move);
			}
		}
		return bar;
	}

	private JComponent windowButton(String glyph, String tooltip, Runnable action) {
		JLabel button = new JLabel(glyph, SwingConstants.CENTER) {
			private boolean hover;

			{
				addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent event) { hover = true; repaint(); }
					@Override public void mouseExited(MouseEvent event) { hover = false; repaint(); }
					@Override public void mouseClicked(MouseEvent event) { action.run(); }
				});
			}

			@Override protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				try {
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					if (hover) {
						g.setColor("✕".equals(glyph) ? new Color(0xE8484F) : Theme.HOVER);
						g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
					}
				} finally {
					g.dispose();
				}
				super.paintComponent(graphics);
			}
		};
		button.setFont(Theme.sans(12, false));
		button.setForeground(Theme.DIM);
		button.setToolTipText(tooltip);
		button.setPreferredSize(new Dimension(30, 24));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return button;
	}

	private JComponent statusBar() {
		JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(Theme.TITLE);
		bar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
			BorderFactory.createEmptyBorder(6, 14, 6, 14)));
		statusLeft.setFont(Theme.mono(10));
		statusLeft.setForeground(Theme.DIM);
		statusRight.setFont(Theme.mono(10));
		statusRight.setForeground(Theme.FAINT);
		bar.add(statusLeft, BorderLayout.WEST);
		bar.add(statusRight, BorderLayout.EAST);
		return bar;
	}

	// ---- tabs ------------------------------------------------------------

	public void addTab(String id, Icons.Glyph glyph, String label, JComponent content) {
		RailButton button = new RailButton(glyph, label, () -> show(id));
		tabs.put(id, button);
		rail.add(button);
		rail.add(Box.createVerticalStrut(4));
		cards.add(content, id);
		if (current == null) show(id);
	}

	/** Fills the remaining rail space so the version label sits at the bottom. */
	public void railFooter(String text) {
		rail.add(Box.createVerticalGlue());
		JLabel version = new JLabel(text);
		version.setFont(Theme.mono(9));
		version.setForeground(Theme.FAINT);
		version.setAlignmentX(Component.CENTER_ALIGNMENT);
		rail.add(version);
	}

	public void show(String id) {
		if (!tabs.containsKey(id)) return;
		current = id;
		((CardLayout) cards.getLayout()).show(cards, id);
		tabs.forEach((key, button) -> button.setActive(key.equals(id)));
		revalidate();
		repaint();
	}

	public String currentTab() {
		return current;
	}

	public void status(String left, String right) {
		statusLeft.setText(left);
		statusRight.setText(right);
	}

	public void setStatusColour(Color colour) {
		statusLeft.setForeground(colour);
	}

	private void minimize() {
		setState(ICONIFIED);
	}

	private void toggleMaximise() {
		if (restoreBounds != null) {
			setBounds(restoreBounds);
			restoreBounds = null;
			return;
		}
		restoreBounds = getBounds();
		Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		setBounds(screen);
	}

	private void closeWindow() {
		dispatchEvent(new java.awt.event.WindowEvent(this, java.awt.event.WindowEvent.WINDOW_CLOSING));
	}

	/** One rail entry: glyph above a caption, crimson highlight when active. */
	private static final class RailButton extends JComponent {
		private final Icons.Glyph glyph;
		private final String label;
		private final Runnable action;
		private boolean active;
		private boolean hover;

		RailButton(Icons.Glyph glyph, String label, Runnable action) {
			this.glyph = glyph;
			this.label = label;
			this.action = action;
			setPreferredSize(new Dimension(80, 56));
			setMaximumSize(new Dimension(80, 56));
			setAlignmentX(Component.CENTER_ALIGNMENT);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter() {
				@Override public void mouseClicked(MouseEvent event) { action.run(); }
				@Override public void mouseEntered(MouseEvent event) { hover = true; repaint(); }
				@Override public void mouseExited(MouseEvent event) { hover = false; repaint(); }
			});
		}

		void setActive(boolean active) {
			this.active = active;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				if (active || hover) {
					g.setColor(active ? new Color(0x2A1317) : Theme.HOVER);
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
				}
				if (active) {
					g.setColor(Theme.ACCENT);
					g.fillRoundRect(0, 12, 3, getHeight() - 24, 3, 3);
				}
				Color icon = active ? Theme.ACCENT_SOFT : hover ? Theme.TEXT : Theme.DIM;
				Icons.paint(g, glyph, (getWidth() - 20) / 2, 10, 20, icon);
				g.setFont(Theme.sans(10, active));
				g.setColor(active ? Theme.ACCENT_SOFT : hover ? Theme.TEXT : Theme.DIM);
				int width = g.getFontMetrics().stringWidth(label);
				g.drawString(label, (getWidth() - width) / 2, 44);
			} finally {
				g.dispose();
			}
		}
	}

	/** Edge/corner resizing for an undecorated window. */
	private static final class WindowResizer {
		private final JFrame frame;
		private final int border;
		private int edge = -1;
		private Point origin;
		private Rectangle startBounds;

		WindowResizer(JFrame frame, int border) {
			this.frame = frame;
			this.border = border;
		}

		void install() {
			java.awt.Container pane = frame.getRootPane();
			pane.addMouseMotionListener(new MouseMotionAdapter() {
				@Override public void mouseMoved(MouseEvent event) {
					int next = edgeAt(event.getPoint());
					if (next != -1) {
						pane.setCursor(Cursor.getPredefinedCursor(cursorFor(next)));
					} else {
						pane.setCursor(Cursor.getDefaultCursor());
					}
				}

				@Override public void mouseDragged(MouseEvent event) {
					if (edge == -1 || startBounds == null) return;
					Point screen = event.getLocationOnScreen();
					int dx = screen.x - origin.x;
					int dy = screen.y - origin.y;
					int x = startBounds.x;
					int y = startBounds.y;
					int width = startBounds.width;
					int height = startBounds.height;
					if ((edge & 2) != 0) { x += dx; width -= dx; }      // left
					if ((edge & 4) != 0) { width += dx; }               // right
					if ((edge & 8) != 0) { y += dy; height -= dy; }     // top
					if ((edge & 16) != 0) { height += dy; }             // bottom
					Dimension minimum = frame.getMinimumSize();
					if (width >= minimum.width && height >= minimum.height) {
						frame.setBounds(x, y, width, height);
					}
				}
			});
			pane.addMouseListener(new MouseAdapter() {
				@Override public void mousePressed(MouseEvent event) {
					edge = edgeAt(event.getPoint());
					origin = event.getLocationOnScreen();
					startBounds = frame.getBounds();
				}

				@Override public void mouseReleased(MouseEvent event) {
					edge = -1;
					startBounds = null;
				}
			});
		}

		/** Bit mask: 2 left, 4 right, 8 top, 16 bottom. */
		private int edgeAt(Point point) {
			int width = frame.getWidth();
			int height = frame.getHeight();
			int mask = 0;
			if (point.x <= border) mask |= 2;
			if (point.x >= width - border) mask |= 4;
			if (point.y <= border) mask |= 8;
			if (point.y >= height - border) mask |= 16;
			return mask;
		}

		private int cursorFor(int mask) {
			return switch (mask) {
				case 2, 4 -> Cursor.E_RESIZE_CURSOR;
				case 8, 16 -> Cursor.N_RESIZE_CURSOR;
				case 2 | 8, 4 | 16 -> Cursor.NW_RESIZE_CURSOR;
				default -> Cursor.NE_RESIZE_CURSOR;
			};
		}
	}
}
