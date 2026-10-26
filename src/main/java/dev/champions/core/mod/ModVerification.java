package dev.champions.core.mod;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What each catalogue family looked like upstream on the day it was checked.
 *
 * <p>A catalogue of mod descriptions has the same failure as a catalogue of exploits:
 * it reads the same in five years. This table answers the one question a description
 * cannot -- when was this family last looked at, what was the newest release then,
 * and does that release exist for the game version this build targets.</p>
 *
 * <p>The date is the point. An entry with no date is a claim about an unknown day, and
 * {@link #staleAt(LocalDate, int)} makes the build say so once the table gets old.</p>
 */
public final class ModVerification {

	/**
	 * One upstream check.
	 *
	 * @param slug     the project page the version was read from
	 * @param version  the version the mod loader reports for that release
	 * @param checkedOn the date of the check, ISO-8601
	 * @param forTarget whether a build exists for the game version this project targets
	 */
	public record Seen(String slug, String version, String checkedOn, boolean forTarget) {

		public Seen {
			slug = blankTo(slug, "unknown");
			version = blankTo(version, "unknown");
			checkedOn = blankTo(checkedOn, "");
		}

		/** The date, or null when the entry carries none. */
		public LocalDate date() {
			try { return LocalDate.parse(checkedOn); } catch (DateTimeParseException | NullPointerException e) { return null; }
		}
	}

	private static String blankTo(String value, String fallback) {
		String cleaned = value == null ? "" : value.strip();
		return cleaned.isEmpty() ? fallback : cleaned;
	}

	/** The date every entry below was written on. Kept as one constant on purpose. */
	public static final String CHECKED_ON = "2026-10-26";

	private static final Map<String, Seen> SEEN = seen();

	private static Map<String, Seen> seen() {
		Map<String, Seen> map = new LinkedHashMap<>();
		map.put("cobblemon", new Seen("cobblemon", "1.8.3F", CHECKED_ON, false));
		map.put("voicechat", new Seen("simple-voice-chat", "2.6.26+26.3", CHECKED_ON, true));
		map.put("luckperms", new Seen("luckperms", "5.5.85", CHECKED_ON, true));
		map.put("fabric-api", new Seen("fabric-api", "0.165.0+26.3", CHECKED_ON, true));
		map.put("create", new Seen("create", "6.0.10+mc1.21.1", CHECKED_ON, false));
		map.put("lithium", new Seen("lithium", "0.26.2+mc26.3", CHECKED_ON, true));
		map.put("sodium", new Seen("sodium", "0.9.3-beta.1+mc26.3", CHECKED_ON, true));
		map.put("iris", new Seen("iris", "1.11.7+mc26.3", CHECKED_ON, true));
		map.put("distanthorizons", new Seen("distanthorizons", "3.3.4", CHECKED_ON, true));
		map.put("terralith", new Seen("terralith", "2.6.5", CHECKED_ON, true));
		map.put("jei", new Seen("jei", "31.17.0.94", CHECKED_ON, true));
		map.put("roughlyenoughitems", new Seen("rei", "26.3.824", CHECKED_ON, true));
		map.put("emi", new Seen("emi", "1.1.24+1.21.1", CHECKED_ON, false));
		map.put("journeymap", new Seen("journeymap", "26.3-6.0.10", CHECKED_ON, true));
		map.put("xaerominimap", new Seen("xaeros-minimap", "26.6.2", CHECKED_ON, true));
		map.put("modmenu", new Seen("modmenu", "21.0.0", CHECKED_ON, true));
		map.put("cloth-config", new Seen("cloth-config", "26.3.159", CHECKED_ON, true));
		map.put("architectury", new Seen("architectury-api", "22.0.5", CHECKED_ON, true));
		map.put("geyser", new Seen("geyser", "2.12.0-SNAPSHOT", CHECKED_ON, true));
		map.put("viaversion", new Seen("viaversion", "5.12.2-SNAPSHOT", CHECKED_ON, true));
		map.put("spark", new Seen("spark", "1.10.187", CHECKED_ON, true));
		map.put("floodgate", new Seen("floodgate", "2.2.7-SNAPSHOT", CHECKED_ON, true));
		map.put("fabricproxy-lite", new Seen("fabricproxy-lite", "2.12.0", CHECKED_ON, true));
		map.put("easyauth", new Seen("easyauth", "3.4.4", CHECKED_ON, true));
		map.put("viabackwards", new Seen("viabackwards", "5.12.2-SNAPSHOT", CHECKED_ON, true));
		map.put("viafabricplus", new Seen("viafabricplus", "5.1.2", CHECKED_ON, true));
		map.put("bluemap", new Seen("bluemap", "5.29", CHECKED_ON, true));
		map.put("squaremap", new Seen("squaremap", "1.4.3", CHECKED_ON, true));
		map.put("journeymap_webmap", new Seen("journeymap-web-map", "1.0.13", CHECKED_ON, true));
		map.put("e4mc", new Seen("e4mc", "6.2.3+modern", CHECKED_ON, true));
		map.put("plasmovoice", new Seen("plasmo-voice", "2.2.0-beta.2", CHECKED_ON, true));
		map.put("carpet", new Seen("carpet", "26.3+v260915", CHECKED_ON, true));
		map.put("computercraft", new Seen("cc-tweaked", "1.120.3", CHECKED_ON, true));
		map.put("minescript", new Seen("minescript", "5.0", CHECKED_ON, true));
		map.put("trustedclients", new Seen("trustedclients", "0.7.0", CHECKED_ON, true));
		map.put("openpartiesandclaims", new Seen("open-parties-and-claims", "0.33.0", CHECKED_ON, true));
		map.put("fabric-permissions-api-v0", new Seen("fabric-permissions-api", "0.7.0", CHECKED_ON, true));
		map.put("worldedit", new Seen("worldedit", "7.4.6+7648-00664b0", CHECKED_ON, true));
		map.put("ledger", new Seen("ledger", "1.3.25", CHECKED_ON, true));
		map.put("fastback", new Seen("fastback", "0.35.0+26.3.0", CHECKED_ON, true));
		map.put("nochatreports", new Seen("no-chat-reports", "26.3-v2.21.0", CHECKED_ON, true));
		map.put("essential-container", new Seen("essential", "1.0.0", CHECKED_ON, true));
		map.put("customskinloader-bootstrap", new Seen("customskinloader", "15.1", CHECKED_ON, true));
		map.put("no-telemetry", new Seen("no-telemetry", "1.10.0", CHECKED_ON, true));
		map.put("chunky", new Seen("chunky", "1.5.3", CHECKED_ON, true));
		return Map.copyOf(map);
	}

	private ModVerification() {}

	/** What upstream looked like for an installed id, following aliases. */
	public static Optional<Seen> seen(String modId) {
		if (modId == null) return Optional.empty();
		ModCatalog.Profile profile = ModCatalog.canonical(modId).orElse(null);
		if (profile == null) return Optional.empty();
		return Optional.ofNullable(SEEN.get(profile.id()));
	}

	/** Every entry, for the coverage test and the documentation table. */
	public static Map<String, Seen> all() {
		return SEEN;
	}

	/** Families that have no build for the game version this project targets. */
	public static List<String> withoutCurrentBuild() {
		return SEEN.entrySet().stream().filter(entry -> !entry.getValue().forTarget())
			.map(Map.Entry::getKey).sorted().toList();
	}

	/**
	 * Entries whose check is older than {@code days} before {@code asOf}.
	 *
	 * <p>Takes the date as a parameter so the rule can be tested without waiting a year,
	 * and so a caller can ask about a build rather than about today.</p>
	 */
	public static List<String> staleAt(LocalDate asOf, int days) {
		if (asOf == null) return List.of();
		LocalDate threshold = asOf.minusDays(Math.max(1, days));
		return SEEN.entrySet().stream()
			.filter(entry -> {
				LocalDate date = entry.getValue().date();
				return date == null || date.isBefore(threshold);
			})
			.map(Map.Entry::getKey).sorted().toList();
	}

	/** Entries with no parseable date, which can never be trusted. */
	public static List<String> undated() {
		return SEEN.entrySet().stream().filter(entry -> entry.getValue().date() == null)
			.map(Map.Entry::getKey).sorted().toList();
	}

	/** The project page an entry came from, for the report. */
	public static String sourceUrl(Seen seen) {
		if (seen == null || seen.slug().equals("unknown")) return "";
		return "https://modrinth.com/mod/" + seen.slug().toLowerCase(Locale.ROOT);
	}
}
