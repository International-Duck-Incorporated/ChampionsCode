package dev.champions.client.java;

import dev.champions.api.java.ChampionJavaContext;
import dev.champions.core.console.ConsoleLine;
import dev.champions.core.module.ModuleContext;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class ChampionJavaContextImpl implements ChampionJavaContext {
	private final Supplier<ModuleContext> snapshotSupplier;
	private final Path workspace;
	private final String sourceHash;
	private final Consumer<ConsoleLine> output;
	private final List<Runnable> cleanup = new ArrayList<>();

	ChampionJavaContextImpl(Supplier<ModuleContext> snapshotSupplier, Path workspace, String sourceHash, Consumer<ConsoleLine> output) {
		this.snapshotSupplier = snapshotSupplier;
		this.workspace = workspace;
		this.sourceHash = sourceHash;
		this.output = output;
	}

	@Override public ModuleContext snapshot() { return snapshotSupplier.get(); }
	@Override public Path workspace() { return workspace; }
	@Override public String sourceHash() { return sourceHash; }
	@Override public void info(String message) { output.accept(ConsoleLine.info("[java] " + safe(message))); }
	@Override public void warning(String message) { output.accept(ConsoleLine.warning("[java] " + safe(message))); }
	@Override public void error(String message) { output.accept(ConsoleLine.error("[java] " + safe(message))); }
	@Override public void executeOnClient(Runnable action) { Minecraft.getInstance().execute(action); }

	@Override public synchronized void onStop(Runnable action) {
		if (action == null) throw new IllegalArgumentException("cleanup action cannot be null");
		cleanup.add(action);
	}

	synchronized List<Throwable> runCleanup() {
		List<Throwable> failures = new ArrayList<>();
		for (int i = cleanup.size() - 1; i >= 0; i--) {
			try { cleanup.get(i).run(); }
			catch (Throwable error) { failures.add(error); }
		}
		cleanup.clear();
		return failures;
	}

	private static String safe(String value) {
		if (value == null) return "";
		String cleaned = value.replace('\r', ' ').replace('\n', ' ');
		return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500) + "...";
	}
}
