package dev.champions.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.champions.client.ChampionsScreens;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.champions.core.ui.MinecraftPointerButtons.PRIMARY;

/** A movable desktop-style terminal for ChampionsCode commands. */
public final class ChampionsConsoleScreen extends Screen implements WorkspaceWindow {
	private static final int TITLE_HEIGHT = 24;
	private static final int PADDING = 10;
	private static final int INPUT_AREA_HEIGHT = 28;
	private static final int LINE_HEIGHT = 10;
	private static final int RESIZE_GRIP = 12;
	private static int savedX = -1;
	private static int savedY = -1;
	private static int savedWidth = -1;
	private static int savedHeight = -1;

	private final ChampionConsole console;
	private WorkspaceHost host;
	private EditBox input;
	private int windowX;
	private int windowY;
	private int windowWidth;
	private int windowHeight;
	private int restoreX;
	private int restoreY;
	private int restoreWidth;
	private int restoreHeight;
	private int scrollOffset;
	private int historyCursor;
	private boolean moving;
	private boolean resizing;
	private boolean minimized;
	private boolean maximized;
	private int dragOffsetX;
	private int dragOffsetY;
	private boolean selecting;
	private int selectionAnchor = -1;
	private int selectionCaret = -1;
	private List<VisualLine> renderedVisual = List.of();
	private int renderedStart;
	private int renderedEnd;
	private int renderedTextY;
	private long copiedNoticeUntil;

	public ChampionsConsoleScreen(ChampionConsole console) {
		super(Component.literal("ChampionsCode Terminal"));
		this.console = console;
	}

	/** When hosted inside the workspace, close/navigation actions go through the host. */
	public void host(WorkspaceHost host) {
		this.host = host;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// No vanilla blur/menu background: the terminal is one window on the workspace desktop.
	}

	@Override
	protected void init() {
		int maximumWidth = Math.min(width, Math.max(260, width - Math.min(32, Math.max(0, width / 6))));
		int maximumHeight = Math.min(height, Math.max(180, height - Math.min(30, Math.max(0, height / 6))));
		int minimumWidth = Math.min(400, maximumWidth);
		int minimumHeight = Math.min(250, maximumHeight);
		if (maximized) {
			windowX = 0;
			windowY = 0;
			windowWidth = width;
			windowHeight = height;
		} else {
			windowWidth = savedWidth < 0 ? Math.min(820, maximumWidth) : clamp(savedWidth, minimumWidth, maximumWidth);
			windowHeight = savedHeight < 0 ? Math.min(500, maximumHeight) : clamp(savedHeight, minimumHeight, maximumHeight);
			windowX = savedX < 0 ? (width - windowWidth) / 2 : clamp(savedX, 0, Math.max(0, width - windowWidth));
			windowY = savedY < 0 ? (height - windowHeight) / 2 : clamp(savedY, 0, Math.max(0, height - windowHeight));
			saveGeometry();
		}

		input = new EditBox(font, inputX(), inputY(), inputWidth(), 18, Component.literal("ChampionsCode command"));
		input.setMaxLength(2048);
		input.setHint(Component.literal("help | show modules | clickgui | categorygui | java status | exit"));
		input.setCanLoseFocus(false);
		input.setBordered(false);
		input.setTextColor(0xFFF1F1F1);
		input.setVisible(!minimized);
		addRenderableWidget(input);
		setInitialFocus(input);
		historyCursor = console.history().size();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		int shownHeight = minimized ? TITLE_HEIGHT : windowHeight;
		graphics.fill(windowX - 4, windowY - 4, windowX + windowWidth + 4, windowY + shownHeight + 4, 0x99000000);
		graphics.fill(windowX, windowY, windowX + windowWidth, windowY + shownHeight, 0xFF090909);
		graphics.fill(windowX, windowY, windowX + windowWidth, windowY + TITLE_HEIGHT, 0xFF252525);
		graphics.fill(windowX, windowY + TITLE_HEIGHT - 1, windowX + windowWidth, windowY + TITLE_HEIGHT, 0xFF353535);
		graphics.fill(windowX + 7, windowY + 7, windowX + 13, windowY + 13, 0xFF58D66B);
		String title = "champions@minecraft: ~";
		graphics.text(font, title, windowX + (windowWidth - font.width(title)) / 2, windowY + 8, 0xFFF0F0F0, false);
		WindowChrome.render(graphics, font, windowX, windowY, windowWidth, TITLE_HEIGHT,
			mouseX, mouseY, 0xFFD5D5D5, maximized);
		if (minimized) {
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}

		graphics.fill(windowX + 4, windowY + TITLE_HEIGHT + 4, windowX + windowWidth - 4,
			windowY + windowHeight - 4, 0xFF090909);
		int outputBottom = windowY + windowHeight - INPUT_AREA_HEIGHT;
		List<VisualLine> visual = buildVisualLines(Math.max(80, windowWidth - PADDING * 2));
		int rows = Math.max(1, (outputBottom - (windowY + TITLE_HEIGHT + 10)) / LINE_HEIGHT);
		int maxOffset = Math.max(0, visual.size() - rows);
		scrollOffset = Math.min(scrollOffset, maxOffset);
		int end = visual.size() - scrollOffset;
		int start = Math.max(0, end - rows);
		int y = windowY + TITLE_HEIGHT + 9;
		renderedVisual = visual;
		renderedStart = start;
		renderedEnd = end;
		renderedTextY = y;
		clampSelection();
		for (int i = start; i < end; i++) {
			VisualLine line = visual.get(i);
			renderSelection(graphics, line, y);
			graphics.text(font, line.text(), windowX + PADDING, y, color(line.tone()), false);
			y += LINE_HEIGHT;
		}

		int promptY = windowY + windowHeight - 19;
		int promptX = windowX + PADDING;
		graphics.text(font, "champions@minecraft", promptX, promptY, 0xFF55D65A, false);
		promptX += font.width("champions@minecraft");
		graphics.text(font, ":~$", promptX, promptY, 0xFFF2F2F2, false);
		if (scrollOffset > 0) {
			String marker = "[" + scrollOffset + " line(s) above]";
			graphics.text(font, marker, windowX + windowWidth - PADDING - font.width(marker),
				windowY + TITLE_HEIGHT + 9, 0xFFE5C35A, false);
		}
		if (System.currentTimeMillis() < copiedNoticeUntil) {
			graphics.text(font, "copied", windowX + windowWidth - PADDING - 38, promptY, 0xFF55D65A, false);
		}
		renderResizeGrip(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private void renderSelection(GuiGraphicsExtractor graphics, VisualLine line, int y) {
		if (!hasSelection()) return;
		int low = Math.min(selectionAnchor, selectionCaret);
		int high = Math.max(selectionAnchor, selectionCaret);
		int lineStart = line.offset();
		int lineEnd = lineStart + line.text().length();
		int selectedStart = Math.max(low, lineStart);
		int selectedEnd = Math.min(high, lineEnd);
		if (selectedStart < selectedEnd) {
			int from = selectedStart - lineStart;
			int to = selectedEnd - lineStart;
			int x1 = windowX + PADDING + font.width(line.text().substring(0, from));
			int x2 = windowX + PADDING + font.width(line.text().substring(0, to));
			graphics.fill(x1, y - 1, Math.max(x1 + 1, x2), y + 9, 0xA06687A8);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isCopy() && hasSelection()) {
			copyTerminalText(false);
			return true;
		}
		if (event.isConfirmation()) {
			submitInput();
			return true;
		}
		if (event.isUp()) {
			navigateHistory(-1);
			return true;
		}
		if (event.isDown()) {
			navigateHistory(1);
			return true;
		}
		if (event.input() == InputConstants.KEY_PAGEUP) {
			scrollOffset += 5;
			return true;
		}
		if (event.input() == InputConstants.KEY_PAGEDOWN) {
			scrollOffset = Math.max(0, scrollOffset - 5);
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
				else if (!maximized) {
					moving = true;
					dragOffsetX = (int) event.x() - windowX;
					dragOffsetY = (int) event.y() - windowY;
					setDragging(true);
				}
				return true;
			}
			if (!minimized && inOutput(event.x(), event.y())) {
				int position = textPositionAt(event.x(), event.y());
				if (doubleClick) {
					VisualLine line = visualLineAt(event.y());
					if (line != null) {
						selectionAnchor = line.offset();
						selectionCaret = line.offset() + line.text().length();
					}
				} else {
					selectionAnchor = position;
					selectionCaret = position;
					selecting = true;
					setDragging(true);
				}
				return true;
			}
			if (!minimized) clearSelection();
		}
		return minimized ? true : super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (resizing) {
			int maxWidth = Math.max(1, width - windowX);
			int maxHeight = Math.max(1, height - windowY);
			windowWidth = clamp((int) event.x() - windowX, Math.min(400, maxWidth), maxWidth);
			windowHeight = clamp((int) event.y() - windowY, Math.min(250, maxHeight), maxHeight);
			saveGeometry();
			clearSelection();
			layoutInput();
			return true;
		}
		if (moving) {
			int shownHeight = minimized ? TITLE_HEIGHT : windowHeight;
			windowX = clamp((int) event.x() - dragOffsetX, 0, Math.max(0, width - windowWidth));
			windowY = clamp((int) event.y() - dragOffsetY, 0, Math.max(0, height - shownHeight));
			saveGeometry();
			layoutInput();
			return true;
		}
		if (selecting) {
			selectionCaret = textPositionAt(event.x(), event.y());
			return true;
		}
		return super.mouseDragged(event, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (moving || resizing || selecting) {
			moving = false;
			resizing = false;
			selecting = false;
			setDragging(false);
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (!insideWindow(mouseX, mouseY) || minimized) return false;
		if (vertical > 0) scrollOffset += 3;
		else if (vertical < 0) scrollOffset = Math.max(0, scrollOffset - 3);
		return true;
	}

	@Override
	public void onClose() {
		if (host != null) {
			host.requestClose(ChampionsScreens.TERMINAL);
			return;
		}
		minecraft.setScreenAndShow(null);
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }

	private void handleWindowControl(WindowChrome.Control control) {
		switch (control) {
			case MINIMIZE -> {
				minimized = !minimized;
				input.setVisible(!minimized);
			}
			case MAXIMIZE -> toggleMaximize();
			case CLOSE -> {
			if (host != null) host.requestClose(ChampionsScreens.TERMINAL);
			else minecraft.setScreenAndShow(null);
		}
			default -> {}
		}
	}

	private void toggleMaximize() {
		if (minimized) {
			minimized = false;
			input.setVisible(true);
		}
		if (!maximized) {
			restoreX = windowX;
			restoreY = windowY;
			restoreWidth = windowWidth;
			restoreHeight = windowHeight;
			windowX = 0;
			windowY = 0;
			windowWidth = width;
			windowHeight = height;
			maximized = true;
		} else {
			maximized = false;
			windowX = restoreX;
			windowY = restoreY;
			windowWidth = restoreWidth;
			windowHeight = restoreHeight;
			saveGeometry();
		}
		clearSelection();
		layoutInput();
	}

	private void layoutInput() {
		input.setX(inputX());
		input.setY(inputY());
		input.setWidth(inputWidth());
	}

	private int inputX() {
		return windowX + PADDING + font.width("champions@minecraft:~$ ");
	}

	private int inputY() {
		// Borderless EditBox renders its text at getY() (see EditBox.updateTextPosition),
		// so the box must start exactly on the prompt line to keep both aligned.
		return windowY + windowHeight - 19;
	}

	private int inputWidth() {
		return Math.max(32, windowX + windowWidth - PADDING - inputX());
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

	private boolean inOutput(double mouseX, double mouseY) {
		return mouseX >= windowX + PADDING && mouseX < windowX + windowWidth - PADDING
			&& mouseY >= windowY + TITLE_HEIGHT + 5 && mouseY < windowY + windowHeight - INPUT_AREA_HEIGHT;
	}

	private boolean insideWindow(double mouseX, double mouseY) {
		int shownHeight = minimized ? TITLE_HEIGHT : windowHeight;
		return mouseX >= windowX && mouseX < windowX + windowWidth
			&& mouseY >= windowY && mouseY < windowY + shownHeight;
	}

	@Override
	public boolean coversPoint(double mouseX, double mouseY) {
		return insideWindow(mouseX, mouseY);
	}

	private void renderResizeGrip(GuiGraphicsExtractor graphics) {
		if (maximized) return;
		int right = windowX + windowWidth;
		int bottom = windowY + windowHeight;
		graphics.fill(right - 10, bottom - 3, right - 2, bottom - 1, 0xFF7C7C7C);
		graphics.fill(right - 7, bottom - 6, right - 2, bottom - 4, 0xFF7C7C7C);
		graphics.fill(right - 4, bottom - 9, right - 2, bottom - 7, 0xFF7C7C7C);
	}

	private void submitInput() {
		String command = input.getValue().strip();
		if (command.isBlank()) return;
		console.submit(command);
		input.setValue("");
		historyCursor = console.history().size();
		scrollOffset = 0;
		clearSelection();
	}

	private void navigateHistory(int direction) {
		List<String> history = console.history();
		if (history.isEmpty()) return;
		historyCursor = Math.max(0, Math.min(history.size(), historyCursor + direction));
		input.setValue(historyCursor == history.size() ? "" : history.get(historyCursor));
		input.moveCursorToEnd(false);
	}

	private List<VisualLine> buildVisualLines(int maxWidth) {
		List<VisualLine> result = new ArrayList<>();
		int offset = 0;
		for (ConsoleLine line : console.lines()) {
			String rest = line.text();
			if (rest.isEmpty()) {
				result.add(new VisualLine(line.tone(), "", offset));
				offset++;
				continue;
			}
			while (!rest.isEmpty()) {
				String part = font.plainSubstrByWidth(rest, maxWidth);
				if (part.isEmpty()) part = rest.substring(0, 1);
				if (part.length() < rest.length()) {
					int space = part.lastIndexOf(' ');
					if (space > 12) part = part.substring(0, space);
				}
				result.add(new VisualLine(line.tone(), part, offset));
				offset += part.length() + 1;
				rest = rest.substring(part.length());
				while (rest.startsWith(" ")) rest = rest.substring(1);
			}
		}
		return result;
	}

	private int textPositionAt(double mouseX, double mouseY) {
		VisualLine line = visualLineAt(mouseY);
		if (line == null) return 0;
		int relativeX = clamp((int) mouseX - (windowX + PADDING), 0, Math.max(0, font.width(line.text())));
		String prefix = font.plainSubstrByWidth(line.text(), relativeX);
		return line.offset() + prefix.length();
	}

	private VisualLine visualLineAt(double mouseY) {
		if (renderedVisual.isEmpty() || renderedStart >= renderedEnd) return null;
		int row = clamp((int) ((mouseY - renderedTextY) / LINE_HEIGHT), 0, renderedEnd - renderedStart - 1);
		return renderedVisual.get(renderedStart + row);
	}

	private void copyTerminalText(boolean allWhenNoSelection) {
		String text = visualText();
		if (text.isEmpty()) return;
		if (hasSelection()) {
			int low = clamp(Math.min(selectionAnchor, selectionCaret), 0, text.length());
			int high = clamp(Math.max(selectionAnchor, selectionCaret), 0, text.length());
			text = text.substring(low, high);
		} else if (!allWhenNoSelection) {
			return;
		}
		minecraft.keyboardHandler.setClipboard(text);
		copiedNoticeUntil = System.currentTimeMillis() + 1_500L;
	}

	private String visualText() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < renderedVisual.size(); i++) {
			if (i > 0) text.append('\n');
			text.append(renderedVisual.get(i).text());
		}
		return text.toString();
	}

	private boolean hasSelection() {
		return selectionAnchor >= 0 && selectionCaret >= 0 && selectionAnchor != selectionCaret;
	}

	private void clearSelection() {
		selectionAnchor = -1;
		selectionCaret = -1;
		selecting = false;
	}

	private void clampSelection() {
		int length = visualTextLength();
		if (selectionAnchor >= 0) selectionAnchor = clamp(selectionAnchor, 0, length);
		if (selectionCaret >= 0) selectionCaret = clamp(selectionCaret, 0, length);
	}

	private int visualTextLength() {
		if (renderedVisual.isEmpty()) return 0;
		VisualLine last = renderedVisual.getLast();
		return last.offset() + last.text().length();
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int color(ConsoleLine.Tone tone) {
		return switch (tone) {
			case MUTED -> 0xFF9A9A9A;
			case INFO -> 0xFFF0F0F0;
			case SUCCESS -> 0xFF55D65A;
			case WARNING -> 0xFFE5C35A;
			case ERROR -> 0xFFFF6868;
			case ACCENT -> 0xFF5F9DFF;
		};
	}

	private record VisualLine(ConsoleLine.Tone tone, String text, int offset) {}
}
