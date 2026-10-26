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
 *
 * <p>Every id in this table was read out of the published jar's {@code fabric.mod.json}
 * on 2026-10-26, not guessed from the project name, because the loader reports
 * the id and a wrong one matches nothing. That check found three families the old
 * table could never have matched: REI reports itself as {@code roughlyenoughitems},
 * Xaero's Minimap as {@code xaerominimap}, and Simple Voice Chat as
 * {@code voicechat}.</p>
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
	 * The question an audit actually asks about a mod.
	 *
	 * <p>{@link Role} says where the mod runs; this says what installing it changes
	 * about the meaning of every other finding. Two server-side mods are not
	 * interchangeable: one is a performance patch and the other decides identity.</p>
	 */
	public enum Focus {
		AUTH("Who does the server think you are, and what proves it?"),
		PROTOCOL("Which version is each side actually speaking?"),
		SERVICE("Which port is open beside the game, and to whom?"),
		CODE_EXECUTION("Who can make this machine run code, and whose code is it?"),
		CLIENT_INSPECTION("What does the other end learn about this client?"),
		PERMISSIONS("What does a default player get, and who can change that?"),
		LOGGING("What is recorded, and who can read it?"),
		TELEMETRY("What does this client send to somebody else?"),
		DATA("Which data leaves the place it was collected?"),
		NONE("Nothing here changes what an audit means.");

		private final String question;

		Focus(String question) { this.question = question; }

		public String question() { return question; }
	}

	/**
	 * One mod family.
	 *
	 * @param id      the Fabric mod id as the jar reports it, or an alias that also matches
	 * @param name    the name an operator would recognise
	 * @param role    what it is, from the audit's point of view
	 * @param focus   the question an audit asks about it
	 * @param review  what to look at if it is installed
	 * @param caveat  what the presence of the mod does <em>not</em> establish
	 * @param aliasOf the canonical family this entry only exists to match, or empty
	 */
	public record Profile(String id, String name, Role role, Focus focus, String review,
			String caveat, String aliasOf) {

		public Profile {
			id = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
			name = name == null ? id : name;
			role = role == null ? Role.LIBRARY : role;
			focus = focus == null ? Focus.NONE : focus;
			review = review == null ? "" : review;
			caveat = caveat == null ? "" : caveat;
			aliasOf = aliasOf == null ? "" : aliasOf.strip().toLowerCase(Locale.ROOT);
		}

		/** True when this entry exists only to match another family's older name. */
		public boolean isAlias() { return !aliasOf.isEmpty(); }

		/** True when this profile covers the given installed mod id. */
		public boolean matches(String modId) {
			if (modId == null) return false;
			String candidate = modId.strip().toLowerCase(Locale.ROOT);
			return !id.isEmpty() && (candidate.equals(id) || candidate.startsWith(id + "-")
				|| candidate.startsWith(id + "_"));
		}
	}

	private static final List<Profile> PROFILES = List.of(
		new Profile("cobblemon", "Cobblemon", Role.SERVER_SIDE, Focus.PERMISSIONS,
"Battles, spawns and storage are server-authoritative. Review the permission nodes behind its "
			+ "commands (/pokeheal, /pokespawn, /pokeedit, /pc, /trade), the spawn configuration, and "
			+ "anything the server exposes to a client that owns a Pokemon it did not catch.",
"Installed proves nothing about configuration. An operator with tightened permissions and a "
			+ "stock config is a different target from one who left the defaults on.",
""),
		new Profile("cobblemon-fabric", "Cobblemon (Fabric)", Role.SERVER_SIDE, Focus.PERMISSIONS,
"Same review as Cobblemon: command permissions, spawn configuration and trade or battle "
			+ "surfaces.",
"Presence is not configuration.",
"cobblemon"),
		new Profile("voicechat", "Simple Voice Chat", Role.SERVICE, Focus.SERVICE,
"Opens a UDP port beside the game port. Confirm the port is reachable only where intended, "
			+ "that the server password (if any) is set, and that group or admin permissions cover who may "
			+ "start a group. The Fabric mod id is voicechat; the project is called Simple Voice Chat.",
"An open voice port is a network question, not a Minecraft permission question.",
""),
		new Profile("simple-voice-chat", "Simple Voice Chat (project name)", Role.SERVICE, Focus.SERVICE,
"Alias for the family above: the loader reports this mod as voicechat, not as its project "
			+ "name.",
"A profile under the project name only matches a jar that used it as its id.",
"voicechat"),
		new Profile("luckperms", "LuckPerms", Role.SERVER_SIDE, Focus.PERMISSIONS,
"Whatever grants permissions is the root of every other finding. Review who may edit nodes, "
			+ "whether the web editor is exposed, and whether defaults grant anything privileged.",
"A permission system that is installed and correctly configured is a control, not a finding.",
""),
		new Profile("luckperms-web", "LuckPerms web editor", Role.SERVICE, Focus.SERVICE,
"The editor endpoint is an administrative surface, shipped as part of LuckPerms rather than "
			+ "as a separate mod. Confirm it is not exposed publicly and that session links are treated as "
			+ "credentials.",
"Exposure is a hosting fact; this module cannot see it.",
"luckperms"),
		new Profile("fabric-api", "Fabric API", Role.LIBRARY, Focus.NONE,
"Every dependant shares its version. Keep it aligned with the mods that declare it, and "
			+ "prefer release builds.",
"A mismatch surfaces as a crash or a missing registration, not as a security issue.",
""),
		new Profile("create", "Create", Role.SERVER_SIDE, Focus.PERMISSIONS,
"Contraptions and schematics move blocks between inventories. Review schematic upload or "
			+ "download permissions, and who may place a contraption near protected regions.",
"Automation is intentional; the finding is only ever about who can build it.",
""),
		new Profile("lithium", "Lithium", Role.SERVER_SIDE, Focus.NONE,
"Performance-changing mod: it alters vanilla tick behaviour. If a bug appears only on this "
			+ "server, check whether it reproduces without the mod before reporting it upstream.",
"A behaviour difference is not a vulnerability.",
""),
		new Profile("sodium", "Sodium", Role.CLIENT_ONLY, Focus.NONE,
"Rendering only. It changes what this client draws, so a visual difference is not evidence "
			+ "about the server.",
"Nothing on the server side changes.",
""),
		new Profile("iris", "Iris", Role.CLIENT_ONLY, Focus.NONE,
"Shader support on the client. No server surface.",
"Nothing on the server side changes.",
""),
		new Profile("distanthorizons", "Distant Horizons", Role.CLIENT_ONLY, Focus.NONE,
"Renders terrain beyond the loaded area from data the server already sent; it does not grant "
			+ "sight the server withheld.",
"Seeing further is a rendering claim, not an information leak by itself.",
""),
		new Profile("terralith", "Terralith", Role.WORLDGEN, Focus.NONE,
"Datapack world generation. Review seed handling and whether structure or loot data is "
			+ "public.",
"World generation shapes the terrain; it does not change permissions.",
""),
		new Profile("jei", "Just Enough Items", Role.CLIENT_ONLY, Focus.NONE,
"Recipe viewing on the client.",
"No server surface.",
""),
		new Profile("roughlyenoughitems", "Roughly Enough Items", Role.CLIENT_ONLY, Focus.NONE,
"Recipe viewing on the client. The loader id is roughlyenoughitems; rei was the wrong guess "
			+ "and never matched anything.",
"No server surface.",
""),
		new Profile("rei", "Roughly Enough Items (short name)", Role.CLIENT_ONLY, Focus.NONE,
"Alias kept for packs that ship a jar using the short name. The published mod reports "
			+ "roughlyenoughitems.",
"No server surface.",
"roughlyenoughitems"),
		new Profile("emi", "EMI", Role.CLIENT_ONLY, Focus.NONE,
"Recipe viewing on the client.",
"No server surface.",
""),
		new Profile("journeymap", "JourneyMap", Role.CLIENT_ONLY, Focus.DATA,
"Mapping on the client, with an optional web server. If the web map is enabled, treat it as a "
			+ "service and review who can reach it.",
"A local map of what you have seen.",
""),
		new Profile("xaerominimap", "Xaero's Minimap", Role.CLIENT_ONLY, Focus.NONE,
"Mapping on the client. The loader id is xaerominimap, not the project name.",
"No server surface.",
""),
		new Profile("xaeros-minimap", "Xaero's Minimap (project name)", Role.CLIENT_ONLY, Focus.NONE,
"Alias for the family above: the published jar reports xaerominimap.",
"No server surface.",
"xaerominimap"),
		new Profile("modmenu", "Mod Menu", Role.CLIENT_ONLY, Focus.NONE,
"Lists installed mods in the client. Useful for an inventory, irrelevant to a server audit.",
"No server surface.",
""),
		new Profile("cloth-config", "Cloth Config", Role.LIBRARY, Focus.NONE,
"Configuration library for other mods. Version conflicts show up as missing screens.",
"A library has no surface of its own.",
""),
		new Profile("architectury", "Architectury API", Role.LIBRARY, Focus.NONE,
"Cross-loader abstraction for other mods. Keep it aligned with its dependants.",
"A library has no surface of its own.",
""),
		new Profile("geyser", "Geyser", Role.SERVICE, Focus.SERVICE,
"Bedrock bridge: it opens its own port and translates traffic into the Java protocol. Review "
			+ "the port, the authentication pass-through, and any resource-pack configuration.",
"Bedrock players bypass nothing on their own; a misconfigured bridge is what does.",
""),
		new Profile("viaversion", "ViaVersion", Role.SERVER_SIDE, Focus.PROTOCOL,
"Protocol translation: the version a client reports may not be the version the server runs. "
			+ "Every version-dependent finding elsewhere in this workspace weakens when this is installed.",
"The true server version has to come from the console.",
""),
		new Profile("spark", "spark", Role.SERVER_SIDE, Focus.SERVICE,
"Profiler with a web viewer. If the upload or viewer endpoint is enabled, review who can "
			+ "reach it.",
"A profiler reports timings, not player data.",
""),
		new Profile("floodgate", "Floodgate", Role.SERVER_SIDE, Focus.AUTH,
"Lets Bedrock players in without a Java account: it trusts the Bedrock identity Geyser "
			+ "forwards. Review whether that mapping is one-to-one, whether a Java account can hold the "
			+ "same name, and what the first Bedrock join is granted. It pairs with Geyser and is useless "
			+ "without it.",
"The mod is how Bedrock players get in; the finding is only ever about which identity may be "
			+ "claimed.",
""),
		new Profile("fabricproxy-lite", "FabricProxy-Lite", Role.SERVER_SIDE, Focus.AUTH,
"Accepts forwarded player data from Velocity instead of authenticating it itself. If the "
			+ "forwarding secret is missing, or the game port is directly reachable, a client can present "
			+ "any name and UUID it likes. Confirm the port is bound to loopback or firewalled to the "
			+ "proxy.",
"A client cannot see the bind address or the secret; this needs the server's own netstat.",
""),
		new Profile("easyauth", "EasyAuth", Role.SERVER_SIDE, Focus.AUTH,
"Server-side password login for cracked or mixed servers. Review how credentials are stored, "
			+ "whether sessions are remembered by address, what a failed attempt costs, and whether an "
			+ "unauthenticated player can run anything except the login command.",
"Password auth replaces Mojang auth for everyone it covers, including the operator.",
""),
		new Profile("viabackwards", "ViaBackwards", Role.SERVER_SIDE, Focus.PROTOCOL,
"Translates the newer protocol down to older clients. A finding that depends on the client "
			+ "version may have been produced by the translator rather than by the server; pin what each "
			+ "side actually speaks.",
"The mod exists to hide a version gap, which is exactly what a version-based finding trips "
			+ "over.",
""),
		new Profile("viafabricplus", "ViaFabricPlus", Role.CLIENT_ONLY, Focus.PROTOCOL,
"Client-side protocol broadening: this client can join servers of many versions and reports a "
			+ "version the server may not expect. Useful for reproducing old behaviour; it also means a "
			+ "version string in a log may be this mod's doing.",
"It changes what this client sends, not what the server accepts.",
""),
		new Profile("bluemap", "BlueMap", Role.SERVICE, Focus.SERVICE,
"Starts a web server (8100 by default) that renders the world, and with live updating it "
			+ "shows where players are. Review whether the map is public, whether it is behind auth, and "
			+ "whether it reveals what the server otherwise hides.",
"The map is as sensitive as the world it renders; a public map is an intended feature on many "
			+ "servers.",
""),
		new Profile("squaremap", "squaremap", Role.SERVICE, Focus.SERVICE,
"A web map service, lighter than a 3D renderer and with the same exposure question: a port, a "
			+ "world, and usually live player positions.",
"Popular on public servers; exposure is a configuration choice.",
""),
		new Profile("journeymap_webmap", "JourneyMap Web Map", Role.SERVICE, Focus.SERVICE,
"Serves this client's map on a local port. It is a client-side service, so what can leak is "
			+ "what the client has already seen, not the server's world.",
"Bound to localhost by default in most installs; verify before assuming.",
""),
		new Profile("e4mc", "e4mc", Role.SERVICE, Focus.SERVICE,
"Opens a single-player world to the internet through a relay and prints a public address. "
			+ "Anyone holding the address can join. Check what that world has enabled -- cheats, operator "
			+ "status, command blocks -- before sharing the link.",
"The relay is the point of the mod; the finding is what the shared world allows.",
""),
		new Profile("plasmovoice", "Plasmo Voice", Role.SERVICE, Focus.SERVICE,
"UDP voice service beside the game port, with its own permissions and optional addons. Check "
			+ "the port, whether it is firewalled, and whether speaking requires a permission.",
"A voice port is a network surface, not a Minecraft permission surface.",
""),
		new Profile("carpet", "Carpet", Role.SERVER_SIDE, Focus.CODE_EXECUTION,
"Scarpet runs scripts inside the server process, and /script can load and run them. Anyone "
			+ "with that permission has code execution on the host. Review who holds carpet and script "
			+ "permissions, and whether any script is loaded from a path another party can write.",
"Carpet is a research tool by design; the finding is who can reach its script loader.",
""),
		new Profile("computercraft", "CC: Tweaked", Role.SERVER_SIDE, Focus.CODE_EXECUTION,
"Computers and turtles have an HTTP API that reaches the network from the server, and "
			+ "peripherals read inventories. Review whether HTTP is enabled, what the allowlist says, and "
			+ "which players may place a computer near a protected block.",
"A disabled HTTP API is the usual hardening; a client cannot see whether it is off.",
""),
		new Profile("minescript", "Minescript", Role.CLIENT_ONLY, Focus.CODE_EXECUTION,
"Runs Python inside this client against the client's own API. Anything a player can do by "
			+ "hand a script can do faster, and a script can be written by someone else.",
"It automates this client only; it does not grant anything the server did not already allow.",
""),
		new Profile("trustedclients", "Trusted Clients", Role.SERVER_SIDE, Focus.CLIENT_INSPECTION,
"The server inspects the client's mod list and reacts to it. That is a privacy boundary as "
			+ "much as a control: what is collected, where it is stored, and what a mismatch does to the "
			+ "player.",
"Client scanning is a policy this server chose; the client cannot see its results.",
""),
		new Profile("openpartiesandclaims", "Open Parties and Claims", Role.SERVER_SIDE, Focus.PERMISSIONS,
"Claims are permission boundaries implemented as a mod. Review the default claim size, "
			+ "whether explosions and pistons respect claims, and whether a party leader can hand out "
			+ "access to land they do not own.",
"A claim is only as good as the config that enables it.",
""),
		new Profile("fabric-permissions-api-v0", "Fabric Permissions API", Role.LIBRARY, Focus.PERMISSIONS,
"Every permission check in a Fabric mod goes through whichever provider is installed. With no "
			+ "provider, checks may quietly return the default. Confirm what this server answers for an "
			+ "ordinary player.",
"The library is the question, not the answer: the provider decides.",
""),
		new Profile("worldedit", "WorldEdit", Role.SERVER_SIDE, Focus.PERMISSIONS,
"Region operations and schematics, gated by permission nodes. It is also the plugin whose "
			+ "schematic-name handling was confined in 7.4.4, so read the version before treating that as a "
			+ "live issue.",
"WorldEdit is an administrative tool; the finding is who may run it and what the build "
			+ "actually is.",
""),
		new Profile("ledger", "Ledger", Role.SERVER_SIDE, Focus.LOGGING,
"Writes block and container changes to a database. It is evidence, and it is also a second "
			+ "copy of everything: review who may query it and where the database file or server lives.",
"An audit log nobody can read is not evidence; one everyone can read is a disclosure.",
""),
		new Profile("fastback", "FastBack", Role.SERVER_SIDE, Focus.LOGGING,
"Backups stored as a git repository. Review where the repository lives, whether it is pushed "
			+ "to a remote, and whether it contains data the server would not otherwise hand out.",
"A backup is a copy of the world, including anything the world holds.",
""),
		new Profile("nochatreports", "No Chat Reports", Role.CLIENT_ONLY, Focus.TELEMETRY,
"Stops this client signing chat, so messages cannot be reported to Mojang as verifiably the "
			+ "sender's. It changes what can be proven afterwards; it does not hide the message from the "
			+ "server.",
"The server still receives and logs the message; only the signature is missing.",
""),
		new Profile("essential-container", "Essential", Role.CLIENT_ONLY, Focus.TELEMETRY,
"A client platform with accounts, cosmetics, world hosting and telemetry behind one mod. "
			+ "Review what it sends, which remote features are on, and whether its hosting is in use -- it "
			+ "is the largest outbound surface most clients have.",
"Everything it offers is a service someone else operates.",
""),
		new Profile("customskinloader-bootstrap", "CustomSkinLoader", Role.CLIENT_ONLY, Focus.DATA,
"Fetches skins from third-party APIs instead of Mojang. That request leaves the client for a "
			+ "host of someone else's choosing, and the image it returns is rendered.",
"The skin server sees this client's address and requested player names.",
""),
		new Profile("no-telemetry", "No Telemetry", Role.CLIENT_ONLY, Focus.TELEMETRY,
"Disables the client's own telemetry. Its presence says this client is quieter than stock, "
			+ "not that anything on the server changed.",
"A privacy mod on one client says nothing about the other nine.",
""),
		new Profile("chunky", "Chunky", Role.SERVER_SIDE, Focus.NONE,
"Pre-generates chunks. Long-running and heavy: a server running it is generating terrain, not "
			+ "under load from players.",
"Heavy CPU is the expected cost of pre-generation.",
"")
	);

	private ModCatalog() {}

	public static List<Profile> profiles() {
		return PROFILES;
	}

	/** The profile for an installed mod id, if this build knows the family. */
	public static Optional<Profile> forMod(String modId) {
		return PROFILES.stream().filter(profile -> profile.matches(modId))
			.max(java.util.Comparator.comparingInt(profile -> profile.id().length()));
	}

	/** Every family this build can recognise, for the module's option list. */
	public static List<String> knownIds() {
		return PROFILES.stream().map(Profile::id).distinct().sorted().toList();
	}

	/** The families that describe a real mod, without the alias entries. */
	public static List<Profile> families() {
		return PROFILES.stream().filter(profile -> !profile.isAlias()).toList();
	}

	/** The profile a family or alias id resolves to, following one alias hop. */
	public static Optional<Profile> canonical(String modId) {
		return forMod(modId).map(profile -> profile.isAlias()
			? forMod(profile.aliasOf()).orElse(profile) : profile);
	}
}
