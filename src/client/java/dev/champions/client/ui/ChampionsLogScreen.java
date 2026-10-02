package dev.champions.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.champions.client.ChampionsScreens;
import dev.champions.client.log.GameLog;
import dev.champions.core.log.LogLine;
import dev.champions.core.log.LogSearch;
import dev.champions.core.log.LogTail;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.champions.core.ui.MinecraftPointerButtons.PRIMARY;

/**
 * The game log window: a live view of the running game's own output log
 * ({@code logs/latest.log} in the game directory) with search on top of it.
 *
 * The window follows the end of the log like {@code tail -f} until the reader
 * scrolls away from the bottom; lines keep arriving in that case without moving
 * the view, and the LIVE chip (or End) jumps back to the newest line. Typing in
 * the search field filters and highlights every occurrence, Enter/Shift+Enter or
 * the Prev/Next chips walk the matches, and the level chip hides everything
 * below INFO, WARN or ERROR. Clicking a line selects it, dragging extends the
 * selection, and Ctrl+C copies the selection - or every match, or the whole
 * buffer when nothing is selected or searched.
 *
 * Selections and the current match are kept as line numbers and as a match
 * value rather than as indices into the filtered list, so filtering, searching
 * or the eviction of old lines never silently retargets them.
 */
public final class ChampionsLogScreen extends Screen implements WorkspaceWindow {
	private static final int TITLE_HEIGHT = 26;
	private static final int TOOLBAR_HEIGHT = 26;
	private static final int STATUS_HEIGHT = 14;
	private static final int PADDING = 10;
	private static final int LINE_HEIGHT = 10;
	private static final int RESIZE_GRIP = 12;
	private static final int GUTTER_WIDTH = 46;
	private static final int SEARCH_GLYPH_WIDTH = 14;
	private static final int CLEAR_BOX_SIZE = 11;
	private static final int CHIP_HEIGHT = 16;
	private static final int CHIP_GAP = 4;
	private static final int MINIMUM_WINDOW_WIDTH = 520;
	private static final int MINIMUM_WINDOW_HEIGHT = 260;
	private static final int DEFAULT_WINDOW_WIDTH = 900;
	private static final int DEFAULT_WINDOW_HEIGHT = 560;
	private static final long NOTICE_MILLIS = 2_500L;
	private static int savedX = -1;
	private static int savedY = -1;
	private static int savedWidth = -1;
	private static int savedHeight = -1;

	// The log window shares the console's dark shell so the workspace feels like
	// one desktop, with a cyan accent to tell the two windows apart.
	private static final int COLOR_WINDOW = 0xFF090909;
	private static final int COLOR_TITLE_BAR = 0xFF252525;
	private static final int COLOR_TITLE_DIVIDER = 0xFF353535;
	private static final int COLOR_PANEL = 0xFF0D0D0D;
	private static final int COLOR_BODY = 0xFF070707;
	private static final int COLOR_STATUS_BAR = 0xFF151515;
	private static final int COLOR_ACCENT = 0xFF4CC9F0;
	private static final int COLOR_TITLE_TEXT = 0xFFF0F0F0;
	private static final int COLOR_MUTED = 0xFF9A9A9A;
	private static final int COLOR_DIM = 0xFF6E6E6E;
	private static final int COLOR_INFO = 0xFFE6E6E6;
	private static final int COLOR_CONTINUATION = 0xFFB9B9B9;
	private static final int COLOR_DEBUG = 0xFF8A8A8A;
	private static final int COLOR_WARNING = 0xFFE5C35A;
	private static final int COLOR_ERROR = 0xFFFF6868;
	private static final int COLOR_SUCCESS = 0xFF55D65A;
	private static final int COLOR_SELECTION = 0x554E6EA8;
	private static final int COLOR_HIT = 0x88324E7A;
	private static final int COLOR_HIT_CURRENT = 0xCC4E8CD6;
	private static final int COLOR_HIT_TEXT = 0xFFF5F5F5;
	private static final int COLOR_CHIP = 0xFF1E1E1E;
	private static final int COLOR_CHIP_HOVER = 0xFF2F3B52;
	private static final int COLOR_CHIP_ACTIVE = 0xFF2B4A75;
	private static final int COLOR_CHIP_ACTIVE_HOVER = 0xFF3F6FB5;
	private static final int COLOR_CHIP_TEXT = 0xFFD5D5D5;
	private static final int COLOR_CHIP_TEXT_ACTIVE = 0xFFFFFFFF;
	private static final int COLOR_CHIP_BORDER = 0xFF3A3A3A;

	/** Levels the level chip cycles through; TRACE means "show everything". */
	private static final LogLine.Level[] LEVEL_FILTERS = {
		LogLine.Level.TRACE, LogLine.Level.INFO, LogLine.Level.WARN, LogLine.Level.ERROR
	};

	private final LogTail tail = GameLog.tail();
	private final List<AbstractWidget> windowWidgets = new ArrayList<>();
	private WorkspaceHost host;
	private EditBox search;
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
	private boolean selecting;
	private boolean follow = true;
	private boolean caseSensitive;
	private boolean onlyMatches;
	private boolean rowsDirty = true;
	private int levelFilterIndex;
	private int dragOffsetX;
	private int dragOffsetY;
	private int scrollOffset;
	private int renderedVisibleRows;
	private int lastBodyWidth = -1;
	private boolean lastClearVisible;
	private long selectionAnchor = -1L;
	private long selectionCaret = -1L;
	private String lastQuery = "";
	private String notice = "";
	private int noticeColor = COLOR_MUTED;
	private long noticeUntil;

	private LogSearch matcher = new LogSearch("", false);
	private Marker currentMarker;
	private List<Line> visible = List.of();
	private List<Row> rows = List.of();
	private int[] firstRowOfLine = new int[0];
	private Map<Integer, List<int[]>> hitRanges = Map.of();
	private List<LogSearch.Hit> hits = List.of();
	private boolean hitsTruncated;

	public ChampionsLogScreen() {
		super(Component.literal("ChampionsCode Log Viewer"));
	}

	/** A buffered log line paired with its stable number in the whole log. */
	private record Line(LogLine line, long number) {}

	/** One rendered row: a wrapped slice of one buffered line. */
	private record Row(int lineIndex, int start, String text) {}

	/**
	 * The match the reader last jumped to, anchored to the line's stable number
	 * instead of its index in the filtered list, so filtering, searching or the
	 * eviction of old lines cannot move the marker onto another line.
	 */
	private record Marker(long number, int start, int end) {}

	private record ChipRect(Chip chip, int x, int y, int width, int height) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}
	}

	/** Toolbar chips, ordered left to right as they are laid out. */
	private enum Chip {
		CASE, ONLY, PREV, NEXT, LIVE, COPY, LEVEL;

		int width() {
			return switch (this) {
				case CASE -> 26;
				case ONLY -> 40;
				case PREV, NEXT -> 34;
				case LIVE -> 40;
				case COPY -> 40;
				case LEVEL -> 86;
			};
		}
	}

	/** When hosted inside the workspace, close/navigation actions go through the host. */
	public void host(WorkspaceHost host) {
		this.host = host;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// No vanilla blur/menu background: the viewer is one window on the workspace desktop.
	}

	@Override
	protected void init() {
		windowWidgets.clear();
		int maximumWidth = Math.min(width, Math.max(MINIMUM_WINDOW_WIDTH, width - Math.min(32, Math.max(0, width / 8))));
		int maximumHeight = Math.min(height, Math.max(MINIMUM_WINDOW_HEIGHT, height - Math.min(32, Math.max(0, height / 8))));
		int minimumWidth = Math.min(MINIMUM_WINDOW_WIDTH, maximumWidth);
		int minimumHeight = Math.min(MINIMUM_WINDOW_HEIGHT, maximumHeight);
		if (maximized) {
			windowX = 0;
			windowY = 0;
			windowWidth = width;
			windowHeight = height;
		} else {
			windowWidth = savedWidth < 0 ? Math.min(DEFAULT_WINDOW_WIDTH, maximumWidth) : clamp(savedWidth, minimumWidth, maximumWidth);
			windowHeight = savedHeight < 0 ? Math.min(DEFAULT_WINDOW_HEIGHT, maximumHeight) : clamp(savedHeight, minimumHeight, maximumHeight);
			windowX = savedX < 0 ? (width - windowWidth) / 2 : clamp(savedX, 0, Math.max(0, width - windowWidth));
			windowY = savedY < 0 ? (height - windowHeight) / 2 : clamp(savedY, 0, Math.max(0, height - windowHeight));
			saveGeometry();
		}

		String query = search == null ? lastQuery : search.getValue();
		search = new EditBox(font, 0, 0, 80, 14, Component.literal("Search the game log"));
		search.setMaxLength(160);
		search.setHint(Component.literal("search this log - Enter next match, Shift+Enter previous"));
		search.setBordered(false);
		search.setCanLoseFocus(true);
		search.setTextColor(0xFFF1F1F1);
		search.setValue(query);
		lastQuery = query;
		lastClearVisible = !query.isEmpty();
		addRenderableWidget(search);
		windowWidgets.add(search);
		layoutSearch();
		setWidgetsVisible(!minimized);
		setInitialFocus(search);
		markDirty();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (GameLog.refresh()) {
			markDirty();
			if (follow) scrollOffset = 0;
		}
		detectQueryChange();
		rebuildIfDirty();

		int shownHeight = minimized ? TITLE_HEIGHT : windowHeight;
		graphics.fill(windowX - 4, windowY - 4, windowX + windowWidth + 4, windowY + shownHeight + 4, 0x99000000);
		graphics.fill(windowX, windowY, windowX + windowWidth, windowY + shownHeight, COLOR_WINDOW);
		graphics.fill(windowX, windowY, windowX + windowWidth, windowY + TITLE_HEIGHT, COLOR_TITLE_BAR);
		graphics.fill(windowX, windowY + TITLE_HEIGHT - 1, windowX + windowWidth, windowY + TITLE_HEIGHT, COLOR_TITLE_DIVIDER);
		graphics.fill(windowX + 7, windowY + 7, windowX + 13, windowY + 13, COLOR_ACCENT);
		String title = "champions@minecraft: ~/logs/" + tail.file().getFileName();
		graphics.text(font, title, windowX + (windowWidth - font.width(title)) / 2, windowY + 9, COLOR_TITLE_TEXT, false);
		renderFollowIndicator(graphics);
		WindowChrome.render(graphics, font, windowX, windowY, windowWidth, TITLE_HEIGHT,
			mouseX, mouseY, 0xFFD5D5D5, maximized);
		if (minimized) {
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}

		renderToolbar(graphics, mouseX, mouseY);
		renderRows(graphics);
		renderStatusBar(graphics);
		renderResizeGrip(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private void renderFollowIndicator(GuiGraphicsExtractor graphics) {
		int right = WindowChrome.controlsLeft(windowX, windowWidth) - 8;
		String label = follow ? "LIVE" : "PAUSED";
		int color = follow ? COLOR_SUCCESS : COLOR_WARNING;
		int x = right - font.width(label) - 8;
		graphics.fill(x, windowY + 8, x + 5, windowY + 13, color);
		graphics.text(font, label, x + 8, windowY + 9, color, false);
	}

	private void renderToolbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int top = windowY + TITLE_HEIGHT;
		graphics.fill(windowX, top, windowX + windowWidth, top + TOOLBAR_HEIGHT, COLOR_PANEL);
		int[] field = searchField();
		graphics.fill(field[0], field[1], field[2], field[3], COLOR_STATUS_BAR);
		renderBorder(graphics, field[0], field[1], field[2], field[3], COLOR_CHIP_BORDER);
		graphics.text(font, "/", field[0] + 4, field[1] + 4, COLOR_ACCENT, false);
		if (clearVisible()) {
			int[] clear = clearBox();
			boolean hovered = inside(clear, mouseX, mouseY);
			graphics.fill(clear[0], clear[1], clear[2], clear[3], hovered ? COLOR_CHIP_HOVER : COLOR_CHIP);
			String mark = "x";
			graphics.text(font, mark, clear[0] + (clear[2] - clear[0] - font.width(mark)) / 2,
				clear[1] + 3, hovered ? COLOR_CHIP_TEXT_ACTIVE : COLOR_CHIP_TEXT, false);
		}
		for (ChipRect rect : chipRects()) {
			boolean active = chipActive(rect.chip());
			boolean hovered = rect.contains(mouseX, mouseY);
			int background = active
				? hovered ? COLOR_CHIP_ACTIVE_HOVER : COLOR_CHIP_ACTIVE
				: hovered ? COLOR_CHIP_HOVER : COLOR_CHIP;
			graphics.fill(rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + rect.height(), background);
			renderBorder(graphics, rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + rect.height(), COLOR_CHIP_BORDER);
			String label = chipLabel(rect.chip());
			int color = active ? COLOR_CHIP_TEXT_ACTIVE : COLOR_CHIP_TEXT;
			graphics.text(font, label, rect.x() + (rect.width() - font.width(label)) / 2, rect.y() + 4, color, false);
		}
	}

	private void renderRows(GuiGraphicsExtractor graphics) {
		int bodyTop = windowY + TITLE_HEIGHT + TOOLBAR_HEIGHT + 1;
		int bodyBottom = windowY + windowHeight - STATUS_HEIGHT - 4;
		int visibleRows = Math.max(1, (bodyBottom - bodyTop) / LINE_HEIGHT);
		renderedVisibleRows = visibleRows;
		graphics.fill(windowX, bodyTop, windowX + windowWidth, bodyBottom, COLOR_BODY);

		if (rows.isEmpty()) {
			graphics.text(font, emptyMessage(), windowX + PADDING, bodyTop + 8, COLOR_MUTED, false);
			return;
		}
		scrollOffset = clamp(scrollOffset, 0, Math.max(0, rows.size() - visibleRows));
		int last = rows.size() - 1 - scrollOffset;
		int first = Math.max(0, last - visibleRows + 1);
		int textX = windowX + PADDING + GUTTER_WIDTH;
		int y = bodyTop + 2;
		for (int i = first; i <= last; i++) {
			renderRow(graphics, rows.get(i), y, textX);
			y += LINE_HEIGHT;
		}
	}

	private void renderRow(GuiGraphicsExtractor graphics, Row row, int y, int textX) {
		Line line = visible.get(row.lineIndex());
		LogLine.Level level = line.line().level();
		if (isSelected(line)) {
			graphics.fill(windowX, y - 1, windowX + windowWidth, y + LINE_HEIGHT - 1, COLOR_SELECTION);
		}
		int marker = markerColor(level);
		if (marker != 0) graphics.fill(windowX + 3, y - 1, windowX + 5, y + LINE_HEIGHT - 1, marker);
		if (row.start() == 0) {
			String number = Long.toString(line.number());
			graphics.text(font, number, windowX + PADDING + GUTTER_WIDTH - 8 - font.width(number), y, COLOR_DIM, false);
		}
		renderHighlightedText(graphics, row, line, textX, y, levelColor(level));
	}

	private void renderHighlightedText(GuiGraphicsExtractor graphics, Row row, Line line, int x, int y, int baseColor) {
		String text = row.text();
		int cursor = 0;
		List<int[]> ranges = hitRanges.get(row.lineIndex());
		if (ranges != null) {
			int rowEnd = row.start() + text.length();
			for (int[] range : ranges) {
				if (range[1] <= row.start() || range[0] >= rowEnd) continue;
				int from = Math.max(range[0], row.start()) - row.start();
				int to = Math.min(range[1], rowEnd) - row.start();
				if (from > cursor) {
					String plain = text.substring(cursor, from);
					graphics.text(font, plain, x, y, baseColor, false);
					x += font.width(plain);
				}
				String highlighted = text.substring(from, to);
				int width = font.width(highlighted);
				boolean current = isCurrentHit(line, range);
				graphics.fill(x, y - 1, x + width + 1, y + LINE_HEIGHT - 1,
					current ? COLOR_HIT_CURRENT : COLOR_HIT);
				graphics.text(font, highlighted, x, y, COLOR_HIT_TEXT, false);
				x += width;
				cursor = to;
			}
		}
		if (cursor < text.length()) graphics.text(font, text.substring(cursor), x, y, baseColor, false);
	}

	private void renderStatusBar(GuiGraphicsExtractor graphics) {
		int top = windowY + windowHeight - STATUS_HEIGHT;
		graphics.fill(windowX, top, windowX + windowWidth, windowY + windowHeight, COLOR_STATUS_BAR);
		graphics.fill(windowX, top, windowX + windowWidth, top + 1, COLOR_TITLE_DIVIDER);
		StringBuilder left = new StringBuilder("logs/").append(tail.file().getFileName());
		left.append("  |  ").append(visible.size()).append(" line").append(visible.size() == 1 ? "" : "s");
		if (tail.droppedLines() > 0) left.append(" (of ").append(tail.linesRead()).append(" read)");
		if (levelFilter() != LogLine.Level.TRACE) left.append("  |  level >= ").append(levelFilter().name());
		if (onlyMatches) left.append("  |  matches only");
		if (caseSensitive) left.append("  |  case sensitive");
		graphics.text(font, fit(left.toString(), Math.max(60, windowWidth / 2 - PADDING)), windowX + PADDING, top + 3, COLOR_DIM, false);

		String right;
		int color;
		if (!tail.problem().isEmpty()) {
			right = "read problem: " + tail.problem();
			color = COLOR_ERROR;
		} else if (System.currentTimeMillis() < noticeUntil) {
			right = notice;
			color = noticeColor;
		} else if (matcher.active()) {
			right = hitsTruncated
				? LogSearch.MAX_HITS + "+ matches - narrow the query"
				: hits.size() + (hits.size() == 1 ? " match" : " matches");
			int position = markerIndex();
			if (position >= 0) right += "  [" + (position + 1) + "/" + hits.size() + "]";
			color = hits.isEmpty() ? COLOR_WARNING : COLOR_ACCENT;
		} else {
			right = "click a line to select, Ctrl+C to copy, Ctrl+F to search";
			color = COLOR_DIM;
		}
		String shown = fit(right, Math.max(60, windowWidth / 2 - PADDING));
		graphics.text(font, shown, windowX + windowWidth - PADDING - font.width(shown), top + 3, color, false);
	}

	private void renderResizeGrip(GuiGraphicsExtractor graphics) {
		if (maximized) return;
		int right = windowX + windowWidth;
		int bottom = windowY + windowHeight;
		graphics.fill(right - 10, bottom - 3, right - 2, bottom - 1, 0xFF7C7C7C);
		graphics.fill(right - 7, bottom - 6, right - 2, bottom - 4, 0xFF7C7C7C);
		graphics.fill(right - 4, bottom - 9, right - 2, bottom - 7, 0xFF7C7C7C);
	}

	/** One-pixel border drawn as four fills; no dependency on the outline API's argument order. */
	private void renderBorder(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color) {
		graphics.fill(left, top, right, top + 1, color);
		graphics.fill(left, bottom - 1, right, bottom, color);
		graphics.fill(left, top + 1, left + 1, bottom - 1, color);
		graphics.fill(right - 1, top + 1, right, bottom - 1, color);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isCopy()) {
			// Ctrl+C inside the search field belongs to the field's own selection.
			String fieldSelection = search == null ? "" : search.getHighlighted();
			if (search != null && search.isFocused() && fieldSelection != null && !fieldSelection.isEmpty()) {
				return super.keyPressed(event);
			}
			copyLines();
			return true;
		}
		if (event.hasControlDownWithQuirk() && event.input() == InputConstants.KEY_F) {
			focusSearch();
			return true;
		}
		if (event.isConfirmation()) {
			jumpToHit(event.hasShiftDown() ? -1 : 1);
			return true;
		}
		if (event.isUp()) {
			scroll(-3);
			return true;
		}
		if (event.isDown()) {
			scroll(3);
			return true;
		}
		if (event.input() == InputConstants.KEY_PAGEUP) {
			scroll(-pageStep());
			return true;
		}
		if (event.input() == InputConstants.KEY_PAGEDOWN) {
			scroll(pageStep());
			return true;
		}
		if (event.input() == InputConstants.KEY_HOME) {
			jumpToOldest();
			return true;
		}
		if (event.input() == InputConstants.KEY_END) {
			jumpToNewest();
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
			if (minimized) return true;
			if (!maximized && inResizeGrip(event.x(), event.y())) {
				resizing = true;
				setDragging(true);
				return true;
			}
			if (inTitleBar(event.x(), event.y())) {
				if (doubleClick) {
					toggleMaximize();
				} else if (!maximized) {
					moving = true;
					dragOffsetX = (int) event.x() - windowX;
					dragOffsetY = (int) event.y() - windowY;
					setDragging(true);
				}
				return true;
			}
			for (ChipRect rect : chipRects()) {
				if (rect.contains(event.x(), event.y())) {
					activateChip(rect.chip());
					return true;
				}
			}
			if (clearVisible() && inside(clearBox(), event.x(), event.y())) {
				clearQuery();
				return true;
			}
			if (inBody(event.x(), event.y())) {
				Line line = lineAt(event.y());
				if (line != null) {
					selectionAnchor = line.number();
					selectionCaret = line.number();
					selecting = !doubleClick;
					setDragging(selecting);
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (resizing) {
			int maxWidth = Math.max(1, width - windowX);
			int maxHeight = Math.max(1, height - windowY);
			windowWidth = clamp((int) event.x() - windowX, Math.min(MINIMUM_WINDOW_WIDTH, maxWidth), maxWidth);
			windowHeight = clamp((int) event.y() - windowY, Math.min(MINIMUM_WINDOW_HEIGHT, maxHeight), maxHeight);
			saveGeometry();
			layoutSearch();
			markDirty();
			return true;
		}
		if (moving) {
			windowX = clamp((int) event.x() - dragOffsetX, 0, Math.max(0, width - windowWidth));
			windowY = clamp((int) event.y() - dragOffsetY, 0, Math.max(0, height - TITLE_HEIGHT));
			saveGeometry();
			layoutSearch();
			return true;
		}
		if (selecting) {
			Line line = lineAt(event.y());
			if (line != null) selectionCaret = line.number();
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
		if (vertical > 0) scroll(-3);
		else if (vertical < 0) scroll(3);
		return true;
	}

	@Override
	public void onClose() {
		if (host != null) {
			host.requestClose(ChampionsScreens.LOGVIEWER);
			return;
		}
		minecraft.setScreenAndShow(null);
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }

	@Override
	public boolean coversPoint(double mouseX, double mouseY) {
		return insideWindow(mouseX, mouseY);
	}

	private void handleWindowControl(WindowChrome.Control control) {
		switch (control) {
			case MINIMIZE -> {
				minimized = !minimized;
				setWidgetsVisible(!minimized);
			}
			case MAXIMIZE -> toggleMaximize();
			case CLOSE -> onClose();
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
		layoutSearch();
		markDirty();
	}

	private void markDirty() {
		rowsDirty = true;
	}

	private void rebuildIfDirty() {
		if (rowsDirty || lastBodyWidth != bodyWidth()) rebuild();
	}

	/**
	 * Rebuilds the filtered line list, the match list and the wrapped rows. Runs
	 * only when the log, the query, a filter or the window width changed, so the
	 * per-frame work stays a plain draw loop.
	 */
	private void rebuild() {
		LogSearch query = new LogSearch(lastQuery, caseSensitive);
		matcher = query;

		List<LogLine> all = tail.lines();
		long numberOffset = tail.numberOffset();
		List<Line> nextVisible = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			LogLine line = all.get(i);
			if (!line.atLeast(levelFilter())) continue;
			if (onlyMatches && query.active() && !query.matches(line.text())) continue;
			nextVisible.add(new Line(line, numberOffset + i + 1));
		}
		visible = nextVisible;

		List<LogLine> searchable = new ArrayList<>(nextVisible.size());
		for (Line line : nextVisible) searchable.add(line.line());
		LogSearch.Hits found = query.hits(searchable);
		hits = found.hits();
		hitsTruncated = found.truncated();
		if (currentMarker != null && markerIndex() < 0) currentMarker = null;

		int maxWidth = Math.max(40, bodyWidth() - GUTTER_WIDTH - 10);
		List<Row> nextRows = new ArrayList<>();
		int[] firstRow = new int[nextVisible.size()];
		for (int i = 0; i < nextVisible.size(); i++) {
			firstRow[i] = nextRows.size();
			String text = nextVisible.get(i).line().text();
			if (text.isEmpty()) {
				nextRows.add(new Row(i, 0, ""));
				continue;
			}
			int offset = 0;
			while (offset < text.length()) {
				String part = font.plainSubstrByWidth(text.substring(offset), maxWidth);
				if (part.isEmpty()) part = text.substring(offset, offset + 1);
				nextRows.add(new Row(i, offset, part));
				offset += part.length();
			}
		}
		Map<Integer, List<int[]>> ranges = new LinkedHashMap<>();
		for (LogSearch.Hit hit : hits) {
			ranges.computeIfAbsent(hit.lineIndex(), key -> new ArrayList<>()).add(new int[] {hit.start(), hit.end()});
		}
		rows = nextRows;
		firstRowOfLine = firstRow;
		hitRanges = ranges;
		rowsDirty = false;
		lastBodyWidth = bodyWidth();
	}

	private void detectQueryChange() {
		if (search == null) return;
		String value = search.getValue();
		boolean clearShown = !value.isEmpty();
		if (clearShown != lastClearVisible) {
			// The clear button appearing or disappearing changes the usable width.
			lastClearVisible = clearShown;
			layoutSearch();
		}
		if (value.equals(lastQuery)) return;
		lastQuery = value;
		rebuild();
		currentMarker = null;
		if (!matcher.active()) return;
		if (hits.isEmpty()) {
			setNotice("no matches for \"" + lastQuery + "\"", COLOR_WARNING);
			return;
		}
		// Following the tail means the newest match is the interesting one;
		// browsing history means the first match below the viewport is.
		LogSearch.Hit target = follow ? hits.getLast() : matcher.next(hits, topVisibleLine(), 0);
		reveal(target);
	}

	private void jumpToHit(int direction) {
		if (!matcher.active()) {
			setNotice("type a search first", COLOR_WARNING);
			focusSearch();
			return;
		}
		if (hits.isEmpty()) {
			setNotice("no matches for \"" + lastQuery + "\"", COLOR_WARNING);
			return;
		}
		int marked = markerIndex();
		LogSearch.Hit from = marked >= 0 ? hits.get(marked) : null;
		LogSearch.Hit target;
		if (from == null) {
			target = direction > 0
				? matcher.next(hits, topVisibleLine() - 1, Integer.MAX_VALUE)
				: matcher.previous(hits, bottomVisibleLine() + 1, 0);
		} else {
			target = direction > 0
				? matcher.next(hits, from.lineIndex(), from.start())
				: matcher.previous(hits, from.lineIndex(), from.start());
		}
		reveal(target);
	}

	/** Scrolls to a match, marks it current, and keeps following only if it is at the end. */
	private void reveal(LogSearch.Hit hit) {
		if (hit == null) return;
		currentMarker = markerFor(hit);
		if (hit.lineIndex() >= firstRowOfLine.length) return;
		int row = firstRowOfLine[hit.lineIndex()];
		int lastRow = rows.size() - 1;
		int viewport = Math.max(1, renderedVisibleRows == 0 ? visibleRowsEstimate() : renderedVisibleRows);
		int currentLast = lastRow - scrollOffset;
		int currentFirst = Math.max(0, currentLast - viewport + 1);
		if (row < currentFirst || row > currentLast) {
			scrollOffset = clamp(lastRow - row - viewport / 3, 0, Math.max(0, rows.size() - viewport));
		}
		follow = row >= Math.max(0, lastRow - viewport + 1);
	}

	private void jumpToNewest() {
		follow = true;
		scrollOffset = 0;
	}

	private void jumpToOldest() {
		follow = false;
		scrollOffset = Math.max(0, rows.size() - visibleRowsEstimate());
	}

	/** Positive delta looks at newer lines, negative at older ones. */
	private void scroll(int deltaRows) {
		int maximum = Math.max(0, rows.size() - visibleRowsEstimate());
		int next = clamp(scrollOffset - deltaRows, 0, maximum);
		scrollOffset = next;
		if (next == 0) follow = true;
		else if (deltaRows < 0) follow = false;
	}

	private void activateChip(Chip chip) {
		switch (chip) {
			case CASE -> {
				caseSensitive = !caseSensitive;
				rebuild();
				currentMarker = null;
			}
			case ONLY -> {
				onlyMatches = !onlyMatches;
				rebuild();
				currentMarker = null;
			}
			case PREV -> jumpToHit(-1);
			case NEXT -> jumpToHit(1);
			case LIVE -> jumpToNewest();
			case COPY -> copyLines();
			case LEVEL -> {
				levelFilterIndex = (levelFilterIndex + 1) % LEVEL_FILTERS.length;
				rebuild();
				currentMarker = null;
			}
		}
	}

	private boolean chipActive(Chip chip) {
		return switch (chip) {
			case CASE -> caseSensitive;
			case ONLY -> onlyMatches;
			case LIVE -> follow;
			case LEVEL -> levelFilter() != LogLine.Level.TRACE;
			default -> false;
		};
	}

	private String chipLabel(Chip chip) {
		return switch (chip) {
			case CASE -> "Aa";
			case ONLY -> "Only";
			case PREV -> "Prev";
			case NEXT -> "Next";
			case LIVE -> "Live";
			case COPY -> "Copy";
			case LEVEL -> "Level: " + (levelFilter() == LogLine.Level.TRACE ? "ALL" : levelFilter().name());
		};
	}

	private LogLine.Level levelFilter() {
		return LEVEL_FILTERS[clamp(levelFilterIndex, 0, LEVEL_FILTERS.length - 1)];
	}

	private void focusSearch() {
		if (search == null) return;
		setFocused(search);
		search.setFocused(true);
	}

	private void clearQuery() {
		if (search == null) return;
		search.setValue("");
		lastQuery = "";
		rebuild();
		currentMarker = null;
		focusSearch();
		setNotice("search cleared", COLOR_MUTED);
	}

	/**
	 * Copies the selected lines, or every match when only a query is set, or the
	 * whole buffer when neither is there - the three things a reader wants out
	 * of a log window. A selection whose lines have all been evicted falls
	 * through to the next rule instead of copying nothing.
	 */
	private void copyLines() {
		String text = null;
		String description = "";
		long low = Math.min(selectionAnchor, selectionCaret);
		long high = Math.max(selectionAnchor, selectionCaret);
		if (low >= 0) {
			List<String> selected = new ArrayList<>();
			for (Line line : visible) {
				if (line.number() >= low && line.number() <= high) selected.add(line.line().text());
			}
			if (!selected.isEmpty()) {
				text = String.join("\n", selected);
				description = selected.size() + (selected.size() == 1 ? " selected line" : " selected lines");
			}
		}
		if (text == null && matcher.active()) {
			List<String> matched = new ArrayList<>();
			for (Line line : visible) {
				if (matched.size() >= LogSearch.MAX_HITS) break;
				if (matcher.matches(line.line().text())) matched.add(line.line().text());
			}
			text = String.join("\n", matched);
			description = matched.size() + (matched.size() == 1 ? " matching line" : " matching lines");
		}
		if (text == null) {
			List<String> everything = new ArrayList<>();
			for (Line line : visible) everything.add(line.line().text());
			text = String.join("\n", everything);
			description = everything.size() + " buffered lines";
		}
		if (text.isEmpty()) {
			setNotice("nothing to copy", COLOR_WARNING);
			return;
		}
		minecraft.keyboardHandler.setClipboard(text);
		setNotice("copied " + description, COLOR_SUCCESS);
	}

	private void setNotice(String message, int color) {
		notice = message;
		noticeColor = color;
		noticeUntil = System.currentTimeMillis() + NOTICE_MILLIS;
	}

	private void setWidgetsVisible(boolean visible) {
		for (AbstractWidget widget : windowWidgets) widget.visible = visible;
	}

	private void layoutSearch() {
		if (search == null) return;
		int[] field = searchField();
		search.setX(field[0] + SEARCH_GLYPH_WIDTH);
		search.setY(field[1] + 4);
		search.setWidth(Math.max(40, field[2] - field[0] - SEARCH_GLYPH_WIDTH - (clearVisible() ? CLEAR_BOX_SIZE + 6 : 6)));
	}

	/** The framed search field, as {@code [left, top, right, bottom]} in screen space. */
	private int[] searchField() {
		int left = windowX + PADDING;
		int right = leftmostChipX() - 8;
		int top = windowY + TITLE_HEIGHT + 5;
		return new int[] {left, top, Math.max(left + 80, right), top + CHIP_HEIGHT};
	}

	private int leftmostChipX() {
		int x = windowX + windowWidth - PADDING;
		Chip[] rightToLeft = {Chip.LEVEL, Chip.COPY, Chip.LIVE, Chip.NEXT, Chip.PREV, Chip.ONLY, Chip.CASE};
		for (Chip chip : rightToLeft) {
			x -= chip.width();
			x -= CHIP_GAP;
		}
		return x + CHIP_GAP;
	}

	private int[] clearBox() {
		int[] field = searchField();
		return new int[] {field[2] - CLEAR_BOX_SIZE - 3, field[1] + 3, field[2] - 3, field[3] - 3};
	}

	private boolean clearVisible() {
		return search != null && !search.getValue().isEmpty();
	}

	private List<ChipRect> chipRects() {
		List<ChipRect> rects = new ArrayList<>();
		int y = windowY + TITLE_HEIGHT + (TOOLBAR_HEIGHT - CHIP_HEIGHT) / 2;
		int x = leftmostChipX();
		for (Chip chip : Chip.values()) {
			rects.add(new ChipRect(chip, x, y, chip.width(), CHIP_HEIGHT));
			x += chip.width() + CHIP_GAP;
		}
		return rects;
	}

	private boolean inTitleBar(double mouseX, double mouseY) {
		return mouseX >= windowX && mouseX < WindowChrome.controlsLeft(windowX, windowWidth)
			&& mouseY >= windowY && mouseY < windowY + TITLE_HEIGHT;
	}

	private boolean inBody(double mouseX, double mouseY) {
		int bodyTop = windowY + TITLE_HEIGHT + TOOLBAR_HEIGHT + 1;
		int bodyBottom = windowY + windowHeight - STATUS_HEIGHT - 4;
		// The line-number gutter counts as body, so clicking a number selects it.
		return mouseX >= windowX + PADDING && mouseX < windowX + windowWidth - PADDING
			&& mouseY >= bodyTop && mouseY < bodyBottom;
	}

	private boolean inResizeGrip(double mouseX, double mouseY) {
		return mouseX >= windowX + windowWidth - RESIZE_GRIP && mouseX < windowX + windowWidth
			&& mouseY >= windowY + windowHeight - RESIZE_GRIP && mouseY < windowY + windowHeight;
	}

	private boolean insideWindow(double mouseX, double mouseY) {
		int shownHeight = minimized ? TITLE_HEIGHT : windowHeight;
		return mouseX >= windowX && mouseX < windowX + windowWidth
			&& mouseY >= windowY && mouseY < windowY + shownHeight;
	}

	private static boolean inside(int[] rect, double mouseX, double mouseY) {
		return mouseX >= rect[0] && mouseX < rect[2] && mouseY >= rect[1] && mouseY < rect[3];
	}

	/** Width available to the wrapped log text. */
	private int bodyWidth() {
		return Math.max(80, windowWidth - PADDING * 2 - GUTTER_WIDTH - 10);
	}

	private int visibleRowsEstimate() {
		int bodyTop = windowY + TITLE_HEIGHT + TOOLBAR_HEIGHT + 1;
		int bodyBottom = windowY + windowHeight - STATUS_HEIGHT - 4;
		return Math.max(1, (bodyBottom - bodyTop) / LINE_HEIGHT);
	}

	private int pageStep() {
		return Math.max(1, visibleRowsEstimate() - 1);
	}

	private int topVisibleLine() {
		int last = rows.size() - 1 - scrollOffset;
		int first = Math.max(0, last - visibleRowsEstimate() + 1);
		return first < rows.size() ? rows.get(first).lineIndex() : Math.max(0, visible.size() - 1);
	}

	private int bottomVisibleLine() {
		int last = rows.size() - 1 - scrollOffset;
		return last >= 0 && last < rows.size() ? rows.get(last).lineIndex() : Math.max(0, visible.size() - 1);
	}

	/** The buffered line under the cursor, or null when nothing is shown. */
	private Line lineAt(double mouseY) {
		if (rows.isEmpty()) return null;
		int bodyTop = windowY + TITLE_HEIGHT + TOOLBAR_HEIGHT + 1;
		int row = (int) ((mouseY - bodyTop - 2) / LINE_HEIGHT);
		int last = rows.size() - 1 - scrollOffset;
		int first = Math.max(0, last - visibleRowsEstimate() + 1);
		int index = clamp(first + Math.max(0, row), 0, rows.size() - 1);
		int lineIndex = rows.get(index).lineIndex();
		return lineIndex >= 0 && lineIndex < visible.size() ? visible.get(lineIndex) : null;
	}

	private boolean isSelected(Line line) {
		if (selectionAnchor < 0 || selectionCaret < 0) return false;
		long low = Math.min(selectionAnchor, selectionCaret);
		long high = Math.max(selectionAnchor, selectionCaret);
		return line.number() >= low && line.number() <= high;
	}

	private Marker markerFor(LogSearch.Hit hit) {
		return new Marker(visible.get(hit.lineIndex()).number(), hit.start(), hit.end());
	}

	/** Position of the marked match in the current match list, or -1 when it is gone. */
	private int markerIndex() {
		if (currentMarker == null) return -1;
		for (int i = 0; i < hits.size(); i++) {
			LogSearch.Hit hit = hits.get(i);
			if (hit.start() == currentMarker.start() && hit.end() == currentMarker.end()
				&& hit.lineIndex() < visible.size()
				&& visible.get(hit.lineIndex()).number() == currentMarker.number()) {
				return i;
			}
		}
		return -1;
	}

	private boolean isCurrentHit(Line line, int[] range) {
		return currentMarker != null
			&& currentMarker.number() == line.number()
			&& currentMarker.start() == range[0]
			&& currentMarker.end() == range[1];
	}

	private String emptyMessage() {
		if (!tail.filePresent()) {
			return "waiting for logs/" + tail.file().getFileName() + " - the game writes it on the first log output";
		}
		if (tail.bufferedLines() == 0) return "the log is empty so far";
		return "no buffered lines match the current filter";
	}

	private static int levelColor(LogLine.Level level) {
		return switch (level) {
			case TRACE, DEBUG -> COLOR_DEBUG;
			case INFO -> COLOR_INFO;
			case WARN -> COLOR_WARNING;
			case ERROR, FATAL -> COLOR_ERROR;
			case UNKNOWN -> COLOR_CONTINUATION;
		};
	}

	private static int markerColor(LogLine.Level level) {
		return switch (level) {
			case WARN -> COLOR_WARNING;
			case ERROR, FATAL -> COLOR_ERROR;
			default -> 0;
		};
	}

	private void saveGeometry() {
		if (maximized) return;
		savedX = windowX;
		savedY = windowY;
		savedWidth = windowWidth;
		savedHeight = windowHeight;
	}

	private String fit(String value, int maxWidth) {
		if (maxWidth <= 4 || font.width(value) <= maxWidth) return value;
		String suffix = "...";
		return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width(suffix))) + suffix;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
