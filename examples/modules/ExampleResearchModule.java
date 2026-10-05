package custom;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.util.List;
import java.util.Map;

/**
 * Copy this file anywhere under .minecraft/config/champions-mod/modules/ - any
 * subfolder, at any depth. ChampionsCode compiles and registers it
 * automatically within a couple of seconds, with no hash confirmation and no
 * console command, and reloads it when the file changes. With plugAndPlay=false
 * the manual workflow applies instead: review the files, run java modules hash,
 * confirm that SHA-256, then java modules reload.
 */
public final class ExampleResearchModule implements ResearchModule {
	@Override public String id() { return "auxiliary/custom/session_snapshot"; }
	@Override public String name() { return "External session snapshot example"; }
	@Override public String description() {
		return "Demonstrates a drop-in Java module that reports only values already captured by the client.";
	}

	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current explicitly authorized connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase")
		);
	}

	@Override
	public ModuleResult run(ModuleContext context, Map<String, String> options) {
		if (!context.connected()) {
			return new ModuleResult("No connected session", List.of(
				new Finding(Finding.Severity.INFO, "Session", "The client is not connected to a server.",
					"Connect to the authorized test target before collecting a session snapshot.")));
		}
		return new ModuleResult("Reviewed the current client snapshot", List.of(
			new Finding(Finding.Severity.INFO, "Server brand", context.serverBrand(),
				"Treat client-reported metadata as an observation, not server-side proof."),
			new Finding(Finding.Severity.INFO, "Advertised protocol",
				context.advertisedVersion() + " (protocol " + context.advertisedProtocol() + ")",
				"Validate version information against an authorized server-side source."),
			new Finding(Finding.Severity.INFO, "Session latency", context.pingMillis() + " ms",
				"Latency is a point-in-time client measurement and may vary.")));
	}
}
