package dev.champions.client.update;

import dev.champions.core.update.UpdateCheck;
import dev.champions.core.update.UpdateFeedParser;
import dev.champions.core.update.UpdateResult;
import dev.champions.core.update.UpdateService;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Update service backed by this project's public GitLab releases. Checks read
 * the releases API; downloads verify the JAR's SHA-256 against the release's
 * SHA256SUMS.txt before anything is written to the mods folder. Only HTTPS
 * URLs on gitlab.com are ever contacted, and nothing is executed or hot-swapped.
 */
public final class GitLabUpdateService implements UpdateService {
	private static final String HOST = "gitlab.com";
	private static final String FEED_URL = "https://" + HOST + "/api/v4/projects/"
		+ "international-duck-incorporated%2FChampionsCode/releases?per_page=10";

	private final HttpClient http = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	@Override
	public CompletionStage<Optional<UpdateCheck>> checkLatest() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return UpdateFeedParser.parse(fetchText(FEED_URL), currentVersion());
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Update check interrupted", interrupted);
			} catch (IOException failure) {
				throw new java.io.UncheckedIOException(failure);
			}
		});
	}

	@Override
	public CompletionStage<UpdateResult> download(UpdateCheck check) {
		return CompletableFuture.supplyAsync(() -> downloadVerified(check));
	}

	private UpdateResult downloadVerified(UpdateCheck check) {
		String version = check.latestVersion();
		if (check.jarUrl().isEmpty() || check.checksumsUrl().isEmpty()) {
			return UpdateResult.failure("Release " + version + " does not list both a mod JAR and a SHA256SUMS asset; refusing to download.");
		}
		String jarName = "champions-mod-" + version + ".jar";
		try {
			String sums = fetchText(check.checksumsUrl());
			String expected = expectedChecksum(sums, jarName);
			if (expected == null) {
				return UpdateResult.failure("SHA256SUMS.txt has no entry for " + jarName + "; refusing to download.");
			}
			byte[] jar = fetchBytes(check.jarUrl());
			String actual = sha256(jar);
			if (!actual.equals(expected)) {
				return UpdateResult.failure("Checksum mismatch for " + jarName + "; nothing was written (expected " + expected + ", got " + actual + ").");
			}
			Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
			Files.createDirectories(modsDir);
			Path target = modsDir.resolve(jarName);
			Path staging = Files.createTempFile(modsDir, jarName + ".", ".part");
			Files.write(staging, jar);
			try {
				Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
				Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return UpdateResult.success("Verified and saved " + jarName + " (SHA-256 " + actual + ").", target.toString());
		} catch (Exception failure) {
			return UpdateResult.failure("Update download failed: " + failure.getMessage());
		}
	}

	private byte[] fetchBytes(String url) throws IOException, InterruptedException {
		requireProjectHost(url);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(60))
			.header("User-Agent", "ChampionsCode/" + currentVersion() + " (mod self-update)")
			.GET()
			.build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " from " + HOST);
		}
		return response.body();
	}

	private String fetchText(String url) throws IOException, InterruptedException {
		return new String(fetchBytes(url), StandardCharsets.UTF_8);
	}

	private static void requireProjectHost(String url) {
		URI uri = URI.create(url);
		if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost())) {
			throw new IllegalArgumentException("Refusing a non-" + HOST + " update URL: " + url);
		}
	}

	private static String expectedChecksum(String sums, String jarName) {
		for (String line : sums.split("\\R")) {
			String[] parts = line.trim().split("\\s+");
			if (parts.length == 2 && parts[1].equals(jarName)) {
				return parts[0].toLowerCase(Locale.ROOT);
			}
		}
		return null;
	}

	private static String sha256(byte[] data) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		return HexFormat.of().formatHex(digest.digest(data));
	}

	private static String currentVersion() {
		return FabricLoader.getInstance().getModContainer("champions")
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}
}
