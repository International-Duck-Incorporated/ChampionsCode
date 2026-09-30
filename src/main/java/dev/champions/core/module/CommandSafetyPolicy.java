package dev.champions.core.module;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared deny-by-default filtering for command-root reconnaissance. */
public final class CommandSafetyPolicy {
	private static final Pattern SAFE_ROOT = Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_.-]+)?");
	private static final Set<String> DEFAULT_ROOTS = Set.of(
		"advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "chase", "clear", "clone",
		"compute", "damage", "data", "datapack", "debug", "debugconfig", "debugmobspawning", "debugpath",
		"defaultgamemode", "deop", "dialog", "difficulty", "effect", "enchant", "execute", "experience",
		"fetchprofile", "fill", "fillbiome", "forceload", "function", "gamemode", "gamerule", "give",
		"help", "item", "jfr", "kick", "kill", "list", "locate", "loot", "me", "msg", "op",
		"pardon", "pardon-ip", "particle", "perf", "place", "playsound", "posteffect", "publish", "raid",
		"random", "recipe", "reload", "return", "ride", "rotate", "save-all", "save-off", "save-on",
		"say", "schedule", "scoreboard", "seed", "serverpack", "setblock", "setidletimeout", "setspawn",
		"setworldspawn", "spawn_armor_trims", "spawnarmortrims", "spawnpoint", "spectate", "spreadplayers",
		"stop", "stopsound", "stopwatch", "summon", "swing", "tag", "team", "teammsg", "teleport",
		"tell", "tellraw", "test", "tick", "time", "title", "tm", "tp", "transfer", "trigger",
		"unpublish", "w", "warden_spawn_tracker", "wardenspawntracker", "waypoint", "weather", "whitelist",
		"worldborder", "xp",
		// Standard Bukkit/Paper implementation commands are not plugin candidates.
		"about", "bukkit", "icanhasbukkit", "paper", "pl", "plugins", "spigot", "ver", "version"
	);
	private static final Set<String> NEVER_PROBE_LOCAL_NAMES = Set.of(
		"ban", "ban-ip", "clear", "deop", "execute", "fill", "function", "give", "kick", "kill",
		"op", "reload", "restart", "save-all", "stop", "sudo", "summon", "whitelist"
	);

	private CommandSafetyPolicy() {}

	public enum Classification { STANDARD_NAMESPACE, STANDARD_ROOT, BLOCKED, THIRD_PARTY_CANDIDATE, INVALID }

	public static Classification classify(String root) {
		String normalized = normalize(root);
		if (!SAFE_ROOT.matcher(normalized).matches()) return Classification.INVALID;
		String namespace = namespace(normalized);
		String local = localName(normalized);
		if (NEVER_PROBE_LOCAL_NAMES.contains(local)) return Classification.BLOCKED;
		if ("minecraft".equals(namespace) || "bukkit".equals(namespace)
			|| "paper".equals(namespace) || "spigot".equals(namespace)) return Classification.STANDARD_NAMESPACE;
		if (DEFAULT_ROOTS.contains(local)) return Classification.STANDARD_ROOT;
		return Classification.THIRD_PARTY_CANDIDATE;
	}

	public static boolean isNonDefaultCandidate(String root) {
		String normalized = normalize(root);
		if (!SAFE_ROOT.matcher(normalized).matches()) return false;
		String namespace = namespace(normalized);
		String local = localName(normalized);
		if ("minecraft".equals(namespace) || "bukkit".equals(namespace)) return false;
		return !DEFAULT_ROOTS.contains(local) && !NEVER_PROBE_LOCAL_NAMES.contains(local);
	}

	public static boolean mayProbe(String root) {
		String normalized = normalize(root);
		return isNonDefaultCandidate(normalized) && !NEVER_PROBE_LOCAL_NAMES.contains(localName(normalized));
	}

	public static String normalize(String root) {
		return root == null ? "" : root.strip().toLowerCase(Locale.ROOT);
	}

	public static String localName(String root) {
		String normalized = normalize(root);
		int colon = normalized.indexOf(':');
		return colon < 0 ? normalized : normalized.substring(colon + 1);
	}

	private static String namespace(String root) {
		int colon = root.indexOf(':');
		return colon < 0 ? "" : root.substring(0, colon);
	}
}
