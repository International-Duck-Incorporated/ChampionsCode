package dev.champions.core.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The watcher is what makes the drop folder plug-and-play, so these tests pin
 * down when a change is reported: once per settled state, never while files are
 * still being written, and never for files the module loader ignores.
 *
 * <p>The clock is driven by hand to model the runtime's real cadence, where the
 * folder is polled once a second and the quiet period is shorter than that.</p>
 */
class ModuleFolderWatcherTest {
	private static final long QUIET = 600L;
	private static final long POLL_INTERVAL = 1_000L;
	@TempDir Path temporary;
	private final AtomicLong clock = new AtomicLong(10_000L);

	private ModuleFolderWatcher watcherOn(Path folder) {
		return new ModuleFolderWatcher(folder, QUIET, clock::get);
	}

	/** Models the runtime loop: poll, advance one interval, poll, and so on. */
	private Optional<RuntimeModuleSourceSet.FolderFingerprint> pollLoop(ModuleFolderWatcher watcher, int polls) throws Exception {
		Optional<RuntimeModuleSourceSet.FolderFingerprint> last = Optional.empty();
		for (int i = 0; i < polls; i++) {
			clock.addAndGet(POLL_INTERVAL);
			last = watcher.poll();
			if (last.isPresent()) return last;
		}
		return last;
	}

	private RuntimeModuleSourceSet.FolderFingerprint settle(ModuleFolderWatcher watcher) throws Exception {
		return pollLoop(watcher, 4).orElseThrow(() -> new AssertionError("the drop folder change was never reported"));
	}

	private void assertNothingToDo(ModuleFolderWatcher watcher) throws Exception {
		assertTrue(pollLoop(watcher, 3).isEmpty(), "an unchanged folder must never be reported");
	}

	@Test void reportsTheInitialStateOnceItHasSettled() throws Exception {
		Path folder = Files.createDirectories(temporary.resolve("modules"));
		Files.writeString(folder.resolve("First.java"), "class First {}\n");
		ModuleFolderWatcher watcher = watcherOn(folder);

		assertEquals(RuntimeModuleSourceSet.fingerprint(folder), settle(watcher));
		assertNothingToDo(watcher);
	}

	@Test void reportsEachSettledChangeExactlyOnce() throws Exception {
		Path folder = Files.createDirectories(temporary.resolve("modules"));
		Files.writeString(folder.resolve("First.java"), "class First {}\n");
		ModuleFolderWatcher watcher = watcherOn(folder);
		settle(watcher);

		Files.writeString(folder.resolve("Second.java"), "class Second {}\n");
		RuntimeModuleSourceSet.FolderFingerprint change = settle(watcher);
		assertEquals(2, change.fileCount());
		assertFalse(change.empty());
		assertNothingToDo(watcher);
	}

	@Test void collapsesSeveralWritesIntoOneReport() throws Exception {
		Path folder = Files.createDirectories(temporary.resolve("modules"));
		Files.writeString(folder.resolve("Draft.java"), "class Draft {}\n");
		ModuleFolderWatcher watcher = watcherOn(folder);
		settle(watcher);

		// An editor writing a file in stages must not trigger a reload per write:
		// the second write arrives before the first one has settled.
		Files.writeString(folder.resolve("Draft.java"), "class Draft { int first = 1; }\n");
		clock.addAndGet(200L);
		assertTrue(watcher.poll().isEmpty(), "a file that is still being written must not be reported yet");
		Files.writeString(folder.resolve("Draft.java"), "class Draft { int first = 1; int second = 2; }\n");
		RuntimeModuleSourceSet.FolderFingerprint reported = settle(watcher);
		assertEquals(RuntimeModuleSourceSet.fingerprint(folder), reported);
		assertNothingToDo(watcher);
	}

	@Test void ignoresFilesTheModuleLoaderSkips() throws Exception {
		Path folder = Files.createDirectories(temporary.resolve("modules"));
		ModuleFolderWatcher watcher = watcherOn(folder);
		assertTrue(settle(watcher).empty());

		Files.createDirectories(folder.resolve(".build").resolve("generated"));
		Files.writeString(folder.resolve(".build").resolve("generated").resolve("Generated.java"), "class Generated {}\n");
		Files.writeString(folder.resolve(".notes.java.swp"), "editor scratch\n");
		Files.writeString(folder.resolve("readme.txt"), "notes\n");
		assertNothingToDo(watcher);
	}

	@Test void reportsAnEmptiedFolderSoLoadedModulesCanBeUnregistered() throws Exception {
		Path folder = Files.createDirectories(temporary.resolve("modules"));
		Path source = folder.resolve("Only.java");
		Files.writeString(source, "class Only {}\n");
		ModuleFolderWatcher watcher = watcherOn(folder);
		assertFalse(settle(watcher).empty());

		Files.delete(source);
		assertTrue(settle(watcher).empty());
	}

	@Test void reportsAFolderThatAppearsLater() throws Exception {
		Path folder = temporary.resolve("late-modules");
		ModuleFolderWatcher watcher = watcherOn(folder);
		assertTrue(settle(watcher).absent(), "a missing drop folder is reported as absent, not as an error");

		Files.createDirectories(folder.resolve("nested"));
		Files.writeString(folder.resolve("nested").resolve("Late.java"), "class Late {}\n");
		RuntimeModuleSourceSet.FolderFingerprint change = settle(watcher);
		assertFalse(change.absent());
		assertEquals(1, change.fileCount());
	}

	@Test void markHandledKeepsAManualActionFromBeingUndone() throws Exception {
		Path folder = Files.createDirectories(temporary.resolve("modules"));
		Files.writeString(folder.resolve("Only.java"), "class Only {}\n");
		ModuleFolderWatcher watcher = watcherOn(folder);
		settle(watcher);

		// `java modules unload` must stick until the user actually changes something.
		watcher.markHandled();
		assertNothingToDo(watcher);

		Files.writeString(folder.resolve("Only.java"), "class Only { int changed = 1; }\n");
		assertEquals(RuntimeModuleSourceSet.fingerprint(folder), settle(watcher));
	}

	@Test void invalidateReportsTheCurrentStateAgain() throws Exception {
		Path folder = Files.createDirectories(temporary.resolve("modules"));
		Files.writeString(folder.resolve("Only.java"), "class Only {}\n");
		ModuleFolderWatcher watcher = watcherOn(folder);
		settle(watcher);
		assertNothingToDo(watcher);

		// Used when plug-and-play is switched back on: the folder state is re-read at once.
		watcher.invalidate();
		clock.addAndGet(POLL_INTERVAL);
		assertEquals(RuntimeModuleSourceSet.fingerprint(folder), watcher.poll().orElseThrow());
	}

	@Test void resetForgetsEverything() throws Exception {
		Path folder = Files.createDirectories(temporary.resolve("modules"));
		Files.writeString(folder.resolve("Only.java"), "class Only {}\n");
		ModuleFolderWatcher watcher = watcherOn(folder);
		settle(watcher);

		watcher.reset();
		assertTrue(settle(watcher).fileCount() == 1, "after a reset the folder is re-reported from scratch");
	}
}
