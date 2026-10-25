package dev.champions.desktop;

import dev.champions.core.console.ConsoleLine;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External ClickGUI: the same module tree, option editors, dropdowns and run
 * controls the in-game sorted-list interface shows, driven through the same
 * {@code *FromUi} facade on the console.
 */
public final class ModulesPanel extends JPanel {
	private final ConsolePort port;
	private final JTextField search = new JTextField();
	private final DefaultListModel<String> listModel = new DefaultListModel<>();
	private final JList<String> list = new JList<>(listModel);
	private final JPanel details = new JPanel(new BorderLayout());
	private final JLabel statusLabel = new JLabel(" ");
	private final Map<String, ModuleItem> visible = new LinkedHashMap<>();
	private final Map<String, Component> editors = new LinkedHashMap<>();
	private List<ConsolePort.ModuleInfo> modules = List.of();
	private String activeId;
	private String renderedActiveId;
	private final Timer timer;

	record ModuleItem(ConsolePort.ModuleInfo info) {
		@Override public String toString() {
			return info.id();
		}
	}

	public ModulesPanel(ConsolePort port) {
		super(new BorderLayout());
		this.port = port;
		setBackground(Theme.BACKGROUND);

		JPanel left = new JPanel(new BorderLayout(0, 6));
		left.setBackground(Theme.PANEL);
		left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		search.setFont(Theme.mono(12));
		search.setForeground(Theme.DIM);
		search.setText("search modules");
		search.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override public void focusGained(java.awt.event.FocusEvent event) {
				if (search.getText().equals("search modules")) {
					search.setText("");
					search.setForeground(Theme.TEXT);
				}
			}

			@Override public void focusLost(java.awt.event.FocusEvent event) {
				if (search.getText().isBlank()) {
					search.setForeground(Theme.DIM);
					search.setText("search modules");
				}
			}
		});
		search.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Theme.BORDER), BorderFactory.createEmptyBorder(5, 7, 5, 7)));
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override public void insertUpdate(javax.swing.event.DocumentEvent event) { refreshList(); }
			@Override public void removeUpdate(javax.swing.event.DocumentEvent event) { refreshList(); }
			@Override public void changedUpdate(javax.swing.event.DocumentEvent event) { refreshList(); }
		});
		left.add(search, BorderLayout.NORTH);

		list.setFont(Theme.mono(12));
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new ModuleRenderer());
		list.addListSelectionListener(event -> {
			if (event.getValueIsAdjusting()) return;
			ModuleItem item = list.getSelectedValue() == null ? null : visible.get(list.getSelectedValue());
			if (item == null) return;
			if (!item.info().id().equals(activeId)) port.selectModule(item.info().id());
		});
		JScrollPane listScroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		listScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		left.add(listScroll, BorderLayout.CENTER);
		statusLabel.setFont(Theme.mono(10));
		statusLabel.setForeground(Theme.DIM);
		left.add(statusLabel, BorderLayout.SOUTH);
		left.setPreferredSize(new Dimension(300, 600));
		add(left, BorderLayout.WEST);

		details.setBackground(Theme.BACKGROUND);
		add(details, BorderLayout.CENTER);

		timer = new Timer(250, event -> tick());
		timer.start();
		tick();
	}

	private void tick() {
		List<ConsolePort.ModuleInfo> next = port.modules();
		if (!sameModules(next, modules)) {
			modules = next;
			refreshList();
			statusLabel.setText(modules.size() + " modules · " + port.mode());
		}
		ConsolePort.ActiveInfo active = port.active();
		if (active == null) {
			activeId = null;
			if (renderedActiveId != null) {
				renderedActiveId = null;
				showPlaceholder();
			}
			return;
		}
		activeId = active.id();
		if (!active.id().equals(renderedActiveId)) {
			renderedActiveId = active.id();
			buildDetails(active);
		} else {
			updateValues(active);
		}
	}

	private void showPlaceholder() {
		details.removeAll();
		JLabel label = new JLabel("Select a module. Console commands work too: use <module>");
		label.setFont(Theme.mono(12));
		label.setForeground(Theme.DIM);
		label.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
		details.add(label, BorderLayout.NORTH);
		details.revalidate();
		details.repaint();
	}

	private void buildDetails(ConsolePort.ActiveInfo active) {
		details.removeAll();
		editors.clear();

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(Theme.BACKGROUND);
		header.setBorder(BorderFactory.createEmptyBorder(10, 14, 6, 14));

		JLabel title = new JLabel(active.id());
		title.setFont(Theme.monoBold(14));
		title.setForeground(active.exploit() ? Theme.RED : Theme.BLUE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(title);

		javax.swing.JTextArea description = new javax.swing.JTextArea(active.description());
		description.setFont(Theme.sans(12, false));
		description.setForeground(Theme.DIM);
		description.setBackground(Theme.BACKGROUND);
		description.setEditable(false);
		description.setFocusable(false);
		description.setLineWrap(true);
		description.setWrapStyleWord(true);
		description.setBorder(null);
		description.setAlignmentX(Component.LEFT_ALIGNMENT);
		description.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
		header.add(Box.createVerticalStrut(4));
		header.add(description);
		if (active.exploit()) {
			JLabel badge = new JLabel("EXPLOIT RESEARCH · per-run legal warning required");
			badge.setFont(Theme.monoBold(10));
			badge.setForeground(Theme.RED);
			badge.setAlignmentX(Component.LEFT_ALIGNMENT);
			header.add(Box.createVerticalStrut(6));
			header.add(badge);
		}
		header.add(Box.createVerticalStrut(10));
		details.add(header, BorderLayout.NORTH);

		JPanel options = new JPanel();
		options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
		options.setBackground(Theme.BACKGROUND);
		options.setBorder(BorderFactory.createEmptyBorder(0, 14, 8, 14));
		for (ConsolePort.OptionInfo option : active.options()) {
			options.add(optionRow(option));
			options.add(Box.createVerticalStrut(6));
		}
		options.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 14));
		JScrollPane optionScroll = new JScrollPane(options,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		optionScroll.setBorder(BorderFactory.createEmptyBorder());
		optionScroll.getViewport().setBackground(Theme.BACKGROUND);
		details.add(optionScroll, BorderLayout.CENTER);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
		actions.setBackground(Theme.TITLE);
		JButton run = new JButton("Run");
		run.setFont(Theme.monoBold(12));
		run.setForeground(Theme.GREEN);
		run.setBackground(new Color(0x1F3322));
		run.addActionListener(event -> port.run());
		JButton clear = new JButton("Back");
		clear.setFont(Theme.mono(12));
		clear.addActionListener(event -> port.clearSelection());
		JButton authorize = new JButton();
		authorize.setFont(Theme.mono(12));
		ConsolePort.Status status = port.status();
		authorize.setText(status.authorized() ? "Deauthorize" : "Authorize");
		authorize.setEnabled(status.authorizeAvailable());
		authorize.addActionListener(event -> port.setAuthorized(!port.status().authorized()));
		actions.add(run);
		actions.add(clear);
		actions.add(authorize);
		run.setToolTipText("Runs the selected module. CONFIRM is consumed on every run, allowed or denied.");
		details.add(actions, BorderLayout.SOUTH);

		details.revalidate();
		details.repaint();
	}

	private JPanel optionRow(ConsolePort.OptionInfo option) {
		JPanel row = new JPanel(new java.awt.GridBagLayout());
		row.setBackground(Theme.BACKGROUND);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));

		JPanel labels = new JPanel(new GridLayout(2, 1));
		labels.setBackground(Theme.BACKGROUND);
		labels.setPreferredSize(new Dimension(180, 44));
		String name = option.name() + (option.required() ? "  *" : "");
		JLabel nameLabel = new JLabel(name);
		nameLabel.setFont(Theme.monoBold(12));
		nameLabel.setForeground(option.required() ? Theme.YELLOW : Theme.TEXT);
		JLabel description = new JLabel(option.description());
		description.setFont(Theme.sans(11, false));
		description.setForeground(Theme.DIM);
		labels.add(nameLabel);
		labels.add(description);
		java.awt.GridBagConstraints labelConstraints = new java.awt.GridBagConstraints();
		labelConstraints.gridx = 0;
		labelConstraints.weightx = 0;
		labelConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
		labelConstraints.insets = new java.awt.Insets(0, 0, 0, 10);
		row.add(labels, labelConstraints);

		Component editor;
		if (!option.choices().isEmpty()) {
			JComboBox<String> combo = new JComboBox<>(option.choices().toArray(new String[0]));
			combo.setFont(Theme.mono(12));
			combo.setSelectedItem(option.displayValue());
			combo.addActionListener(event -> {
				Object selected = combo.getSelectedItem();
				if (selected != null) port.setOption(option.name(), String.valueOf(selected));
			});
			editor = combo;
			combo.setPreferredSize(new Dimension(10, 26));
		} else {
			JTextField field = new JTextField();
			field.setText(option.displayValue());
			field.setFont(Theme.mono(12));
			if (option.secret()) {
				field.setForeground(option.configured() ? Theme.GREEN : Theme.DIM);
				field.setToolTipText("Secret: redacted in echo, history and the audit log.");
			}
			field.addActionListener(event -> port.setOption(option.name(), field.getText()));
			field.addFocusListener(new java.awt.event.FocusAdapter() {
				@Override public void focusLost(java.awt.event.FocusEvent event) {
					port.setOption(option.name(), field.getText());
				}
			});
			editor = field;
		}
		editors.put(option.name(), editor);
		java.awt.GridBagConstraints editorConstraints = new java.awt.GridBagConstraints();
		editorConstraints.gridx = 1;
		editorConstraints.weightx = 1;
		editorConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
		row.add(editor, editorConstraints);
		return row;
	}

	private void updateValues(ConsolePort.ActiveInfo active) {
		for (ConsolePort.OptionInfo option : active.options()) {
			Component editor = editors.get(option.name());
			if (editor == null) continue;
			if (editor instanceof JTextField field) {
				if (field.isFocusOwner()) continue;
				if (!field.getText().equals(option.displayValue())) field.setText(option.displayValue());
			} else if (editor instanceof JComboBox<?> combo) {
				if (combo.isFocusOwner()) continue;
				Object value = option.displayValue();
				if (!value.equals(combo.getSelectedItem())) combo.setSelectedItem(value);
			}
		}
	}

	private void refreshList() {
		String query = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
		if (query.equals("search modules")) query = "";
		String previous = list.getSelectedValue();
		visible.clear();
		listModel.clear();
		for (ConsolePort.ModuleInfo info : modules) {
			if (!query.isEmpty() && !info.id().toLowerCase(java.util.Locale.ROOT).contains(query)
				&& !info.name().toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
			ModuleItem item = new ModuleItem(info);
			visible.put(info.id(), item);
			listModel.addElement(info.id());
		}
		if (previous != null && visible.containsKey(previous)) list.setSelectedValue(previous, true);
		else if (activeId != null && visible.containsKey(activeId)) list.setSelectedValue(activeId, true);
	}

	private static boolean sameModules(List<ConsolePort.ModuleInfo> left, List<ConsolePort.ModuleInfo> right) {
		if (left.size() != right.size()) return false;
		for (int index = 0; index < left.size(); index++) {
			ConsolePort.ModuleInfo a = left.get(index);
			ConsolePort.ModuleInfo b = right.get(index);
			if (!a.id().equals(b.id()) || a.selected() != b.selected()) return false;
		}
		return true;
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Row renderer: exploit modules are red, the selected module is highlighted. */
	private final class ModuleRenderer extends javax.swing.DefaultListCellRenderer {
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean focused) {
			JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
			ModuleItem item = visible.get(String.valueOf(value));
			label.setFont(Theme.mono(12));
			label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
			label.setBackground(selected ? new Color(0x2C3E5A) : Theme.PANEL);
			label.setText("<html><span style='color:#"
				+ (item != null && item.info().exploit() ? "FF6868" : item != null && item.info().selected() ? "55D65A" : "F0F0F0")
				+ "'>" + escape(String.valueOf(value)) + "</span></html>");
			return label;
		}
	}
}
