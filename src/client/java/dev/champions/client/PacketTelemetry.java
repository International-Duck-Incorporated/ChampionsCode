package dev.champions.client;

import dev.champions.core.module.PacketEvent;
import dev.champions.core.module.PacketFlowSnapshot;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe, metadata-only packet counter used by the Wire Lens modules. */
public final class PacketTelemetry {
	private static final int MAX_TIMELINE_EVENTS = 512;
	private static final ArrayDeque<TimedEvent> EVENTS = new ArrayDeque<>();
	private static final Map<String, Long> INBOUND = new HashMap<>();
	private static final Map<String, Long> OUTBOUND = new HashMap<>();
	private static long totalInbound;
	private static long totalOutbound;
	private static long connectionErrors;
	private static long evictions;

	private PacketTelemetry() {}

	public static synchronized void inbound(Packet<?> packet) {
		record(PacketEvent.Direction.INBOUND, packet);
		totalInbound++;
	}

	public static synchronized void outbound(Packet<?> packet) {
		record(PacketEvent.Direction.OUTBOUND, packet);
		totalOutbound++;
	}

	public static synchronized void connectionError(Throwable ignored) {
		connectionErrors++;
	}

	public static synchronized PacketFlowSnapshot snapshot() {
		long now = System.nanoTime();
		List<PacketEvent> recent = EVENTS.stream()
			.map(event -> new PacketEvent(event.direction, event.packetType,
				Math.max(0L, (now - event.nanoTime) / 1_000_000L)))
			.toList();
		return new PacketFlowSnapshot(totalInbound, totalOutbound, connectionErrors, evictions,
			Map.copyOf(INBOUND), Map.copyOf(OUTBOUND), recent);
	}

	private static void record(PacketEvent.Direction direction, Packet<?> packet) {
		String type = packet == null ? "unknown" : sanitizeType(packet.getClass().getSimpleName());
		(direction == PacketEvent.Direction.INBOUND ? INBOUND : OUTBOUND).merge(type, 1L, Long::sum);
		if (EVENTS.size() >= MAX_TIMELINE_EVENTS) {
			EVENTS.removeFirst();
			evictions++;
		}
		EVENTS.addLast(new TimedEvent(System.nanoTime(), direction, type));
	}

	private static String sanitizeType(String value) {
		if (value == null || value.isBlank()) return "unknown";
		return value.length() <= 120 ? value : value.substring(0, 120);
	}

	private record TimedEvent(long nanoTime, PacketEvent.Direction direction, String packetType) {}
}
