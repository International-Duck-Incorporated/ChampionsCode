package dev.champions.core.module.builtin;

import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.PacketEvent;
import dev.champions.core.module.PacketFlowAnalysis;
import dev.champions.core.module.ResearchModule;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Bounded metadata timeline suitable for reproducing event ordering without payloads. */
public final class SessionTraceModule implements ResearchModule {
	@Override public String id() { return "auxiliary/replay/session_trace"; }
	@Override public String name() { return "Session Trace metadata replay"; }
	@Override public String description() {
		return "Renders a recent direction/type timeline for ordering analysis. This is metadata replay, not packet reinjection.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("WINDOW_SECONDS", "5", false, false, "Recent window, 1-30 seconds"),
			new ModuleOption("MAX_EVENTS", "40", false, false, "Maximum timeline rows, 5-100"),
			new ModuleOption("DIRECTION", "both", false, false, "both, inbound, or outbound"),
			new ModuleOption("FAMILY", "all", false, false, "all, movement, chat, inventory, world, entities, interaction, payload, connection, or other")
		);
	}

	@Override public ModuleResult run(ModuleContext context, Map<String, String> options) {
		int seconds = boundedInt(options.get("WINDOW_SECONDS"), 5, 1, 30);
		int max = boundedInt(options.get("MAX_EVENTS"), 40, 5, 100);
		String requestedDirection = options.getOrDefault("DIRECTION", "both").toLowerCase(Locale.ROOT);
		String direction = switch (requestedDirection) {
			case "inbound", "outbound" -> requestedDirection;
			default -> "both";
		};
		String family = options.getOrDefault("FAMILY", "all").strip().toLowerCase(Locale.ROOT);
		List<PacketEvent> matching = context.packetFlow().recentEvents().stream()
			.filter(event -> event.ageMillis() <= seconds * 1_000L)
			.filter(event -> direction.equals("both") || event.direction().name().equalsIgnoreCase(direction))
			.filter(event -> family.equals("all") || familyMatches(PacketFlowAnalysis.family(event.packetType()), family))
			.toList();
		List<PacketEvent> events = matching.size() <= max ? matching : matching.subList(matching.size() - max, matching.size());
		String trace = events.stream().map(event -> String.format(Locale.ROOT, "-%dms %s %s",
			event.ageMillis(), event.direction() == PacketEvent.Direction.INBOUND ? "S→C" : "C→S", event.packetType()))
			.collect(Collectors.joining(" | "));
		List<EvidenceNode> rows = events.stream().map(event -> new EvidenceNode(
			(event.direction() == PacketEvent.Direction.INBOUND ? "S→C" : "C→S") + " " + event.packetType(),
			"-" + event.ageMillis() + " ms; " + PacketFlowAnalysis.family(event.packetType()),
			EvidenceNode.Source.OBSERVED, EvidenceNode.Confidence.HIGH,
			"Timing is relative to snapshot capture and payloads are not retained.", List.of())).toList();
		return new ModuleResult("Session metadata trace rendered", List.of(
			new Finding(Finding.Severity.INFO, "Trace scope", events.size() + " event(s), " + direction + ", " + family + " family, last " + seconds + "s",
				"Increase MAX_EVENTS only when needed; the global timeline remains capped at 512 entries."),
			new Finding(Finding.Severity.INFO, "Ordered metadata", trace.isEmpty() ? "<no matching events>" : trace,
				"Payloads are intentionally unavailable and this trace cannot transmit or replay packets to a server.")),
			List.of(EvidenceNode.group("Packet timeline", rows)));
	}

	private static boolean familyMatches(String classified, String requested) {
		String value = classified.toLowerCase(Locale.ROOT);
		return switch (requested) {
			case "movement" -> value.equals("movement");
			case "chat" -> value.equals("chat/commands");
			case "inventory" -> value.equals("inventory/menu");
			case "world" -> value.equals("world/chunks");
			case "entities" -> value.equals("entities/players");
			case "interaction", "payload", "connection", "other" -> value.startsWith(requested);
			default -> true;
		};
	}

	private static int boundedInt(String value, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
