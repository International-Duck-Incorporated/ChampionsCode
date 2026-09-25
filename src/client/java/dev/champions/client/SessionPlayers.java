package dev.champions.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session-scoped registry of every player this client has observed on the current
 * server, including players who later left. Data is read passively from the client's
 * own player list; nothing is sent or requested from the server.
 *
 * <p>The registry is intentionally static: it lives for this game session and is used
 * by the ClickGUI to offer recently-seen player names (and UUIDs) as dropdown options.
 */
public final class SessionPlayers {
	private static final int CAPACITY = 256;
	/** name -> dashed UUID string, in first-seen order. */
	private static final Map<String, String> seen = new LinkedHashMap<>();
	/** Names present in the latest capture (currently online). */
	private static final Set<String> online = new LinkedHashSet<>();

	private SessionPlayers() {}

	/** Called every client tick; cheap because the player list is a small collection. */
	public static void capture(Minecraft client) {
		ClientPacketListener listener = client.getConnection();
		if (listener == null) {
			online.clear();
			return;
		}
		online.clear();
		Collection<PlayerInfo> players = listener.getOnlinePlayers();
		if (players == null) return;
		for (PlayerInfo info : players) {
			if (info == null || info.getProfile() == null) continue;
			String name = info.getProfile().name();
			if (name == null || name.isBlank()) continue;
			online.add(name);
			seen.putIfAbsent(name, info.getProfile().id().toString());
		}
		while (seen.size() > CAPACITY) {
			seen.remove(seen.keySet().iterator().next());
		}
	}

	/** All players seen this session, oldest first. */
	public static List<String> names() {
		return List.copyOf(seen.keySet());
	}

	public static boolean isOnline(String name) {
		return online.contains(name);
	}

	/** Dashed lowercase UUID for a seen player, or null. */
	public static String uuid(String name) {
		return seen.get(name);
	}

	public static int size() {
		return seen.size();
	}
}
