package dev.champions.core.update;

import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import dev.champions.core.console.ExploitWarningPrompt;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.active.ActiveModuleRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class UpdateConsoleCommandTest {
	@TempDir Path temp;

	private static final UpdateCheck NEWER = new UpdateCheck("0.1.0-beta.1", "0.1.0-beta.2",
		"https://gitlab.com/international-duck-incorporated/ChampionsCode/-/releases/v0.1.0-beta.2",
		"https://gitlab.com/x/downloads/champions-mod-0.1.0-beta.2.jar",
		"https://gitlab.com/x/downloads/SHA256SUMS.txt");

	private final AtomicReference<UpdateCheck> downloaded = new AtomicReference<>();

	private ChampionConsole console(Function<UpdateCheck, UpdateResult> downloader) {
		UpdateService service = new UpdateService() {
			@Override public java.util.concurrent.CompletionStage<Optional<UpdateCheck>> checkLatest() {
				return CompletableFuture.completedFuture(Optional.of(NEWER));
			}

			@Override public java.util.concurrent.CompletionStage<UpdateResult> download(UpdateCheck check) {
				downloaded.set(check);
				return CompletableFuture.completedFuture(downloader.apply(check));
			}
		};
		return new ChampionConsole(temp, UpdateConsoleCommandTest::context, ActiveModuleRuntime.disabled(),
			command -> {}, () -> {}, () -> {}, ExploitWarningPrompt.unavailable(), service);
	}

	private static ModuleContext context() {
		return new ModuleContext(true, true, true, true, "singleplayer", "vanilla", "26.3", 1, 0, 1,
			"enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
	}

	private static List<String> texts(ChampionConsole console) {
		return console.lines().stream().map(ConsoleLine::text).toList();
	}

	@Test void checkReportsNewerReleaseWithDownloadHint() {
		ChampionConsole console = console(check -> UpdateResult.failure("unused"));
		console.submit("update check");
		List<String> lines = texts(console);
		assertTrue(lines.stream().anyMatch(line -> line.contains("0.1.0-beta.2 is available")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("update download 0.1.0-beta.2")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("releases/v0.1.0-beta.2")));
	}

	@Test void checkReportsUpToDate() {
		UpdateCheck current = new UpdateCheck("0.1.0-beta.2", "0.1.0-beta.2", "https://gitlab.example/r", "", "");
		UpdateService service = new UpdateService() {
			@Override public java.util.concurrent.CompletionStage<Optional<UpdateCheck>> checkLatest() {
				return CompletableFuture.completedFuture(Optional.of(current));
			}

			@Override public java.util.concurrent.CompletionStage<UpdateResult> download(UpdateCheck check) {
				return CompletableFuture.completedFuture(UpdateResult.failure("not invoked"));
			}
		};
		ChampionConsole console = new ChampionConsole(temp, UpdateConsoleCommandTest::context, ActiveModuleRuntime.disabled(),
			command -> {}, () -> {}, () -> {}, ExploitWarningPrompt.unavailable(), service);
		console.submit("update check");
		assertTrue(texts(console).stream().anyMatch(line -> line.contains("up to date")));
	}

	@Test void checkFailureIsReportedAsWarning() {
		UpdateService service = new UpdateService() {
			@Override public java.util.concurrent.CompletionStage<Optional<UpdateCheck>> checkLatest() {
				return CompletableFuture.failedFuture(new java.io.IOException("offline"));
			}

			@Override public java.util.concurrent.CompletionStage<UpdateResult> download(UpdateCheck check) {
				return CompletableFuture.completedFuture(UpdateResult.failure("not invoked"));
			}
		};
		ChampionConsole console = new ChampionConsole(temp, UpdateConsoleCommandTest::context, ActiveModuleRuntime.disabled(),
			command -> {}, () -> {}, () -> {}, ExploitWarningPrompt.unavailable(), service);
		console.submit("update check");
		assertTrue(texts(console).stream().anyMatch(line -> line.contains("Update check failed")));
	}

	@Test void downloadRequiresAVersionArgument() {
		ChampionConsole console = console(check -> UpdateResult.failure("unused"));
		console.submit("update download");
		assertTrue(texts(console).stream().anyMatch(line -> line.contains("Usage: update download <version>")));
		assertNull(downloaded.get());
	}

	@Test void downloadRejectsAnythingButTheLatestRelease() {
		ChampionConsole console = console(check -> UpdateResult.success("should not happen", "/tmp/x.jar"));
		console.submit("update download 0.0.1");
		assertTrue(texts(console).stream().anyMatch(line -> line.contains("Only the latest release")));
		assertNull(downloaded.get());
	}

	@Test void downloadVerifiesAndWritesThenInstructsRestart() throws Exception {
		ChampionConsole console = console(check -> UpdateResult.success(
			"Verified and saved champions-mod-0.1.0-beta.2.jar (SHA-256 abc).", temp.resolve("mods/champions-mod-0.1.0-beta.2.jar").toString()));
		console.submit("update download 0.1.0-beta.2");
		assertEquals(NEWER, downloaded.get());
		List<String> lines = texts(console);
		assertTrue(lines.stream().anyMatch(line -> line.contains("Verified and saved")));
		assertTrue(lines.stream().anyMatch(line -> line.contains("restart Minecraft")));
		String audit = Files.readString(console.auditFile());
		assertTrue(audit.contains("\"event\": \"update_download\"") || audit.contains("\"event\":\"update_download\""));
	}

	@Test void downloadFailureIsReportedWithoutRestartHint() {
		ChampionConsole console = console(check -> UpdateResult.failure("Checksum mismatch for champions-mod-0.1.0-beta.2.jar."));
		console.submit("update download 0.1.0-beta.2");
		List<String> lines = texts(console);
		assertTrue(lines.stream().anyMatch(line -> line.contains("Checksum mismatch")));
		assertFalse(lines.stream().anyMatch(line -> line.contains("restart Minecraft")));
	}

	@Test void unknownUpdateActionPrintsUsage() {
		ChampionConsole console = console(check -> UpdateResult.failure("unused"));
		console.submit("update frobnicate");
		assertTrue(texts(console).stream().anyMatch(line -> line.contains("Unknown update action")));
	}
}
