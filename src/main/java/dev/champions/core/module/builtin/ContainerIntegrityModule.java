package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.InventorySnapshot;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only container synchronization and item-component audit. */
public final class ContainerIntegrityModule implements ResearchModule {
	@Override public String id() { return "auxiliary/audit/container_integrity"; }
	@Override public String name() { return "Container Integrity audit"; }
	@Override public String description() {
		return "Reviews the local menu ID/state, slot population, carried stack, component density, and recent inventory packet churn without changing inventory state.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("WINDOW_SECONDS", "10", false, false, "Inventory packet metadata window, 1-30 seconds")
		);
	}

	@Override public ModuleResult run(ModuleContext context, Map<String, String> options) {
		InventorySnapshot inventory = context.inventory();
		if (!inventory.available()) {
			return new ModuleResult("Container audit unavailable", List.of(new Finding(Finding.Severity.INFO,
				"Container context", "No local player/menu is available.", "Join the authorized server and open the relevant container before running this module.")));
		}
		int seconds = boundedInt(options.get("WINDOW_SECONDS"), 10, 1, 30);
		long relatedPackets = context.packetFlow().recentEvents().stream()
			.filter(event -> event.ageMillis() <= seconds * 1_000L)
			.filter(event -> {
				String type = event.packetType().toLowerCase(Locale.ROOT);
				return type.contains("container") || type.contains("slot") || type.contains("inventory") || type.contains("merchant") || type.contains("screen");
			}).count();

		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO, "Menu synchronization",
			inventory.menuClass() + " id=" + inventory.containerId() + " state=" + inventory.stateId() + " slots=" + inventory.slotCount(),
			"Compare IDs and state revisions with server logs when diagnosing rejected or duplicated interactions."));
		findings.add(new Finding(Finding.Severity.INFO, "Local slot summary",
			inventory.nonEmptySlots() + " non-empty; max component count=" + inventory.maximumComponentCount(),
			"High component counts are context only, not proof of malicious NBT or a vulnerable decoder."));
		findings.add(new Finding(inventory.nonStandardStackCounts() > 0 ? Finding.Severity.LOW : Finding.Severity.PASS,
			"Non-standard stack counts", Integer.toString(inventory.nonStandardStackCounts()),
			"Some legitimate custom servers use unusual counts; verify server-side before treating this as a finding."));
		findings.add(new Finding(Finding.Severity.INFO, "Carried stack",
			inventory.carriedItem() + " x" + inventory.carriedCount(), "The item is observed locally and is never modified by this module."));
		findings.add(new Finding(Finding.Severity.INFO, "Inventory-related flow",
			relatedPackets + " related packet class event(s) in " + seconds + "s", "Correlate bursts with one intentional UI action at a time in a test environment."));
		return new ModuleResult("Container Integrity audit completed", findings);
	}

	private static int boundedInt(String value, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
