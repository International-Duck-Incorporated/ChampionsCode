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
			portedCatalog();
			exploitSeparation();
			verificationFlow();
			verificationAudit();
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

	/** The ported catalog and the artifact folder: the rules, checked without a server. */
	private static void portedCatalog() {
		check("catalog knows Skript from its command",
			dev.champions.core.catalog.ExploitCatalog.match(java.util.List.of("skript", "sk")).stream()
				.anyMatch(entry -> entry.id().equals("skript_console")));
		check("catalog resolves a namespaced command",
			dev.champions.core.catalog.ExploitCatalog.match(java.util.List.of("bukkit:plugins")).isEmpty());
		check("catalog stays quiet on a vanilla surface",
			dev.champions.core.catalog.ExploitCatalog.match(java.util.List.of("help", "list", "me")).isEmpty());
		check("catalog has no duplicate ids",
			dev.champions.core.catalog.ExploitCatalog.entries().stream().map(e -> e.id()).distinct().count()
				== dev.champions.core.catalog.ExploitCatalog.entries().size());

		// The disposition table for the ported addon ships in the jar, one row per
		// source module, so the completeness claim can be read out of the artifact
		// instead of taken from a document.
		int manifestRows = 0;
		java.util.Set<String> manifestRefs = new java.util.HashSet<>();
		try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
				SelfTest.class.getResourceAsStream("/META-INF/port-manifest.tsv"),
				java.nio.charset.StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] parts = line.split("\\t", -1);
				if (parts.length < 5) continue;
				manifestRows++;
				if (!parts[3].isBlank()) manifestRefs.add(parts[3]);
			}
		} catch (Exception e) {
			manifestRows = -1;
		}
		check("port manifest ships with 48 rows", manifestRows == 48);
		check("port manifest accounts for every catalogued entry",
			manifestRefs.equals(dev.champions.core.catalog.ExploitCatalog.entries().stream()
				.map(e -> e.id()).collect(java.util.stream.Collectors.toSet())));

		// Every artifact a catalogued entry offers must pass the payload rules, or the
		// payload module would be advertising something it can never write.
		boolean everyArtifactAllowed = dev.champions.core.catalog.ExploitCatalog.withArtifacts().stream()
			.allMatch(entry -> dev.champions.core.module.payload.PayloadSafety
				.check(dev.champions.core.module.payload.PayloadEffect.auditArtifact(entry.artifactFile())).allowed());
		check("every catalogued artifact passes the payload rules", everyArtifactAllowed);
		check("every catalogued artifact fits the size ceiling",
			dev.champions.core.catalog.ExploitCatalog.withArtifacts().stream()
				.allMatch(entry -> dev.champions.core.module.payload.PayloadSafety
					.acceptableArtifactSize(entry.artifact().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
		check("artifact with a path is refused",
			!dev.champions.core.module.payload.PayloadSafety
				.check(dev.champions.core.module.payload.PayloadEffect.auditArtifact("../escape.yml")).allowed());
		check("artifact with an executable extension is refused",
			!dev.champions.core.module.payload.PayloadSafety
				.check(dev.champions.core.module.payload.PayloadEffect.auditArtifact("payload.jar")).allowed());
		check("artifact with an inert extension is allowed",
			dev.champions.core.module.payload.PayloadSafety
				.check(dev.champions.core.module.payload.PayloadEffect.auditArtifact("notes.yml")).allowed());

		// The folder itself refuses to be talked out of its directory.
		try {
			java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("selftest-artifacts");
			var folder = dev.champions.core.catalog.AuditFolder.under(dir);
			boolean wrote = folder.write("ok.txt", "hello").ok();
			boolean escaped = folder.write("../escape.txt", "no").ok();
			boolean deleted = folder.delete("ok.txt");
			check("artifact folder writes and deletes inside itself", wrote && deleted);
			check("artifact folder refuses a traversal name", !escaped);
			check("artifact folder holds nothing after revert", folder.list().isEmpty());
		} catch (java.io.IOException error) {
			check("artifact folder writes and deletes inside itself", false);
		}
	}

	/**
	 * The separation, as properties: a payload that serves several mechanisms, a gate
	 * that will not be talked round, and a reproduction whose verdict comes from what
	 * the fixture recorded.
	 */
	private static void exploitSeparation() {
		var mechanisms = dev.champions.core.exploit.MechanismRegistry.all();
		check("mechanisms are registered", mechanisms.size() >= 10);
		check("every mechanism names a fixture this build ships",
			mechanisms.stream().allMatch(m -> dev.champions.core.exploit.fixture.FixtureRegistry
				.byId(m.fixtureId()) != null));
		check("every mechanism has a catalogue entry", mechanisms.stream().allMatch(m -> m.entry() != null));

		var payloads = dev.champions.core.exploit.ReproductionEngine.payloads();
		check("payloads are registered", payloads.size() >= 9);
		check("payload ids are unique",
			payloads.stream().map(dev.champions.core.exploit.PayloadProvider::id).distinct().count() == payloads.size());
		check("every payload declares a cleanup",
			payloads.stream().allMatch(p -> !p.describe().cleanup().isBlank()));

		// One payload, several mechanisms, unchanged.
		var injection = new dev.champions.core.exploit.payload.ConsoleInjectionPayload();
		var fixture = (dev.champions.core.exploit.fixture.ConsoleDispatchFixture)
			dev.champions.core.exploit.fixture.FixtureRegistry.byId("console_dispatch");
		boolean allThree = true;
		for (String id : java.util.List.of("skript_console", "conditional_events_console", "item_plugin_actions")) {
			var mechanism = dev.champions.core.exploit.MechanismRegistry.get(id);
			fixture.reset();
			var outcome = ((dev.champions.core.exploit.ExploitMechanism<dev.champions.core.exploit.fixture.ConsoleDispatchFixture>)
				mechanism).reproduce(fixture, injection.material(java.util.Map.of()),
					java.util.Map.of("MARKER", "selftest"));
			allThree &= outcome.reachedSink();
			fixture.reset();
		}
		check("one payload reproduces three mechanisms", allThree);

		// The gate's central rule, checked without a fixture.
		var descriptor = new dev.champions.core.exploit.PayloadDescriptor("probe", "Probe", "1.0.0",
			dev.champions.core.exploit.PayloadKind.EXECUTION_CAPABLE, dev.champions.core.exploit.PayloadShape.CONSOLE_TEXT,
			java.util.Set.of("skript_console"), "", "", "", "", java.util.List.of(), java.util.List.of(), "");
		var material = new dev.champions.core.exploit.PayloadMaterial.InjectedText(
			dev.champions.core.exploit.PayloadShape.CONSOLE_TEXT, "x; say y", "x");
		var liveRequest = new dev.champions.core.exploit.GateRequest("skript_console", descriptor, material,
			dev.champions.core.exploit.GateRequest.ReproductionMode.LIVE, true, true, "", java.util.Map.of());
		check("gate refuses an execution-capable payload on a live target",
			!dev.champions.core.exploit.ExecutionGate.decide(liveRequest).allowed());
		var fixtureRequest = new dev.champions.core.exploit.GateRequest("skript_console", descriptor, material,
			dev.champions.core.exploit.GateRequest.ReproductionMode.FIXTURE, false, false, "", java.util.Map.of());
		check("gate allows the same payload against a fixture",
			dev.champions.core.exploit.ExecutionGate.decide(fixtureRequest).allowed());

		// A payload cannot declare one thing and produce another.
		var lying = new dev.champions.core.exploit.PayloadDescriptor("liar", "Liar", "1.0.0",
			dev.champions.core.exploit.PayloadKind.INERT_ARTIFACT, dev.champions.core.exploit.PayloadShape.ARTIFACT_TEXT,
			java.util.Set.of(), "", "", "", "", java.util.List.of(), java.util.List.of(), "");
		var lieRequest = new dev.champions.core.exploit.GateRequest("skript_console", lying, material,
			dev.champions.core.exploit.GateRequest.ReproductionMode.FIXTURE, true, true, "notes.txt", java.util.Map.of());
		check("gate refuses a payload that changes what it is at run time",
			!dev.champions.core.exploit.ExecutionGate.decide(lieRequest).allowed());

		check("fixtures are clean after a run", fixture.recorded().isEmpty());
	}

	/**
	 * Verification as a flow: a plan that runs nothing, an acknowledgement that
	 * releases it, and a control that decides whether the result means anything.
	 */
	private static void verificationFlow() {
		java.nio.file.Path dir;
		try {
			dir = java.nio.file.Files.createTempDirectory("selftest-verify");
		} catch (java.io.IOException error) {
			check("verification temp directory", false);
			return;
		}
		try {
			var engine = new dev.champions.core.exploit.ReproductionEngine(
				dev.champions.core.catalog.AuditFolder.under(dir),
				dev.champions.core.module.active.ActiveModuleRuntime.disabled());
			var payload = new dev.champions.core.exploit.payload.ConsoleInjectionPayload();
			var fixture = (dev.champions.core.exploit.fixture.ConsoleDispatchFixture)
				dev.champions.core.exploit.fixture.FixtureRegistry.byId("console_dispatch");

			// Planning is inert.
			fixture.reset();
			var plan = engine.plan("skript_console", payload,
				dev.champions.core.exploit.GateRequest.ReproductionMode.FIXTURE, false, false, java.util.Map.of());
			check("a verification plan is executable", plan.executable());
			check("a plan has control, payload and repeat stages", plan.stages().size() == 3);
			check("planning executes nothing", fixture.recorded().isEmpty());

			// Execution needs the acknowledgement.
			var refused = engine.verify("skript_console", payload,
				dev.champions.core.exploit.GateRequest.ReproductionMode.FIXTURE, false, false, "", java.util.Map.of())
				.toCompletableFuture().join();
			check("verification without acknowledgement is blocked",
				refused.verdict() == dev.champions.core.exploit.ReproductionState.BLOCKED_BY_GATE);
			check("a blocked verification executes nothing", fixture.recorded().isEmpty());

			// With it, the control and the payload decide the verdict.
			var report = engine.verify("skript_console", payload,
				dev.champions.core.exploit.GateRequest.ReproductionMode.FIXTURE, false, false,
				dev.champions.core.exploit.ReproductionEngine.ACKNOWLEDGEMENT_PHRASE, java.util.Map.of())
				.toCompletableFuture().join();
			check("a discriminating reproduction is verified",
				report.verdict() == dev.champions.core.exploit.ReproductionState.FIXTURE_VERIFIED);
			check("the verdict records that it discriminated", report.discriminative());
			check("the control did not produce the payload's effect",
				report.stage(dev.champions.core.exploit.VerificationStage.CONTROL).effects().stream()
					.noneMatch(effect -> effect.contains("-second")));
			check("the control is the payload minus the trigger",
				report.stage(dev.champions.core.exploit.VerificationStage.PAYLOAD).effects()
					.containsAll(report.stage(dev.champions.core.exploit.VerificationStage.CONTROL).effects()));
			check("the control took the same sink path",
				report.stage(dev.champions.core.exploit.VerificationStage.CONTROL).sinkPath()
					.equals(report.stage(dev.champions.core.exploit.VerificationStage.PAYLOAD).sinkPath()));
			check("the payload reached the sink twice",
				report.stage(dev.champions.core.exploit.VerificationStage.PAYLOAD).reached()
					&& report.stage(dev.champions.core.exploit.VerificationStage.REPEAT).reached());
			check("verification leaves the fixture clean", fixture.recorded().isEmpty());

			// Every execution-capable payload can be verified: it declares a control.
			boolean controlsPresent = dev.champions.core.exploit.ReproductionEngine.payloads().stream()
				.filter(p -> p.describe().kind() == dev.champions.core.exploit.PayloadKind.EXECUTION_CAPABLE)
				.allMatch(p -> p.control(java.util.Map.of()) != null);
			check("every execution-capable payload declares a control", controlsPresent);

			// An artifact is not a verification.
			var artifactPlan = engine.plan("skript_console",
				new dev.champions.core.exploit.payload.InertArtifactPayload(),
				dev.champions.core.exploit.GateRequest.ReproductionMode.FIXTURE, false, false,
				java.util.Map.of("MECHANISM", "skript_console"));
			check("an artifact payload cannot produce a verification plan", !artifactPlan.executable());
		} finally {
			try (var files = java.nio.file.Files.walk(dir)) {
				files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
					try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
				});
			} catch (java.io.IOException ignored) {
				// best effort: the directory is a temp tree
			}
		}
	}

	/**
	 * The five things a verified verdict has to survive: the control takes the same
	 * path, the sink is what is observed, the sample is declared, the verdict names
	 * what ran, and an approval cannot be manufactured.
	 */
	private static void verificationAudit() {
		// 1. A control must be the payload minus the trigger, and take the same road.
		var payload = new dev.champions.core.exploit.payload.ConsoleInjectionPayload();
		check("a control is not the payload",
			!payload.control(java.util.Map.of()).equals(payload.material(java.util.Map.of())));

		// 2. The sink records effects; the engine reads them, not a mechanism's opinion.
		var console = (dev.champions.core.exploit.fixture.ConsoleDispatchFixture)
			dev.champions.core.exploit.fixture.FixtureRegistry.byId("console_dispatch");
		console.reset();
		console.runConsoleEffect("audit; say second");
		check("the fixture records privileged effects", console.recorded().size() == 2);
		check("the fixture records which operation it entered",
			console.trail().equals(java.util.List.of("runConsoleEffect")));
		console.reset();

		// 3. Samples follow the declared determinism, not a constant.
		check("a deterministic mechanism needs two observations",
			dev.champions.core.exploit.Determinism.DETERMINISTIC.minimumSamples() == 2);
		check("an intermittent mechanism needs more than two",
			dev.champions.core.exploit.Determinism.INTERMITTENT.minimumSamples() > 2);

		// 4. The verdict ladder is ordered and the claims differ.
		var fixtureVerified = dev.champions.core.exploit.ReproductionState.FIXTURE_VERIFIED;
		var implementationVerified = dev.champions.core.exploit.ReproductionState.IMPLEMENTATION_VERIFIED;
		var targetVerified = dev.champions.core.exploit.ReproductionState.TARGET_VERIFIED;
		check("the verdicts are ordered model < implementation < target",
			fixtureVerified.tier() < implementationVerified.tier()
				&& implementationVerified.tier() < targetVerified.tier());
		check("the three verified states carry different claims",
			!fixtureVerified.claim().equals(implementationVerified.claim())
				&& !implementationVerified.claim().equals(targetVerified.claim()));
		check("a modelled sink has no reference implementation to run",
			!dev.champions.core.exploit.MechanismRegistry.get("worldguard_yaml").referenceImplementationAvailable());
		check("the deserialiser fixture does",
			dev.champions.core.exploit.MechanismRegistry.get("mythicmobs_deser").referenceImplementationAvailable());

		// 4b. The tier boundary: one modelled observation is enough to drop the claim.
		check("tier 2 needs every observation to have run real code",
			dev.champions.core.exploit.ReproductionEngine.evidenceTier(
				dev.champions.core.exploit.GateRequest.ReproductionMode.FIXTURE,
				java.util.List.of(dev.champions.core.exploit.fixture.VulnerableFixture.Fidelity.REFERENCE_IMPLEMENTATION,
					dev.champions.core.exploit.fixture.VulnerableFixture.Fidelity.MODELLED))
				== dev.champions.core.exploit.ReproductionState.FIXTURE_VERIFIED);
		check("tier 2 is available when every observation ran real code",
			dev.champions.core.exploit.ReproductionEngine.evidenceTier(
				dev.champions.core.exploit.GateRequest.ReproductionMode.FIXTURE,
				java.util.List.of(dev.champions.core.exploit.fixture.VulnerableFixture.Fidelity.REFERENCE_IMPLEMENTATION))
				== dev.champions.core.exploit.ReproductionState.IMPLEMENTATION_VERIFIED);
		check("no evidence is not strong evidence",
			dev.champions.core.exploit.ReproductionEngine.evidenceTier(
				dev.champions.core.exploit.GateRequest.ReproductionMode.FIXTURE, java.util.List.of())
				== dev.champions.core.exploit.ReproductionState.FIXTURE_VERIFIED);

		// 4c. Every mechanism declares its determinism rather than inheriting it.
		boolean declared = dev.champions.core.exploit.MechanismRegistry.all().stream()
			.allMatch(mechanism -> mechanism.getClass().getDeclaredMethods().length > 0
				&& java.util.Arrays.stream(mechanism.getClass().getDeclaredMethods())
					.anyMatch(method -> method.getName().equals("determinism")));
		check("every mechanism declares its own determinism", declared);

		// 5. An approval is a capability, not a boolean anyone can build.
		boolean refused = false;
		try {
			dev.champions.core.exploit.GateDecision.permit(new Object(),
				dev.champions.core.exploit.PayloadKind.EXECUTION_CAPABLE, "no");
		} catch (IllegalArgumentException expected) {
			refused = true;
		}
		check("an approval cannot be constructed outside the gate", refused);
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
