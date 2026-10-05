package dev.champions.core.java;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Change detector for a recursive module drop folder.
 *
 * <p>The watcher is deliberately dumb: it polls a cheap metadata fingerprint of
 * the folder and reports that the folder has settled into a new state. It never
 * reads source content, compiles, or executes anything, so a caller can poll it
 * as often as once per game tick without measuring hashing cost.</p>
 *
 * <p>Two rules keep the plug-and-play workflow from firing while a file is
 * still being written:</p>
 * <ul>
 *   <li>a change is only reported after the folder has been unchanged for the
 *       quiet period, so a save that takes several writes is observed once; and</li>
 *   <li>a fingerprint equal to the last reported one is never reported twice.</li>
 * </ul>
 *
 * <p>Clipboard-style workflows (create, write, rename, delete) therefore produce
 * one reload each, not one per file-system event.</p>
 */
public final class ModuleFolderWatcher {
	/** How long the folder must stay unchanged before a change is reported. */
	public static final long DEFAULT_QUIET_PERIOD_MILLIS = 600L;

	private final Path folder;
	private final long quietPeriodMillis;
	private final LongSupplier clock;
	private RuntimeModuleSourceSet.FolderFingerprint observed;
	private RuntimeModuleSourceSet.FolderFingerprint reported;
	private long observedAtMillis;
	private boolean pending;

	public ModuleFolderWatcher(Path folder) {
		this(folder, DEFAULT_QUIET_PERIOD_MILLIS, System::currentTimeMillis);
	}

	/** Test seam: a deterministic clock keeps the quiet-period logic verifiable. */
	ModuleFolderWatcher(Path folder, long quietPeriodMillis, LongSupplier clock) {
		this.folder = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
		this.quietPeriodMillis = Math.max(0L, quietPeriodMillis);
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public Path folder() { return folder; }

	public long quietPeriodMillis() { return quietPeriodMillis; }

	/**
	 * Polls the drop folder once.
	 *
	 * @return the new fingerprint when the folder content changed and has been
	 *         stable for the quiet period; empty when nothing needs to happen.
	 */
	public synchronized Optional<RuntimeModuleSourceSet.FolderFingerprint> poll() throws IOException {
		RuntimeModuleSourceSet.FolderFingerprint current = RuntimeModuleSourceSet.fingerprint(folder);
		long now = clock.getAsLong();
		if (observed == null || !current.equals(observed)) {
			observed = current;
			observedAtMillis = now;
			pending = true;
		}
		if (reported != null && current.equals(reported)) {
			pending = false;
			return Optional.empty();
		}
		if (!pending || now - observedAtMillis < quietPeriodMillis) return Optional.empty();
		pending = false;
		reported = current;
		return Optional.of(current);
	}

	/**
	 * True when the folder does not match the last reported state, e.g. a change
	 * arrived while a module run was in the way. Unlike {@link #poll()} this
	 * reports nothing and keeps the pending state for the next poll.
	 */
	public synchronized boolean hasUnreportedChange() throws IOException {
		RuntimeModuleSourceSet.FolderFingerprint current = RuntimeModuleSourceSet.fingerprint(folder);
		return reported == null || !current.equals(reported);
	}

	/**
	 * Records that the caller handled the folder in its current state, so the
	 * next change is reported even if a manual command already loaded it.
	 */
	public synchronized void markHandled() throws IOException {
		RuntimeModuleSourceSet.FolderFingerprint current = RuntimeModuleSourceSet.fingerprint(folder);
		observed = current;
		reported = current;
		pending = false;
	}

	/**
	 * Forgets the last reported state, so the next {@link #poll()} reports the
	 * current folder again. Used when the caller wants an immediate reconcile,
	 * such as re-enabling plug-and-play.
	 */
	public synchronized void invalidate() {
		reported = null;
		pending = true;
		observedAtMillis = clock.getAsLong() - quietPeriodMillis;
	}

	/** Drops all remembered state, e.g. while the caller is paused or closed. */
	public synchronized void reset() {
		observed = null;
		reported = null;
		pending = false;
		observedAtMillis = 0L;
	}
}
