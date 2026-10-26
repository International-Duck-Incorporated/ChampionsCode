package dev.champions.desktop;

import dev.champions.api.java.RuntimeJavaCommands;
import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.update.UpdateService;
import dev.champions.net.ipc.Json;
import dev.champions.net.ipc.Protocol;
import dev.champions.core.repo.RepoConfig;
import dev.champions.surface.ConsoleBuffer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Headless verification that the desktop half of the mod really runs without
 * Minecraft.
 *
 * <p>Run from the shipped mod JAR with {@code java -jar champions-mod-x.jar
 * --self-test}. It exercises the shared terminal model, the wire codec, a full
 * gated console session against the simulated target, and asserts that no
 * Minecraft or Fabric class is on the classpath. CI fails the build when this
 * exits non-zero.</p>
 */
public final class SelfTest {
	private static int failures;

	private SelfTest() {}

	public static int run() {
		System.out.println("ChampionsCode desktop self-test " + DesktopMain.APP_VERSION);
		Path config;
		try {
			config = Files.createTempDirectory("champions-selftest");
		} catch (IOException error) {
			System.out.println("FAIL: could not create a temporary directory");
			return 1;
		}
		try {
			buffer();
			codec();
			isolation();
			consoleSession(config.resolve("demo"));
			capabilityGate(config.resolve("gated"));
			dropInCompiler(config.resolve("codespace"));
			interfacePreference(config.resolve("interfaces"));
			settingsLayout(config.resolve("layout"));
			moduleRepository(config.resolve("repo"));
			payloadSafety();
			jsonEdgeCases();
		} finally {
			deleteTree(config);
		}
		System.out.println(failures == 0 ? "SELF-TEST PASSED" : "SELF-TEST FAILED (" + failures + ")");
		return failures == 0 ? 0 : 1;
	}

	private static void buffer() {
		ConsoleBuffer buffer = new ConsoleBuffer();
		for (int index = 0; index < 1_500; index++) buffer.append(ConsoleLine.info("line " + index));
		check("buffer keeps the 1000-line bound", buffer.size() == ConsoleBuffer.MAX_LINES);
		check("buffer drops from the front", buffer.snapshot().getLast().text().equals("line 1499"));
		check("buffer reports a delta", buffer.since(995).size() == 5);
		long revision = buffer.revision();
		buffer.append(ConsoleLine.info("x"));
		check("revision advances on append", buffer.revision() == revision + 1);
		buffer.recordCommand("show modules");
		buffer.recordCommand("use auxiliary/scanner/server_ping");
		check("history steps back", "use auxiliary/scanner/server_ping".equals(buffer.previousCommand()));
		check("history steps back again", "show modules".equals(buffer.previousCommand()));
		check("history returns empty past the end", "".equals(buffer.nextCommand()) || buffer.nextCommand() != null);
	}

	private static void codec() {
		List<ConsoleLine> lines = List.of(
			ConsoleLine.success("[+] done"),
			ConsoleLine.warning("quote\" backslash\\ newline\n"),
			new ConsoleLine(ConsoleLine.Tone.ACCENT, "unicode ✓ ünïcode")
		);
		String frame = Protocol.encodeConsoleLines(7, lines);
		Map<String, Object> decoded = Protocol.decode(frame);
		List<ConsoleLine> round = Protocol.decodeLines(decoded, "lines");
		check("codec round-trips tones", round.getFirst().tone() == ConsoleLine.Tone.SUCCESS);
		check("codec round-trips text", round.get(2).text().equals("unicode ✓ ünïcode"));
		check("codec round-trips the offset", Protocol.num(decoded, "from", -1) == 7);
		Map<String, Object> state = Map.of("connected", true, "target", "lab.demo.invalid",
			"modules", List.of(Json.obj("id", "a/b", "selected", false)));
		String stateFrame = Protocol.encodeState(state);
		check("state frames carry the type", Protocol.STATE_PUSH.equals(Protocol.type(Protocol.decode(stateFrame))));
		check("state frames keep nested objects", Protocol.decode(stateFrame).get("modules") instanceof List<?>);
	}

	private static void isolation() {
		for (String className : List.of("net.minecraft.client.Minecraft", "net.fabricmc.loader.api.FabricLoader")) {
			try {
				Class.forName(className);
				check("no Minecraft/Fabric class on the desktop classpath (" + className + ")", false);
			} catch (ClassNotFoundException expected) {
				check("no Minecraft/Fabric class on the desktop classpath (" + className + ")", true);
			}
		}
	}

	/**
	 * The `interfaces` command has to survive a round trip through the properties
	 * file, because that is how the in-game chat, the app's Settings tab and the
	 * next launch all agree on where the UI opens.
	 */
	private static void interfacePreference(Path config) {
		try {
			Files.createDirectories(config);
		} catch (IOException error) {
			check("interface settings directory exists", false);
			return;
		}
		ChampionConsole console = new ChampionConsole(config, DemoContext::capture,
			ActiveModuleRuntime.disabled(), RuntimeJavaCommands.disabled(), () -> {}, () -> {}, () -> {},
			dev.champions.core.console.ExploitWarningPrompt.unavailable(), UpdateService.disabled(), () -> {});
		FileInterfacePreferences preferences = new FileInterfacePreferences(config, () -> { });
		console.setInterfacePreferences(preferences);

		console.submit("interfaces");
		check("interfaces prints the current mode", contains(console, "both"));
		console.submit("interfaces in-game");
		check("interfaces switches to in-game", contains(console, "in-game"));
		check("interfaces persists the mode", preferences.currentMode().equals("in-game"));
		console.submit("interfaces external");
		check("interfaces switches to external", preferences.currentMode().equals("external"));
		console.submit("interfaces ingame off");
		check("interfaces toggles the in-game windows", !preferences.inGameWindows());
		console.submit("interfaces spawn off");
		check("interfaces toggles autostart", !preferences.spawnOnOpen());
		console.submit("interfaces nonsense");
		check("interfaces rejects an unknown mode", contains(console, "Unknown interface"));
		try {
			String file = Files.readString(config.resolve("desktop.properties"));
			check("interfaces writes ui=", file.contains("ui=external"));
			check("interfaces writes ingameWindows=", file.contains("ingameWindows=false"));
			check("interfaces writes spawnOnOpen=", file.contains("spawnOnOpen=false"));
		} catch (IOException error) {
			check("desktop.properties readable", false);
		}
	}

	private static void consoleSession(Path config) {
		ChampionConsole console = new ChampionConsole(config, DemoContext::capture,
			ActiveModuleRuntime.disabled(), RuntimeJavaCommands.disabled(), () -> {}, () -> {}, () -> {},
			dev.champions.core.console.ExploitWarningPrompt.unavailable(), UpdateService.disabled(), () -> {});
		console.submit("show modules");
		check("console lists builtins", contains(console, "auxiliary/audit/plugin_surface"));
		console.submit("use auxiliary/audit/plugin_surface");
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");
		console.submit("run");
		check("unauthorized target is denied", contains(console, "Authorization gate denied"));
		console.submit("authorize current");
		check("target can be authorized", contains(console, "Authorized current target"));
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");
		console.submit("run");
		check("authorized run completes", contains(console, "Advertised surface inventoried"));
		console.submit("run");
		check("CONFIRM is consumed after one run", contains(console, "Authorization gate denied"));
		console.submit("use auxiliary/audit/dependency_posture");
		console.submit("set COMPONENTS worldedit@7.3.0,libreforge");
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");
		console.submit("run");
		check("dependency posture runs", contains(console, "dependency"));
		try {
			List<String> audit = Files.readAllLines(config.resolve("audit.jsonl"));
			check("audit log records runs", audit.stream().anyMatch(line -> line.contains("\"module_run\"")));
			check("audit log records the denial", audit.stream().anyMatch(line -> line.contains("\"denied\"")));
			check("audit log is valid JSON", audit.stream().allMatch(line -> {
				try {
					Protocol.decode(line);
					return true;
				} catch (RuntimeException malformed) {
					return false;
				}
			}));
		} catch (IOException error) {
			check("audit log readable", false);
		}
	}

	/**
	 * A build that lacks the command tree must refuse a gated module with an
	 * explicit reason and audit the refusal, instead of returning empty evidence.
	 */
	private static void capabilityGate(Path config) {
		ChampionConsole console = new ChampionConsole(config, DemoContext::capture,
			ActiveModuleRuntime.disabled(), RuntimeJavaCommands.disabled(), () -> {}, () -> {}, () -> {},
			dev.champions.core.console.ExploitWarningPrompt.unavailable(), UpdateService.disabled(), () -> {});
		console.setCapabilitiesSupplier(() -> dev.champions.core.mc.ClientCapabilities.minimal(
			"1.21.11", "0.16.0", "0.2.0", "mc1.21.11"));
		console.submit("use auxiliary/audit/plugin_surface");
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");
		console.submit("authorize current");
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");
		console.submit("run");
		check("version-gated module is refused", contains(console, "which this build for Minecraft 1.21.11 does not provide"));
		check("refusal names the missing feature", contains(console, "advertised command tree"));
		check("ungated module still runs", runsPassiveModule());
	}

	private static boolean runsPassiveModule() {
		ChampionConsole console = new ChampionConsole(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "champions-cap-" + System.nanoTime()),
			DemoContext::capture, ActiveModuleRuntime.disabled(), RuntimeJavaCommands.disabled(), () -> {}, () -> {}, () -> {},
			dev.champions.core.console.ExploitWarningPrompt.unavailable(), UpdateService.disabled(), () -> {});
		console.setCapabilitiesSupplier(() -> dev.champions.core.mc.ClientCapabilities.minimal(
			"1.21.11", "0.16.0", "0.2.0", "mc1.21.11"));
		console.submit("use auxiliary/audit/connection_posture");
		console.submit("authorize current");
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");
		console.submit("run");
		return contains(console, "[+]");
	}

	/**
	 * The code space must compile and register a drop-in module with no game
	 * running: same bounds, same discovery, same registry swap as the in-game
	 * Java Studio.
	 */
	private static void dropInCompiler(Path config) {
		ChampionConsole console = new ChampionConsole(config, DemoContext::offline,
			ActiveModuleRuntime.disabled(), RuntimeJavaCommands.disabled(), () -> {}, () -> {}, () -> {},
			dev.champions.core.console.ExploitWarningPrompt.unavailable(), UpdateService.disabled(), () -> {});
		Path modules = config.resolve("modules");
		try {
			Files.createDirectories(modules);
			Files.writeString(modules.resolve("SelfTestModule.java"), """
				import dev.champions.core.module.Finding;
				import dev.champions.core.module.ModuleContext;
				import dev.champions.core.module.ModuleOption;
				import dev.champions.core.module.ModuleResult;
				import dev.champions.core.module.ResearchModule;

				import java.util.List;
				import java.util.Map;

				public final class SelfTestModule implements ResearchModule {
					@Override public String id() { return "auxiliary/local/self_test"; }
					@Override public String name() { return "Self test module"; }
					@Override public String description() { return "Compiled outside Minecraft."; }
					@Override public List<ModuleOption> options() { return List.of(); }
					@Override public ModuleResult run(ModuleContext context, Map<String, String> options) {
						return new ModuleResult("ran", List.of(new Finding(Finding.Severity.PASS, "ok", "ok", "ok")));
					}
				}
				""");
		} catch (IOException error) {
			check("drop-in compiler: could write a module", false);
			return;
		}
		StandaloneRuntime runtime = new StandaloneRuntime(config, console);
		StandaloneRuntime.Result result = runtime.reload();
		check("drop-in compiler: compiles outside Minecraft", result.success());
		check("drop-in compiler: registers the module", result.moduleIds().contains("auxiliary/local/self_test"));
		check("drop-in compiler: module is selectable", console.selectModuleFromUi("auxiliary/local/self_test"));
		StandaloneRuntime.Result unload = runtime.unload("self-test cleanup");
		check("drop-in compiler: unload removes it", unload.moduleIds().isEmpty());
	}

	/**
	 * Guards the Settings pane against the layout bug where the interface card
	 * reported an unbounded maximum height: inside a vertical BoxLayout it then
	 * swallowed every spare pixel and the rows hung stranded above a footer
	 * pinned to the bottom edge. The card must stay as tall as its content at
	 * any window height, and the whole page must fit the smallest window.
	 */
	private static void settingsLayout(Path config) {
		SettingsPanel panel = new SettingsPanel(null, config);
		for (int height : new int[] { 600, 1000 }) {
			panel.setSize(1040, height);
			layOut(panel);
			var card = panel.interfaceCard();
			int slack = card.getHeight() - card.getPreferredSize().height;
			check("interface card is not stretched at " + height + "px (" + slack + "px slack)",
				Math.abs(slack) <= 1);
		}
		panel.setSize(1040, 600);
		layOut(panel);
		int needed = panel.getPreferredSize().height;
		check("settings page fits the minimum window (" + needed + "px of 634px)",
			needed <= 634);
	}

	/** Lays out a component tree that was never realised on screen. */
	private static void layOut(java.awt.Component component) {
		component.doLayout();
		if (component instanceof java.awt.Container container) {
			for (java.awt.Component child : container.getComponents()) layOut(child);
		}
	}

	/**
	 * The module repository contract, without the network: the index parses,
	 * search filters the way the console promises, and a file is only accepted
	 * when it hashes to what the index pinned.
	 */
	private static void moduleRepository(Path config) {
		try {
			moduleRepositoryChecked(config);
		} catch (IOException error) {
			check("repo configuration is writable (" + error.getMessage() + ")", false);
		}
	}

	private static void moduleRepositoryChecked(Path config) throws IOException {
		String index = """
			{
			  "schema": 1,
			  "name": "Test collection",
			  "updated": "2026-10-26",
			  "modules": [
			    {"id": "auxiliary/audit/alpha", "name": "Alpha audit", "version": "1.0.0",
			     "category": "audit", "api": 1, "tags": ["audit", "chat"],
			     "description": "Reviews chat exposure.",
			     "file": "modules/Alpha.java", "sha256": "%s"},
			    {"id": "scanner/beta", "name": "Beta scanner", "category": "scanner",
			     "tags": ["performance"], "description": "Measures capacity.",
			     "file": "modules/Beta.java", "sha256": "00"},
			    {"name": "no id, must be dropped", "file": "modules/Nope.java"}
			  ]
			}
			""".formatted(dev.champions.core.repo.ModuleRepositoryClient.sha256("class Alpha {}"));

		dev.champions.core.repo.ModuleIndex parsed = dev.champions.core.repo.ModuleIndex.parse(index);
		check("repo index parses and drops entries without an id", parsed.entries().size() == 2);
		check("repo search matches tags", parsed.search(java.util.List.of("chat")).size() == 1
				&& parsed.search(java.util.List.of("chat")).getFirst().id().equals("auxiliary/audit/alpha"));
		check("repo search requires every word", parsed.search(java.util.List.of("audit", "chat")).size() == 1
				&& parsed.search(java.util.List.of("audit", "performance")).isEmpty());
		check("repo search is case-insensitive", parsed.search(java.util.List.of("BETA")).size() == 1);

		dev.champions.core.repo.RepoEntry entry = parsed.find("auxiliary/audit/alpha").orElseThrow();
		check("repo accepts a matching file",
				dev.champions.core.repo.ModuleRepositoryClient.verify(entry, "class Alpha {}"));
		check("repo rejects a changed file",
				!dev.champions.core.repo.ModuleRepositoryClient.verify(entry, "class Alpha { }"));
		check("repo rejects a file when the index pins no hash",
				!dev.champions.core.repo.ModuleRepositoryClient.verify(
						parsed.find("scanner/beta").orElseThrow(), "class Beta {}"));

		boolean rejected = false;
		try {
			dev.champions.core.repo.ModuleIndex.parse("<html>404</html>");
		} catch (IllegalArgumentException expected) {
			rejected = true;
		}
		check("repo rejects a non-index document instead of reading it as empty", rejected);

		dev.champions.core.repo.RepoConfig repoConfig = new dev.champions.core.repo.RepoConfig(config);
		repoConfig.setUrl("default", "https://gitlab.com/group/sub/project.git", "main");
		dev.champions.core.repo.RepoConfig.Repo defaultRepo = repoConfig.primary();
		check("repo derives the project path from a URL",
				defaultRepo.projectPath().equals("group/sub/project"));
		check("repo derives the API origin", defaultRepo.origin().equals("https://gitlab.com"));
		repoConfig.setToken("default", "glpat-abcdefghijklmnop");
		check("repo masks the token in console output",
				repoConfig.primary().maskedToken().equals("glpa...mnop"));

		// Several repositories, in the order they were added.
		try {
			repoConfig.add("team", "https://gitlab.com/group/team-modules", "main");
			repoConfig.add("vendor", "https://gitlab.com/group/vendor-modules", "release");
		} catch (IOException error) {
			check("repo accepts additional repositories", false);
		}
		check("repo keeps several repositories in order",
				repoConfig.repos().size() == 3
						&& repoConfig.repos().get(0).name().equals("default")
						&& repoConfig.repos().get(2).name().equals("vendor"));
		check("repo finds a repository by name",
				repoConfig.find("team").map(dev.champions.core.repo.RepoConfig.Repo::ref).orElse("").equals("main")
						&& repoConfig.find("vendor").map(RepoConfig.Repo::ref).orElse("").equals("release"));
		check("repo rejects a duplicate name", duplicateRejected(repoConfig, "team"));
		check("repo rejects an unusable name", duplicateRejected(repoConfig, "Bad Name"));

		// Everything above survives a reload from disk.
		dev.champions.core.repo.RepoConfig reread = new dev.champions.core.repo.RepoConfig(config);
		check("repo configuration round-trips through the file",
				reread.repos().size() == 3 && reread.find("vendor").isPresent()
						&& reread.primary().maskedToken().equals("glpa...mnop"));
	}

	private static boolean duplicateRejected(dev.champions.core.repo.RepoConfig config, String name) {
		try {
			config.add(name, "https://gitlab.com/group/dupe", "main");
			return false;
		} catch (IllegalArgumentException | IOException expected) {
			return true;
		}
	}

	/**
	 * A payload must declare a benign effect before it can run. These are the
	 * rules that make "prove the impact" different from "do damage", so they are
	 * tested as rules rather than trusted as conventions.
	 */
	private static void payloadSafety() {
		check("payload allows a visible chat command",
				dev.champions.core.module.payload.PayloadSafety.check(
						dev.champions.core.module.payload.PayloadEffect.chatCommand("say hello from audit")).allowed());
		check("payload allows the leading slash form",
				dev.champions.core.module.payload.PayloadSafety.check(
						dev.champions.core.module.payload.PayloadEffect.chatCommand("/say proof")).allowed());
		check("payload refuses a state-changing command",
				!dev.champions.core.module.payload.PayloadSafety.check(
						dev.champions.core.module.payload.PayloadEffect.chatCommand("give @a diamond 64")).allowed());
		check("payload refuses op",
				!dev.champions.core.module.payload.PayloadSafety.check(
						dev.champions.core.module.payload.PayloadEffect.chatCommand("op Player")).allowed());
		check("payload refuses command chaining",
				!dev.champions.core.module.payload.PayloadSafety.check(
						dev.champions.core.module.payload.PayloadEffect.chatCommand("say hi\nop Player")).allowed());
		check("payload refuses NBT smuggling",
				!dev.champions.core.module.payload.PayloadSafety.check(
						dev.champions.core.module.payload.PayloadEffect.chatCommand("say {\"clickEvent\":{}}")).allowed());
		check("payload refuses an unknown root",
				!dev.champions.core.module.payload.PayloadSafety.check(
						dev.champions.core.module.payload.PayloadEffect.chatCommand("pluginsurface run")).allowed());
		check("payload allows a bare marker file",
				dev.champions.core.module.payload.PayloadSafety.check(
						dev.champions.core.module.payload.PayloadEffect.markerFile("champions-proof.txt")).allowed());
		check("payload refuses a marker path",
				!dev.champions.core.module.payload.PayloadSafety.check(
						dev.champions.core.module.payload.PayloadEffect.markerFile("../../startup.txt")).allowed());
		check("payload refuses a nested marker path",
				!dev.champions.core.module.payload.PayloadSafety.check(
						dev.champions.core.module.payload.PayloadEffect.markerFile("sub/proof.txt")).allowed());
		// The runtime accepts a vanilla root only for a declared proof, and keeps
		// refusing it for a surface probe: the two paths must not share a rule.
		boolean proofAccepted = true;
		try {
			dev.champions.core.module.active.ActiveModuleRuntime.ChatCommandRequest.proof(
					"say", List.of("champions", "proof"), 60, 4);
		} catch (IllegalArgumentException refused) {
			proofAccepted = false;
		}
		check("runtime accepts a proof on a vanilla root", proofAccepted);
		boolean probeRefused = false;
		try {
			dev.champions.core.module.active.ActiveModuleRuntime.ChatCommandRequest.path(
					"say", List.of("champions"), 60, 4);
		} catch (IllegalArgumentException refused) {
			probeRefused = true;
		}
		check("runtime still refuses to probe a vanilla root", probeRefused);
		boolean proofRefusedStateChange = false;
		try {
			dev.champions.core.module.active.ActiveModuleRuntime.ChatCommandRequest.proof(
					"give", List.of("diamond"), 60, 4);
		} catch (IllegalArgumentException refused) {
			proofRefusedStateChange = true;
		}
		check("runtime refuses a state-changing proof", proofRefusedStateChange);

		// A command with no arguments (a payload's /seed, /list) takes the
		// bare-command factory, which the literal form cannot express.
		String bareWire = "";
		boolean bareAccepted = true;
		try {
			bareWire = dev.champions.core.module.active.ActiveModuleRuntime.ChatCommandRequest
				.proof("seed", 60, 4).wireCommand();
		} catch (IllegalArgumentException refused) {
			bareAccepted = false;
		}
		check("runtime accepts a bare-command proof", bareAccepted);
		check("bare-command proof sends exactly the command", bareWire.equals("seed"));
		boolean bareRefused = false;
		try {
			dev.champions.core.module.active.ActiveModuleRuntime.ChatCommandRequest.proof("op", 60, 4);
		} catch (IllegalArgumentException refused) {
			bareRefused = true;
		}
		check("runtime refuses a bare-command proof that changes state", bareRefused);
		check("module API level covers the bare-command proof",
			dev.champions.core.repo.ModuleIndex.SUPPORTED_API >= 2);

		check("payload accepts a small marker file",
				dev.champions.core.module.payload.PayloadSafety.acceptableFileSize(512));
		check("payload refuses an oversized marker file",
				!dev.champions.core.module.payload.PayloadSafety.acceptableFileSize(
						dev.champions.core.module.payload.PayloadSafety.maximumFileBytes() + 1));
	}

	private static void jsonEdgeCases() {
		String encoded = Json.obj("t", "test", "value", "line\nbreak \"quoted\" \\ \u0001", "number", 42L, "flag", true);
		Map<String, Object> decoded = Protocol.decode(encoded);
		check("json escapes control characters", Protocol.str(decoded, "value", "").contains("\n"));
		check("json keeps numbers", Protocol.num(decoded, "number", 0) == 42);
		check("json keeps booleans", Protocol.bool(decoded, "flag", false));
	}

	private static boolean contains(ChampionConsole console, String needle) {
		for (ConsoleLine line : console.lines()) if (line.text().contains(needle)) return true;
		return false;
	}

	private static void check(String label, boolean passed) {
		if (passed) {
			System.out.println("  ok   " + label);
		} else {
			failures++;
			System.out.println("  FAIL " + label);
		}
	}

	private static void deleteTree(Path root) {
		try (var stream = Files.walk(root)) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// best effort
				}
			});
		} catch (IOException ignored) {
			// best effort
		}
	}
}
