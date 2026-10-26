package dev.champions.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the interfaces open, and what happens when something else changes that.
 *
 * <p>The reported bug was "F8 opens both windows no matter what": the mode was
 * written to {@code desktop.properties} by the external app's Settings tab, but
 * the running game kept the values it read at startup, so the choice only took
 * effect after a restart. These tests pin the file format, the mode semantics,
 * and the staleness check that makes an outside edit visible.</p>
 */
class DesktopSettingsTest {

	private static final String MODES = """
			# ChampionsCode interface mode.
			ui=both
			spawnOnOpen=true
			""";

	private static DesktopSettings loaded(Path directory, String contents) throws IOException {
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("desktop.properties"), contents, StandardCharsets.UTF_8);
		return new DesktopSettings(directory);
	}

	@Test void defaultsToBothWhenThereIsNoFile(@TempDir Path directory) {
		DesktopSettings settings = new DesktopSettings(directory);
		assertEquals(DesktopSettings.Mode.BOTH, settings.mode());
		assertTrue(settings.usesInGame());
		assertTrue(settings.usesExternal());
		assertTrue(settings.spawnOnOpen());
	}

	@Test void externalMeansTheInGameWindowsStayClosed(@TempDir Path directory) throws IOException {
		DesktopSettings settings = loaded(directory, "ui=external\nspawnOnOpen=true\n");
		assertTrue(settings.usesExternal());
		assertFalse(settings.usesInGame(), "the whole point of external is that no in-game window opens");
	}

	@Test void inGameMeansNoExternalProcess(@TempDir Path directory) throws IOException {
		DesktopSettings settings = loaded(directory, "ui=in-game\nspawnOnOpen=true\n");
		assertFalse(settings.usesExternal());
		assertTrue(settings.usesInGame());
	}

	@Test void anUnknownModeFallsBackToBoth(@TempDir Path directory) throws IOException {
		DesktopSettings settings = loaded(directory, "ui=sideways\n");
		assertEquals(DesktopSettings.Mode.BOTH, settings.mode());
	}

	@Test void anOutsideEditIsPickedUpWithoutARestart(@TempDir Path directory) throws IOException {
		DesktopSettings settings = loaded(directory, MODES);
		assertTrue(settings.usesInGame());

		Path file = directory.resolve("desktop.properties");
		Files.writeString(file, MODES.replace("ui=both", "ui=external"), StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));

		assertTrue(settings.refreshIfChanged(), "an outside write must be noticed");
		assertFalse(settings.usesInGame());
		assertTrue(settings.usesExternal());
	}

	@Test void ourOwnWriteIsNotMistakenForAnOutsideEdit(@TempDir Path directory) throws IOException {
		DesktopSettings settings = loaded(directory, MODES);
		settings.setMode(DesktopSettings.Mode.EXTERNAL);
		assertFalse(settings.refreshIfChanged(),
			"the file was rewritten by this object; reloading it would be wasted work, not a fix");
		assertEquals(DesktopSettings.Mode.EXTERNAL, settings.mode());
	}

	@Test void aChangedFileWithTheSameTimestampIsNoticedBySize(@TempDir Path directory) throws IOException {
		DesktopSettings settings = loaded(directory, MODES);
		Path file = directory.resolve("desktop.properties");
		FileTime stamp = Files.getLastModifiedTime(file);
		Files.writeString(file, MODES.replace("ui=both", "ui=external"), StandardCharsets.UTF_8);
		// Some file systems and some editors keep the timestamp; the size still moved.
		Files.setLastModifiedTime(file, stamp);
		assertTrue(settings.refreshIfChanged());
		assertEquals(DesktopSettings.Mode.EXTERNAL, settings.mode());
	}

	@Test void aMissingFileKeepsTheCurrentValues(@TempDir Path directory) throws IOException {
		DesktopSettings settings = loaded(directory, "ui=external\n");
		Files.delete(directory.resolve("desktop.properties"));
		assertFalse(settings.refreshIfChanged());
		assertTrue(settings.usesExternal(), "deleting the file does not silently change the mode");
	}

	@Test void theModeIsWrittenBackInTheFormTheFileUses(@TempDir Path directory) throws IOException {
		DesktopSettings settings = loaded(directory, MODES);
		settings.setMode(DesktopSettings.Mode.IN_GAME);
		String written = Files.readString(directory.resolve("desktop.properties"), StandardCharsets.UTF_8);
		assertTrue(written.contains("ui=in-game"), written);
		assertTrue(written.contains("# ChampionsCode interface mode."), "comments survive a write");
		DesktopSettings reread = new DesktopSettings(directory);
		assertEquals(DesktopSettings.Mode.IN_GAME, reread.mode());
	}

	@Test void disablingInGameWindowsDoesNotChangeTheMode(@TempDir Path directory) throws IOException {
		DesktopSettings settings = loaded(directory, "ui=both\n");
		settings.setInGameWindows(false);
		assertTrue(settings.usesExternal());
		assertFalse(settings.usesInGame(), "the independent switch must be honoured on its own");
	}
}
