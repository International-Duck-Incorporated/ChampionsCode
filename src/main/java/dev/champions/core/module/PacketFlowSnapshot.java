package dev.champions.core.module;

import java.util.List;
import java.util.Map;

/** Bounded packet metadata snapshot; never contains packet payload bytes. */
public record PacketFlowSnapshot(
	long totalInbound,
	long totalOutbound,
	long connectionErrors,
	long timelineEvictions,
	Map<String, Long> inboundByType,
	Map<String, Long> outboundByType,
	List<PacketEvent> recentEvents
) {
	public static final PacketFlowSnapshot EMPTY = new PacketFlowSnapshot(0, 0, 0, 0, Map.of(), Map.of(), List.of());

	public PacketFlowSnapshot {
		inboundByType = inboundByType == null ? Map.of() : Map.copyOf(inboundByType);
		outboundByType = outboundByType == null ? Map.of() : Map.copyOf(outboundByType);
		recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
	}
}
