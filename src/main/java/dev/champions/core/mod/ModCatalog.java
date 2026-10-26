package dev.champions.core.mod;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What this build knows about the mods an audit is likely to meet.
 *
 * <p>Deliberately not the {@code ExploitCatalog}: that table is the port's record of
 * knowledge taken from the venom4plugin archive, and every entry in it has to trace
 * back to a source module (see {@code PortManifestTest}). This is a different kind of
 * table -- descriptions of well-known mods and what an operator should review about
 * each -- written for this project, with no upstream to account for.</p>
 *
 * <p>Nothing here asserts that a mod is vulnerable. A profile says what the mod does
 * that is worth auditing, and what a version string can and cannot tell you.</p>
 */
public final class ModCatalog {

	/** What the mod is, which decides how much of an audit it changes. */
	public enum Role {
		/** Runs on both sides and changes what the server accepts. */
		SERVER_SIDE,
		/** Presentation, rendering or performance on the client. */
		CLIENT_ONLY,
		/** A library other mods build on: a version conflict here breaks its dependants. */
		LIBRARY,
		/** World generation or data, usually shipped as a datapack. */
		WORLDGEN,
		/** Adds an outward-facing service (a port, a web panel, a voice chat). */
		SERVICE
	}

	/**
	 * One mod family.
	 *
	 * @param id      the Fabric mod id, or the prefix that identifies a family
	 * @param name    the name an operator would recognise
	 * @param role    what it is, from the audit's point of view
	 * @param review  what to look at if it is installed
	 * @param caveat  what the presence of the mod does <em>not</em> establish
	 */
	public record Profile(String id, String name, Role role, String review, String caveat) {

		public Profile {
			id = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
			name = name == null ? id : name;
			role = role == null ? Role.LIBRARY : role;
			review = review == null ? "" : review;
			caveat = caveat == null ? "" : caveat;
		}

		/** True when this profile covers the given installed mod id. */
		public boolean matches(String modId) {
			if (modId == null) return false;
			String candidate = modId.strip().toLowerCase(Locale.ROOT);
			return !id.isEmpty() && (candidate.equals(id) || candidate.startsWith(id + "-") || candidate.startsWith(id + "_"));
		}
	}

	private static final List<Profile> PROFILES = List.of(
		new Profile("cobblemon", "Cobblemon", Role.SERVER_SIDE,
			"Battles, spawns and storage are server-authoritative. Review the permission nodes behind its "
				+ "commands (/pokeheal, /pokespawn, /pokeedit, /pc, /trade), the spawn configuration, and anything "
				+ "the server exposes to a client that owns a Pokemon it did not catch.",
			"Installed proves nothing about configuration. An operator with tightened permissions and a stock "
				+ "config is a different target from one who left the defaults on."),
		new Profile("cobblemon-fabric", "Cobblemon (Fabric)", Role.SERVER_SIDE,
			"Same review as Cobblemon: command permissions, spawn configuration and trade or battle surfaces.",
			"Presence is not configuration."),
		new Profile("simple-voice-chat", "Simple Voice Chat", Role.SERVICE,
			"Opens a UDP port beside the game port. Confirm the port is reachable only where intended, that "
				+ "the server password (if any) is set, and that group or admin permissions cover who may start "
				+ "a group.",
			"An open voice port is a network question, not a Minecraft permission question."),
		new Profile("voicechat", "Simple Voice Chat", Role.SERVICE,
			"Opens a UDP port beside the game port. Review who may start groups and whether the port is "
				+ "firewalled from the internet.",
			"Reachability of the port is a host and firewall fact this module cannot see."),
		new Profile("luckperms", "LuckPerms", Role.SERVER_SIDE,
			"Whatever grants permissions is the root of every other finding. Review who may edit nodes, whether "
				+ "the web editor is exposed, and whether defaults grant anything privileged.",
			"A permission system that is installed and correctly configured is a control, not a finding."),
		new Profile("fabric-api", "Fabric API", Role.LIBRARY,
			"Every dependant shares its version. Keep it aligned with the mods that declare it, and prefer "
				+ "release builds.",
			"A mismatch surfaces as a crash or a missing registration, not as a security issue."),
		new Profile("create", "Create", Role.SERVER_SIDE,
			"Contraptions and schematics move blocks between inventories. Review schematic upload or download "
				+ "permissions, and who may place a contraption near protected regions.",
			"Automation is intentional; the finding is only ever about who can build it."),
		new Profile("lithium", "Lithium", Role.SERVER_SIDE,
			"Performance-changing mod: it alters vanilla tick behaviour. If a bug appears only on this server, "
				+ "check whether it reproduces without the mod before reporting it upstream.",
			"A behaviour difference is not a vulnerability."),
		new Profile("sodium", "Sodium", Role.CLIENT_ONLY,
			"Rendering only. It changes what this client draws, so a visual difference is not evidence about "
				+ "the server.",
			"Nothing on the server side changes."),
		new Profile("iris", "Iris", Role.CLIENT_ONLY,
			"Shader support on the client. No server surface.",
			"Nothing on the server side changes."),
		new Profile("distanthorizons", "Distant Horizons", Role.CLIENT_ONLY,
			"Renders terrain beyond the loaded area from data the server already sent; it does not grant sight "
				+ "the server withheld.",
			"Seeing further is a rendering claim, not an information leak by itself."),
		new Profile("terralith", "Terralith", Role.WORLDGEN,
			"Datapack world generation. Review seed handling and whether structure or loot data is public.",
			"World generation shapes the terrain; it does not change permissions."),
		new Profile("jei", "Just Enough Items", Role.CLIENT_ONLY,
			"Recipe viewing on the client.",
			"No server surface."),
		new Profile("rei", "Roughly Enough Items", Role.CLIENT_ONLY,
			"Recipe viewing on the client.",
			"No server surface."),
		new Profile("emi", "EMI", Role.CLIENT_ONLY,
			"Recipe viewing on the client.",
			"No server surface."),
		new Profile("journeymap", "JourneyMap", Role.CLIENT_ONLY,
			"Mapping on the client, with an optional web server. If the web map is enabled, treat it as a "
				+ "service and review who can reach it.",
			"A local map of what you have seen."),
		new Profile("xaeros-minimap", "Xaero's Minimap", Role.CLIENT_ONLY,
			"Mapping on the client.",
			"No server surface."),
		new Profile("modmenu", "Mod Menu", Role.CLIENT_ONLY,
			"Lists installed mods in the client. Useful for an inventory, irrelevant to a server audit.",
			"No server surface."),
		new Profile("cloth-config", "Cloth Config", Role.LIBRARY,
			"Configuration library for other mods. Version conflicts show up as missing screens.",
			"A library has no surface of its own."),
		new Profile("architectury", "Architectury API", Role.LIBRARY,
			"Cross-loader abstraction for other mods. Keep it aligned with its dependants.",
			"A library has no surface of its own."),
		new Profile("geyser", "Geyser", Role.SERVICE,
			"Bedrock bridge: it opens its own port and translates traffic into the Java protocol. Review the "
				+ "port, the authentication pass-through, and any resource-pack configuration.",
			"Bedrock players bypass nothing on their own; a misconfigured bridge is what does."),
		new Profile("viaversion", "ViaVersion", Role.SERVER_SIDE,
			"Protocol translation: the version a client reports may not be the version the server runs. Every "
				+ "version-dependent finding elsewhere in this workspace weakens when this is installed.",
			"The true server version has to come from the console."),
		new Profile("spark", "spark", Role.SERVER_SIDE,
			"Profiler with a web viewer. If the upload or viewer endpoint is enabled, review who can reach it.",
			"A profiler reports timings, not player data."),
		new Profile("luckperms-web", "LuckPerms web editor", Role.SERVICE,
			"The editor endpoint is an administrative surface. Confirm it is not exposed publicly and that "
				+ "session links are treated as credentials.",
			"Exposure is a hosting fact; this module cannot see it.")
	);

	private ModCatalog() {}

	public static List<Profile> profiles() {
		return PROFILES;
	}

	/** The profile for an installed mod id, if this build knows the family. */
	public static Optional<Profile> forMod(String modId) {
		return PROFILES.stream().filter(profile -> profile.matches(modId)).findFirst();
	}

	/** Every family this build can recognise, for the module's option list. */
	public static List<String> knownIds() {
		return PROFILES.stream().map(Profile::id).distinct().sorted().toList();
	}
}
