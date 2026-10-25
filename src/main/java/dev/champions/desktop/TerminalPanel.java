package dev.champions.desktop;

import dev.champions.core.console.ConsoleLine;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The external msfconsole: the same prompt, tones, history and 1 000-line
 * bound as the in-game terminal, rendered natively with a monospaced font.
 */
public final class TerminalPanel extends JPanel {
	private final ConsolePort port;
	private final OutputArea output;
	private final JTextField input;
	private final JLabel statusBar;
	private final JLabel promptLabel;
	private final List<String> localHistory = new ArrayList<>();
	private int historyIndex;
	private long seenRevision = -1L;
	private final Timer timer;

	public TerminalPanel(ConsolePort port) {
		super(new BorderLayout());
		this.port = port;
		setBackground(Theme.BACKGROUND);

		output = new OutputArea();
		add(output, BorderLayout.CENTER);

		statusBar = new JLabel();
		statusBar.setOpaque(true);
		statusBar.setBackground(Theme.TITLE);
		statusBar.setForeground(Theme.DIM);
		statusBar.setFont(Theme.mono(11));
		statusBar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
		add(statusBar, BorderLayout.NORTH);

		JPanel promptRow = new JPanel(new BorderLayout(6, 0));
		promptRow.setBackground(Theme.BACKGROUND);
		promptRow.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

		promptLabel = new JLabel();
		promptLabel.setFont(Theme.monoBold(13));
		promptLabel.setForeground(Theme.GREEN);
		promptRow.add(promptLabel, BorderLayout.WEST);

		input = new JTextField();
		input.setFont(Theme.mono(13));
		input.setBackground(Theme.FIELD);
		input.setForeground(Theme.TEXT);
		input.setCaretColor(Theme.GREEN);
		input.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Theme.BORDER),
			BorderFactory.createEmptyBorder(5, 7, 5, 7)));
		promptRow.add(input, BorderLayout.CENTER);
		promptRow.add(hint(), BorderLayout.EAST);
		add(promptRow, BorderLayout.SOUTH);

		input.addActionListener(event -> submitInput());
		installHistoryKeys();
		installScrollKeys();

		timer = new Timer(100, event -> tick());
		timer.start();
		tick();
	}

	private JLabel hint() {
		JLabel label = new JLabel("↵ run   ↑↓ history   ctrl+c copy");
		label.setFont(Theme.mono(10));
		label.setForeground(new Color(0x6A6A6A));
		label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
		return label;
	}

	private void submitInput() {
		String command = input.getText().strip();
		input.setText("");
		if (command.isEmpty()) return;
		localHistory.add(command);
		historyIndex = localHistory.size();
		port.submit(command);
		output.scrollToBottom();
	}

	private void installHistoryKeys() {
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "history-prev");
		input.getActionMap().put("history-prev", new javax.swing.AbstractAction() {
			@Override public void actionPerformed(ActionEvent event) {
				if (localHistory.isEmpty()) return;
				historyIndex = Math.max(0, historyIndex - 1);
				input.setText(localHistory.get(historyIndex));
			}
		});
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "history-next");
		input.getActionMap().put("history-next", new javax.swing.AbstractAction() {
			@Override public void actionPerformed(ActionEvent event) {
				if (localHistory.isEmpty()) return;
				historyIndex = Math.min(localHistory.size(), historyIndex + 1);
				input.setText(historyIndex >= localHistory.size() ? "" : localHistory.get(historyIndex));
			}
		});
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_L, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "clear-view");
		input.getActionMap().put("clear-view", new javax.swing.AbstractAction() {
			@Override public void actionPerformed(ActionEvent event) {
				output.clearView();
			}
		});
	}

	private void installScrollKeys() {
		bindScroll(KeyEvent.VK_PAGE_UP, -10);
		bindScroll(KeyEvent.VK_PAGE_DOWN, 10);
		bindScroll(KeyEvent.VK_HOME, Integer.MIN_VALUE);
		bindScroll(KeyEvent.VK_END, Integer.MAX_VALUE);
	}

	private void bindScroll(int keyCode, int delta) {
		String name = "scroll-" + keyCode;
		input.getInputMap().put(KeyStroke.getKeyStroke(keyCode, 0), name);
		input.getActionMap().put(name, new javax.swing.AbstractAction() {
			@Override public void actionPerformed(ActionEvent event) {
				if (delta == Integer.MIN_VALUE) output.scrollToTop();
				else if (delta == Integer.MAX_VALUE) output.scrollToBottom();
				else output.scrollBy(delta);
			}
		});
	}

	private void tick() {
		var buffer = port.buffer();
		if (buffer.revision() != seenRevision) {
			seenRevision = buffer.revision();
			output.setLines(buffer.snapshot());
		}
		String prompt = port.prompt() + ":~$";
		if (!prompt.equals(promptLabel.getText())) promptLabel.setText(prompt);
		ConsolePort.Status status = port.status();
		String text = " " + port.mode() + "  " + status.summary();
		String capabilities = port.capabilities();
		if (!capabilities.isBlank()) text += "   [" + capabilities + "]";
		// Keep the status bar readable in a narrow window: the capability detail is
		// available in full from the console 'status' command.
		if (text.length() > 150) text = text.substring(0, 147) + "...";
		if (!text.equals(statusBar.getText())) {
			statusBar.setText(text);
			statusBar.setForeground(status.connected() ? (status.authorized() ? Theme.GREEN : Theme.YELLOW) : Theme.DIM);
		}
	}

	public void focusInput() {
		SwingUtilities.invokeLater(input::requestFocusInWindow);
	}

	/** One wrapped visual row: a slice of a logical line plus its tone colour. */
	record Row(String text, Color color, int logicalIndex) {}

	private final class OutputArea extends JComponent {
		private final List<ConsoleLine> lines = new ArrayList<>();
		private final List<Row> rows = new ArrayList<>();
		private int scrollOffset;
		private int selectionStart = -1;
		private int selectionEnd = -1;

		OutputArea() {
			setBackground(Theme.BACKGROUND);
			setFont(Theme.mono(13));
			setFocusable(true);
			MouseAdapter mouse = new MouseAdapter() {
				@Override public void mousePressed(MouseEvent event) {
					int row = rowAt(event.getY());
					selectionStart = row;
					selectionEnd = row;
					repaint();
				}

				@Override public void mouseDragged(MouseEvent event) {
					selectionEnd = rowAt(event.getY());
					repaint();
				}

				@Override public void mouseWheelMoved(MouseWheelEvent event) {
					scrollBy(event.getWheelRotation() * 3);
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
			addMouseWheelListener(mouse);
			getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C,
				Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "copy");
			getActionMap().put("copy", new javax.swing.AbstractAction() {
				@Override public void actionPerformed(ActionEvent event) {
					copySelection();
				}
			});
			setPreferredSize(new Dimension(900, 560));
		}

		void setLines(List<ConsoleLine> next) {
			boolean atBottom = scrollOffset == 0;
			lines.clear();
			lines.addAll(next);
			reflow();
			if (atBottom) scrollOffset = 0;
			repaint();
		}

		void clearView() {
			lines.clear();
			rows.clear();
			scrollOffset = 0;
			repaint();
		}

		void scrollBy(int delta) {
			int max = Math.max(0, rows.size() - visibleRows());
			scrollOffset = Math.max(0, Math.min(max, scrollOffset - delta));
			repaint();
		}

		void scrollToBottom() { scrollOffset = 0; repaint(); }
		void scrollToTop() { scrollOffset = Math.max(0, rows.size() - visibleRows()); repaint(); }

		private int visibleRows() {
			FontMetrics metrics = getFontMetrics(getFont());
			return Math.max(1, (getHeight() - 12) / metrics.getHeight());
		}

		private void reflow() {
			rows.clear();
			FontMetrics metrics = getFontMetrics(getFont());
			int width = Math.max(120, getWidth() - 24);
			for (int index = 0; index < lines.size(); index++) {
				ConsoleLine line = lines.get(index);
				String text = line.text().isEmpty() ? " " : line.text();
				Color color = Theme.tone(line.tone());
				StringBuilder current = new StringBuilder();
				for (int i = 0; i < text.length(); i++) {
					current.append(text.charAt(i));
					if (metrics.stringWidth(current.toString()) > width) {
						rows.add(new Row(current.toString(), color, index));
						current.setLength(0);
					}
				}
				rows.add(new Row(current.toString(), color, index));
			}
		}

		private int rowAt(int y) {
			FontMetrics metrics = getFontMetrics(getFont());
			int index = (y - 6) / Math.max(1, metrics.getHeight());
			int top = rows.size() - visibleRows() - scrollOffset;
			return Math.max(0, Math.min(rows.size() - 1, top + index));
		}

		private void copySelection() {
			StringBuilder out = new StringBuilder();
			if (selectionStart >= 0 && selectionEnd >= 0) {
				int from = Math.min(selectionStart, selectionEnd);
				int to = Math.max(selectionStart, selectionEnd);
				for (int i = from; i <= to && i < rows.size(); i++) out.append(rows.get(i).text()).append('\n');
			} else {
				for (ConsoleLine line : lines) out.append(line.text()).append('\n');
			}
			Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(out.toString()), null);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g.setColor(Theme.BACKGROUND);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setFont(getFont());
				FontMetrics metrics = getFontMetrics(getFont());
				int lineHeight = metrics.getHeight();
				int first = Math.max(0, rows.size() - visibleRows() - scrollOffset);
				int y = 6 + metrics.getAscent();
				for (int index = first; index < rows.size() && y < getHeight(); index++) {
					Row row = rows.get(index);
					boolean selected = selectionStart >= 0 && selectionEnd >= 0
						&& index >= Math.min(selectionStart, selectionEnd) && index <= Math.max(selectionStart, selectionEnd);
					if (selected) {
						g.setColor(new Color(0x2C3E5A));
						g.fillRect(0, y - metrics.getAscent(), getWidth(), lineHeight);
					}
					g.setColor(row.color());
					g.drawString(row.text(), 12, y);
					y += lineHeight;
				}
				if (rows.isEmpty()) {
					g.setColor(Theme.DIM);
					g.drawString("Welcome to ChampionsCode. Type 'help' for the command list.", 12, 6 + metrics.getAscent());
				}
			} finally {
				g.dispose();
			}
		}
	}

	/** Exposed for the demo harness and tests. */
	JTextField inputField() {
		return input;
	}
}
