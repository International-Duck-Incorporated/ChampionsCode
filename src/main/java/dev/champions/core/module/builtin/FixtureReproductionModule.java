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
				"Where to run: FIXTURE is a controlled target, LIVE is the connected server", List.of("FIXTURE", "LIVE")),
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

	private static boolean isVerify(Map<String, String> options) {
		return "VERIFY".equalsIgnoreCase(options.getOrDefault("ACTION", "PLAN").strip());
	}

	@Override
	public CompletionStage<ModuleResult> runActive(ModuleContext context,
			Map<String, String> options, ActiveModuleRuntime runtime) {
		String mechanismId = options.getOrDefault("MECHANISM", "").strip();
		String payloadId = options.getOrDefault("PAYLOAD", "").strip();
		GateRequest.ReproductionMode mode = "LIVE".equalsIgnoreCase(options.getOrDefault("MODE", "FIXTURE").strip())
			? GateRequest.ReproductionMode.LIVE
			: GateRequest.ReproductionMode.FIXTURE;
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
			return CompletableFuture.completedFuture(
				planResult(engine.plan(mechanismId, payload, mode, authorized, confirmed, options), mode));
		}

		return engine.verify(mechanismId, payload, mode, authorized, confirmed,
				options.getOrDefault("ACK", "").strip(), options)
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
			"Nothing has run. " + plan.stages().size() + " stage(s) would run against "
				+ (mode == GateRequest.ReproductionMode.LIVE ? "the connected server" : "the controlled fixture") + ".",
			plan.executable()
				? "Set ACTION VERIFY and ACK " + plan.acknowledgementPhrase() + " to execute this plan."
				: "The plan cannot execute as written; read the stage refusals below."));
		for (VerificationPlan.Stage stage : plan.stages()) {
			findings.add(new Finding(Finding.Severity.INFO,
				stage.stage() + ": " + stage.material() + (stage.allowed() ? " (would be allowed)" : " (would be refused)"),
				stage.expectation(),
				stage.allowed() ? "" : "Gate: " + stage.gateReason()));
		}
		findings.add(new Finding(Finding.Severity.INFO, "Why a control stage exists",
			"The control carries the same shape with a benign value. If the control also reaches the sink, the "
				+ "mechanism is not discriminating and the run will report INCONCLUSIVE rather than VERIFIED.",
			"This is what stops a permissive fixture, or a patched build, from being reported as vulnerable."));
		findings.add(new Finding(Finding.Severity.INFO, "Cleanup", plan.cleanup(), "Declared by the payload."));

		List<EvidenceNode> evidence = List.of(EvidenceNode.group("Plan", List.of(
			EvidenceNode.observed("Mechanism", plan.mechanismId(), "From the mechanism registry."),
			EvidenceNode.observed("Payload", plan.payloadId(), "Declares its own kind, shape and cleanup."),
			EvidenceNode.observed("Kind", plan.payloadKind().name(), "What the gate will authorise."),
			EvidenceNode.observed("Stages", Integer.toString(plan.stages().size()),
				"CONTROL, PAYLOAD and REPEAT; every stage is gated separately."),
			EvidenceNode.observed("Executed", "nothing", "Planning performs no write, send or invocation."))));

		return new ModuleResult("Plan ready: " + plan.stages().size() + " stage(s), nothing executed", findings, evidence);
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
		if (!report.claimCeiling().isBlank()) {
			findings.add(new Finding(Finding.Severity.INFO, "What this verdict may be used to claim",
				report.claimCeiling(), "Anything stronger needs a server-side observation."));
		}
		for (String note : report.notes()) {
			findings.add(new Finding(Finding.Severity.INFO, "Note", clip(note, 300), ""));
		}

		ExploitMechanism<?> mechanism = MechanismRegistry.get(mechanismId);
		List<EvidenceNode> evidence = new ArrayList<>();
		evidence.add(EvidenceNode.group("Verification", List.of(
			EvidenceNode.observed("Verdict", report.verdict().name(), "Decided by the stages, not by the module."),
			EvidenceNode.observed("Basis", report.basis().name(),
				"FIXTURE is a controlled target; LIVE is the connected server."),
			EvidenceNode.observed("Discriminative", Boolean.toString(report.discriminative()),
				"The control did not reach the sink and the payload did."),
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

	private static String stateLabel(ReproductionState state) {
		return switch (state) {
			case VERIFIED -> "Reproduced";
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
			case VERIFIED -> Finding.Severity.HIGH;
			case POTENTIALLY_VULNERABLE -> Finding.Severity.MEDIUM;
			case NOT_REPRODUCED, NOT_TESTED, ARTIFACT_ONLY -> Finding.Severity.INFO;
			case INCONCLUSIVE -> Finding.Severity.MEDIUM;
			case BLOCKED_BY_GATE -> Finding.Severity.LOW;
			case ERROR -> Finding.Severity.LOW;
		};
	}

	private static String recommendation(VerificationReport report) {
		return switch (report.verdict()) {
			case VERIFIED -> report.basis() == dev.champions.core.exploit.ReproductionBasis.FIXTURE
				? "The mechanism is real and the payload discriminates. Confirm the target's build and configuration "
					+ "before reporting it as a live finding; this verdict is about the seam, not the plugin build."
				: "The target demonstrated the behaviour inside the authorised scope of this run.";
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
			case VERIFIED -> result.basis() == dev.champions.core.exploit.ReproductionBasis.FIXTURE
				? "The mechanism is real. Confirm the target's build and configuration before reporting it as a live finding."
				: "The target demonstrated the behaviour inside the authorised scope.";
			case NOT_REPRODUCED -> "The payload did not reach the sink. Do not report this as a vulnerability.";
			case BLOCKED_BY_GATE -> "The gate refused; nothing ran. Read the reason before trying another payload.";
			case ARTIFACT_ONLY -> "A document was written. It is not evidence that the target is vulnerable.";
			case NOT_TESTED -> "Nothing was attempted.";
			case POTENTIALLY_VULNERABLE -> "A surface match only; reproduce it before reporting.";
			case INCONCLUSIVE -> "The run could not attribute the effect to the payload. Do not report this as a "
				+ "vulnerability; the control stage is there to prevent exactly this.";
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
