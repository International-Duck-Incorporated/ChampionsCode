package dev.champions.core.console;

import dev.champions.api.java.RuntimeJavaCommands;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleRegistry;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.module.active.ActiveResearchModule;
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
	private final Supplier<ModuleContext> contextSupplier;
	private final ActiveModuleRuntime activeRuntime;
	private final RuntimeJavaCommands runtimeJava;
	private final Runnable openClickGui;
	private final List<ConsoleLine> lines = new ArrayList<>();
	private final List<String> history = new ArrayList<>();
	private ResearchModule activeModule;
	private String runningModuleId;
	private final Map<String, String> activeOptions = new LinkedHashMap<>();

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier) {
		this(configDirectory, contextSupplier, ActiveModuleRuntime.disabled(), RuntimeJavaCommands.disabled(), () -> {});
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier, ActiveModuleRuntime activeRuntime) {
		this(configDirectory, contextSupplier, activeRuntime, RuntimeJavaCommands.disabled(), () -> {});
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier,
		ActiveModuleRuntime activeRuntime, RuntimeJavaCommands runtimeJava) {
		this(configDirectory, contextSupplier, activeRuntime, runtimeJava, () -> {});
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier,
		ActiveModuleRuntime activeRuntime, RuntimeJavaCommands runtimeJava, Runnable openClickGui) {
		this.contextSupplier = contextSupplier;
		this.activeRuntime = activeRuntime;
		this.runtimeJava = runtimeJava;
		this.openClickGui = openClickGui;
		this.authorization = new AuthorizationPolicy(configDirectory);
		this.audit = new AuditLog(configDirectory);
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
				case "help", "?" -> help(args);
				case "clickgui" -> openClickGui.run();
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
				case "authorize" -> manageCurrentAuthorization(args, true);
				case "deauthorize" -> manageCurrentAuthorization(args, false);
				case "reload" -> reload();
				case "java" -> runtimeJava.submit(commandRemainder(input));
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
	public synchronized void emit(ConsoleLine line) { add(line); }
	public synchronized void recordAudit(String event, String target, String module, String outcome, String detail) {
		audit.record(event, target, module, outcome, detail);
	}

	public synchronized List<ModuleView> moduleViews() {
		return registry.all().stream()
			.sorted(java.util.Comparator.comparing(ResearchModule::id))
			.map(module -> new ModuleView(
				module.id(), module.name(), module.description(),
				activeModule != null && activeModule.id().equals(module.id())
			)).toList();
	}

	public synchronized ActiveModuleView activeModuleView() {
		if (activeModule == null) return null;
		List<OptionView> options = activeModule.options().stream().map(option -> {
			String value = activeOptions.getOrDefault(option.name(), "");
			boolean configured = !value.isBlank();
			return new OptionView(option.name(), option.secret() ? "" : value,
				option.required(), option.secret(), configured, option.description());
		}).toList();
		return new ActiveModuleView(activeModule.id(), activeModule.name(), activeModule.description(), options);
	}

	public synchronized boolean selectModuleFromUi(String id) {
		ResearchModule module = registry.find(id).orElse(null);
		if (module == null) return false;
		selectModule(module);
		return true;
	}

	public synchronized boolean setOptionFromUi(String name, String value) {
		if (activeModule == null || name == null) return false;
		String normalized = name.toUpperCase(Locale.ROOT);
		if (!activeOptions.containsKey(normalized)) return false;
		activeOptions.put(normalized, value == null ? "" : value);
		return true;
	}

	public synchronized void runFromUi() { runActive(); }
	public synchronized String runningModuleId() { return runningModuleId == null ? "" : runningModuleId; }
	public synchronized void clearSelectionFromUi() { back(false); }
	public synchronized void reloadFromUi() { reload(); }
	public synchronized CurrentTargetView currentTargetView() {
		ModuleContext context = contextSupplier.get();
		String target = AuthorizationPolicy.normalizeHost(context.serverAddress());
		boolean available = context.connected() && !target.isBlank() && !target.equals("unknown");
		return new CurrentTargetView(available, target, available && authorization.isAllowed(target));
	}
	public synchronized void setCurrentTargetAuthorizedFromUi(boolean authorized) {
		manageCurrentAuthorization(List.of(authorized ? "authorize" : "deauthorize", "current"), authorized);
	}

	public record ModuleView(String id, String name, String description, boolean selected) {}
	public record ActiveModuleView(String id, String name, String description, List<OptionView> options) {}
	public record OptionView(String name, String value, boolean required, boolean secret, boolean configured, String description) {}
	public record CurrentTargetView(boolean available, String target, boolean authorized) {}

	private void help(List<String> args) {
		String topic = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "quick";
		switch (topic) {
			case "console", "commands" -> {
				add(ConsoleLine.accent("Console command reference"));
				add(ConsoleLine.info("  clickgui | show modules | search <text> | use <module> | info | back"));
				add(ConsoleLine.info("  show options | set <name> <value> | unset <name> | run"));
				add(ConsoleLine.info("  authorize current | deauthorize current | show targets"));
				add(ConsoleLine.info("  status | reload | clear | banner"));
			}
			case "java" -> {
				add(ConsoleLine.accent("Runtime Java command reference"));
				add(ConsoleLine.info("  java status | sources | classpath | hash | confirm <sha256>"));
				add(ConsoleLine.info("  java compile | start | reload | stop | diagnostics"));
				add(ConsoleLine.warning("Runtime Java is unrestricted code execution and is disabled by default."));
			}
			default -> {
				add(ConsoleLine.accent("Quick start"));
				add(ConsoleLine.info("  clickgui - open modules, options, and current-target authorization"));
				add(ConsoleLine.info("  authorize current - authorize the connected server in game"));
				add(ConsoleLine.info("  help console - command reference | help java - runtime Java reference"));
				add(ConsoleLine.muted("  Modules remain authorization-gated and audited."));
			}
		}
	}

	private void banner() {
		add(ConsoleLine.accent("   CHAMPIONS MOD  // defensive research console"));
		add(ConsoleLine.info("   Minecraft 26.3 | client-only | Fabric"));
		add(ConsoleLine.muted("   F8 opens this console. Type clickgui to browse modules."));
		add(ConsoleLine.warning("   Test only systems you own or have explicit permission to assess."));
	}

	private void show(List<String> args) {
		if (args.size() < 2) { add(ConsoleLine.error("Usage: show modules|options|targets")); return; }
		switch (args.get(1).toLowerCase(Locale.ROOT)) {
			case "modules" -> {
				add(ConsoleLine.accent("Available modules"));
				for (ResearchModule module : registry.all()) add(ConsoleLine.info("  " + module.id() + " - " + module.name()));
			}
			case "options" -> showOptions();
			case "targets" -> {
				add(ConsoleLine.accent("Exact authorized targets"));
				authorization.allowedTargets().stream().sorted().forEach(t -> add(ConsoleLine.info("  " + t)));
				add(ConsoleLine.muted("Manage the connected server with authorize current or deauthorize current."));
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
				add(ConsoleLine.info("  " + module.id() + " - " + module.name()));
				count++;
			}
		}
		if (count == 0) add(ConsoleLine.muted("No modules matched."));
	}

	private void use(List<String> args) {
		if (args.size() != 2) { add(ConsoleLine.error("Usage: use <module>")); return; }
		Optional<ResearchModule> found = registry.find(args.get(1));
		if (found.isEmpty()) { add(ConsoleLine.error("Module not found: " + args.get(1))); return; }
		selectModule(found.get());
		add(ConsoleLine.success("Using " + activeModule.id()));
		add(ConsoleLine.muted("Type info or show options."));
	}

	private void selectModule(ResearchModule module) {
		activeModule = module;
		activeOptions.clear();
		for (ModuleOption option : activeModule.options()) activeOptions.put(option.name(), option.defaultValue());
	}

	private void back() { back(true); }

	private void back(boolean announce) {
		if (activeModule == null) {
			if (announce) add(ConsoleLine.muted("No active module."));
		} else {
			activeOptions.clear();
			activeModule = null;
			if (announce) add(ConsoleLine.info("Returned to the main console."));
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

	private void manageCurrentAuthorization(List<String> args, boolean authorize) {
		if (args.size() != 2 || !args.get(1).equalsIgnoreCase("current")) {
			add(ConsoleLine.error("Usage: " + (authorize ? "authorize" : "deauthorize") + " current"));
			return;
		}
		ModuleContext context = contextSupplier.get();
		String target = AuthorizationPolicy.normalizeHost(context.serverAddress());
		if (!context.connected() || target.isBlank() || target.equals("unknown")) {
			add(ConsoleLine.error("No current server target is available."));
			return;
		}
		try {
			boolean changed = authorize ? authorization.addTarget(target) : authorization.removeTarget(target);
			String action = authorize ? "Authorized" : "Removed authorization for";
			String detail = changed ? action + " current target: " + target : "Current target already " + (authorize ? "authorized: " : "not authorized: ") + target;
			add(changed ? ConsoleLine.success(detail) : ConsoleLine.muted(detail));
			audit.record("target_allowlist", target, "authorization", authorize ? "authorized" : "removed", "Changed in game");
		} catch (IOException | IllegalArgumentException error) {
			add(ConsoleLine.error("Could not update current target authorization: " + safeMessage(error)));
		}
	}

	private void reload() {
		try {
			authorization.reload();
			add(ConsoleLine.success("Reloaded authorization policy. Runtime Java has separate settings and reload commands."));
		} catch (IOException e) {
			add(ConsoleLine.error("Reload failed: " + e.getMessage()));
		}
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

	private static String commandRemainder(String input) {
		int space = input.indexOf(' ');
		return space < 0 ? "" : input.substring(space + 1).strip();
	}

	private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }
	private static String safeMessage(Throwable error) {
		String text = error.getMessage();
		return text == null || text.isBlank() ? error.getClass().getSimpleName() : limit(text.replace('\n', ' '), 240);
	}
}
