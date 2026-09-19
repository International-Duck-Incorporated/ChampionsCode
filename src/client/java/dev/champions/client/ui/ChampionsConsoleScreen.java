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

public final class ChampionsConsoleScreen extends Screen {
	private static final int MARGIN = 12;
	private static final int TITLE_HEIGHT = 28;
	private static final int HEADER_HEIGHT = 58;
	private static final int FOOTER_HEIGHT = 38;
	private static final int LINE_HEIGHT = 10;
	private static int savedX = -1;
	private static int savedY = -1;

	private final ChampionConsole console;
	private EditBox input;
	private Button consoleTab;
	private Button javaTab;
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
	private int dragOffsetX;
	private int dragOffsetY;

	public ChampionsConsoleScreen(ChampionConsole console) {
		super(Component.literal("Champions Security Research Console"));
		this.console = console;
	}

	@Override
	protected void init() {
		windowWidth = Math.min(900, Math.max(300, width - 32));
		windowHeight = Math.min(540, Math.max(210, height - 30));
		if (savedX < 0 || savedY < 0) {
			windowX = (width - windowWidth) / 2;
			windowY = (height - windowHeight) / 2;
		} else {
			windowX = clamp(savedX, 0, Math.max(0, width - windowWidth));
			windowY = clamp(savedY, 0, Math.max(0, height - windowHeight));
		}

		int inputY = windowY + windowHeight - 27;
		int inputWidth = Math.max(62, windowWidth - 236);
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
		clearButton = addRenderableWidget(Button.builder(Component.literal("Clear"), button -> {
			console.submit("clear");
			scrollOffset = 0;
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
		graphics.fill(activeTab == WorkspaceTab.CONSOLE ? windowX + MARGIN : windowX + MARGIN + 78, windowY + 52,
			activeTab == WorkspaceTab.CONSOLE ? windowX + MARGIN + 72 : windowX + MARGIN + 150, windowY + 54, 0xFFFBBF24);
		graphics.fill(windowX, windowY + windowHeight - FOOTER_HEIGHT, windowX + windowWidth, windowY + windowHeight, 0xFF0B1726);
		graphics.fill(windowX + MARGIN - 4, windowY + HEADER_HEIGHT + 5,
			windowX + windowWidth - MARGIN + 4, windowY + windowHeight - FOOTER_HEIGHT - 4, 0xB30B1220);

		graphics.text(font, "CHAMPIONS TERMINAL", windowX + MARGIN, windowY + 9, 0xFF18D9C7, false);
		graphics.text(font, "drag title bar", windowX + windowWidth - 80, windowY + 9, 0xFF7E8A9E, false);
		String modeLine = activeTab == WorkspaceTab.JAVA
			? "RUNTIME JAVA | UNRESTRICTED | JDK 25 | HASH-GATED"
			: "DEFENSIVE RESEARCH | CURRENT CONNECTION | AUDITED";
		graphics.text(font, fit(modeLine, Math.max(40, windowWidth - 176)), windowX + 164, windowY + 39,
			activeTab == WorkspaceTab.JAVA ? 0xFFFB7185 : 0xFF6EE7B7, false);
		graphics.text(font, activeTab == WorkspaceTab.JAVA ? "java >" : "champions >",
			windowX + MARGIN, windowY + windowHeight - 22, 0xFFFBBF24, false);

		List<VisualLine> visual = buildVisualLines(Math.max(80, windowWidth - MARGIN * 2 - 10));
		int rows = Math.max(1, (windowHeight - HEADER_HEIGHT - FOOTER_HEIGHT - 14) / LINE_HEIGHT);
		int maxOffset = Math.max(0, visual.size() - rows);
		scrollOffset = Math.min(scrollOffset, maxOffset);
		int end = visual.size() - scrollOffset;
		int start = Math.max(0, end - rows);
		int y = windowY + HEADER_HEIGHT + 9;
		for (int i = start; i < end; i++) {
			VisualLine line = visual.get(i);
			graphics.text(font, line.text(), windowX + MARGIN, y, color(line.tone()), false);
			y += LINE_HEIGHT;
		}
		if (scrollOffset > 0) {
			graphics.text(font, "up: " + scrollOffset + " line(s)", windowX + windowWidth - 116,
				windowY + HEADER_HEIGHT + 8, 0xFFFBBF24, false);
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
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
		if (event.button() == 0 && inTitleBar(event.x(), event.y())) {
			moving = true;
			dragOffsetX = (int) event.x() - windowX;
			dragOffsetY = (int) event.y() - windowY;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (moving) {
			windowX = clamp((int) event.x() - dragOffsetX, 0, Math.max(0, width - windowWidth));
			windowY = clamp((int) event.y() - dragOffsetY, 0, Math.max(0, height - windowHeight));
			savedX = windowX;
			savedY = windowY;
			layoutWidgets();
			return true;
		}
		return super.mouseDragged(event, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (moving) {
			moving = false;
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
		input.setX(windowX + MARGIN + 82);
		input.setY(inputY);
		clearButton.setX(windowX + windowWidth - 136);
		clearButton.setY(inputY);
		closeButton.setX(windowX + windowWidth - 76);
		closeButton.setY(inputY);
	}

	private boolean inTitleBar(double mouseX, double mouseY) {
		return mouseX >= windowX && mouseX < windowX + windowWidth
			&& mouseY >= windowY && mouseY < windowY + TITLE_HEIGHT;
	}

	private boolean insideWindow(double mouseX, double mouseY) {
		return mouseX >= windowX && mouseX < windowX + windowWidth
			&& mouseY >= windowY && mouseY < windowY + windowHeight;
	}

	private void selectTab(WorkspaceTab tab) {
		activeTab = tab;
		input.setValue("");
		updateInputHint();
		setFocused(input);
	}

	private void updateInputHint() {
		if (input == null) return;
		input.setHint(Component.literal(activeTab == WorkspaceTab.JAVA
			? "status | hash | confirm <sha256> | compile | start | reload | stop"
			: "clickgui | help"));
	}

	private void submitInput() {
		String command = input.getValue().strip();
		if (command.isBlank()) return;
		if (activeTab == WorkspaceTab.JAVA) console.submit(toJavaCommand(command));
		else console.submit(command);
		input.setValue("");
		historyCursor = console.history().size();
		scrollOffset = 0;
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
		for (ConsoleLine line : console.lines()) {
			String rest = line.text();
			if (rest.isEmpty()) { result.add(new VisualLine(line.tone(), "")); continue; }
			while (!rest.isEmpty()) {
				String part = font.plainSubstrByWidth(rest, maxWidth);
				if (part.isEmpty()) part = rest.substring(0, 1);
				if (part.length() < rest.length()) {
					int space = part.lastIndexOf(' ');
					if (space > 12) part = part.substring(0, space);
				}
				result.add(new VisualLine(line.tone(), part));
				rest = rest.substring(part.length());
				while (rest.startsWith(" ")) rest = rest.substring(1);
			}
		}
		return result;
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

	private enum WorkspaceTab { CONSOLE, JAVA }

	private record VisualLine(ConsoleLine.Tone tone, String text) {}
}
