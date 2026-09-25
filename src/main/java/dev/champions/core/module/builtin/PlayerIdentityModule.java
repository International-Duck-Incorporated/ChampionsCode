package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.module.active.ActiveResearchModule;
import dev.champions.core.module.net.MojangProfileClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Resolves one username or UUID against Mojang's public profile endpoints and
 * reports the current name, dashed UUID, and skin/cape textures. Like the
 * update check, this talks only to a public API (api.mojang.com and the
 * session server), never to the connected Minecraft server, and uses no
 * session credentials - so it is not part of the authorization-gated session
 * target path.
 */
public final class PlayerIdentityModule implements ActiveResearchModule {
	private final MojangProfileClient.Gateway gateway;

	public PlayerIdentityModule() {
		this(MojangProfileClient.HTTP);
	}

	PlayerIdentityModule(MojangProfileClient.Gateway gateway) {
		this.gateway = gateway;
	}

	@Override public String id() { return "auxiliary/lookup/player_identity"; }
	@Override public String name() { return "Mojang player identity lookup"; }
	@Override public String description() {
		return "Resolves one username or UUID via Mojang's public profile API: current name, dashed UUID, and "
			+ "skin/cape textures. Contacts only Mojang endpoints, never the connected server.";
	}

	@Override public boolean requiresAuthorization() { return false; }

	@Override public boolean performsActiveAction(Map<String, String> options) {
		return false; // nothing is sent to the connected server
	}

	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("USERNAME", "", false, false, "Player name to resolve (a pasted UUID is detected and routed automatically)"),
			new ModuleOption("UUID", "", false, false, "Player UUID to resolve, dashed or compact (a pasted name is detected too)")
		);
	}

	@Override
	public CompletionStage<ModuleResult> runActive(ModuleContext context, Map<String, String> options, ActiveModuleRuntime runtime) {
		String username = options.getOrDefault("USERNAME", "").strip();
		String uuid = options.getOrDefault("UUID", "").strip();
		// Accept a UUID typed into USERNAME (and a username typed into UUID):
		// players are used to pasting whichever they have, so route by shape.
		if (uuid.isEmpty() && !username.isEmpty() && !MojangProfileClient.validUsername(username)
			&& MojangProfileClient.normalizeUuid(username) != null) {
			uuid = username;
			username = "";
		} else if (username.isEmpty() && !uuid.isEmpty() && MojangProfileClient.normalizeUuid(uuid) == null
			&& MojangProfileClient.validUsername(uuid)) {
			username = uuid;
			uuid = "";
		}
		if (!username.isEmpty() && !uuid.isEmpty()) {
			return completed(error("Set exactly one of USERNAME or UUID, not both.",
				"Clear the option you do not need and rerun."));
		}
		if (username.isEmpty() && uuid.isEmpty()) {
			return completed(error("Set USERNAME or UUID before running.",
				"Example: set USERNAME Notch, then run."));
		}
		if (!username.isEmpty() && !MojangProfileClient.validUsername(username)) {
			return completed(error("'" + username + "' is not a valid Minecraft username.",
				"Usernames are 1-16 characters of letters, digits, and underscores; paste a UUID into UUID to resolve an account by id."));
		}
		if (!uuid.isEmpty() && MojangProfileClient.normalizeUuid(uuid) == null) {
			return completed(error("'" + uuid + "' is not a valid UUID.",
				"Provide a 32-digit hex UUID, dashed or compact."));
		}
		final String name = username;
		final String id = uuid;
		return CompletableFuture.supplyAsync(() -> {
			MojangProfileClient.LookupResult result = name.isEmpty()
				? MojangProfileClient.byUuid(gateway, id)
				: MojangProfileClient.byName(gateway, name);
			return switch (result.outcome()) {
				case FOUND -> found(result.identity());
				case NOT_FOUND -> new ModuleResult("No Mojang profile found", List.of(
					new Finding(Finding.Severity.INFO, "No profile", result.detail() + ".",
						"The name may be unregistered, migrated, or non-premium; verify in the official launcher before acting.")));
				case RATE_LIMITED -> new ModuleResult("Mojang API rate limit reached", List.of(
					new Finding(Finding.Severity.LOW, "Rate limited", "Mojang's public API is throttling requests.",
						"Wait about a minute and rerun; public lookups are intentionally limited.")));
				case ERROR -> error(result.detail(), "Check the input, wait, and rerun; the detail above is the API's response.");
			};
		});
	}

	private static ModuleResult found(MojangProfileClient.Identity identity) {
		List<Finding> out = new ArrayList<>();
		out.add(new Finding(Finding.Severity.INFO, "Identity",
			identity.name() + " - " + identity.dashedUuid(),
			"Public Mojang data; treat as OSINT and verify in-game before any moderation decision."));
		out.add(new Finding(Finding.Severity.INFO, "Skin",
			(identity.skinUrl() == null ? "No skin texture published."
				: identity.skinModel() + " model - " + identity.skinUrl()),
			"Texture URLs are public CDN links; they do not authenticate anyone."));
		if (identity.capeUrl() != null) {
			out.add(new Finding(Finding.Severity.INFO, "Cape", identity.capeUrl(),
				"Capes often identify map or event participation; public information only."));
		}
		return new ModuleResult("Mojang profile resolved for " + identity.name(), out);
	}

	private static ModuleResult error(String detail, String recommendation) {
		return new ModuleResult("Lookup did not complete", List.of(
			new Finding(Finding.Severity.INFO, "Lookup refused", detail, recommendation)));
	}

	private static CompletionStage<ModuleResult> completed(ModuleResult result) {
		return CompletableFuture.completedFuture(result);
	}
}
