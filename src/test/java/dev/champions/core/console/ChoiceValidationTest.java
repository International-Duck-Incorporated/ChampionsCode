package dev.champions.core.console;

import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.active.ActiveModuleRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Constrained options (choices) validate input in the console and via the UI seam. */
class ChoiceValidationTest {
	@TempDir Path temp;

	private static dev.champions.core.module.ModuleContext context() {
		return new dev.champions.core.module.ModuleContext(true, false, false, true, "play.example.com",
			"vanilla", "26.3", 771, 42L, 7, "enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
	}

	@Test void setRejectsValuesOutsideTheChoices() {
		ChampionConsole console = new ChampionConsole(temp, ChoiceValidationTest::context);
		console.submit("use auxiliary/scanner/chat_plugin_enumerator");
		console.submit("set MODE loud");
		assertTrue(console.lines().stream().anyMatch(line -> line.tone() == ConsoleLine.Tone.ERROR
			&& line.text().contains("Choose one of: SILENT, AGGRESSIVE")));
	}

	@Test void setAcceptsChoicesCaseInsensitivelyAndCanonicalizes() {
		ChampionConsole console = new ChampionConsole(temp, ChoiceValidationTest::context);
		console.submit("use auxiliary/scanner/chat_plugin_enumerator");
		console.submit("set MODE aggressive");
		assertEquals("AGGRESSIVE", console.activeModuleView().options().stream()
			.filter(option -> option.name().equals("MODE")).findFirst().orElseThrow().value());
	}

	@Test void uiCannotStoreInvalidChoiceValues() {
		ChampionConsole console = new ChampionConsole(temp, ChoiceValidationTest::context);
		console.submit("use auxiliary/scanner/chat_plugin_enumerator");
		assertTrue(console.setOptionFromUi("MODE", "SILENT"));
		assertFalse(console.setOptionFromUi("MODE", "nonsense"));
		assertEquals("SILENT", console.activeModuleView().options().stream()
			.filter(option -> option.name().equals("MODE")).findFirst().orElseThrow().value());
	}

	@Test void freeTextOptionsStillAcceptAnyValue() {
		ChampionConsole console = new ChampionConsole(temp, ChoiceValidationTest::context);
		console.submit("use auxiliary/scanner/chat_plugin_enumerator");
		assertTrue(console.setOptionFromUi("MAX_COMMANDS", "12"));
		assertEquals("12", console.activeModuleView().options().stream()
			.filter(option -> option.name().equals("MAX_COMMANDS")).findFirst().orElseThrow().value());
	}

	@Test void moduleOptionExposesChoiceHelpers() {
		dev.champions.core.module.ModuleOption option = new dev.champions.core.module.ModuleOption(
			"MODE", "silent", true, false, "mode", List.of("SILENT", "AGGRESSIVE"));
		assertTrue(option.constrained());
		assertEquals("AGGRESSIVE", option.resolveChoice("aggressive"));
		assertNull(option.resolveChoice("loud"));
		dev.champions.core.module.ModuleOption free = new dev.champions.core.module.ModuleOption(
			"HOST", "", true, false, "host");
		assertFalse(free.constrained());
		assertEquals(List.of(), free.choices());
	}
}
