package dev.champions.api.java;

/** Command bridge implemented by the client-side runtime Java manager. */
@FunctionalInterface
public interface RuntimeJavaCommands {
	void submit(String command);

	static RuntimeJavaCommands disabled() {
		return command -> {};
	}
}
