package dev.champions.core.module.builtin;

import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;
import dev.champions.core.module.InventorySnapshot;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.PacketEvent;
import dev.champions.core.module.PacketFlowAnalysis;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only container synchronization and item-component audit. */
public final class ContainerIntegrityModule implements ResearchModule {
	private PreviousSnapshot previous;

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

	@Override public synchronized ModuleResult run(ModuleContext context, Map<String, String> options) {
		InventorySnapshot inventory = context.inventory();
		if (!inventory.available()) {
			return new ModuleResult("Container audit unavailable", List.of(new Finding(Finding.Severity.INFO,
				"Container context", "No local player/menu is available.", "Join the authorized server and open the relevant container before running this module.")));
		}
		int seconds = boundedInt(options.get("WINDOW_SECONDS"), 10, 1, 30);
		List<PacketEvent> relatedEvents = context.packetFlow().recentEvents().stream()
			.filter(event -> event.ageMillis() <= seconds * 1_000L)
			.filter(event -> PacketFlowAnalysis.family(event.packetType()).equals("Inventory/menu"))
			.toList();
		long relatedPackets = relatedEvents.size();
		long outboundActions = relatedEvents.stream().filter(event -> event.direction() == PacketEvent.Direction.OUTBOUND).count();
		long inboundUpdates = relatedEvents.stream().filter(event -> event.direction() == PacketEvent.Direction.INBOUND).count();
		String snapshotKey = context.serverAddress() + "\u0000" + inventory.menuClass() + "\u0000" + inventory.containerId();
		InventorySnapshot before = previous != null && previous.key().equals(snapshotKey) ? previous.snapshot() : null;
		previous = new PreviousSnapshot(snapshotKey, inventory);

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
		List<EvidenceNode> current = List.of(
			EvidenceNode.observed("Menu type", inventory.menuClass(), "The Java menu class is local implementation metadata."),
			EvidenceNode.observed("Container ID", Integer.toString(inventory.containerId()), "Useful for local correlation only; packet fields are not retained."),
			EvidenceNode.observed("State revision", Integer.toString(inventory.stateId()), "Revision semantics are controlled by the current menu implementation."),
			EvidenceNode.observed("Slot occupancy", inventory.nonEmptySlots() + "/" + inventory.slotCount(), "Only aggregate occupancy is retained; item-level slot contents are not."),
			EvidenceNode.observed("Component density", "maximum=" + inventory.maximumComponentCount(), "Components and NBT values are intentionally not retained."),
			EvidenceNode.observed("Carried stack", inventory.carriedItem() + " x" + inventory.carriedCount(), "Display name and count only; no item data is exported."));
		List<EvidenceNode> changes = before == null ? List.of(new EvidenceNode("Previous snapshot", "unavailable",
			EvidenceNode.Source.DERIVED, EvidenceNode.Confidence.UNKNOWN,
			"Run the module again while the same menu remains open to compare bounded aggregate state.", List.of())) : compare(before, inventory);
		List<EvidenceNode> flow = List.of(
			EvidenceNode.observed("Outbound inventory events", Long.toString(outboundActions), "Classified from packet class names; no container identifiers are available."),
			EvidenceNode.observed("Inbound inventory events", Long.toString(inboundUpdates), "May include updates unrelated to a specific local action."),
			EvidenceNode.observed("Classes", PacketFlowAnalysis.formatCounts(PacketFlowAnalysis.counts(relatedEvents, event -> true), 12),
				"Counts describe recent traffic shape and do not prove synchronization correctness."));
		return new ModuleResult("Container Integrity audit completed", findings, List.of(
			EvidenceNode.group("Current container snapshot", current),
			EvidenceNode.group("Change since previous run", changes),
			EvidenceNode.group("Correlated packet-flow window", flow)));
	}

	private static List<EvidenceNode> compare(InventorySnapshot before, InventorySnapshot after) {
		int stateDelta = after.stateId() - before.stateId();
		int occupancyDelta = after.nonEmptySlots() - before.nonEmptySlots();
		boolean carriedChanged = !before.carriedItem().equals(after.carriedItem()) || before.carriedCount() != after.carriedCount();
		return List.of(
			new EvidenceNode("State revision", before.stateId() + " → " + after.stateId() + " (delta " + stateDelta + ")",
				EvidenceNode.Source.DERIVED, stateDelta >= 0 ? EvidenceNode.Confidence.MEDIUM : EvidenceNode.Confidence.LOW,
				"A revision decrease may also mean the menu was recreated with a reused container ID.", List.of()),
			new EvidenceNode("Occupied slots", before.nonEmptySlots() + " → " + after.nonEmptySlots() + " (delta " + occupancyDelta + ")",
				EvidenceNode.Source.DERIVED, EvidenceNode.Confidence.HIGH,
				"Equal occupancy does not mean individual slots or items remained unchanged.", List.of()),
			new EvidenceNode("Carried stack", carriedChanged
				? before.carriedItem() + " x" + before.carriedCount() + " → " + after.carriedItem() + " x" + after.carriedCount()
				: "unchanged", EvidenceNode.Source.DERIVED, EvidenceNode.Confidence.HIGH,
				"Only sanitized display name and count are compared.", List.of()),
			new EvidenceNode("Maximum component count", before.maximumComponentCount() + " → " + after.maximumComponentCount(),
				EvidenceNode.Source.DERIVED, EvidenceNode.Confidence.MEDIUM,
				"This is an aggregate maximum and does not identify which stack changed.", List.of()));
	}

	private record PreviousSnapshot(String key, InventorySnapshot snapshot) {}

	private static int boundedInt(String value, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
