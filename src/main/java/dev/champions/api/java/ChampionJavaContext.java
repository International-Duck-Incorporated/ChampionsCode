package dev.champions.api.java;

import dev.champions.core.module.ModuleContext;

import java.nio.file.Path;

/** Convenience services for runtime Java addons. This interface is not a sandbox. */
public interface ChampionJavaContext {
	ModuleContext snapshot();

	Path workspace();

	String sourceHash();

	void info(String message);

	void warning(String message);

	void error(String message);

	void executeOnClient(Runnable action);

	void onStop(Runnable cleanup);
}
