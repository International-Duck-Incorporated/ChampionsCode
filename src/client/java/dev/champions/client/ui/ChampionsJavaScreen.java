package dev.champions.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.champions.client.java.JavaRuntimeManager;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static dev.champions.core.ui.MinecraftPointerButtons.PRIMARY;

/** A dedicated runtime Java workspace with an IDE-like layout and explicit safety gate. */
public final class ChampionsJavaScreen extends Screen implements WorkspaceWindow {
	private static final int TITLE_HEIGHT = 26;
	private static final int SIDEBAR_WIDTH = 126;
	private static final int RIGHT_PANEL_WIDTH = 164;
	private static final int RESIZE_GRIP = 12;
	private static int savedX = -1;
	private static int savedY = -1;
	private static int savedWidth = -1;
	private static int savedHeight = -1;

	private final ChampionConsole console;
	private final JavaRuntimeManager runtime;
	private WorkspaceHost host;
	private final List<AbstractWidget> windowWidgets = new ArrayList<>();
	private List<String> files = List.of();
	private String selectedFile = "";
	private MultiLineEditBox editor;
	private EditBox commandInput;
	private int windowX;
	private int windowY;
	private int windowWidth;
	private int windowHeight;
	private int restoreX;
	private int restoreY;
	private int restoreWidth;
	private int restoreHeight;
	private boolean moving;
	private boolean resizing;
	private boolean minimized;
	private boolean maximized;
	private boolean dirty;
	private int dragOffsetX;
	private int dragOffsetY;
	private String notice = "Runtime Java is unrestricted. Review and confirm the source hash before compiling.";
	private ConsoleLine.Tone noticeTone = ConsoleLine.Tone.WARNING;

	public ChampionsJavaScreen(ChampionConsole console, JavaRuntimeManager runtime) {
		super(Component.literal("ChampionsCode Java Studio"));
		this.console = console;
		this.runtime = runtime;
	}

	/** When hosted inside the workspace, close/navigation actions go through the host. */
	public void host(WorkspaceHost host) {
		this.host = host;
	}

	@Override
	public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// No vanilla blur/menu background: the studio is one window on the workspace desktop.
	}

	@Override
	protected void init() {
		windowWidgets.clear();
		int maximumWidth = Math.min(width, Math.max(520, width - Math.min(24, Math.max(0, width / 8))));
		int maximumHeight = Math.min(height, Math.max(330, height - Math.min(24, Math.max(0, height / 8))));
		int minimumWidth = Math.min(620, maximumWidth);
		int minimumHeight = Math.min(400, maximumHeight);
		if (maximized) {
			windowX = 0;
			windowY = 0;
			windowWidth = width;
			windowHeight = height;
		} else {
			windowWidth = savedWidth < 0 ? Math.min(980, maximumWidth) : clamp(savedWidth, minimumWidth, maximumWidth);
			windowHeight = savedHeight < 0 ? Math.min(610, maximumHeight) : clamp(savedHeight, minimumHeight, maximumHeight);
			windowX = savedX < 0 ? (width - windowWidth) / 2 : clamp(savedX, 0, Math.max(0, width - windowWidth));
			windowY = savedY < 0 ? (height - windowHeight) / 2 : clamp(savedY, 0, Math.max(0, height - windowHeight));
			saveGeometry();
		}
		refreshFileList(false);
		buildWorkspaceWidgets();
		setWidgetsVisible(!minimized);
	}

	private void buildWorkspaceWidgets() {
		int centerX = windowX + SIDEBAR_WIDTH;
		int rightX = windowX + windowWidth - RIGHT_PANEL_WIDTH;
		int centerWidth = Math.max(180, rightX - centerX);
		int editorY = windowY + TITLE_HEIGHT + 34;
		int consoleHeight = Math.max(104, (windowHeight - TITLE_HEIGHT) / 3);
		int consoleTop = windowY + windowHeight - consoleHeight;
		int editorHeight = Math.max(90, consoleTop - editorY - 8);

		editor = MultiLineEditBox.builder()
			.setX(centerX + 12)
			.setY(editorY)
			.setPlaceholder(Component.literal("Select a Java source or champion-java.json from Workspace Files."))
			.setTextColor(0xFFE6DDF8)
			.setTextShadow(false)
			.setCursorColor(0xFF9B4DFF)
			.setShowBackground(false)
			.setShowDecorations(true)
			.build(font, Math.max(80, centerWidth - 24), editorHeight, Component.literal("Runtime Java source editor"));
		editor.setCharacterLimit(512 * 1024);
		loadSelectedFile();
		editor.setValueListener(value -> dirty = true);
		track(editor);

		commandInput = new EditBox(font, centerX + 14, windowY + windowHeight - 23,
			Math.max(60, centerWidth - 94), 17, Component.literal("Java runtime command"));
		commandInput.setMaxLength(2048);
		commandInput.setHint(Component.literal("status | hash | confirm <sha256> | compile | start | reload | stop"));
		commandInput.setBordered(false);
		commandInput.setTextColor(0xFFEDE9F6);
		track(commandInput);
		track(Button.builder(Component.literal("Run"), button -> submitCommand())
			.bounds(rightX - 72, windowY + windowHeight - 25, 62, 19).build());

		int sideX = windowX + 12;
		int sideY = windowY + TITLE_HEIGHT + 54;
		track(Button.builder(Component.literal("Workspace"), button -> refreshFileList(true))
			.bounds(sideX, sideY, SIDEBAR_WIDTH - 24, 20).build());
		track(Button.builder(Component.literal("Terminal"), button -> openWindow(dev.champions.client.ChampionsScreens.TERMINAL))
			.bounds(sideX, sideY + 26, SIDEBAR_WIDTH - 24, 20).build());
		track(Button.builder(Component.literal("ClickGUI"), button -> openWindow(dev.champions.client.ChampionsScreens.CLICKGUI))
			.bounds(sideX, sideY + 52, SIDEBAR_WIDTH - 24, 20).build());
		track(Button.builder(Component.literal("Log viewer"), button -> openWindow(dev.champions.client.ChampionsScreens.LOGVIEWER))
			.bounds(sideX, sideY + 72, SIDEBAR_WIDTH - 24, 20).build());
		track(Button.builder(Component.literal("Status"), button -> runRuntime("status"))
			.bounds(sideX, sideY + 92, SIDEBAR_WIDTH - 24, 20).build());
		track(Button.builder(Component.literal("Source hash"), button -> runRuntime("hash"))
			.bounds(sideX, sideY + 118, SIDEBAR_WIDTH - 24, 20).build());
		track(Button.builder(Component.literal("Diagnostics"), button -> runRuntime("diagnostics"))
			.bounds(sideX, sideY + 144, SIDEBAR_WIDTH - 24, 20).build());

		int fileY = windowY + TITLE_HEIGHT + 48;
		int maxFileButtons = Math.max(1, Math.min(7, (windowHeight - 250) / 23));
		for (int i = 0; i < Math.min(files.size(), maxFileButtons); i++) {
			String file = files.get(i);
			String label = file.equals(selectedFile) ? "> " + shortName(file) : shortName(file);
			track(Button.builder(Component.literal(label), button -> selectFile(file))
				.bounds(rightX + 10, fileY + i * 23, RIGHT_PANEL_WIDTH - 20, 19).build());
		}

		int actionsY = windowY + windowHeight - 151;
		track(Button.builder(Component.literal(dirty ? "Save file *" : "Save file"), button -> saveCurrentFile())
			.bounds(rightX + 10, actionsY, RIGHT_PANEL_WIDTH - 20, 20).build());
		track(Button.builder(Component.literal("Compile"), button -> runRuntime("compile"))
			.bounds(rightX + 10, actionsY + 26, RIGHT_PANEL_WIDTH - 20, 20).build());
		track(Button.builder(Component.literal("Start / Reload"), button -> runRuntime("reload"))
			.bounds(rightX + 10, actionsY + 52, RIGHT_PANEL_WIDTH - 20, 20).build());
		track(Button.builder(Component.literal("Stop"), button -> runRuntime("stop"))
			.bounds(rightX + 10, actionsY + 78, RIGHT_PANEL_WIDTH - 20, 20).build());
		track(Button.builder(Component.literal("Close"), button -> closeWindow())
			.bounds(rightX + 10, actionsY + 104, RIGHT_PANEL_WIDTH - 20, 20).build());
	}

	private <T extends AbstractWidget> T track(T widget) {
		windowWidgets.add(widget);
		return addRenderableWidget(widget);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int shownHeight = minimized ? TITLE_HEIGHT : windowHeight;
		graphics.fill(windowX - 3, windowY - 3, windowX + windowWidth + 3, windowY + shownHeight + 3, 0x990A0016);
		graphics.fill(windowX, windowY, windowX + windowWidth, windowY + shownHeight, 0xFA080611);
		graphics.fill(windowX, windowY, windowX + windowWidth, windowY + TITLE_HEIGHT, 0xFF12101E);
		graphics.fill(windowX, windowY + TITLE_HEIGHT - 1, windowX + windowWidth, windowY + TITLE_HEIGHT, 0xFF7124D8);
		graphics.text(font, "CHAMPIONSCODE JAVA STUDIO", windowX + 12, windowY + 9, 0xFFB26BFF, false);
		graphics.text(font, "JDK 25 | HASH-GATED", Math.max(windowX + 170, WindowChrome.controlsLeft(windowX, windowWidth) - 120),
			windowY + 9, 0xFF8F829F, false);
		WindowChrome.render(graphics, font, windowX, windowY, windowWidth, TITLE_HEIGHT,
			mouseX, mouseY, 0xFFD9C5F5, maximized);
		if (minimized) {
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}

		int centerX = windowX + SIDEBAR_WIDTH;
		int rightX = windowX + windowWidth - RIGHT_PANEL_WIDTH;
		int consoleHeight = Math.max(104, (windowHeight - TITLE_HEIGHT) / 3);
		int consoleTop = windowY + windowHeight - consoleHeight;
		graphics.fill(windowX, windowY + TITLE_HEIGHT, centerX, windowY + windowHeight, 0xFF0D0B17);
		graphics.fill(centerX, windowY + TITLE_HEIGHT, rightX, windowY + windowHeight, 0xFF08070E);
		graphics.fill(rightX, windowY + TITLE_HEIGHT, windowX + windowWidth, windowY + windowHeight, 0xFF0D0B17);
		graphics.fill(centerX, windowY + TITLE_HEIGHT, centerX + 1, windowY + windowHeight, 0xFF25183A);
		graphics.fill(rightX - 1, windowY + TITLE_HEIGHT, rightX, windowY + windowHeight, 0xFF25183A);

		graphics.text(font, "CHAMPIONSCODE", windowX + 12, windowY + TITLE_HEIGHT + 16, 0xFFA855F7, false);
		graphics.text(font, "RUNTIME JAVA", windowX + 12, windowY + TITLE_HEIGHT + 29, 0xFF786A89, false);
		graphics.text(font, "Workspace Files", rightX + 10, windowY + TITLE_HEIGHT + 15, 0xFFEDE9F6, false);
		graphics.text(font, files.size() + " editable file(s)", rightX + 10, windowY + TITLE_HEIGHT + 28, 0xFF786A89, false);

		graphics.fill(centerX, windowY + TITLE_HEIGHT, rightX, windowY + TITLE_HEIGHT + 30, 0xFF100D1A);
		String tab = selectedFile.isBlank() ? "No file selected" : selectedFile + (dirty ? " *" : "");
		graphics.fill(centerX + 8, windowY + TITLE_HEIGHT + 5, centerX + Math.min(rightX - centerX - 8, 154),
			windowY + TITLE_HEIGHT + 29, 0xFF1B1230);
		graphics.text(font, fit(tab, Math.max(40, Math.min(140, rightX - centerX - 28))), centerX + 16,
			windowY + TITLE_HEIGHT + 13, 0xFFD9C5F5, false);
		graphics.fill(centerX + 8, windowY + TITLE_HEIGHT + 28, centerX + Math.min(rightX - centerX - 8, 154),
			windowY + TITLE_HEIGHT + 30, 0xFF8B2BE2);

		graphics.fill(centerX + 8, windowY + TITLE_HEIGHT + 32, rightX - 8, consoleTop - 5, 0xFF0A0811);
		graphics.fill(centerX, consoleTop - 1, rightX, consoleTop + 1, 0xFF4A1F73);
		graphics.fill(centerX + 8, consoleTop + 7, rightX - 8, windowY + windowHeight - 30, 0xFF07060B);
		graphics.text(font, "Console", centerX + 14, consoleTop + 13, 0xFFC56FFF, false);
		graphics.text(font, fit(notice, Math.max(30, rightX - centerX - 95)), centerX + 66, consoleTop + 13,
			color(noticeTone), false);
		renderConsoleOutput(graphics, centerX + 14, consoleTop + 27, Math.max(20, rightX - centerX - 28),
			Math.max(1, (windowY + windowHeight - 57 - (consoleTop + 27)) / 10));
		graphics.text(font, "java >", centerX + 14, windowY + windowHeight - 19, 0xFFA855F7, false);
		renderResizeGrip(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private void renderConsoleOutput(GuiGraphicsExtractor graphics, int x, int y, int maxWidth, int rows) {
		List<ConsoleLine> lines = console.lines();
		int start = Math.max(0, lines.size() - rows);
		for (int i = start; i < lines.size(); i++) {
			ConsoleLine line = lines.get(i);
			graphics.text(font, fit(line.text(), maxWidth), x, y, color(line.tone()), false);
			y += 10;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.hasControlDownWithQuirk() && event.input() == InputConstants.KEY_S) {
			saveCurrentFile();
			return true;
		}
		if (event.isConfirmation() && commandInput != null && commandInput.isFocused()) {
			submitCommand();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == PRIMARY) {
			WindowChrome.Control control = WindowChrome.controlAt(windowX, windowY, windowWidth, TITLE_HEIGHT, event.x(), event.y());
			if (control != WindowChrome.Control.NONE) {
				handleWindowControl(control);
				return true;
			}
			if (!minimized && !maximized && inResizeGrip(event.x(), event.y())) {
				resizing = true;
				setDragging(true);
				return true;
			}
			if (inTitleBar(event.x(), event.y())) {
				if (doubleClick) toggleMaximize();
				else {
					moving = !maximized;
					dragOffsetX = (int) event.x() - windowX;
					dragOffsetY = (int) event.y() - windowY;
					setDragging(moving);
				}
				return true;
			}
		}
		return minimized ? true : super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (resizing) {
			int maxWidth = Math.max(1, width - windowX);
			int maxHeight = Math.max(1, height - windowY);
			savedWidth = clamp((int) event.x() - windowX, Math.min(620, maxWidth), maxWidth);
			savedHeight = clamp((int) event.y() - windowY, Math.min(400, maxHeight), maxHeight);
			savedX = windowX;
			savedY = windowY;
			rebuildPreservingDraft();
			setDragging(true);
			return true;
		}
		if (moving) {
			int shownHeight = minimized ? TITLE_HEIGHT : windowHeight;
			int nextX = clamp((int) event.x() - dragOffsetX, 0, Math.max(0, width - windowWidth));
			int nextY = clamp((int) event.y() - dragOffsetY, 0, Math.max(0, height - shownHeight));
			moveWidgets(nextX - windowX, nextY - windowY);
			windowX = nextX;
			windowY = nextY;
			saveGeometry();
			return true;
		}
		return super.mouseDragged(event, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (moving || resizing) {
			moving = false;
			resizing = false;
			setDragging(false);
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public void onClose() {
		closeWindow();
	}

	private void closeWindow() {
		if (host != null) {
			host.requestClose(dev.champions.client.ChampionsScreens.JAVASTUDIO);
			return;
		}
		minecraft.setScreenAndShow(null);
	}

	private void openWindow(String windowId) {
		if (host != null) {
			host.requestOpen(windowId);
			return;
		}
		minecraft.setScreenAndShow(switch (windowId) {
			case dev.champions.client.ChampionsScreens.TERMINAL -> new ChampionsConsoleScreen(console);
			case dev.champions.client.ChampionsScreens.LOGVIEWER -> new ChampionsLogScreen();
			default -> new ChampionsClickGuiScreen(console, this);
		});
	}

	@Override
	public boolean coversPoint(double mouseX, double mouseY) {
		int shownHeight = minimized ? TITLE_HEIGHT : windowHeight;
		return mouseX >= windowX && mouseX < windowX + windowWidth
			&& mouseY >= windowY && mouseY < windowY + shownHeight;
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }

	private void handleWindowControl(WindowChrome.Control control) {
		switch (control) {
			case MINIMIZE -> {
				minimized = !minimized;
				setWidgetsVisible(!minimized);
			}
			case MAXIMIZE -> toggleMaximize();
			case CLOSE -> minecraft.setScreenAndShow(null);
			default -> {}
		}
	}

	private void toggleMaximize() {
		if (minimized) {
			minimized = false;
			setWidgetsVisible(true);
		}
		if (!maximized) {
			restoreX = windowX;
			restoreY = windowY;
			restoreWidth = windowWidth;
			restoreHeight = windowHeight;
			maximized = true;
		} else {
			maximized = false;
			savedX = restoreX;
			savedY = restoreY;
			savedWidth = restoreWidth;
			savedHeight = restoreHeight;
		}
		rebuildPreservingDraft();
	}

	private void rebuildPreservingDraft() {
		String draft = editor == null ? "" : editor.getValue();
		String file = selectedFile;
		boolean changed = dirty;
		rebuildWidgets();
		if (editor != null && file.equals(selectedFile) && changed) {
			editor.setValue(draft);
			dirty = true;
		}
	}

	private void refreshFileList(boolean report) {
		if (dirty && report) {
			setNotice("Save or discard the current edit before refreshing files.", ConsoleLine.Tone.WARNING);
			return;
		}
		try {
			files = runtime.editableFiles();
			if (!files.contains(selectedFile)) selectedFile = files.isEmpty() ? "" : files.getFirst();
			if (report) {
				setNotice("Reloaded " + files.size() + " editable workspace file(s).", ConsoleLine.Tone.SUCCESS);
				rebuildWidgets();
			}
		} catch (IOException error) {
			setNotice("Could not read Java workspace: " + safeMessage(error), ConsoleLine.Tone.ERROR);
		}
	}

	private void selectFile(String file) {
		if (file.equals(selectedFile)) return;
		if (dirty) {
			setNotice("Unsaved changes remain in " + selectedFile + ". Save before switching files.", ConsoleLine.Tone.WARNING);
			return;
		}
		selectedFile = file;
		rebuildWidgets();
	}

	private void loadSelectedFile() {
		if (editor == null || selectedFile.isBlank()) return;
		try {
			editor.setValue(runtime.readEditableFile(selectedFile));
			dirty = false;
		} catch (IOException error) {
			editor.setValue("");
			dirty = false;
			setNotice("Could not open " + selectedFile + ": " + safeMessage(error), ConsoleLine.Tone.ERROR);
		}
	}

	private void saveCurrentFile() {
		if (editor == null || selectedFile.isBlank()) {
			setNotice("No editable workspace file is selected.", ConsoleLine.Tone.WARNING);
			return;
		}
		try {
			runtime.saveEditableFile(selectedFile, editor.getValue());
			dirty = false;
			String hash = runtime.currentSourceHash();
			setNotice("Saved. Review and confirm source hash " + hash, ConsoleLine.Tone.SUCCESS);
		} catch (IOException error) {
			setNotice("Save failed: " + safeMessage(error), ConsoleLine.Tone.ERROR);
		}
	}

	private void submitCommand() {
		String command = commandInput == null ? "" : commandInput.getValue().strip();
		if (command.isBlank()) return;
		if (command.toLowerCase(java.util.Locale.ROOT).startsWith("java ")) command = command.substring(5).strip();
		runRuntime(command);
		commandInput.setValue("");
	}

	private void runRuntime(String command) {
		runtime.submit(command);
		setNotice("Runtime command submitted: " + command, ConsoleLine.Tone.INFO);
	}

	private void setNotice(String value, ConsoleLine.Tone tone) {
		notice = value;
		noticeTone = tone;
	}

	private void setWidgetsVisible(boolean visible) {
		for (AbstractWidget widget : windowWidgets) widget.visible = visible;
	}

	private void moveWidgets(int deltaX, int deltaY) {
		for (AbstractWidget widget : windowWidgets) {
			widget.setX(widget.getX() + deltaX);
			widget.setY(widget.getY() + deltaY);
		}
	}

	private void saveGeometry() {
		if (maximized) return;
		savedX = windowX;
		savedY = windowY;
		savedWidth = windowWidth;
		savedHeight = windowHeight;
	}

	private boolean inTitleBar(double mouseX, double mouseY) {
		return mouseX >= windowX && mouseX < WindowChrome.controlsLeft(windowX, windowWidth)
			&& mouseY >= windowY && mouseY < windowY + TITLE_HEIGHT;
	}

	private boolean inResizeGrip(double mouseX, double mouseY) {
		return mouseX >= windowX + windowWidth - RESIZE_GRIP && mouseX < windowX + windowWidth
			&& mouseY >= windowY + windowHeight - RESIZE_GRIP && mouseY < windowY + windowHeight;
	}

	private void renderResizeGrip(GuiGraphicsExtractor graphics) {
		if (maximized) return;
		int right = windowX + windowWidth;
		int bottom = windowY + windowHeight;
		graphics.fill(right - 10, bottom - 3, right - 2, bottom - 1, 0xFF9B4DFF);
		graphics.fill(right - 7, bottom - 6, right - 2, bottom - 4, 0xFF9B4DFF);
		graphics.fill(right - 4, bottom - 9, right - 2, bottom - 7, 0xFF9B4DFF);
	}

	private String fit(String value, int maxWidth) {
		if (maxWidth <= 4 || font.width(value) <= maxWidth) return value;
		String suffix = "...";
		return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width(suffix))) + suffix;
	}

	private static String shortName(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}

	private static String safeMessage(Throwable error) {
		String message = error.getMessage();
		if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
		return message.replace('\n', ' ').replace('\r', ' ');
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int color(ConsoleLine.Tone tone) {
		return switch (tone) {
			case MUTED -> 0xFF786A89;
			case INFO -> 0xFFD9CDE7;
			case SUCCESS -> 0xFF5EE38C;
			case WARNING -> 0xFFF7C35F;
			case ERROR -> 0xFFFF6B8A;
			case ACCENT -> 0xFFC56FFF;
		};
	}
}
