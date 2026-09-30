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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
	private static final String PROJECT_PATH = "/international-duck-incorporated/ChampionsCode/";
	private static final String PROJECT_API_PREFIX = "/api/v4/projects/86855171/";
	private static final int MAX_REDIRECTS = 3;
	private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
	private static final int MAX_JAR_BYTES = 64 * 1024 * 1024;
	private static final String FEED_URL = "https://" + HOST + "/api/v4/projects/"
		+ "international-duck-incorporated%2FChampionsCode/releases?per_page=10";

	private final HttpClient http = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
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
			try {
				Files.write(staging, jar);
				try {
					Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
					Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(staging);
			}
			return UpdateResult.success("Verified and saved " + jarName + " (SHA-256 " + actual + ").", target.toString());
		} catch (Exception failure) {
			return UpdateResult.failure("Update download failed: " + failure.getMessage());
		}
	}

	private byte[] fetchBytes(String url) throws IOException, InterruptedException {
		return fetchBytes(url, MAX_JAR_BYTES);
	}

	private byte[] fetchBytes(String url, int maximumBytes) throws IOException, InterruptedException {
		URI uri = URI.create(url);
		for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
			requireProjectUri(uri);
			HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(60))
				.header("User-Agent", "ChampionsCode/" + currentVersion() + " (mod self-update)")
				.GET().build();
			HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			int status = response.statusCode();
			if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
				try (InputStream ignored = response.body()) {
					String location = response.headers().firstValue("location")
						.orElseThrow(() -> new IOException("Redirect from GitLab had no Location header"));
					uri = uri.resolve(location);
					continue;
				}
			}
			if (status != 200) {
				try (InputStream ignored = response.body()) {
					throw new IOException("HTTP " + status + " from " + HOST);
				}
			}
			long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
			if (declared > maximumBytes) throw new IOException("Update response exceeds " + maximumBytes + " bytes");
			try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[16 * 1024];
				int total = 0;
				for (int read; (read = input.read(buffer)) >= 0;) {
					total += read;
					if (total > maximumBytes) throw new IOException("Update response exceeds " + maximumBytes + " bytes");
					output.write(buffer, 0, read);
				}
				return output.toByteArray();
			}
		}
		throw new IOException("Too many GitLab redirects");
	}

	private String fetchText(String url) throws IOException, InterruptedException {
		return new String(fetchBytes(url, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
	}

	private static void requireProjectUri(URI uri) {
		String path = uri.getPath();
		String rawPath = uri.getRawPath();
		boolean projectPath = path != null && (path.startsWith(PROJECT_PATH) || path.startsWith(PROJECT_API_PREFIX));
		boolean encodedApiPath = rawPath != null && rawPath.startsWith("/api/v4/projects/international-duck-incorporated%2FChampionsCode/");
		if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost())
			|| (!projectPath && !encodedApiPath)) {
			throw new IllegalArgumentException("Refusing an update URL outside the ChampionsCode GitLab project: " + uri);
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
