package dev.champions.core.module.builtin;

import dev.champions.core.catalog.ExploitCatalog;
import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What is installed here, named from the command surface, with a stable
 * fingerprint of that surface.
 *
 * <p>This is the discovery step the rest of the workspace sits on: plugin
 * families come from the review profiles and the exploit catalogue, and every one
 * of them is matched against the command roots the server advertised to this
 * account. The fingerprint is a hash of the sorted roots, so two scans of the
 * same server compare equal and a changed surface does not -- which turns the
 * command tree into something you can take before and after a plugin update and
 * diff.</p>
 *
 * <p>A family that is absent was not proven absent: the tree is filtered by
 * permission for the account that asked for it.</p>
 */
public final class PluginFingerprintModule implements ResearchModule {

	@Override public String id() { return "auxiliary/scanner/plugin_fingerprint"; }

	@Override public String name() { return "Plugin fingerprint"; }

	@Override public String description() {
		return "Names the plugin families the advertised command surface points at, records which of them the "
			+ "exploit catalogue has entries for, and hashes the surface so two scans can be compared.";
	}

	@Override
	public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("MAX_FAMILIES", "16", false, false, "Maximum families listed, 1-32"),
			new ModuleOption("SHOW_ROOTS", "true", false, false,
				"true: list the roots each family was matched from", List.of("true", "false"))
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		Set<String> roots = new LinkedHashSet<>();
		for (String advertised : context.advertisedCommands()) {
			String bare = bare(advertised);
			if (!bare.isBlank()) roots.add(bare);
		}
		if (!context.connected() || roots.isEmpty()) {
			return new ModuleResult("No command surface to fingerprint",
				List.of(new Finding(Finding.Severity.INFO, "Nothing advertised",
					"Either there is no connection or the server advertised no commands to this account.",
					"Connect to the authorized target first; nothing was sent.")),
				List.of(EvidenceNode.observed("Roots", "0", "No surface was captured.")));
		}

		int maxFamilies = boundedInt(options.getOrDefault("MAX_FAMILIES", "16"), 16, 1, 32);
		boolean showRoots = !"false".equalsIgnoreCase(options.getOrDefault("SHOW_ROOTS", "true").strip());

		// family -> matched roots, from the review profiles.
		Map<String, Set<String>> families = new TreeMap<>();
		for (PluginRiskReviewModule.Profile profile : PluginRiskReviewModule.profiles()) {
			List<String> matched = roots.stream().filter(profile.roots()::contains).toList();
			if (!matched.isEmpty()) families.computeIfAbsent(profile.name(), key -> new LinkedHashSet<>()).addAll(matched);
		}

		// family -> catalogue entries, from the exploit catalogue.
		Map<String, List<String>> catalogued = new LinkedHashMap<>();
		Set<String> entryIds = new LinkedHashSet<>();
		for (ExploitCatalog.Entry entry : ExploitCatalog.match(context.advertisedCommands())) {
			catalogued.computeIfAbsent(entry.plugin(), key -> new ArrayList<>()).add(entry.id());
			entryIds.add(entry.id());
		}

		List<Finding> findings = new ArrayList<>();
		int shown = 0;
		for (Map.Entry<String, Set<String>> family : families.entrySet()) {
			if (shown >= maxFamilies) {
				findings.add(new Finding(Finding.Severity.INFO, "Family limit",
					(families.size() - maxFamilies) + " further families omitted by MAX_FAMILIES=" + maxFamilies + ".",
					"Raise MAX_FAMILIES for the complete list."));
				break;
			}
			shown++;
			findings.add(new Finding(Finding.Severity.INFO, "Installed (by surface): " + family.getKey(),
				(showRoots ? "Roots: " + String.join(", ", family.getValue()) + ". " : "")
					+ "Evidence: the server offered these command roots to this account.",
				"Confirm the build and version from the server console; a root proves a command, not a version."));
		}

		for (Map.Entry<String, List<String>> entry : catalogued.entrySet()) {
			findings.add(new Finding(Finding.Severity.INFO, "Catalogued plugins detected: " + entry.getKey(),
				"Entries: " + String.join(", ", entry.getValue()) + ".",
				"Run 'use auxiliary/audit/plugin_exploit_surface' with this surface to read what each entry says "
					+ "about the bug class, and reproduce nothing until you are authorized to."));
		}

		if (families.isEmpty() && catalogued.isEmpty()) {
			findings.add(new Finding(Finding.Severity.PASS, "Nothing recognised",
				"None of the " + roots.size() + " advertised roots matched a known plugin family."
					+ " A vanilla or lightly-plugin'd server looks like this, and so does a heavily filtered "
					+ "tree, so this is not a statement that nothing is installed.",
				"Confirm the installed inventory from the server console before concluding anything."));
		}

		String fingerprint = fingerprint(roots);
		List<EvidenceNode> evidence = List.of(
			EvidenceNode.observed("Fingerprint", fingerprint,
				"SHA-256 over the sorted advertised roots, first 16 hex characters. Equal values mean an "
					+ "identical surface; a different value means something was added, removed or renamed."),
			EvidenceNode.advertised("Roots counted", Integer.toString(roots.size()),
				"Only roots this account was offered; permission filtering hides the rest."),
			EvidenceNode.observed("Families matched", Integer.toString(families.size()),
				"From the review profiles in this build."),
			EvidenceNode.observed("Catalogue entries matched", Integer.toString(entryIds.size()),
				"From the ported exploit catalogue in this build."),
			EvidenceNode.observed("Commands sent", "none", "Nothing was invoked to build this inventory."));

		return new ModuleResult("Fingerprint " + fingerprint + ": " + families.size() + " family(ies), "
			+ entryIds.size() + " catalogued entr(y/ies) over " + roots.size() + " root(s)", findings, evidence);
	}

	/** Stable across runs because it sorts first and strips namespaces. */
	static String fingerprint(Set<String> roots) {
		List<String> sorted = roots.stream().sorted().toList();
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String root : sorted) digest.update((root + "\n").getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
		} catch (Exception unavailable) {
			return "unavailable";
		}
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
