package dev.champions.core.console;

import java.util.List;
import java.util.Locale;

/**
 * Host-provided bridge for the {@code interfaces} console command, so the user
 * can switch between the in-game windows and the external desktop app from
 * chat, without editing a file.
 *
 * <p>The core stays platform-free: the client adapter implements this against
 * {@code DesktopSettings} plus the desktop runtime. In the standalone app the
 * command edits the same {@code desktop.properties} file directly.</p>
 */
public interface InterfacePreferences {
	/** One selectable interface mode. */
	record Mode(String id, String description) {}

	List<Mode> modes();

	/** Current mode id, or "unknown" when it cannot be read. */
	String currentMode();

	/** Also open the in-game windows when the external app is active. */
	boolean inGameWindows();

	/** Whether the desktop app is allowed to be started automatically. */
	boolean spawnOnOpen();

	/** True when a desktop session is attached right now. */
	boolean desktopAttached();

	/** Applies a mode id; the returned text is shown in the console. */
	String setMode(String id);

	void setInGameWindows(boolean enabled);

	void setSpawnOnOpen(boolean enabled);

	/** Re-reads the settings file and applies what changed. */
	void reload();

	static InterfacePreferences unavailable() {
		List<Mode> modes = List.of(new Mode("in-game", "in-game windows only"));
		return new InterfacePreferences() {
			@Override public List<Mode> modes() { return modes; }
			@Override public String currentMode() { return "in-game"; }
			@Override public boolean inGameWindows() { return true; }
			@Override public boolean spawnOnOpen() { return false; }
			@Override public boolean desktopAttached() { return false; }
			@Override public String setMode(String id) { return "This build has no external interface."; }
			@Override public void setInGameWindows(boolean enabled) {}
			@Override public void setSpawnOnOpen(boolean enabled) {}
			@Override public void reload() {}
		};
	}

	static String normalise(String value) {
		return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
	}
}
