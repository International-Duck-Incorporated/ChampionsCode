package dev.champions.client.desktop;

import dev.champions.core.console.InterfacePreferences;

import java.util.List;
import java.util.Locale;

/**
 * Backs the {@code interfaces} console command with the real client state, so
 * the user can move between the in-game windows and the external app from chat
 * and have the choice persist in {@code desktop.properties}.
 */
public final class ChatInterfacePreferences implements InterfacePreferences {
	private static final List<Mode> MODES = List.of(
		new Mode("both", "in-game windows and the external app (default)"),
		new Mode("external", "external app only, in-game windows stay closed"),
		new Mode("in-game", "in-game windows only, no external process"));

	private final DesktopRuntime runtime;
	private final Runnable applyExternal;

	/**
	 * @param runtime the desktop runtime that owns the settings file and bridge
	 * @param applyExternal re-reads settings and starts the bridge when it was
	 *        just enabled (called on the client thread)
	 */
	public ChatInterfacePreferences(DesktopRuntime runtime, Runnable applyExternal) {
		this.runtime = runtime;
		this.applyExternal = applyExternal;
	}

	@Override public List<Mode> modes() { return MODES; }

	@Override public String currentMode() {
		return runtime.settings().mode().name().toLowerCase(Locale.ROOT);
	}

	@Override public boolean inGameWindows() {
		return runtime.settings().inGameWindows();
	}

	@Override public boolean spawnOnOpen() {
		return runtime.settings().spawnOnOpen();
	}

	@Override public boolean desktopAttached() {
		return runtime.isAttached();
	}

	@Override public String setMode(String id) {
		DesktopSettings.Mode mode;
		try {
			mode = DesktopSettings.Mode.valueOf(InterfacePreferences.normalise(id).replace('-', '_').toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException unknown) {
			return "Unknown interface '" + id + "'.";
		}
		runtime.settings().setMode(mode);
		applyExternal.run();
		return switch (mode) {
			case BOTH -> "Interfaces: in-game windows + external app. Saved to desktop.properties.";
			case EXTERNAL -> "Interfaces: external app only. Saved to desktop.properties.";
			case IN_GAME -> "Interfaces: in-game windows only. Saved to desktop.properties.";
		};
	}

	@Override public void setInGameWindows(boolean enabled) {
		runtime.settings().setInGameWindows(enabled);
	}

	@Override public void setSpawnOnOpen(boolean enabled) {
		runtime.settings().setSpawnOnOpen(enabled);
	}

	@Override public void reload() {
		runtime.settings().reload();
		applyExternal.run();
	}
}
