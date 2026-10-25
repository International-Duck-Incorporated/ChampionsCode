package dev.champions.desktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * External ClickGUI: the module tree, option editors, dropdowns and run
 * controls the in-game sorted-list interface shows, driven through the same
 * {@code *FromUi} facade on the console.
 */
public final class ModulesPanel extends JPanel {
	private static final String SEARCH_HINT = "search modules";

	private final ConsolePort port;
	private final JTextField search = new JTextField();
	private final JPanel categories = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
	private final DefaultListModel<String> listModel = new DefaultListModel<>();
	private final JList<String> list = new JList<>(listModel);
	private final JPanel details = new JPanel(new BorderLayout());
	private final JLabel statusLabel = new JLabel(" ");
	private final Map<String, ConsolePort.ModuleInfo> visible = new LinkedHashMap<>();
	private final Map<String, JComponent> editors = new LinkedHashMap<>();

	private List<ConsolePort.ModuleInfo> modules = List.of();
	private String category = "all";
	private String activeId;
	private String renderedActiveId;
	private final Timer timer;

	public ModulesPanel(ConsolePort port) {
		super(new BorderLayout());
		this.port = port;
		setBackground(Theme.BACKGROUND);

		details.setBackground(Theme.BACKGROUND);
		// Resizable so a long module id or a wide option field is one drag away.
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSidebar(), details);
		split.setBorder(BorderFactory.createEmptyBorder());
		split.setContinuousLayout(true);
		split.setResizeWeight(0.0);
		add(split, BorderLayout.CENTER);
		// Proportional divider: on a narrow window the list gives room to the
		// option editors instead of squeezing them off the edge.
		addComponentListener(new java.awt.event.ComponentAdapter() {
			private boolean placed;

			@Override public void componentResized(java.awt.event.ComponentEvent event) {
				if (placed || getWidth() < 200) return;
				placed = true;
				split.setDividerLocation(Math.clamp(Math.round(getWidth() * 0.40f), 200, 330));
			}
		});

		timer = new Timer(250, event -> tick());
		timer.start();
		showPlaceholder();
		tick();
	}

	private JPanel buildSidebar() {
		JPanel sidebar = new JPanel(new BorderLayout(0, 8));
		sidebar.setBackground(Theme.PANEL);
		sidebar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER),
			BorderFactory.createEmptyBorder(10, 10, 10, 10)));
		sidebar.setPreferredSize(new Dimension(262, 600));

		search.setFont(Theme.mono(12));
		search.setForeground(Theme.DIM);
		search.setText(SEARCH_HINT);
		search.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Theme.BORDER),
			BorderFactory.createEmptyBorder(6, 9, 6, 9)));
		search.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override public void focusGained(java.awt.event.FocusEvent event) {
				if (search.getText().equals(SEARCH_HINT)) {
					search.setText("");
					search.setForeground(Theme.TEXT);
				}
			}

			@Override public void focusLost(java.awt.event.FocusEvent event) {
				if (search.getText().isBlank()) {
					search.setForeground(Theme.DIM);
					search.setText(SEARCH_HINT);
				}
			}
		});
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override public void insertUpdate(javax.swing.event.DocumentEvent event) { refreshList(); }
			@Override public void removeUpdate(javax.swing.event.DocumentEvent event) { refreshList(); }
			@Override public void changedUpdate(javax.swing.event.DocumentEvent event) { refreshList(); }
		});

		JPanel top = new JPanel(new BorderLayout(0, 8));
		top.setBackground(Theme.PANEL);
		top.add(search, BorderLayout.NORTH);
		categories.setBackground(Theme.PANEL);
		top.add(categories, BorderLayout.CENTER);
		sidebar.add(top, BorderLayout.NORTH);

		list.setFont(Theme.mono(12));
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new ModuleRenderer());
		list.addListSelectionListener(event -> {
			if (event.getValueIsAdjusting()) return;
			Object selected = list.getSelectedValue();
			if (selected == null) return;
			ConsolePort.ModuleInfo info = visible.get(String.valueOf(selected));
			if (info != null && !info.id().equals(activeId)) port.selectModule(info.id());
		});
		list.setToolTipText("");
		JScrollPane listScroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		listScroll.setBorder(Theme.line());
		listScroll.getViewport().setBackground(Theme.PANEL);
		sidebar.add(listScroll, BorderLayout.CENTER);

		statusLabel.setFont(Theme.mono(10));
		statusLabel.setForeground(Theme.FAINT);
		sidebar.add(statusLabel, BorderLayout.SOUTH);
		return sidebar;
	}

	private void rebuildCategories() {
		categories.removeAll();
		Set<String> found = new LinkedHashSet<>();
		for (ConsolePort.ModuleInfo info : modules) found.add(categoryOf(info.id()));
		List<String> ordered = new ArrayList<>();
		ordered.add("all");
		ordered.addAll(found.stream().sorted().toList());
		for (String name : ordered) {
			JButton chip = Theme.button(name, name.equals(category) ? Theme.GREEN : Theme.DIM, null);
			chip.setFont(Theme.mono(11));
			chip.addActionListener(event -> {
				category = name;
				rebuildCategories();
				refreshList();
			});
			chip.setBorder(BorderFactory.createEmptyBorder(4, 9, 4, 9));
			categories.add(chip);
		}
		categories.revalidate();
		categories.repaint();
	}

	/**
	 * Ids are shown without the leading {@code auxiliary/} that every module
	 * shares, which keeps the full path readable in a narrow list.
	 */
	private static String displayId(String id) {
		return id.startsWith("auxiliary/") ? id.substring("auxiliary/".length()) : id;
	}

	private static String categoryOf(String id) {
		String[] parts = id.split("/");
		return parts.length > 1 ? parts[1] : id;
	}

	private void tick() {
		List<ConsolePort.ModuleInfo> next = port.modules();
		if (!sameModules(next, modules)) {
			boolean categoriesChanged = !categoriesOf(next).equals(categoriesOf(modules));
			modules = next;
			if (categoriesChanged) rebuildCategories();
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

	private static Set<String> categoriesOf(List<ConsolePort.ModuleInfo> infos) {
		Set<String> out = new LinkedHashSet<>();
		for (ConsolePort.ModuleInfo info : infos) out.add(categoryOf(info.id()));
		return out;
	}

	private void showPlaceholder() {
		details.removeAll();
		editors.clear();
		JPanel centre = new JPanel(new GridBagLayout());
		centre.setBackground(Theme.BACKGROUND);
		JPanel box = new JPanel();
		box.setOpaque(false);
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("No module selected");
		title.setFont(Theme.monoBold(15));
		title.setForeground(Theme.DIM);
		title.setAlignmentX(Component.CENTER_ALIGNMENT);
		box.add(title);
		box.add(Box.createVerticalStrut(10));

		JLabel hint = new JLabel("Pick a module on the left, or drive the console:");
		hint.setFont(Theme.sans(12, false));
		hint.setForeground(Theme.FAINT);
		hint.setAlignmentX(Component.CENTER_ALIGNMENT);
		box.add(hint);
		box.add(Box.createVerticalStrut(6));

		JLabel command = new JLabel("use <module>   ·   show options   ·   run");
		command.setFont(Theme.mono(12));
		command.setForeground(Theme.BLUE);
		command.setAlignmentX(Component.CENTER_ALIGNMENT);
		box.add(command);

		centre.add(box);
		details.add(centre, BorderLayout.CENTER);
		details.revalidate();
		details.repaint();
	}

	private void buildDetails(ConsolePort.ActiveInfo active) {
		details.removeAll();
		editors.clear();

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(Theme.BACKGROUND);
		header.setBorder(BorderFactory.createEmptyBorder(14, 18, 8, 18));

		JLabel title = new JLabel(active.id());
		title.setFont(Theme.monoBold(15));
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
		description.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));
		header.add(Box.createVerticalStrut(6));
		header.add(description);

		if (active.exploit()) {
			JLabel badge = new JLabel("EXPLOIT RESEARCH · per-run legal warning required");
			badge.setFont(Theme.monoBold(10));
			badge.setForeground(Theme.RED);
			badge.setAlignmentX(Component.LEFT_ALIGNMENT);
			header.add(Box.createVerticalStrut(8));
			header.add(badge);
		}
		details.add(header, BorderLayout.NORTH);

		JPanel options = new JPanel();
		options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
		options.setBackground(Theme.BACKGROUND);
		for (ConsolePort.OptionInfo option : active.options()) {
			options.add(optionRow(option));
			options.add(Box.createVerticalStrut(8));
		}
		JScrollPane optionScroll = new JScrollPane(options,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		optionScroll.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 18));
		optionScroll.getViewport().setBackground(Theme.BACKGROUND);
		details.add(optionScroll, BorderLayout.CENTER);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
		actions.setBackground(Theme.PANEL);
		actions.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));

		JButton run = Theme.button("Run", Theme.GREEN, event -> port.run());
		run.setToolTipText("Run the selected module. CONFIRM is consumed on every run, allowed or denied.");
		JButton back = Theme.button("Back", Theme.DIM, event -> port.clearSelection());
		ConsolePort.Status status = port.status();
		JButton authorize = Theme.button(status.authorized() ? "Deauthorize" : "Authorize target",
			status.authorized() ? Theme.YELLOW : Theme.BLUE, event -> port.setAuthorized(!port.status().authorized()));
		authorize.setEnabled(status.authorizeAvailable());
		actions.add(run);
		actions.add(back);
		actions.add(authorize);
		details.add(actions, BorderLayout.SOUTH);

		details.revalidate();
		details.repaint();
	}

	private JPanel optionRow(ConsolePort.OptionInfo option) {
		JPanel row = new JPanel(new GridBagLayout());
		row.setBackground(Theme.BACKGROUND);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel labels = new JPanel(new BorderLayout(0, 2));
		labels.setBackground(Theme.BACKGROUND);
		String name = option.name() + (option.required() ? "  *" : "");
		JLabel nameLabel = new JLabel(name);
		nameLabel.setFont(Theme.monoBold(12));
		nameLabel.setForeground(option.required() ? Theme.YELLOW : Theme.TEXT);
		JLabel description = new JLabel(option.description());
		description.setFont(Theme.sans(11, false));
		description.setForeground(Theme.FAINT);
		labels.add(nameLabel, BorderLayout.NORTH);
		labels.add(description, BorderLayout.CENTER);
		labels.setToolTipText(option.description());
		description.setToolTipText(option.description());
		labels.setPreferredSize(new Dimension(168, 46));

		GridBagConstraints labelConstraints = new GridBagConstraints();
		labelConstraints.gridx = 0;
		labelConstraints.weightx = 0;
		labelConstraints.fill = GridBagConstraints.HORIZONTAL;
		labelConstraints.insets = new Insets(0, 0, 0, 12);
		row.add(labels, labelConstraints);

		Component editor;
		if (!option.choices().isEmpty()) {
			JComboBox<String> combo = new JComboBox<>(option.choices().toArray(new String[0]));
			combo.setFont(Theme.mono(12));
			combo.setBackground(Theme.FIELD);
			combo.setForeground(Theme.TEXT);
			combo.setSelectedItem(option.displayValue());
			combo.setPreferredSize(new Dimension(10, 28));
			combo.addActionListener(event -> {
				Object selected = combo.getSelectedItem();
				if (selected != null) port.setOption(option.name(), String.valueOf(selected));
			});
			editor = combo;
		} else {
			JTextField field = new JTextField(option.displayValue());
			field.setFont(Theme.mono(12));
			field.setBackground(Theme.FIELD);
			field.setForeground(option.secret() ? (option.configured() ? Theme.GREEN : Theme.DIM) : Theme.TEXT);
			field.setCaretColor(Theme.GREEN);
			field.setBorder(BorderFactory.createCompoundBorder(
				Theme.line(), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
			if (option.secret()) {
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
		editors.put(option.name(), (JComponent) editor);
		GridBagConstraints editorConstraints = new GridBagConstraints();
		editorConstraints.gridx = 1;
		editorConstraints.weightx = 1;
		editorConstraints.fill = GridBagConstraints.HORIZONTAL;
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
		String query = search.getText().strip().toLowerCase(Locale.ROOT);
		if (query.equals(SEARCH_HINT)) query = "";
		String previous = list.getSelectedValue();
		visible.clear();
		listModel.clear();
		for (ConsolePort.ModuleInfo info : modules) {
			if (!category.equals("all") && !categoryOf(info.id()).equals(category)) continue;
			if (!query.isEmpty()
					&& !info.id().toLowerCase(Locale.ROOT).contains(query)
					&& !info.name().toLowerCase(Locale.ROOT).contains(query)) continue;
			visible.put(displayId(info.id()), info);
			listModel.addElement(displayId(info.id()));
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

	/** Row renderer: category dimmed, exploit modules red, selection highlighted. */
	private final class ModuleRenderer extends javax.swing.DefaultListCellRenderer {
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean focused) {
			JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
			String id = String.valueOf(value);
			ConsolePort.ModuleInfo info = visible.get(id);
			label.setFont(Theme.mono(12));
			label.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
			label.setBackground(selected ? Theme.SELECTION : Theme.PANEL);

			String prefix = id.substring(0, Math.max(0, id.lastIndexOf('/') + 1));
			String leaf = id.substring(prefix.length());
			String color = info != null && info.exploit() ? "#FF6868"
				: info != null && info.selected() ? "#55D65A"
				: selected ? "#FFFFFF" : "#F0F0F0";
			label.setText("<html><span style='color:#6A6A6A'>" + escape(prefix) + "</span>"
				+ "<span style='color:" + color + "'>" + escape(leaf) + "</span></html>");
			label.setHorizontalAlignment(SwingConstants.LEFT);
			label.setToolTipText(info == null ? id : info.id() + " — " + info.name());
			return label;
		}
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
