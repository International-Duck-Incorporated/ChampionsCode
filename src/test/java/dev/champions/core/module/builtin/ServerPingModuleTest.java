package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.net.ServerListPing;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerPingModuleTest {
	private static final String STATUS_JSON = """
		{
		  "version": {"name": "26.3-fork", "protocol": 770},
		  "players": {"online": 7, "max": 40, "sample": [{"name": "Alice"}, {"name": "Bob"}]},
		  "description": {"text": "§aA test ", "extra": [{"text": "server"}]},
		  "favicon": "data:image/png;base64,AA",
		  "enforcesSecureChat": true
		}
		""";

	private static ModuleContext session(String address, int protocol) {
		return new ModuleContext(true, false, false, true, address, "vanilla", "26.3", protocol,
			42L, 7, "enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
	}

	private static List<String> details(ModuleResult result) {
		return result.findings().stream().map(Finding::detail).toList();
	}

	private static List<String> titles(ModuleResult result) {
		return result.findings().stream().map(Finding::title).toList();
	}

	@Test void reportsTheUnauthenticatedStatusSurface() {
		ServerPingModule module = new ServerPingModule((host, port, timeout) -> new ServerListPing.Reply(37L, STATUS_JSON));
		ModuleResult result = module.runActive(session("play.example.com:25565", 771), Map.of(), null).toCompletableFuture().join();
		assertTrue(result.summary().contains("play.example.com:25565"));
		assertTrue(result.summary().contains("37 ms"));
		assertTrue(details(result).stream().anyMatch(d -> d.contains("answered in 37 ms")));
		assertTrue(details(result).stream().anyMatch(d -> d.contains("26.3-fork") && d.contains("770")));
		assertTrue(details(result).stream().anyMatch(d -> d.contains("7 online / 40 max")));
		assertTrue(details(result).stream().anyMatch(d -> d.contains("A test server")));
		assertTrue(titles(result).contains("Player sample disclosure"));
		assertTrue(titles(result).contains("Protocol mismatch"));
		assertTrue(details(result).stream().anyMatch(d -> d.contains("enforcesSecureChat=true")));
	}

	@Test void omitsMismatchFindingWhenProtocolsAgree() {
		ServerPingModule module = new ServerPingModule((host, port, timeout) -> new ServerListPing.Reply(37L, STATUS_JSON));
		ModuleResult result = module.runActive(session("play.example.com", 770), Map.of(), null).toCompletableFuture().join();
		assertFalse(titles(result).contains("Protocol mismatch"));
	}

	@Test void declinesIntegratedSessions() {
		ServerPingModule module = new ServerPingModule((host, port, timeout) -> {
			throw new IllegalStateException("must not be called");
		});
		ModuleContext singleplayer = new ModuleContext(true, true, true, false, "singleplayer", "vanilla",
			"26.3", -1, 0, 1, "enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
		ModuleResult result = module.runActive(singleplayer, Map.of(), null).toCompletableFuture().join();
		assertEquals("Status audit not applicable", result.summary());
		assertTrue(titles(result).contains("Integrated session"));
	}

	@Test void reportsUnreachableStatusEndpoints() {
		ServerPingModule module = new ServerPingModule((host, port, timeout) -> {
			throw new IOException("connect timed out");
		});
		ModuleResult result = module.runActive(session("down.example.com", 771), Map.of(), null).toCompletableFuture().join();
		assertTrue(result.summary().contains("unreachable"));
		assertTrue(titles(result).contains("Unreachable"));
		assertTrue(details(result).stream().anyMatch(d -> d.contains("connect timed out")));
	}

	@Test void rejectsInvalidTimeouts() {
		ServerPingModule module = new ServerPingModule((host, port, timeout) -> new ServerListPing.Reply(1L, STATUS_JSON));
		ModuleResult result = module.runActive(session("play.example.com", 771), Map.of("TIMEOUT", "banana"), null).toCompletableFuture().join();
		assertEquals("Invalid timeout", result.summary());
	}

	@Test void passesParsedAddressAndTimeoutToThePinger() {
		AtomicReference<String> seenHost = new AtomicReference<>();
		AtomicReference<Integer> seenPort = new AtomicReference<>();
		AtomicReference<Integer> seenTimeout = new AtomicReference<>();
		ServerPingModule module = new ServerPingModule((host, port, timeout) -> {
			seenHost.set(host);
			seenPort.set(port);
			seenTimeout.set(timeout);
			return new ServerListPing.Reply(5L, "{\"version\": {\"name\": \"x\", \"protocol\": 1}}");
		});
		module.runActive(session("play.example.com:25599", 1), Map.of("TIMEOUT", "2"), null).toCompletableFuture().join();
		assertEquals("play.example.com", seenHost.get());
		assertEquals(25599, seenPort.get());
		assertEquals(2000, seenTimeout.get());
	}

	@Test void declaresItselfAnActiveButBoundedModule() {
		ServerPingModule module = new ServerPingModule();
		assertTrue(module.performsActiveAction(Map.of()));
		assertTrue(module.requiresAuthorization());
		assertEquals("auxiliary/scanner/server_ping", module.id());
	}
}
