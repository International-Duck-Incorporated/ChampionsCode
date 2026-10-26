package dev.champions.core.catalog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completeness claim, checked instead of asserted.
 *
 * <p>{@code META-INF/port-manifest.tsv} has one row per class named {@code *Module.java} in
 * the venom4plugin 0.3.0 archive. Every row says what became of that class. These tests fail
 * the build when the archive and the manifest disagree, when a disposition is invented, or
 * when a catalogue entry exists that no source module traces back to.</p>
 *
 * <p>They cannot read the archive itself -- it is not shipped in this repository -- so the
 * manifest is the record, and the test pins its shape. The point is that a dropped row or a
 * wrong reference is a build failure rather than a sentence in a document that nobody
 * re-reads.</p>
 */
class PortManifestTest {

	private static final Set<String> DISPOSITIONS =
			Set.of("ported", "merged", "check", "catalogue", "excluded", "base");

	record Row(String file, String kind, String disposition, String reference, boolean registered, String detail) {
		boolean isCatalogueOnly() { return disposition.equals("catalogue"); }
		boolean isBase() { return disposition.equals("base"); }
	}

	private static List<Row> rows() {
		InputStream in = PortManifestTest.class.getResourceAsStream("/META-INF/port-manifest.tsv");
		assertNotNull(in, "port-manifest.tsv must ship in the jar");
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			List<Row> rows = new ArrayList<>();
			for (String line : reader.lines().toList()) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] parts = line.split("\t", -1);
				assertEquals(6, parts.length, "malformed row: " + line);
				assertTrue(parts[4].equals("yes") || parts[4].equals("no"), "registered flag: " + line);
				rows.add(new Row(parts[0], parts[1], parts[2], parts[3], parts[4].equals("yes"), parts[5]));
			}
			return rows;
		} catch (Exception e) {
			throw new AssertionError("could not read the port manifest", e);
		}
	}

	@Test void everyArchiveClassHasExactlyOneRow() {
		List<Row> rows = rows();
		assertEquals(48, rows.size(), "the archive has 48 classes named *Module.java");
		Set<String> files = new HashSet<>();
		for (Row row : rows) {
			assertTrue(row.file().endsWith("Module.java"), row.file());
			assertTrue(files.add(row.file()), "duplicate row for " + row.file());
		}
	}

	@Test void oneAbstractBaseAndFortySevenModules() {
		List<Row> rows = rows();
		List<Row> base = rows.stream().filter(Row::isBase).toList();
		assertEquals(1, base.size(), "exactly one abstract base");
		assertEquals("PocModule.java", base.get(0).file());
		assertEquals("base", base.get(0).kind());
		assertEquals(47, rows.size() - 1, "47 classes are registered modules");
		assertTrue(rows.stream().filter(r -> !r.isBase()).allMatch(r -> r.kind().equals("module")));
	}

	@Test void thirtyThreeClassesWereEverRegistered() {
		long registered = rows().stream().filter(Row::registered).count();
		assertEquals(33, registered,
				"Venom4Plugin.onInitialize has 33 Modules.get().add(...) calls, whatever its log line says");
		assertFalse(rows().stream().filter(Row::isBase).findFirst().orElseThrow().registered(),
				"the abstract base is never registered");
	}

	@Test void dispositionsAreKnown() {
		for (Row row : rows()) {
			assertTrue(DISPOSITIONS.contains(row.disposition()),
					row.file() + " has an unknown disposition: " + row.disposition());
			assertFalse(row.detail().isBlank(), row.file() + " must say why");
		}
	}

	@Test void everyReferenceResolves() {
		for (Row row : rows()) {
			if (row.reference().isBlank()) continue;
			assertTrue(ExploitCatalog.byId(row.reference()).isPresent(),
					row.file() + " points at a catalogue entry that does not exist: " + row.reference());
		}
	}

	@Test void everyCatalogueEntryTracesBackToASourceModule() {
		Set<String> referenced = rows().stream()
				.map(Row::reference)
				.filter(s -> !s.isBlank())
				.collect(Collectors.toCollection(HashSet::new));
		Set<String> known = ExploitCatalog.entries().stream().map(ExploitCatalog.Entry::id)
				.collect(Collectors.toCollection(HashSet::new));
		assertEquals(known, referenced,
				"an entry with no source module behind it, or a reference to an entry that is not there");
	}

	@Test void catalogueOnlyRowsShipNoArtifact() {
		for (Row row : rows()) {
			if (!row.isCatalogueOnly() || row.reference().isBlank()) continue;
			ExploitCatalog.Entry entry = ExploitCatalog.byId(row.reference()).orElseThrow();
			assertFalse(entry.hasArtifact(),
					row.file() + " is catalogue only but " + entry.id() + " ships an artifact");
		}
	}

	@Test void theOnlyExcludedModuleSaysWhy() {
		List<Row> excluded = rows().stream().filter(r -> r.disposition().equals("excluded")).toList();
		assertEquals(1, excluded.size());
		assertEquals("ZAuctionHouseSqlInjectionModule.java", excluded.get(0).file());
		assertTrue(excluded.get(0).detail().toLowerCase().contains("destructive"));
	}
}
