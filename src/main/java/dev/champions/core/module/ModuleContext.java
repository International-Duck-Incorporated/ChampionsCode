package dev.champions.core.module;

import java.util.List;

/** Immutable snapshot of state already available to the connected client. */
public record ModuleContext(
	boolean connected,
	boolean integratedServer,
	boolean memoryTransport,
	boolean onlineMode,
	String serverAddress,
	String serverBrand,
	String advertisedVersion,
	int advertisedProtocol,
	long pingMillis,
	int onlinePlayers,
	String resourcePackPolicy,
	String playerName,
	String playerPosition,
	String dimension,
	int clientFps,
	float averageReceivedPackets,
	float averageSentPackets,
	List<String> advertisedCommands,
	List<CommandDescriptor> advertisedCommandDetails,
	PacketFlowSnapshot packetFlow,
	InventorySnapshot inventory,
	List<PlayerMotionObservation> playerMotion
) {
	public ModuleContext {
		serverAddress = safe(serverAddress);
		serverBrand = safe(serverBrand);
		advertisedVersion = safe(advertisedVersion);
		resourcePackPolicy = safe(resourcePackPolicy);
		playerName = safe(playerName);
		playerPosition = safe(playerPosition);
		dimension = safe(dimension);
		advertisedCommands = advertisedCommands == null ? List.of() : List.copyOf(advertisedCommands);
		advertisedCommandDetails = advertisedCommandDetails == null ? List.of() : List.copyOf(advertisedCommandDetails);
		packetFlow = packetFlow == null ? PacketFlowSnapshot.EMPTY : packetFlow;
		inventory = inventory == null ? InventorySnapshot.EMPTY : inventory;
		playerMotion = playerMotion == null ? List.of() : List.copyOf(playerMotion);
	}

	/** Compatibility constructor for contexts that only need command root names. */
	public ModuleContext(
		boolean connected, boolean integratedServer, boolean memoryTransport, boolean onlineMode,
		String serverAddress, String serverBrand, String advertisedVersion, int advertisedProtocol,
		long pingMillis, int onlinePlayers, String resourcePackPolicy, String playerName,
		String playerPosition, String dimension, int clientFps, float averageReceivedPackets,
		float averageSentPackets, List<String> advertisedCommands
	) {
		this(connected, integratedServer, memoryTransport, onlineMode, serverAddress, serverBrand,
			advertisedVersion, advertisedProtocol, pingMillis, onlinePlayers, resourcePackPolicy,
			playerName, playerPosition, dimension, clientFps, averageReceivedPackets,
			averageSentPackets, advertisedCommands, List.of(), PacketFlowSnapshot.EMPTY,
			InventorySnapshot.EMPTY, List.of());
	}

	/** Compatibility constructor for contexts with command-tree details but no telemetry. */
	public ModuleContext(
		boolean connected, boolean integratedServer, boolean memoryTransport, boolean onlineMode,
		String serverAddress, String serverBrand, String advertisedVersion, int advertisedProtocol,
		long pingMillis, int onlinePlayers, String resourcePackPolicy, String playerName,
		String playerPosition, String dimension, int clientFps, float averageReceivedPackets,
		float averageSentPackets, List<String> advertisedCommands, List<CommandDescriptor> commandDetails
	) {
		this(connected, integratedServer, memoryTransport, onlineMode, serverAddress, serverBrand,
			advertisedVersion, advertisedProtocol, pingMillis, onlinePlayers, resourcePackPolicy,
			playerName, playerPosition, dimension, clientFps, averageReceivedPackets,
			averageSentPackets, advertisedCommands, commandDetails, PacketFlowSnapshot.EMPTY,
			InventorySnapshot.EMPTY, List.of());
	}

	private static String safe(String value) {
		return value == null || value.isBlank() ? "unknown" : value;
	}
}
