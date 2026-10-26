package dev.champions.core.catalog;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Whether each catalogue entry still describes something that can happen.
 *
 * <p>A catalogue of bug classes goes stale quietly: the mechanism is described
 * correctly, the plugin still exists, and the operator has no way to tell that the
 * build in front of them fixed it two years ago. This table is the answer, entry by
 * entry: what the finding still applies to, what fixed it, when it was last checked,
 * and how it was checked.</p>
 *
 * <p>Nothing here upgrades a guess into a fact. {@link Status#UNVERIFIED} is the
 * default for anything not checked against upstream, and it is printed to the
 * operator as such rather than omitted.</p>
 */
public final class CatalogApplicability {

	/** What the entry is worth on a current build. */
	public enum Status {
		/**
		 * A standing design question, not a bug with version boundaries: SQL assembled
		 * by concatenation, a console dispatch that trusts expanded text. There is no
		 * "fixed in" to name, and the mechanism applies wherever the pattern does.
		 */
		STANDING,
		/**
		 * A specific defect with a fix. The artifact and the finding describe versions
		 * below {@code fixedIn}; a server at or above it will not reproduce.
		 */
		FIXED,
		/**
		 * Version-dependent and not confirmed against upstream in this build. The
		 * operator must check the running version before acting on it.
		 */
		UNVERIFIED,
		/**
		 * No longer applicable to any supported build. Kept because the tool still has
		 * to recognise the name and say so, rather than staying silent and implying
		 * there is nothing to know.
		 */
		OBSOLETE
	}

	/**
	 * One verdict.
	 *
	 * @param status        what the entry is worth on a current build
	 * @param affected      the versions the mechanism was observed on, or "any"
	 * @param fixedIn       the first version that is not affected, or empty
	 * @param latestSeen    the newest upstream release seen when this was written
	 * @param checkedOn     the date of the check, ISO-8601
	 * @param evidence      what the verdict rests on -- an advisory id, a changelog, a source read
	 */
	public record Applicability(Status status, String affected, String fixedIn, String latestSeen,
			String checkedOn, String evidence) {

		public Applicability {
			status = status == null ? Status.UNVERIFIED : status;
			affected = blankTo(affected, "unspecified");
			fixedIn = blankTo(fixedIn, "");
			latestSeen = blankTo(latestSeen, "unknown");
			checkedOn = blankTo(checkedOn, "");
			evidence = blankTo(evidence, "");
		}

		/** True when a server running {@code fixedIn} or later cannot reproduce this. */
		public boolean patchedInCurrentBuilds() {
			return status == Status.FIXED || status == Status.OBSOLETE;
		}

		/** One line for the console and the artifact header. */
		public String describe() {
			return switch (status) {
				case STANDING -> "standing design class, applies wherever the pattern does"
					+ (affected.equals("any") ? "" : " (observed on " + affected + ")");
				case FIXED -> "fixed in " + fixedIn + "; applies to " + affected
					+ ". A server at or above " + fixedIn + " will not reproduce this.";
				case OBSOLETE -> "obsolete: applies to " + affected
					+ (fixedIn.isBlank() ? "" : ", fixed in " + fixedIn)
					+ ". Nothing on a current build reproduces this.";
				case UNVERIFIED -> "version-dependent and not confirmed against upstream"
					+ (latestSeen.equals("unknown") ? "" : " (latest seen: " + latestSeen + ")")
					+ ". Check the running version before acting on it.";
			};
		}
	}

	private static String blankTo(String value, String fallback) {
		String cleaned = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
		return cleaned.isEmpty() ? fallback : cleaned;
	}

	/**
	 * The verdict for each catalogue entry.
	 *
	 * <p>Dated {@code 2026-10-26}. Entries whose status is {@link Status#FIXED} were
	 * checked against the upstream advisory on that date; entries marked
	 * {@link Status#UNVERIFIED} were not, and say so rather than borrowing the
	 * confidence of the ones that were.</p>
	 */
	private static final Map<String, Applicability> VERDICTS = verdicts();

	private static Map<String, Applicability> verdicts() {
		Map<String, Applicability> map = new LinkedHashMap<>();

		// -- checked against upstream on 2026-10-26 ------------------------------

		map.put("geyser_skin_ssrf", new Applicability(Status.FIXED,
			"<= 2.9.2", "2.9.3", "2.9.3", "2026-10-26",
			"CVE-2026-42188 / GHSA-xcfg-fcr5-gw9r: Bedrock player-head texture URL fetched without a host "
				+ "allowlist; advisory lists affected <= 2.9.2, patched 2.9.3 (TextureUrlChecker)."));

		map.put("worldedit_schematic", new Applicability(Status.OBSOLETE,
			"< 7.4.4", "7.4.4", "7.4.5", "2026-10-26",
			"WorldEdit 7.4.4 and later confine //schematic names to the configured folder; 7.4.5 is current. "
				+ "Kept as knowledge only, with no artifact."));

		// -- design classes: no version boundary to name -------------------------

		map.put("skript_console", new Applicability(Status.STANDING, "any", "", "unknown", "2026-10-26",
			"A script that dispatches console commands from player-controlled text is a configuration "
				+ "decision, not a defect with a fix version."));
		map.put("conditional_events_console", new Applicability(Status.STANDING, "any", "", "unknown",
			"2026-10-26", "Same class as skript_console: a configured console action fed from player input."));
		map.put("essentialsx_alt_commands", new Applicability(Status.STANDING, "any", "", "unknown",
			"2026-10-26",
			"Alias permission nodes are granted by configuration. Whether the wrapper checks the wrapped "
				+ "command's node is a per-server fact."));
		map.put("placeholder_expansion", new Applicability(Status.STANDING, "any", "", "unknown", "2026-10-26",
			"Expansion alone is never a finding; the sink that dispatches expanded text is configuration."));
		map.put("item_plugin_actions", new Applicability(Status.STANDING, "any", "", "unknown", "2026-10-26",
			"Items carrying console actions are a feature. The finding is who may create one."));
		map.put("chatgames_console", new Applicability(Status.STANDING, "any", "", "unknown", "2026-10-26",
			"A reward command built from a chat answer is configuration-side."));

		// -- version-dependent, not confirmed here -------------------------------

		map.put("deluxemenus_js", new Applicability(Status.UNVERIFIED, "unspecified", "", "unknown",
			"2026-10-26",
			"Whether a menu script can reach Java.type depends on the build and on whether scripting is "
				+ "enabled. Not confirmed against the current release in this build."));
		map.put("worldguard_yaml", new Applicability(Status.UNVERIFIED, "unspecified", "", "unknown",
			"2026-10-26",
			"Depends on the SnakeYAML version bundled and whether the loader opts into safe construction, "
				+ "neither of which a client can see."));
		map.put("mythicmobs_deser", new Applicability(Status.UNVERIFIED, "5.x (point releases differ)", "",
			"5.13.0", "2026-10-26",
			"Latest release seen 2026-10-26 is 5.13.0. The class-loader behaviour is reported to differ "
				+ "between 5.x point releases; no advisory was confirmed, so this stays unverified."));
		map.put("discordsrv_sql", new Applicability(Status.UNVERIFIED, "older builds", "", "unknown",
			"2026-10-26", "Only where an identifier is concatenated and a non-staff party controls the value."));
		map.put("coreprotect_rollback_sql", new Applicability(Status.UNVERIFIED, "unspecified", "", "unknown",
			"2026-10-26", "Requires the rollback permission in the first place; version not confirmed."));
		map.put("quickshop_economy", new Applicability(Status.UNVERIFIED, "unspecified", "", "unknown",
			"2026-10-26", "A class of state-handling bug rather than one defect; no version range confirmed."));
		map.put("viabackwards_snapshot", new Applicability(Status.UNVERIFIED, "pre-release builds", "",
			"unknown", "2026-10-26",
			"Applies to unattended snapshot builds; whether a given build carries it is not visible to a client."));
		map.put("zauctionhouse_deser", new Applicability(Status.UNVERIFIED, "unspecified", "", "unknown",
			"2026-10-26",
			"No advisory confirmed for the listing deserialisation path; kept as knowledge with no artifact."));
		map.put("bluemap_ssrf", new Applicability(Status.UNVERIFIED, "configuration-dependent", "", "unknown",
			"2026-10-26",
			"A remote marker or storage URL is a configuration choice; not a version boundary."));
		map.put("auth_plugins", new Applicability(Status.STANDING, "any", "", "unknown", "2026-10-26",
			"Token disclosure is a hosting and configuration question, not a fixed defect."));

		return Map.copyOf(map);
	}

	private CatalogApplicability() {}

	/** The verdict for a catalogue entry id, if this build has one. */
	public static Optional<Applicability> forEntry(String entryId) {
		if (entryId == null) return Optional.empty();
		return Optional.ofNullable(VERDICTS.get(entryId.strip().toLowerCase(Locale.ROOT)));
	}

	/** Every verdict, for the coverage test. */
	public static Map<String, Applicability> all() {
		return VERDICTS;
	}

	/** Entries that a current build cannot reproduce, for the console summary. */
	public static List<String> notReproducibleOnCurrentBuilds() {
		return VERDICTS.entrySet().stream()
			.filter(entry -> entry.getValue().patchedInCurrentBuilds())
			.map(Map.Entry::getKey)
			.sorted()
			.toList();
	}

	/** Today, for a caller that wants to stamp a scan rather than a table. */
	public static String today() {
		return LocalDate.now().toString();
	}
}
