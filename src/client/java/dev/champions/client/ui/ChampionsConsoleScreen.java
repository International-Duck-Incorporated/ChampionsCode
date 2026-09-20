package dev.champions.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.champions.core.ui.MinecraftPointerButtons.PRIMARY;

public final class ChampionsConsoleScreen extends Screen {
	private static final int MARGIN = 12;
	private static final int TITLE_HEIGHT = 28;
	private static final int HEADER_HEIGHT = 58;
	private static final int FOOTER_HEIGHT = 38;
	private static final int LINE_HEIGHT = 10;
	private static final int RESIZE_GRIP = 12;
	private static int savedX = -1;
	private static int savedY = -1;
	private static int savedWidth = -1;
	private static int savedHeight = -1;

	private final ChampionConsole console;
	private EditBox input;
	private Button consoleTab;
	private Button javaTab;
	private Button exploitsTab;
	private Button copyButton;
	private Button clearButton;
	private Button closeButton;
	private WorkspaceTab activeTab = WorkspaceTab.CONSOLE;
	private int windowX;
	private int windowY;
	private int windowWidth;
	private int windowHeight;
	private int scrollOffset;
	private int historyCursor;
	private boolean moving;
	private boolean resizing;
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
		super(Component.literal("Champions Security Research Console"));
		this.console = console;
	}

	@Override
	protected void init() {
		int maximumWidth = Math.max(220, width - Math.min(32, Math.max(0, width / 6)));
		int maximumHeight = Math.max(180, height - Math.min(30, Math.max(0, height / 6)));
		maximumWidth = Math.min(width, maximumWidth);
		maximumHeight = Math.min(height, maximumHeight);
		int minimumWidth = Math.min(360, maximumWidth);
		int minimumHeight = Math.min(240, maximumHeight);
		int defaultWidth = Math.min(900, maximumWidth);
		int defaultHeight = Math.min(540, maximumHeight);
		windowWidth = savedWidth < 0 ? defaultWidth : clamp(savedWidth, minimumWidth, maximumWidth);
		windowHeight = savedHeight < 0 ? defaultHeight : clamp(savedHeight, minimumHeight, maximumHeight);
		if (savedX < 0 || savedY < 0) {
			windowX = (width - windowWidth) / 2;
			windowY = (height - windowHeight) / 2;
		} else {
			windowX = clamp(savedX, 0, Math.max(0, width - windowWidth));
			windowY = clamp(savedY, 0, Math.max(0, height - windowHeight));
		}
		saveGeometry();

		int inputY = windowY + windowHeight - 27;
		int inputWidth = Math.max(40, windowWidth - 296);
		input = new EditBox(font, windowX + MARGIN + 82, inputY, inputWidth, 18, Component.literal("Champions command"));
		input.setMaxLength(2048);
		updateInputHint();
		input.setCanLoseFocus(false);
		addRenderableWidget(input);
		setInitialFocus(input);
		historyCursor = console.history().size();

		consoleTab = addRenderableWidget(Button.builder(Component.literal("Console"), button -> selectTab(WorkspaceTab.CONSOLE))
			.bounds(windowX + MARGIN, windowY + 34, 72, 18).build());
		javaTab = addRenderableWidget(Button.builder(Component.literal("Java"), button -> selectTab(WorkspaceTab.JAVA))
			.bounds(windowX + MARGIN + 78, windowY + 34, 72, 18).build());
		exploitsTab = addRenderableWidget(Button.builder(Component.literal("Exploits"), button -> requestExploitTab())
			.bounds(windowX + MARGIN + 156, windowY + 34, 82, 18).build());
		copyButton = addRenderableWidget(Button.builder(Component.literal("Copy"), button -> copyTerminalText(true))
			.bounds(windowX + windowWidth - 196, inputY, 54, 18).build());
		clearButton = addRenderableWidget(Button.builder(Component.literal("Clear"), button -> {
			console.submit("clear");
			scrollOffset = 0;
			clearSelection();
		}).bounds(windowX + windowWidth - 136, inputY, 54, 18).build());
		closeButton = addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
			.bounds(windowX + windowWidth - 76, inputY, 64, 18).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0x33000000);
		graphics.fill(windowX - 3, windowY - 3, windowX + windowWidth + 3, windowY + windowHeight + 3, 0x88000000);
		graphics.fill(windowX, windowY, windowX + windowWidth, windowY + windowHeight, 0xF2070A12);
		graphics.fill(windowX, windowY, windowX + windowWidth, windowY + HEADER_HEIGHT, 0xFF0B1726);
		graphics.fill(windowX, windowY + TITLE_HEIGHT - 1, windowX + windowWidth, windowY + TITLE_HEIGHT, 0xFF24435D);
		graphics.fill(windowX, windowY + HEADER_HEIGHT - 2, windowX + windowWidth, windowY + HEADER_HEIGHT, 0xFF18D9C7);
		int activeTabX = windowX + MARGIN + switch (activeTab) {
			case CONSOLE -> 0;
			case JAVA -> 78;
			case EXPLOITS -> 156;
		};
		int activeTabWidth = activeTab == WorkspaceTab.EXPLOITS ? 82 : 72;
		graphics.fill(activeTabX, windowY + 52, activeTabX + activeTabWidth, windowY + 54, 0xFFFBBF24);
		graphics.fill(windowX, windowY + windowHeight - FOOTER_HEIGHT, windowX + windowWidth, windowY + windowHeight, 0xFF0B1726);
		graphics.fill(windowX + MARGIN - 4, windowY + HEADER_HEIGHT + 5,
			windowX + windowWidth - MARGIN + 4, windowY + windowHeight - FOOTER_HEIGHT - 4, 0xB30B1220);

		graphics.text(font, "CHAMPIONS TERMINAL", windowX + MARGIN, windowY + 9, 0xFF18D9C7, false);
		graphics.text(font, "drag title | resize corner", windowX + windowWidth - 133, windowY + 9, 0xFF7E8A9E, false);
		String modeLine = switch (activeTab) {
			case JAVA -> "RUNTIME JAVA | UNRESTRICTED | JDK 25 | HASH-GATED";
			case EXPLOITS -> "EXPLOIT RESEARCH | LEGAL RISK | AUTHORIZATION-GATED | AUDITED";
			case CONSOLE -> "DEFENSIVE RESEARCH | CURRENT CONNECTION | AUDITED";
		};
		int modeColor = activeTab == WorkspaceTab.CONSOLE ? 0xFF6EE7B7 : 0xFFFB7185;
		if (windowWidth > 330) {
			graphics.text(font, fit(modeLine, Math.max(40, windowWidth - 270)), windowX + 258, windowY + 39,
				modeColor, false);
		}
		graphics.text(font, activeTab == WorkspaceTab.JAVA ? "java >" : activeTab == WorkspaceTab.EXPLOITS ? "exploits >" : "champions >",
			windowX + MARGIN, windowY + windowHeight - 22, 0xFFFBBF24, false);

		List<VisualLine> visual = buildVisualLines(Math.max(80, windowWidth - MARGIN * 2 - 10));
		int rows = Math.max(1, (windowHeight - HEADER_HEIGHT - FOOTER_HEIGHT - 14) / LINE_HEIGHT);
		int maxOffset = Math.max(0, visual.size() - rows);
		scrollOffset = Math.min(scrollOffset, maxOffset);
		int end = visual.size() - scrollOffset;
		int start = Math.max(0, end - rows);
		int y = windowY + HEADER_HEIGHT + 9;
		renderedVisual = visual;
		renderedStart = start;
		renderedEnd = end;
		renderedTextY = y;
		clampSelection();
		for (int i = start; i < end; i++) {
			VisualLine line = visual.get(i);
			renderSelection(graphics, line, y);
			graphics.text(font, line.text(), windowX + MARGIN, y, color(line.tone()), false);
			y += LINE_HEIGHT;
		}
		if (scrollOffset > 0) {
			graphics.text(font, "up: " + scrollOffset + " line(s)", windowX + windowWidth - 116,
				windowY + HEADER_HEIGHT + 8, 0xFFFBBF24, false);
		}
		if (System.currentTimeMillis() < copiedNoticeUntil) {
			graphics.text(font, "Copied", windowX + windowWidth - 240, windowY + windowHeight - 22, 0xFF6EE7B7, false);
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
			int x1 = windowX + MARGIN + font.width(line.text().substring(0, from));
			int x2 = windowX + MARGIN + font.width(line.text().substring(0, to));
			graphics.fill(x1, y - 1, Math.max(x1 + 1, x2), y + 9, 0xA04B6B8A);
		}
	}

	private void renderResizeGrip(GuiGraphicsExtractor graphics) {
		int right = windowX + windowWidth;
		int bottom = windowY + windowHeight;
		graphics.fill(right - 10, bottom - 3, right - 2, bottom - 1, 0xFF18D9C7);
		graphics.fill(right - 7, bottom - 6, right - 2, bottom - 4, 0xFF18D9C7);
		graphics.fill(right - 4, bottom - 9, right - 2, bottom - 7, 0xFF18D9C7);
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
		if (event.button() == PRIMARY && inResizeGrip(event.x(), event.y())) {
			resizing = true;
			setDragging(true);
			return true;
		}
		if (event.button() == PRIMARY && inTitleBar(event.x(), event.y())) {
			moving = true;
			dragOffsetX = (int) event.x() - windowX;
			dragOffsetY = (int) event.y() - windowY;
			setDragging(true);
			return true;
		}
		if (event.button() == PRIMARY && inOutput(event.x(), event.y())) {
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
		if (event.button() == PRIMARY) clearSelection();
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (resizing) {
			int maxWidth = Math.max(1, width - windowX);
			int maxHeight = Math.max(1, height - windowY);
			windowWidth = clamp((int) event.x() - windowX, Math.min(360, maxWidth), maxWidth);
			windowHeight = clamp((int) event.y() - windowY, Math.min(240, maxHeight), maxHeight);
			saveGeometry();
			clearSelection();
			layoutWidgets();
			return true;
		}
		if (moving) {
			windowX = clamp((int) event.x() - dragOffsetX, 0, Math.max(0, width - windowWidth));
			windowY = clamp((int) event.y() - dragOffsetY, 0, Math.max(0, height - windowHeight));
			saveGeometry();
			layoutWidgets();
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
		if (!insideWindow(mouseX, mouseY)) return false;
		if (vertical > 0) scrollOffset += 3;
		else if (vertical < 0) scrollOffset = Math.max(0, scrollOffset - 3);
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(null);
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }

	private void layoutWidgets() {
		int inputY = windowY + windowHeight - 27;
		consoleTab.setX(windowX + MARGIN);
		consoleTab.setY(windowY + 34);
		javaTab.setX(windowX + MARGIN + 78);
		javaTab.setY(windowY + 34);
		exploitsTab.setX(windowX + MARGIN + 156);
		exploitsTab.setY(windowY + 34);
		input.setX(windowX + MARGIN + 82);
		input.setY(inputY);
		input.setWidth(Math.max(40, windowWidth - 296));
		copyButton.setX(windowX + windowWidth - 196);
		copyButton.setY(inputY);
		clearButton.setX(windowX + windowWidth - 136);
		clearButton.setY(inputY);
		closeButton.setX(windowX + windowWidth - 76);
		closeButton.setY(inputY);
	}

	private void saveGeometry() {
		savedX = windowX;
		savedY = windowY;
		savedWidth = windowWidth;
		savedHeight = windowHeight;
	}

	private boolean inTitleBar(double mouseX, double mouseY) {
		return mouseX >= windowX && mouseX < windowX + windowWidth
			&& mouseY >= windowY && mouseY < windowY + TITLE_HEIGHT;
	}

	private boolean inResizeGrip(double mouseX, double mouseY) {
		return mouseX >= windowX + windowWidth - RESIZE_GRIP && mouseX < windowX + windowWidth
			&& mouseY >= windowY + windowHeight - RESIZE_GRIP && mouseY < windowY + windowHeight;
	}

	private boolean inOutput(double mouseX, double mouseY) {
		return mouseX >= windowX + MARGIN - 4 && mouseX < windowX + windowWidth - MARGIN + 4
			&& mouseY >= windowY + HEADER_HEIGHT + 5 && mouseY < windowY + windowHeight - FOOTER_HEIGHT - 4;
	}

	private boolean insideWindow(double mouseX, double mouseY) {
		return mouseX >= windowX && mouseX < windowX + windowWidth
			&& mouseY >= windowY && mouseY < windowY + windowHeight;
	}

	private void selectTab(WorkspaceTab tab) {
		if (tab == WorkspaceTab.EXPLOITS) {
			requestExploitTab();
			return;
		}
		activateTab(tab);
	}

	private void requestExploitTab() {
		if (activeTab == WorkspaceTab.EXPLOITS) return;
		CurrentTargetNotice target = currentTargetNotice();
		minecraft.setScreenAndShow(ExploitWarningScreen.forEntry(this, "Champions Exploits tab", () -> {
			activateTab(WorkspaceTab.EXPLOITS);
			console.recordAudit("exploit_workspace", target.target(), "workspace", "acknowledged",
				"Legal-risk warning acknowledged on terminal tab entry");
			console.emit(ConsoleLine.warning("Exploit workspace warning acknowledged. Every module run requires another popup."));
		}, () -> console.recordAudit("exploit_workspace", target.target(), "workspace", "cancelled",
			"Legal-risk warning declined on terminal tab entry")));
	}

	private void activateTab(WorkspaceTab tab) {
		activeTab = tab;
		if (input != null) input.setValue("");
		clearSelection();
		updateInputHint();
		if (input != null) setFocused(input);
	}

	private CurrentTargetNotice currentTargetNotice() {
		ChampionConsole.CurrentTargetView target = console.currentTargetView();
		return new CurrentTargetNotice(target.available() ? target.target() : "unknown");
	}

	private void updateInputHint() {
		if (input == null) return;
		String hint = switch (activeTab) {
			case JAVA -> "status | hash | confirm <sha256> | compile | start | reload | stop";
			case EXPLOITS -> "show exploits | use auxiliary/exploits/research_workspace | info | run";
			case CONSOLE -> "clickgui | help";
		};
		input.setHint(Component.literal(hint));
	}

	private void submitInput() {
		String command = input.getValue().strip();
		if (command.isBlank()) return;
		if (activeTab == WorkspaceTab.JAVA) console.submit(toJavaCommand(command));
		else console.submit(command);
		input.setValue("");
		historyCursor = console.history().size();
		scrollOffset = 0;
		clearSelection();
	}

	private static String toJavaCommand(String input) {
		String lower = input.toLowerCase(java.util.Locale.ROOT);
		return lower.equals("java") || lower.startsWith("java ") ? input : "java " + input;
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
		int relativeX = clamp((int) mouseX - (windowX + MARGIN), 0, Math.max(0, font.width(line.text())));
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

	private String fit(String value, int maxWidth) {
		if (font.width(value) <= maxWidth) return value;
		String suffix = "...";
		return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width(suffix))) + suffix;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int color(ConsoleLine.Tone tone) {
		return switch (tone) {
			case MUTED -> 0xFF7E8A9E;
			case INFO -> 0xFFD8E2F0;
			case SUCCESS -> 0xFF6EE7B7;
			case WARNING -> 0xFFFBBF24;
			case ERROR -> 0xFFFB7185;
			case ACCENT -> 0xFF22D3EE;
		};
	}

	private enum WorkspaceTab { CONSOLE, JAVA, EXPLOITS }

	private record CurrentTargetNotice(String target) {}
	private record VisualLine(ConsoleLine.Tone tone, String text, int offset) {}
}
