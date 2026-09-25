package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.module.active.ActiveResearchModule;
import dev.champions.core.module.net.ServerListPing;
import dev.champions.core.module.net.ServerStatusParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Audits what any unauthenticated server-list pinger can observe about the
 * currently authorized target without joining: protocol, MOTD, player counts
 * and sample, chat-security flags, and mod-platform disclosure. It opens one
 * standard status connection to the connected, allowlisted server only - no
 * ranges, no sweeps, no third-party hosts, exactly mirroring the server-list
 * entry every client already performs.
 */
public final class ServerPingModule implements ActiveResearchModule {
	/** Seam so the module logic can be tested without sockets. */
	interface Pinger {
		ServerListPing.Reply contact(String host, int port, int timeoutMillis) throws Exception;
	}

	private final Pinger pinger;

	public ServerPingModule() {
		this(ServerListPing::contact);
	}

	ServerPingModule(Pinger pinger) {
		this.pinger = pinger;
	}

	@Override public String id() { return "auxiliary/scanner/server_ping"; }
	@Override public String name() { return "Authorized target status-ping audit"; }
	@Override public String description() {
		return "Opens one standard server-list status connection to the currently authorized target and reports "
			+ "exactly what an unauthenticated pinger sees without joining. Only the connected, allowlisted "
			+ "server is contacted; no ranges or third-party hosts.";
	}

	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Must match the currently connected, allowlisted server"),
			new ModuleOption("TIMEOUT", "4", true, false, "Seconds to wait for the status endpoint (1-10)"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase: I_HAVE_AUTHORIZATION")
		);
	}

	@Override public boolean performsActiveAction(Map<String, String> options) {
		return true; // contacts the authorized server outside the game session
	}

	@Override
	public CompletionStage<ModuleResult> runActive(ModuleContext context, Map<String, String> options, ActiveModuleRuntime runtime) {
		if (context.integratedServer() || "singleplayer".equalsIgnoreCase(context.serverAddress())) {
			return completed(integratedResult());
		}
		ServerListPing.EndPoint endPoint = ServerListPing.parseAddress(context.serverAddress());
		if (endPoint == null) {
			return completed(new ModuleResult("Status audit not applicable", List.of(
				new Finding(Finding.Severity.INFO, "No remote target", "This session has no remote server address to audit.",
					"Connect to the server you want to audit, then rerun this module."))));
		}
		int timeout = parseTimeout(options.getOrDefault("TIMEOUT", "4"));
		if (timeout < 1) {
			return completed(new ModuleResult("Invalid timeout", List.of(
				new Finding(Finding.Severity.INFO, "Invalid TIMEOUT", "TIMEOUT must be 1-10 seconds.",
					"Leave TIMEOUT at the default of 4 seconds."))));
		}
		String display = endPoint.host() + ":" + endPoint.port();
		return CompletableFuture.supplyAsync(() -> {
			try {
				ServerListPing.Reply reply = pinger.contact(endPoint.host(), endPoint.port(), timeout * 1000);
				ServerStatusParser.ParsedStatus status = ServerStatusParser.parse(reply.json());
				return mapFindings(display, context, status, reply.rttMillis());
			} catch (Exception failure) {
				return new ModuleResult("Status endpoint of " + display + " unreachable", List.of(
					new Finding(Finding.Severity.INFO, "Unreachable",
						"The status endpoint did not answer within " + timeout + "s (" + failure.getMessage() + "). "
							+ "Server-list sites and scanners would record the same absence.",
						"If the address is correct, an offline server or a filtered status port is a deliberate posture, not an error.")));
			}
		});
	}

	private ModuleResult mapFindings(String display, ModuleContext context, ServerStatusParser.ParsedStatus status, long rttMillis) {
		List<Finding> out = new ArrayList<>();
		out.add(new Finding(Finding.Severity.INFO, "Reachability",
			"Status endpoint answered in " + rttMillis + " ms.",
			"Rate-limit or firewall status connections if this metadata exposure matters to you; scanners see it too."));
		out.add(new Finding(Finding.Severity.INFO, "Advertised identity",
			status.versionName() + " (protocol " + status.protocol() + ").",
			"This is what server-list scanners record. Keep it accurate for support; do not treat it as a secret."));
		if (context.advertisedProtocol() > 0 && status.protocol() > 0 && context.advertisedProtocol() != status.protocol()) {
			out.add(new Finding(Finding.Severity.LOW, "Protocol mismatch",
				"In-session handshake protocol " + context.advertisedProtocol() + " differs from status protocol " + status.protocol() + ".",
				"A translating proxy (for example a Velocity or Geyser front) usually explains this; confirm which layer owns the version."));
		}
		if (!status.motd().isEmpty()) {
			out.add(new Finding(Finding.Severity.INFO, "MOTD", truncate(status.motd(), 200),
				"The MOTD is public branding surface only; never parse it for security decisions."));
		}
		if (status.playersOnline() >= 0 || status.playersMax() >= 0) {
			out.add(new Finding(Finding.Severity.INFO, "Players",
				status.playersOnline() + " online / " + status.playersMax() + " max (unauthenticated view).",
				"Correlate with server-side counts; the status view can be spoofed by proxies."));
		}
		if (!status.sample().isEmpty()) {
			out.add(new Finding(Finding.Severity.LOW, "Player sample disclosure",
				"Sample exposes " + status.sample().size() + " name(s) to any pinger: " + String.join(", ", status.sample()),
				"Disable the player sample in server configuration if exposing these names is outside your threat model."));
		}
		if (status.enforcesSecureChat() != null || status.preventsChatReports() != null) {
			String detail = "enforcesSecureChat=" + status.enforcesSecureChat()
				+ ", preventsChatReports=" + status.preventsChatReports() + ".";
			out.add(new Finding(Finding.Severity.INFO, "Chat security flags", detail,
				"These flags describe the server's chat-signing posture as advertised to every client."));
		}
		if (status.favicon()) {
			out.add(new Finding(Finding.Severity.INFO, "Favicon", "A custom favicon is served to unauthenticated pingers.",
				"Branding surface only; no action needed."));
		}
		if (status.modPlatform() != null) {
			out.add(new Finding(Finding.Severity.LOW, "Mod-platform disclosure",
				"The status reply discloses a modded platform (" + status.modPlatform() + ") with "
					+ status.mods().size() + " visible mod id(s): " + String.join(", ", status.mods()),
				"Mod identifiers help attackers pick exploits. Expose the mod list only where it is required for client compatibility."));
		}
		return new ModuleResult("Status surface of " + display + " captured (" + rttMillis + " ms)", out);
	}

	private static ModuleResult integratedResult() {
		return new ModuleResult("Status audit not applicable", List.of(
			new Finding(Finding.Severity.INFO, "Integrated session",
				"The current session is singleplayer or memory-connected; there is no remote status endpoint.",
				"Connect to the remote server you want to audit, then rerun this module.")));
	}

	private static int parseTimeout(String raw) {
		if (raw == null || raw.isBlank()) return 4;
		try {
			int value = Integer.parseInt(raw.strip());
			return value >= 1 && value <= 10 ? value : -1;
		} catch (NumberFormatException invalid) {
			return -1;
		}
	}

	private static String truncate(String value, int limit) {
		return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
	}

	private static CompletionStage<ModuleResult> completed(ModuleResult result) {
		return CompletableFuture.completedFuture(result);
	}
}
