package dev.champions.core.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The module repositories this client pulls from, by name.
 *
 * <p>Any number can be configured, so a personal collection, a team collection
 * and a vendor's can coexist and be searched in one go. The file stays a flat
 * properties document:</p>
 *
 * <pre>
 * repo.default.url=https://gitlab.com/group/project
 * repo.default.ref=main
 * repo.default.token=glpat-...
 * </pre>
 *
 * <p>The pre-0.2.0-beta.4 single-repository keys ({@code url=}, {@code ref=},
 * {@code token=}) are still read and are folded into a repository named
 * {@code default}; saving rewrites them into the named form, so an upgrade does
 * not lose a configured token.</p>
 *
 * <p>The environment can override any repository: {@code CHAMPIONS_REPO_URL},
 * {@code CHAMPIONS_REPO_REF} and {@code CHAMPIONS_REPO_TOKEN} apply to the
 * {@code default} repository, so a token can be kept off disk entirely.</p>
 */
public final class RepoConfig {
	/**
	 * Where the owner's private collection lives. Knowing the path grants
	 * nothing, so a fresh install only has to supply a read token.
	 */
	public static final String DEFAULT_URL =
			"https://gitlab.com/international-duck-incorporated/archive-department/championscode-modules";

	public static final String DEFAULT_NAME = "default";

	private static final String FILE_NAME = "repo.properties";
	private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

	/** One configured repository. */
	public record Repo(String name, String url, String ref, String token) {
		public Repo {
			name = name == null || name.isBlank() ? DEFAULT_NAME : name.strip().toLowerCase(Locale.ROOT);
			url = url == null ? "" : url.strip();
			ref = ref == null || ref.isBlank() ? "main" : ref.strip();
			token = token == null ? "" : token.strip();
		}

		public boolean configured() {
			return !url.isBlank();
		}

		/** {@code group/project}, with any scheme, host and {@code .git} removed. */
		public String projectPath() {
			String cleaned = stripSuffix(url);
			int scheme = cleaned.indexOf("://");
			if (scheme >= 0) cleaned = cleaned.substring(scheme + 3);
			int slash = cleaned.indexOf('/');
			if (slash < 0) return "";
			return cleaned.substring(slash + 1).replaceAll("^/+|/+$", "");
		}

		/** {@code scheme://host} so API calls stay on the configured origin. */
		public String origin() {
			String cleaned = stripSuffix(url);
			int scheme = cleaned.indexOf("://");
			if (scheme < 0) return "";
			int slash = cleaned.indexOf('/', scheme + 3);
			return slash < 0 ? cleaned : cleaned.substring(0, slash);
		}

		private static String stripSuffix(String value) {
			return value.endsWith(".git") ? value.substring(0, value.length() - 4) : value;
		}

		public String maskedToken() {
			if (token.isBlank()) return "none";
			if (token.length() <= 12) return "set";
			return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
		}

		public String describe() {
			if (!configured()) return name + " (no url)";
			return name + " -> " + projectPath() + "@" + ref
					+ (token.isBlank() ? " (no token)" : " (token " + maskedToken() + ")");
		}
	}

	private final Path file;
	/** Insertion order is search order: the first repository that has a module wins. */
	private final Map<String, Repo> repos = new LinkedHashMap<>();

	public RepoConfig(Path configDirectory) {
		this.file = configDirectory.resolve(FILE_NAME);
		reload();
	}

	/** Re-reads the file, then lets the environment win. */
	public void reload() {
		repos.clear();
		Properties properties = new Properties();
		if (Files.isRegularFile(file)) {
			try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException ignored) {
				// a broken file behaves like an unconfigured one
			}
		}
		// Named form.
		List<String> names = new ArrayList<>();
		for (String key : properties.stringPropertyNames()) {
			if (!key.startsWith("repo.") || !key.endsWith(".url")) continue;
			String name = key.substring("repo.".length(), key.length() - ".url".length());
			if (!NAME.matcher(name).matches()) continue;
			names.add(name);
		}
		names.sort(String::compareTo);
		for (String name : names) {
			repos.put(name, new Repo(name,
					properties.getProperty("repo." + name + ".url", ""),
					properties.getProperty("repo." + name + ".ref", "main"),
					properties.getProperty("repo." + name + ".token", "")));
		}
		// Legacy single-repository keys.
		String legacyUrl = properties.getProperty("url", "").strip();
		if (!legacyUrl.isEmpty() && !repos.containsKey(DEFAULT_NAME)) {
			repos.put(DEFAULT_NAME, new Repo(DEFAULT_NAME, legacyUrl,
					properties.getProperty("ref", "main"),
					properties.getProperty("token", "")));
		}
		if (repos.isEmpty()) {
			repos.put(DEFAULT_NAME, new Repo(DEFAULT_NAME, DEFAULT_URL, "main", ""));
		}
		// Environment overrides, applied to the default repository only.
		Repo fallback = repos.get(DEFAULT_NAME);
		if (fallback == null) {
			fallback = new Repo(DEFAULT_NAME, DEFAULT_URL, "main", "");
			repos.put(DEFAULT_NAME, fallback);
		}
		String url = environment("CHAMPIONS_REPO_URL");
		String ref = environment("CHAMPIONS_REPO_REF");
		String token = environment("CHAMPIONS_REPO_TOKEN");
		if (url != null || ref != null || token != null) {
			repos.put(DEFAULT_NAME, new Repo(DEFAULT_NAME,
					url == null ? fallback.url() : url,
					ref == null ? fallback.ref() : ref,
					token == null ? fallback.token() : token));
		}
	}

	private static String environment(String key) {
		String value = System.getenv(key);
		return value == null || value.isBlank() ? null : value.strip();
	}

	public void save() throws IOException {
		Files.createDirectories(file.getParent());
		List<String> lines = new ArrayList<>();
		lines.add("# ChampionsCode module repositories, searched in this order.");
		lines.add("# Keep this file private: tokens below are secrets.");
		for (Repo repo : repos.values()) {
			lines.add("");
			lines.add("repo." + repo.name() + ".url=" + repo.url());
			lines.add("repo." + repo.name() + ".ref=" + repo.ref());
			if (!repo.token().isBlank()) lines.add("repo." + repo.name() + ".token=" + repo.token());
		}
		Files.write(file, lines, StandardCharsets.UTF_8);
		try {
			Set<PosixFilePermission> permissions = EnumSet.of(
					PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
			Files.setPosixFilePermissions(file, permissions);
		} catch (UnsupportedOperationException | IOException ignored) {
			// Windows has no POSIX permissions; the file stays in the user's config folder.
		}
	}

	// ---- repositories ------------------------------------------------------

	public List<Repo> repos() {
		return List.copyOf(repos.values());
	}

	public Optional<Repo> find(String name) {
		if (name == null) return Optional.empty();
		return Optional.ofNullable(repos.get(name.strip().toLowerCase(Locale.ROOT)));
	}

	/** The first configured repository, which is what an unqualified command means. */
	public Repo primary() {
		return repos.values().stream().filter(Repo::configured).findFirst()
				.orElseGet(() -> repos.values().iterator().next());
	}

	public boolean isConfigured() {
		return repos.values().stream().anyMatch(Repo::configured);
	}

	/** @throws IllegalArgumentException when the name is unusable or already taken. */
	public Repo add(String name, String url, String ref) throws IOException {
		String clean = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
		if (!NAME.matcher(clean).matches()) {
			throw new IllegalArgumentException(
					"Repository name must be lowercase letters, digits, '-' or '_' (got '" + name + "')");
		}
		if (repos.containsKey(clean)) {
			throw new IllegalArgumentException("Repository '" + clean + "' already exists. Remove it first.");
		}
		Repo repo = new Repo(clean, url, ref, "");
		repos.put(clean, repo);
		save();
		return repo;
	}

	public boolean remove(String name) throws IOException {
		if (name == null) return false;
		boolean removed = repos.remove(name.strip().toLowerCase(Locale.ROOT)) != null;
		if (removed) {
			if (repos.isEmpty()) repos.put(DEFAULT_NAME, new Repo(DEFAULT_NAME, DEFAULT_URL, "main", ""));
			save();
		}
		return removed;
	}

	public Repo setToken(String name, String token) throws IOException {
		Repo repo = find(name).orElseThrow(() ->
				new IllegalArgumentException("No repository named '" + name + "'. See: repo status"));
		Repo updated = new Repo(repo.name(), repo.url(), repo.ref(), token);
		repos.put(updated.name(), updated);
		save();
		return updated;
	}

	/** Replaces a repository's URL/ref, keeping any stored token. */
	public Repo setUrl(String name, String url, String ref) throws IOException {
		String clean = name == null || name.isBlank() ? DEFAULT_NAME : name.strip().toLowerCase(Locale.ROOT);
		Repo existing = repos.get(clean);
		Repo updated = new Repo(clean, url,
				ref == null || ref.isBlank() ? (existing == null ? "main" : existing.ref()) : ref,
				existing == null ? "" : existing.token());
		repos.put(clean, updated);
		save();
		return updated;
	}

	public Path file() {
		return file;
	}
}
