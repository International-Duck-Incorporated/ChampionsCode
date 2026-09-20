package dev.champions.core.module.builtin;

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

/** Reviews a bounded, administrator-supplied component list without file or network access. */
public final class DependencyPostureModule implements ResearchModule {
	static final int MAX_COMPONENTS = 32;
	static final int MAX_INPUT_LENGTH = 2_048;

	@Override public String id() { return "auxiliary/audit/dependency_posture"; }
	@Override public String name() { return "Declared dependency posture"; }
	@Override public String description() {
		return "Reviews an administrator-supplied name@version inventory for missing pins, conflicting versions, and pre-release builds. It does not read server files or claim live CVE coverage.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("COMPONENTS", "", false, false, "Comma-separated name@version entries, at most 32")
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		String raw = options.getOrDefault("COMPONENTS", "");
		List<Finding> findings = new ArrayList<>();
		if (raw.isBlank()) {
			findings.add(new Finding(Finding.Severity.INFO, "No declared inventory",
				"COMPONENTS is empty. Example: Geyser@2.9.3, ViaVersion@5.4.0, ExamplePlugin@unknown",
				"Export the installed inventory from the authorized server console and enter only component names and versions, not secrets or configuration."));
			return new ModuleResult("Dependency posture review needs a declared inventory", findings);
		}

		boolean truncatedInput = raw.length() > MAX_INPUT_LENGTH;
		String bounded = raw.substring(0, Math.min(raw.length(), MAX_INPUT_LENGTH));
		ParsedInventory inventory = parse(bounded);
		if (truncatedInput || inventory.omitted() > 0) {
			findings.add(new Finding(Finding.Severity.INFO, "Input bound applied",
				(truncatedInput ? "Input exceeded " + MAX_INPUT_LENGTH + " characters. " : "") +
				(inventory.omitted() > 0 ? inventory.omitted() + " entries exceeded the " + MAX_COMPONENTS + " component limit." : ""),
				"Split large inventories into separate bounded reviews."));
		}
		if (inventory.invalidEntries() > 0) {
			findings.add(new Finding(Finding.Severity.LOW, "Unparseable inventory entries",
				inventory.invalidEntries() + " entries were rejected without echoing their contents.",
				"Use name@version or group:artifact@version. Do not include credentials, URLs with tokens, or configuration contents."));
		}

		List<String> unpinned = new ArrayList<>();
		List<String> prerelease = new ArrayList<>();
		List<String> conflicts = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : inventory.versionsByName().entrySet()) {
			Set<String> versions = entry.getValue();
			if (versions.stream().anyMatch(DependencyPostureModule::isUnpinned)) unpinned.add(entry.getKey());
			if (versions.stream().anyMatch(DependencyPostureModule::isPrerelease)) {
				prerelease.add(entry.getKey() + "=" + String.join("/", versions));
			}
			Set<String> concrete = new LinkedHashSet<>();
			for (String version : versions) if (!isUnpinned(version)) concrete.add(version.toLowerCase(Locale.ROOT));
			if (concrete.size() > 1) conflicts.add(entry.getKey() + "=" + String.join("/", concrete));
		}

		findings.add(new Finding(Finding.Severity.INFO, "Declared inventory",
			inventory.versionsByName().size() + " unique components and " + inventory.acceptedEntries() + " accepted entries were reviewed locally.",
			"Reconcile this declaration with the server's actual plugin and library inventory."));
		if (!conflicts.isEmpty()) {
			findings.add(new Finding(Finding.Severity.MEDIUM, "Conflicting declared versions",
				boundedJoin(conflicts),
				"Remove unintended duplicates and verify classpath resolution before assessing advisories."));
		}
		if (!prerelease.isEmpty()) {
			findings.add(new Finding(Finding.Severity.LOW, "Pre-release or moving builds",
				boundedJoin(prerelease),
				"Prefer a pinned stable release unless a documented testing need requires the pre-release build."));
		}
		if (!unpinned.isEmpty()) {
			findings.add(new Finding(Finding.Severity.LOW, "Missing version pins",
				boundedJoin(unpinned),
				"Record exact versions and trusted origins so upstream security advisories can be evaluated accurately."));
		}
		if (conflicts.isEmpty() && prerelease.isEmpty() && unpinned.isEmpty() && inventory.invalidEntries() == 0) {
			findings.add(new Finding(Finding.Severity.PASS, "Basic inventory hygiene",
				"Every accepted component had one concrete version and no obvious snapshot or pre-release marker.",
				"This is not a vulnerability verdict. Compare exact versions and hashes with official upstream advisories and release signatures."));
		}
		findings.add(new Finding(Finding.Severity.INFO, "Coverage boundary",
			"No files, server directories, JAR contents, remote registries, or live CVE feeds were accessed.",
			"Use administrator-supplied SBOM or plugin metadata and official advisories for a complete assessment."));
		return new ModuleResult("Declared dependency posture reviewed", findings);
	}

	static ParsedInventory parse(String raw) {
		Map<String, Set<String>> versions = new LinkedHashMap<>();
		String[] tokens = raw.split("[,;\\n\\r]+");
		int accepted = 0;
		int omitted = 0;
		int invalid = 0;
		for (String token : tokens) {
			String entry = safeToken(token);
			if (entry.isBlank()) continue;
			if (accepted >= MAX_COMPONENTS) { omitted++; continue; }
			int separator = entry.lastIndexOf('@');
			if (separator < 1) separator = entry.lastIndexOf('=');
			String name = separator < 1 ? entry : entry.substring(0, separator).strip();
			String version = separator < 1 ? "unknown" : entry.substring(separator + 1).strip();
			if (!name.matches("[A-Za-z0-9_.:/-]{1,80}") || version.length() > 48 || version.matches(".*\\s+.*")) {
				invalid++;
				continue;
			}
			if (version.isBlank()) version = "unknown";
			accepted++;
			versions.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> new LinkedHashSet<>()).add(version);
		}
		return new ParsedInventory(versions, invalid, accepted, omitted);
	}

	private static boolean isUnpinned(String version) {
		return version.isBlank() || version.equalsIgnoreCase("unknown") || version.equals("*") ||
			version.equalsIgnoreCase("latest") || version.equalsIgnoreCase("current");
	}

	private static boolean isPrerelease(String version) {
		String normalized = version.toLowerCase(Locale.ROOT);
		return normalized.contains("snapshot") || normalized.contains("nightly") || normalized.contains("dev") ||
			normalized.matches(".*(?:^|[._-])(alpha|beta|rc|pre)(?:[._-]?\\d*)?.*");
	}

	private static String safeToken(String token) {
		String cleaned = token == null ? "" : token.replaceAll("\\p{Cntrl}", " ").strip();
		return cleaned.length() <= 96 ? cleaned : cleaned.substring(0, 96);
	}

	private static String boundedJoin(List<String> values) {
		return String.join(", ", values.stream().limit(12).toList()) + (values.size() > 12 ? ", ..." : "");
	}

	record ParsedInventory(Map<String, Set<String>> versionsByName, int invalidEntries, int acceptedEntries, int omitted) {}
}
