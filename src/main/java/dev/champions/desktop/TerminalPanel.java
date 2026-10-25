package dev.champions.desktop;

import dev.champions.core.console.ConsoleLine;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
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
 * bound as the in-game terminal, rendered natively with a real monospace font.
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
		statusBar.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
		add(statusBar, BorderLayout.NORTH);

		JPanel promptRow = new JPanel(new BorderLayout(8, 0));
		promptRow.setBackground(Theme.PANEL);
		promptRow.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
			BorderFactory.createEmptyBorder(8, 12, 8, 12)));

		promptLabel = new JLabel();
		promptLabel.setFont(Theme.monoBold(13));
		promptLabel.setForeground(Theme.GREEN);
		promptRow.add(promptLabel, BorderLayout.WEST);

		input = new JTextField();
		input.setFont(Theme.mono(13));
		input.setBackground(Theme.FIELD);
		input.setForeground(Theme.TEXT);
		input.setCaretColor(Theme.GREEN_BRIGHT);
		input.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Theme.BORDER),
			BorderFactory.createEmptyBorder(6, 9, 6, 9)));
		promptRow.add(input, BorderLayout.CENTER);

		JLabel hint = new JLabel("↵ run   ↑↓ history   ctrl+c copy");
		hint.setFont(Theme.mono(10));
		hint.setForeground(Theme.FAINT);
		promptRow.add(hint, BorderLayout.EAST);
		add(promptRow, BorderLayout.SOUTH);

		input.addActionListener(event -> submitInput());
		installHistoryKeys();
		installScrollKeys();

		timer = new Timer(100, event -> tick());
		timer.start();
		tick();
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
		bind(KeyEvent.VK_UP, 0, "history-prev", () -> {
			if (localHistory.isEmpty()) return;
			historyIndex = Math.max(0, historyIndex - 1);
			input.setText(localHistory.get(historyIndex));
		});
		bind(KeyEvent.VK_DOWN, 0, "history-next", () -> {
			if (localHistory.isEmpty()) return;
			historyIndex = Math.min(localHistory.size(), historyIndex + 1);
			input.setText(historyIndex >= localHistory.size() ? "" : localHistory.get(historyIndex));
		});
		bind(KeyEvent.VK_L, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(), "clear-view", output::clearView);
	}

	private void installScrollKeys() {
		bind(KeyEvent.VK_PAGE_UP, 0, "pgup", () -> output.scrollBy(-10));
		bind(KeyEvent.VK_PAGE_DOWN, 0, "pgdn", () -> output.scrollBy(10));
		bind(KeyEvent.VK_HOME, 0, "top", output::scrollToTop);
		bind(KeyEvent.VK_END, 0, "end", output::scrollToBottom);
	}

	private void bind(int keyCode, int modifiers, String name, Runnable action) {
		input.getInputMap().put(KeyStroke.getKeyStroke(keyCode, modifiers), name);
		input.getActionMap().put(name, new javax.swing.AbstractAction() {
			@Override public void actionPerformed(ActionEvent event) {
				action.run();
			}
		});
	}

	private void tick() {
		var buffer = port.buffer();
		if (buffer.revision() != seenRevision) {
			seenRevision = buffer.revision();
			output.setLines(buffer.snapshot());
		}
		String prompt = port.prompt() + " ❯";
		if (!prompt.equals(promptLabel.getText())) promptLabel.setText(prompt);
		ConsolePort.Status status = port.status();
		String text = " ● " + port.mode() + "   " + status.summary();
		String capabilities = port.capabilities();
		if (!capabilities.isBlank()) text += "   [" + capabilities + "]";
		if (text.length() > 170) text = text.substring(0, 167) + "...";
		if (!text.equals(statusBar.getText())) {
			statusBar.setText(text);
			statusBar.setForeground(status.connected() ? (status.authorized() ? Theme.GREEN : Theme.YELLOW) : Theme.DIM);
		}
	}

	public void focusInput() {
		SwingUtilities.invokeLater(input::requestFocusInWindow);
	}

	/** One wrapped visual row: a slice of a logical line plus its tone colour. */
	record Row(String text, Color color) {}

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
					selectionStart = rowAt(event.getY());
					selectionEnd = selectionStart;
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

		private int lineHeight() {
			FontMetrics metrics = getFontMetrics(getFont());
			return metrics.getHeight() + 3;
		}

		private int visibleRows() {
			return Math.max(1, (getHeight() - 16) / Math.max(1, lineHeight()));
		}

		private void reflow() {
			rows.clear();
			FontMetrics metrics = getFontMetrics(getFont());
			int width = Math.max(120, getWidth() - 28);
			for (ConsoleLine line : lines) {
				String text = line.text().isEmpty() ? " " : line.text();
				Color color = Theme.tone(line.tone());
				int start = 0;
				while (start < text.length()) {
					int count = 0;
					int lastSpace = -1;
					while (start + count < text.length()
							&& metrics.stringWidth(text.substring(start, start + count + 1)) <= width) {
						if (text.charAt(start + count) == ' ') lastSpace = count;
						count++;
					}
					if (count == 0) count = 1;
					// Break on the last space so wrapped text stays readable; leave the
					// space out of the row when it was only a wrap point.
					if (start + count < text.length() && lastSpace > 0) count = lastSpace + 1;
					rows.add(new Row(text.substring(start, start + count).stripTrailing(), color));
					start += count;
				}
			}
		}

		private int rowAt(int y) {
			int index = (y - 8) / Math.max(1, lineHeight());
			int top = rows.size() - visibleRows() - scrollOffset;
			return Math.max(0, Math.min(Math.max(0, rows.size() - 1), top + index));
		}

		private void copySelection() {
			StringBuilder out = new StringBuilder();
			if (selectionStart >= 0 && selectionEnd >= 0 && !rows.isEmpty()) {
				int from = Math.min(selectionStart, selectionEnd);
				int to = Math.max(selectionStart, selectionEnd);
				for (int index = from; index <= to && index < rows.size(); index++) {
					out.append(rows.get(index).text()).append('\n');
				}
			} else {
				for (ConsoleLine line : lines) out.append(line.text()).append('\n');
			}
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(out.toString()), null);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
				g.setColor(Theme.BACKGROUND);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setFont(getFont());
				FontMetrics metrics = getFontMetrics(getFont());
				int lineHeight = lineHeight();
				int first = Math.max(0, rows.size() - visibleRows() - scrollOffset);
				int y = 8 + metrics.getAscent();
				for (int index = first; index < rows.size() && y < getHeight(); index++) {
					Row row = rows.get(index);
					boolean selected = selectionStart >= 0 && selectionEnd >= 0
						&& index >= Math.min(selectionStart, selectionEnd)
						&& index <= Math.max(selectionStart, selectionEnd);
					if (selected) {
						g.setColor(Theme.SELECTION);
						g.fillRect(0, y - metrics.getAscent(), getWidth(), lineHeight);
					}
					g.setColor(row.color());
					g.drawString(row.text(), 14, y);
					y += lineHeight;
				}
				if (rows.isEmpty()) {
					g.setColor(Theme.DIM);
					g.drawString("Welcome to ChampionsCode. Type 'help' for the command list.", 14, 8 + metrics.getAscent());
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
