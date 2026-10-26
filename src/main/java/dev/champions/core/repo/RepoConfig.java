package dev.champions.core.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Where the module repository lives and how to authenticate to it.
 *
 * <p>Read from {@code <config>/repo.properties} with the environment able to
 * override it, so a token can be kept out of a shared config folder:</p>
 *
 * <pre>
 * url=https://gitlab.com/group/champions-modules
 * ref=main
 * token=glpat-...
 * </pre>
 *
 * <p>The file is written owner-only where the filesystem supports it. The token
 * is never printed in full — {@link #maskedToken()} is what the console shows.</p>
 */
public final class RepoConfig {
	/**
	 * Where the owner's collection lives. It is private, so knowing the path
	 * grants nothing; a fresh install only has to supply a read token.
	 */
	public static final String DEFAULT_URL =
			"https://gitlab.com/international-duck-incorporated/archive-department/championscode-modules";

	private static final String FILE_NAME = "repo.properties";

	private final Path file;
	private String url;
	private String ref = "main";
	private String token = "";

	public RepoConfig(Path configDirectory) {
		this.file = configDirectory.resolve(FILE_NAME);
		reload();
	}

	/** Re-reads the file, then lets the environment win. */
	public void reload() {
		url = DEFAULT_URL;
		ref = "main";
		token = "";
		if (Files.isRegularFile(file)) {
			Properties properties = new Properties();
			try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException ignored) {
				// a broken file behaves like an unconfigured one
			}
			url = properties.getProperty("url", DEFAULT_URL).strip();
			ref = properties.getProperty("ref", "main").strip();
			token = properties.getProperty("token", "").strip();
		}
		url = environment("CHAMPIONS_REPO_URL", url);
		ref = environment("CHAMPIONS_REPO_REF", ref);
		token = environment("CHAMPIONS_REPO_TOKEN", token);
		if (ref.isBlank()) ref = "main";
	}

	private static String environment(String key, String fallback) {
		String value = System.getenv(key);
		return value == null || value.isBlank() ? fallback : value.strip();
	}

	public void save() throws IOException {
		Files.createDirectories(file.getParent());
		List<String> lines = new ArrayList<>();
		lines.add("# ChampionsCode module repository. Keep this file private.");
		lines.add("url=" + url);
		lines.add("ref=" + ref);
		if (!token.isBlank()) lines.add("token=" + token);
		Files.write(file, lines, StandardCharsets.UTF_8);
		restrict();
	}

	private void restrict() {
		try {
			Set<PosixFilePermission> permissions = EnumSet.of(
					PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
			Files.setPosixFilePermissions(file, permissions);
		} catch (UnsupportedOperationException | IOException ignored) {
			// Windows has no POSIX permissions; the file stays in the user's config folder.
		}
	}

	public boolean isConfigured() {
		return !url.isBlank();
	}

	public Path file() {
		return file;
	}

	public String url() {
		return url;
	}

	public void url(String value) {
		this.url = value == null ? "" : value.strip();
	}

	public String ref() {
		return ref;
	}

	public void ref(String value) {
		this.ref = value == null || value.isBlank() ? "main" : value.strip();
	}

	public String token() {
		return token;
	}

	public void token(String value) {
		this.token = value == null ? "" : value.strip();
	}

	/** First and last four characters only, for console output. */
	public String maskedToken() {
		if (token.isBlank()) return "none";
		if (token.length() <= 12) return "set";
		return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
	}

	/**
	 * {@code group/project} — the part of the URL GitLab wants escaped inside
	 * {@code /projects/:id/...}. Handles a trailing {@code .git} and slashes.
	 */
	public String projectPath() {
		String cleaned = url.strip();
		if (cleaned.endsWith(".git")) cleaned = cleaned.substring(0, cleaned.length() - 4);
		int scheme = cleaned.indexOf("://");
		if (scheme >= 0) cleaned = cleaned.substring(scheme + 3);
		int slash = cleaned.indexOf('/');
		if (slash < 0) return "";
		return cleaned.substring(slash + 1).replaceAll("^/+|/+$", "");
	}

	/** {@code scheme://host} for the same host, so api calls stay on one origin. */
	public String origin() {
		String cleaned = url.strip();
		if (cleaned.endsWith(".git")) cleaned = cleaned.substring(0, cleaned.length() - 4);
		int scheme = cleaned.indexOf("://");
		if (scheme < 0) return "";
		int slash = cleaned.indexOf('/', scheme + 3);
		return slash < 0 ? cleaned : cleaned.substring(0, slash);
	}

	public String describe() {
		if (!isConfigured()) return "not configured";
		return projectPath() + "@" + ref.toLowerCase(Locale.ROOT)
				+ (token.isBlank() ? " (no token)" : " (token " + maskedToken() + ")");
	}
}
