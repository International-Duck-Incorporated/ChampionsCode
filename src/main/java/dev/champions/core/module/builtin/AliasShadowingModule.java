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
 * Which plugin owns a command name, and where two plugins claim the same one.
 *
 * <p>Command names are a shared namespace. When two plugins register the same
 * literal, the one that registered first wins the bare name, and the other is
 * reachable only as {@code plugin:name}. Players and operators type the bare
 * name, so the plugin that answers it is not always the one they meant -- a
 * well-known trick is to register {@code msg} or {@code help} and answer it
 * yourself.</p>
 *
 * <p>This module reports collisions and shadowing from the tree the server sent
 * to this account. It cannot see registration order, so it does not claim to
 * know which plugin wins; it reports that the choice exists.</p>
 */
public final class AliasShadowingModule implements ResearchModule {

	/** Vanilla roots worth naming when a plugin claims them. */
	private static final Set<String> SENSITIVE_VANILLA = Set.of(
		"op", "deop", "gamemode", "give", "ban", "pardon", "kick", "whitelist", "stop",
		"reload", "save", "save-all", "tp", "teleport", "effect", "enchant", "setblock", "fill");

	private static final Set<String> ORDINARY_VANILLA = Set.of(
		"say", "me", "msg", "tell", "w", "list", "help", "seed", "time", "version", "plugins");

	private record Claim(String bare, Set<String> namespaces) {}

	@Override public String id() { return "auxiliary/audit/alias_shadowing"; }

	@Override public String name() { return "Command alias collisions"; }

	@Override public String description() {
		return "Reports bare command names that more than one provider claims, and vanilla names now answered "
			+ "by a plugin. It reads the advertised tree and sends nothing.";
	}

	@Override
	public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("MAX_FINDINGS", "10", false, false, "Maximum collisions shown, 1-24"),
			new ModuleOption("INCLUDE_ORDINARY", "true", false, false,
				"true: also list ordinary vanilla names a plugin answers", List.of("true", "false"))
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		if (!context.connected()) {
			return new ModuleResult("No connection: collisions are read from a live command tree",
				List.of(new Finding(Finding.Severity.INFO, "Not connected",
					"There is no server connection, so no command tree was captured.",
					"Connect to the authorized target first; nothing was sent.")));
		}

		int max = boundedInt(options.getOrDefault("MAX_FINDINGS", "10"), 10, 1, 24);
		boolean includeOrdinary = !"false".equalsIgnoreCase(options.getOrDefault("INCLUDE_ORDINARY", "true").strip());

		// A bare root carries no provider: the server offered the name, and who
		// answers it is a registration-order fact the client cannot see. Only the
		// namespaced form names a provider, so the two are collected separately.
		Set<String> bareNames = new LinkedHashSet<>();
		Map<String, Set<String>> claims = new LinkedHashMap<>();
		for (String advertised : context.advertisedCommands()) {
			String working = advertised == null ? "" : advertised.strip().toLowerCase(Locale.ROOT);
			while (working.startsWith("/")) working = working.substring(1);
			if (working.isEmpty()) continue;
			int colon = working.indexOf(':');
			if (colon < 0) {
				if (working.matches("[a-z0-9_.-]{1,64}")) bareNames.add(working);
				continue;
			}
			String namespace = working.substring(0, colon);
			String bare = working.substring(colon + 1);
			if (!bare.matches("[a-z0-9_.-]{1,64}")) continue;
			claims.computeIfAbsent(bare, key -> new LinkedHashSet<>()).add(namespace);
		}
		for (String bareName : bareNames) claims.computeIfAbsent(bareName, key -> new LinkedHashSet<>());

		List<Claim> collisions = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : claims.entrySet()) {
			Set<String> providers = new LinkedHashSet<>(entry.getValue());
			// Vanilla claims its own names; a vanilla provider only counts as a
			// contest when a plugin also took the same name.
			providers.remove("minecraft"); // vanilla alone is not a contest
			boolean pluginContest = providers.size() > 1;
			if (pluginContest) collisions.add(new Claim(entry.getKey(), providers));
		}

		List<String> shadowedVanilla = new ArrayList<>();
		List<String> shadowedSensitive = new ArrayList<>();
		for (String vanillaName : new LinkedHashSet<>(concat(SENSITIVE_VANILLA, includeOrdinary ? ORDINARY_VANILLA : Set.of()))) {
			Set<String> providers = claims.get(vanillaName);
			if (providers == null || providers.isEmpty()) continue;
			if (providers.contains("minecraft")) continue; // the real vanilla command is still there
			// A plugin answers a name vanilla also defines, and vanilla does not.
			(SENSITIVE_VANILLA.contains(vanillaName) ? shadowedSensitive : shadowedVanilla).add(vanillaName);
		}

		List<String> subcommandCollisions = new ArrayList<>();
		for (CommandDescriptor descriptor : context.advertisedCommandDetails()) {
			Set<String> duplicated = new LinkedHashSet<>();
			Set<String> seen = new LinkedHashSet<>();
			for (String literal : descriptor.literalChildren()) {
				if (!seen.add(literal)) duplicated.add(literal);
			}
			if (!duplicated.isEmpty()) subcommandCollisions.add(descriptor.root() + " " + String.join(",", duplicated));
		}

		List<Finding> findings = new ArrayList<>();
		int shown = 0;
		for (Claim collision : collisions) {
			if (shown >= max) {
				findings.add(new Finding(Finding.Severity.INFO, "Finding limit",
					(collisions.size() - max) + " further collision(s) omitted by MAX_FINDINGS=" + max + ".",
					"Raise MAX_FINDINGS for the complete list."));
				break;
			}
			shown++;
			findings.add(new Finding(Finding.Severity.LOW, "Contested name: " + collision.bare(),
				"Claimed by " + String.join(", ", collision.namespaces()) + ".",
				"Work out which provider answers the bare name on this server and whether that is the one staff "
					+ "expect. Players typing the short form reach the winner."));
		}

		if (!shadowedSensitive.isEmpty()) {
			findings.add(new Finding(Finding.Severity.MEDIUM, "Sensitive vanilla name answered by a plugin",
				String.join(", ", shadowedSensitive),
				"A plugin answers a name vanilla reserves for administrative use. Confirm it implements the same "
					+ "permission checks; a replacement that checks a different node can be weaker than the original."));
		}
		if (!shadowedVanilla.isEmpty()) {
			findings.add(new Finding(Finding.Severity.INFO, "Ordinary vanilla name answered by a plugin",
				String.join(", ", shadowedVanilla),
				"Usually intentional (chat formatting, custom help). Worth knowing when a player reports that the "
					+ "command behaves unlike vanilla."));
		}
		if (!subcommandCollisions.isEmpty()) {
			findings.add(new Finding(Finding.Severity.INFO, "Duplicate subcommand literals",
				String.join("; ", subcommandCollisions),
				"Two children share a name below one root. The client shows both; which one runs depends on the "
					+ "server's registration order."));
		}

		if (findings.isEmpty()) {
			findings.add(new Finding(Finding.Severity.PASS, "No contested command names",
				"Every advertised bare name has exactly one provider, and no vanilla name is answered by a plugin.",
				"Collisions can still exist for names this account was not offered."));
		}

		List<EvidenceNode> evidence = List.of(
			EvidenceNode.advertised("Bare names seen", Integer.toString(claims.size()),
				"Counted from the tree this account was offered."),
			EvidenceNode.observed("Collisions", Integer.toString(collisions.size()),
				"Registration order is a server-side fact; the client cannot see which plugin won."),
			EvidenceNode.observed("Commands sent", "none", "Nothing was invoked to test a claim."));

		return new ModuleResult("Alias review: " + collisions.size() + " contested name(s), "
			+ shadowedSensitive.size() + " sensitive vanilla name(s) answered by a plugin", findings, evidence);
	}

	private static List<String> concat(Set<String> first, Set<String> second) {
		List<String> all = new ArrayList<>(first);
		all.addAll(second);
		return all;
	}

	private static int boundedInt(String text, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(text.strip()))); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
