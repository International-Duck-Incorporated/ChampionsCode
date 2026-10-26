package com.internationalduck.championscode.module.audit;

import dev.champions.core.mod.ModPosture;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The mods this client loaded, and what they mean for an audit.
 *
 * <p>This is the mod-side counterpart to the plugin inventory. It belongs in the
 * client source set because {@code FabricLoader} does: the analysis is in
 * {@link ModPosture}, which is pure and tested without a game, and this class only
 * fills it in.</p>
 *
 * <p>Two things are kept apart on purpose. The inventory below is what this client
 * loaded, and says nothing about the server. The channel identifiers are the only
 * evidence about the other end, and only for mods this client can decode. Neither
 * is allowed to stand in for the other, and the report says so in a finding rather
 * than in a footnote.</p>
 *
 * <p>Nothing is sent. Channel identifiers are read from telemetry that never kept a
 * payload body.</p>
 */
public final class InstalledModsModule implements ResearchModule {

	@Override public String id() { return "auxiliary/audit/mod_posture"; }

	@Override public String name() { return "Installed mods and their audit surface"; }

	@Override public String description() {
		return "Inventories the mods loaded in this client, names the ones that change what a server accepts, "
			+ "and reports which mod channels the connection is actually carrying. Sends nothing.";
	}

	@Override
	public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("INCLUDE_LIBRARIES", "false", false, false,
				"true: also list library and client-only mods individually", List.of("true", "false"))
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		List<ModPosture.InstalledMod> mods = capture();
		Set<String> channels = channels();
		ModPosture.Result result = ModPosture.analyse(mods, channels, context.serverBrand());

		List<dev.champions.core.module.Finding> findings = new ArrayList<>(result.findings());
		boolean includeLibraries = "true".equalsIgnoreCase(options.getOrDefault("INCLUDE_LIBRARIES", "false").strip());
		if (includeLibraries) {
			List<String> all = mods.stream()
				.map(mod -> mod.id() + "@" + mod.version())
				.sorted()
				.toList();
			findings.add(new dev.champions.core.module.Finding(dev.champions.core.module.Finding.Severity.INFO,
				"Full inventory (" + all.size() + ")",
				String.join(", ", all),
				"Copy this list into a change record before updating the pack."));
		}
		return new ModuleResult(result.summary(), findings, result.evidence());
	}

	/** The mods this client loaded, minus the ones the loader injects itself. */
	private static List<ModPosture.InstalledMod> capture() {
		List<ModPosture.InstalledMod> mods = new ArrayList<>();
		try {
			for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
				var metadata = container.getMetadata();
				String id = metadata.getId();
				if (id == null || id.isBlank()) continue;
				String name = metadata.getName() == null ? id : metadata.getName();
				String version = metadata.getVersion() == null ? "unknown"
					: metadata.getVersion().getFriendlyString();
				String source = container.getOrigin() == null || container.getOrigin().getKind() == null
					? "unknown" : container.getOrigin().getKind().name().toLowerCase(java.util.Locale.ROOT);
				mods.add(new ModPosture.InstalledMod(id, name, version, source));
			}
		} catch (RuntimeException | LinkageError loaderUnavailable) {
			return List.of();
		}
		mods.sort(java.util.Comparator.comparing(ModPosture.InstalledMod::id));
		return mods;
	}

	/** Channel identifiers seen on this connection; empty when nothing was sampled. */
	private static Set<String> channels() {
		try {
			return new TreeSet<>(dev.champions.client.PacketTelemetry.channels());
		} catch (RuntimeException | LinkageError unavailable) {
			return Set.of();
		}
	}
}
