package dev.champions.core.mod;

import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the loaded mods are, and which of them change what an audit means.
 *
 * <p>Pure on purpose: it takes a list of installed mods and a set of channel
 * identifiers observed on the wire, and returns findings. The Fabric call that
 * produces the list lives in the client module, so this half can be tested against
 * fixed inputs without a game.</p>
 *
 * <p>The distinction the whole class is built around: this client's mod list says
 * nothing about the server's. A channel identifier on the wire does, weakly, because
 * a server only sends on a channel it has registered -- but only when the client is
 * able to decode it, which is exactly the case where the client has the mod too. Both
 * halves are reported separately and neither is allowed to stand in for the other.</p>
 */
public final class ModPosture {

	/** One mod as the loader reports it. */
	public record InstalledMod(String id, String name, String version, String source) {
		public InstalledMod {
			id = clean(id);
			name = clean(name).isEmpty() ? id : clean(name);
			version = clean(version).isEmpty() ? "unknown" : clean(version);
			source = clean(source).isEmpty() ? "unknown" : clean(source);
		}

		/**
		 * True when the version string does not look like a published release.
		 *
		 * <p>Build metadata after a {@code +} is not a sign of a local build: Fabric API
		 * releases look like {@code 0.161.0+26.3}, where the suffix names the game
		 * version. Only a {@code +} segment that says git, local, dirty or snapshot is
		 * treated as a working-tree build.</p>
		 */
		public boolean unpinned() {
			String value = version.toLowerCase(Locale.ROOT);
			if (value.equals("unknown") || value.equals("dev") || value.endsWith("-dirty")) return true;
			if (value.contains("snapshot") || value.contains("git.") || value.contains("local")) return true;
			int plus = value.indexOf('+');
			if (plus >= 0) {
				String metadata = value.substring(plus + 1);
				return metadata.contains("git") || metadata.contains("local")
					|| metadata.contains("dirty") || metadata.contains("snapshot");
			}
			return false;
		}
	}

	private static final int MAX_LISTED_MODS = 12;
	private static final int MAX_LISTED_CHANNELS = 16;

	private ModPosture() {}

	/**
	 * The findings for one inventory.
	 *
	 * @param mods     the mods this client loaded
	 * @param channels channel identifiers observed on the connection, namespace:id form
	 * @param brand    the brand string the server sent, or an empty string
	 */
	public static Result analyse(List<InstalledMod> mods, Set<String> channels, String brand) {
		List<Finding> findings = new ArrayList<>();
		List<EvidenceNode> evidence = new ArrayList<>();
		List<InstalledMod> inventory = mods == null ? List.of() : List.copyOf(mods);

		// 1. Duplicate ids: two copies of one mod is a broken install, not a finding
		//    about the server, but it makes every other count wrong.
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (InstalledMod mod : inventory) counts.merge(mod.id(), 1, Integer::sum);
		List<String> duplicates = counts.entrySet().stream()
			.filter(entry -> entry.getValue() > 1).map(Map.Entry::getKey).sorted().toList();
		if (!duplicates.isEmpty()) {
			findings.add(new Finding(Finding.Severity.MEDIUM, "Two copies of the same mod",
				String.join(", ", duplicates) + ". Every version-dependent result is unreliable until this is fixed.",
				"Remove one copy and restart. Two jars of one mod is the usual cause of a version check that "
					+ "disagrees with itself."));
		}

		// 2. Unpinned versions: a build from a working tree tells you nothing.
		List<String> unpinned = inventory.stream().filter(InstalledMod::unpinned)
			.map(mod -> mod.id() + "@" + mod.version).sorted().toList();
		if (!unpinned.isEmpty()) {
			findings.add(new Finding(Finding.Severity.LOW, "Mods built from a working tree",
				summarise(unpinned, MAX_LISTED_MODS),
				"A version string with git metadata, a snapshot marker or a local build is not something upstream "
					+ "tested. Compare against a release before reporting anything version-dependent."));
		}

		// 3. Known families, split by what they mean for an audit.
		Map<ModCatalog.Role, List<String>> byRole = new LinkedHashMap<>();
		List<String> unknown = new ArrayList<>();
		for (InstalledMod mod : inventory) {
			ModCatalog.Profile profile = ModCatalog.forMod(mod.id()).orElse(null);
			if (profile == null) { unknown.add(mod.id()); continue; }
			byRole.computeIfAbsent(profile.role(), key -> new ArrayList<>())
				.add(profile.name() + " " + mod.version() + " (" + mod.id() + ")");
		}

		for (ModCatalog.Role role : List.of(ModCatalog.Role.SERVER_SIDE, ModCatalog.Role.SERVICE)) {
			List<String> entries = byRole.get(role);
			if (entries == null || entries.isEmpty()) continue;
			List<String> ids = entries.stream().map(entry -> entry.replaceAll("\\s*\\(.*\\)$", "")).toList();
			List<String> reviews = inventory.stream()
				.map(mod -> ModCatalog.forMod(mod.id()).orElse(null))
				.filter(profile -> profile != null && profile.role() == role)
				.map(ModCatalog.Profile::review).distinct().toList();
			findings.add(new Finding(role == ModCatalog.Role.SERVICE ? Finding.Severity.MEDIUM : Finding.Severity.INFO,
				(role == ModCatalog.Role.SERVICE ? "Services reachable beyond the game port: "
					: "Changes what the server accepts: ") + ids.size(),
				summarise(entries, MAX_LISTED_MODS) + " " + (reviews.isEmpty() ? "" : String.join(" ", reviews)),
				"Review each one against how this server is configured, not against whether it is installed."));
		}

		List<String> clientOnly = byRole.get(ModCatalog.Role.CLIENT_ONLY);
		if (clientOnly != null && !clientOnly.isEmpty()) {
			findings.add(new Finding(Finding.Severity.INFO, "Client-side only",
				summarise(clientOnly, MAX_LISTED_MODS) + " These change what this client sees, not what the server "
					+ "accepts. A visual difference found with them installed is not a server finding.",
				"Reproduce with the client mods off before drawing a conclusion about a server."));
		}

		if (!unknown.isEmpty()) {
			findings.add(new Finding(Finding.Severity.INFO, "Not in this build's catalogue",
				summarise(unknown.stream().sorted().toList(), MAX_LISTED_MODS),
				"Unrecognised is not the same as unsafe. Record id and version from the inventory below and check "
					+ "the mod's own release notes; a module cannot describe a mod it does not know."));
		}

		// 4. Server-side inference from channel identifiers. This is the only part of
		//    the report that says anything at all about the other end.
		Set<String> observed = channels == null ? Set.of() : channels;
		List<String> inferred = new ArrayList<>();
		for (String channel : observed) {
			String namespace = namespaceOf(channel);
			if (namespace.isBlank() || namespace.equals("minecraft") || namespace.equals("fabric")
				|| namespace.equals("c")) continue;
			ModCatalog.forMod(namespace).ifPresent(profile -> inferred.add(profile.name() + " (" + channel + ")"));
		}
		if (!inferred.isEmpty()) {
			findings.add(new Finding(Finding.Severity.INFO, "The server is talking on these channels",
				summarise(inferred.stream().distinct().sorted().toList(), MAX_LISTED_CHANNELS),
				"A channel identifier in the namespace of a known mod means that mod is registered on the other "
					+ "end. It is the closest a client gets to a server mod list, and it only works for mods this "
					+ "client can also decode."));
		} else if (!observed.isEmpty()) {
			findings.add(new Finding(Finding.Severity.INFO, "No mod channels recognised",
				observed.size() + " custom channel(s) seen, none in a namespace this build knows.",
				"Either the server is lightly modded or it is sending on channels this client cannot name."));
		}

		// 5. The boundary, said once, in the report rather than a footnote.
		findings.add(new Finding(Finding.Severity.INFO, "What this inventory is",
			"These are the mods loaded in this client. The server's mod list is not visible to a client and this "
				+ "module does not claim to produce one.",
			"For a server-side inventory, run the same check from the server's own client, or read its mods "
				+ "folder from the console."));

		evidence.add(EvidenceNode.observed("Mods loaded", Integer.toString(inventory.size()),
			"Reported by the mod loader in this client."));
		evidence.add(EvidenceNode.observed("Recognised families", Integer.toString(byRole.values().stream()
			.mapToInt(List::size).sum()), "From the mod catalogue in this build."));
		evidence.add(EvidenceNode.advertised("Channels observed", Integer.toString(observed.size()),
			"Channel identifiers only; payload bodies are never retained."));
		evidence.add(EvidenceNode.advertised("Server brand", brand == null || brand.isBlank() ? "none" : brand,
			"A string the server chose to send."));
		evidence.add(EvidenceNode.observed("Commands sent", "none", "Nothing was sent to build this report."));

		String summary = inventory.size() + " mod(s) loaded, " + byRole.values().stream().mapToInt(List::size).sum()
			+ " recognised, " + (inferred.isEmpty() ? "no" : Integer.toString(inferred.size()))
			+ " server-side channel(s) identified";
		return new Result(findings, evidence, summary, List.copyOf(inventory), Set.copyOf(observed));
	}

	public record Result(List<Finding> findings, List<EvidenceNode> evidence, String summary,
			List<InstalledMod> inventory, Set<String> channels) {}

	private static String summarise(List<String> values, int max) {
		if (values.isEmpty()) return "";
		List<String> limited = values.size() <= max ? values : values.subList(0, max);
		String joined = String.join(", ", limited);
		return values.size() <= max ? joined + "." : joined + ", and " + (values.size() - max) + " more.";
	}

	private static String namespaceOf(String channel) {
		if (channel == null) return "";
		String value = channel.strip().toLowerCase(Locale.ROOT);
		int colon = value.indexOf(':');
		return colon < 0 ? "" : value.substring(0, colon).replaceAll("[^a-z0-9_.-]", "");
	}

	private static String clean(String value) {
		return value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
	}
}
