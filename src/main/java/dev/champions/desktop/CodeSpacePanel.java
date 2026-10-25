package dev.champions.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Volt-style code space: a file tree over the drop-in module folder, a plain
 * editor, and compile/reload/unload wired to the same bound-checked pipeline
 * the in-game Java Studio uses.
 */
public final class CodeSpacePanel extends JPanel {
	private final ConsolePort port;
	private final StandaloneRuntime runtime;
	private final Path folder;
	private final JTree tree = new JTree();
	private final JTextArea editor = new JTextArea();
	private final JLabel status = new JLabel(" ");
	private Path openFile;

	public CodeSpacePanel(ConsolePort port, StandaloneRuntime runtime, Path moduleFolder) {
		super(new BorderLayout());
		this.port = port;
		this.runtime = runtime;
		this.folder = moduleFolder;
		setBackground(Theme.BACKGROUND);

		tree.setFont(Theme.mono(12));
		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.setBackground(Theme.PANEL);
		tree.addTreeSelectionListener(event -> {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
			if (node == null) return;
			Object user = node.getUserObject();
			if (user instanceof FileNode file && file.path() != null && Files.isRegularFile(file.path())) open(file.path());
		});

		JPanel treePane = new JPanel(new BorderLayout());
		treePane.setBackground(Theme.PANEL);
		treePane.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JLabel root = new JLabel("modules/");
		root.setFont(Theme.monoBold(12));
		root.setForeground(Theme.GREEN);
		treePane.add(root, BorderLayout.NORTH);
		JScrollPane treeScroll = new JScrollPane(tree);
		treeScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		treePane.add(treeScroll, BorderLayout.CENTER);
		treePane.setPreferredSize(new Dimension(280, 500));

		editor.setFont(Theme.mono(13));
		editor.setBackground(Theme.FIELD);
		editor.setForeground(Theme.TEXT);
		editor.setCaretColor(Theme.GREEN);
		editor.setTabSize(4);
		JScrollPane editorScroll = new JScrollPane(editor);
		editorScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

		JPanel editorPane = new JPanel(new BorderLayout());
		editorPane.setBackground(Theme.BACKGROUND);
		editorPane.add(editorScroll, BorderLayout.CENTER);
		editorPane.add(toolbar(), BorderLayout.NORTH);
		status.setFont(Theme.mono(11));
		status.setForeground(Theme.DIM);
		status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
		status.setOpaque(true);
		status.setBackground(Theme.TITLE);
		editorPane.add(status, BorderLayout.SOUTH);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePane, editorPane);
		split.setBorder(BorderFactory.createEmptyBorder());
		split.setDividerLocation(280);
		add(split, BorderLayout.CENTER);

		refreshTree();
		status.setText("Drop .java files into " + folder + " — compile and run them without restarting.");
	}

	private JPanel toolbar() {
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
		bar.setBackground(Theme.TITLE);
		bar.add(button("Save", event -> save()));
		bar.add(button("New module", event -> newModule()));
		bar.add(button("Compile & load", event -> compile()));
		bar.add(button("Unload", event -> unload()));
		bar.add(button("Refresh", event -> refreshTree()));
		return bar;
	}

	private JButton button(String label, java.awt.event.ActionListener action) {
		JButton button = new JButton(label);
		button.setFont(Theme.mono(12));
		button.addActionListener(action);
		return button;
	}

	private void open(Path file) {
		try {
			openFile = file;
			editor.setText(Files.readString(file, StandardCharsets.UTF_8));
			editor.setCaretPosition(0);
			status.setForeground(Theme.DIM);
			status.setText(folder.relativize(file).toString());
		} catch (IOException error) {
			status.setForeground(Theme.RED);
			status.setText("Could not read " + file.getFileName() + ": " + error.getMessage());
		}
	}

	private void save() {
		if (openFile == null) {
			status.setForeground(Theme.YELLOW);
			status.setText("Select a file first, or use New module.");
			return;
		}
		try {
			Files.writeString(openFile, editor.getText(), StandardCharsets.UTF_8);
			status.setForeground(Theme.GREEN);
			status.setText("Saved " + folder.relativize(openFile));
			refreshTree();
		} catch (IOException error) {
			status.setForeground(Theme.RED);
			status.setText("Save failed: " + error.getMessage());
		}
	}

	private void newModule() {
		String name = "MyModule";
		Path file = folder.resolve(name + ".java");
		int suffix = 1;
		while (Files.exists(file)) file = folder.resolve(name + (suffix++) + ".java");
		try {
			Files.createDirectories(folder);
			Files.writeString(file, template(name + (suffix == 1 ? "" : String.valueOf(suffix - 1))), StandardCharsets.UTF_8);
			refreshTree();
			open(file);
			status.setForeground(Theme.GREEN);
			status.setText("Created " + folder.relativize(file));
		} catch (IOException error) {
			status.setForeground(Theme.RED);
			status.setText("Could not create the file: " + error.getMessage());
		}
	}

	private void compile() {
		if (port instanceof RemoteConsolePort) {
			port.submit("java modules reload");
			status.setForeground(Theme.BLUE);
			status.setText("Asked the game process to reload the module folder (output appears in the terminal).");
			return;
		}
		if (runtime == null) {
			status.setForeground(Theme.RED);
			status.setText("No standalone runtime is available in this session.");
			return;
		}
		save();
		StandaloneRuntime.Result result = runtime.reload();
		status.setForeground(result.success() ? Theme.GREEN : Theme.RED);
		status.setText(result.message());
		refreshTree();
	}

	private void unload() {
		if (runtime == null) {
			port.submit("java modules unload");
			return;
		}
		StandaloneRuntime.Result result = runtime.unload("Unloaded on request");
		status.setForeground(Theme.YELLOW);
		status.setText(result.message());
	}

	private void refreshTree() {
		DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(new FileNode(folder, folder.getFileName() == null ? "modules" : folder.getFileName().toString()));
		try {
			Files.createDirectories(folder);
			try (Stream<Path> stream = Files.walk(folder, 4)) {
				stream.map(path -> path.toAbsolutePath().normalize())
					.filter(path -> !path.equals(folder.toAbsolutePath().normalize()))
					.filter(path -> !path.getFileName().toString().startsWith("."))
					.sorted()
					.forEach(path -> {
						DefaultMutableTreeNode parent = parentFor(rootNode, path.getParent());
						if (parent != null) parent.add(new DefaultMutableTreeNode(
							new FileNode(path, path.getFileName().toString())));
					});
			}
		} catch (IOException error) {
			status.setForeground(Theme.RED);
			status.setText("Could not read the module folder: " + error.getMessage());
		}
		tree.setModel(new DefaultTreeModel(rootNode));
		for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
	}

	private DefaultMutableTreeNode parentFor(DefaultMutableTreeNode rootNode, Path parent) {
		if (parent == null || parent.equals(folder.getParent())) return null;
		if (parent.equals(folder)) return rootNode;
		DefaultMutableTreeNode result = null;
		java.util.Enumeration<javax.swing.tree.TreeNode> nodes = rootNode.depthFirstEnumeration();
		while (nodes.hasMoreElements()) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
			if (node.getUserObject() instanceof FileNode file && parent.equals(file.path())) result = node;
		}
		return result;
	}

	private record FileNode(Path path, String label) {
		@Override public String toString() {
			return label + (path != null && Files.isDirectory(path) ? "/" : "");
		}
	}

	/** Starter module matching the documented drop-in contract. */
	private static String template(String className) {
		return "import dev.champions.core.module.Finding;\n"
			+ "import dev.champions.core.module.ModuleContext;\n"
			+ "import dev.champions.core.module.ModuleOption;\n"
			+ "import dev.champions.core.module.ModuleResult;\n"
			+ "import dev.champions.core.module.ResearchModule;\n\n"
			+ "import java.util.List;\n"
			+ "import java.util.Map;\n\n"
			+ "public final class " + className + " implements ResearchModule {\n"
			+ "\t@Override public String id() { return \"auxiliary/local/" + className.toLowerCase(java.util.Locale.ROOT) + "\"; }\n"
			+ "\t@Override public String name() { return \"" + className + "\"; }\n"
			+ "\t@Override public String description() { return \"Local drop-in module.\"; }\n"
			+ "\t@Override public List<ModuleOption> options() {\n"
			+ "\t\treturn List.of(\n"
			+ "\t\t\tnew ModuleOption(\"RHOST\", \"CURRENT\", true, false, \"Current authorized connection\"),\n"
			+ "\t\t\tnew ModuleOption(\"CONFIRM\", \"\", true, true, \"One-run authorization phrase\")\n"
			+ "\t\t);\n"
			+ "\t}\n"
			+ "\t@Override public ModuleResult run(ModuleContext context, Map<String, String> options) {\n"
			+ "\t\treturn new ModuleResult(\"Ran locally\", List.of(new Finding(Finding.Severity.PASS,\n"
			+ "\t\t\t\"Target\", context.serverAddress(), \"Passive module: nothing was sent.\")));\n"
			+ "\t}\n"
			+ "}\n";
	}
}
