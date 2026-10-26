package dev.champions.core.console;

import dev.champions.core.module.ExploitResearchModule;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;
import dev.champions.core.module.active.ActiveModuleRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChampionConsoleTest {
	@TempDir Path temp;

	@Test void redactsAndDoesNotRetainConfirmationSecret() {
		ChampionConsole console = new ChampionConsole(temp, ChampionConsoleTest::context);
		console.submit("use auxiliary/scanner/server_fingerprint");
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");
		assertFalse(console.history().stream().anyMatch(line -> line.contains("I_HAVE_AUTHORIZATION")));
		assertTrue(console.lines().stream().anyMatch(line -> line.text().contains("set CONFIRM <redacted>")));
	}

	@Test void routesJavaCommandsToRuntimeBridge() {
		AtomicReference<String> submitted = new AtomicReference<>();
		ChampionConsole console = new ChampionConsole(temp, ChampionConsoleTest::context,
			ActiveModuleRuntime.disabled(), submitted::set);
		console.submit("java reload");
		assertEquals("reload", submitted.get());
		assertTrue(console.history().contains("java reload"));
	}

	@Test void opensGuiModesAndExposesStructuredModuleOptions() {
		AtomicBoolean openedList = new AtomicBoolean();
		AtomicBoolean openedCategories = new AtomicBoolean();
		ChampionConsole console = new ChampionConsole(temp, ChampionConsoleTest::context,
			ActiveModuleRuntime.disabled(), command -> {}, () -> openedList.set(true), () -> openedCategories.set(true));
		console.submit("clickgui");
		console.submit("categorygui");
		assertTrue(openedList.get());
		assertTrue(openedCategories.get());
		assertEquals(21, console.moduleViews().size());
		List<String> ids = console.moduleViews().stream().map(ChampionConsole.ModuleView::id).toList();
		assertTrue(ids.contains("auxiliary/payloads/chat_reach_proof"));
		// Ported from the old exploit addon: a check tier and a payload tier.
		assertTrue(ids.contains("auxiliary/audit/plugin_exploit_surface"));
		assertTrue(ids.contains("auxiliary/payloads/poc_artifact"));
		assertEquals(ids.stream().sorted().toList(), ids);
		assertTrue(console.selectModuleFromUi("auxiliary/scanner/chat_plugin_enumerator"));
		assertNotNull(console.activeModuleView());
		assertTrue(console.activeModuleView().options().stream().anyMatch(option -> option.name().equals("MODE")));
		assertTrue(console.setOptionFromUi("MODE", "aggressive"));
		// Constrained options store the canonical spelling of the accepted choice.
		assertEquals("AGGRESSIVE", console.activeModuleView().options().stream()
			.filter(option -> option.name().equals("MODE")).findFirst().orElseThrow().value());
		// Choices are exposed so UIs can render dropdowns.
		assertEquals(List.of("SILENT", "AGGRESSIVE"), console.activeModuleView().options().stream()
			.filter(option -> option.name().equals("MODE")).findFirst().orElseThrow().choices());
		console.submit("deauthorize current");
		assertFalse(console.currentTargetView().authorized());
		console.submit("authorize current");
		assertTrue(console.currentTargetView().authorized());
	}

	@Test void logCommandsOpenTheLogViewerWindow() {
		AtomicInteger opened = new AtomicInteger();
		ChampionConsole console = new ChampionConsole(temp, ChampionConsoleTest::context,
			ActiveModuleRuntime.disabled(), command -> {}, () -> {}, () -> {}, opened::incrementAndGet,
			ExploitWarningPrompt.unavailable(), null, null);
		console.submit("logs");
		console.submit("log");
		console.submit("logviewer");
		assertEquals(3, opened.get());
		// Consoles built without the hook (all shorter constructors) must not throw.
		ChampionConsole defaultConsole = new ChampionConsole(temp, ChampionConsoleTest::context);
		defaultConsole.submit("logs");
		assertTrue(defaultConsole.lines().stream().noneMatch(line -> line.text().startsWith("Unknown command")));
	}

	@Test void exploitWorkspaceRequiresFreshPopupAcknowledgementForEveryRun() {
		AtomicInteger prompts = new AtomicInteger();
		AtomicReference<ExploitWarningPrompt.Request> request = new AtomicReference<>();
		ExploitWarningPrompt prompt = pending -> {
			prompts.incrementAndGet();
			request.set(pending);
		};
		ChampionConsole console = new ChampionConsole(temp, ChampionConsoleTest::context,
			ActiveModuleRuntime.disabled(), command -> {}, () -> {}, () -> {}, prompt);
		// The reference payload is an ExploitResearchModule as well, so the
		// exploits view holds the workspace plus that payload.
		assertEquals(4, console.exploitModuleViews().size());
		assertTrue(console.exploitModuleViews().stream()
			.anyMatch(view -> view.id().equals("auxiliary/payloads/chat_reach_proof")));
		assertTrue(console.selectModuleFromUi("auxiliary/exploits/research_workspace"));
		assertTrue(console.activeModuleView().exploitResearch());
		assertTrue(console.setOptionFromUi("CONFIRM", "I_HAVE_AUTHORIZATION"));

		console.runFromUi();
		assertEquals(1, prompts.get());
		assertFalse(console.lines().stream().anyMatch(line -> line.text().contains("plan reviewed")));
		request.get().confirm().run();
		assertTrue(console.lines().stream().anyMatch(line -> line.text().contains("plan reviewed")));

		assertTrue(console.setOptionFromUi("CONFIRM", "I_HAVE_AUTHORIZATION"));
		console.runFromUi();
		assertEquals(2, prompts.get());
		request.get().cancel().run();
		assertTrue(console.lines().stream().anyMatch(line -> line.text().contains("cancelled at the legal-risk warning")));
	}

	@Test void runtimeModulesAreAtomicallyAddedReplacedAndRemoved() {
		ChampionConsole console = new ChampionConsole(temp, ChampionConsoleTest::context);
		assertEquals(21, console.moduleViews().size());

		String id = "auxiliary/custom/dynamic_example";
		console.replaceExternalModules(List.of(), List.of(new TestModule(id)));
		assertEquals(22, console.moduleViews().size());
		assertTrue(console.selectModuleFromUi(id));
		assertNotNull(console.activeModuleView());

		// A failed duplicate-id replacement must leave the live registry unchanged.
		assertThrows(IllegalArgumentException.class, () -> console.replaceExternalModules(List.of(id),
			List.of(new TestModule("auxiliary/scanner/server_fingerprint"))));
		assertTrue(console.selectModuleFromUi(id));

		// Replacing the same ID clears the old selected object before its loader closes.
		console.replaceExternalModules(List.of(id), List.of(new TestModule(id)));
		assertNull(console.activeModuleView());
		assertTrue(console.moduleViews().stream().anyMatch(module -> module.id().equals(id)));
		console.replaceExternalModules(List.of(id), List.of());
		assertEquals(21, console.moduleViews().size());
	}

	@Test void staleExploitWarningCannotRunAHotReloadedModuleWithTheSameId() {
		AtomicReference<ExploitWarningPrompt.Request> request = new AtomicReference<>();
		AtomicInteger runs = new AtomicInteger();
		ChampionConsole console = new ChampionConsole(temp, ChampionConsoleTest::context,
			ActiveModuleRuntime.disabled(), command -> {}, () -> {}, () -> {}, request::set);
		String id = "auxiliary/exploits/external_example";
		TestExploitModule original = new TestExploitModule(id, runs);
		console.replaceExternalModules(List.of(), List.of(original));
		assertTrue(console.selectModuleFromUi(id));
		console.runFromUi();
		assertNotNull(request.get());

		console.replaceExternalModules(List.of(id), List.of(new TestExploitModule(id, runs)));
		request.get().confirm().run();
		assertEquals(0, runs.get());
		assertTrue(console.lines().stream().anyMatch(line -> line.text().contains("selected module changed")));
	}

	@Test void verificationReleasesThePlanThatWasShownAndNothingElse() {
		// Every run of an exploit module goes through the legal-risk prompt first.
		ExploitWarningPrompt prompt = request -> request.confirm().run();
		ChampionConsole console = new ChampionConsole(temp, ChampionConsoleTest::context,
			ActiveModuleRuntime.disabled(), command -> {}, () -> {}, () -> {}, prompt);
		console.submit("use auxiliary/exploits/fixture_reproduction");
		console.submit("set MECHANISM skript_console");
		console.submit("set PAYLOAD console_injection");
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");

		// Verifying without planning has nothing to acknowledge, so nothing runs.
		console.submit("set ACTION VERIFY");
		console.submit("set ACK I_ACKNOWLEDGE_FIXTURE_EXECUTION");
		console.submit("run");
		assertTrue(console.lines().stream().anyMatch(line -> line.text().contains("No plan to acknowledge")),
			"an acknowledgement of nothing was accepted");

		// Plan, then verify: the run releases that plan. The confirmation phrase is
		// armed for one run and consumed by it, so it is re-set before each one.
		console.submit("set ACTION PLAN");
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");
		console.submit("run");
		assertTrue(console.lines().stream().anyMatch(line -> line.text().contains("Plan ready")),
			"no plan was produced");

		console.submit("set ACTION VERIFY");
		console.submit("set ACK I_ACKNOWLEDGE_FIXTURE_EXECUTION");
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");
		console.submit("run");
		assertTrue(console.lines().stream().anyMatch(line -> line.text().contains("Reproduced against a model")),
			"the acknowledged plan did not run");

		// Changing what would run invalidates the approval.
		console.submit("set MECHANISM conditional_events_console");
		console.submit("set CONFIRM I_HAVE_AUTHORIZATION");
		console.submit("run");
		assertTrue(console.lines().stream().anyMatch(line -> line.text().contains("No plan to acknowledge")),
			"a changed configuration inherited the previous approval");
	}

	@Test void minecraft263UsesOneBasedPrimaryAndSecondaryPointerButtons() {
		assertEquals(1, dev.champions.core.ui.MinecraftPointerButtons.PRIMARY);
		assertEquals(2, dev.champions.core.ui.MinecraftPointerButtons.SECONDARY);
	}

	@Test void exitCommandInvokesCloseHookOnceAndStaysHarmlessWithoutOne() {
		AtomicInteger closed = new AtomicInteger();
		ChampionConsole console = new ChampionConsole(temp, ChampionConsoleTest::context,
			ActiveModuleRuntime.disabled(), command -> {}, () -> {}, () -> {},
			ExploitWarningPrompt.unavailable(), null, closed::incrementAndGet);
		console.submit("exit");
		assertEquals(1, closed.get());
		assertTrue(console.lines().stream().anyMatch(line -> line.text().contains("Closing terminal.")));
		// The aliases close and quit hit the same hook.
		console.submit("close");
		console.submit("quit");
		assertEquals(3, closed.get());

		// Consoles built without a close hook (all shorter constructors) must not throw.
		ChampionConsole defaultConsole = new ChampionConsole(temp, ChampionConsoleTest::context);
		defaultConsole.submit("exit");
		assertTrue(defaultConsole.lines().stream().anyMatch(line -> line.text().contains("Closing terminal.")));
	}

	private static ModuleContext context() {
		return new ModuleContext(true, true, true, true, "singleplayer", "vanilla", "26.3", 1, 0, 1,
			"enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
	}

	private static class TestModule implements ResearchModule {
		private final String id;
		private TestModule(String id) { this.id = id; }
		@Override public String id() { return id; }
		@Override public String name() { return "Test external module"; }
		@Override public String description() { return "Test only."; }
		@Override public List<ModuleOption> options() { return List.of(); }
		@Override public ModuleResult run(ModuleContext context, Map<String, String> options) {
			return new ModuleResult("completed", List.of(new Finding(Finding.Severity.PASS, "Test", "ok", "")));
		}
	}

	private static final class TestExploitModule extends TestModule implements ExploitResearchModule {
		private final AtomicInteger runs;
		private TestExploitModule(String id, AtomicInteger runs) { super(id); this.runs = runs; }
		@Override public ModuleResult run(ModuleContext context, Map<String, String> options) {
			runs.incrementAndGet();
			return super.run(context, options);
		}
	}
}

