package dev.champions.core.repo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Reads a module repository over the GitLab API.
 *
 * <p>Only two calls are ever made, both reads: the index, and one file from it.
 * Nothing is written back and no other host is contacted. A private repository
 * is what makes this workable at all — the index is the trust anchor, and the
 * token keeps it out of public view.</p>
 */
public final class ModuleRepositoryClient {
	/** Cap on any single downloaded document; a repository index is kilobytes. */
	private static final int MAX_BYTES = 512 * 1024;

	private final RepoConfig config;
	private final HttpClient http;

	public ModuleRepositoryClient(RepoConfig config) {
		this.config = config;
		this.http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(8))
				.build();
	}

	public ModuleIndex index() throws IOException {
		if (!config.isConfigured()) throw new IOException("No module repository configured. Try: repo config <url>");
		String body = fetch("index.json");
		try {
			return ModuleIndex.parse(body);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Repository index rejected: " + invalid.getMessage(), invalid);
		}
	}

	public String source(RepoEntry entry) throws IOException {
		if (entry.file().isBlank()) throw new IOException("Entry " + entry.id() + " names no file");
		return fetch(entry.file());
	}

	/** True when {@code body} hashes to the value the index pinned for the entry. */
	public static boolean verify(RepoEntry entry, String body) {
		if (entry.sha256() == null || entry.sha256().isBlank()) return false;
		String actual = sha256(body);
		return actual.equals(entry.sha256().toLowerCase(Locale.ROOT));
	}

	public static String sha256(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
		}
	}

	private String fetch(String repositoryFile) throws IOException {
		String project = config.projectPath();
		String origin = config.origin();
		if (project.isBlank() || origin.isBlank()) {
			throw new IOException("Repository URL '" + config.url() + "' is not a GitLab project URL");
		}
		String encodedProject = URLEncoder.encode(project, StandardCharsets.UTF_8);
		String encodedFile = repositoryFile.split("/").length == 1
				? URLEncoder.encode(repositoryFile, StandardCharsets.UTF_8)
				: java.util.Arrays.stream(repositoryFile.split("/"))
						.map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8))
						.reduce((left, right) -> left + "%2F" + right).orElse("");
		String uri = origin + "/api/v4/projects/" + encodedProject + "/repository/files/"
				+ encodedFile + "/raw?ref=" + URLEncoder.encode(config.ref(), StandardCharsets.UTF_8);

		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri))
				.timeout(Duration.ofSeconds(20))
				.header("Accept", "application/json")
				.header("User-Agent", "ChampionsCode");
		if (!config.token().isBlank()) request.header("PRIVATE-TOKEN", config.token());

		HttpResponse<String> response;
		try {
			response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IOException("Repository request interrupted", interrupted);
		}
		int status = response.statusCode();
		if (status == 401 || status == 403) {
			throw new IOException("Repository refused the token (HTTP " + status
					+ "). Check that it can read the project.");
		}
		if (status == 404) {
			throw new IOException("Not found (HTTP 404): " + repositoryFile
					+ " on ref '" + config.ref() + "'. Check the URL, the ref and that the token can read it.");
		}
		if (status != 200) {
			throw new IOException("Repository returned HTTP " + status + " for " + repositoryFile);
		}
		String body = response.body();
		if (body == null) throw new IOException("Repository returned an empty body for " + repositoryFile);
		if (body.length() > MAX_BYTES) {
			throw new IOException(repositoryFile + " is larger than " + MAX_BYTES + " bytes");
		}
		return body;
	}
}
