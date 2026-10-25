package dev.champions.core.module.builtin;

import dev.champions.core.mc.ClientCapabilities;
import dev.champions.core.mc.VersionGated;
import dev.champions.core.module.CommandDescriptor;
import dev.champions.core.module.CommandPathDescriptor;
import dev.champions.core.module.CommandSafetyPolicy;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.module.active.ActiveModuleRuntime.ChatCommandRequest;
import dev.champions.core.module.active.ActiveModuleRuntime.ChatTranscript;
import dev.champions.core.module.active.ActiveModuleRuntime.ProbeVariant;
import dev.champions.core.module.active.ActiveResearchModule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Filters default roots and optionally performs bounded metadata-oriented probes. */
public final class ChatPluginEnumeratorModule implements ActiveResearchModule, VersionGated {
	@Override public java.util.Set<ClientCapabilities.Feature> requires() { return java.util.Set.of(ClientCapabilities.Feature.COMMAND_TREE); }

	private static final Pattern VERSION_TOKEN = Pattern.compile(
		"(?i)\\b(?:version\\s*[:=]?\\s*|v)(v?\\d+(?:\\.\\d+){1,4}(?:[-+][0-9A-Za-z_.-]+)?)\\b");
	private static final Pattern BARE_VERSION_TOKEN = Pattern.compile(
		"(?<![A-Za-z0-9])v?(\\d+(?:\\.\\d+){1,4}(?:[-+][0-9A-Za-z_.-]+)?)(?![A-Za-z0-9])");
	private static final Set<String> METADATA_LEAVES = Set.of("version", "v", "about", "info", "status", "help");

	@Override public String id() { return "auxiliary/scanner/chat_plugin_enumerator"; }
	@Override public String name() { return "Filtered chat plugin enumerator"; }
	@Override public String description() {
		return "Maps bounded advertised command trees, classifies standard namespaces, and optionally probes only advertised literal metadata paths.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Must match the current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase: I_HAVE_AUTHORIZATION"),
			new ModuleOption("MODE", "silent", true, false, "silent (chat only) or aggressive (command probes)", List.of("SILENT", "AGGRESSIVE")),
			new ModuleOption("MAX_COMMANDS", "8", false, false, "Maximum non-default roots considered, 1-12"),
			new ModuleOption("MAX_DEPTH", "5", false, false, "Maximum advertised command-tree depth displayed/probed, 1-5"),
			new ModuleOption("MAX_PROBES", "12", false, false, "Maximum active metadata probes, 1-24"),
			new ModuleOption("INCLUDE_STANDARD", "true", false, false, "Classify and display standard Minecraft/Bukkit/Paper/Spigot surfaces"),
			new ModuleOption("TIMEOUT_TICKS", "30", false, false, "Per-probe capture window, 20-100 ticks"),
			new ModuleOption("MAX_MESSAGES", "4", false, false, "Maximum game messages per probe, 1-8"),
			new ModuleOption("INCLUDE_RAW", "true", false, false, "Include bounded response excerpts")
		);
	}

	@Override
	public boolean performsActiveAction(Map<String, String> options) {
		return parseMode(options.get("MODE")) == Mode.AGGRESSIVE;
	}

	@Override
	public CompletionStage<ModuleResult> runActive(ModuleContext context, Map<String, String> options, ActiveModuleRuntime runtime) {
		Mode mode = parseMode(options.get("MODE"));
		int maxCommands = boundedInt(options.get("MAX_COMMANDS"), 8, 1, 12);
		int maxDepth = boundedInt(options.get("MAX_DEPTH"), 5, 1, 5);
		int maxProbes = boundedInt(options.get("MAX_PROBES"), 12, 1, 24);
		int timeout = boundedInt(options.get("TIMEOUT_TICKS"), 30, 20, 100);
		int maxMessages = boundedInt(options.get("MAX_MESSAGES"), 4, 1, 8);
		boolean includeRaw = Boolean.parseBoolean(options.getOrDefault("INCLUDE_RAW", "true"));
		boolean includeStandard = Boolean.parseBoolean(options.getOrDefault("INCLUDE_STANDARD", "true"));
		List<CommandDescriptor> all = context.advertisedCommandDetails();
		List<CommandDescriptor> candidates = all.stream()
			.filter(command -> CommandSafetyPolicy.isNonDefaultCandidate(command.root()))
			.sorted(java.util.Comparator.comparing(CommandDescriptor::root))
			.limit(maxCommands)
			.toList();
		List<CommandDescriptor> standard = includeStandard ? all.stream()
			.filter(command -> {
				var classification = CommandSafetyPolicy.classify(command.root());
				return classification == CommandSafetyPolicy.Classification.STANDARD_NAMESPACE
					|| classification == CommandSafetyPolicy.Classification.STANDARD_ROOT;
			})
			.sorted(java.util.Comparator.comparing(CommandDescriptor::root)).toList() : List.of();
		int filtered = Math.max(0, all.size() - candidates.size() - standard.size());

		if (mode == Mode.SILENT) {
			return CompletableFuture.completedFuture(silentResult(candidates, standard, filtered, all.size(), maxDepth));
		}

		List<ChatCommandRequest> probes = buildProbePlan(candidates, timeout, maxMessages, maxDepth, maxProbes);
		if (probes.isEmpty()) {
			return CompletableFuture.completedFuture(noProbeResult(candidates, standard, filtered, maxDepth));
		}
		return executeSequentially(probes, runtime).thenApply(transcripts ->
			aggressiveResult(candidates, standard, filtered, transcripts, includeRaw, maxDepth));
	}

	private ModuleResult silentResult(List<CommandDescriptor> candidates, List<CommandDescriptor> standard,
		int filtered, int total, int maxDepth) {
		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO, "Mode", "SILENT - no command was sent.",
			"Switch to aggressive only for an explicitly authorized target and account."));
		findings.add(new Finding(Finding.Severity.INFO, "Classification",
			candidates.size() + " candidate, " + standard.size() + " standard, " + filtered + " blocked/invalid/limited root(s) out of " + total + ".",
			"Standard implementation commands remain visible but are not treated as third-party plugin evidence."));
		if (!standard.isEmpty()) findings.add(new Finding(Finding.Severity.INFO, "Standard implementation surface",
			standard.stream().map(command -> describeTree(command, maxDepth)).collect(Collectors.joining(" ; ")),
			"Minecraft/Bukkit/Paper/Spigot surfaces are classified separately and are not actively probed by this module."));
		if (candidates.isEmpty()) {
			findings.add(new Finding(Finding.Severity.PASS, "Non-default candidates", "None marked in the advertised command tree.",
				"Absence is not proof that no plugins are installed; command visibility depends on permissions."));
		} else {
			findings.add(new Finding(Finding.Severity.INFO, "Recursive candidate surface",
				candidates.stream().map(command -> describeTree(command, maxDepth)).collect(Collectors.joining(" ; ")),
				"Paths are passively read from the advertised Brigadier tree; plugin ownership remains unverified."));
		}
		return new ModuleResult("Silent recursive command mapping completed", findings);
	}

	private ModuleResult noProbeResult(List<CommandDescriptor> candidates, List<CommandDescriptor> standard,
		int filtered, int maxDepth) {
		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO, "Classification", standard.size() + " standard and " + filtered + " excluded root(s).",
			"Standard roots remain visible rather than being mislabeled as plugins."));
		findings.add(new Finding(Finding.Severity.INFO, "Candidate trees",
			candidates.isEmpty() ? "No non-default roots were advertised." : candidates.stream().map(c -> describeTree(c, maxDepth)).collect(Collectors.joining(" ; ")),
			"Active probes require a non-executable root or an advertised literal metadata path."));
		return new ModuleResult("Aggressive mode found no safe advertised metadata paths", findings);
	}

	private List<ChatCommandRequest> buildProbePlan(List<CommandDescriptor> candidates, int timeout, int maxMessages,
		int maxDepth, int maxProbes) {
		List<ChatCommandRequest> probes = new ArrayList<>();
		for (CommandDescriptor command : candidates) {
			if (!command.executableWithoutArguments() && probes.size() < maxProbes)
				probes.add(new ChatCommandRequest(command.root(), ProbeVariant.BLANK, timeout, maxMessages));
			for (CommandPathDescriptor path : command.descendants()) {
				if (probes.size() >= maxProbes) break;
				if (path.literalOnly() && path.segments().size() <= maxDepth && METADATA_LEAVES.contains(path.leaf()))
					probes.add(ChatCommandRequest.path(command.root(), path.segments(), timeout, maxMessages));
			}
		}
		return List.copyOf(probes);
	}

	private CompletionStage<List<ChatTranscript>> executeSequentially(List<ChatCommandRequest> probes, ActiveModuleRuntime runtime) {
		CompletableFuture<List<ChatTranscript>> chain = CompletableFuture.completedFuture(new ArrayList<>());
		for (ChatCommandRequest probe : probes) {
			chain = chain.thenCompose(results -> runtime.execute(probe).thenApply(transcript -> {
				results.add(transcript);
				return results;
			})).toCompletableFuture();
		}
		return chain.thenApply(List::copyOf);
	}

	private ModuleResult aggressiveResult(List<CommandDescriptor> candidates, List<CommandDescriptor> standard,
		int filtered, List<ChatTranscript> transcripts, boolean includeRaw, int maxDepth) {
		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO, "Mode",
			"AGGRESSIVE - " + transcripts.size() + " bounded probe(s) completed sequentially across " + candidates.size() + " candidate root(s).",
			"Probes are rate-limited by the client runtime and remain visible to the server."));
		findings.add(new Finding(Finding.Severity.INFO, "Classification",
			standard.size() + " standard implementation root(s) visible; " + filtered + " blocked/invalid/limited root(s).",
			"Only non-default candidates are actively probed."));
		findings.add(new Finding(Finding.Severity.INFO, "Recursive candidate surface",
			candidates.stream().map(command -> describeTree(command, maxDepth)).collect(Collectors.joining(" ; ")),
			"Argument branches are displayed but never used as active probe values."));

		Map<String, Set<String>> versions = new LinkedHashMap<>();
		Map<String, List<ChatTranscript>> byRoot = transcripts.stream().collect(Collectors.groupingBy(
			t -> t.request().root(), LinkedHashMap::new, Collectors.toList()));
		for (ChatTranscript transcript : transcripts) {
			for (String message : transcript.messages()) {
				String version = extractVersion(message);
				if (version != null) versions.computeIfAbsent(transcript.request().root(), ignored -> new LinkedHashSet<>()).add(version);
			}
		}
		if (versions.isEmpty()) {
			findings.add(new Finding(Finding.Severity.INFO, "Version evidence", "No version token was recognized in captured responses.",
				"Do not infer a version from silence; verify against an administrator-supplied inventory."));
		} else {
			findings.add(new Finding(Finding.Severity.LOW, "Version strings disclosed",
				versions.entrySet().stream().map(e -> e.getKey() + "=" + String.join("/", e.getValue())).collect(Collectors.joining(", ")),
				"Treat command output as unverified evidence and confirm plugin identity/version server-side before reporting."));
		}

		if (includeRaw) {
			for (Map.Entry<String, List<ChatTranscript>> entry : byRoot.entrySet()) {
				String excerpt = entry.getValue().stream().map(ChatPluginEnumeratorModule::describeTranscript).collect(Collectors.joining(" ; "));
				findings.add(new Finding(Finding.Severity.INFO, "Probe transcript for /" + entry.getKey(), limit(excerpt, 1_200),
					"Captured game messages can include unrelated server output; verify manually."));
			}
		}
		return new ModuleResult("Filtered chat command enumeration completed", findings);
	}

	private static String describeTree(CommandDescriptor command, int maxDepth) {
		List<String> paths = command.descendants().stream()
			.filter(path -> path.segments().size() <= maxDepth)
			.map(path -> command.root() + " " + path.path()
				+ (path.kind() == CommandPathDescriptor.NodeKind.ARGUMENT ? " <argument>" : "")
				+ (path.executable() ? " [exec]" : "")
				+ (path.redirects() ? " [redirect]" : ""))
			.limit(48).toList();
		String root = command.root() + (command.executableWithoutArguments() ? " [exec]" : "");
		return paths.isEmpty() ? root : root + " -> " + String.join(", ", paths);
	}

	private static String describeTranscript(ChatTranscript transcript) {
		String response = transcript.messages().isEmpty() ? "<no response>" : String.join(" | ", transcript.messages());
		return transcript.request().displayCommand() + " => " + transcript.status().name().toLowerCase(Locale.ROOT) + ": " + limit(response, 500);
	}

	static String extractVersion(String message) {
		Matcher labeled = VERSION_TOKEN.matcher(message);
		if (labeled.find()) return labeled.group(1);
		Matcher bare = BARE_VERSION_TOKEN.matcher(message);
		return bare.find() ? bare.group(1) : null;
	}

	private static Mode parseMode(String value) {
		String normalized = value == null ? "silent" : value.strip().toLowerCase(Locale.ROOT);
		return normalized.equals("aggressive") || normalized.equals("agresive") ? Mode.AGGRESSIVE : Mode.SILENT;
	}

	private static int boundedInt(String value, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
		catch (RuntimeException ignored) { return fallback; }
	}

	private static String limit(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max) + "…";
	}

	private enum Mode { SILENT, AGGRESSIVE }
}
