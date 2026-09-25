package dev.champions.core.module.net;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class MojangProfileClientTest {
	private static final String NOTCH_ID = "069a79f444e94726a5befca90e38aaf5";

	private static String texturesPayload(String profileJson) {
		return Base64.getEncoder().encodeToString(profileJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static String sessionBody(String texturesValue) {
		return "{\"id\": \"" + NOTCH_ID + "\", \"name\": \"Notch\", \"properties\": ["
			+ "{\"name\": \"textures\", \"value\": \"" + texturesValue + "\"}]}";
	}

	@Test void normalizesUuids() {
		assertEquals(NOTCH_ID, MojangProfileClient.normalizeUuid("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
		assertEquals(NOTCH_ID, MojangProfileClient.normalizeUuid(NOTCH_ID.toUpperCase()));
		assertNull(MojangProfileClient.normalizeUuid("069a79f4-44e9-4726-a5be-fca90e38aaf")); // 31 hex
		assertNull(MojangProfileClient.normalizeUuid("not-a-uuid"));
		assertNull(MojangProfileClient.normalizeUuid(null));
	}

	@Test void dashesCompactUuids() {
		assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", MojangProfileClient.dashed(NOTCH_ID));
		assertThrows(IllegalArgumentException.class, () -> MojangProfileClient.dashed("xyz"));
	}

	@Test void validatesUsernames() {
		assertTrue(MojangProfileClient.validUsername("Notch"));
		assertTrue(MojangProfileClient.validUsername("x"));
		assertTrue(MojangProfileClient.validUsername("Player_123"));
		assertFalse(MojangProfileClient.validUsername("has space"));
		assertFalse(MojangProfileClient.validUsername("0123456789abcdefg")); // 17 chars
		assertFalse(MojangProfileClient.validUsername(null));
	}

	@Test void parsesProfileWithSlimSkinAndCape() {
		String payload = "{\"profileId\": \"" + NOTCH_ID + "\", \"profileName\": \"Notch\", \"textures\": {"
			+ "\"SKIN\": {\"url\": \"https://textures.minecraft.net/texture/skin\", \"metadata\": {\"model\": \"slim\"}},"
			+ "\"CAPE\": {\"url\": \"https://textures.minecraft.net/texture/cape\"}}}";
		MojangProfileClient.Identity identity = MojangProfileClient.parseUuidProfile(sessionBody(texturesPayload(payload)));
		assertEquals("Notch", identity.name());
		assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", identity.dashedUuid());
		assertEquals("https://textures.minecraft.net/texture/skin", identity.skinUrl());
		assertEquals("slim", identity.skinModel());
		assertEquals("https://textures.minecraft.net/texture/cape", identity.capeUrl());
	}

	@Test void parsesProfileWithoutTextures() {
		String body = "{\"id\": \"" + NOTCH_ID + "\", \"name\": \"Notch\", \"properties\": []}";
		MojangProfileClient.Identity identity = MojangProfileClient.parseUuidProfile(body);
		assertNull(identity.skinUrl());
		assertEquals("classic", identity.skinModel());
		assertNull(identity.capeUrl());
	}

	@Test void resolvesByNameThroughBothEndpoints() {
		MojangProfileClient.Gateway gateway = url -> {
			if (url.endsWith("/users/profiles/minecraft/Notch")) {
				return new MojangProfileClient.GatewayReply(200, "{\"id\": \"" + NOTCH_ID + "\", \"name\": \"Notch\"}");
			}
			if (url.endsWith("/session/minecraft/profile/" + NOTCH_ID)) {
				String payload = "{\"profileId\": \"" + NOTCH_ID + "\", \"profileName\": \"Notch\", "
					+ "\"textures\": {\"SKIN\": {\"url\": \"https://textures.minecraft.net/texture/s\"}}}";
				return new MojangProfileClient.GatewayReply(200, sessionBody(texturesPayload(payload)));
			}
			return new MojangProfileClient.GatewayReply(404, "");
		};
		MojangProfileClient.LookupResult result = MojangProfileClient.byName(gateway, "Notch");
		assertEquals(MojangProfileClient.Outcome.FOUND, result.outcome());
		assertEquals("Notch", result.identity().name());
		assertEquals("classic", result.identity().skinModel());
	}

	@Test void reportsMissingAndRateLimitedNames() {
		MojangProfileClient.Gateway missing = url -> new MojangProfileClient.GatewayReply(204, "");
		assertEquals(MojangProfileClient.Outcome.NOT_FOUND, MojangProfileClient.byName(missing, "Ghost").outcome());

		MojangProfileClient.Gateway throttled = url -> new MojangProfileClient.GatewayReply(429, "");
		assertEquals(MojangProfileClient.Outcome.RATE_LIMITED, MojangProfileClient.byName(throttled, "Notch").outcome());
	}

	@Test void resolvesByUuidDirectly() {
		MojangProfileClient.Gateway gateway = url -> new MojangProfileClient.GatewayReply(404, "");
		assertEquals(MojangProfileClient.Outcome.NOT_FOUND, MojangProfileClient.byUuid(gateway, NOTCH_ID).outcome());
		assertEquals(MojangProfileClient.Outcome.ERROR, MojangProfileClient.byUuid(gateway, "bad-uuid").outcome());
	}
}
