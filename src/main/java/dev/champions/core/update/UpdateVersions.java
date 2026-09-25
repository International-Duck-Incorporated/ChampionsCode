package dev.champions.core.update;

import java.util.Locale;

/**
 * Order-preserving comparison for the project's version scheme:
 * {@code major.minor.patch[-prerelease]} with an optional leading {@code v}.
 * Follows the usual prerelease rules: a release outranks any prerelease of the
 * same core, numeric prerelease identifiers compare numerically, and numeric
 * identifiers rank below alphanumeric ones. Anything that does not fit the
 * scheme degrades to a stable plain string comparison.
 */
public final class UpdateVersions {
	private UpdateVersions() {}

	public static int compare(String left, String right) {
		String l = normalize(left);
		String r = normalize(right);
		int[] lc = coreSegments(l);
		int[] rc = coreSegments(r);
		if (lc == null || rc == null) return l.compareTo(r);
		for (int i = 0; i < Math.max(lc.length, rc.length); i++) {
			int lv = i < lc.length ? lc[i] : -1;
			int rv = i < rc.length ? rc[i] : -1;
			if (lv != rv) return Integer.compare(lv, rv);
		}
		String lp = prerelease(l);
		String rp = prerelease(r);
		if (lp.isEmpty() && rp.isEmpty()) return 0;
		if (lp.isEmpty()) return 1;
		if (rp.isEmpty()) return -1;
		return comparePrereleases(lp, rp);
	}

	private static String normalize(String version) {
		String clean = version == null ? "" : version.strip().toLowerCase(Locale.ROOT);
		if (clean.startsWith("v")) clean = clean.substring(1);
		return clean;
	}

	private static int[] coreSegments(String version) {
		int split = version.indexOf('-');
		String core = split < 0 ? version : version.substring(0, split);
		if (core.isEmpty()) return null;
		String[] parts = core.split("\\.");
		int[] segments = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				segments[i] = Integer.parseInt(parts[i]);
			} catch (NumberFormatException notNumeric) {
				return null;
			}
		}
		return segments;
	}

	private static String prerelease(String version) {
		int split = version.indexOf('-');
		return split < 0 ? "" : version.substring(split + 1);
	}

	private static int comparePrereleases(String left, String right) {
		String[] lp = left.split("\\.");
		String[] rp = right.split("\\.");
		for (int i = 0; i < Math.max(lp.length, rp.length); i++) {
			if (i >= lp.length) return -1;
			if (i >= rp.length) return 1;
			Long ln = tryParse(lp[i]);
			Long rn = tryParse(rp[i]);
			if (ln != null && rn != null) {
				if (!ln.equals(rn)) return Long.compare(ln, rn);
			} else if (ln != null) {
				return -1;
			} else if (rn != null) {
				return 1;
			} else if (!lp[i].equals(rp[i])) {
				return lp[i].compareTo(rp[i]);
			}
		}
		return 0;
	}

	private static Long tryParse(String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException notNumeric) {
			return null;
		}
	}
}
