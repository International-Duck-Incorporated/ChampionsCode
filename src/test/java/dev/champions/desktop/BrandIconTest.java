package dev.champions.desktop;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two uses of the logo, which need opposite backgrounds.
 *
 * <p>The title-bar mark sits on the window's dark chrome, so its black source
 * background is keyed out. The window/taskbar icon is composited by the
 * desktop, where the same transparency read as a floating glyph with no shape;
 * it must keep its black background. The regression was that both used the
 * keyed image.</p>
 */
class BrandIconTest {

	private static int alpha(BufferedImage image, int x, int y) {
		return (image.getRGB(x, y) >>> 24) & 0xFF;
	}

	@Test void windowIconsAreOpaqueBlackSquares() {
		List<Image> icons = Brand.windowIcons();
		assertFalse(icons.isEmpty(), "the logo ships in the jar, so there are window icons");
		for (Image image : icons) {
			BufferedImage icon = (BufferedImage) image;
			assertEquals(icon.getWidth(), icon.getHeight(), "taskbar icons are square");
			for (int y = 0; y < icon.getHeight(); y += 3) {
				for (int x = 0; x < icon.getWidth(); x += 3) {
					assertEquals(255, alpha(icon, x, y),
						"pixel " + x + "," + y + " is transparent; the window icon must be opaque");
				}
			}
			// Opaque black at the corners: the artwork is centred, not stretched.
			for (int[] corner : new int[][] {{0, 0}, {icon.getWidth() - 1, 0},
					{0, icon.getHeight() - 1}, {icon.getWidth() - 1, icon.getHeight() - 1}}) {
				int rgb = icon.getRGB(corner[0], corner[1]) & 0xFFFFFF;
				assertEquals(0, rgb, "corner " + corner[0] + "," + corner[1] + " is not black");
			}
		}
	}

	@Test void theTitleBarMarkStillKeysItsBackgroundOut() {
		BufferedImage mark = Brand.logo(48);
		assertNotNull(mark, "the title-bar mark is drawn from the same asset");
		int transparent = 0;
		for (int y = 0; y < mark.getHeight(); y++) {
			for (int x = 0; x < mark.getWidth(); x++) {
				if (alpha(mark, x, y) == 0) transparent++;
			}
		}
		assertTrue(transparent > 0,
			"the title-bar mark is transparent around the strokes; keying it out is the point");
	}

	@Test void theArtworkItselfIsAvailable() {
		assertTrue(Brand.available(), "logo.png must be on the classpath");
	}
}
