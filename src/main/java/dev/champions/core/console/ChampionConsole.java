package dev.champions.core.console;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleRegistry;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.module.active.ActiveResearchModule;
import dev.champions.core.script.ChampionScriptEngine;
import dev.champions.core.script.ScriptRepository;
import dev.champions.core.security.AuditLog;
import dev.champions.core.security.AuthorizationPolicy;
import dev.champions.core.security.AuthorizationPolicy.AuthorizationDecision;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Stateful msfconsole-inspired command shell for defensive, authorized modules. */
public final class ChampionConsole {
	private static final int MAX_LINES = 1_000;
	private static final int MAX_HISTORY = 100;
	private final ModuleRegistry registry = new ModuleRegistry();
	private final AuthorizationPolicy authorization;
	private final AuditLog audit;
	private final ScriptRepository scripts;
	private final ChampionScriptEngine scriptEngine;
	private final Supplier<ModuleContext> contextSupplier;
	private final ActiveModuleRuntime activeRuntime;
	private final List<ConsoleLine> lines = new ArrayList<>();
	private final List<String> history = new ArrayList<>();
	private ResearchModule activeModule;
	private String runningModuleId;
	private final Map<String, String> activeOptions = new LinkedHashMap<>();

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier) {
		this(configDirectory, contextSupplier, ActiveModuleRuntime.disabled());
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier, ActiveModuleRuntime activeRuntime) {
		this.contextSupplier = contextSupplier;
		this.activeRuntime = activeRuntime;
		this.authorization = new AuthorizationPolicy(configDirectory);
		this.audit = new AuditLog(configDirectory);
		this.scripts = new ScriptRepository(configDirectory);
		this.scriptEngine = new ChampionScriptEngine(registry);
		banner();
	}

	public synchronized void submit(String rawInput) {
		String input = rawInput == null ? "" : rawInput.strip();
		if (input.isEmpty()) return;
		addHistory(input);
		add(ConsoleLine.accent(prompt() + " > " + redactForEcho(input)));

		List<String> args;
		try { args = CommandTokenizer.tokenize(input); }
		catch (IllegalArgumentException e) { add(ConsoleLine.error(e.getMessage())); return; }
		if (args.isEmpty()) return;
		String command = args.getFirst().toLowerCase(Locale.ROOT);
		try {
			switch (command) {
				case "help", "?" -> help();
				case "banner" -> banner();
				case "clear" -> lines.clear();
				case "show" -> show(args);
				case "search" -> search(args);
				case "use" -> use(args);
				case "back" -> back();
				case "info" -> info(args);
				case "set" -> set(args);
				case "unset" -> unset(args);
				case "run", "exploit" -> runActive();
				case "status" -> status();
				case "reload" -> reload();
				case "volt", "script" -> volt(input, args);
				default -> add(ConsoleLine.error("Unknown command: " + command + ". Type help."));
			}
		} catch (RuntimeException e) {
			add(ConsoleLine.error("Command failed safely: " + safeMessage(e)));
		}
	}

	public synchronized List<ConsoleLine> lines() { return List.copyOf(lines); }
	public synchronized List<String> history() { return List.copyOf(history); }
	public synchronized String prompt() { return activeModule == null ? "champions" : "champions(" + activeModule.id() + ")"; }
	public Path auditFile() { return audit.file(); }
	public Path allowlistFile() { return authorization.allowlistFile(); }
	public Path scriptsDirectory() { return scripts.directory(); }

	private void help() {
		add(ConsoleLine.accent("Core commands"));
		add(ConsoleLine.info("  show modules | search <text> | use <module> | info | back"));
		add(ConsoleLine.info("  show options | set <name> <value> | unset <name> | run"));
		add(ConsoleLine.info("  status | show targets | reload | clear | banner"));
		add(ConsoleLine.accent("Volt-style sandbox"));
		add(ConsoleLine.info("  volt list | volt show <name> | volt run <name>"));
		add(ConsoleLine.info("  volt eval <statement; statement> | volt help"));
		add(ConsoleLine.warning("Modules are current-connection-only, allowlist-gated, and audited. Active modules state their one bounded action."));
	}

	private void banner() {
		add(ConsoleLine.accent("   CHAMPIONS MOD  // defensive research console"));
		add(ConsoleLine.info("   Minecraft 26.3 | client-only | Fabric"));
		add(ConsoleLine.muted("   F8 opens this console. Type help to begin."));
		add(ConsoleLine.warning("   Test only systems you own or have explicit permission to assess."));
	}

	private void show(List<String> args) {
		if (args.size() < 2) { add(ConsoleLine.error("Usage: show modules|options|targets")); return; }
		switch (args.get(1).toLowerCase(Locale.ROOT)) {
			case "modules" -> {
				add(ConsoleLine.accent("Available modules"));
				for (ResearchModule module : registry.all()) add(ConsoleLine.info("  " + module.id() + "  -  " + module.name()));
			}
			case "options" -> showOptions();
			case "targets" -> {
				add(ConsoleLine.accent("Exact authorized targets"));
				authorization.allowedTargets().stream().sorted().forEach(t -> add(ConsoleLine.info("  " + t)));
				add(ConsoleLine.muted("File: " + authorization.allowlistFile()));
			}
			default -> add(ConsoleLine.error("Unknown show type: " + args.get(1)));
		}
	}

	private void search(List<String> args) {
		if (args.size() < 2) { add(ConsoleLine.error("Usage: search <text>")); return; }
		String query = String.join(" ", args.subList(1, args.size())).toLowerCase(Locale.ROOT);
		int count = 0;
		for (ResearchModule module : registry.all()) {
			if ((module.id() + " " + module.name() + " " + module.description()).toLowerCase(Locale.ROOT).contains(query)) {
				add(ConsoleLine.info("  " + module.id() + "  -  " + module.name()));
				count++;
			}
		}
		if (count == 0) add(ConsoleLine.muted("No modules matched."));
	}

	private void use(List<String> args) {
		if (args.size() != 2) { add(ConsoleLine.error("Usage: use <module>")); return; }
		Optional<ResearchModule> found = registry.find(args.get(1));
		if (found.isEmpty()) { add(ConsoleLine.error("Module not found: " + args.get(1))); return; }
		activeModule = found.get();
		activeOptions.clear();
		for (ModuleOption option : activeModule.options()) activeOptions.put(option.name(), option.defaultValue());
		add(ConsoleLine.success("Using " + activeModule.id()));
		add(ConsoleLine.muted("Type info or show options."));
	}

	private void back() {
		if (activeModule == null) add(ConsoleLine.muted("No active module."));
		else {
			activeOptions.clear();
			activeModule = null;
			add(ConsoleLine.info("Returned to the main console."));
		}
	}

	private void info(List<String> args) {
		ResearchModule module = activeModule;
		if (args.size() > 1) module = registry.find(args.get(1)).orElse(null);
		if (module == null) { add(ConsoleLine.error("Select a module with use <module>, or use info <module>.")); return; }
		add(ConsoleLine.accent(module.id()));
		add(ConsoleLine.info(module.name()));
		add(ConsoleLine.info(module.description()));
		if (module instanceof ActiveResearchModule) {
			add(ConsoleLine.warning("Active behavior is mode-dependent: only filtered, advertised blank/version/v paths; bounded capture; no bypasses."));
		} else {
			add(ConsoleLine.muted("Network behavior: none; inspects an immutable client snapshot only."));
		}
	}

	private void set(List<String> args) {
		if (activeModule == null) { add(ConsoleLine.error("Select a module first.")); return; }
		if (args.size() < 3) { add(ConsoleLine.error("Usage: set <option> <value>")); return; }
		String name = args.get(1).toUpperCase(Locale.ROOT);
		if (!activeOptions.containsKey(name)) { add(ConsoleLine.error("Unknown option: " + name)); return; }
		String value = String.join(" ", args.subList(2, args.size()));
		activeOptions.put(name, value);
		boolean secret = activeModule.options().stream().anyMatch(o -> o.name().equals(name) && o.secret());
		add(ConsoleLine.success(name + " => " + (secret ? "<armed for one run>" : value)));
	}

	private void unset(List<String> args) {
		if (activeModule == null || args.size() != 2) { add(ConsoleLine.error("Usage: unset <option> (with a selected module)")); return; }
		String name = args.get(1).toUpperCase(Locale.ROOT);
		ModuleOption option = activeModule.options().stream().filter(o -> o.name().equals(name)).findFirst().orElse(null);
		if (option == null) { add(ConsoleLine.error("Unknown option: " + name)); return; }
		activeOptions.put(name, option.defaultValue());
		add(ConsoleLine.info("Unset " + name));
	}

	private void showOptions() {
		if (activeModule == null) { add(ConsoleLine.error("Select a module first.")); return; }
		add(ConsoleLine.accent("Options for " + activeModule.id()));
		for (ModuleOption option : activeModule.options()) {
			String value = activeOptions.getOrDefault(option.name(), "");
			if (option.secret() && !value.isBlank()) value = "<armed for one run>";
			add(ConsoleLine.info(String.format(Locale.ROOT, "  %-14s %-18s %s%s", option.name(), value.isBlank() ? "<unset>" : value, option.description(), option.required() ? " [required]" : "")));
		}
	}

	private void runActive() {
		if (activeModule == null) { add(ConsoleLine.error("Select a module first.")); return; }
		if (runningModuleId != null) { add(ConsoleLine.error("Active module already running: " + runningModuleId)); return; }

		ResearchModule module = activeModule;
		ModuleContext context = contextSupplier.get();
		String confirm = activeOptions.getOrDefault("CONFIRM", "");
		AuthorizationDecision decision = authorization.authorize(context.connected(), context.serverAddress(), activeOptions.get("RHOST"), confirm);
		activeOptions.put("CONFIRM", ""); // one-run secret, consumed even on denial
		if (module.requiresAuthorization() && !decision.allowed()) {
			add(ConsoleLine.error("Authorization gate denied: " + decision.reason()));
			audit.record("module_run", AuthorizationPolicy.normalizeHost(context.serverAddress()), module.id(), "denied", decision.reason());
			return;
		}

		Map<String, String> options = Map.copyOf(activeOptions);
		long started = System.nanoTime();
		boolean activeAction = module instanceof ActiveResearchModule active && active.performsActiveAction(options);
		add(ConsoleLine.accent("[*] Running " + module.id() + " against current connection"));
		try {
			if (module instanceof ActiveResearchModule active) {
				if (activeAction) {
					runningModuleId = module.id();
					add(ConsoleLine.warning("[>] AGGRESSIVE action authorized; probes are sequential, filtered, and rate-limited…"));
					audit.record("module_run", decision.normalizedTarget(), module.id(), "started", "Bounded active command probes started");
				} else {
					add(ConsoleLine.muted("[*] SILENT mode selected; no command will be sent."));
				}
				active.runActive(context, options, activeRuntime).whenComplete((result, error) ->
					finishRun(module, decision.normalizedTarget(), started, result, error, activeAction));
			} else {
				finishRun(module, decision.normalizedTarget(), started, module.run(context, options), null, false);
			}
		} catch (RuntimeException e) {
			finishRun(module, decision.normalizedTarget(), started, null, e, activeAction);
		}
	}

	private synchronized void finishRun(ResearchModule module, String target, long started, ModuleResult result, Throwable error, boolean wasActive) {
		if (wasActive) runningModuleId = null;
		if (error != null) {
			Throwable cause = error.getCause() == null ? error : error.getCause();
			add(ConsoleLine.error("Module stopped safely: " + safeMessage(cause)));
			audit.record("module_run", target, module.id(), "error", safeMessage(cause));
			return;
		}
		for (Finding finding : result.findings()) renderFinding(finding);
		long elapsed = (System.nanoTime() - started) / 1_000_000L;
		add(ConsoleLine.success("[+] " + result.summary() + " (" + elapsed + " ms)"));
		audit.record("module_run", target, module.id(), "completed", result.summary());
	}

	private void status() {
		ModuleContext c = contextSupplier.get();
		add(ConsoleLine.accent("Client runtime status"));
		add(ConsoleLine.info("  connected=" + c.connected() + " target=" + c.serverAddress()));
		add(ConsoleLine.info("  brand=" + c.serverBrand() + " version=" + c.advertisedVersion()));
		add(ConsoleLine.info("  player=" + c.playerName() + " position=" + c.playerPosition()));
		add(ConsoleLine.info("  active_job=" + (runningModuleId == null ? "none" : runningModuleId)));
		add(ConsoleLine.muted("  audit=" + audit.file()));
	}

	private void reload() {
		try {
			authorization.reload();
			add(ConsoleLine.success("Reloaded authorization policy. Scripts are read fresh on each run."));
		} catch (IOException e) {
			add(ConsoleLine.error("Reload failed: " + e.getMessage()));
		}
	}

	private void volt(String raw, List<String> args) {
		if (args.size() < 2 || args.get(1).equalsIgnoreCase("help")) {
			add(ConsoleLine.accent("Volt-style ChampionScript runtime"));
			add(ConsoleLine.info("  volt list | show <name> | run <name> | eval <code>"));
			add(ConsoleLine.muted("Read-only API: runtime metadata, variables, assertions, and console output."));
			add(ConsoleLine.warning("No JVM/native execution, files, network, packets, chat, reflection, or game mutation."));
			return;
		}
		switch (args.get(1).toLowerCase(Locale.ROOT)) {
			case "list" -> {
				try {
					add(ConsoleLine.accent("ChampionScript files in " + scripts.directory()));
					scripts.list().forEach(name -> add(ConsoleLine.info("  " + name)));
				} catch (IOException e) { add(ConsoleLine.error(e.getMessage())); }
			}
			case "show" -> {
				if (args.size() != 3) { add(ConsoleLine.error("Usage: volt show <name>")); return; }
				try {
					add(ConsoleLine.accent("--- " + args.get(2) + ".champ ---"));
					for (String line : scripts.read(args.get(2)).lines().toList()) add(ConsoleLine.muted(line));
				} catch (IOException e) { add(ConsoleLine.error(e.getMessage())); }
			}
			case "run" -> {
				if (args.size() != 3) { add(ConsoleLine.error("Usage: volt run <name>")); return; }
				try { runScript(args.get(2), scripts.read(args.get(2))); }
				catch (IOException e) { add(ConsoleLine.error(e.getMessage())); }
			}
			case "eval" -> {
				int at = raw.toLowerCase(Locale.ROOT).indexOf("eval");
				String source = at < 0 ? "" : raw.substring(at + 4).strip();
				if (source.isEmpty()) { add(ConsoleLine.error("Usage: volt eval <code>")); return; }
				runScript("<eval>", source);
			}
			default -> add(ConsoleLine.error("Unknown volt command. Try volt help."));
		}
	}

	private void runScript(String name, String source) {
		add(ConsoleLine.accent("[volt] running " + name + " in the read-only sandbox"));
		var result = scriptEngine.execute(source, contextSupplier.get());
		result.output().forEach(this::add);
		if (result.success()) add(ConsoleLine.success("[volt] completed " + result.statementsExecuted() + " statement(s)"));
		else add(ConsoleLine.error("[volt] stopped: " + result.message()));
		audit.record("script_run", "client", name, result.success() ? "completed" : "error", result.message());
	}

	private void renderFinding(Finding finding) {
		String prefix = switch (finding.severity()) {
			case PASS -> "[PASS]";
			case INFO -> "[INFO]";
			case LOW -> "[LOW]";
			case MEDIUM -> "[MEDIUM]";
			case HIGH -> "[HIGH]";
		};
		ConsoleLine line = switch (finding.severity()) {
			case PASS -> ConsoleLine.success(prefix + " " + finding.title() + ": " + finding.detail());
			case INFO -> ConsoleLine.info(prefix + " " + finding.title() + ": " + finding.detail());
			case LOW, MEDIUM -> ConsoleLine.warning(prefix + " " + finding.title() + ": " + finding.detail());
			case HIGH -> ConsoleLine.error(prefix + " " + finding.title() + ": " + finding.detail());
		};
		add(line);
		if (!finding.recommendation().isBlank()) add(ConsoleLine.muted("       Recommendation: " + finding.recommendation()));
	}

	private void add(ConsoleLine line) {
		for (String textLine : line.text().split("\\n", -1)) lines.add(new ConsoleLine(line.tone(), limit(textLine, 500)));
		if (lines.size() > MAX_LINES) lines.subList(0, lines.size() - MAX_LINES).clear();
	}

	private void addHistory(String input) {
		// Secret option values are neither echoed nor retained in command history.
		if (input.matches("(?i)^set\\s+confirm\\s+.+$")) return;
		if (history.isEmpty() || !history.getLast().equals(input)) history.add(input);
		if (history.size() > MAX_HISTORY) history.removeFirst();
	}

	private static String redactForEcho(String input) {
		return input.replaceFirst("(?i)^(set\\s+confirm\\s+).+$", "$1<redacted>");
	}

	private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }
	private static String safeMessage(Throwable error) {
		String text = error.getMessage();
		return text == null || text.isBlank() ? error.getClass().getSimpleName() : limit(text.replace('\n', ' '), 240);
	}
}
