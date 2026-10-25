package dev.champions.core.mc;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What this particular build of the mod can actually do on the Minecraft
 * version it was compiled against.
 *
 * <p>A multi-version build compiles the version-free core once and links it
 * against a thin per-version adapter. Some versions cannot offer every feature
 * (a renamed packet class, a changed container API, a screen layer that was not
 * ported yet). Instead of failing to build, the adapter declares what it has,
 * and anything that needs a missing feature reports it plainly rather than
 * silently producing wrong evidence.</p>
 *
 * <p>This record is version- and platform-free: it lives in {@code core} and is
 * reported by the console, the in-game status view, and the external app.</p>
 */
public record ClientCapabilities(
		String minecraftVersion,
		String loaderVersion,
		String modVersion,
		boolean guiScreens,
		boolean commandTree,
		boolean packetTelemetry,
		boolean inventoryTelemetry,
		boolean motionTelemetry,
		boolean externalUi,
		String buildTarget
) {
	/** A feature one module may depend on. */
	public enum Feature {
		GUI_SCREENS("in-game windows"),
		COMMAND_TREE("advertised command tree"),
		PACKET_TELEMETRY("packet flow telemetry"),
		INVENTORY_TELEMETRY("container snapshots"),
		MOTION_TELEMETRY("player motion samples"),
		EXTERNAL_UI("external desktop app");

		private final String label;

		Feature(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public ClientCapabilities {
		minecraftVersion = safe(minecraftVersion);
		loaderVersion = safe(loaderVersion);
		modVersion = safe(modVersion);
		buildTarget = safe(buildTarget);
	}

	/** Everything available: the reference build for the current Minecraft version. */
	public static ClientCapabilities full(String minecraftVersion, String loaderVersion, String modVersion, String buildTarget) {
		return new ClientCapabilities(minecraftVersion, loaderVersion, modVersion,
			true, true, true, true, true, true, buildTarget);
	}

	/**
	 * Core plus external UI only, for a Minecraft version whose screen layer is
	 * not ported. Every UI then lives in the desktop app; passive modules that do
	 * not need telemetry still run.
	 */
	public static ClientCapabilities headless(String minecraftVersion, String loaderVersion, String modVersion, String buildTarget) {
		return new ClientCapabilities(minecraftVersion, loaderVersion, modVersion,
			false, true, true, true, true, true, buildTarget);
	}

	/**
	 * Document-only build: for a Minecraft version whose telemetry classes changed
	 * enough that only the text-based modules can be trusted. Everything that
	 * needs a live tree or packet/container access is refused with a reason.
	 */
	public static ClientCapabilities minimal(String minecraftVersion, String loaderVersion, String modVersion, String buildTarget) {
		return new ClientCapabilities(minecraftVersion, loaderVersion, modVersion,
			false, false, false, false, false, true, buildTarget);
	}

	/** No game attached at all, which is the standalone desktop app. */
	public static ClientCapabilities standalone(String modVersion) {
		return new ClientCapabilities("none", "none", modVersion, false, false, false, false, false, true, "desktop");
	}

	public boolean supports(Feature feature) {
		return switch (feature) {
			case GUI_SCREENS -> guiScreens;
			case COMMAND_TREE -> commandTree;
			case PACKET_TELEMETRY -> packetTelemetry;
			case INVENTORY_TELEMETRY -> inventoryTelemetry;
			case MOTION_TELEMETRY -> motionTelemetry;
			case EXTERNAL_UI -> externalUi;
		};
	}

	public boolean supportsAll(Set<Feature> features) {
		for (Feature feature : features) if (!supports(feature)) return false;
		return true;
	}

	/** Human-readable list of what is missing, used verbatim in console messages. */
	public String missing(Set<Feature> features) {
		Set<String> labels = new LinkedHashSet<>();
		for (Feature feature : features) if (!supports(feature)) labels.add(feature.label());
		return String.join(", ", labels);
	}

	public String summary() {
		StringBuilder out = new StringBuilder();
		if ("none".equals(minecraftVersion)) {
			out.append("standalone desktop");
			if (!"unknown".equals(modVersion)) out.append(" ").append(modVersion);
			out.append(" · external ui only");
			return out.toString();
		}
		out.append("mc ").append(minecraftVersion).append(" · mod ").append(modVersion);
		if (!"unknown".equals(buildTarget)) out.append(" · target ").append(buildTarget);
		out.append(" · ");
		Set<String> available = new LinkedHashSet<>();
		for (Feature feature : Feature.values()) {
			if (supports(feature)) available.add(feature.name().toLowerCase(java.util.Locale.ROOT));
		}
		out.append(available.isEmpty() ? "no optional features" : String.join(", ", available));
		return out.toString();
	}

	private static String safe(String value) {
		return value == null || value.isBlank() ? "unknown" : value;
	}
}
