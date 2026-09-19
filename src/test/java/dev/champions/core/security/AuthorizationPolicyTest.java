package dev.champions.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationPolicyTest {
	@TempDir Path temp;

	@Test void requiresConnectionAllowlistMatchAndOneRunPhrase() throws Exception {
		AuthorizationPolicy policy = new AuthorizationPolicy(temp);
		AuthorizationPolicy.AuthorizationDecision denied = policy.authorize(
			true, "example.org:25565", "CURRENT", AuthorizationPolicy.CONFIRMATION_PHRASE);
		assertFalse(denied.allowed());
		assertEquals("Target is not allowlisted. Use authorize current or ClickGUI.", denied.reason());
		assertFalse(denied.reason().contains(temp.toString()));
		Files.writeString(policy.allowlistFile(), "example.org\n");
		policy.reload();
		assertFalse(policy.authorize(true, "example.org:25565", "other.org", AuthorizationPolicy.CONFIRMATION_PHRASE).allowed());
		assertFalse(policy.authorize(true, "example.org:25565", "CURRENT", "nope").allowed());
		assertTrue(policy.authorize(true, "example.org:25565", "CURRENT", AuthorizationPolicy.CONFIRMATION_PHRASE).allowed());
	}

	@Test void managesExactTargetsAndPersistsThemInGame() throws Exception {
		AuthorizationPolicy policy = new AuthorizationPolicy(temp);
		assertTrue(policy.addTarget("Example.ORG:25565"));
		assertTrue(policy.isAllowed("example.org"));
		assertFalse(policy.addTarget("example.org"));

		AuthorizationPolicy reloaded = new AuthorizationPolicy(temp);
		assertTrue(reloaded.isAllowed("example.org:25565"));
		assertTrue(reloaded.removeTarget("example.org"));
		assertFalse(reloaded.isAllowed("example.org"));
		assertFalse(Files.readString(reloaded.allowlistFile()).contains("example.org"));
	}

	@Test void normalizesHostAndPortWithoutDns() {
		assertEquals("example.org", AuthorizationPolicy.normalizeHost("Example.ORG:25565"));
		assertEquals("::1", AuthorizationPolicy.normalizeHost("[::1]:25565"));
	}
}
