package dev.champions.core.module.builtin;

import dev.champions.core.module.CommandDescriptor;
import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which administrative command surface this account was offered.
 *
 * <p>The command tree a server sends is built for the connection that asked for
 * it, so a root that appears here is a root the server believes this account may
 * use -- usually because it was granted the node, sometimes because the plugin
 * never filtered its tree at all. That distinction is the audit question: an
 * administrative root visible to an account that should not have it is either a
 * misconfigured permission or a plugin that does not check one, and both are
 * worth finding before someone else does.</p>
 *
 * <p>Nothing is sent. This reads the tree already delivered to the client and
 * classifies it.</p>
 */
public final class AdminCommandExposureModule implements ResearchModule {

	/** What an exposed root lets someone do, and how much that matters. */
	private enum Capability {
		AUTHORITY("authority", Finding.Severity.HIGH,
			"Changes who is allowed to do what. Exposure to a non-staff account is a permission-model failure."),
		CODE("code", Finding.Severity.HIGH,
			"Runs or installs code, scripts or remote content. Exposure means the account can extend the server."),
		MODERATION("moderation", Finding.Severity.MEDIUM,
			"Acts on players. Expected for staff, a finding for anyone else."),
		WORLD("world", Finding.Severity.MEDIUM,
			"Alters world state. Review whether this account should build or edit at all."),
		PLAYER("player", Finding.Severity.MEDIUM,
			"Alters a player's state or inventory. Often granted broadly; check what it can do to others."),
		DISCLOSURE("disclosure", Finding.Severity.LOW,
			"Reveals server or player facts. Low on its own, useful when chained with something else.");

		private final String label;
		private final Finding.Severity severity;
		private final String meaning;

		Capability(String label, Finding.Severity severity, String meaning) {
			this.label = label;
			this.severity = severity;
			this.meaning = meaning;
		}

		String label() { return label; }
		Finding.Severity severity() { return severity; }
		String meaning() { return meaning; }
	}

	private static final Map<String, Capability> ROOTS = roots();

	private static Map<String, Capability> roots() {
		Map<String, Capability> map = new LinkedHashMap<>();
		// Authority
		for (String root : List.of("op", "deop", "permission", "permissions", "perm", "perms",
				"pex", "lp", "luckperms", "group", "groups", "rank", "ranks", "sudo", "runas",
				"execute_as", "auth", "authorize")) {
			map.put(root, Capability.AUTHORITY);
		}
		// Code execution and remote content
		for (String root : List.of("script", "scripts", "sk", "skript", "eval", "exec", "js",
				"javascript", "lua", "lua_run", "function", "ecloud", "papi", "placeholderapi",
				"compile", "deploy", "reload")) {
			map.put(root, Capability.CODE);
		}
		// Moderation
		for (String root : List.of("ban", "tempban", "unban", "pardon", "kick", "mute", "unmute",
				"tempmute", "warn", "jail", "unjail", "whitelist", "banlist", "freeze", "staff")) {
			map.put(root, Capability.MODERATION);
		}
		// World
		for (String root : List.of("setblock", "fill", "clone", "gamerule", "time", "weather",
				"difficulty", "place", "structure", "worldedit", "we", "worldguard", "wg",
				"brush", "tool", "schematic", "schematics", "region", "regions")) {
			map.put(root, Capability.WORLD);
		}
		// Player state
		for (String root : List.of("give", "effect", "enchant", "xp", "experience", "summon",
				"kill", "tp", "teleport", "gamemode", "clear", "spawnpoint", "heal", "feed",
				"god", "fly", "vanish", "invsee", "enderchest", "hat", "nick")) {
			map.put(root, Capability.PLAYER);
		}
		// Disclosure
		for (String root : List.of("seed", "plugins", "pl", "version", "about", "whois", "seen",
				"lookup", "checkperm", "uuid", "ip", "alt", "alts", "balances", "balance", "baltop",
				"ignore", "back", "co", "coreprotect", "rollback")) {
			map.put(root, Capability.DISCLOSURE);
		}
		return Map.copyOf(map);
	}

	@Override public String id() { return "auxiliary/audit/admin_command_exposure"; }

	@Override public String name() { return "Administrative command exposure"; }

	@Override public String description() {
		return "Classifies the command tree the server offered this account and names which administrative "
			+ "capabilities were in it. It reads the tree already sent to the client and sends nothing.";
	}

	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("FOCUS", "all", false, false,
				"all, or one of: authority, code, moderation, world, player, disclosure",
				List.of("all", "authority", "code", "moderation", "world", "player", "disclosure")),
			new ModuleOption("MAX_FINDINGS", "12", false, false, "Maximum capability groups shown, 1-24")
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		if (!context.connected()) {
			return new ModuleResult("No connection: the command tree is only available from a live session",
				List.of(new Finding(Finding.Severity.INFO, "Not connected",
					"There is no server connection, so no command tree was captured.",
					"Connect to the authorized target first; nothing was sent.")));
		}

		Map<String, String> rootToNamespace = new LinkedHashMap<>();
		for (String advertised : context.advertisedCommands()) {
			String root = bare(advertised);
			if (root.isBlank()) continue;
			rootToNamespace.putIfAbsent(root, namespaceOf(advertised));
		}

		String focus = options.getOrDefault("FOCUS", "all").strip().toLowerCase(Locale.ROOT);
		int max = boundedInt(options.getOrDefault("MAX_FINDINGS", "12"), 12, 1, 24);

		Map<Capability, List<String>> grouped = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : rootToNamespace.entrySet()) {
			Capability capability = ROOTS.get(entry.getKey());
			if (capability == null) continue;
			if (!focus.equals("all") && !focus.equals(capability.label())) continue;
			grouped.computeIfAbsent(capability, key -> new ArrayList<>())
				.add(displayRoot(entry.getKey(), entry.getValue()));
		}

		// Subcommands say what the root actually reaches: a "plugin" root that
		// exposes "op" is not the same surface as one that exposes "list".
		Set<String> subcommandHits = new LinkedHashSet<>();
		for (CommandDescriptor descriptor : context.advertisedCommandDetails()) {
			for (String literal : descriptor.literalChildren()) {
				if (ROOTS.containsKey(literal) && !ROOTS.containsKey(bare(descriptor.root()))) {
					subcommandHits.add(descriptor.root() + " " + literal);
				}
			}
		}

		List<Finding> findings = new ArrayList<>();
		List<EvidenceNode> evidence = new ArrayList<>();
		List<Capability> order = List.of(Capability.AUTHORITY, Capability.CODE, Capability.MODERATION,
			Capability.WORLD, Capability.PLAYER, Capability.DISCLOSURE);

		int shown = 0;
		for (Capability capability : order) {
			List<String> roots = grouped.get(capability);
			if (roots == null || roots.isEmpty()) continue;
			if (shown >= max) {
				findings.add(new Finding(Finding.Severity.INFO, "Finding limit",
					"Further capability groups were omitted by MAX_FINDINGS=" + max + ".",
					"Raise MAX_FINDINGS to see the rest; the counts below are complete."));
				break;
			}
			shown++;
			findings.add(new Finding(capability.severity(),
				"Offered: " + capability.label() + " (" + roots.size() + ")",
				String.join(", ", roots) + ". " + capability.meaning(),
				"Confirm from the server console that this account holds these nodes on purpose. If it does, "
					+ "nothing here is wrong; if it does not, work out whether the tree was granted or the "
					+ "plugin never filtered it."));
		}

		if (!subcommandHits.isEmpty()) {
			findings.add(new Finding(Finding.Severity.INFO, "Administrative subcommands below ordinary roots",
				String.join(", ", subcommandHits),
				"A root whose own name looks harmless can still carry a privileged child. Review the plugin "
					+ "configuration that defines it."));
		}

		if (shown == 0) {
			findings.add(new Finding(Finding.Severity.PASS, "No classified administrative root",
				"None of the " + rootToNamespace.size() + " advertised roots matched the administrative table"
					+ (focus.equals("all") ? "." : " for FOCUS=" + focus + ".")
					+ " That is what a tightly scoped account looks like. It is not proof: a plugin that "
					+ "registers its commands late, or hides them by permission, sends nothing to classify.",
				"Confirm the installed inventory and the account's nodes from the server console."));
		}

		evidence.add(EvidenceNode.advertised("Advertised roots", Integer.toString(rootToNamespace.size()),
			"The tree is filtered by the server for this account, so presence is a stronger signal than absence."));
		evidence.add(EvidenceNode.observed("Capability groups matched", Integer.toString(grouped.size()),
			"Classification is by root name. A plugin may name a privileged command anything it likes."));
		evidence.add(EvidenceNode.observed("Commands sent", "none",
			"This module reads the captured tree; it never invokes a root it classifies."));

		return new ModuleResult("Administrative surface: " + countAll(grouped)
			+ " classified root(s) across " + grouped.size() + " capability group(s)", findings, evidence);
	}

	private static int countAll(Map<Capability, List<String>> grouped) {
		return grouped.values().stream().mapToInt(List::size).sum();
	}

	private static String displayRoot(String root, String namespace) {
		return namespace.isBlank() || namespace.equals("minecraft") ? root : namespace + ":" + root;
	}

	private static String namespaceOf(String value) {
		String root = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
		while (root.startsWith("/")) root = root.substring(1);
		int colon = root.indexOf(':');
		return colon < 0 ? "" : root.substring(0, colon);
	}

	private static String bare(String value) {
		String root = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
		while (root.startsWith("/")) root = root.substring(1);
		int colon = root.indexOf(':');
		if (colon >= 0 && colon + 1 < root.length()) root = root.substring(colon + 1);
		return root.matches("[a-z0-9_.-]{1,64}") ? root : "";
	}

	private static int boundedInt(String text, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(text.strip()))); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
