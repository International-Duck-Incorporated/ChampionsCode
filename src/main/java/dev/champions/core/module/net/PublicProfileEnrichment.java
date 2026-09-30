package dev.champions.core.module.net;

import dev.champions.core.update.MiniJson;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Optional enrichment from PlayerDB plus a canonical public Laby.net profile link. */
public final class PublicProfileEnrichment {
	private static final String PLAYERDB = "https://playerdb.co/api/player/minecraft/";
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private PublicProfileEnrichment() {}

	public static PlayerProfileDetails lookup(MojangProfileClient.Identity identity) {
		String compact = identity.dashedUuid().replace("-", "");
		String playerDbUrl = PLAYERDB + compact;
		String labyUrl = "https://laby.net/@" + URLEncoder.encode(identity.name(), StandardCharsets.UTF_8);
		String avatar = null;
		String cachedAt = null;
		String status = "unavailable";
		List<String> names = new ArrayList<>();

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(playerDbUrl))
				.timeout(Duration.ofSeconds(6))
				.header("Accept", "application/json")
				.header("User-Agent", "ChampionsCode (public player profile lookup)")
				.GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200) {
				Object parsed = MiniJson.parse(response.body());
				if (parsed instanceof Map<?, ?> root
					&& root.get("data") instanceof Map<?, ?> data
					&& data.get("player") instanceof Map<?, ?> player) {
					if (player.get("avatar") instanceof String value) avatar = value;
					if (player.get("username") instanceof String value) names.add(value);
					if (player.get("meta") instanceof Map<?, ?> meta
						&& meta.get("cached_at") instanceof Number timestamp) {
						cachedAt = Instant.ofEpochSecond(timestamp.longValue()).toString();
					}
					if (player.get("meta") instanceof Map<?, ?> meta
						&& meta.get("name_history") instanceof List<?> history) {
						for (Object entry : history) {
							if (entry instanceof Map<?, ?> item && item.get("name") instanceof String value
								&& !names.contains(value)) names.add(value);
						}
					}
					status = "available";
				} else status = "unexpected response";
			} else status = "HTTP " + response.statusCode();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			status = "interrupted";
		} catch (Exception failure) {
			status = "unavailable: " + safeMessage(failure);
		}

		return new PlayerProfileDetails(identity, avatar, cachedAt, names, playerDbUrl, labyUrl, status);
	}

	private static String safeMessage(Exception failure) {
		String message = failure.getMessage();
		if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
		return message.length() <= 100 ? message : message.substring(0, 100) + "...";
	}
}
