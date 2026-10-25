package dev.champions.core.module.builtin;

import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.PacketEvent;
import dev.champions.core.module.PacketFlowAnalysis;
import dev.champions.core.mc.ClientCapabilities;
import dev.champions.core.mc.VersionGated;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Metadata-only packet-flow observer. */
public final class WireLensModule implements ResearchModule, VersionGated {
	@Override public java.util.Set<ClientCapabilities.Feature> requires() { return java.util.Set.of(ClientCapabilities.Feature.PACKET_TELEMETRY); }

	@Override public String id() { return "auxiliary/observe/wire_lens"; }
	@Override public String name() { return "Wire Lens packet-flow observer"; }
	@Override public String description() {
		return "Summarizes packet class names, directions, rates, and bursts from a bounded local timeline. It never records packet payloads.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("WINDOW_SECONDS", "10", false, false, "Recent metadata window, 1-30 seconds"),
			new ModuleOption("TOP_TYPES", "8", false, false, "Top packet classes per direction, 1-20")
		);
	}

	@Override public ModuleResult run(ModuleContext context, Map<String, String> options) {
		int seconds = boundedInt(options.get("WINDOW_SECONDS"), 10, 1, 30);
		int top = boundedInt(options.get("TOP_TYPES"), 8, 1, 20);
		List<PacketEvent> events = context.packetFlow().recentEvents().stream()
			.filter(event -> event.ageMillis() <= seconds * 1_000L).toList();
		Map<String, Long> inbound = counts(events, event -> event.direction() == PacketEvent.Direction.INBOUND);
		Map<String, Long> outbound = counts(events, event -> event.direction() == PacketEvent.Direction.OUTBOUND);
		long customPayloads = events.stream().filter(event -> event.packetType().toLowerCase(Locale.ROOT).contains("custompayload")).count();
		long peakSecond = events.stream().collect(Collectors.groupingBy(event -> event.ageMillis() / 1_000L, Collectors.counting()))
			.values().stream().mapToLong(Long::longValue).max().orElse(0L);

		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO, "Window totals",
			inbound.values().stream().mapToLong(Long::longValue).sum() + " inbound; "
				+ outbound.values().stream().mapToLong(Long::longValue).sum() + " outbound in " + seconds + "s; peak " + peakSecond + "/s",
			"Use aggregate flow first; inspect payloads only in isolated test fixtures with explicit data-handling rules."));
		findings.add(new Finding(Finding.Severity.INFO, "Top inbound classes", formatTop(inbound, top),
			"Packet class counts indicate flow, not validity or vulnerability."));
		findings.add(new Finding(Finding.Severity.INFO, "Top outbound classes", formatTop(outbound, top),
			"No packets are delayed, changed, duplicated, or suppressed by Wire Lens."));
		findings.add(new Finding(Finding.Severity.INFO, "Custom payload volume", Long.toString(customPayloads),
			"Review channel-specific behavior server-side; this module intentionally stores no payload bytes."));
		findings.add(new Finding(Finding.Severity.INFO, "Lifetime metadata",
			context.packetFlow().totalInbound() + " inbound; " + context.packetFlow().totalOutbound() + " outbound; "
				+ context.packetFlow().timelineEvictions() + " old timeline event(s) evicted",
			"Timeline eviction is normal after the fixed 512-event privacy/memory bound."));
		List<EvidenceNode> evidence = List.of(EvidenceNode.group("Packet flow", List.of(
			flowDirection("Server → Client", inbound, PacketFlowAnalysis.families(events, PacketEvent.Direction.INBOUND), top),
			flowDirection("Client → Server", outbound, PacketFlowAnalysis.families(events, PacketEvent.Direction.OUTBOUND), top),
			EvidenceNode.group("Capture bounds", List.of(
				EvidenceNode.observed("Window", seconds + " seconds", "Only events still present in the bounded timeline are analyzed."),
				EvidenceNode.observed("Peak observed second", peakSecond + " events", "Age-bucket grouping is approximate and is not server tick timing."),
				EvidenceNode.observed("Timeline evictions", Long.toString(context.packetFlow().timelineEvictions()), "The local timeline intentionally retains at most 512 metadata events."),
				EvidenceNode.observed("Payload retention", "none", "Class names, direction, and timing metadata only.")))
		)));
		return new ModuleResult("Wire Lens flow summary completed", findings, evidence);
	}

	private static EvidenceNode flowDirection(String label, Map<String, Long> classes, Map<String, Long> families, int top) {
		List<EvidenceNode> familyNodes = families.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
			.map(entry -> EvidenceNode.observed(entry.getKey(), entry.getValue() + " event(s)", "Family is inferred from the packet class name."))
			.toList();
		return new EvidenceNode(label, classes.values().stream().mapToLong(Long::longValue).sum() + " event(s)",
			EvidenceNode.Source.OBSERVED, EvidenceNode.Confidence.HIGH, "Packet bodies are intentionally unavailable.", List.of(
				EvidenceNode.group("Families", familyNodes),
				EvidenceNode.observed("Top classes", formatTop(classes, top), "Counts indicate traffic shape, not validity or vulnerability.")));
	}

	private static Map<String, Long> counts(List<PacketEvent> events, Predicate<PacketEvent> filter) {
		return events.stream().filter(filter).collect(Collectors.groupingBy(PacketEvent::packetType, LinkedHashMap::new, Collectors.counting()));
	}

	private static String formatTop(Map<String, Long> counts, int top) {
		String value = counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
			.limit(top).map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(", "));
		return value.isEmpty() ? "<none observed>" : value;
	}

	private static int boundedInt(String value, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
