package dev.champions.core.module.builtin;

import dev.champions.core.catalog.ArtifactFolderAware;
import dev.champions.core.catalog.AuditFolder;
import dev.champions.core.catalog.ExploitCatalog;
import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.module.payload.PayloadEffect;
import dev.champions.core.module.payload.PayloadModule;
import dev.champions.core.module.payload.PayloadSafety;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Payload tier: writes one reproduction into the audit folder.
 *
 * <p>What the old addon called a "deploy" module — pick a plugin, get a file that
 * demonstrates why it is exploitable. The port keeps the artifact and drops the
 * delivery: nothing here is sent to a server, applied to a configuration, or
 * executed. The file lands in this client's audit folder, and an operator reads
 * it, edits it and decides whether to run it against a server they are authorized
 * to test.</p>
 *
 * <p>The effect is declared before the run and checked by
 * {@link PayloadSafety}: one file, bare name, inert extension, inside the audit
 * folder, at most 8 KB. The revert plan is a deletion, and the module performs it.</p>
 */
public final class PocArtifactModule implements PayloadModule, ArtifactFolderAware {
	private AuditFolder folder = AuditFolder.unavailable();

	@Override public String id() { return "auxiliary/payloads/poc_artifact"; }
	@Override public String name() { return "Proof-of-concept artifact"; }
	@Override public String description() {
		return "Writes one declared reproduction artifact for a catalogued plugin bug into the audit folder.";
	}

	@Override
	public void useAuditFolder(AuditFolder folder) {
		this.folder = folder == null ? AuditFolder.unavailable() : folder;
	}

	@Override
	public List<ModuleOption> options() {
		List<String> ids = ExploitCatalog.withArtifacts().stream().map(ExploitCatalog.Entry::id).toList();
		return List.of(
			new ModuleOption("ENTRY", ids.isEmpty() ? "" : ids.get(0), true, false,
				"Catalogued entry to write a reproduction for", ids),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase: I_HAVE_AUTHORIZATION"),
			new ModuleOption("OVERWRITE", "false", false, false,
				"true: replace an artifact of the same name", List.of("true", "false")),
			new ModuleOption("REVERT", "false", false, false,
				"true: remove the artifact instead of writing it", List.of("true", "false"))
		);
	}

	@Override
	public PayloadEffect effect() {
		// The constant form has no options to read, so it declares nothing and the
		// console asks for the option-aware form below before every run.
		return PayloadEffect.none();
	}

	@Override
	public PayloadEffect effect(Map<String, String> options) {
		String fileName = artifactName(options.getOrDefault("ENTRY", ""));
		return fileName.isBlank() ? PayloadEffect.none() : PayloadEffect.auditArtifact(fileName);
	}

	@Override
	public String revertPlan() {
		return "delete the named artifact from the audit folder (" + folder.describe() + ")";
	}

	@Override
	public CompletionStage<ModuleResult> runActive(ModuleContext context,
			Map<String, String> options, ActiveModuleRuntime runtime) {
		String entryId = options.getOrDefault("ENTRY", "").strip().toLowerCase(java.util.Locale.ROOT);
		ExploitCatalog.Entry entry = ExploitCatalog.byId(entryId).orElse(null);
		if (entry == null) return failed("No catalogued entry '" + entryId + "'.");
		if (!entry.hasArtifact()) return failed("'" + entryId + "' has no portable artifact; it is a report-only entry.");

		String fileName = entry.artifactFile();
		PayloadSafety.Verdict verdict = PayloadSafety.check(PayloadEffect.auditArtifact(fileName));
		if (!verdict.allowed()) return failed("Artifact refused: " + verdict.reason());

		boolean revert = "true".equalsIgnoreCase(options.getOrDefault("REVERT", "false").strip());
		if (revert) return revertRun(entry, fileName);

		boolean overwrite = "true".equalsIgnoreCase(options.getOrDefault("OVERWRITE", "false").strip());
		if (folder.contains(fileName) && !overwrite) {
			return CompletableFuture.completedFuture(new ModuleResult(
				"Artifact already exists: " + fileName,
				List.of(new Finding(Finding.Severity.INFO, "Nothing written",
					fileName + " is already in " + folder.describe() + ".",
					"Set OVERWRITE true to replace it, or REVERT true to remove it.")),
				List.of(EvidenceNode.observed("Audit folder", folder.describe(),
					"Existing files are left alone unless you say otherwise."))));
		}

		AuditFolder.Written written = folder.write(fileName, entry.artifact());
		if (!written.ok()) return failed(written.message());

		List<Finding> findings = List.of(
			new Finding(Finding.Severity.INFO, "Reproduction written: " + fileName,
				entry.mechanism(),
				"Read it before using it. It is inert text: ChampionsCode does not apply, deploy or send it."),
			new Finding(Finding.Severity.INFO, "What this artifact is for",
				"Reproducing " + entry.plugin() + " on a server you are authorized to test.",
				entry.caveats()),
			new Finding(Finding.Severity.INFO, "How to undo this run",
				"Set REVERT true and run again, or delete the file.",
				"Nothing outside the audit folder was touched."));

		List<EvidenceNode> evidence = List.of(
			EvidenceNode.group("Artifact", List.of(
				EvidenceNode.observed("File", fileName, "Bare name inside the audit folder only."),
				EvidenceNode.observed("Location", folder.describe(), "Fixed when the console was built."),
				EvidenceNode.observed("Bytes", Integer.toString(entry.artifact().getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
					"Ceiling is " + PayloadSafety.maximumArtifactBytes() + " bytes."),
				EvidenceNode.observed("Catalogued entry", entry.id() + " (" + entry.plugin() + ")",
					"Mechanism and caveats come from the catalogue, not from this run."))),
			EvidenceNode.group("Basis", List.of(
				EvidenceNode.observed("Sent to the server", "nothing",
					"This module performs no network action; the runtime was not used."),
				EvidenceNode.observed("Revert", revertPlan(), "Deleting the file returns the folder to its previous state."))));

		return CompletableFuture.completedFuture(
			new ModuleResult("Wrote " + fileName + " for " + entry.plugin(), findings, evidence));
	}

	private CompletionStage<ModuleResult> revertRun(ExploitCatalog.Entry entry, String fileName) {
		boolean removed = folder.delete(fileName);
		return CompletableFuture.completedFuture(new ModuleResult(
			removed ? "Removed " + fileName : "No artifact to remove: " + fileName,
			List.of(new Finding(removed ? Finding.Severity.PASS : Finding.Severity.INFO,
				removed ? "Artifact removed" : "Nothing to remove",
				removed ? fileName + " was deleted from " + folder.describe() + "."
					: fileName + " was not in " + folder.describe() + ".",
				"The run left the audit folder as it found it.")),
			List.of(EvidenceNode.observed("Audit folder", folder.describe(),
				"Revert is a deletion, nothing more."),
				EvidenceNode.observed("Entry", entry.id(), "Kept so the audit log names what was written and undone."))));
	}

	private static CompletionStage<ModuleResult> failed(String detail) {
		return CompletableFuture.completedFuture(new ModuleResult(detail,
			List.of(new Finding(Finding.Severity.LOW, "Nothing was written", detail,
				"Fix the cause and run again; the audit folder was not touched.")),
			List.of(EvidenceNode.observed("Artifact", "none", detail))));
	}

	private static String artifactName(String entryId) {
		return ExploitCatalog.byId(entryId).filter(ExploitCatalog.Entry::hasArtifact)
			.map(ExploitCatalog.Entry::artifactFile).orElse("");
	}
}
