package com.internationalduck.championscode.module.payload;

/*
 * ==========================================================================
 * EXAMPLE FILE — how to use the payload folder
 * ==========================================================================
 * This package is a plug-and-play home for payload modules: classes that
 * build, inspect, or dispatch bounded test payloads over the connected
 * client session. To add your own module:
 *
 *  1. Copy this file inside .../module/payload/ and rename the class.
 *  2. Keep the plug-and-play rules from docs/exploit-and-payload-files.md:
 *     one self-contained class per file, one payload family per file, a
 *     public no-argument constructor, and deterministic bounds for counts
 *     and sizes.
 *  3. Implement ResearchModule (like this file) so the module can surface
 *     in the F8 console, the sorted ClickGUI, and the category GUI.
 *  4. Register it from the client entrypoint right after the console is
 *     built (see ChampionsClient.onInitializeClient):
 *
 *         console.registerModule(new ExamplePayloadModule());
 *
 * What you get for free once registered:
 *   - `use auxiliary/payloads/example_plan`, `info`, `show options`,
 *     `set`/`unset`, and `run` in the F8 terminal.
 *   - The exact-host authorization allowlist and the one-run CONFIRM
 *     phrase (required because requiresAuthorization() is true).
 *   - The append-only JSONL audit log.
 *
 * This example is a dry run: it builds the payload variants in memory and
 * reports their sizes and the dispatch plan, but never sends anything.
 * Registered modules must stay passive inside run(); any future dispatch must
 * use the gated active-module path with explicit authorization, strict size
 * and rate limits, and audit logging.
 * ==========================================================================
 */

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Example payload-folder module: a dry-run planner that builds a bounded set
 * of payload variants for a chosen channel and reports what would be sent.
 */
public final class ExamplePayloadModule implements ResearchModule {

	@Override public String id() { return "auxiliary/payloads/example_plan"; }

	@Override public String name() { return "Example payload module (dry-run planner)"; }

	@Override public String description() {
		return "Builds a bounded set of payload variants in memory and reports their sizes and dispatch plan. "
			+ "It never dispatches: real delivery goes through the gated active path or the adaptation point "
			+ "documented in this module.";
	}

	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current explicitly authorized connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("CHANNEL_NAMESPACE", "champions", true, false, "Payload channel namespace"),
			new ModuleOption("CHANNEL_PATH", "example", true, false, "Payload channel path"),
			new ModuleOption("MAX_BYTES", "256", true, false, "Upper bound per payload variant (1-32767)")
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		int maxBytes = parseBounded(options.get("MAX_BYTES"), 256, 1, 32_767);
		String channel = bounded(options.getOrDefault("CHANNEL_NAMESPACE", "champions"), 32)
			+ ":" + bounded(options.getOrDefault("CHANNEL_PATH", "example"), 32);
		Random deterministic = new Random(0xC0FFEE); // fixed seed keeps fuzz bytes reproducible

		List<Finding> findings = new ArrayList<>();
		findings.add(variant("empty", new byte[0], channel));
		findings.add(variant("max-size", randomBytes(deterministic, maxBytes), channel));
		findings.add(variant("malformed-json",
			"{ \"test\": \"value\", ".getBytes(StandardCharsets.UTF_8), channel));
		findings.add(variant("fuzz", randomBytes(deterministic, Math.min(256, maxBytes)), channel));
		findings.add(new Finding(Finding.Severity.PASS, "Capability boundary",
			"Built " + findings.size() + " payload variants in memory and dispatched nothing.",
			"Wire dispatch through ClientPlayNetworking.send(CustomPacketPayload) after the gates, "
				+ "only after adding explicit authorization, limits, and audit logging."));

		return new ModuleResult("Planned payload variants for channel " + channel
			+ " without dispatching", findings);
	}

	private static Finding variant(String label, byte[] data, String channel) {
		return new Finding(Finding.Severity.INFO, "Variant " + label,
			data.length + " byte(s) for channel " + channel,
			"Review size and encoding before any real dispatch.");
	}

	private static byte[] randomBytes(Random random, int count) {
		byte[] data = new byte[count];
		random.nextBytes(data);
		return data;
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
