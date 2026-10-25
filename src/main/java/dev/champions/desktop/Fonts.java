package dev.champions.desktop;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Font selection for the desktop app.
 *
 * <p>Swing's logical {@code Font.MONOSPACED} resolves to Courier New on Windows
 * and to whatever the JRE feels like elsewhere, which is what made the first
 * build look thin and grainy. This picks the best installed monospace family —
 * Consolas / Cascadia Mono ship with Windows and VS, JetBrains Mono and
 * Menlo/SF Mono cover the other platforms, and DejaVu/Liberation cover Linux —
 * then falls back to the logical font. Results are cached; the scan runs once.
 * </p>
 */
public final class Fonts {
	private static final String[] MONOSPACE = {
		"JetBrains Mono", "Cascadia Mono", "Cascadia Code", "Consolas", "SF Mono", "Menlo",
		"DejaVu Sans Mono", "Liberation Mono", "Ubuntu Mono", "Noto Sans Mono", "Courier New"
	};
	private static final String[] SANS = {
		"Segoe UI Variable Text", "Segoe UI", "Inter", "SF Pro Text", "Helvetica Neue",
		"Cantarell", "Ubuntu", "Noto Sans", "DejaVu Sans", "Liberation Sans"
	};

	private static volatile String monoFamily;
	private static volatile String sansFamily;

	private Fonts() {}

	public static String monoFamily() {
		String family = monoFamily;
		if (family == null) {
			family = firstInstalled(MONOSPACE, Font.MONOSPACED);
			monoFamily = family;
		}
		return family;
	}

	public static String sansFamily() {
		String family = sansFamily;
		if (family == null) {
			family = firstInstalled(SANS, Font.SANS_SERIF);
			sansFamily = family;
		}
		return family;
	}

	public static Font mono(int size) {
		return new Font(monoFamily(), Font.PLAIN, size);
	}

	public static Font monoBold(int size) {
		return new Font(monoFamily(), Font.BOLD, size);
	}

	public static Font sans(int size, boolean bold) {
		return new Font(sansFamily(), bold ? Font.BOLD : Font.PLAIN, size);
	}

	/** True when a real, named family was found rather than the logical fallback. */
	public static boolean hasRealMonospace() {
		return !monoFamily().equals(Font.MONOSPACED) && !monoFamily().equals(Font.DIALOG);
	}

	private static String firstInstalled(String[] candidates, String fallback) {
		Set<String> installed;
		try {
			installed = new LinkedHashSet<>(Arrays.asList(
				GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)));
		} catch (RuntimeException headlessOrBroken) {
			return fallback;
		}
		for (String candidate : candidates) {
			if (installed.contains(candidate)) return candidate;
		}
		return fallback;
	}
}
