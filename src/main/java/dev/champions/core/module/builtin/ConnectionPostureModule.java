package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConnectionPostureModule implements ResearchModule {
	@Override public String id() { return "auxiliary/audit/connection_posture"; }
	@Override public String name() { return "Connection posture audit"; }
	@Override public String description() {
		return "Reviews passive connection/session indicators and produces defensive hardening notes.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Must match the current connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase: I_HAVE_AUTHORIZATION")
		);
	}

	@Override
	public ModuleResult run(ModuleContext c, Map<String, String> options) {
		List<Finding> out = new ArrayList<>();
		out.add(new Finding(c.onlineMode() ? Finding.Severity.PASS : Finding.Severity.MEDIUM,
			"Account authentication mode",
			c.onlineMode() ? "The client session reports online-mode authentication." : "The session does not report online-mode authentication.",
			c.onlineMode() ? "Retain authenticated mode and secure any proxy forwarding secret." : "Use online-mode where possible; if behind a proxy, lock down backend reachability and forwarding secrets."));
		out.add(new Finding(Finding.Severity.INFO, "Transport type",
			c.memoryTransport() ? "Integrated-server memory transport" : "Remote network transport",
			"Review transport encryption and proxy termination in server-side configuration; a client snapshot is not authoritative."));
		out.add(new Finding(Finding.Severity.INFO, "Resource-pack policy", c.resourcePackPolicy(),
			"Only distribute packs from controlled origins and verify content before release."));
		out.add(new Finding(Finding.Severity.INFO, "Observed packet rates",
			String.format(java.util.Locale.ROOT, "received %.1f/s; sent %.1f/s", c.averageReceivedPackets(), c.averageSentPackets()),
			"Use server-side rate-limit telemetry for abuse detection; these client averages are informational."));
		return new ModuleResult("Connection posture reviewed", out);
	}
}
