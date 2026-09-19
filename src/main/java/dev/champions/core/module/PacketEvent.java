package dev.champions.core.module;

/** Metadata-only packet event. Payloads and chat contents are never retained. */
public record PacketEvent(Direction direction, String packetType, long ageMillis) {
	public PacketEvent {
		packetType = packetType == null || packetType.isBlank() ? "unknown" : packetType;
		ageMillis = Math.max(0, ageMillis);
	}

	public enum Direction { INBOUND, OUTBOUND }
}
