package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.net.MojangProfileClient;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerIdentityModuleTest {
	private static final String NOTCH_ID = "069a79f444e94726a5befca90e38aaf5";

	private static ModuleContext session() {
		return new ModuleContext(true, false, false, true, "play.example.com", "vanilla", "26.3", 771,
			42L, 7, "enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
	}

	private static Map<String, String> options(String username, String uuid) {
		Map<String, String> options = new HashMap<>();
		options.put("USERNAME", username);
		options.put("UUID", uuid);
		return options;
	}

	private static MojangProfileClient.Gateway resolvedGateway() {
		return url -> {
			if (url.endsWith("/users/profiles/minecraft/Notch")) {
				return new MojangProfileClient.GatewayReply(200, "{\"id\": \"" + NOTCH_ID + "\", \"name\": \"Notch\"}");
			}
			if (url.endsWith("/session/minecraft/profile/" + NOTCH_ID)) {
				String payload = "{\"profileName\": \"Notch\", \"textures\": {\"SKIN\": "
					+ "{\"url\": \"https://textures.minecraft.net/texture/s\", \"metadata\": {\"model\": \"slim\"}}}}";
				String body = "{\"id\": \"" + NOTCH_ID + "\", \"name\": \"Notch\", \"properties\": "
					+ "[{\"name\": \"textures\", \"value\": \"" + Base64.getEncoder()
						.encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\"}]}";
				return new MojangProfileClient.GatewayReply(200, body);
			}
			return new MojangProfileClient.GatewayReply(404, "");
		};
	}

	private static List<String> details(ModuleResult result) {
		return result.findings().stream().map(Finding::detail).toList();
	}

	@Test void resolvesAUsernameToIdentityAndTextures() {
		PlayerIdentityModule module = new PlayerIdentityModule(resolvedGateway());
		ModuleResult result = module.runActive(session(), options("Notch", ""), null).toCompletableFuture().join();
		assertEquals("Mojang profile resolved for Notch", result.summary());
		assertTrue(details(result).stream().anyMatch(d -> d.contains("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
		assertTrue(details(result).stream().anyMatch(d -> d.contains("slim model")));
	}

	@Test void resolvesAUuidDirectly() {
		PlayerIdentityModule module = new PlayerIdentityModule(resolvedGateway());
		ModuleResult result = module.runActive(session(), options("", "069a79f4-44e9-4726-a5be-fca90e38aaf5"), null)
			.toCompletableFuture().join();
		assertEquals("Mojang profile resolved for Notch", result.summary());
	}

	@Test void routesAUuidPastedIntoTheUsernameField() {
		// Regression: the ClickGUI player dropdown once inserted UUIDs into the
		// USERNAME field; pasted UUIDs must resolve instead of failing validation.
		PlayerIdentityModule module = new PlayerIdentityModule(resolvedGateway());
		ModuleResult dashed = module.runActive(session(), options("069a79f4-44e9-4726-a5be-fca90e38aaf5", ""), null)
			.toCompletableFuture().join();
		assertEquals("Mojang profile resolved for Notch", dashed.summary());
		ModuleResult compact = module.runActive(session(), options(NOTCH_ID, ""), null)
			.toCompletableFuture().join();
		assertEquals("Mojang profile resolved for Notch", compact.summary());
	}

	@Test void routesAUsernamePastedIntoTheUuidField() {
		PlayerIdentityModule module = new PlayerIdentityModule(resolvedGateway());
		ModuleResult result = module.runActive(session(), options("", "Notch"), null)
			.toCompletableFuture().join();
		assertEquals("Mojang profile resolved for Notch", result.summary());
	}

	@Test void reportsMissingProfilesWithoutError() {
		PlayerIdentityModule module = new PlayerIdentityModule(url -> new MojangProfileClient.GatewayReply(204, ""));
		ModuleResult result = module.runActive(session(), options("Ghost", ""), null).toCompletableFuture().join();
		assertEquals("No Mojang profile found", result.summary());
		assertTrue(details(result).stream().anyMatch(d -> d.contains("no premium account")));
	}

	@Test void reportsRateLimitsAsLowSeverity() {
		PlayerIdentityModule module = new PlayerIdentityModule(url -> new MojangProfileClient.GatewayReply(429, ""));
		ModuleResult result = module.runActive(session(), options("Notch", ""), null).toCompletableFuture().join();
		assertEquals("Mojang API rate limit reached", result.summary());
		assertEquals(Finding.Severity.LOW, result.findings().getFirst().severity());
	}

	@Test void validatesInputsBeforeAnyRequest() {
		PlayerIdentityModule module = new PlayerIdentityModule(url -> {
			throw new IllegalStateException("gateway must not be contacted");
		});
		assertEquals("Lookup did not complete",
			module.runActive(session(), options("Notch", NOTCH_ID), null).toCompletableFuture().join().summary());
		assertEquals("Lookup did not complete",
			module.runActive(session(), options("", ""), null).toCompletableFuture().join().summary());
		assertEquals("Lookup did not complete",
			module.runActive(session(), options("invalid name!", ""), null).toCompletableFuture().join().summary());
		assertEquals("Lookup did not complete",
			module.runActive(session(), options("", "not a uuid!"), null).toCompletableFuture().join().summary());
	}

	@Test void isNotAuthorizationGatedAndSendsNothingToTheSession() {
		PlayerIdentityModule module = new PlayerIdentityModule();
		assertFalse(module.requiresAuthorization());
		assertFalse(module.performsActiveAction(Map.of()));
		assertEquals("auxiliary/lookup/player_identity", module.id());
	}
}
