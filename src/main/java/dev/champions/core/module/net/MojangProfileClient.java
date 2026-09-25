package dev.champions.core.module.net;

import dev.champions.core.update.MiniJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Bounded client for Mojang's public profile endpoints: username to UUID
 * resolution and the session-server profile with skin/cape textures. These
 * endpoints are the same ones the vanilla launcher uses; the client never
 * contacts the connected Minecraft server and sends no session credentials.
 */
public final class MojangProfileClient {
	private static final String PROFILE_BY_NAME = "https://api.mojang.com/users/profiles/minecraft/";
	private static final String PROFILE_BY_UUID = "https://sessionserver.mojang.com/session/minecraft/profile/";

	private MojangProfileClient() {}

	/** Narrow HTTP seam so lookups can be exercised without network access. */
	public interface Gateway {
		GatewayReply get(String url);
	}

	/** Status code plus body; an absent body is an empty string. */
	public record GatewayReply(int status, String body) {}

	/** The public identity surface of one player account. */
	public record Identity(String name, String dashedUuid, String skinUrl, String skinModel, String capeUrl) {}

	public enum Outcome { FOUND, NOT_FOUND, RATE_LIMITED, ERROR }

	/** One completed lookup with a machine-readable outcome. */
	public record LookupResult(Outcome outcome, Identity identity, String detail) {}

	/** Default gateway backed by java.net.http with short timeouts. */
	public static final Gateway HTTP = url -> {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(5))
			.header("User-Agent", "ChampionsCode (public profile research lookup)")
			.header("Accept", "application/json")
			.GET()
			.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			return new GatewayReply(response.statusCode(), response.body() == null ? "" : response.body());
		} catch (IOException | InterruptedException failure) {
			if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new IllegalStateException("Mojang API request failed: " + failure.getMessage(), failure);
		}
	};

	public static LookupResult byName(Gateway gateway, String username) {
		if (!validUsername(username)) return new LookupResult(Outcome.ERROR, null, "not a valid username: " + username);
		GatewayReply reply = gateway.get(PROFILE_BY_NAME + username);
		if (reply.status() == 204 || reply.status() == 404) return new LookupResult(Outcome.NOT_FOUND, null, "no premium account with that username");
		if (reply.status() == 429) return new LookupResult(Outcome.RATE_LIMITED, null, "Mojang API rate limit reached");
		if (reply.status() != 200) return new LookupResult(Outcome.ERROR, null, "HTTP " + reply.status() + " from api.mojang.com");
		Object root = MiniJson.parse(reply.body());
		if (!(root instanceof Map<?,?> profile) || !(profile.get("id") instanceof String id)) {
			return new LookupResult(Outcome.ERROR, null, "unexpected profile payload");
		}
		return byUuid(gateway, id);
	}

	public static LookupResult byUuid(Gateway gateway, String uuid) {
		String normalized = normalizeUuid(uuid);
		if (normalized == null) return new LookupResult(Outcome.ERROR, null, "not a valid UUID: " + uuid);
		GatewayReply reply = gateway.get(PROFILE_BY_UUID + normalized);
		if (reply.status() == 204 || reply.status() == 404) return new LookupResult(Outcome.NOT_FOUND, null, "no profile for that UUID");
		if (reply.status() == 429) return new LookupResult(Outcome.RATE_LIMITED, null, "Mojang API rate limit reached");
		if (reply.status() != 200) return new LookupResult(Outcome.ERROR, null, "HTTP " + reply.status() + " from sessionserver.mojang.com");
		try {
			return new LookupResult(Outcome.FOUND, parseUuidProfile(reply.body()), "resolved");
		} catch (RuntimeException invalid) {
			return new LookupResult(Outcome.ERROR, null, "unexpected profile payload: " + invalid.getMessage());
		}
	}

	/** Parses a session-server profile body into an {@link Identity}. */
	public static Identity parseUuidProfile(String body) {
		Object root = MiniJson.parse(body);
		if (!(root instanceof Map<?,?> profile)) throw new IllegalArgumentException("profile is not an object");
		if (!(profile.get("id") instanceof String rawId) || !(profile.get("name") instanceof String name)) {
			throw new IllegalArgumentException("profile is missing id or name");
		}
		String skinUrl = null;
		String skinModel = null;
		String capeUrl = null;
		if (profile.get("properties") instanceof List<?> properties) {
			for (Object property : properties) {
				if (!(property instanceof Map<?,?> entry) || !"textures".equals(entry.get("name"))) continue;
				if (!(entry.get("value") instanceof String encoded)) continue;
				Object decoded = MiniJson.parse(new String(Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8));
				if (decoded instanceof Map<?,?> payload && payload.get("textures") instanceof Map<?,?> textures) {
					if (textures.get("SKIN") instanceof Map<?,?> skin && skin.get("url") instanceof String url) {
						skinUrl = url;
						if (skin.get("metadata") instanceof Map<?,?> metadata && "slim".equals(metadata.get("model"))) {
							skinModel = "slim";
						}
					}
					if (textures.get("CAPE") instanceof Map<?,?> cape && cape.get("url") instanceof String url) capeUrl = url;
				}
			}
		}
		return new Identity(name, dashed(rawId), skinUrl, skinModel == null ? "classic" : skinModel, capeUrl);
	}

	/** Accepts dashed or undashed hex; returns the undashed lowercase form or null. */
	public static String normalizeUuid(String uuid) {
		if (uuid == null) return null;
		String compact = uuid.strip().replace("-", "").toLowerCase(java.util.Locale.ROOT);
		return compact.matches("[0-9a-f]{32}") ? compact : null;
	}

	public static String dashed(String uuid) {
		String compact = normalizeUuid(uuid);
		if (compact == null) throw new IllegalArgumentException("not a UUID: " + uuid);
		return compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-" + compact.substring(12, 16)
			+ "-" + compact.substring(16, 20) + "-" + compact.substring(20, 32);
	}

	public static boolean validUsername(String username) {
		return username != null && username.matches("[A-Za-z0-9_]{1,16}");
	}
}
