package dev.champions.desktop;

import java.awt.Cursor;
import java.awt.Point;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The window edge/cursor mapping, tested without a display.
 *
 * <p>These look like trivia, but the resize corners reported the wrong
 * direction (bottom-right drew a north-east arrow), and the resize cursor used
 * to stay on screen because the listener that set it stopped receiving events
 * as soon as the pointer moved onto a child component. The mask and the cursor
 * are pure functions, so they can be pinned here; the listener wiring is
 * covered by the fact that the same function drives it.</p>
 */
class ShellCursorTest {

	private static final int WIDTH = 800;
	private static final int HEIGHT = 600;
	private static final int BORDER = 6;

	private static int maskAt(int x, int y) {
		return Shell.edgeMask(new Point(x, y), WIDTH, HEIGHT, BORDER);
	}

	@Test void theMiddleOfTheWindowIsNotAnEdge() {
		// The old bug: this kept whatever cursor was last set, because the motion
		// listener never fired here. A 0 mask is what makes the caller reset to
		// the default cursor.
		assertEquals(0, maskAt(WIDTH / 2, HEIGHT / 2));
		assertEquals(0, maskAt(BORDER + 1, BORDER + 1));
	}

	@Test void eachEdgeReportsItsOwnBit() {
		assertEquals(2, maskAt(1, HEIGHT / 2));
		assertEquals(4, maskAt(WIDTH - 1, HEIGHT / 2));
		assertEquals(8, maskAt(WIDTH / 2, 1));
		assertEquals(16, maskAt(WIDTH / 2, HEIGHT - 1));
	}

	@Test void cornersReportBothBits() {
		assertEquals(2 | 8, maskAt(1, 1));
		assertEquals(4 | 8, maskAt(WIDTH - 1, 1));
		assertEquals(2 | 16, maskAt(1, HEIGHT - 1));
		assertEquals(4 | 16, maskAt(WIDTH - 1, HEIGHT - 1));
	}

	@Test void outsideTheWindowIsNotAnEdge() {
		assertEquals(-1, maskAt(-1, 10));
		assertEquals(-1, maskAt(10, -1));
		assertEquals(-1, maskAt(WIDTH + 1, 10));
		assertEquals(-1, maskAt(10, HEIGHT + 1));
	}

	@Test void everyCornerPointsTheRightWay() {
		assertEquals(Cursor.NW_RESIZE_CURSOR, Shell.cursorForMask(2 | 8));
		assertEquals(Cursor.NE_RESIZE_CURSOR, Shell.cursorForMask(4 | 8));
		assertEquals(Cursor.SW_RESIZE_CURSOR, Shell.cursorForMask(2 | 16));
		assertEquals(Cursor.SE_RESIZE_CURSOR, Shell.cursorForMask(4 | 16));
		assertEquals(Cursor.E_RESIZE_CURSOR, Shell.cursorForMask(2));
		assertEquals(Cursor.E_RESIZE_CURSOR, Shell.cursorForMask(4));
		assertEquals(Cursor.N_RESIZE_CURSOR, Shell.cursorForMask(8));
		assertEquals(Cursor.N_RESIZE_CURSOR, Shell.cursorForMask(16));
	}

	@Test void noEdgeMeansTheDefaultCursor() {
		// Not a resize arrow: this is the value that replaces the one left behind.
		assertEquals(Cursor.DEFAULT_CURSOR, Shell.cursorForMask(0));
		assertEquals(Cursor.DEFAULT_CURSOR, Shell.cursorForMask(-1));
	}
}
