package dev.champions.core.module.builtin;

import dev.champions.core.catalog.AuditFolder;
import dev.champions.core.catalog.ArtifactFolderAware;
import dev.champions.core.exploit.ExploitMechanism;
import dev.champions.core.exploit.GateRequest;
import dev.champions.core.exploit.MechanismRegistry;
import dev.champions.core.exploit.PayloadDescriptor;
import dev.champions.core.exploit.PayloadKind;
import dev.champions.core.exploit.PayloadProvider;
import dev.champions.core.exploit.ReproductionEngine;
import dev.champions.core.exploit.ReproductionResult;
import dev.champions.core.exploit.ReproductionState;
import dev.champions.core.exploit.StageOutcome;
import dev.champions.core.exploit.VerificationPlan;
import dev.champions.core.exploit.VerificationReport;
import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.module.payload.PayloadEffect;
import dev.champions.core.module.payload.PayloadModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The reproduction workspace: pick a mechanism and a payload, and let the engine
 * decide whether the pairing may run.
 *
 * <p>The module owns no vulnerability logic and no payload logic. It reads the
 * registries, hands the engine the selection, and reports what came back — including
 * when the answer is "the gate refused" or "that produced a document, not a
 * reproduction".</p>
 *
 * <p>Its own payload declaration is the widest one it might perform: it can write an
 * artifact, so the console prints and checks that before the gate runs. Whether the
 * selected payload is actually allowed is the engine's decision, not the module's
 * claim.</p>
 */
public final class FixtureReproductionModule implements PayloadModule, ArtifactFolderAware {
	/**
	 * The plan the operator was shown, and the configuration it was made for.
	 *
	 * <p>Verifying re-plans nothing: the acknowledgement releases <em>that</em> plan.
	 * A provider whose material answers differently on a second call, or an option
	 * changed between the two runs, cannot turn the approval into a different run — the
	 * key no longer matches and the module asks for a new plan.</p>
	 */
	private volatile dev.champions.core.exploit.VerificationPlan pendingPlan;
	private volatile String pendingKey = "";

	private AuditFolder folder = AuditFolder.unavailable();

	@Override public String id() { return "auxiliary/exploits/fixture_reproduction"; }
	@Override public String name() { return "Mechanism reproduction"; }
	@Override public String description() {
		return "Reproduces a ported vulnerability mechanism against its controlled fixture with a chosen payload.";
	}

	@Override
	public void useAuditFolder(AuditFolder folder) {
		this.folder = folder == null ? AuditFolder.unavailable() : folder;
	}

	private static List<String> mechanismIds() {
		return MechanismRegistry.ids();
	}

	private static List<String> payloadIds() {
		return ReproductionEngine.payloads().stream().map(PayloadProvider::id).toList();
	}

	@Override
	public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Must match the current connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase: I_HAVE_AUTHORIZATION"),
			new ModuleOption("MECHANISM", mechanismIds().isEmpty() ? "" : mechanismIds().get(0), true, false,
				"Mechanism to reproduce", mechanismIds()),
			new ModuleOption("PAYLOAD", "console_injection", true, false,
				"Payload provider to demonstrate it with", payloadIds()),
			new ModuleOption("MODE", "FIXTURE", false, false,
				"FIXTURE is a modelled target, IMPLEMENTATION demands real code that implements the pattern, "
					+ "LIVE is the connected server", List.of("FIXTURE", "IMPLEMENTATION", "LIVE")),
			new ModuleOption("ACTION", "PLAN", true, false,
				"PLAN describes what a verification would do and runs nothing; VERIFY executes it after ACK",
				List.of("PLAN", "VERIFY")),
			new ModuleOption("ACK", "", false, true,
				"Required for ACTION VERIFY: I_ACKNOWLEDGE_FIXTURE_EXECUTION")
		);
	}

	@Override
	public PayloadEffect effect() {
		return PayloadEffect.auditArtifact("reproduction.txt");
	}

	@Override
	public PayloadEffect effect(Map<String, String> options) {
		// Planning declares nothing, because planning does nothing.
		if (!isVerify(options)) return PayloadEffect.none();
		PayloadProvider payload = ReproductionEngine.payload(options.getOrDefault("PAYLOAD", ""));
		if (payload == null) return PayloadEffect.none();
		return switch (payload.describe().kind()) {
			case INERT_ARTIFACT -> PayloadEffect.auditArtifact("reproduction.txt");
			case BOUNDED_VERIFICATION -> PayloadEffect.chatCommand("/say champions-proof");
			case EXECUTION_CAPABLE -> PayloadEffect.none();
		};
	}

	@Override
	public String revertPlan() {
		return "nothing is left behind: fixtures are reset after every run and the audit folder keeps at most one "
			+ "reproduction document";
	}

	@Override
	public boolean performsActiveAction(Map<String, String> options) {
		if (!isVerify(options)) return false;
		PayloadProvider payload = ReproductionEngine.payload(options.getOrDefault("PAYLOAD", ""));
		return payload != null && payload.describe().kind() == PayloadKind.BOUNDED_VERIFICATION;
	}

	/**
	 * The configuration a plan was made for: everything that changes what would run,
	 * excluding the options that are about the run itself.
	 */
	private static String planKey(String mechanismId, String payloadId,
			GateRequest.ReproductionMode mode, Map<String, String> options) {
		StringBuilder key = new StringBuilder(mechanismId).append('|').append(payloadId).append('|').append(mode);
		java.util.Set<String> operational = java.util.Set.of("ACTION", "ACK", "CONFIRM", "RHOST");
		options.entrySet().stream()
			.filter(entry -> !operational.contains(entry.getKey().toUpperCase(java.util.Locale.ROOT)))
			.sorted(java.util.Map.Entry.comparingByKey())
			.forEach(entry -> key.append('|').append(entry.getKey()).append('=')
				.append(entry.getValue().replace('|', '/')));
		return key.toString();
	}

	private static boolean isVerify(Map<String, String> options) {
		return "VERIFY".equalsIgnoreCase(options.getOrDefault("ACTION", "PLAN").strip());
	}

	@Override
	public CompletionStage<ModuleResult> runActive(ModuleContext context,
			Map<String, String> options, ActiveModuleRuntime runtime) {
		String mechanismId = options.getOrDefault("MECHANISM", "").strip();
		String payloadId = options.getOrDefault("PAYLOAD", "").strip();
		GateRequest.ReproductionMode mode = switch (options.getOrDefault("MODE", "FIXTURE").strip()
			.toUpperCase(java.util.Locale.ROOT)) {
			case "LIVE" -> GateRequest.ReproductionMode.LIVE;
			case "IMPLEMENTATION" -> GateRequest.ReproductionMode.IMPLEMENTATION;
			default -> GateRequest.ReproductionMode.FIXTURE;
		};
		if (!context.connected() && mode == GateRequest.ReproductionMode.LIVE) {
			return CompletableFuture.completedFuture(new ModuleResult(
				"No connection: a live verification needs a session",
				List.of(new Finding(Finding.Severity.INFO, "Not connected",
					"The LIVE mode sends a bounded request to the connected server.",
					"Connect first, or leave MODE at FIXTURE.")),
				List.of(EvidenceNode.observed("Connection", "none", "Nothing ran."))));
		}

		PayloadProvider payload = ReproductionEngine.payload(payloadId);
		ReproductionEngine engine = new ReproductionEngine(folder, runtime);
		boolean authorized = context.connected();
		boolean confirmed = !options.getOrDefault("CONFIRM", "").isBlank();

		// Activating the module produces a plan. Executing it is a separate decision,
		// taken by the operator, with its own acknowledgement.
		if (!isVerify(options)) {
			dev.champions.core.exploit.VerificationPlan plan =
				engine.plan(mechanismId, payload, mode, authorized, confirmed, options);
			pendingPlan = plan;
			pendingKey = planKey(mechanismId, payloadId, mode, options);
			return CompletableFuture.completedFuture(planResult(plan, mode));
		}

		// Verification executes the plan that was shown and acknowledged, not a fresh one.
		String key = planKey(mechanismId, payloadId, mode, options);
		dev.champions.core.exploit.VerificationPlan approved = pendingPlan;
		if (approved == null || !pendingKey.equals(key)) {
			return CompletableFuture.completedFuture(new ModuleResult(
				"No plan to acknowledge: run ACTION PLAN for this configuration first",
				List.of(new Finding(Finding.Severity.LOW, "Nothing to acknowledge", approved == null
						? "This module has not produced a plan since it was loaded."
						: "The configuration changed since the last plan, so the old approval does not apply.",
					"Set ACTION PLAN, read the plan, then set ACTION VERIFY and ACK.")),
				List.of(EvidenceNode.observed("Plan", "none", "Planning is what ACK acknowledges."))));
		}

		return engine.verify(approved, authorized, confirmed, options.getOrDefault("ACK", "").strip(), options)
			.thenApply(report -> reportResult(report, mechanismId, payloadId));
	}

	/** The plan, rendered so an operator can decide before anything runs. */
	private ModuleResult planResult(VerificationPlan plan, GateRequest.ReproductionMode mode) {
		if (!plan.refusal().isBlank()) {
			return new ModuleResult("No verification plan: " + plan.refusal(),
				List.of(new Finding(Finding.Severity.LOW, "Nothing to verify", plan.refusal(),
					"Pick a bounded or execution-capable payload, and a mechanism that accepts it.")),
				List.of(EvidenceNode.observed("Plan", "refused", plan.refusal())));
		}

		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO,
			"Verification plan for " + plan.mechanismName() + " with " + plan.payloadId(),
			"Nothing has run. " + plan.stages().size() + " stage(s) would run against " + target(mode)
				+ ": a control, then " + plan.samples() + " payload observation(s) because the mechanism is "
				+ "declared " + plan.determinism().name().toLowerCase(java.util.Locale.ROOT) + ".",
			plan.executable()
				? "Set ACTION VERIFY and ACK " + plan.acknowledgementPhrase() + " to execute this plan."
				: "The plan cannot execute as written; read the stage refusals below."));
		for (VerificationPlan.Stage stage : plan.stages()) {
			findings.add(new Finding(Finding.Severity.INFO,
				stage.stage() + ": " + stage.description() + (stage.allowed() ? " (would be allowed)" : " (would be refused)"),
				stage.expectation(),
				stage.allowed() ? "" : "Gate: " + stage.gateReason()));
		}
		findings.add(new Finding(Finding.Severity.INFO, "Why a control stage exists",
			"The control carries the same shape as the payload with a benign value and must enter the same sink "
				+ "operation. An effect is credited to the payload only when the control did not produce it and it "
				+ "names the payload's own data; otherwise the run reports INCONCLUSIVE.",
			"This is what stops a permissive sink, or a patched build, from being reported as vulnerable."));
		findings.add(new Finding(Finding.Severity.INFO, "What could be verified here",
			plan.referenceImplementation()
				? "A real implementation of this pattern is in the build, so this run can reach IMPLEMENTATION_VERIFIED."
				: "This mechanism has a modelled sink only, so the strongest verdict available is FIXTURE_VERIFIED. "
					+ "MODE IMPLEMENTATION will be refused rather than granted and then over-claimed.",
			plan.referenceImplementation() ? "" : "That is a property of the build, not a setting."));
		findings.add(new Finding(Finding.Severity.INFO, "Cleanup", plan.cleanup(), "Declared by the payload."));

		List<EvidenceNode> evidence = List.of(EvidenceNode.group("Plan", List.of(
			EvidenceNode.observed("Mechanism", plan.mechanismId(), "From the mechanism registry."),
			EvidenceNode.observed("Payload", plan.payloadId(), "Declares its own kind, shape and cleanup."),
			EvidenceNode.observed("Kind", plan.payloadKind().name(), "What the gate will authorise."),
			EvidenceNode.observed("Stages", Integer.toString(plan.stages().size()),
				"A control plus " + plan.samples() + " payload observation(s); every stage is gated separately."),
			EvidenceNode.observed("Determinism", plan.determinism().name(),
				"How many observations this mechanism needs before one success means anything."),
			EvidenceNode.observed("Reference implementation", Boolean.toString(plan.referenceImplementation()),
				"Whether real code implementing the pattern exists, as opposed to a model of it."),
			EvidenceNode.observed("Executed", "nothing", "Planning performs no write, send or invocation."))));

		return new ModuleResult(plan.executable()
			? "Plan ready: " + plan.stages().size() + " stage(s), nothing executed"
			: "Plan cannot run: " + plan.stages().stream().filter(stage -> !stage.allowed()).count()
				+ " stage(s) would be refused, nothing executed", findings, evidence);
	}

	/** The report, with the verdict and the reasoning that produced it. */
	private ModuleResult reportResult(VerificationReport report, String mechanismId, String payloadId) {
		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(severity(report.verdict()), stateLabel(report.verdict()), report.summary(),
			recommendation(report)));
		for (StageOutcome stage : report.stages()) {
			findings.add(new Finding(Finding.Severity.INFO,
				stage.stage() + " -> " + (stage.allowed() ? (stage.reached() ? "reached the sink" : "did not reach")
					: "refused by the gate"),
				stage.allowed() ? stage.observed() : stage.gateReason(),
				stage.allowed() ? "Expected: " + stage.expected() : ""));
		}
		if (!report.claims().isBlank()) {
			findings.add(new Finding(Finding.Severity.INFO, "What this verdict may be used to claim",
				report.claims(), "Anything stronger needs a server-side observation."));
		}
		for (String note : report.notes()) {
			findings.add(new Finding(Finding.Severity.INFO, "Note", clip(note, 300), ""));
		}

		ExploitMechanism<?> mechanism = MechanismRegistry.get(mechanismId);
		List<EvidenceNode> evidence = new ArrayList<>();
		evidence.add(EvidenceNode.group("Verification", List.of(
			EvidenceNode.observed("Verdict", report.verdict().name(), "Decided by the stages, not by the module."),
			EvidenceNode.observed("Basis", report.basis().name(),
				"FIXTURE is a model of the seam, IMPLEMENTATION is real code that implements it, LIVE is the server."),
			EvidenceNode.observed("Discriminative", Boolean.toString(report.discriminative()),
				"The control did not produce the effect the payload produced."),
			EvidenceNode.observed("Same sink path", Boolean.toString(report.samePath()),
				"Whether the control entered the same sink operation as the payload."),
			EvidenceNode.observed("Attributed", report.attributed() + " of " + report.requiredSamples(),
				"Observations that landed on the payload's own data, out of the number required."),
			EvidenceNode.observed("Mechanism", report.mechanismId(), "From the mechanism registry."),
			EvidenceNode.observed("Payload", report.payloadId().isBlank() ? "none" : report.payloadId(),
				"Supplied the control and the payload, independently of the mechanism."))));
		if (mechanism != null) {
			evidence.add(EvidenceNode.group("Fixtures", List.of(
				EvidenceNode.observed("Fixture", mechanism.fixtureId(), "Reset before and after every stage."))));
		}

		return new ModuleResult(report.verdict().name() + " - " + report.summary(), findings, evidence);
	}

	private ModuleResult toModuleResult(ReproductionResult result, String mechanismId, String payloadId,
			ReproductionEngine engine) {
		ExploitMechanism<?> mechanism = MechanismRegistry.get(mechanismId);
		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(severity(result.state()), stateLabel(result.state()), result.summary(),
			recommendation(result)));
		if (mechanism != null && mechanism.entry() != null) {
			findings.add(new Finding(Finding.Severity.INFO, "Mechanism: " + mechanism.entry().plugin(),
				clip(mechanism.entry().mechanism(), 320),
				clip(mechanism.entry().caveats(), 200)));
		}
		if (!result.observed().isBlank()) {
			findings.add(new Finding(Finding.Severity.INFO, "Observed",
				clip(result.observed(), 360), "What the fixture or the runtime recorded, not what it implies."));
		}
		if (!result.expected().isBlank()) {
			findings.add(new Finding(Finding.Severity.INFO, "Expected on a healthy implementation",
				clip(result.expected(), 300), "The difference between the two is the finding."));
		}
		for (String note : result.notes()) {
			findings.add(new Finding(Finding.Severity.INFO, "Note", clip(note, 300), ""));
		}

		List<EvidenceNode> evidence = new ArrayList<>();
		evidence.add(EvidenceNode.group("Reproduction", List.of(
			EvidenceNode.observed("State", result.state().name(), "One of the explicit reproduction states."),
			EvidenceNode.observed("Basis", result.basis().name(),
				"ARTIFACT is a document, FIXTURE is a controlled target, LIVE is the connected server."),
			EvidenceNode.observed("Mechanism", result.mechanismId(), "From the mechanism registry."),
			EvidenceNode.observed("Payload", result.payloadId().isBlank() ? "none" : result.payloadId(),
				"The provider that supplied the demonstration, independent of the mechanism."))));
		if (mechanism != null) {
			evidence.add(EvidenceNode.group("Fixture", List.of(
				EvidenceNode.observed("Fixture", mechanism.fixtureId(),
					"Reset before and after every run; nothing outside it is touched."))));
		}

		String summary = result.state().name() + " - " + result.summary();
		return new ModuleResult(summary, findings, evidence);
	}

	private static String target(GateRequest.ReproductionMode mode) {
		return switch (mode) {
			case LIVE -> "the connected server";
			case IMPLEMENTATION -> "a real implementation of the pattern";
			case FIXTURE -> "the controlled fixture";
			case NONE -> "nothing";
		};
	}

	private static String stateLabel(ReproductionState state) {
		return switch (state) {
			case FIXTURE_VERIFIED -> "Reproduced against a model";
			case IMPLEMENTATION_VERIFIED -> "Reproduced against a real implementation";
			case TARGET_VERIFIED -> "Reproduced against the target";
			case POTENTIALLY_VULNERABLE -> "Potentially vulnerable";
			case NOT_REPRODUCED -> "Not reproduced";
			case NOT_TESTED -> "Not tested";
			case BLOCKED_BY_GATE -> "Blocked by the gate";
			case ARTIFACT_ONLY -> "Artifact only";
			case INCONCLUSIVE -> "Inconclusive";
			case ERROR -> "Error";
		};
	}

	private static Finding.Severity severity(ReproductionState state) {
		return switch (state) {
			case FIXTURE_VERIFIED, IMPLEMENTATION_VERIFIED, TARGET_VERIFIED -> Finding.Severity.HIGH;
			case POTENTIALLY_VULNERABLE -> Finding.Severity.MEDIUM;
			case NOT_REPRODUCED, NOT_TESTED, ARTIFACT_ONLY -> Finding.Severity.INFO;
			case INCONCLUSIVE -> Finding.Severity.MEDIUM;
			case BLOCKED_BY_GATE -> Finding.Severity.LOW;
			case ERROR -> Finding.Severity.LOW;
		};
	}

	private static String recommendation(VerificationReport report) {
		return switch (report.verdict()) {
			case FIXTURE_VERIFIED -> "The mechanism is real and the payload discriminates, against a model. Confirm "
				+ "the target's build and configuration before reporting it as a live finding: this verdict is about "
				+ "the seam, not the plugin build.";
			case IMPLEMENTATION_VERIFIED -> "Real code implementing the pattern did the privileged thing. Establish "
				+ "that the target runs that code before reporting it: this verdict is about the bug class, not the "
				+ "build.";
			case TARGET_VERIFIED -> "The target demonstrated the behaviour inside the authorised scope of this run.";
			case NOT_REPRODUCED -> "The control was refused and the payload did not reach the sink. Do not report this "
				+ "as a vulnerability.";
			case INCONCLUSIVE -> "The run cannot attribute the effect to the payload. Do not report this as a "
				+ "vulnerability; the control stage exists to prevent exactly this.";
			case BLOCKED_BY_GATE -> "The gate refused; nothing ran. Read the stage reasons before trying another payload.";
			case NOT_TESTED -> "Nothing was attempted.";
			case POTENTIALLY_VULNERABLE -> "A surface match only; verify it before reporting.";
			case ARTIFACT_ONLY -> "A document was written. It is not evidence that the target is vulnerable.";
			case ERROR -> "The run failed. Fix the cause before drawing any conclusion.";
		};
	}

	private static String recommendation(ReproductionResult result) {
		return switch (result.state()) {
			case FIXTURE_VERIFIED, IMPLEMENTATION_VERIFIED, TARGET_VERIFIED ->
				"The payload reached the sink. Run ACTION VERIFY for a verdict with a control behind it.";
			case NOT_REPRODUCED -> "The payload did not reach the sink. Do not report this as a vulnerability.";
			case BLOCKED_BY_GATE -> "The gate refused; nothing ran. Read the reason before trying another payload.";
			case ARTIFACT_ONLY -> "A document was written. It is not evidence that the target is vulnerable.";
			case NOT_TESTED -> "Nothing was attempted.";
			case POTENTIALLY_VULNERABLE -> "A surface match only; reproduce it before reporting.";
			case INCONCLUSIVE -> "A single attempt cannot attribute the effect to the payload. Run ACTION VERIFY.";
			case ERROR -> "The attempt failed. Fix the cause before drawing any conclusion.";
		};
	}

	private static String clip(String text, int max) {
		if (text == null) return "";
		String flat = text.replace('\n', ' ').strip();
		if (flat.length() <= max) return flat;
		int space = flat.lastIndexOf(' ', max);
		return (space > max / 2 ? flat.substring(0, space) : flat.substring(0, max)).strip() + " ...";
	}
}
