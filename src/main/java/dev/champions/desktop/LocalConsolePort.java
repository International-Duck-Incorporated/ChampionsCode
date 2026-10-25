package dev.champions.desktop;

import dev.champions.core.console.ChampionConsole;
import dev.champions.core.console.ConsoleLine;
import dev.champions.core.mc.ClientCapabilities;
import dev.champions.core.module.ModuleContext;
import dev.champions.surface.ConsoleBuffer;

import java.util.List;
import java.util.function.Supplier;

/**
 * Port backed by a console living in this process.
 *
 * <p>Two situations use it: standalone mode (no game at all, so only passive
 * modules can complete) and the demo harness (a simulated context whose target
 * is {@code *.invalid}, used to exercise the gated pipeline without touching
 * any real server).</p>
 */
public final class LocalConsolePort implements ConsolePort {
	private final ChampionConsole console;
	private final Supplier<ModuleContext> context;
	private final ConsoleBuffer buffer = new ConsoleBuffer();
	private final String detail;
	private volatile long seenSequence;
	private volatile String lastPrompt = "";

	public LocalConsolePort(ChampionConsole console, Supplier<ModuleContext> context, String detail) {
		this.console = console;
		this.context = context;
		this.detail = detail;
		synchronized (this) {
			seenSequence = 0L;
			sync();
		}
	}

	private synchronized void sync() {
		long total = console.lineSequence();
		List<ConsoleLine> lines = console.lines();
		long firstSequence = total - lines.size();
		if (seenSequence < firstSequence) {
			buffer.reset(lines);
		} else if (seenSequence < total) {
			buffer.appendAll(lines.subList((int) (seenSequence - firstSequence), lines.size()));
		}
		seenSequence = total;
		lastPrompt = console.prompt();
	}

	@Override
	public ConsoleBuffer buffer() {
		sync();
		return buffer;
	}

	@Override
	public String prompt() {
		return lastPrompt;
	}

	@Override
	public Status status() {
		ModuleContext snapshot = context.get();
		ChampionConsole.CurrentTargetView target = console.currentTargetView();
		return new Status(
			snapshot.connected(), snapshot.serverAddress(), snapshot.serverBrand(), snapshot.playerName(),
			target.available() && target.authorized(), target.available(), console.runningModuleId(), detail);
	}

	@Override
	public List<ModuleInfo> modules() {
		return console.moduleViews().stream()
			.map(view -> new ModuleInfo(view.id(), view.name(), view.description(), view.selected(), view.exploitResearch()))
			.toList();
	}

	@Override
	public ActiveInfo active() {
		ChampionConsole.ActiveModuleView view = console.activeModuleView();
		if (view == null) return null;
		return new ActiveInfo(view.id(), view.name(), view.description(), view.exploitResearch(),
			view.options().stream()
				.map(option -> new OptionInfo(option.name(), option.value(), option.defaultValue(),
					option.required(), option.secret(), option.configured(), option.description(), option.choices()))
				.toList());
	}

	@Override
	public List<String> history() {
		return console.history();
	}

	@Override
	public void submit(String line) {
		console.submit(line);
	}

	@Override
	public void selectModule(String id) {
		console.selectModuleFromUi(id);
	}

	@Override
	public void setOption(String name, String value) {
		console.setOptionFromUi(name, value);
	}

	@Override
	public void run() {
		console.runFromUi();
	}

	@Override
	public void clearSelection() {
		console.clearSelectionFromUi();
	}

	@Override
	public void setAuthorized(boolean authorized) {
		console.setCurrentTargetAuthorizedFromUi(authorized);
	}

	@Override
	public String capabilities() {
		var capabilities = console.capabilities();
		return capabilities == null ? ClientCapabilities.standalone("unknown").summary() : capabilities.summary();
	}

	@Override
	public String mode() {
		return context.get().connected() ? "STANDALONE" : "STANDALONE (offline)";
	}

	@Override
	public void close() {
		// Nothing to release: the console is owned by the caller.
	}
}
