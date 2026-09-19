package dev.champions.client.ui;

import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ChampionConsole.ActiveModuleView;
import dev.champions.core.console.ChampionConsole.ModuleView;
import dev.champions.core.console.ChampionConsole.OptionView;
import dev.champions.core.console.ConsoleLine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class ChampionsClickGuiScreen extends Screen {
	private static final int TITLE_HEIGHT = 30;
	private static final int ACTION_HEIGHT = 60;
	private static final int MODULE_ROW_HEIGHT = 21;
	private static final int OPTION_ROW_HEIGHT = 36;
	private static final int RESIZE_GRIP = 12;
	private static int savedX = -1;
	private static int savedY = -1;
	private static int savedWidth = -1;
	private static int savedHeight = -1;

	private final ChampionConsole console;
	private final Screen parent;
	private final List<AbstractWidget> windowWidgets = new ArrayList<>();
	private int windowX;
	private int windowY;
	private int windowWidth;
	private int windowHeight;
	private int modulePanelWidth;
	private int moduleScroll;
	private int optionScroll;
	private boolean moving;
	private boolean resizing;
	private int dragOffsetX;
	private int dragOffsetY;
	private ConsoleLine observedConsoleTail;
	private ConsoleLine.Tone lastActionTone = ConsoleLine.Tone.INFO;
	private String lastAction = "Select a module, configure its options, then press Run now.";

	public ChampionsClickGuiScreen(ChampionConsole console, Screen parent) {
		super(Component.literal("Champions ClickGUI"));
		this.console = console;
		this.parent = parent;
		List<ConsoleLine> lines = console.lines();
		this.observedConsoleTail = lines.isEmpty() ? null : lines.getLast();
	}

	@Override
	protected void init() {
		windowWidgets.clear();
		int defaultWidth = Math.min(920, Math.max(440, width - 30));
		int defaultHeight = Math.min(560, Math.max(300, height - 28));
		int minimumWidth = Math.min(440, width);
		int minimumHeight = Math.min(300, height);
		windowWidth = savedWidth < 0 ? Math.min(defaultWidth, width) : clamp(savedWidth, minimumWidth, width);
		windowHeight = savedHeight < 0 ? Math.min(defaultHeight, height) : clamp(savedHeight, minimumHeight, height);
		modulePanelWidth = Math.min(280, Math.max(185, windowWidth / 3));
		if (savedX < 0 || savedY < 0) {
			windowX = (width - windowWidth) / 2;
			windowY = (height - windowHeight) / 2;
		} else {
			windowX = clamp(savedX, 0, Math.max(0, width - windowWidth));
			windowY = clamp(savedY, 0, Math.max(0, height - windowHeight));
		}
		saveGeometry();

		buildModuleButtons();
		buildOptionWidgets();
		buildActionButtons();
	}

	private void buildModuleButtons() {
		List<ModuleView> modules = console.moduleViews();
		int visibleRows = visibleModuleRows();
		moduleScroll = clamp(moduleScroll, 0, Math.max(0, modules.size() - visibleRows));
		int end = Math.min(modules.size(), moduleScroll + visibleRows);
		for (int i = moduleScroll; i < end; i++) {
			ModuleView module = modules.get(i);
			int row = i - moduleScroll;
			String label = (module.selected() ? "> " : "") + category(module.id()) + " | " + module.name();
			track(Button.builder(Component.literal(label), button -> {
				console.selectModuleFromUi(module.id());
				optionScroll = 0;
				setActivity("Selected " + module.id());
				rebuildWidgets();
			}).bounds(windowX + 10, windowY + 52 + row * MODULE_ROW_HEIGHT,
				modulePanelWidth - 20, 18).build());
		}
	}

	private void buildOptionWidgets() {
		ActiveModuleView active = console.activeModuleView();
		if (active == null) return;
		List<OptionView> options = active.options();
		int visibleRows = visibleOptionRows();
		optionScroll = clamp(optionScroll, 0, Math.max(0, options.size() - visibleRows));
		int end = Math.min(options.size(), optionScroll + visibleRows);
		int rightX = windowX + modulePanelWidth + 18;
		int rightWidth = windowWidth - modulePanelWidth - 30;
		for (int i = optionScroll; i < end; i++) {
			OptionView option = options.get(i);
			int row = i - optionScroll;
			int y = windowY + 108 + row * OPTION_ROW_HEIGHT;
			int editorOffset = Math.min(112, rightWidth / 3);
			if (option.secret()) {
				String label = option.configured() ? "Armed - click to clear" : "Arm authorization";
				Button arm = Button.builder(Component.literal(label), button -> {
					boolean armNow = !option.configured();
					console.setOptionFromUi(option.name(), armNow ? "I_HAVE_AUTHORIZATION" : "");
					setActivity(armNow ? "Authorization confirmation armed for one run." : "Authorization confirmation cleared.");
					rebuildWidgets();
				}).bounds(rightX + editorOffset, y, Math.max(64, rightWidth - editorOffset), 18).build();
				track(arm);
			} else {
				int editorX = rightX + editorOffset;
				EditBox editor = new EditBox(font, editorX, y, Math.max(64, rightX + rightWidth - editorX), 18,
					Component.literal(option.name()));
				editor.setMaxLength(256);
				editor.setValue(option.value());
				editor.setHint(Component.literal("<unset>"));
				editor.setResponder(value -> console.setOptionFromUi(option.name(), value));
				track(editor);
			}
		}
	}

	private void buildActionButtons() {
		ActiveModuleView active = console.activeModuleView();
		int y = windowY + windowHeight - 25;
		int x = windowX + 10;
		Button run = track(Button.builder(Component.literal("Run now"), button -> {
			ActiveModuleView selected = console.activeModuleView();
			setActivity(selected == null ? "No module selected." : "Run requested for " + selected.id());
			console.runFromUi();
			rebuildWidgets();
		}).bounds(x, y, 66, 18).build());
		run.active = active != null && console.runningModuleId().isBlank();
		x += 72;
		Button back = track(Button.builder(Component.literal("Back"), button -> {
			console.clearSelectionFromUi();
			optionScroll = 0;
			setActivity("Module selection cleared.");
			rebuildWidgets();
		}).bounds(x, y, 54, 18).build());
		back.active = active != null;
		x += 60;
		track(Button.builder(Component.literal("Reload"), button -> {
			console.reloadFromUi();
			setActivity("Authorization policy reloaded.");
		}).bounds(x, y, 62, 18).build());
		track(Button.builder(Component.literal("Terminal"), button -> returnToTerminal())
			.bounds(windowX + windowWidth - 142, y, 70, 18).build());
		track(Button.builder(Component.literal("Close"), button -> minecraft.setScreenAndShow(null))
			.bounds(windowX + windowWidth - 66, y, 56, 18).build());
	}

	private <T extends AbstractWidget> T track(T widget) {
		windowWidgets.add(widget);
		return addRenderableWidget(widget);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0x33000000);
		graphics.fill(windowX - 3, windowY - 3, windowX + windowWidth + 3, windowY + windowHeight + 3, 0x88000000);
		graphics.fill(windowX, windowY, windowX + windowWidth, windowY + windowHeight, 0xF20A0F18);
		graphics.fill(windowX, windowY, windowX + windowWidth, windowY + TITLE_HEIGHT, 0xFF0B1726);
		graphics.fill(windowX, windowY + TITLE_HEIGHT - 2, windowX + windowWidth, windowY + TITLE_HEIGHT, 0xFF18D9C7);
		graphics.fill(windowX + modulePanelWidth, windowY + TITLE_HEIGHT,
			windowX + modulePanelWidth + 1, windowY + windowHeight - ACTION_HEIGHT, 0xFF24435D);
		graphics.fill(windowX, windowY + windowHeight - ACTION_HEIGHT,
			windowX + windowWidth, windowY + windowHeight, 0xFF0B1726);

		graphics.text(font, "CHAMPIONS CLICKGUI", windowX + 10, windowY + 10, 0xFF18D9C7, false);
		graphics.text(font, "drag title | resize corner", windowX + windowWidth - 133, windowY + 10, 0xFF7E8A9E, false);
		graphics.text(font, "MODULES - SORTED BY CATEGORY", windowX + 10, windowY + 37, 0xFFFBBF24, false);
		List<ModuleView> modules = console.moduleViews();
		if (modules.size() > visibleModuleRows()) {
			graphics.text(font, (moduleScroll + 1) + "-" + Math.min(modules.size(), moduleScroll + visibleModuleRows())
				+ "/" + modules.size(), windowX + modulePanelWidth - 50, windowY + 37, 0xFF7E8A9E, false);
		}

		ActiveModuleView active = console.activeModuleView();
		int rightX = windowX + modulePanelWidth + 18;
		int rightWidth = windowWidth - modulePanelWidth - 30;
		if (active == null) {
			graphics.text(font, "Select a module from the sorted list.", rightX, windowY + 48, 0xFFD8E2F0, false);
			graphics.text(font, "Options and authorization controls appear here.", rightX, windowY + 64, 0xFF7E8A9E, false);
		} else {
			graphics.text(font, active.name(), rightX, windowY + 42, 0xFF22D3EE, false);
			graphics.text(font, fit(active.id(), rightWidth), rightX, windowY + 55, 0xFFFBBF24, false);
			graphics.text(font, fit(active.description(), rightWidth), rightX, windowY + 69, 0xFFA8B3C7, false);
			graphics.text(font, "OPTIONS", rightX, windowY + 91, 0xFFFBBF24, false);
			renderOptionLabels(graphics, active, rightX, rightWidth);
		}

		renderRunStatus(graphics);
		renderResizeGrip(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private void renderOptionLabels(GuiGraphicsExtractor graphics, ActiveModuleView active, int rightX, int rightWidth) {
		List<OptionView> options = active.options();
		int visibleRows = visibleOptionRows();
		int end = Math.min(options.size(), optionScroll + visibleRows);
		for (int i = optionScroll; i < end; i++) {
			OptionView option = options.get(i);
			int row = i - optionScroll;
			int y = windowY + 111 + row * OPTION_ROW_HEIGHT;
			graphics.text(font, option.name() + (option.required() ? " *" : ""), rightX, y, 0xFFD8E2F0, false);
			graphics.text(font, fit(option.description(), rightWidth - 4), rightX, y + 20, 0xFF7E8A9E, false);
		}
		if (options.size() > visibleRows) {
			graphics.text(font, "Scroll for options " + (optionScroll + 1) + "-" + end + "/" + options.size(),
				rightX + rightWidth - 130, windowY + 91, 0xFF7E8A9E, false);
		}
	}

	private void renderRunStatus(GuiGraphicsExtractor graphics) {
		String running = console.runningModuleId();
		String status = running.isBlank() ? "STATUS: IDLE" : "STATUS: RUNNING " + running;
		int statusColor = running.isBlank() ? 0xFF6EE7B7 : 0xFFFBBF24;
		graphics.text(font, fit(status, windowWidth - 20), windowX + 10, windowY + windowHeight - 52, statusColor, false);
		List<ConsoleLine> lines = console.lines();
		ConsoleLine latest = lines.isEmpty() ? null : lines.getLast();
		if (latest != null && !latest.equals(observedConsoleTail)) {
			lastAction = latest.text();
			lastActionTone = latest.tone();
			observedConsoleTail = latest;
		}
		graphics.text(font, fit(lastAction, windowWidth - 20), windowX + 10, windowY + windowHeight - 40, color(lastActionTone), false);
	}

	private void renderResizeGrip(GuiGraphicsExtractor graphics) {
		int right = windowX + windowWidth;
		int bottom = windowY + windowHeight;
		graphics.fill(right - 10, bottom - 3, right - 2, bottom - 1, 0xFF18D9C7);
		graphics.fill(right - 7, bottom - 6, right - 2, bottom - 4, 0xFF18D9C7);
		graphics.fill(right - 4, bottom - 9, right - 2, bottom - 7, 0xFF18D9C7);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && inResizeGrip(event.x(), event.y())) {
			resizing = true;
			setDragging(true);
			return true;
		}
		if (event.button() == 0 && inTitleBar(event.x(), event.y())) {
			moving = true;
			dragOffsetX = (int) event.x() - windowX;
			dragOffsetY = (int) event.y() - windowY;
			setDragging(true);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (resizing) {
			int maxWidth = Math.max(1, width - windowX);
			int maxHeight = Math.max(1, height - windowY);
			savedWidth = clamp((int) event.x() - windowX, Math.min(440, maxWidth), maxWidth);
			savedHeight = clamp((int) event.y() - windowY, Math.min(300, maxHeight), maxHeight);
			savedX = windowX;
			savedY = windowY;
			rebuildWidgets();
			setDragging(true);
			return true;
		}
		if (moving) {
			int nextX = clamp((int) event.x() - dragOffsetX, 0, Math.max(0, width - windowWidth));
			int nextY = clamp((int) event.y() - dragOffsetY, 0, Math.max(0, height - windowHeight));
			int moveX = nextX - windowX;
			int moveY = nextY - windowY;
			windowX = nextX;
			windowY = nextY;
			saveGeometry();
			for (AbstractWidget widget : windowWidgets) {
				widget.setX(widget.getX() + moveX);
				widget.setY(widget.getY() + moveY);
			}
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
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (!insideWindow(mouseX, mouseY)) return false;
		int direction = vertical > 0 ? -1 : vertical < 0 ? 1 : 0;
		if (direction == 0) return true;
		if (mouseX < windowX + modulePanelWidth) {
			int maximum = Math.max(0, console.moduleViews().size() - visibleModuleRows());
			moduleScroll = clamp(moduleScroll + direction, 0, maximum);
		} else {
			ActiveModuleView active = console.activeModuleView();
			int maximum = active == null ? 0 : Math.max(0, active.options().size() - visibleOptionRows());
			optionScroll = clamp(optionScroll + direction, 0, maximum);
		}
		rebuildWidgets();
		return true;
	}

	@Override
	public void onClose() {
		returnToTerminal();
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }

	private void returnToTerminal() {
		minecraft.setScreenAndShow(parent == null ? new ChampionsConsoleScreen(console) : parent);
	}

	private void setActivity(String text) {
		lastAction = text;
		lastActionTone = ConsoleLine.Tone.INFO;
		List<ConsoleLine> lines = console.lines();
		observedConsoleTail = lines.isEmpty() ? null : lines.getLast();
	}

	private void saveGeometry() {
		savedX = windowX;
		savedY = windowY;
		savedWidth = windowWidth;
		savedHeight = windowHeight;
	}

	private int visibleModuleRows() {
		return Math.max(1, (windowHeight - TITLE_HEIGHT - ACTION_HEIGHT - 28) / MODULE_ROW_HEIGHT);
	}

	private int visibleOptionRows() {
		return Math.max(1, (windowHeight - TITLE_HEIGHT - ACTION_HEIGHT - 74) / OPTION_ROW_HEIGHT);
	}

	private boolean inTitleBar(double mouseX, double mouseY) {
		return mouseX >= windowX && mouseX < windowX + windowWidth
			&& mouseY >= windowY && mouseY < windowY + TITLE_HEIGHT;
	}

	private boolean inResizeGrip(double mouseX, double mouseY) {
		return mouseX >= windowX + windowWidth - RESIZE_GRIP && mouseX < windowX + windowWidth
			&& mouseY >= windowY + windowHeight - RESIZE_GRIP && mouseY < windowY + windowHeight;
	}

	private boolean insideWindow(double mouseX, double mouseY) {
		return mouseX >= windowX && mouseX < windowX + windowWidth
			&& mouseY >= windowY && mouseY < windowY + windowHeight;
	}

	private String fit(String value, int maxWidth) {
		if (font.width(value) <= maxWidth) return value;
		String suffix = "...";
		return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width(suffix))) + suffix;
	}

	private static String category(String moduleId) {
		String[] parts = moduleId.split("/");
		return parts.length > 1 ? parts[1].toUpperCase(java.util.Locale.ROOT) : "MODULE";
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
}
