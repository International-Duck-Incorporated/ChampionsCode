package dev.champions.desktop;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Element;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Volt-style code space: a file tree over the drop-in module folder, a plain
 * editor with line numbers, and compile/reload/unload wired to the same
 * bound-checked pipeline the in-game Java Studio uses.
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
		super(new BorderLayout(0, 10));
		this.port = port;
		this.runtime = runtime;
		this.folder = moduleFolder;
		setOpaque(false);

		tree.setFont(Theme.mono(12));
		tree.setBackground(Theme.PANEL);
		tree.setRowHeight(Math.max(18, tree.getFontMetrics(Theme.mono(12)).getHeight() + 3));
		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.setCellRenderer(new FlatTreeRenderer());
		// No connector lines: the glyphs carry the hierarchy.
		tree.putClientProperty("JTree.lineStyle", "None");
		tree.addTreeSelectionListener(event -> {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
			if (node != null && node.getUserObject() instanceof FileNode fileNode
					&& !java.nio.file.Files.isRegularFile(fileNode.path())) {
				return;
			}
			if (node == null) return;
			Object user = node.getUserObject();
			if (user instanceof FileNode file && file.path() != null && Files.isRegularFile(file.path())) open(file.path());
		});

		JPanel treePane = Theme.card(new BorderLayout(0, 6));
		treePane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		JLabel root = new JLabel("modules/");
		root.setFont(Theme.monoBold(12));
		root.setForeground(Theme.GREEN);
		treePane.add(root, BorderLayout.NORTH);
		JScrollPane treeScroll = new JScrollPane(tree);
		treeScroll.setBorder(Theme.line());
		treeScroll.getViewport().setBackground(Theme.PANEL);
		treePane.add(treeScroll, BorderLayout.CENTER);
		treePane.setPreferredSize(new Dimension(260, 500));

		editor.setFont(Theme.mono(13));
		editor.setBackground(Theme.FIELD);
		editor.setForeground(Theme.TEXT);
		editor.setCaretColor(Theme.ACCENT_SOFT);
		editor.setSelectionColor(Theme.SELECTION);
		editor.setTabSize(4);
		editor.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		editor.setText("");
		editor.setToolTipText("New module… to start a file, or pick one in the tree.");

		JScrollPane editorScroll = new JScrollPane(editor);
		editorScroll.setBorder(Theme.line());
		editorScroll.setRowHeaderView(new LineNumbers(editor));

		JPanel editorBody = new JPanel(new BorderLayout());
		editorBody.setOpaque(false);
		editorBody.setBorder(Theme.padding(8, 8, 8, 8));
		editorBody.add(editorScroll, BorderLayout.CENTER);

		JPanel editorPane = Theme.card(new BorderLayout());
		editorPane.add(toolbar(), BorderLayout.NORTH);
		editorPane.add(editorBody, BorderLayout.CENTER);

		status.setFont(Theme.mono(11));
		status.setForeground(Theme.DIM);
		status.setOpaque(true);
		status.setBackground(Theme.PANEL);
		status.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
			BorderFactory.createEmptyBorder(6, 12, 6, 12)));
		editorPane.add(status, BorderLayout.SOUTH);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePane, editorPane);
		split.setBorder(BorderFactory.createEmptyBorder());
		split.setDividerSize(12);
		split.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
			@Override public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
				return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
					@Override public void paint(java.awt.Graphics graphics) {
						graphics.setColor(Theme.BACKGROUND);
						graphics.fillRect(0, 0, getWidth(), getHeight());
					}
				};
			}
		});
		split.setDividerLocation(260);
		split.setContinuousLayout(true);
		split.setOpaque(false);
		add(split, BorderLayout.CENTER);
		status.setFont(Theme.monoBold(9));
		add(Theme.header("Code space", "drop-in Java modules · compiled and loaded without restarting", status), BorderLayout.NORTH);

		refreshTree();
		status.setText("Drop .java files into " + folder + " — compile and run them without restarting.");
	}

	private JPanel toolbar() {
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
		bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
		bar.setOpaque(false);
		bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
		bar.add(Theme.button("Save", Theme.TEXT, event -> save()));
		bar.add(Theme.button("New module", Theme.TEXT, event -> newModule()));
		bar.add(Theme.filled("Compile & load", event -> compile()));
		bar.add(Theme.button("Unload", Theme.YELLOW, event -> unload()));
		bar.add(Theme.button("Refresh", Theme.DIM, event -> refreshTree()));
		return bar;
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
		String className = file.getFileName().toString().replace(".java", "");
		try {
			Files.createDirectories(folder);
			Files.writeString(file, template(className), StandardCharsets.UTF_8);
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
		DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(
			new FileNode(folder, folder.getFileName() == null ? "modules" : folder.getFileName().toString()));
		try {
			Files.createDirectories(folder);
			Path rootPath = folder.toAbsolutePath().normalize();
			try (Stream<Path> stream = Files.walk(folder, 4)) {
				stream.map(path -> path.toAbsolutePath().normalize())
					.filter(path -> !path.equals(rootPath))
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
		if (rootNode.getChildCount() == 0) {
			rootNode.add(new DefaultMutableTreeNode(new FileNode(folder, "no .java files yet — New module…")));
		}
		tree.setModel(new DefaultTreeModel(rootNode));
		for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
		if (openFile == null) {
			try (Stream<Path> modules = Files.walk(folder, 4)) {
				modules.map(path -> path.toAbsolutePath().normalize())
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.sorted()
					.findFirst()
					.ifPresent(this::open);
			} catch (IOException ignored) {
				// an unreadable folder is reported by the status line above
			}
		}
	}

	private DefaultMutableTreeNode parentFor(DefaultMutableTreeNode rootNode, Path parent) {
		if (parent == null || parent.equals(folder.getParent())) return null;
		if (parent.equals(folder.toAbsolutePath().normalize())) return rootNode;
		DefaultMutableTreeNode result = null;
		java.util.Enumeration<javax.swing.tree.TreeNode> nodes = rootNode.depthFirstEnumeration();
		while (nodes.hasMoreElements()) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
			if (node.getUserObject() instanceof FileNode file && parent.equals(file.path())) result = node;
		}
		return result;
	}

	/**
	 * Icon-free, monospaced rows: folders green, files light, selection highlighted.
	 * Builds its own label rather than reconfiguring the platform renderer, whose
	 * opaque/background handling differs between LAFs.
	 */
	private static javax.swing.Icon glyph(Icons.Glyph glyph, java.awt.Color colour) {
		return new javax.swing.Icon() {
			@Override public void paintIcon(java.awt.Component target, java.awt.Graphics graphics, int x, int y) {
				java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
				try {
					g.translate(x, y);
					Icons.paint(g, glyph, 0, 0, getIconWidth(), colour);
				} finally {
					g.dispose();
				}
			}
			@Override public int getIconWidth() { return 13; }
			@Override public int getIconHeight() { return 13; }
		};
	}

	private static final class FlatTreeRenderer extends javax.swing.tree.DefaultTreeCellRenderer {
		@Override
		public java.awt.Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
				boolean expanded, boolean leaf, int row, boolean focused) {
			JLabel label = new JLabel();
			label.setOpaque(true);
			label.setFont(Theme.mono(12));
			label.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
			label.setBackground(selected ? Theme.SELECTION : Theme.PANEL);
			if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof FileNode file) {
				boolean directory = file.path() != null && Files.isDirectory(file.path());
				label.setText(directory ? file.label() + "/" : file.label());
				label.setIcon(glyph(directory ? Icons.Glyph.FOLDER : Icons.Glyph.FILE,
					selected ? java.awt.Color.WHITE : directory ? Theme.ACCENT_SOFT : Theme.DIM));
				label.setIconTextGap(7);
				label.setForeground(selected ? java.awt.Color.WHITE : directory ? Theme.TEXT : Theme.DIM);
			} else {
				label.setText(String.valueOf(value));
				label.setForeground(Theme.DIM);
			}
			return label;
		}
	}

	private record FileNode(Path path, String label) {
		@Override public String toString() {
			return label + (path != null && Files.isDirectory(path) ? "/" : "");
		}
	}

	/** Monospaced line-number gutter that follows the editor's font and scrolling. */
	private static final class LineNumbers extends JPanel implements DocumentListener {
		private final JTextArea area;

		LineNumbers(JTextArea area) {
			this.area = area;
			setBackground(Theme.PANEL);
			setFont(Theme.mono(12));
			area.getDocument().addDocumentListener(this);
			area.addCaretListener(event -> repaint());
		}

		@Override public Dimension getPreferredSize() {
			int lines = Math.max(1, area.getLineCount());
			FontMetrics metrics = getFontMetrics(getFont());
			int width = metrics.stringWidth(String.valueOf(lines)) + 18;
			return new Dimension(width, Math.max(area.getHeight(), lines * metrics.getHeight()));
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g.setColor(Theme.PANEL);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setFont(getFont());
				FontMetrics metrics = getFontMetrics(getFont());
				Rectangle clip = g.getClipBounds();
				Element root = area.getDocument().getDefaultRootElement();
				int caretLine = root.getElementIndex(area.getCaretPosition());
				for (int line = 0; line < root.getElementCount(); line++) {
					int offset = root.getElement(line).getStartOffset();
					int y;
					try {
						java.awt.geom.Rectangle2D view = area.modelToView2D(offset);
						if (view == null) continue;
						y = (int) Math.round(view.getY()) + metrics.getAscent();
					} catch (javax.swing.text.BadLocationException | NullPointerException unavailable) {
						continue;
					}
					if (y < clip.y - metrics.getHeight() || y > clip.y + clip.height + metrics.getHeight()) continue;
					g.setColor(line == caretLine ? Theme.TEXT : Theme.FAINT);
					String label = String.valueOf(line + 1);
					g.drawString(label, getWidth() - 8 - metrics.stringWidth(label), y);
				}
			} finally {
				g.dispose();
			}
		}

		@Override public void insertUpdate(DocumentEvent event) { revalidate(); repaint(); }
		@Override public void removeUpdate(DocumentEvent event) { revalidate(); repaint(); }
		@Override public void changedUpdate(DocumentEvent event) { revalidate(); repaint(); }
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
