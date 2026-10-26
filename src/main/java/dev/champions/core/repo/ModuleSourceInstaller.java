package dev.champions.core.repo;

/**
 * Writes a downloaded module file where the running front end will compile it.
 *
 * <p>The console owns the fetch, the hash check and the user-facing output; the
 * front end owns the folder and the compile. That split is why the desktop app
 * and the in-game client share one implementation of the {@code repo} command:
 * only this method differs between them.</p>
 */
@FunctionalInterface
public interface ModuleSourceInstaller {

	Outcome install(String moduleId, String fileName, String source);

	record Outcome(boolean ok, String message) {
		public static Outcome ok(String message) {
			return new Outcome(true, message);
		}

		public static Outcome failed(String message) {
			return new Outcome(false, message);
		}
	}

	static ModuleSourceInstaller disabled() {
		return (id, file, source) -> Outcome.failed(
				"This front end has no module folder to install into.");
	}
}
