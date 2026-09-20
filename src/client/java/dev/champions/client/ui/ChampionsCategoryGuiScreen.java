package dev.champions.client.ui;

import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ChampionConsole.ActiveModuleView;
import dev.champions.core.console.ChampionConsole.CurrentTargetView;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Original multi-window category interface inspired by classic utility-client layouts. */
public final class ChampionsCategoryGuiScreen extends Screen {
	private static final String CONTROLS = "$controls";
	private static final int TITLE_HEIGHT = 20;
	private static final int ROW_HEIGHT = 20;
	private static final int RESIZE_GRIP = 10;
	private static final Map<String, PanelLayout> SAVED_PANELS = new LinkedHashMap<>();
	private static final List<String> Z_ORDER = new ArrayList<>();
	private static int settingsX = -1;
	private static int settingsY = -1;
	private static int settingsWidth = 390;
	private static int settingsHeight = 400;

	private final ChampionConsole console;
	private final Screen parent;
	private final List<AbstractWidget> settingsWidgets = new ArrayList<>();
	private Map<String, List<ModuleView>> categories = Map.of();
	private String draggingPanel;
	private String resizingPanel;
	private boolean movingSettings;
	private boolean resizingSettings;
	private int dragOffsetX;
	private int dragOffsetY;
	private int optionScroll;
	private ConsoleLine observedConsoleTail;
	private ConsoleLine.Tone activityTone = ConsoleLine.Tone.INFO;
	private String activity = "Choose a module from a category window.";

	public ChampionsCategoryGuiScreen(ChampionConsole console, Screen parent) {
		super(Component.literal("Champions Category GUI"));
		this.console = console;
		this.parent = parent;
		List<ConsoleLine> lines = console.lines();
		observedConsoleTail = lines.isEmpty() ? null : lines.getLast();
	}

	@Override
	protected void init() {
		settingsWidgets.clear();
		categories = groupModules(console.moduleViews());
		initializePanels();
		initializeSettingsGeometry();
		buildSettingsWidgets();
	}

	private Map<String, List<ModuleView>> groupModules(List<ModuleView> modules) {
		Map<String, List<ModuleView>> grouped = new TreeMap<>();
		for (ModuleView module : modules) grouped.computeIfAbsent(category(module.id()), ignored -> new ArrayList<>()).add(module);
		Map<String, List<ModuleView>> ordered = new LinkedHashMap<>();
		grouped.forEach((category, values) -> ordered.put(category, List.copyOf(values)));
		return java.util.Collections.unmodifiableMap(ordered);
	}

	private void initializePanels() {
		List<String> ids = new ArrayList<>();
		ids.add(CONTROLS);
		ids.addAll(categories.keySet());
		for (int i = 0; i < ids.size(); i++) {
			String id = ids.get(i);
			int defaultX = 12 + (i % 5) * 176;
			int defaultY = 34 + (i / 5) * 180;
			PanelLayout panel = SAVED_PANELS.computeIfAbsent(id, ignored -> new PanelLayout(defaultX, defaultY, 166, false));
			panel.width = clamp(panel.width, 126, Math.max(126, width));
			int panelHeight = panelHeight(id, panel);
			panel.x = clamp(panel.x, 0, Math.max(0, width - panel.width));
			panel.y = clamp(panel.y, 0, Math.max(0, height - panelHeight));
			if (!Z_ORDER.contains(id)) Z_ORDER.add(id);
		}
		Z_ORDER.removeIf(id -> !ids.contains(id));
	}

	private void initializeSettingsGeometry() {
		settingsWidth = clamp(settingsWidth, Math.min(300, width), Math.max(1, width));
		settingsHeight = clamp(settingsHeight, Math.min(260, height), Math.max(1, height));
		if (settingsX < 0 || settingsY < 0) {
			settingsX = Math.max(0, (width - settingsWidth) / 2);
			settingsY = Math.max(0, (height - settingsHeight) / 2);
		} else {
			settingsX = clamp(settingsX, 0, Math.max(0, width - settingsWidth));
			settingsY = clamp(settingsY, 0, Math.max(0, height - settingsHeight));
		}
	}

	private void buildSettingsWidgets() {
		ActiveModuleView active = console.activeModuleView();
		if (active == null) return;
		List<OptionView> options = active.options();
		int visibleRows = visibleOptionRows();
		optionScroll = clamp(optionScroll, 0, Math.max(0, options.size() - visibleRows));
		int end = Math.min(options.size(), optionScroll + visibleRows);
		for (int i = optionScroll; i < end; i++) {
			OptionView option = options.get(i);
			int row = i - optionScroll;
			int y = settingsY + 76 + row * 36;
			int editorX = settingsX + 116;
			int editorWidth = Math.max(64, settingsX + settingsWidth - 12 - editorX);
			if (option.secret()) {
				String label = option.configured() ? "Armed - click to clear" : "Arm authorization";
				track(Button.builder(Component.literal(label), button -> {
					boolean arm = !option.configured();
					console.setOptionFromUi(option.name(), arm ? "I_HAVE_AUTHORIZATION" : "");
					setActivity(arm ? "One-run confirmation armed." : "One-run confirmation cleared.");
					rebuildWidgets();
				}).bounds(editorX, y, editorWidth, 18).build());
			} else {
				EditBox editor = new EditBox(font, editorX, y, editorWidth, 18, Component.literal(option.name()));
				editor.setMaxLength(256);
				editor.setValue(option.value());
				editor.setHint(Component.literal("<unset>"));
				editor.setResponder(value -> console.setOptionFromUi(option.name(), value));
				track(editor);
			}
		}

		int actionsY = settingsY + settingsHeight - 28;
		Button run = track(Button.builder(Component.literal("Run now"), button -> {
			setActivity("Run requested for " + active.id());
			console.runFromUi();
			rebuildWidgets();
		}).bounds(settingsX + 10, actionsY, 68, 18).build());
		run.active = console.runningModuleId().isBlank();
		track(Button.builder(Component.literal("List GUI"), button ->
			minecraft.setScreenAndShow(new ChampionsClickGuiScreen(console, this)))
			.bounds(settingsX + 84, actionsY, 62, 18).build());
		track(Button.builder(Component.literal("Close settings"), button -> {
			console.clearSelectionFromUi();
			optionScroll = 0;
			setActivity("Settings window closed.");
			rebuildWidgets();
		}).bounds(settingsX + settingsWidth - 104, actionsY, 94, 18).build());
	}

	private <T extends AbstractWidget> T track(T widget) {
		settingsWidgets.add(widget);
		return addRenderableWidget(widget);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0x66000000);
		graphics.text(font, "CHAMPIONS CATEGORY GUI", 8, 8, 0xFF18D9C7, false);
		graphics.text(font, "drag headers | right-click header to collapse | drag corners to resize", 142, 8, 0xFF8EA0B8, false);
		for (String id : Z_ORDER) renderPanel(graphics, id, mouseX, mouseY);
		if (console.activeModuleView() != null) renderSettingsPanel(graphics, mouseX, mouseY);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private void renderPanel(GuiGraphicsExtractor graphics, String id, int mouseX, int mouseY) {
		PanelLayout panel = SAVED_PANELS.get(id);
		if (panel == null) return;
		int panelHeight = panelHeight(id, panel);
		boolean hoveredTitle = contains(panel.x, panel.y, panel.width, TITLE_HEIGHT, mouseX, mouseY);
		graphics.fill(panel.x - 2, panel.y - 2, panel.x + panel.width + 2, panel.y + panelHeight + 2, 0x99000000);
		graphics.fill(panel.x, panel.y, panel.x + panel.width, panel.y + TITLE_HEIGHT,
			hoveredTitle ? 0xFF153A4A : 0xFF0B2432);
		graphics.fill(panel.x, panel.y + TITLE_HEIGHT - 2, panel.x + panel.width, panel.y + TITLE_HEIGHT, 0xFF18D9C7);
		String title = id.equals(CONTROLS) ? "CHAMPIONS" : id.toUpperCase(Locale.ROOT);
		graphics.text(font, fit(title, panel.width - 28), panel.x + 7, panel.y + 7, 0xFFEAF4FF, false);
		graphics.text(font, panel.collapsed ? "+" : "-", panel.x + panel.width - 12, panel.y + 7, 0xFFFBBF24, false);
		if (!panel.collapsed) {
			graphics.fill(panel.x, panel.y + TITLE_HEIGHT, panel.x + panel.width, panel.y + panelHeight, 0xEE09111C);
			if (id.equals(CONTROLS)) renderControlRows(graphics, panel, mouseX, mouseY);
			else renderModuleRows(graphics, id, panel, mouseX, mouseY);
			renderPanelGrip(graphics, panel.x + panel.width, panel.y + panelHeight);
		}
	}

	private void renderModuleRows(GuiGraphicsExtractor graphics, String category, PanelLayout panel, int mouseX, int mouseY) {
		List<ModuleView> modules = categories.getOrDefault(category, List.of());
		for (int i = 0; i < modules.size(); i++) {
			ModuleView module = modules.get(i);
			int y = panel.y + TITLE_HEIGHT + i * ROW_HEIGHT;
			boolean hovered = contains(panel.x, y, panel.width, ROW_HEIGHT, mouseX, mouseY);
			int background = module.selected() ? 0xCC155E75 : hovered ? 0xAA1A3040 : (i % 2 == 0 ? 0x44182A39 : 0x22182A39);
			graphics.fill(panel.x, y, panel.x + panel.width, y + ROW_HEIGHT, background);
			graphics.text(font, fit(module.name(), panel.width - 16), panel.x + 7, y + 7,
				module.selected() ? 0xFFFFFFFF : 0xFFD8E2F0, false);
		}
	}

	private void renderControlRows(GuiGraphicsExtractor graphics, PanelLayout panel, int mouseX, int mouseY) {
		CurrentTargetView target = console.currentTargetView();
		List<String> labels = List.of(
			"Terminal",
			"Sorted list GUI",
			target.authorized() ? "Remove current target" : "Authorize current target",
			"Reset window layout",
			"Close"
		);
		for (int i = 0; i < labels.size(); i++) {
			int y = panel.y + TITLE_HEIGHT + i * ROW_HEIGHT;
			boolean hovered = contains(panel.x, y, panel.width, ROW_HEIGHT, mouseX, mouseY);
			graphics.fill(panel.x, y, panel.x + panel.width, y + ROW_HEIGHT, hovered ? 0xAA1A3040 : (i % 2 == 0 ? 0x44182A39 : 0x22182A39));
			int color = i == 2 ? (target.authorized() ? 0xFF6EE7B7 : 0xFFFBBF24) : 0xFFD8E2F0;
			graphics.text(font, fit(labels.get(i), panel.width - 16), panel.x + 7, y + 7, color, false);
		}
	}

	private void renderSettingsPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		ActiveModuleView active = console.activeModuleView();
		if (active == null) return;
		graphics.fill(settingsX - 3, settingsY - 3, settingsX + settingsWidth + 3, settingsY + settingsHeight + 3, 0xBB000000);
		graphics.fill(settingsX, settingsY, settingsX + settingsWidth, settingsY + settingsHeight, 0xFA080E18);
		graphics.fill(settingsX, settingsY, settingsX + settingsWidth, settingsY + 24, 0xFF102B3A);
		graphics.fill(settingsX, settingsY + 22, settingsX + settingsWidth, settingsY + 24, 0xFFFBBF24);
		graphics.text(font, fit(active.name(), settingsWidth - 130), settingsX + 9, settingsY + 8, 0xFFFFFFFF, false);
		graphics.text(font, "SETTINGS", settingsX + settingsWidth - 58, settingsY + 8, 0xFFFBBF24, false);
		graphics.text(font, fit(active.id(), settingsWidth - 20), settingsX + 9, settingsY + 31, 0xFF22D3EE, false);
		graphics.text(font, fit(active.description(), settingsWidth - 20), settingsX + 9, settingsY + 45, 0xFF8EA0B8, false);
		renderOptionLabels(graphics, active);
		renderSettingsStatus(graphics);
		renderPanelGrip(graphics, settingsX + settingsWidth, settingsY + settingsHeight);
	}

	private void renderOptionLabels(GuiGraphicsExtractor graphics, ActiveModuleView active) {
		List<OptionView> options = active.options();
		int visibleRows = visibleOptionRows();
		int end = Math.min(options.size(), optionScroll + visibleRows);
		for (int i = optionScroll; i < end; i++) {
			OptionView option = options.get(i);
			int row = i - optionScroll;
			int y = settingsY + 79 + row * 36;
			graphics.text(font, option.name() + (option.required() ? " *" : ""), settingsX + 10, y, 0xFFD8E2F0, false);
			graphics.text(font, fit(option.description(), settingsWidth - 24), settingsX + 10, y + 19, 0xFF718096, false);
		}
		if (options.size() > visibleRows) {
			graphics.text(font, (optionScroll + 1) + "-" + end + "/" + options.size(),
				settingsX + settingsWidth - 46, settingsY + 61, 0xFFFBBF24, false);
		}
	}

	private void renderSettingsStatus(GuiGraphicsExtractor graphics) {
		List<ConsoleLine> lines = console.lines();
		ConsoleLine latest = lines.isEmpty() ? null : lines.getLast();
		if (latest != null && !latest.equals(observedConsoleTail)) {
			observedConsoleTail = latest;
			activity = latest.text();
			activityTone = latest.tone();
		}
		String running = console.runningModuleId();
		CurrentTargetView target = console.currentTargetView();
		String state = running.isBlank() ? "IDLE" : "RUNNING " + running;
		String authorization = target.available() ? (target.authorized() ? "AUTHORIZED" : "NOT AUTHORIZED") : "NO TARGET";
		graphics.text(font, fit("STATUS: " + state + " | " + authorization, settingsWidth - 20),
			settingsX + 10, settingsY + settingsHeight - 51, running.isBlank() ? 0xFF6EE7B7 : 0xFFFBBF24, false);
		graphics.text(font, fit(activity, settingsWidth - 20), settingsX + 10, settingsY + settingsHeight - 39,
			color(activityTone), false);
	}

	private void renderPanelGrip(GuiGraphicsExtractor graphics, int right, int bottom) {
		graphics.fill(right - 8, bottom - 3, right - 1, bottom - 1, 0xFF18D9C7);
		graphics.fill(right - 5, bottom - 6, right - 1, bottom - 4, 0xFF18D9C7);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int mouseX = (int) event.x();
		int mouseY = (int) event.y();
		if (console.activeModuleView() != null) {
			if (event.button() == 0 && inSettingsResizeGrip(mouseX, mouseY)) {
				resizingSettings = true;
				setDragging(true);
				return true;
			}
			if (event.button() == 0 && contains(settingsX, settingsY, settingsWidth, 24, mouseX, mouseY)) {
				movingSettings = true;
				dragOffsetX = mouseX - settingsX;
				dragOffsetY = mouseY - settingsY;
				setDragging(true);
				return true;
			}
			if (contains(settingsX, settingsY, settingsWidth, settingsHeight, mouseX, mouseY)) {
				if (super.mouseClicked(event, doubleClick)) return true;
				return true;
			}
		}

		String panelId = topPanelAt(mouseX, mouseY);
		if (panelId == null) return super.mouseClicked(event, doubleClick);
		PanelLayout panel = SAVED_PANELS.get(panelId);
		bringToFront(panelId);
		int panelHeight = panelHeight(panelId, panel);
		if (event.button() == 0 && !panel.collapsed && contains(panel.x + panel.width - RESIZE_GRIP,
			panel.y + panelHeight - RESIZE_GRIP, RESIZE_GRIP, RESIZE_GRIP, mouseX, mouseY)) {
			resizingPanel = panelId;
			setDragging(true);
			return true;
		}
		if (contains(panel.x, panel.y, panel.width, TITLE_HEIGHT, mouseX, mouseY)) {
			if (event.button() == 1 || doubleClick) {
				panel.collapsed = !panel.collapsed;
				return true;
			}
			if (event.button() == 0) {
				draggingPanel = panelId;
				dragOffsetX = mouseX - panel.x;
				dragOffsetY = mouseY - panel.y;
				setDragging(true);
				return true;
			}
		}
		if (event.button() == 0 && !panel.collapsed) {
			int row = (mouseY - panel.y - TITLE_HEIGHT) / ROW_HEIGHT;
			if (panelId.equals(CONTROLS)) return activateControl(row);
			List<ModuleView> modules = categories.getOrDefault(panelId, List.of());
			if (row >= 0 && row < modules.size()) {
				ModuleView module = modules.get(row);
				console.selectModuleFromUi(module.id());
				optionScroll = 0;
				setActivity("Selected " + module.id());
				rebuildWidgets();
				return true;
			}
		}
		return true;
	}

	private boolean activateControl(int row) {
		switch (row) {
			case 0 -> returnToTerminal();
			case 1 -> minecraft.setScreenAndShow(new ChampionsClickGuiScreen(console, this));
			case 2 -> {
				CurrentTargetView target = console.currentTargetView();
				setActivity(target.authorized() ? "Removing current target authorization..." : "Authorizing current target...");
				console.setCurrentTargetAuthorizedFromUi(!target.authorized());
				rebuildWidgets();
			}
			case 3 -> resetPanelLayout();
			case 4 -> minecraft.setScreenAndShow(null);
			default -> { return false; }
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		int mouseX = (int) event.x();
		int mouseY = (int) event.y();
		if (movingSettings) {
			int nextX = clamp(mouseX - dragOffsetX, 0, Math.max(0, width - settingsWidth));
			int nextY = clamp(mouseY - dragOffsetY, 0, Math.max(0, height - settingsHeight));
			moveSettingsWidgets(nextX - settingsX, nextY - settingsY);
			settingsX = nextX;
			settingsY = nextY;
			return true;
		}
		if (resizingSettings) {
			settingsWidth = clamp(mouseX - settingsX, Math.min(300, width - settingsX), Math.max(1, width - settingsX));
			settingsHeight = clamp(mouseY - settingsY, Math.min(260, height - settingsY), Math.max(1, height - settingsY));
			rebuildWidgets();
			setDragging(true);
			return true;
		}
		if (draggingPanel != null) {
			PanelLayout panel = SAVED_PANELS.get(draggingPanel);
			if (panel != null) {
				panel.x = clamp(mouseX - dragOffsetX, 0, Math.max(0, width - panel.width));
				panel.y = clamp(mouseY - dragOffsetY, 0, Math.max(0, height - panelHeight(draggingPanel, panel)));
			}
			return true;
		}
		if (resizingPanel != null) {
			PanelLayout panel = SAVED_PANELS.get(resizingPanel);
			if (panel != null) panel.width = clamp(mouseX - panel.x, 126, Math.max(126, width - panel.x));
			return true;
		}
		return super.mouseDragged(event, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (movingSettings || resizingSettings || draggingPanel != null || resizingPanel != null) {
			movingSettings = false;
			resizingSettings = false;
			draggingPanel = null;
			resizingPanel = null;
			setDragging(false);
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (console.activeModuleView() != null && contains(settingsX, settingsY, settingsWidth, settingsHeight, mouseX, mouseY)) {
			int direction = vertical > 0 ? -1 : vertical < 0 ? 1 : 0;
			ActiveModuleView active = console.activeModuleView();
			int maximum = active == null ? 0 : Math.max(0, active.options().size() - visibleOptionRows());
			optionScroll = clamp(optionScroll + direction, 0, maximum);
			rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public void onClose() {
		returnToTerminal();
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }

	private void moveSettingsWidgets(int deltaX, int deltaY) {
		for (AbstractWidget widget : settingsWidgets) {
			widget.setX(widget.getX() + deltaX);
			widget.setY(widget.getY() + deltaY);
		}
	}

	private void setActivity(String text) {
		activity = text;
		activityTone = ConsoleLine.Tone.INFO;
		List<ConsoleLine> lines = console.lines();
		observedConsoleTail = lines.isEmpty() ? null : lines.getLast();
	}

	private void resetPanelLayout() {
		SAVED_PANELS.clear();
		Z_ORDER.clear();
		settingsX = -1;
		settingsY = -1;
		settingsWidth = 390;
		settingsHeight = 400;
		setActivity("Category window layout reset.");
		rebuildWidgets();
	}

	private void returnToTerminal() {
		minecraft.setScreenAndShow(parent == null ? new ChampionsConsoleScreen(console) : parent);
	}

	private void bringToFront(String id) {
		Z_ORDER.remove(id);
		Z_ORDER.add(id);
	}

	private String topPanelAt(int mouseX, int mouseY) {
		for (int i = Z_ORDER.size() - 1; i >= 0; i--) {
			String id = Z_ORDER.get(i);
			PanelLayout panel = SAVED_PANELS.get(id);
			if (panel != null && contains(panel.x, panel.y, panel.width, panelHeight(id, panel), mouseX, mouseY)) return id;
		}
		return null;
	}

	private int panelHeight(String id, PanelLayout panel) {
		if (panel.collapsed) return TITLE_HEIGHT;
		int rows = id.equals(CONTROLS) ? 5 : categories.getOrDefault(id, List.of()).size();
		return TITLE_HEIGHT + rows * ROW_HEIGHT;
	}

	private boolean inSettingsResizeGrip(int mouseX, int mouseY) {
		return contains(settingsX + settingsWidth - RESIZE_GRIP, settingsY + settingsHeight - RESIZE_GRIP,
			RESIZE_GRIP, RESIZE_GRIP, mouseX, mouseY);
	}

	private int visibleOptionRows() {
		return Math.max(1, (settingsHeight - 132) / 36);
	}

	private String fit(String value, int maxWidth) {
		if (font.width(value) <= maxWidth) return value;
		String suffix = "...";
		return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width(suffix))) + suffix;
	}

	private static String category(String moduleId) {
		String[] parts = moduleId.split("/");
		return parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "modules";
	}

	private static boolean contains(int x, int y, int width, int height, double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
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

	private static final class PanelLayout {
		private int x;
		private int y;
		private int width;
		private boolean collapsed;

		private PanelLayout(int x, int y, int width, boolean collapsed) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.collapsed = collapsed;
		}
	}
}
