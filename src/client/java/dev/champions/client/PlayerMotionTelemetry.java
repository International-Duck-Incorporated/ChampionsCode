package dev.champions.client;

import dev.champions.core.module.PlayerMotionObservation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Broad, read-only remote-player movement telemetry. It never issues verdicts. */
final class PlayerMotionTelemetry {
	private static final Map<UUID, Track> TRACKS = new HashMap<>();
	private static ClientLevel trackedLevel;
	private static long tick;

	private PlayerMotionTelemetry() {}

	static synchronized void tick(Minecraft client) {
		tick++;
		if (client.level == null || client.player == null) {
			TRACKS.clear();
			trackedLevel = null;
			return;
		}
		if (trackedLevel != client.level) {
			TRACKS.clear();
			trackedLevel = client.level;
		}
		for (AbstractClientPlayer player : client.level.players()) {
			if (player == client.player) continue;
			Track track = TRACKS.computeIfAbsent(player.getUUID(), ignored -> new Track(
				sanitize(player.getName().getString()), player.getX(), player.getY(), player.getZ()));
			double dx = player.getX() - track.x;
			double dy = player.getY() - track.y;
			double dz = player.getZ() - track.z;
			double horizontal = Math.hypot(dx, dz);
			track.samples++;
			track.lastHorizontal = horizontal;
			track.lastVertical = dy;
			track.maxHorizontal = Math.max(track.maxHorizontal, horizontal);
			// Deliberately broad: teleports, lag and vehicles can all trigger this.
			if (horizontal > 8.0 || Math.abs(dy) > 8.0) track.suddenDisplacements++;
			if (Math.abs(player.getXRot()) > 90.01F) track.pitchBoundaryEvents++;
			track.x = player.getX();
			track.y = player.getY();
			track.z = player.getZ();
			track.lastSeenTick = tick;
		}
		TRACKS.entrySet().removeIf(entry -> tick - entry.getValue().lastSeenTick > 200);
	}

	static synchronized List<PlayerMotionObservation> snapshot() {
		return TRACKS.values().stream()
			.map(Track::snapshot)
			.sorted(Comparator.comparingLong(PlayerMotionObservation::suddenDisplacements).reversed()
				.thenComparing(Comparator.comparingDouble(PlayerMotionObservation::maximumHorizontalDelta).reversed()))
			.toList();
	}

	private static String sanitize(String value) {
		String cleaned = value == null ? "unknown" : value.replace('\n', ' ').replace('\r', ' ').strip();
		return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 64);
	}

	private static final class Track {
		private final String name;
		private double x;
		private double y;
		private double z;
		private long samples;
		private double lastHorizontal;
		private double lastVertical;
		private double maxHorizontal;
		private long suddenDisplacements;
		private long pitchBoundaryEvents;
		private long lastSeenTick = tick;

		private Track(String name, double x, double y, double z) {
			this.name = name;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		private PlayerMotionObservation snapshot() {
			List<String> notes = new ArrayList<>();
			if (suddenDisplacements > 0) notes.add("broad displacement threshold crossed; teleport/lag/vehicle may explain it");
			if (pitchBoundaryEvents > 0) notes.add("pitch outside normal rendered boundary observed");
			return new PlayerMotionObservation(name, samples, lastHorizontal, lastVertical,
				maxHorizontal, suddenDisplacements, pitchBoundaryEvents, notes);
		}
	}
}
