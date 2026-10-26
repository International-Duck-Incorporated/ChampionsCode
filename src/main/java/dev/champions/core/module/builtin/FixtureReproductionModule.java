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
				"Where to run: FIXTURE is a controlled target, LIVE is the connected server", List.of("FIXTURE", "LIVE"))
		);
	}

	@Override
	public PayloadEffect effect() {
		return PayloadEffect.auditArtifact("reproduction.txt");
	}

	@Override
	public PayloadEffect effect(Map<String, String> options) {
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
		PayloadProvider payload = ReproductionEngine.payload(options.getOrDefault("PAYLOAD", ""));
		return payload != null && payload.describe().kind() == PayloadKind.BOUNDED_VERIFICATION;
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

		return engine.reproduce(mechanismId, payload, mode, authorized, confirmed, options)
			.thenApply(result -> toModuleResult(result, mechanismId, payloadId, engine));
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
			case ERROR -> "Error";
		};
	}

	private static Finding.Severity severity(ReproductionState state) {
		return switch (state) {
			case VERIFIED -> Finding.Severity.HIGH;
			case POTENTIALLY_VULNERABLE -> Finding.Severity.MEDIUM;
			case NOT_REPRODUCED, NOT_TESTED, ARTIFACT_ONLY -> Finding.Severity.INFO;
			case BLOCKED_BY_GATE -> Finding.Severity.LOW;
			case ERROR -> Finding.Severity.LOW;
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
