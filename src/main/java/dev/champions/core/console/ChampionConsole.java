package dev.champions.core.console;

import dev.champions.api.java.RuntimeJavaCommands;
import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.ExploitResearchModule;
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
import dev.champions.core.update.UpdateCheck;
import dev.champions.core.update.UpdateResult;
import dev.champions.core.update.UpdateService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stateful msfconsole-inspired command shell for defensive, authorized modules. */
public final class ChampionConsole {
	private static final Logger LOG = LoggerFactory.getLogger("champions-console");
	private static final int MAX_LINES = 1_000;
	private static final int MAX_HISTORY = 100;
	private final ModuleRegistry registry = new ModuleRegistry();
	private final AuthorizationPolicy authorization;
	private final AuditLog audit;
	private final Supplier<ModuleContext> contextSupplier;
	private final ActiveModuleRuntime activeRuntime;
	private final RuntimeJavaCommands runtimeJava;
	private final Runnable openClickGui;
	private final Runnable openCategoryGui;
	private final ExploitWarningPrompt exploitWarningPrompt;
	private final UpdateService updateService;
	private final Runnable closeUi;
	private final List<ConsoleLine> lines = new ArrayList<>();
	private final List<String> history = new ArrayList<>();
	private ResearchModule activeModule;
	private String runningModuleId;
	private String approvedExploitRunModuleId;
	private boolean exploitWarningPending;
	private final Map<String, String> activeOptions = new LinkedHashMap<>();

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier) {
		this(configDirectory, contextSupplier, ActiveModuleRuntime.disabled(), RuntimeJavaCommands.disabled(),
			() -> {}, () -> {}, ExploitWarningPrompt.unavailable());
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier, ActiveModuleRuntime activeRuntime) {
		this(configDirectory, contextSupplier, activeRuntime, RuntimeJavaCommands.disabled(),
			() -> {}, () -> {}, ExploitWarningPrompt.unavailable());
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier,
		ActiveModuleRuntime activeRuntime, RuntimeJavaCommands runtimeJava) {
		this(configDirectory, contextSupplier, activeRuntime, runtimeJava,
			() -> {}, () -> {}, ExploitWarningPrompt.unavailable());
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier,
		ActiveModuleRuntime activeRuntime, RuntimeJavaCommands runtimeJava, Runnable openClickGui) {
		this(configDirectory, contextSupplier, activeRuntime, runtimeJava,
			openClickGui, () -> {}, ExploitWarningPrompt.unavailable());
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier,
		ActiveModuleRuntime activeRuntime, RuntimeJavaCommands runtimeJava, Runnable openClickGui, Runnable openCategoryGui) {
		this(configDirectory, contextSupplier, activeRuntime, runtimeJava,
			openClickGui, openCategoryGui, ExploitWarningPrompt.unavailable());
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier,
		ActiveModuleRuntime activeRuntime, RuntimeJavaCommands runtimeJava, Runnable openClickGui,
		Runnable openCategoryGui, ExploitWarningPrompt exploitWarningPrompt) {
		this(configDirectory, contextSupplier, activeRuntime, runtimeJava, openClickGui,
			openCategoryGui, exploitWarningPrompt, UpdateService.disabled());
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier,
		ActiveModuleRuntime activeRuntime, RuntimeJavaCommands runtimeJava, Runnable openClickGui,
		Runnable openCategoryGui, ExploitWarningPrompt exploitWarningPrompt, UpdateService updateService) {
		this(configDirectory, contextSupplier, activeRuntime, runtimeJava, openClickGui,
			openCategoryGui, exploitWarningPrompt, updateService, () -> {});
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier,
		ActiveModuleRuntime activeRuntime, RuntimeJavaCommands runtimeJava, Runnable openClickGui,
		Runnable openCategoryGui, ExploitWarningPrompt exploitWarningPrompt, UpdateService updateService,
		Runnable closeUi) {
		this.contextSupplier = contextSupplier;
		this.activeRuntime = activeRuntime;
		this.runtimeJava = runtimeJava;
		this.openClickGui = openClickGui;
		this.openCategoryGui = openCategoryGui;
		this.exploitWarningPrompt = exploitWarningPrompt == null ? ExploitWarningPrompt.unavailable() : exploitWarningPrompt;
		this.updateService = updateService == null ? UpdateService.disabled() : updateService;
		this.closeUi = closeUi == null ? () -> {} : closeUi;
		this.authorization = new AuthorizationPolicy(configDirectory);
		this.audit = new AuditLog(configDirectory);
		banner();
	}

	/**
	 * Registers an additional module after construction. Intended for client-side
	 * drop-in modules (the exploit and payload folders), which the core source
	 * set cannot reach; the client entrypoint calls this once per drop-in module.
	 */
	public synchronized void registerModule(ResearchModule module) {
		registry.register(module);
		LOG.info("drop-in module registered: {}", module.id());
	}

	public synchronized void submit(String rawInput) {
		String input = rawInput == null ? "" : rawInput.strip();
		if (input.isEmpty()) return;
		addHistory(input);
		add(ConsoleLine.accent(prompt() + " > " + redactForEcho(input)));
		LOG.info("command: {}", redactForEcho(input));

		List<String> args;
		try { args = CommandTokenizer.tokenize(input); }
		catch (IllegalArgumentException e) { add(ConsoleLine.error(e.getMessage())); return; }
		if (args.isEmpty()) return;
		String command = args.getFirst().toLowerCase(Locale.ROOT);
		try {
			switch (command) {
				case "help", "?" -> help(args);
				case "clickgui" -> openClickGui.run();
				case "categorygui", "windowgui", "panelgui" -> openCategoryGui.run();
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
				case "update" -> update(args);
				case "exit", "close", "quit" -> {
					add(ConsoleLine.muted("Closing terminal."));
					LOG.info("terminal closed via {} command", command);
					closeUi.run();
				}
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
				activeModule != null && activeModule.id().equals(module.id()),
				module instanceof ExploitResearchModule
			)).toList();
	}

	public synchronized List<ModuleView> exploitModuleViews() {
		return moduleViews().stream().filter(ModuleView::exploitResearch).toList();
	}

	public synchronized ActiveModuleView activeModuleView() {
		if (activeModule == null) return null;
		List<OptionView> options = activeModule.options().stream().map(option -> {
			String value = activeOptions.getOrDefault(option.name(), "");
			boolean configured = !value.isBlank();
			return new OptionView(option.name(), option.secret() ? "" : value, option.defaultValue(),
				option.required(), option.secret(), configured, option.description(), option.choices());
		}).toList();
		return new ActiveModuleView(activeModule.id(), activeModule.name(), activeModule.description(), options,
			activeModule instanceof ExploitResearchModule);
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
		ModuleOption option = findOption(normalized);
		if (option == null) return false;
		String stored = value == null ? "" : value;
		if (option.constrained()) {
			String resolved = option.resolveChoice(stored);
			if (resolved == null) return false;
			stored = resolved;
		}
		activeOptions.put(normalized, stored);
		return true;
	}

	private ModuleOption findOption(String normalizedName) {
		if (activeModule == null) return null;
		for (ModuleOption option : activeModule.options()) {
			if (option.name().equals(normalizedName)) return option;
		}
		return null;
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

	public record ModuleView(String id, String name, String description, boolean selected, boolean exploitResearch) {}
	public record ActiveModuleView(String id, String name, String description, List<OptionView> options, boolean exploitResearch) {}
	public record OptionView(String name, String value, String defaultValue, boolean required, boolean secret, boolean configured, String description, List<String> choices) {}
	public record CurrentTargetView(boolean available, String target, boolean authorized) {}

	private void update(List<String> args) {
		String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "check";
		switch (action) {
			case "check" -> {
				add(ConsoleLine.info("Checking this project's public GitLab releases..."));
				updateService.checkLatest().whenComplete((check, error) -> {
					if (error != null) {
						String reason = causeOf(error);
						emit(ConsoleLine.warning("Update check failed: " + reason));
						recordAudit("update_check", "client", "update", "error", reason);
						return;
					}
					if (check.isEmpty()) {
						emit(ConsoleLine.muted("The update feed listed no releases."));
						return;
					}
					UpdateCheck result = check.get();
					recordAudit("update_check", "client", "update", result.newerAvailable() ? "available" : "current", result.latestVersion());
					if (result.newerAvailable()) {
						emit(ConsoleLine.success("ChampionsCode " + result.latestVersion() + " is available (running " + result.currentVersion() + ")."));
						emit(ConsoleLine.info("Release page: " + result.releasePageUrl()));
						emit(ConsoleLine.info("Run 'update download " + result.latestVersion() + "' to fetch the verified JAR into the mods folder."));
					} else {
						emit(ConsoleLine.success("ChampionsCode " + result.currentVersion() + " is up to date (latest release: " + result.latestVersion() + ")."));
					}
				});
			}
			case "download" -> {
				if (args.size() < 3 || args.get(2).isBlank()) {
					add(ConsoleLine.error("Usage: update download <version> - run 'update check' first."));
					return;
				}
				String target = args.get(2);
				add(ConsoleLine.info("Resolving release " + target + " from the update feed..."));
				updateService.checkLatest().whenComplete((check, error) -> {
					if (error != null) {
						String reason = causeOf(error);
						emit(ConsoleLine.error("Update download failed: " + reason));
						recordAudit("update_download", "client", "update", "error", reason);
						return;
					}
					if (check.isEmpty()) {
						emit(ConsoleLine.error("The update feed listed no releases."));
						return;
					}
					UpdateCheck result = check.get();
					if (!result.latestVersion().equals(target)) {
						emit(ConsoleLine.error("Only the latest release can be downloaded; the latest is " + result.latestVersion() + "."));
						return;
					}
					if (result.matchesCurrent()) {
						emit(ConsoleLine.info("ChampionsCode " + target + " is already the running version."));
						return;
					}
					emit(ConsoleLine.info("Downloading champions-mod-" + target + ".jar ..."));
					updateService.download(result).whenComplete((download, downloadError) -> {
						boolean verified = downloadError == null && download != null && download.success();
						String detail = downloadError != null ? causeOf(downloadError)
							: download.message();
						emit(verified ? ConsoleLine.success(detail) : ConsoleLine.error(detail));
						if (verified) {
							emit(ConsoleLine.warning("Remove the old champions-mod JAR from the mods folder and restart Minecraft to complete the update."));
						}
						recordAudit("update_download", "client", "update", verified ? "completed" : "error",
							target + (download != null && !download.savedPath().isEmpty() ? " -> " + download.savedPath() : ""));
					});
				});
			}
			default -> add(ConsoleLine.error("Unknown update action: " + action + ". Use 'update check' or 'update download <version>'."));
		}
	}

	private static String causeOf(Throwable error) {
		Throwable cause = error instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null
			? completion.getCause() : error;
		return safeMessage(cause);
	}

	private void help(List<String> args) {
		String topic = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "quick";
		switch (topic) {
			case "console", "commands" -> {
				add(ConsoleLine.accent("Console command reference"));
				add(ConsoleLine.info("  clickgui | categorygui | show modules|exploits | search <text> | use <module> | info | back"));
				add(ConsoleLine.info("  show options | set <name> <value> | unset <name> | run"));
				add(ConsoleLine.info("  authorize current | deauthorize current | show targets"));
				add(ConsoleLine.info("  update check | update download <version>"));
				add(ConsoleLine.info("  status | reload | clear | banner | exit"));
			}
			case "java" -> {
				add(ConsoleLine.accent("Runtime Java command reference"));
				add(ConsoleLine.info("  java status | sources | classpath | hash | confirm <sha256>"));
				add(ConsoleLine.info("  java compile | start | reload | stop | diagnostics"));
				add(ConsoleLine.warning("Runtime Java is unrestricted code execution and is disabled by default."));
			}
			default -> {
				add(ConsoleLine.accent("Quick start"));
				add(ConsoleLine.info("  clickgui - open the sorted list interface"));
				add(ConsoleLine.info("  categorygui - open movable category windows"));
				add(ConsoleLine.info("  authorize current - authorize the connected server in game"));
				add(ConsoleLine.info("  help console - command reference | help java - runtime Java reference"));
				add(ConsoleLine.muted("  Modules remain authorization-gated and audited."));
			}
		}
	}

	private void banner() {
		add(ConsoleLine.accent("   CHAMPIONSCODE  // defensive research console"));
		add(ConsoleLine.info("   Minecraft 26.3 | client-only | Fabric"));
		add(ConsoleLine.muted("   F8 opens this console. Type clickgui to browse modules."));
		add(ConsoleLine.warning("   Test only systems you own or have explicit permission to assess."));
	}

	private void show(List<String> args) {
		if (args.size() < 2) { add(ConsoleLine.error("Usage: show modules|exploits|options|targets")); return; }
		switch (args.get(1).toLowerCase(Locale.ROOT)) {
			case "modules" -> {
				add(ConsoleLine.accent("Available modules"));
				for (ResearchModule module : registry.all()) add(ConsoleLine.info("  " + module.id() + " - " + module.name()));
			}
			case "exploits" -> {
				add(ConsoleLine.accent("Exploit research workspace"));
				registry.all().stream().filter(ExploitResearchModule.class::isInstance)
					.forEach(module -> add(ConsoleLine.warning("  " + module.id() + " - " + module.name())));
				add(ConsoleLine.muted("Opening the workspace and every module run require separate legal-risk acknowledgement."));
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
		if (module instanceof ExploitResearchModule exploit) {
			add(ConsoleLine.warning(exploit.legalRiskSummary()));
			add(ConsoleLine.warning("A legal-risk popup is mandatory before every run; authorization and audit gates still apply."));
		}
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
		ModuleOption option = findOption(name);
		if (option == null) { add(ConsoleLine.error("Unknown option: " + name)); return; }
		String value = String.join(" ", args.subList(2, args.size()));
		if (option.constrained()) {
			String resolved = option.resolveChoice(value);
			if (resolved == null) {
				add(ConsoleLine.error("Invalid value for " + name + ": " + value + ". Choose one of: "
					+ String.join(", ", option.choices()) + "."));
				return;
			}
			value = resolved;
		}
		activeOptions.put(name, value);
		boolean secret = option.secret();
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
		if (module instanceof ExploitResearchModule exploit && !module.id().equals(approvedExploitRunModuleId)) {
			requestExploitRunWarning(exploit);
			return;
		}
		approvedExploitRunModuleId = null;
		ModuleContext context = contextSupplier.get();
		String confirm = activeOptions.getOrDefault("CONFIRM", "");
		AuthorizationDecision decision = authorization.authorize(context.connected(), context.serverAddress(), activeOptions.get("RHOST"), confirm);
		activeOptions.put("CONFIRM", ""); // one-run secret, consumed even on denial
		if (module.requiresAuthorization() && !decision.allowed()) {
			add(ConsoleLine.error("Authorization gate denied: " + decision.reason()));
			LOG.warn("authorization denied for {}: {}", module.id(), decision.reason());
			audit.record("module_run", AuthorizationPolicy.normalizeHost(context.serverAddress()), module.id(), "denied", decision.reason());
			return;
		}

		Map<String, String> options = Map.copyOf(activeOptions);
		long started = System.nanoTime();
		boolean activeAction = module instanceof ActiveResearchModule active && active.performsActiveAction(options);
		add(ConsoleLine.accent("[*] Running " + module.id() + " against current connection"));
		LOG.info("module {} starting against {}", module.id(), decision.normalizedTarget());
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

	private void requestExploitRunWarning(ExploitResearchModule module) {
		if (exploitWarningPending) {
			add(ConsoleLine.warning("An exploit-research legal warning is already awaiting a decision."));
			return;
		}
		exploitWarningPending = true;
		String target = AuthorizationPolicy.normalizeHost(contextSupplier.get().serverAddress());
		AtomicBoolean resolved = new AtomicBoolean();
		ExploitWarningPrompt.Request request = new ExploitWarningPrompt.Request(
			module.id(), module.name(), target, module.legalRiskSummary(),
			() -> {
				if (!resolved.compareAndSet(false, true)) return;
				approveExploitRun(module.id(), target);
			},
			() -> {
				if (!resolved.compareAndSet(false, true)) return;
				cancelExploitRun(module.id(), target);
			}
		);
		try {
			exploitWarningPrompt.request(request);
		} catch (RuntimeException error) {
			exploitWarningPending = false;
			throw error;
		}
	}

	private synchronized void approveExploitRun(String moduleId, String target) {
		exploitWarningPending = false;
		if (activeModule == null || !activeModule.id().equals(moduleId)) {
			add(ConsoleLine.warning("Exploit-research run cancelled because the selected module changed."));
			audit.record("exploit_warning", target, moduleId, "cancelled", "Selection changed before acknowledgement");
			return;
		}
		approvedExploitRunModuleId = moduleId;
		add(ConsoleLine.warning("Legal-risk warning acknowledged for this run. Revalidating authorization and scope."));
		audit.record("exploit_warning", target, moduleId, "acknowledged", "Per-run legal-risk warning acknowledged");
		runActive();
	}

	private synchronized void cancelExploitRun(String moduleId, String target) {
		exploitWarningPending = false;
		approvedExploitRunModuleId = null;
		add(ConsoleLine.warning("Exploit-research run cancelled at the legal-risk warning."));
		audit.record("exploit_warning", target, moduleId, "cancelled", "Per-run warning declined or unavailable");
	}

	private synchronized void finishRun(ResearchModule module, String target, long started, ModuleResult result, Throwable error, boolean wasActive) {
		if (wasActive) runningModuleId = null;
		if (error != null) {
			Throwable cause = error.getCause() == null ? error : error.getCause();
			add(ConsoleLine.error("Module stopped safely: " + safeMessage(cause)));
			LOG.warn("module {} stopped after error: {}", module.id(), safeMessage(cause));
			audit.record("module_run", target, module.id(), "error", safeMessage(cause));
			return;
		}
		for (Finding finding : result.findings()) renderFinding(finding);
		if (!result.evidence().isEmpty()) {
			add(ConsoleLine.accent("Structured evidence"));
			for (EvidenceNode node : result.evidence()) renderEvidence(node, 0);
		}
		long elapsed = (System.nanoTime() - started) / 1_000_000L;
		add(ConsoleLine.success("[+] " + result.summary() + " (" + elapsed + " ms)"));
		LOG.info("module {} completed in {} ms: {}", module.id(), elapsed, result.summary());
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

	private void renderEvidence(EvidenceNode node, int depth) {
		String indent = "  ".repeat(Math.min(depth, 8));
		String value = node.value().isBlank() ? "" : ": " + node.value();
		String provenance = node.source().name().toLowerCase(Locale.ROOT) + "/"
			+ node.confidence().name().toLowerCase(Locale.ROOT);
		add(ConsoleLine.info(indent + "- " + node.label() + value + " [" + provenance + "]"));
		if (!node.limitation().isBlank()) add(ConsoleLine.muted(indent + "  Limitation: " + node.limitation()));
		for (EvidenceNode child : node.children()) renderEvidence(child, depth + 1);
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
