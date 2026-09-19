package dev.champions.core.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Exact-target authorization gate. No wildcard hosts and no active network lookups. */
public final class AuthorizationPolicy {
	public static final String CONFIRMATION_PHRASE = "I_HAVE_AUTHORIZATION";
	private final Path allowlistFile;
	private volatile Set<String> allowedTargets = Set.of("localhost", "127.0.0.1", "::1", "singleplayer");

	public AuthorizationPolicy(Path configDirectory) {
		this.allowlistFile = configDirectory.resolve("authorized-targets.txt");
		initialize();
	}

	public synchronized void reload() throws IOException {
		Set<String> next = new LinkedHashSet<>();
		for (String raw : Files.readAllLines(allowlistFile, StandardCharsets.UTF_8)) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) continue;
			String host = normalizeHost(line);
			if (host.equals("*") || host.startsWith("*.")) {
				throw new IOException("Wildcards are intentionally unsupported: " + line);
			}
			if (!host.isBlank()) next.add(host);
		}
		allowedTargets = Set.copyOf(next);
	}

	public AuthorizationDecision authorize(boolean connected, String currentAddress, String requestedTarget, String confirmation) {
		if (!connected) return AuthorizationDecision.denied("No active client connection.");
		if (!CONFIRMATION_PHRASE.equals(confirmation)) {
			return AuthorizationDecision.denied("Set CONFIRM to " + CONFIRMATION_PHRASE + " for this one run.");
		}
		String current = normalizeHost(currentAddress);
		String requested = requestedTarget == null || requestedTarget.isBlank() || requestedTarget.equalsIgnoreCase("CURRENT")
			? current : normalizeHost(requestedTarget);
		if (current.isBlank() || "unknown".equals(current)) {
			return AuthorizationDecision.denied("The current server address is unavailable.");
		}
		if (!current.equals(requested)) {
			return AuthorizationDecision.denied("RHOST must be CURRENT or exactly match the connected server. No third-party probing is supported.");
		}
		if (!allowedTargets.contains(current)) {
			return AuthorizationDecision.denied("Target is not allowlisted. Use authorize current or ClickGUI.");
		}
		return AuthorizationDecision.allowed(current);
	}

	public synchronized boolean addTarget(String address) throws IOException {
		String host = requireExactHost(address);
		Set<String> next = new LinkedHashSet<>(allowedTargets);
		boolean changed = next.add(host);
		if (changed) persist(next);
		return changed;
	}

	public synchronized boolean removeTarget(String address) throws IOException {
		String host = requireExactHost(address);
		Set<String> next = new LinkedHashSet<>(allowedTargets);
		boolean changed = next.remove(host);
		if (changed) persist(next);
		return changed;
	}

	public boolean isAllowed(String address) {
		return allowedTargets.contains(normalizeHost(address));
	}

	public Set<String> allowedTargets() { return allowedTargets; }
	public Path allowlistFile() { return allowlistFile; }

	public static String normalizeHost(String address) {
		if (address == null) return "";
		String value = address.strip().toLowerCase(Locale.ROOT);
		if (value.equals("singleplayer")) return value;
		if (value.startsWith("[")) {
			int close = value.indexOf(']');
			if (close > 0) value = value.substring(1, close);
		} else {
			int firstColon = value.indexOf(':');
			int lastColon = value.lastIndexOf(':');
			if (firstColon > 0 && firstColon == lastColon) value = value.substring(0, firstColon);
		}
		while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
		return value;
	}

	private String requireExactHost(String address) {
		String host = normalizeHost(address);
		if (host.isBlank() || host.equals("unknown")) throw new IllegalArgumentException("Current server address is unavailable.");
		if (host.equals("*") || host.startsWith("*.")) throw new IllegalArgumentException("Wildcard targets are unsupported.");
		return host;
	}

	private void persist(Set<String> targets) throws IOException {
		List<String> sorted = targets.stream().sorted().toList();
		StringBuilder content = new StringBuilder("""
			# Champions Mod exact target allowlist.
			# Managed in game with authorize current and deauthorize current.
			# Only authorize systems you own or have explicit permission to assess.
			""");
		for (String target : sorted) content.append(target).append('\n');
		Path temporary = allowlistFile.resolveSibling(allowlistFile.getFileName() + ".tmp");
		Files.writeString(temporary, content, StandardCharsets.UTF_8);
		try {
			Files.move(temporary, allowlistFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
			Files.move(temporary, allowlistFile, StandardCopyOption.REPLACE_EXISTING);
		}
		allowedTargets = Set.copyOf(targets);
	}

	private void initialize() {
		try {
			Files.createDirectories(allowlistFile.getParent());
			if (Files.notExists(allowlistFile)) {
				Files.writeString(allowlistFile, """
					# Champions Mod exact target allowlist.
					# Managed in game with authorize current and deauthorize current.
					# Only authorize systems you own or have explicit permission to assess.
					localhost
					127.0.0.1
					::1
					singleplayer
					""", StandardCharsets.UTF_8);
			}
			reload();
		} catch (IOException e) {
			throw new IllegalStateException("Could not initialize authorization policy", e);
		}
	}

	public record AuthorizationDecision(boolean allowed, String normalizedTarget, String reason) {
		public static AuthorizationDecision allowed(String target) { return new AuthorizationDecision(true, target, "Authorized"); }
		public static AuthorizationDecision denied(String reason) { return new AuthorizationDecision(false, "", reason); }
	}
}
