package dev.champions.client;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.champions.core.module.CommandDescriptor;
import dev.champions.core.module.ModuleContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;

import java.util.List;
import java.util.Locale;

final class MinecraftResearchContext {
	private MinecraftResearchContext() {}

	static ModuleContext capture(Minecraft client) {
		ClientPacketListener listener = client.getConnection();
		ServerData server = client.getCurrentServer();
		boolean connected = listener != null;
		Connection network = connected ? listener.getConnection() : null;
		boolean integrated = client.hasSingleplayerServer() || (network != null && network.isMemoryConnection());
		String address = integrated ? "singleplayer" : server == null ? "unknown" : server.ip;
		String brand = connected ? listener.serverBrand() : "unknown";
		String version = server != null && server.version != null ? server.version.getString() : client.getLaunchedVersion();
		int protocol = server == null ? -1 : server.protocol;
		long ping = server == null ? -1L : server.ping;
		int players = connected ? listener.getOnlinePlayers().size() : 0;
		String packPolicy = server == null ? "not applicable" : server.getResourcePackStatus().toString().toLowerCase(Locale.ROOT);
		String playerName = client.player == null ? "unknown" : client.player.getName().getString();
		String position = client.player == null ? "unknown" : String.format(Locale.ROOT, "%.2f, %.2f, %.2f", client.player.getX(), client.player.getY(), client.player.getZ());
		String dimension = client.player == null ? "unknown" : client.player.level().dimension().identifier().toString();
		List<String> commands = connected
			? listener.getCommands().getRoot().getChildren().stream().map(CommandNode::getName).sorted().toList()
			: List.of();
		List<CommandDescriptor> commandDetails = connected
			? listener.getCommands().getRoot().getChildren().stream()
				.map(node -> new CommandDescriptor(
					node.getName(),
					node.getCommand() != null,
					node.getChildren().stream()
						.filter(LiteralCommandNode.class::isInstance)
						.map(CommandNode::getName)
						.toList()))
				.sorted(java.util.Comparator.comparing(CommandDescriptor::root))
				.toList()
			: List.of();

		return new ModuleContext(
			connected,
			integrated,
			network != null && network.isMemoryConnection(),
			connected && listener.onlineMode(),
			address,
			brand,
			version,
			protocol,
			ping,
			players,
			packPolicy,
			playerName,
			position,
			dimension,
			client.getFps(),
			network == null ? 0F : network.getAverageReceivedPackets(),
			network == null ? 0F : network.getAverageSentPackets(),
			commands,
			commandDetails,
			PacketTelemetry.snapshot(),
			InventoryTelemetry.capture(client),
			PlayerMotionTelemetry.snapshot()
		);
	}
}
