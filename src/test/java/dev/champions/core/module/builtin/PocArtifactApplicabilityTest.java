package dev.champions.core.module.builtin;

import dev.champions.core.catalog.AuditFolder;
import dev.champions.core.catalog.CatalogApplicability;
import dev.champions.core.catalog.ExploitCatalog;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the operator finds inside the file, not just in the console.
 *
 * <p>A reproduction written today gets read against a server built next year. The
 * artifact has to carry the version verdict with it, or the first thing it does is
 * send someone chasing a bug that was fixed before they downloaded the tool.</p>
 */
class PocArtifactApplicabilityTest {

	@TempDir Path dir;

	private static ModuleContext session() {
		return new ModuleContext(true, false, false, true, "play.example.com", "vanilla", "26.3", 771,
			42L, 7, "enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
	}

	private PocArtifactModule armed() {
		PocArtifactModule module = new PocArtifactModule();
		module.useAuditFolder(AuditFolder.under(dir));
		return module;
	}

	private ModuleResult run(PocArtifactModule module, String entryId, boolean revert) {
		Map<String, String> options = new HashMap<>();
		options.put("ENTRY", entryId);
		options.put("CONFIRM", "I_HAVE_AUTHORIZATION");
		options.put("OVERWRITE", "true");
		if (revert) options.put("REVERT", "true");
		return module.runActive(session(), options, null).toCompletableFuture().join();
	}

	private Path writtenPath(String entryId) {
		ExploitCatalog.Entry entry = ExploitCatalog.byId(entryId).orElseThrow();
		// AuditFolder.under(configDir) puts artifacts in <configDir>/artifacts/.
		return dir.resolve("artifacts").resolve(entry.artifactFile());
	}

	private static String firstWithArtifact(CatalogApplicability.Status status) {
		for (ExploitCatalog.Entry entry : ExploitCatalog.withArtifacts()) {
			var verdict = CatalogApplicability.forEntry(entry.id()).orElse(null);
			if (verdict != null && verdict.status() == status) return entry.id();
		}
		throw new AssertionError("no catalogued artifact has status " + status
			+ "; the test would silently check nothing");
	}

	@Test void aStandingArtifactCarriesItsVerdictAndKeepsItsBody() throws Exception {
		String id = firstWithArtifact(CatalogApplicability.Status.STANDING);
		ExploitCatalog.Entry entry = ExploitCatalog.byId(id).orElseThrow();
		ModuleResult result = run(armed(), id, false);

		Path written = writtenPath(id);
		assertTrue(Files.exists(written), "the module said '" + result.summary() + "' but wrote nothing");
		String text = Files.readString(written);

		assertTrue(text.contains("\nChampionsCode applicability: " + entry.plugin() + " / " + id + "\n"),
			"header missing from " + entry.artifactFile() + ": " + text.substring(0, Math.min(200, text.length())));
		assertTrue(text.contains("Status: STANDING"), text.substring(0, Math.min(300, text.length())));
		assertTrue(text.contains("Checked: 2026-10-26"), "the header must date the check");
		assertTrue(text.contains("versions, not about the server in front of you"),
			"the header must not overclaim: it describes versions, not the target");
		// The original artifact is still there, byte for byte, below the header.
		assertTrue(text.endsWith(entry.artifact()), "the catalogue artifact must not be rewritten");
	}

	@Test void anUnverifiedArtifactSaysSoBeforeAnyoneRunsIt() throws Exception {
		String id = firstWithArtifact(CatalogApplicability.Status.UNVERIFIED);
		ExploitCatalog.Entry entry = ExploitCatalog.byId(id).orElseThrow();
		ModuleResult result = run(armed(), id, false);

		String text = Files.readString(writtenPath(id));
		assertTrue(text.contains("Status: UNVERIFIED"), text.substring(0, Math.min(300, text.length())));
		assertTrue(text.contains("not confirmed against upstream"),
			"an unverified entry must say so in the file it writes");

		List<String> details = result.findings().stream().map(Finding::detail).toList();
		assertTrue(details.stream().anyMatch(d -> d.contains("not confirmed against upstream")),
			"the finding does not carry the verdict: " + details);
		assertTrue(result.findings().stream().noneMatch(f -> f.severity() == Finding.Severity.MEDIUM),
			"an unverified entry is not a warning about a fixed bug; it must not be raised to MEDIUM");
	}

	@Test void nothingFixedUpstreamCanStillWriteAnArtifact() {
		// This is the property the whole table exists for. If a future edit gives the
		// Geyser or WorldEdit entry a file to write, a run would hand the operator a
		// reproduction that cannot work, and the header alone is not enough.
		for (ExploitCatalog.Entry entry : ExploitCatalog.withArtifacts()) {
			var verdict = CatalogApplicability.forEntry(entry.id()).orElseThrow();
			assertFalse(verdict.patchedInCurrentBuilds(),
				entry.id() + " is " + verdict.status() + " yet still offers an artifact ("
					+ entry.artifactFile() + ")");
		}
		// The two entries that no longer reproduce are report-only, by name.
		for (String id : CatalogApplicability.notReproducibleOnCurrentBuilds()) {
			assertFalse(ExploitCatalog.byId(id).orElseThrow().hasArtifact(),
				id + " no longer reproduces on current builds but still has an artifact");
		}
	}

	@Test void askingToRevertRemovesTheHeadedFile() {
		String id = firstWithArtifact(CatalogApplicability.Status.STANDING);
		run(armed(), id, false);
		assertTrue(Files.exists(writtenPath(id)));

		run(armed(), id, true);
		assertFalse(Files.exists(writtenPath(id)), "revert left the file behind");
	}

	@Test void theHeaderIsPlainTextAndSizedForTheCeiling() {
		// The header is added to every artifact, so it has to be small enough that a
		// catalogued file plus header still passes the 8 KB ceiling -- the payload
		// rules are checked before the write, though, so this is a budget check.
		for (ExploitCatalog.Entry entry : ExploitCatalog.withArtifacts()) {
			var verdict = CatalogApplicability.forEntry(entry.id()).orElseThrow();
			int budget = entry.artifact().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
				+ headerLength(entry, verdict);
			assertTrue(dev.champions.core.module.payload.PayloadSafety.acceptableArtifactSize(budget),
				entry.id() + " would exceed the artifact ceiling once headed (" + budget + " bytes)");
		}
	}

	private static int headerLength(ExploitCatalog.Entry entry, CatalogApplicability.Applicability verdict) {
		// Same shape as the module's header, measured rather than assumed.
		String text = "-".repeat(70) + "\nChampionsCode applicability: " + entry.plugin() + " / "
			+ entry.id() + "\nStatus: " + verdict.status() + "\n" + verdict.describe()
			+ "\nAffected: " + verdict.affected()
			+ (verdict.fixedIn().isBlank() ? "" : " | Fixed in: " + verdict.fixedIn())
			+ " | Latest seen: " + verdict.latestSeen() + "\nChecked: " + verdict.checkedOn()
			+ (verdict.evidence().isBlank() ? "" : " | Basis: " + verdict.evidence())
			+ "\nThis is a statement about versions, not about the server in front of you.\n"
			+ "-".repeat(70) + "\n\n";
		return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
	}
}
