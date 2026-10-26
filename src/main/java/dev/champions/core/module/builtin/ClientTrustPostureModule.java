package dev.champions.core.module.builtin;

import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the session tells you about how much the server checks.
 *
 * <p>Every fact here was already delivered to the client before this module ran:
 * whether the server authenticates accounts, what platform it says it is, which
 * resource pack policy it set, and whether it described its own protocol. None of
 * it proves the server is vulnerable to anything. All of it changes what a
 * finding elsewhere means -- an identity claim on an offline-mode server is worth
 * less than the same claim on an authenticated one, and a suppressed brand means
 * the platform fingerprints the rest of this workspace uses are guesses.</p>
 *
 * <p>Nothing is sent and nothing is executed.</p>
 */
public final class ClientTrustPostureModule implements ResearchModule {

	@Override public String id() { return "auxiliary/audit/client_trust_posture"; }

	@Override public String name() { return "Session trust posture"; }

	@Override public String description() {
		return "Summarises the authentication, platform, protocol and resource-pack facts the server already "
			+ "told this client, and what each one does and does not establish.";
	}

	@Override
	public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase")
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		List<Finding> findings = new ArrayList<>();
		List<EvidenceNode> evidence = new ArrayList<>();

		if (context.integratedServer()) {
			findings.add(new Finding(Finding.Severity.INFO, "Singleplayer session",
				"This is an integrated server; authentication, branding and resource-pack facts describe your own "
					+ "machine rather than a remote operator's.",
				"Connect to the authorized target to audit its posture. Nothing was sent."));
			return new ModuleResult("Singleplayer: trust posture does not apply", findings,
				List.of(EvidenceNode.observed("Session", "integrated", "Facts describe the local server.")));
		}

		if (!context.connected()) {
			return new ModuleResult("No connection: there is no session to describe",
				List.of(new Finding(Finding.Severity.INFO, "Not connected",
					"No session facts exist until a connection is made.",
					"Connect to the authorized target first; nothing was sent.")));
		}

		// 1. Authentication.
		if (!context.onlineMode()) {
			findings.add(new Finding(Finding.Severity.HIGH, "The server does not authenticate accounts",
				"Online mode is off. Any client may claim any username, including one belonging to staff, and "
					+ "anything gated on a username rather than a permission node can be reached by claiming it.",
				"Treat every identity-based claim on this server as a claim about a name, not a person. If you run "
					+ "this server, put it behind a proxy that authenticates and forwards identities."));
		} else {
			findings.add(new Finding(Finding.Severity.PASS, "Accounts are authenticated",
				"Mojang session authentication is enforced, so a username maps to an account.",
				"Authenticated identity says nothing about permissions; that is a separate check."));
		}

		// 2. Platform fingerprint.
		String brand = context.serverBrand() == null ? "" : context.serverBrand().strip();
		String brandLower = brand.toLowerCase(Locale.ROOT);
		boolean knownPlatform = brandLower.matches("(vanilla|paper|purpur|spigot|bukkit|folia|fabric|forge|neoforge|quilt|pufferfish|airplane|tuinity).*");
		if (brand.isBlank() || brand.equalsIgnoreCase("unknown")) {
			findings.add(new Finding(Finding.Severity.INFO, "Platform not advertised",
				"The server sent no brand string. That is normal behind a proxy, and some servers suppress it "
					+ "deliberately.",
				"Plugin-family findings elsewhere in this workspace stay leads until the platform is confirmed from "
					+ "the console or a version banner."));
		} else {
			findings.add(new Finding(knownPlatform ? Finding.Severity.INFO : Finding.Severity.LOW,
				"Platform advertised: " + safe(brand, 40),
				"Version " + safe(context.advertisedVersion(), 40) + ", protocol " + context.advertisedProtocol() + ".",
				knownPlatform
					? "Match the version against upstream release notes before trusting any version-dependent finding."
					: "The brand is not one of the mainstream platforms; check whether a fork carries its own "
						+ "permission or command changes before relying on upstream defaults."));
		}

		// 3. Protocol self-description.
		if (context.advertisedProtocol() <= 0) {
			findings.add(new Finding(Finding.Severity.INFO, "Protocol not described in the server list",
				"The server list entry carried no protocol number, which usually means a proxy answers it.",
				"Protocol-dependent conclusions belong to the backend, not to the entry you connected through."));
		}

		// 4. Resource pack policy.
		String pack = context.resourcePackPolicy() == null ? "" : context.resourcePackPolicy().strip().toLowerCase(Locale.ROOT);
		if (pack.contains("prompt")) {
			findings.add(new Finding(Finding.Severity.LOW, "Resource pack is optional",
				"The server asks before sending its pack, so a client can decline it.",
				"Where the server relies on the pack for custom items, models or sounds, a declined pack is a "
					+ "client-side difference the server may not handle. Confirm how it behaves before reporting "
					+ "anything seen with the pack off."));
		} else if (pack.contains("disabled")) {
			findings.add(new Finding(Finding.Severity.LOW, "Resource pack declined on this client",
				"This client is not running the server's pack.",
				"Re-enable it before drawing conclusions from anything visual."));
		} else if (!pack.isBlank()) {
			findings.add(new Finding(Finding.Severity.PASS, "Resource pack policy: " + safe(pack, 24),
				"No action implied.",
				"Pack contents are not inspected by this module."));
		}

		evidence.add(EvidenceNode.observed("Online mode", Boolean.toString(context.onlineMode()),
			"Reported by the session this client opened."));
		evidence.add(EvidenceNode.advertised("Brand", brand.isBlank() ? "none" : safe(brand, 40),
			"A string the server chose to send."));
		evidence.add(EvidenceNode.advertised("Protocol", Integer.toString(context.advertisedProtocol()),
			"Only the server-list entry, which a proxy may answer."));
		evidence.add(EvidenceNode.observed("Commands sent", "none", "This module reads session facts only."));

		long high = findings.stream().filter(finding -> finding.severity() == Finding.Severity.HIGH).count();
		return new ModuleResult("Trust posture: " + (high > 0
			? "authentication is not enforced, which changes what every other finding means"
			: "authenticated session with no posture defect in the facts the server shared"), findings, evidence);
	}

	private static String safe(String value, int max) {
		String cleaned = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
		return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
	}
}
