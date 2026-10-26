package dev.champions.desktop;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ChampionsCode logo, loaded from the asset that already ships inside the
 * JAR ({@code assets/champions/logo.png}) instead of being redrawn in code, so
 * the title bar, the taskbar entry and the documentation all show the same
 * artwork the mod uses.
 *
 * <p>The source is white and crimson on solid black, which is keyed to
 * transparency once here; the mark is thin strokes, so it is re-scaled with a
 * high-quality filter rather than {@code getScaledInstance}.</p>
 */
public final class Brand {
	private static final String LOGO = "/assets/champions/logo.png";
	private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256};

	/** Breathing room kept around the artwork once its empty canvas is trimmed. */
	private static final float PAD = 0.06f;

	private static final Map<Integer, BufferedImage> SCALED = new ConcurrentHashMap<>();
	private static BufferedImage keyed;
	private static boolean loaded;
	private static BufferedImage cropped;
	private static boolean cropAttempted;
	private static List<Image> icons;

	private Brand() {}

	/**
	 * The logo with its background removed, {@code height} pixels tall and its
	 * own aspect kept (the artwork is wider than it is tall).
	 *
	 * <p>The empty canvas around the mark is cropped first, because at title-bar
	 * size the source's own margins would read as a stray gap. The {@code >_}
	 * accent is only a few pixels thick, so 30 px is about the floor where it
	 * still reads as the logo rather than a smudge.</p>
	 */
	public static synchronized BufferedImage logo(int height) {
		BufferedImage source = trimmed();
		if (source == null) return null;
		int target = Math.max(16, height);
		return SCALED.computeIfAbsent(target, key -> {
			double ratio = (double) key / source.getHeight();
			int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
			return resize(source, width, key);
		});
	}

	/** Window icons in the sizes the OS asks for (taskbar, alt-tab, dock). */
	public static synchronized List<Image> windowIcons() {
		if (icons != null) return icons;
		BufferedImage source = source();
		if (source == null) return List.of();
		BufferedImage artwork = trimmed();
		if (artwork == null) return List.of();
		List<Image> sizes = new ArrayList<>();
		for (int size : ICON_SIZES) sizes.add(square(artwork, size));
		icons = List.copyOf(sizes);
		return icons;
	}

	/** True when the real logo was found; callers can fall back to a vector. */
	public static boolean available() {
		return source() != null;
	}

	private static synchronized BufferedImage source() {
		if (loaded) return keyed;
		loaded = true;
		BufferedImage raw = read(LOGO);
		keyed = raw == null ? null : key(raw);
		return keyed;
	}

	private static BufferedImage read(String resource) {
		try (InputStream stream = Brand.class.getResourceAsStream(resource)) {
			if (stream == null) return null;
			return ImageIO.read(stream);
		} catch (Exception unreadable) {
			return null;
		}
	}

	/**
	 * Solid black becomes transparent; strokes keep their colour and edges.
	 *
	 * <p>The logo's {@code >_} accent is painted {@code #9C0018} — a very dark
	 * crimson that all but disappears against a near-black title bar, and whose
	 * antialiased edge would otherwise be read as half-transparent. It is lifted
	 * to the same crimson the rest of the UI uses ({@link Theme#ACCENT}) at full
	 * opacity; the geometry, and therefore the logo, is unchanged.</p>
	 */
	private static BufferedImage key(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
		for (int index = 0; index < pixels.length; index++) {
			int pixel = pixels[index];
			int r = (pixel >> 16) & 0xFF;
			int g = (pixel >> 8) & 0xFF;
			int b = pixel & 0xFF;
			int coverage = Math.max(r, Math.max(g, b));
			if (coverage < 6) {
				pixels[index] = 0;
				continue;
			}
			boolean crimson = r > 40 && r > g * 2 && r > b * 2;
			if (crimson) {
				// The accent is a solid stroke, not a gradient: give it full
				// opacity scaled by its own peak (156) instead of its raw value.
				int alpha = Math.min(255, Math.round(coverage * 255f / 156f));
				pixels[index] = (alpha << 24) | (0xE8 << 16) | (0x48 << 8) | 0x4F;
				continue;
			}
			// Unpremultiply, so thin antialiased strokes keep their hue instead of
			// darkening towards the black they were drawn on.
			int red = Math.min(255, r * 255 / coverage);
			int green = Math.min(255, g * 255 / coverage);
			int blue = Math.min(255, b * 255 / coverage);
			pixels[index] = (coverage << 24) | (red << 16) | (green << 8) | blue;
		}
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		out.setRGB(0, 0, width, height, pixels, 0, width);
		return out;
	}

	/** The keyed artwork with the source's empty margins cropped away. */
	private static synchronized BufferedImage trimmed() {
		if (cropped != null || cropAttempted) return cropped;
		cropAttempted = true;
		BufferedImage source = source();
		if (source == null) return null;
		int minX = source.getWidth();
		int minY = source.getHeight();
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < source.getHeight(); y++) {
			for (int x = 0; x < source.getWidth(); x++) {
				if ((source.getRGB(x, y) >>> 24) > 8) {
					if (x < minX) minX = x;
					if (y < minY) minY = y;
					if (x > maxX) maxX = x;
					if (y > maxY) maxY = y;
				}
			}
		}
		if (maxX < minX || maxY < minY) {
			cropped = source;
			return cropped;
		}
		int pad = Math.max(2, Math.round((maxY - minY + 1) * PAD));
		int width = source.getWidth();
		int height = source.getHeight();
		int x = Math.max(0, minX - pad);
		int y = Math.max(0, minY - pad);
		int w = Math.min(width - x, maxX - minX + 1 + pad * 2);
		int h = Math.min(height - y, maxY - minY + 1 + pad * 2);
		cropped = source.getSubimage(x, y, w, h);
		return cropped;
	}

	private static BufferedImage square(BufferedImage artwork, int size) {
		return resizeToSquare(artwork, size);
	}

	private static BufferedImage resizeToSquare(BufferedImage artwork, int size) {
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int inner = Math.max(1, Math.round(size * 0.9f));
			double ratio = Math.min((double) inner / artwork.getWidth(),
				(double) inner / artwork.getHeight());
			int w = Math.max(1, (int) Math.round(artwork.getWidth() * ratio));
			int h = Math.max(1, (int) Math.round(artwork.getHeight() * ratio));
			g.drawImage(artwork, (size - w) / 2, (size - h) / 2, w, h, null);
		} finally {
			g.dispose();
		}
		return out;
	}

	private static BufferedImage resize(BufferedImage image, int width, int height) {
		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = scaled.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.drawImage(image, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		return scaled;
	}

}
