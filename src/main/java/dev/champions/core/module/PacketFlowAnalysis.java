package dev.champions.core.module;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Shared payload-free grouping and timing calculations for packet-flow views. */
public final class PacketFlowAnalysis {
	private PacketFlowAnalysis() {}

	public static Map<String, Long> counts(List<PacketEvent> events, Predicate<PacketEvent> filter) {
		return events.stream().filter(filter).collect(Collectors.groupingBy(PacketEvent::packetType,
			LinkedHashMap::new, Collectors.counting()));
	}

	public static Map<String, Long> families(List<PacketEvent> events, PacketEvent.Direction direction) {
		return events.stream().filter(event -> event.direction() == direction)
			.collect(Collectors.groupingBy(event -> family(event.packetType()), LinkedHashMap::new, Collectors.counting()));
	}

	public static String family(String packetType) {
		String type = packetType == null ? "" : packetType.toLowerCase(Locale.ROOT);
		if (type.contains("custompayload")) return "Custom payload";
		if (type.contains("container") || type.contains("setslot") || type.contains("inventory")) return "Inventory/menu";
		if (type.contains("moveplayer") || type.contains("position") || type.contains("rotation") || type.contains("teleport")) return "Movement";
		if (type.contains("chat") || type.contains("command") || type.contains("systemmessage")) return "Chat/commands";
		if (type.contains("chunk") || type.contains("block") || type.contains("lightupdate") || type.contains("level")) return "World/chunks";
		if (type.contains("entity") || type.contains("mob") || type.contains("playerinfo")) return "Entities/players";
		if (type.contains("interact") || type.contains("useitem") || type.contains("swing")) return "Interaction";
		if (type.contains("keepalive") || type.contains("ping") || type.contains("pong")) return "Connection";
		return "Other";
	}

	public static String formatCounts(Map<String, Long> counts, int limit) {
		String value = counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
			.limit(limit).map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(", "));
		return value.isEmpty() ? "<none observed>" : value;
	}
}
