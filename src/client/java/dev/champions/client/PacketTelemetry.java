package dev.champions.client;

import dev.champions.core.module.PacketEvent;
import dev.champions.core.module.PacketFlowSnapshot;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	/** Channel identifiers seen on the wire. Metadata only: never a payload body. */
	private static final Set<String> CHANNELS = new LinkedHashSet<>();
	private static final int MAX_CHANNELS = 64;

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

	/**
	 * Channel identifiers this connection has carried, in {@code namespace:path}
	 * form.
	 *
	 * <p>A custom payload's <em>identifier</em> says a mod on the other end is
	 * registered on that channel; the body says nothing this tool keeps. Only the
	 * identifier is recorded, it is bounded, and it is never written to the audit
	 * log.</p>
	 */
	public static synchronized Set<String> channels() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(CHANNELS));
	}

	private static void recordChannel(Packet<?> packet) {
		if (packet == null) return;
		if (CHANNELS.size() >= MAX_CHANNELS) return;
		String id = channelId(packet);
		if (id == null || id.isBlank()) return;
		CHANNELS.add(sanitizeType(id));
	}

	private static String channelId(Packet<?> packet) {
		try {
			if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket inbound) {
				var payload = inbound.payload();
				if (payload == null || payload.type() == null || payload.type().id() == null) return null;
				return payload.type().id().toString();
			}
		} catch (RuntimeException | LinkageError unsupported) {
			return null;
		}
		return null;
	}

	private static void record(PacketEvent.Direction direction, Packet<?> packet) {
		recordChannel(packet);
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
