package dev.champions.desktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Interface settings for the desktop app: which front ends open, and how.
 *
 * <p>When the app is attached to a running game, changes are written to
 * {@code desktop.properties} and applied by the game through
 * {@code interfaces reload}, so the in-game UI and the app never disagree. In
 * standalone mode the app edits the file directly; the game picks it up on its
 * next start (or earlier if you run {@code /interfaces reload}).</p>
 */
public final class SettingsPanel extends JPanel {
	private static final List<String> UI_MODES = List.of("both", "external", "in-game");
	private static final List<String> CLICKGUI_MODES = List.of("clickgui", "categorygui");

	private final ConsolePort port;
	private final Path configFile;
	/** Switch rows: one fixed row per setting, each as wide as the card. */
	private final JPanel rows = new JPanel();
	private final JLabel note = new JLabel(" ");

	public SettingsPanel(ConsolePort port, Path configDirectory) {
		super(new BorderLayout());
		this.port = port;
		this.configFile = configDirectory.resolve("desktop.properties");
		setOpaque(false);

		rows.setLayout(new java.awt.GridBagLayout());
		rows.setOpaque(false);
		rows.setBorder(BorderFactory.createEmptyBorder(6, 0, 8, 0));

		JPanel card = Theme.card(new BorderLayout());
		card.add(Theme.header("Interfaces", "Where ChampionsCode opens — in-game windows, the external app, or both"),
			BorderLayout.NORTH);
		card.add(rows, BorderLayout.CENTER);

		note.setFont(Theme.mono(10));
		note.setForeground(Theme.FAINT);
		note.setBorder(Theme.padding(4, 12, 10, 16));

		JPanel footer = new JPanel(new BorderLayout());
		footer.setOpaque(false);
		footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
		footer.add(actions(), BorderLayout.WEST);
		footer.add(note, BorderLayout.CENTER);
		card.add(footer, BorderLayout.SOUTH);

		// Both cards size to their content and stack from the top, so the page
		// does not turn into one tall box of empty space on a large screen.
		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		column.add(card);
		column.add(Box.createVerticalStrut(12));
		JPanel files = filesCard(configDirectory);
		files.setAlignmentX(Component.LEFT_ALIGNMENT);
		column.add(files);
		column.add(Box.createVerticalGlue());

		JPanel stacked = new JPanel(new BorderLayout(0, 12));
		stacked.setOpaque(false);
		stacked.add(Theme.header("Settings", "interface placement, startup behaviour and files"), BorderLayout.NORTH);
		stacked.add(column, BorderLayout.CENTER);
		add(stacked, BorderLayout.CENTER);

		refresh();
	}

	private void refresh() {
		Properties properties = read();
		String ui = properties.getProperty("ui", "both").strip().toLowerCase(java.util.Locale.ROOT);
		boolean spawn = Boolean.parseBoolean(properties.getProperty("spawnOnOpen", "true"));
		String clickGui = properties.getProperty("clickgui", "clickgui").strip().toLowerCase(java.util.Locale.ROOT);

		rows.removeAll();
		addRow(segmented("Default interface",
			"Decides what F8, F7 and the clickgui/logs commands open.",
			UI_MODES, ui, value -> write("ui", value)));
		addRow(hint("both = in-game windows and the app · external = app only · in-game = the 0.1.x workspace only"));
		addRow(segmented("ClickGUI style",
			"Which in-game interface the clickgui command opens.",
			CLICKGUI_MODES, clickGui, value -> write("clickgui", value)));
		addRow(hint("clickgui = sorted module list · categorygui = movable category panels"));
		addRow(toggle("Start the desktop app automatically",
			"When a UI is opened and no app is attached, the game launches it.",
			spawn, value -> write("spawnOnOpen", Boolean.toString(value))));
		rows.revalidate();
		rows.repaint();
	}

	private JPanel filesCard(Path configDirectory) {
		JPanel card = Theme.card(new BorderLayout());
		card.setBorder(Theme.padding(0, 0, 12, 0));
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.setBorder(Theme.padding(14, 18, 14, 18));

		JLabel title = new JLabel("Files and in-game control");
		title.setFont(Theme.sans(13, true));
		title.setForeground(Theme.TEXT);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(title);
		body.add(Box.createVerticalStrut(8));

		body.add(fileRow("settings", configFile.toString()));
		body.add(fileRow("modules", configDirectory.resolve("modules").toString()));
		body.add(fileRow("config", configDirectory.toString()));
		body.add(Box.createVerticalStrut(10));

		JLabel chat = new JLabel("<html>In game, the same switches are one chat command away: "
			+ "<span style='font-family:monospace;color:#E8484F'>interfaces both</span>, "
			+ "<span style='font-family:monospace;color:#E8484F'>interfaces external</span> or "
			+ "<span style='font-family:monospace;color:#E8484F'>interfaces in-game</span>. "
			+ "Running <span style='font-family:monospace;color:#E8484F'>interfaces</span> with no argument prints the current mode.</html>");
		chat.setFont(Theme.sans(12, false));
		chat.setForeground(Theme.DIM);
		chat.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(chat);

		card.add(body, BorderLayout.CENTER);
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setBorder(Theme.padding(0, 0, 0, 0));
		holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
		holder.add(card, BorderLayout.CENTER);
		return holder;
	}

	private JPanel fileRow(String label, String value) {
		JPanel row = new JPanel(new BorderLayout(12, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel key = new JLabel(label);
		key.setFont(Theme.mono(11));
		key.setForeground(Theme.FAINT);
		key.setPreferredSize(new Dimension(64, 18));
		JLabel path = new JLabel(value);
		path.setFont(Theme.mono(11));
		path.setForeground(Theme.DIM);
		row.add(key, BorderLayout.WEST);
		row.add(path, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		return row;
	}

	/** Adds one switch row, stacked below the previous one at its natural height. */
	private void addRow(JPanel row) {
		java.awt.GridBagConstraints constraints = new java.awt.GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = rows.getComponentCount();
		constraints.weightx = 1;
		constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
		constraints.anchor = java.awt.GridBagConstraints.NORTH;
		rows.add(row, constraints);
	}

	private JPanel segmented(String title, String description, List<String> options, String current,
			java.util.function.Consumer<String> onSelect) {
		JPanel row = row(title, description);
		JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		group.setOpaque(false);
		for (String option : options) {
			boolean selected = option.equalsIgnoreCase(current);
			java.awt.event.ActionListener choose = event -> {
				onSelect.accept(option);
				refresh();
			};
			group.add(selected ? Theme.filled(option, choose) : Theme.button(option, Theme.DIM, choose));
		}
		row.add(group, BorderLayout.EAST);
		return row;
	}

	private JPanel toggle(String title, String description, boolean value, java.util.function.Consumer<Boolean> onChange) {
		JPanel row = row(title, description);
		JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		group.setOpaque(false);
		java.awt.event.ActionListener flip = event -> {
			onChange.accept(!value);
			refresh();
		};
		group.add(value ? Theme.filled("On", flip) : Theme.button("Off", Theme.DIM, flip));
		row.add(group, BorderLayout.EAST);
		return row;
	}

	private JPanel actions() {
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		row.setOpaque(false);
		row.setBorder(Theme.padding(0, 16, 4, 16));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(Theme.primary("Apply in game", event -> applyInGame()));
		row.add(Theme.button("Reload from disk", Theme.DIM, event -> {
			refresh();
			note.setForeground(Theme.DIM);
			note.setText("Re-read " + configFile);
		}));
		row.add(Theme.button("Open file location", Theme.DIM, event -> {
			note.setForeground(Theme.FAINT);
			note.setText(configFile.toString());
		}));
		return row;
	}

	private void applyInGame() {
		boolean attached = port instanceof RemoteConsolePort;
		if (attached) {
			port.submit("interfaces reload");
			note.setForeground(Theme.GREEN);
			note.setText("Sent 'interfaces reload' to the game — the in-game setting is now the source of truth.");
		} else {
			note.setForeground(Theme.YELLOW);
			note.setText("No game attached. The file is saved; run /interfaces reload in game (or restart) to apply.");
		}
	}

	private JPanel row(String title, String description) {
		JPanel row = new JPanel(new BorderLayout(16, 0));
		row.setOpaque(false);
		row.setBorder(Theme.padding(5, 16, 5, 16));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel text = new JPanel(new BorderLayout(0, 2));
		text.setOpaque(false);
		JLabel name = new JLabel(title);
		name.setFont(Theme.sans(12, true));
		name.setForeground(Theme.TEXT);
		JLabel descriptionLabel = new JLabel(description);
		descriptionLabel.setFont(Theme.mono(10));
		descriptionLabel.setForeground(Theme.FAINT);
		text.add(name, BorderLayout.NORTH);
		text.add(descriptionLabel, BorderLayout.CENTER);
		row.add(text, BorderLayout.CENTER);
		return row;
	}

	private JPanel hint(String text) {
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(Theme.padding(0, 16, 8, 16));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setOpaque(false);
		area.setLineWrap(true);
		area.setFont(Theme.mono(9));
		area.setForeground(Theme.FAINT);
		row.add(area, BorderLayout.CENTER);
		return row;
	}

	// ---- file io ---------------------------------------------------------

	private Properties read() {
		Properties properties = new Properties();
		if (!Files.exists(configFile)) return properties;
		try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException ignored) {
			// keep defaults
		}
		return properties;
	}

	/**
	 * Writes one key, preserving the comments in the file so the documentation
	 * the game generated is not lost.
	 */
	private void write(String key, String value) {
		try {
			Files.createDirectories(configFile.getParent());
			List<String> lines = Files.exists(configFile)
				? new java.util.ArrayList<>(Files.readAllLines(configFile, StandardCharsets.UTF_8))
				: new java.util.ArrayList<>();
			boolean replaced = false;
			for (int index = 0; index < lines.size(); index++) {
				if (lines.get(index).stripLeading().startsWith(key + "=")) {
					lines.set(index, key + "=" + value);
					replaced = true;
					break;
				}
			}
			if (!replaced) lines.add(key + "=" + value);
			Files.write(configFile, lines, StandardCharsets.UTF_8);
			note.setForeground(Theme.GREEN);
			note.setText("Saved " + key + "=" + value + " to " + configFile.getFileName());
		} catch (IOException error) {
			note.setForeground(Theme.RED);
			note.setText("Could not write " + configFile + ": " + error.getMessage());
		}
	}
}
