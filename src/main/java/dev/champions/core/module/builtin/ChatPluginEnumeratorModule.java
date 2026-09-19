package dev.champions.core.module.builtin;

import dev.champions.core.module.CommandDescriptor;
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
public final class ChatPluginEnumeratorModule implements ActiveResearchModule {
	private static final Pattern VERSION_TOKEN = Pattern.compile(
		"(?i)\\b(?:version\\s*[:=]?\\s*|v)(v?\\d+(?:\\.\\d+){1,4}(?:[-+][0-9A-Za-z_.-]+)?)\\b");
	private static final Pattern BARE_VERSION_TOKEN = Pattern.compile(
		"(?<![A-Za-z0-9])v?(\\d+(?:\\.\\d+){1,4}(?:[-+][0-9A-Za-z_.-]+)?)(?![A-Za-z0-9])");

	@Override public String id() { return "auxiliary/scanner/chat_plugin_enumerator"; }
	@Override public String name() { return "Filtered chat plugin enumerator"; }
	@Override public String description() {
		return "Filters vanilla/Bukkit default roots. SILENT only marks non-default candidates; AGGRESSIVE probes bounded, server-advertised blank/version/v paths and captures chat output.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Must match the current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase: I_HAVE_AUTHORIZATION"),
			new ModuleOption("MODE", "silent", true, false, "silent or aggressive (agresive is also accepted)"),
			new ModuleOption("MAX_COMMANDS", "8", false, false, "Maximum non-default roots considered, 1-12"),
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
		int timeout = boundedInt(options.get("TIMEOUT_TICKS"), 30, 20, 100);
		int maxMessages = boundedInt(options.get("MAX_MESSAGES"), 4, 1, 8);
		boolean includeRaw = Boolean.parseBoolean(options.getOrDefault("INCLUDE_RAW", "true"));
		List<CommandDescriptor> candidates = context.advertisedCommandDetails().stream()
			.filter(command -> CommandSafetyPolicy.isNonDefaultCandidate(command.root()))
			.sorted(java.util.Comparator.comparing(CommandDescriptor::root))
			.limit(maxCommands)
			.toList();
		int filtered = Math.max(0, context.advertisedCommandDetails().size() - candidates.size());

		if (mode == Mode.SILENT) {
			return CompletableFuture.completedFuture(silentResult(candidates, filtered, context.advertisedCommandDetails().size()));
		}

		List<ChatCommandRequest> probes = buildProbePlan(candidates, timeout, maxMessages);
		if (probes.isEmpty()) {
			return CompletableFuture.completedFuture(noProbeResult(candidates, filtered));
		}
		return executeSequentially(probes, runtime).thenApply(transcripts ->
			aggressiveResult(candidates, filtered, transcripts, includeRaw));
	}

	private ModuleResult silentResult(List<CommandDescriptor> candidates, int filtered, int total) {
		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO, "Mode", "SILENT — no command was sent.",
			"Switch to aggressive only for an explicitly authorized target and account."));
		findings.add(new Finding(Finding.Severity.INFO, "Default filtering",
			filtered + " of " + total + " advertised root(s) filtered or outside the configured candidate limit.",
			"The filter covers vanilla plus standard Bukkit/Paper implementation roots."));
		if (candidates.isEmpty()) {
			findings.add(new Finding(Finding.Severity.PASS, "Non-default candidates", "None marked in the advertised command tree.",
				"Absence is not proof that no plugins are installed; command visibility depends on permissions."));
		} else {
			findings.add(new Finding(Finding.Severity.INFO, "Marked non-default candidates",
				candidates.stream().map(ChatPluginEnumeratorModule::describeCandidate).collect(Collectors.joining(", ")),
				"These are command-tree hints only; no command was sent and plugin ownership is unverified."));
		}
		return new ModuleResult("Silent command marking completed", findings);
	}

	private ModuleResult noProbeResult(List<CommandDescriptor> candidates, int filtered) {
		return new ModuleResult("Aggressive mode found no safe advertised metadata paths", List.of(
			new Finding(Finding.Severity.INFO, "Default filtering", filtered + " advertised/default root(s) excluded.", "Keep default-command filtering enabled."),
			new Finding(Finding.Severity.INFO, "Candidate roots",
				candidates.isEmpty() ? "No non-default roots were advertised." : candidates.stream().map(CommandDescriptor::root).collect(Collectors.joining(", ")),
				"Blank probes are skipped for executable roots; version/v probes require an advertised literal child.")));
	}

	private List<ChatCommandRequest> buildProbePlan(List<CommandDescriptor> candidates, int timeout, int maxMessages) {
		List<ChatCommandRequest> probes = new ArrayList<>();
		for (CommandDescriptor command : candidates) {
			// Never invoke a root with no arguments when the server marks it executable.
			if (!command.executableWithoutArguments()) probes.add(new ChatCommandRequest(command.root(), ProbeVariant.BLANK, timeout, maxMessages));
			// Metadata suffixes are sent only when the server explicitly advertised them as literals.
			if (command.hasLiteral("version")) probes.add(new ChatCommandRequest(command.root(), ProbeVariant.VERSION, timeout, maxMessages));
			if (command.hasLiteral("v")) probes.add(new ChatCommandRequest(command.root(), ProbeVariant.V, timeout, maxMessages));
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

	private ModuleResult aggressiveResult(List<CommandDescriptor> candidates, int filtered, List<ChatTranscript> transcripts, boolean includeRaw) {
		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO, "Mode",
			"AGGRESSIVE — " + transcripts.size() + " bounded probe(s) completed sequentially across " + candidates.size() + " candidate root(s).",
			"Probes are rate-limited by the client runtime and remain visible to the server."));
		findings.add(new Finding(Finding.Severity.INFO, "Default filtering", filtered + " advertised/default root(s) excluded.",
			"Only non-default roots from the current advertised command tree were considered."));

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

	private static String describeCandidate(CommandDescriptor command) {
		List<String> markers = new ArrayList<>();
		markers.add(command.executableWithoutArguments() ? "blank:skipped" : "blank:eligible");
		if (command.hasLiteral("version")) markers.add("version");
		if (command.hasLiteral("v")) markers.add("v");
		return command.root() + "[" + String.join("/", markers) + "]";
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
