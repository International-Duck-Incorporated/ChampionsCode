package dev.champions.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class ChampionsConsoleScreen extends Screen {
	private static final int MARGIN = 12;
	private static final int HEADER_HEIGHT = 54;
	private static final int FOOTER_HEIGHT = 38;
	private static final int LINE_HEIGHT = 10;
	private final ChampionConsole console;
	private EditBox input;
	private WorkspaceTab activeTab = WorkspaceTab.CONSOLE;
	private int scrollOffset;
	private int historyCursor;

	public ChampionsConsoleScreen(ChampionConsole console) {
		super(Component.literal("Champions Security Research Console"));
		this.console = console;
	}

	@Override
	protected void init() {
		int inputY = height - 27;
		input = new EditBox(font, MARGIN + 82, inputY, Math.max(120, width - 236), 18, Component.literal("Champions command"));
		input.setMaxLength(2048);
		updateInputHint();
		input.setCanLoseFocus(false);
		addRenderableWidget(input);
		setInitialFocus(input);
		historyCursor = console.history().size();

		addRenderableWidget(Button.builder(Component.literal("Console"), button -> selectTab(WorkspaceTab.CONSOLE))
			.bounds(MARGIN, 32, 72, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Java"), button -> selectTab(WorkspaceTab.JAVA))
			.bounds(MARGIN + 78, 32, 72, 18).build());

		addRenderableWidget(Button.builder(Component.literal("Clear"), button -> {
			console.submit("clear");
			scrollOffset = 0;
		}).bounds(width - 136, inputY, 54, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
			.bounds(width - 76, inputY, 64, 18).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0xF2070A12);
		graphics.fill(0, 0, width, HEADER_HEIGHT, 0xFF0B1726);
		graphics.fill(0, HEADER_HEIGHT - 2, width, HEADER_HEIGHT, 0xFF18D9C7);
		graphics.fill(activeTab == WorkspaceTab.CONSOLE ? MARGIN : MARGIN + 78, 50,
			activeTab == WorkspaceTab.CONSOLE ? MARGIN + 72 : MARGIN + 150, 52, 0xFFFBBF24);
		graphics.fill(0, height - FOOTER_HEIGHT, width, height, 0xFF0B1726);
		graphics.fill(MARGIN - 4, HEADER_HEIGHT + 5, width - MARGIN + 4, height - FOOTER_HEIGHT - 4, 0xB30B1220);

		graphics.text(font, "CHAMPIONS", MARGIN, 8, 0xFF18D9C7, false);
		graphics.text(font, "DEFENSIVE SECURITY RESEARCH  /  MC 26.3", MARGIN + 70, 8, 0xFFA8B3C7, false);
		String modeLine = activeTab == WorkspaceTab.JAVA
			? "RUNTIME JAVA  •  UNRESTRICTED  •  JDK 25  •  HASH-GATED"
			: "PASSIVE  •  CURRENT CONNECTION ONLY  •  AUDITED";
		graphics.text(font, modeLine, MARGIN, 20, activeTab == WorkspaceTab.JAVA ? 0xFFFB7185 : 0xFF6EE7B7, false);
		graphics.text(font, activeTab == WorkspaceTab.JAVA ? "java >" : console.prompt() + " >",
			MARGIN, height - 22, 0xFFFBBF24, false);

		List<VisualLine> visual = buildVisualLines(Math.max(80, width - MARGIN * 2 - 10));
		int rows = Math.max(1, (height - HEADER_HEIGHT - FOOTER_HEIGHT - 14) / LINE_HEIGHT);
		int maxOffset = Math.max(0, visual.size() - rows);
		scrollOffset = Math.min(scrollOffset, maxOffset);
		int end = visual.size() - scrollOffset;
		int start = Math.max(0, end - rows);
		int y = HEADER_HEIGHT + 9;
		for (int i = start; i < end; i++) {
			VisualLine line = visual.get(i);
			graphics.text(font, line.text(), MARGIN, y, color(line.tone()), false);
			y += LINE_HEIGHT;
		}
		if (scrollOffset > 0) graphics.text(font, "↑ " + scrollOffset + " line(s) below", width - 120, HEADER_HEIGHT + 8, 0xFFFBBF24, false);
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
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
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
			: "help"));
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
