package com.internationalduck.championscode.module.payload;

/*
 * ==========================================================================
 * EXAMPLE FILE — how to use the payload folder
 * ==========================================================================
 * This package is a plug-and-play home for payload modules. A payload is the
 * smallest observable action that proves an issue is real: it is declared in
 * advance, bounded by PayloadSafety, and reverted where it created something.
 *
 * To add your own module:
 *
 *  1. Copy this file inside .../module/payload/ and rename the class.
 *  2. Keep the plug-and-play rules from docs/exploit-and-payload-files.md:
 *     one self-contained class per file, one payload family per file, a
 *     public no-argument constructor, deterministic bounds for counts and
 *     sizes.
 *  3. Implement PayloadModule — not just ResearchModule. That is what puts the
 *     module in the payload tier: effect() is read and checked by
 *     PayloadSafety before anything runs, the banner names the declared
 *     effect, and revertPlan() is shown and audited. If the module writes a
 *     file, also implement ArtifactFolderAware; the console hands over the one
 *     folder it may write into, at registration, whoever registered it.
 *  4. Register it from the client entrypoint (see ChampionsClient):
 *
 *         console.registerModule(new ExamplePayloadModule());
 *
 *     A module compiled from a dropped-in source file is registered the same
 *     way and gets the same treatment: the payload rules are enforced by the
 *     interface, not by the class being built in.
 *
 * What you get for free once registered:
 *   - `use auxiliary/payloads/example_plan`, `info`, `show options`,
 *     `set`/`unset`, and `run` in the F8 terminal.
 *   - The declared-effect check, the exact-host authorization allowlist, the
 *     one-run CONFIRM phrase and the legal-risk acknowledgement that every
 *     payload carries.
 *   - The append-only JSONL audit log, including the effect and revert plan.
 *
 * This example writes one small text file into the audit folder and nothing
 * else. It sends nothing to the server, so performsActiveAction() is false and
 * the console says so before the run.
 * ==========================================================================
 */

import dev.champions.core.catalog.ArtifactFolderAware;
import dev.champions.core.catalog.AuditFolder;
import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.module.payload.PayloadEffect;
import dev.champions.core.module.payload.PayloadModule;
import dev.champions.core.module.payload.PayloadSafety;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Example payload-folder module: builds a bounded set of payload variants in
 * memory and writes the plan to one artifact in the audit folder.
 *
 * <p>It is the shape a payload module should have — declared effect, bounded
 * work, a revert plan, and no delivery — rather than a demonstration of any
 * particular exploit.</p>
 */
public final class ExamplePayloadModule implements PayloadModule, ArtifactFolderAware {
	/** One bare name: the audit folder refuses anything else. */
	private static final String ARTIFACT = "example_plan.txt";

	private AuditFolder folder = AuditFolder.unavailable();

	@Override public String id() { return "auxiliary/payloads/example_plan"; }

	@Override public String name() { return "Example payload module (plan artifact)"; }

	@Override public String description() {
		return "Builds a bounded set of payload variants in memory and writes the plan to "
			+ ARTIFACT + " in the audit folder. It sends nothing to the server.";
	}

	@Override public void useAuditFolder(AuditFolder folder) {
		this.folder = folder == null ? AuditFolder.unavailable() : folder;
	}

	@Override
	public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current explicitly authorized connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase: I_HAVE_AUTHORIZATION"),
			new ModuleOption("CHANNEL_NAMESPACE", "champions", true, false, "Payload channel namespace"),
			new ModuleOption("CHANNEL_PATH", "example", true, false, "Payload channel path"),
			new ModuleOption("MAX_BYTES", "256", true, false, "Upper bound per payload variant (1-32767)")
		);
	}

	/**
	 * Declared before the authorization gate and again in the warning the user
	 * acknowledges, so the thing that runs is the thing that was described.
	 */
	@Override public PayloadEffect effect() {
		return PayloadEffect.auditArtifact(ARTIFACT);
	}

	@Override public String revertPlan() {
		return "delete " + ARTIFACT + " from the audit folder (" + folder.describe() + ")";
	}

	/** An artifact never leaves the client, so no command is sent. */
	@Override public boolean performsActiveAction(Map<String, String> options) {
		return false;
	}

	@Override
	public CompletionStage<ModuleResult> runActive(ModuleContext context,
			Map<String, String> options, ActiveModuleRuntime runtime) {
		int maxBytes = parseBounded(options.get("MAX_BYTES"), 256, 1, 32_767);
		String channel = bounded(options.getOrDefault("CHANNEL_NAMESPACE", "champions"), 32)
			+ ":" + bounded(options.getOrDefault("CHANNEL_PATH", "example"), 32);
		Random deterministic = new Random(0xC0FFEE); // fixed seed keeps fuzz bytes reproducible

		List<String> variants = new ArrayList<>();
		variants.add(variant("empty", 0, channel));
		variants.add(variant("max-size", maxBytes, channel));
		variants.add(variant("malformed-json", "{ \"test\": \"value\", ".getBytes(StandardCharsets.UTF_8).length, channel));
		variants.add(variant("fuzz", Math.min(256, maxBytes), channel));

		StringBuilder body = new StringBuilder();
		body.append("# Example payload plan\n")
			.append("# Written to the audit folder by a payload-tier module; never sent anywhere.\n")
			.append("# Channel: ").append(channel).append('\n')
			.append("# Ceiling: ").append(PayloadSafety.maximumArtifactBytes()).append(" bytes\n\n");
		for (String line : variants) body.append(line).append('\n');

		AuditFolder.Written written = folder.write(ARTIFACT, body.toString());
		if (!written.ok()) {
			return CompletableFuture.completedFuture(new ModuleResult(
				"The plan could not be written: " + written.message(),
				List.of(new Finding(Finding.Severity.INFO, "Nothing written",
					written.message(),
					"An artifact-only module needs an audit folder; the console supplies it at registration.")),
				List.of(EvidenceNode.observed("Audit folder", folder.describe(), "No artifact was written."))));
		}

		List<Finding> findings = new ArrayList<>();
		for (String line : variants) {
			findings.add(new Finding(Finding.Severity.INFO, "Variant " + line.split(":")[0],
				line, "Review size and encoding before any real dispatch."));
		}
		findings.add(new Finding(Finding.Severity.PASS, "Capability boundary",
			"Built " + variants.size() + " payload variants in memory and wrote the plan to " + ARTIFACT + ".",
			"This module sends nothing. Delivery would have to go through the gated active path with its own "
				+ "authorization, limits and audit line."));

		return CompletableFuture.completedFuture(new ModuleResult(
			"Planned " + variants.size() + " payload variants for " + channel + " and wrote " + ARTIFACT,
			findings,
			List.of(EvidenceNode.observed("Artifact", written.path() == null ? ARTIFACT : written.path().toString(),
				"Inside the audit folder; the revert plan deletes it."))));
	}

	private static String variant(String label, int bytes, String channel) {
		return label + ": " + bytes + " byte(s) for channel " + channel;
	}

	private static String bounded(String value, int max) {
		String clean = value == null ? "<unset>" : value.replaceAll("\\p{Cntrl}", " ").strip();
		if (clean.isBlank()) clean = "<unset>";
		return clean.length() <= max ? clean : clean.substring(0, max) + "...";
	}

	private static int parseBounded(String raw, int fallback, int min, int max) {
		try {
			return Math.clamp(Integer.parseInt(raw == null ? "" : raw.strip()), min, max);
		} catch (NumberFormatException invalid) {
			return fallback;
		}
	}
}
