package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ServerFingerprintModule implements ResearchModule {
	@Override public String id() { return "auxiliary/scanner/server_fingerprint"; }
	@Override public String name() { return "Passive server fingerprint"; }
	@Override public String description() {
		return "Reports metadata the current server already disclosed to this client. Sends no probes or custom packets.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Must match the currently connected, allowlisted server"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase: I_HAVE_AUTHORIZATION")
		);
	}

	@Override
	public ModuleResult run(ModuleContext c, Map<String, String> options) {
		List<Finding> out = new ArrayList<>();
		out.add(new Finding(Finding.Severity.INFO, "Address", c.serverAddress(), "Treat addresses in exported reports as sensitive."));
		out.add(new Finding(Finding.Severity.INFO, "Server brand", c.serverBrand(), "Do not use the client-visible brand as an authentication or trust signal."));
		out.add(new Finding(Finding.Severity.INFO, "Advertised version", c.advertisedVersion() + " (protocol " + c.advertisedProtocol() + ")", "Keep proxy, server, and plugins on supported security releases."));
		out.add(new Finding(Finding.Severity.INFO, "Observed session", c.onlinePlayers() + " online; " + c.pingMillis() + " ms ping", "Correlate with server-side logs for authoritative values."));
		if (!"unknown".equalsIgnoreCase(c.serverBrand()) && !"vanilla".equalsIgnoreCase(c.serverBrand())) {
			out.add(new Finding(Finding.Severity.LOW, "Implementation disclosure", "The server identifies itself as " + c.serverBrand() + ".", "Consider a generic brand only if disclosure is outside your threat model; never rely on hiding it as a control."));
		}
		return new ModuleResult("Passive fingerprint captured", out);
	}
}
