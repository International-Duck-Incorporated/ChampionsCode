package dev.champions.core.console;

import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.active.ActiveModuleRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
		assertEquals(14, console.moduleViews().size());
		List<String> ids = console.moduleViews().stream().map(ChampionConsole.ModuleView::id).toList();
		assertEquals(ids.stream().sorted().toList(), ids);
		assertTrue(console.selectModuleFromUi("auxiliary/scanner/chat_plugin_enumerator"));
		assertNotNull(console.activeModuleView());
		assertTrue(console.activeModuleView().options().stream().anyMatch(option -> option.name().equals("MODE")));
		assertTrue(console.setOptionFromUi("MODE", "aggressive"));
		assertEquals("aggressive", console.activeModuleView().options().stream()
			.filter(option -> option.name().equals("MODE")).findFirst().orElseThrow().value());
		console.submit("deauthorize current");
		assertFalse(console.currentTargetView().authorized());
		console.submit("authorize current");
		assertTrue(console.currentTargetView().authorized());
	}

	private static ModuleContext context() {
		return new ModuleContext(true, true, true, true, "singleplayer", "vanilla", "26.3", 1, 0, 1,
			"enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
	}
}
