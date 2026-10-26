package dev.champions.core.console;

import dev.champions.core.log.Log;
import dev.champions.core.mc.ClientCapabilities;
import dev.champions.core.mc.VersionGated;

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
import dev.champions.core.module.payload.PayloadEffect;
import dev.champions.core.module.payload.PayloadModule;
import dev.champions.core.module.payload.PayloadSafety;
import dev.champions.core.repo.ModuleIndex;
import dev.champions.core.repo.ModuleRepositoryClient;
import dev.champions.core.repo.ModuleSourceInstaller;
import dev.champions.core.repo.RepoConfig;
import dev.champions.core.repo.RepoEntry;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
	private final Runnable openCategoryGui;
	private final Runnable openLogViewer;
	private final ExploitWarningPrompt exploitWarningPrompt;
	private final UpdateService updateService;
	private final Runnable closeUi;
	private final List<ConsoleLine> lines = new ArrayList<>();
	private final List<String> history = new ArrayList<>();
	private long lineSequence;
	private volatile java.util.function.Supplier<ClientCapabilities> capabilitiesSupplier = () -> null;
	private volatile InterfacePreferences interfaces = InterfacePreferences.unavailable();
	private final Path configDirectory;
	private final dev.champions.core.catalog.AuditFolder artifactFolder;
	/** Module repository state: config file, cached index, install target. */
	private volatile RepoConfig repoConfig;
	private volatile ModuleSourceInstaller moduleInstaller = ModuleSourceInstaller.disabled();
	private final Map<String, ModuleIndex> repoIndices = new java.util.concurrent.ConcurrentHashMap<>();
	private ResearchModule activeModule;
	private String runningModuleId;
	private String approvedExploitRunModuleId;
	private boolean exploitWarningPending;
	private ResearchModule pendingExploitModule;
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
		this(configDirectory, contextSupplier, activeRuntime, runtimeJava, openClickGui, openCategoryGui,
			() -> {}, exploitWarningPrompt, updateService, closeUi);
	}

	public ChampionConsole(Path configDirectory, Supplier<ModuleContext> contextSupplier,
		ActiveModuleRuntime activeRuntime, RuntimeJavaCommands runtimeJava, Runnable openClickGui,
		Runnable openCategoryGui, Runnable openLogViewer, ExploitWarningPrompt exploitWarningPrompt,
		UpdateService updateService, Runnable closeUi) {
		this.contextSupplier = contextSupplier;
		this.activeRuntime = activeRuntime;
		this.runtimeJava = runtimeJava;
		this.openClickGui = openClickGui == null ? () -> {} : openClickGui;
		this.openCategoryGui = openCategoryGui == null ? () -> {} : openCategoryGui;
		this.openLogViewer = openLogViewer == null ? () -> {} : openLogViewer;
		this.exploitWarningPrompt = exploitWarningPrompt == null ? ExploitWarningPrompt.unavailable() : exploitWarningPrompt;
		this.updateService = updateService == null ? UpdateService.disabled() : updateService;
		this.closeUi = closeUi == null ? () -> {} : closeUi;
		this.configDirectory = configDirectory;
		// A module that writes an artifact gets the one folder it may write into.
		this.artifactFolder = dev.champions.core.catalog.AuditFolder.under(configDirectory);
		for (ResearchModule module : registry.all()) injectArtifactFolder(module);
		this.authorization = new AuthorizationPolicy(configDirectory);
		this.audit = new AuditLog(configDirectory);
		banner();
	}

	/**
	 * Registers an additional module after construction. Intended for client-side
	 * drop-in modules (the exploit and payload folders), which the core source
	 * set cannot reach; the client entrypoint calls this once per drop-in module.
	 */
	/**
	 * Declares what this build can do. Called by the client adapter with the
	 * per-version capability set; the standalone desktop app leaves it unset and
	 * everything that is not explicitly gated still runs.
	 */
	public void setCapabilitiesSupplier(java.util.function.Supplier<ClientCapabilities> supplier) {
		this.capabilitiesSupplier = supplier == null ? () -> null : supplier;
	}

	public ClientCapabilities capabilities() {
		return capabilitiesSupplier.get();
	}

	/** Host bridge for the {@code interfaces} command (in-game window vs external app). */
	/** Points the console at a module repository and the front end that installs into it. */
	public void setModuleRepository(RepoConfig config, ModuleSourceInstaller installer) {
		this.repoConfig = config;
		if (installer != null) this.moduleInstaller = installer;
	}

	public void setModuleSourceInstaller(ModuleSourceInstaller installer) {
		if (installer != null) this.moduleInstaller = installer;
	}

	public void setInterfacePreferences(InterfacePreferences preferences) {
		this.interfaces = preferences == null ? InterfacePreferences.unavailable() : preferences;
	}

	public InterfacePreferences interfacePreferences() {
		return interfaces;
	}

	public synchronized void registerModule(ResearchModule module) {
		registry.register(module);
		// Drop-in modules are constructed outside the console, so the folder has to
		// be handed over here as well. Without it a plug-and-play payload module
		// that writes an artifact had nowhere to write and silently degraded to
		// "this front end has no artifact folder".
		injectArtifactFolder(module);
		Log.info("drop-in module registered: {}", module.id());
	}

	private void injectArtifactFolder(ResearchModule module) {
		if (module instanceof dev.champions.core.catalog.ArtifactFolderAware aware) aware.useAuditFolder(artifactFolder);
	}

	/** Atomically swaps the external runtime-module group without disturbing builtins. */
	public synchronized void replaceExternalModules(Collection<String> previousIds,
		Collection<? extends ResearchModule> replacements) {
		Set<String> removed = new LinkedHashSet<>();
		for (String id : previousIds) removed.add(normalizeModuleId(id));
		String activeId = activeModule == null ? null : normalizeModuleId(activeModule.id());
		String pendingId = pendingExploitModule == null ? null : normalizeModuleId(pendingExploitModule.id());
		if (runningModuleId != null && removed.contains(normalizeModuleId(runningModuleId))) {
			throw new IllegalStateException("Cannot replace runtime modules while one of them is running: " + runningModuleId);
		}
		registry.replace(removed, replacements);
		for (ResearchModule replacement : replacements) injectArtifactFolder(replacement);
		if (activeId != null && removed.contains(activeId)) {
			activeModule = null;
			activeOptions.clear();
		}
		if (pendingId != null && removed.contains(pendingId)) {
			pendingExploitModule = null;
			exploitWarningPending = false;
		}
		if (approvedExploitRunModuleId != null && removed.contains(normalizeModuleId(approvedExploitRunModuleId))) {
			approvedExploitRunModuleId = null;
		}
	}

	private static String normalizeModuleId(String id) {
		return id == null ? "" : id.toLowerCase(Locale.ROOT);
	}

	public synchronized void submit(String rawInput) {
		String input = rawInput == null ? "" : rawInput.strip();
		if (input.isEmpty()) return;
		addHistory(input);
		add(ConsoleLine.accent(prompt() + " > " + redactForEcho(input)));
		Log.info("command: {}", redactForEcho(input));

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
				case "logs", "log", "logviewer", "logview" -> openLogViewer.run();
				case "interfaces", "interface", "ui" -> interfaces(args);
				case "repo", "registry" -> repo(args);
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
					Log.info("terminal closed via {} command", command);
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
				add(ConsoleLine.info("  clickgui | categorygui | logs | show modules|exploits | search <text> | use <module> | info | back"));
				add(ConsoleLine.info("  show options | set <name> <value> | unset <name> | run"));
				add(ConsoleLine.info("  interfaces | interfaces both|external|in-game | interfaces reload"));
				add(ConsoleLine.info("  repo status | list | search <words> | install <module> | config <url>"));
				add(ConsoleLine.info("  authorize current | deauthorize current | show targets"));
				add(ConsoleLine.info("  update check | update download <version>"));
				add(ConsoleLine.info("  status | reload | clear | banner | exit"));
			}
			case "java" -> {
				add(ConsoleLine.accent("Runtime Java command reference"));
				add(ConsoleLine.info("  java status | sources | classpath | hash | confirm <sha256>"));
				add(ConsoleLine.info("  java compile | start | reload | stop | diagnostics"));
				add(ConsoleLine.info("  java modules folder | status | auto [on|off|now] | unload"));
				add(ConsoleLine.info("  java modules hash | confirm <sha256> | compile | reload   (manual mode)"));
				add(ConsoleLine.warning("Drop-in modules are unrestricted Java inside Minecraft, and are not sandboxed."));
				add(ConsoleLine.muted("Drop .java files below config/champions-mod/modules/ - they load automatically."));
			}
			default -> {
				add(ConsoleLine.accent("Quick start"));
				add(ConsoleLine.info("  clickgui - open the sorted list interface"));
				add(ConsoleLine.info("  categorygui - open movable category windows"));
				add(ConsoleLine.info("  logs - open the live game log window with search"));
				add(ConsoleLine.info("  authorize current - authorize the connected server in game"));
				add(ConsoleLine.info("  help console - command reference | help java - runtime Java reference"));
				add(ConsoleLine.info("  repo search <words> - pull a module from your private repository"));
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

	/**
	 * Switches between the in-game windows and the external desktop app from
	 * chat: {@code interfaces}, {@code interfaces both|external|in-game},
	 * {@code interfaces ingame on|off}, {@code interfaces spawn on|off},
	 * {@code interfaces reload}.
	 */
	// ---- module repositories ----------------------------------------------

	/**
	 * {@code repo} — search any number of configured module repositories and
	 * pull module sources out of them.
	 *
	 * <p>Fetching is read-only and every installed file must hash to the value
	 * its index pinned, so a swapped file is refused before it can be compiled.
	 * When two repositories publish the same id the first configured one wins,
	 * and the console says so rather than silently picking one.</p>
	 */
	private void repo(List<String> args) {
		RepoConfig config = repoConfig();
		if (config == null) {
			add(ConsoleLine.error("Module repository is unavailable in this front end."));
			return;
		}
		String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "status";
		try {
			switch (action) {
				case "status", "repos" -> repoStatus(config);
				case "add" -> repoAdd(config, args);
				case "remove", "rm" -> repoRemove(config, args);
				case "refresh" -> repoRefresh(config, args);
				case "list", "all" -> repoList(config, List.of(), false);
				case "search", "find" -> {
					List<String> words = args.subList(Math.min(2, args.size()), args.size());
					repoList(config, words, true);
				}
				case "install", "add-module", "pull" -> repoInstall(config, args);
				case "token" -> repoToken(config, args);
				case "config" -> repoLegacyConfig(config, args);
				case "help" -> repoHelp();
				default -> add(ConsoleLine.error("Unknown repo command '" + args.get(1)
						+ "'. Try: repo status | list | search <words> | install <id> | add <name> <url>"));
			}
		} catch (IOException | IllegalArgumentException error) {
			add(ConsoleLine.error("Repository: " + safeMessage(error)));
		}
	}

	private RepoConfig repoConfig() {
		RepoConfig config = repoConfig;
		if (config != null) return config;
		Path directory = configDirectory;
		if (directory == null) return null;
		config = new RepoConfig(directory);
		repoConfig = config;
		return config;
	}

	private void repoHelp() {
		add(ConsoleLine.accent("Module repositories"));
		add(ConsoleLine.info("  repo status                     every configured repository and its state"));
		add(ConsoleLine.info("  repo add <name> <url> [ref]     add a repository (searched after the existing ones)"));
		add(ConsoleLine.info("  repo remove <name>              forget a repository and its token"));
		add(ConsoleLine.info("  repo token <name> <token>       store a read-only token for one repository"));
		add(ConsoleLine.info("  repo refresh [name]             drop cached indexes and re-read them"));
		add(ConsoleLine.info("  repo list                       every module across all repositories"));
		add(ConsoleLine.info("  repo search <words>             filter by id, name, description or tag"));
		add(ConsoleLine.info("  repo install <id>               install from whichever repository publishes it"));
		add(ConsoleLine.info("  repo install <repo>:<id>        pin the repository explicitly"));
		add(ConsoleLine.warning("Installed modules are compiled and loaded as plain Java. The authorization"
				+ " gate covers running one against a server, not loading it."));
		add(ConsoleLine.muted("  Tokens can also come from CHAMPIONS_REPO_TOKEN for the default repository."));
	}

	private void repoStatus(RepoConfig config) {
		add(ConsoleLine.accent("Module repositories"));
		List<RepoConfig.Repo> repos = config.repos();
		if (repos.isEmpty()) {
			add(ConsoleLine.muted("  None configured. Add one with: repo add <name> <url>"));
			return;
		}
		for (RepoConfig.Repo repo : repos) {
			ModuleIndex cached = repoIndices.get(repo.name());
			add(ConsoleLine.info("  " + repo.name() + "  " + (repo.configured() ? repo.url() : "<no url>")));
			add(ConsoleLine.muted("      ref " + repo.ref() + " | token " + repo.maskedToken()
					+ " | index " + (cached == null ? "not fetched" : cached.entries().size() + " module(s)")));
		}
		add(ConsoleLine.muted("  settings " + config.file()));
		add(ConsoleLine.muted("  Order matters: the first repository that publishes an id wins."));
	}

	private void repoAdd(RepoConfig config, List<String> args) throws IOException {
		if (args.size() < 4) {
			add(ConsoleLine.error("Usage: repo add <name> <url> [ref]"));
			return;
		}
		RepoConfig.Repo repo = config.add(args.get(2), args.get(3), args.size() > 4 ? args.get(4) : "main");
		repoIndices.remove(repo.name());
		add(ConsoleLine.success("Added repository '" + repo.name() + "': " + repo.projectPath() + "@" + repo.ref()));
		add(ConsoleLine.muted("  Store a token with: repo token " + repo.name() + " <token>"));
	}

	private void repoRemove(RepoConfig config, List<String> args) throws IOException {
		if (args.size() < 3) {
			add(ConsoleLine.error("Usage: repo remove <name>"));
			return;
		}
		String name = args.get(2);
		if (config.remove(name)) {
			repoIndices.remove(name.toLowerCase(Locale.ROOT));
			add(ConsoleLine.success("Removed repository '" + name + "'."));
		} else {
			add(ConsoleLine.error("No repository named '" + name + "'. See: repo status"));
		}
	}

	private void repoToken(RepoConfig config, List<String> args) throws IOException {
		if (args.size() < 4) {
			add(ConsoleLine.error("Usage: repo token <name> <token>"));
			return;
		}
		RepoConfig.Repo repo = config.setToken(args.get(2), args.get(3));
		repoIndices.remove(repo.name());
		add(ConsoleLine.success("Token stored for '" + repo.name() + "' (" + repo.maskedToken() + ")."));
	}

	/** The 0.2.0-beta.3 form, kept so an existing config keeps working. */
	private void repoLegacyConfig(RepoConfig config, List<String> args) throws IOException {
		if (args.size() < 3) {
			add(ConsoleLine.info("  repo config <url> [ref]  |  repo config token <token>  |  repo config clear"));
			add(ConsoleLine.muted("  These apply to the '" + RepoConfig.DEFAULT_NAME
					+ "' repository. Use repo add for more than one."));
			return;
		}
		String subject = args.get(2);
		if (subject.equalsIgnoreCase("clear")) {
			config.setUrl(RepoConfig.DEFAULT_NAME, RepoConfig.DEFAULT_URL, "main");
			config.setToken(RepoConfig.DEFAULT_NAME, "");
			repoIndices.clear();
			add(ConsoleLine.success("Default repository reset to the built-in collection; token cleared."));
			return;
		}
		if (subject.equalsIgnoreCase("token")) {
			if (args.size() < 4) {
				add(ConsoleLine.error("Usage: repo token " + RepoConfig.DEFAULT_NAME + " <token>"));
				return;
			}
			RepoConfig.Repo repo = config.setToken(RepoConfig.DEFAULT_NAME, args.get(3));
			add(ConsoleLine.success("Token stored for '" + repo.name() + "' (" + repo.maskedToken() + ")."));
			return;
		}
		RepoConfig.Repo repo = config.setUrl(RepoConfig.DEFAULT_NAME, subject, args.size() > 3 ? args.get(3) : null);
		repoIndices.remove(repo.name());
		add(ConsoleLine.success("Repository set to " + repo.describe() + "."));
	}

	private void repoRefresh(RepoConfig config, List<String> args) throws IOException {
		String only = args.size() > 2 ? args.get(2).toLowerCase(Locale.ROOT) : null;
		int refreshed = 0;
		for (RepoConfig.Repo repo : config.repos()) {
			if (only != null && !repo.name().equals(only)) continue;
			if (!repo.configured()) continue;
			ModuleIndex index = new ModuleRepositoryClient(repo).index();
			repoIndices.put(repo.name(), index);
			add(ConsoleLine.success("  " + repo.name() + ": " + index.entries().size() + " module(s)"));
			refreshed++;
		}
		if (refreshed == 0) add(ConsoleLine.error("No matching repository. See: repo status"));
		else add(ConsoleLine.muted("  Index refreshed for " + refreshed + " repository(ies)."));
	}

	private ModuleIndex indexOf(RepoConfig.Repo repo) throws IOException {
		ModuleIndex cached = repoIndices.get(repo.name());
		if (cached != null) return cached;
		ModuleIndex index = new ModuleRepositoryClient(repo).index();
		repoIndices.put(repo.name(), index);
		return index;
	}

	private void repoList(RepoConfig config, List<String> words, boolean announce) throws IOException {
		List<RepoConfig.Repo> repos = config.repos().stream().filter(RepoConfig.Repo::configured).toList();
		if (repos.isEmpty()) {
			add(ConsoleLine.error("No repository has a url yet. Add one with: repo add <name> <url>"));
			return;
		}
		add(ConsoleLine.accent(words.isEmpty() ? "Repository modules"
				: "Repository search: " + String.join(" ", words)));
		Map<String, String> owners = new LinkedHashMap<>();
		int total = 0;
		for (RepoConfig.Repo repo : repos) {
			ModuleIndex index;
			try {
				index = indexOf(repo);
			} catch (IOException unreachable) {
				add(ConsoleLine.error("  " + repo.name() + " unreachable: " + safeMessage(unreachable)));
				continue;
			}
			List<RepoEntry> matches = index.search(words);
			total += matches.size();
			if (matches.isEmpty()) {
				add(ConsoleLine.muted("  " + repo.name() + ": no match (" + index.entries().size() + " module(s))"));
				continue;
			}
			add(ConsoleLine.info("  " + repo.name() + " (" + matches.size() + " of " + index.entries().size() + ")"));
			for (RepoEntry entry : matches) {
				String owner = owners.putIfAbsent(entry.id(), repo.name());
				add(ConsoleLine.info("    " + entry.id() + "  " + entry.name()
						+ (entry.version().isBlank() ? "" : "  v" + entry.version())));
				if (!entry.description().isBlank()) add(ConsoleLine.muted("        " + entry.description()));
				if (owner != null) {
					add(ConsoleLine.muted("        also published by " + owner + "; that one wins"));
				}
			}
		}
		add(ConsoleLine.muted("  " + total + " match(es) across " + repos.size()
				+ " repository(ies). Install with: repo install <id>"));
		if (announce && total == 0) add(ConsoleLine.muted("  Try broader words, or repo list for everything."));
	}

	private void repoInstall(RepoConfig config, List<String> args) throws IOException {
		if (args.size() < 3) {
			add(ConsoleLine.error("Usage: repo install <id>  or  repo install <repo>:<id>"));
			return;
		}
		String requested = args.get(2);
		String wanted = requested;
		String pinned = null;
		int colon = requested.indexOf(':');
		if (colon > 0) {
			pinned = requested.substring(0, colon).toLowerCase(Locale.ROOT);
			wanted = requested.substring(colon + 1);
		}

		RepoEntry match = null;
		String from = null;
		ModuleRepositoryClient winner = null;
		for (RepoConfig.Repo repo : config.repos()) {
			if (pinned != null && !repo.name().equals(pinned)) continue;
			if (!repo.configured()) continue;
			ModuleIndex index;
			try {
				index = indexOf(repo);
			} catch (IOException unreachable) {
				add(ConsoleLine.muted("  " + repo.name() + " unreachable: " + safeMessage(unreachable)));
				continue;
			}
			RepoEntry found = index.find(wanted).orElse(null);
			if (found == null) continue;
			match = found;
			from = repo.name();
			winner = new ModuleRepositoryClient(repo);
			break;
		}
		if (match == null) {
			add(ConsoleLine.error("No module '" + wanted + "' in "
					+ (pinned == null ? "any configured repository" : "repository '" + pinned + "'") + "."));
			add(ConsoleLine.muted("  Try: repo search " + wanted));
			return;
		}
		if (match.api() > ModuleIndex.SUPPORTED_API) {
			add(ConsoleLine.error("'" + match.id() + "' targets module API " + match.api()
					+ "; this build speaks API " + ModuleIndex.SUPPORTED_API + ". Update ChampionsCode first."));
			return;
		}
		if (match.sha256().isBlank()) {
			add(ConsoleLine.error("'" + match.id() + "' has no sha256 in the index; refusing to install unverifiable code."));
			return;
		}
		String source = winner.source(match);
		if (!ModuleRepositoryClient.verify(match, source)) {
			add(ConsoleLine.error("Checksum mismatch for " + match.id() + " — the file does not match the index."));
			add(ConsoleLine.muted("  expected " + match.sha256()));
			add(ConsoleLine.muted("  actual   " + ModuleRepositoryClient.sha256(source)));
			return;
		}
		add(ConsoleLine.info("Verified " + match.id() + " from " + from + " (sha256 "
				+ match.sha256().substring(0, 12) + "...)"));
		String fileName = match.file().substring(match.file().lastIndexOf('/') + 1);
		ModuleSourceInstaller.Outcome outcome = moduleInstaller.install(match.id(), fileName, source);
		if (outcome.ok()) {
			add(ConsoleLine.success(outcome.message()));
			add(ConsoleLine.muted("  Run it with: use " + match.id() + ", then authorize and set CONFIRM."));
		} else {
			add(ConsoleLine.error(outcome.message()));
		}
	}

	private void interfaces(List<String> args) {
		InterfacePreferences preferences = interfaces;
		if (args.size() == 1) {
			add(ConsoleLine.accent("Interfaces"));
			add(ConsoleLine.info("  mode          " + preferences.currentMode()));
			add(ConsoleLine.info("  in-game       " + (preferences.inGameWindows() ? "on" : "off")
				+ "   external app " + (preferences.desktopAttached() ? "attached" : "not attached")));
			add(ConsoleLine.info("  spawnOnOpen   " + (preferences.spawnOnOpen() ? "on" : "off")));
			for (InterfacePreferences.Mode mode : preferences.modes()) {
				add(ConsoleLine.muted("    " + mode.id() + " - " + mode.description()));
			}
			add(ConsoleLine.muted("  Switch with: interfaces both | external | in-game"));
			return;
		}
		String action = InterfacePreferences.normalise(args.get(1));
		switch (action) {
			case "reload" -> {
				preferences.reload();
				add(ConsoleLine.success("Interface settings reloaded: " + preferences.currentMode()));
			}
			case "spawn", "autostart" -> {
				boolean enabled = args.size() > 2 && isOn(args.get(2));
				preferences.setSpawnOnOpen(enabled);
				add(ConsoleLine.success("Desktop app autostart " + (enabled ? "enabled" : "disabled") + "."));
			}
			case "ingame", "in-game-windows" -> {
				boolean enabled = args.size() > 2 && isOn(args.get(2));
				preferences.setInGameWindows(enabled);
				add(ConsoleLine.success("In-game windows " + (enabled ? "enabled alongside the app" : "disabled") + "."));
			}
			default -> {
				String mode = action.equals("ingame") ? "in-game" : action;
				boolean known = preferences.modes().stream().anyMatch(candidate -> candidate.id().equals(mode));
				if (!known) {
					add(ConsoleLine.error("Unknown interface '" + args.get(1) + "'. Use both, external or in-game."));
					return;
				}
				add(ConsoleLine.success(preferences.setMode(mode)));
				add(ConsoleLine.muted("  Applies immediately; the desktop app picks it up on its next attach."));
			}
		}
		audit.record("interface_mode", "client", "desktop", "changed", redactForEcho(String.join(" ", args)));
	}

	private static boolean isOn(String value) {
		String normalised = InterfacePreferences.normalise(value);
		return normalised.equals("on") || normalised.equals("true") || normalised.equals("yes")
			|| normalised.equals("1") || normalised.equals("enable") || normalised.equals("enabled");
	}

	private void runActive() {
		if (activeModule == null) { add(ConsoleLine.error("Select a module first.")); return; }
		if (runningModuleId != null) { add(ConsoleLine.error("Active module already running: " + runningModuleId)); return; }

		ResearchModule module = activeModule;
		if (module instanceof PayloadModule payload) {
			// Declared from the options that are about to run, not from whatever the
			// module happens to hold: the declaration has to describe this run.
			Map<String, String> declared = Map.copyOf(activeOptions);
			PayloadSafety.Verdict verdict = PayloadSafety.check(payload.effect(declared));
			if (!verdict.allowed()) {
				add(ConsoleLine.error("Payload refused before running: " + verdict.reason()));
				audit.record("payload", AuthorizationPolicy.normalizeHost(contextSupplier.get().serverAddress()),
					module.id(), "refused", verdict.reason());
				return;
			}
			add(ConsoleLine.info("[*] Declared effect: " + payload.effect(declared).describe()));
			add(ConsoleLine.muted("    Revert: " + payload.revertPlan()));
		}
		if (module instanceof ExploitResearchModule exploit && !module.id().equals(approvedExploitRunModuleId)) {
			requestExploitRunWarning(exploit);
			return;
		}
		approvedExploitRunModuleId = null;
		ClientCapabilities capabilities = capabilitiesSupplier.get();
		if (capabilities != null && module instanceof VersionGated gated && !gated.availableOn(capabilities)) {
			String missing = capabilities.missing(gated.requires());
			add(ConsoleLine.warning("[!] " + module.id() + " needs " + missing
				+ ", which this build for Minecraft " + capabilities.minecraftVersion() + " does not provide."));
			add(ConsoleLine.muted("    Use a build of ChampionsCode for this Minecraft version, or run the module on a supported target."));
			audit.record("module_run", AuthorizationPolicy.normalizeHost(contextSupplier.get().serverAddress()),
				module.id(), "unavailable", missing);
			return;
		}
		ModuleContext context = contextSupplier.get();
		String confirm = activeOptions.getOrDefault("CONFIRM", "");
		AuthorizationDecision decision = authorization.authorize(context.connected(), context.serverAddress(), activeOptions.get("RHOST"), confirm);
		activeOptions.put("CONFIRM", ""); // one-run secret, consumed even on denial
		if (module.requiresAuthorization() && !decision.allowed()) {
			add(ConsoleLine.error("Authorization gate denied: " + decision.reason()));
			Log.warn("authorization denied for {}: {}", module.id(), decision.reason());
			audit.record("module_run", AuthorizationPolicy.normalizeHost(context.serverAddress()), module.id(), "denied", decision.reason());
			return;
		}

		Map<String, String> options = Map.copyOf(activeOptions);
		long started = System.nanoTime();
		boolean activeAction = module instanceof ActiveResearchModule active && active.performsActiveAction(options);
		boolean activeJob = module instanceof ActiveResearchModule;
		add(ConsoleLine.accent("[*] Running " + module.id() + " against current connection"));
		Log.info("module {} starting against {}", module.id(), decision.normalizedTarget());
		try {
			if (module instanceof ActiveResearchModule active) {
				runningModuleId = module.id();
				if (activeAction) {
					add(ConsoleLine.warning(payloadBanner(module, options)));
					String startedDetail = module instanceof PayloadModule payload
						? "Payload started: " + payload.effect().describe()
						: "Bounded active command probes started";
					audit.record("module_run", decision.normalizedTarget(), module.id(), "started", startedDetail);
				} else {
					add(ConsoleLine.muted("[*] SILENT mode selected; no command will be sent."));
				}
				active.runActive(context, options, activeRuntime).whenComplete((result, error) ->
					finishRun(module, decision.normalizedTarget(), started, result, error, activeJob));
			} else {
				finishRun(module, decision.normalizedTarget(), started, module.run(context, options), null, false);
			}
		} catch (RuntimeException e) {
			finishRun(module, decision.normalizedTarget(), started, null, e, activeJob);
		}
	}

	/**
	 * What the user is about to see, in their words. A payload that creates state
	 * is the only kind that can be reverted, so only that kind is called
	 * reversible — a {@code /seed} query is neither reversible nor irreversible,
	 * it simply leaves nothing behind.
	 */
	private static String payloadBanner(ResearchModule module, Map<String, String> options) {
		if (!(module instanceof PayloadModule payload)) {
			return "[>] AGGRESSIVE action authorized; probes are sequential, filtered, and rate-limited...";
		}
		PayloadEffect effect = payload.effect(options);
		return switch (effect.kind()) {
			// An artifact never leaves the client, so it is never "sent".
			case AUDIT_ARTIFACT -> "[>] PAYLOAD authorized for this run; one artifact will be written to the audit folder.";
			case LOCAL_FILE -> "[>] PAYLOAD authorized for this run; one marker file will be written, then removed.";
			case CHAT_COMMAND -> "[>] PAYLOAD authorized for this run; one state-neutral command will be sent.";
			case NONE -> "[>] PAYLOAD authorized for this run; it declares no side effect.";
		};
	}

	private void requestExploitRunWarning(ExploitResearchModule module) {
		if (exploitWarningPending) {
			add(ConsoleLine.warning("An exploit-research legal warning is already awaiting a decision."));
			return;
		}
		exploitWarningPending = true;
		pendingExploitModule = module;
		String target = AuthorizationPolicy.normalizeHost(contextSupplier.get().serverAddress());
		AtomicBoolean resolved = new AtomicBoolean();
		ExploitWarningPrompt.Request request = new ExploitWarningPrompt.Request(
			module.id(), module.name(), target, module.legalRiskSummary(),
			() -> {
				if (!resolved.compareAndSet(false, true)) return;
				approveExploitRun(module, target);
			},
			() -> {
				if (!resolved.compareAndSet(false, true)) return;
				cancelExploitRun(module, target);
			}
		);
		try {
			exploitWarningPrompt.request(request);
		} catch (RuntimeException error) {
			if (pendingExploitModule == module) {
				exploitWarningPending = false;
				pendingExploitModule = null;
			}
			throw error;
		}
	}

	private synchronized void approveExploitRun(ResearchModule requestedModule, String target) {
		String moduleId = requestedModule.id();
		boolean currentWarning = exploitWarningPending && pendingExploitModule == requestedModule;
		if (currentWarning) {
			exploitWarningPending = false;
			pendingExploitModule = null;
		}
		// Compare the instance and pending warning, not only the id: a hot-reloaded
		// module may reuse its id while an old warning dialog is still open.
		if (!currentWarning || activeModule != requestedModule) {
			add(ConsoleLine.warning("Exploit-research run cancelled because the selected module changed."));
			audit.record("exploit_warning", target, moduleId, "cancelled", "Selection changed before acknowledgement");
			return;
		}
		approvedExploitRunModuleId = moduleId;
		add(ConsoleLine.warning("Legal-risk warning acknowledged for this run. Revalidating authorization and scope."));
		audit.record("exploit_warning", target, moduleId, "acknowledged", "Per-run legal-risk warning acknowledged");
		runActive();
	}

	private synchronized void cancelExploitRun(ResearchModule requestedModule, String target) {
		String moduleId = requestedModule.id();
		if (pendingExploitModule == requestedModule) {
			exploitWarningPending = false;
			pendingExploitModule = null;
			approvedExploitRunModuleId = null;
			add(ConsoleLine.warning("Exploit-research run cancelled at the legal-risk warning."));
		} else {
			add(ConsoleLine.warning("Stale exploit-research warning dismissed after the module changed."));
		}
		audit.record("exploit_warning", target, moduleId, "cancelled", "Per-run warning declined, dismissed, or module replaced");
	}

	private synchronized void finishRun(ResearchModule module, String target, long started, ModuleResult result, Throwable error, boolean wasActive) {
		if (wasActive) runningModuleId = null;
		if (error != null) {
			Throwable cause = error.getCause() == null ? error : error.getCause();
			add(ConsoleLine.error("Module stopped safely: " + safeMessage(cause)));
			Log.warn("module {} stopped after error: {}", module.id(), safeMessage(cause));
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
		Log.info("module {} completed in {} ms: {}", module.id(), elapsed, result.summary());
		audit.record("module_run", target, module.id(), "completed", result.summary());
	}

	private void status() {
		ModuleContext c = contextSupplier.get();
		add(ConsoleLine.accent("Client runtime status"));
		add(ConsoleLine.info("  connected=" + c.connected() + " target=" + c.serverAddress()));
		add(ConsoleLine.info("  brand=" + c.serverBrand() + " version=" + c.advertisedVersion()));
		add(ConsoleLine.info("  player=" + c.playerName() + " position=" + c.playerPosition()));
		add(ConsoleLine.info("  active_job=" + (runningModuleId == null ? "none" : runningModuleId)));
		ClientCapabilities capabilities = capabilitiesSupplier.get();
		if (capabilities != null) {
			add(ConsoleLine.info("  capabilities=" + capabilities.summary()));
			if (!capabilities.guiScreens()) add(ConsoleLine.muted("  in-game windows are not part of this build; use the external app."));
		}
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
		for (String textLine : line.text().split("\\n", -1)) {
			lines.add(new ConsoleLine(line.tone(), limit(textLine, 500)));
			lineSequence++;
		}
		if (lines.size() > MAX_LINES) lines.subList(0, lines.size() - MAX_LINES).clear();
	}

	/**
	 * Monotonic count of every line ever appended to the console, including lines
	 * that have since been trimmed out of the bounded buffer. Remote views use it
	 * to compute exact deltas: the buffer never loses track of how much was
	 * dropped from the front.
	 */
	public synchronized long lineSequence() {
		return lineSequence;
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

	private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "..."; }
	private static String safeMessage(Throwable error) {
		String text = error.getMessage();
		return text == null || text.isBlank() ? error.getClass().getSimpleName() : limit(text.replace('\n', ' '), 240);
	}
}
