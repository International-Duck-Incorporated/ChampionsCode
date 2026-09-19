package dev.champions.core.script;

import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChampionScriptEngineTest {
	private static ModuleContext context() {
		return new ModuleContext(true, false, false, true, "lab.test:25565", "Paper", "26.3", 1, 12, 3,
			"enabled", "Researcher", "1.00, 2.00, 3.00", "minecraft:overworld", 120, 2F, 1F, List.of("help"));
	}

	@Test void runsReadOnlyExpressions() {
		var result = new ChampionScriptEngine(new ModuleRegistry()).execute("let host = server.address; print \"target=\" + $host; assert connected", context());
		assertTrue(result.success(), result.message());
		assertTrue(result.output().stream().anyMatch(line -> line.text().equals("target=lab.test:25565")));
	}

	@Test void supportsDocumentedReadOnlyStatements() {
		String source = """
			# All supported statement families
			help; status; server; player; position; modules
			let target = server.address
			get target
			print "target=" + $target + " fps=" + client.fps
			notify "connected=" + client.connected
			assert connected
			assert online_mode
			""";
		var result = new ChampionScriptEngine(new ModuleRegistry()).execute(source, context());
		assertTrue(result.success(), result.message());
	}

	@Test void rejectsUnknownCapabilitiesAndJvmInjectionSyntax() {
		var engine = new ChampionScriptEngine(new ModuleRegistry());
		var network = engine.execute("network.connect 'example.org'", context());
		var jvm = engine.execute("java.lang.Runtime.getRuntime().exec('calc')", context());
		assertFalse(network.success());
		assertFalse(jvm.success());
		assertTrue(network.message().contains("Unknown statement"));
		assertTrue(jvm.message().contains("Unknown statement"));
	}
}
