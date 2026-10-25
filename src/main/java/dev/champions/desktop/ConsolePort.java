package dev.champions.desktop;

import dev.champions.surface.ConsoleBuffer;

import java.util.List;

/**
 * Everything the desktop windows need from a console, independent of whether
 * that console lives in this process (standalone) or in the running game
 * (attached over loopback).
 *
 * <p>Both implementations expose the same data the in-game screens read from
 * {@code ChampionConsole}, which is what keeps the two front ends behaviourally
 * identical without sharing a single line of rendering code.</p>
 */
public interface ConsolePort extends AutoCloseable {
	/** Shared terminal model; poll {@link ConsoleBuffer#revision()} to repaint only on change. */
	ConsoleBuffer buffer();

	String prompt();

	Status status();

	List<ModuleInfo> modules();

	/** Selected module with its current option values, or null when nothing is selected. */
	ActiveInfo active();

	List<String> history();

	void submit(String line);

	void selectModule(String id);

	void setOption(String name, String value);

	void run();

	void clearSelection();

	void setAuthorized(boolean authorized);

	/** "ATTACHED", "DETACHED" or "STANDALONE" - shown in the status bar. */
	String mode();

	/** What the game build reports it can do; empty when unknown. */
	default String capabilities() {
		return "";
	}

	@Override
	void close();

	record Status(boolean connected, String target, String brand, String player,
			boolean authorized, boolean authorizeAvailable, String running, String detail) {
		public static Status offline(String detail) {
			return new Status(false, "unknown", "unknown", "unknown", false, false, "", detail);
		}

		public String summary() {
			if (!connected) return "offline · " + detail;
			return (authorized ? "AUTHORIZED" : "not authorized") + " · " + target
				+ (brand.isBlank() || brand.equals("unknown") ? "" : " · " + brand)
				+ (running.isBlank() ? "" : " · running " + running);
		}
	}

	record ModuleInfo(String id, String name, String description, boolean selected, boolean exploit) {}

	record OptionInfo(String name, String value, String defaultValue, boolean required, boolean secret,
			boolean configured, String description, List<String> choices) {
		public String displayValue() {
			if (secret && !configured) return "";
			return value == null || value.isBlank() ? defaultValue : value;
		}
	}

	record ActiveInfo(String id, String name, String description, boolean exploit, List<OptionInfo> options) {}
}
