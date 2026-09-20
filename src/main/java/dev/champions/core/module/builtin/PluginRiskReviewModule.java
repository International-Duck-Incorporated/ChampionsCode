package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Passively maps advertised command roots to defensive review profiles.
 * A root match is only a lead: aliases can be shared, hidden, or reassigned.
 */
public final class PluginRiskReviewModule implements ResearchModule {
	private static final List<Profile> PROFILES = List.of(
		new Profile("BlueMap", Set.of("bluemap"),
			"Verify the build against upstream releases, restrict web-map administration, and review outbound network and database settings."),
		new Profile("BungeeGuard or proxy authentication", Set.of("bungeeguard", "authme"),
			"Patch proxy authentication components, rotate exposed secrets, and prevent direct backend access."),
		new Profile("ChatGames", Set.of("chatgames"),
			"Treat reward commands and player-controlled placeholders as untrusted input; avoid console-context interpolation."),
		new Profile("CoreProtect", Set.of("coreprotect", "co"),
			"Use a current trusted build, limit rollback and lookup permissions, and give its database account least privilege."),
		new Profile("DeluxeMenus", Set.of("deluxemenus", "dm"),
			"Audit menu actions, placeholders, script requirements, and imported configuration before reloading it."),
		new Profile("DiscordSRV", Set.of("discordsrv", "discord"),
			"Use a trusted current build, protect bot and database credentials, and restrict bridge administration permissions."),
		new Profile("EssentialsX", Set.of("essentials", "ess"),
			"Review command aliases and per-role permissions; update from the official source and test permission inheritance."),
		new Profile("Geyser", Set.of("geyser"),
			"Check the exact release against upstream advisories and constrain unnecessary outbound URL access at the host or network layer."),
		new Profile("MythicMobs", Set.of("mythicmobs", "mythic", "mobs", "mm"),
			"Use a current trusted build and review imported packs, serialization boundaries, and administrator-only loading paths."),
		new Profile("ProtocolLib", Set.of("protocollib", "plib"),
			"Keep it aligned with the server version and audit dependent plugins that parse or create protocol data."),
		new Profile("QuickShop", Set.of("quickshop", "qs"),
			"Review economy permissions and database least privilege, and test price and inventory invariants on a staging server."),
		new Profile("Skript", Set.of("skript", "sk"),
			"Restrict script installation and reload permissions, inventory script sources, and review privileged effects."),
		new Profile("TAB", Set.of("tab"),
			"Use a current trusted build and review configuration parsing, placeholders, and administrative permissions."),
		new Profile("ViaVersion family", Set.of("viaversion", "viabackwards", "via"),
			"Prefer stable releases, avoid unattended snapshot builds, and keep all Via components on compatible versions."),
		new Profile("WorldEdit", Set.of("worldedit", "we"),
			"Restrict schematic and filesystem-adjacent operations, use a current build, and apply least-privilege command permissions."),
		new Profile("WorldGuard", Set.of("worldguard", "wg"),
			"Use a current build, protect region configuration from untrusted edits, and review reload permissions and parser dependencies.")
	);

	@Override public String id() { return "auxiliary/audit/plugin_risk_review"; }
	@Override public String name() { return "Plugin risk review"; }
	@Override public String description() {
		return "Maps command roots already advertised to the player to bounded defensive review profiles. It sends nothing and never treats an alias as proof of a plugin or vulnerability.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("PROFILE", "all", false, false, "all or a profile name such as Geyser, Skript, or DiscordSRV"),
			new ModuleOption("MAX_FINDINGS", "12", false, false, "Maximum matching profiles shown, 1-16")
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		Set<String> roots = new LinkedHashSet<>();
		for (String root : context.advertisedCommands()) {
			String normalized = normalizeRoot(root);
			if (!normalized.isBlank()) roots.add(normalized);
		}

		String requested = value(options, "PROFILE", "all").strip();
		int maxFindings = boundedInt(value(options, "MAX_FINDINGS", "12"), 12, 1, PROFILES.size());
		List<Profile> selected = selectProfiles(requested);
		List<Finding> findings = new ArrayList<>();
		if (selected.isEmpty()) {
			findings.add(new Finding(Finding.Severity.INFO, "Unknown review profile",
				"No profile matched '" + safeText(requested, 48) + "'. Available profiles: " +
				PROFILES.stream().map(Profile::name).sorted().toList(),
				"Use PROFILE=all or one of the listed names."));
			return new ModuleResult("Plugin risk review completed without a profile match", findings);
		}

		List<ProfileMatch> matches = selected.stream()
			.map(profile -> new ProfileMatch(profile,
				profile.roots().stream().filter(roots::contains).sorted().toList()))
			.filter(match -> !match.roots().isEmpty())
			.toList();
		for (ProfileMatch match : matches.stream().limit(maxFindings).toList()) {
			findings.add(new Finding(Finding.Severity.LOW, match.profile().name() + " review lead",
				"Advertised root match: " + match.roots().stream().map(root -> "/" + root).toList() +
				". This is not proof of plugin identity, version, reachability, or vulnerability.",
				match.profile().recommendation()));
		}

		if (matches.isEmpty()) {
			findings.add(new Finding(Finding.Severity.PASS, "Selected review profiles",
				"No selected profile roots appeared in the command tree advertised to this player.",
				"Absence from tab completion is not proof of absence. Confirm the installed inventory from the authorized server console."));
		} else if (matches.size() > maxFindings) {
			findings.add(new Finding(Finding.Severity.INFO, "Finding limit",
				(matches.size() - maxFindings) + " additional matching profiles were omitted by MAX_FINDINGS.",
				"Raise MAX_FINDINGS if the complete passive lead list is needed."));
		}
		findings.add(new Finding(Finding.Severity.INFO, "Evidence boundary",
			"Reviewed " + roots.size() + " advertised roots without sending commands, reading packet bodies, or testing any exploit path.",
			"Validate all leads with an administrator-supplied plugin inventory and official upstream advisories."));
		return new ModuleResult("Passive plugin risk review completed", findings);
	}

	private static List<Profile> selectProfiles(String requested) {
		if (requested.isBlank() || requested.equalsIgnoreCase("all")) return PROFILES;
		String query = requested.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		if (query.isBlank()) return List.of();
		return PROFILES.stream().filter(profile ->
			profile.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").contains(query)).toList();
	}

	private static String normalizeRoot(String value) {
		if (value == null) return "";
		String root = value.strip().toLowerCase(Locale.ROOT);
		while (root.startsWith("/")) root = root.substring(1);
		int colon = root.indexOf(':');
		if (colon >= 0 && colon + 1 < root.length()) root = root.substring(colon + 1);
		return root.matches("[a-z0-9_.-]{1,64}") ? root : "";
	}

	private static String value(Map<String, String> options, String key, String fallback) {
		String value = options.get(key);
		return value == null ? fallback : value;
	}

	private static int boundedInt(String text, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(text))); }
		catch (RuntimeException ignored) { return fallback; }
	}

	private static String safeText(String text, int max) {
		String cleaned = text.replaceAll("\\p{Cntrl}", " ");
		return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
	}

	private record Profile(String name, Set<String> roots, String recommendation) {}
	private record ProfileMatch(Profile profile, List<String> roots) {}
}
