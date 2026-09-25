package dev.champions.core.module.net;

import dev.champions.core.update.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Normalizes the server status JSON document into a bounded, typed view.
 * Optional fields the server did not send stay absent (null flags, negative
 * player counts, empty lists) so callers can distinguish "not advertised".
 */
public final class ServerStatusParser {
	private ServerStatusParser() {}

	/** The fields any unauthenticated server-list pinger can observe. */
	public record ParsedStatus(
		String versionName,
		int protocol,
		String motd,
		int playersOnline,
		int playersMax,
		List<String> sample,
		boolean favicon,
		Boolean enforcesSecureChat,
		Boolean preventsChatReports,
		String modPlatform,
		List<String> mods
	) {}

	public static ParsedStatus parse(String json) {
		Object root = MiniJson.parse(json);
		if (!(root instanceof Map<?,?> document)) {
			throw new IllegalArgumentException("status reply is not a JSON object");
		}

		String versionName = "unknown";
		int protocol = -1;
		if (document.get("version") instanceof Map<?,?> version) {
			if (version.get("name") instanceof String name) versionName = name;
			if (version.get("protocol") instanceof Number number) protocol = number.intValue();
		}

		String motd = flattenDescription(document.get("description"));

		int playersOnline = -1;
		int playersMax = -1;
		List<String> sample = List.of();
		if (document.get("players") instanceof Map<?,?> players) {
			if (players.get("online") instanceof Number online) playersOnline = online.intValue();
			if (players.get("max") instanceof Number max) playersMax = max.intValue();
			if (players.get("sample") instanceof List<?> entries) {
				List<String> names = new ArrayList<>();
				for (Object entry : entries) {
					if (entry instanceof Map<?,?> player && player.get("name") instanceof String name && names.size() < 10) {
						names.add(name);
					}
				}
				sample = List.copyOf(names);
			}
		}

		boolean favicon = document.get("favicon") instanceof String icon && icon.startsWith("data:image");
		Boolean enforcesSecureChat = document.get("enforcesSecureChat") instanceof Boolean flag ? flag : null;
		Boolean preventsChatReports = document.get("preventsChatReports") instanceof Boolean flag ? flag : null;

		String modPlatform = null;
		List<String> mods = List.of();
		if (document.get("modinfo") instanceof Map<?,?> modInfo) {
			if (modInfo.get("type") instanceof String type) modPlatform = type;
			if (modInfo.get("modList") instanceof List<?> entries) {
				List<String> ids = new ArrayList<>();
				for (Object entry : entries) {
					if (entry instanceof Map<?,?> mod && mod.get("modid") instanceof String id && ids.size() < 10) {
						ids.add(id);
					}
				}
				mods = List.copyOf(ids);
			}
		}

		return new ParsedStatus(versionName, protocol, motd, playersOnline, playersMax, sample,
			favicon, enforcesSecureChat, preventsChatReports, modPlatform, mods);
	}

	/** Flattens a chat-component description (string or object with extras) and strips legacy color codes. */
	static String flattenDescription(Object description) {
		StringBuilder out = new StringBuilder();
		appendComponent(description, out);
		return stripLegacyColors(out.toString()).strip();
	}

	private static void appendComponent(Object component, StringBuilder out) {
		if (out.length() > 400) return;
		if (component instanceof String text) {
			out.append(text);
		} else if (component instanceof Map<?,?> map) {
			if (map.get("text") instanceof String text) out.append(text);
			if (map.get("extra") instanceof List<?> extras) {
				for (Object extra : extras) appendComponent(extra, out);
			}
		}
	}

	static String stripLegacyColors(String value) {
		return value.replaceAll("§.", "");
	}
}
