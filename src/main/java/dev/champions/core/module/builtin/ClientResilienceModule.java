package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.PacketEvent;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Defensive client-side warning layer for unusual packet metadata patterns. */
public final class ClientResilienceModule implements ResearchModule {
	@Override public String id() { return "auxiliary/audit/client_resilience"; }
	@Override public String name() { return "Client Resilience packet audit"; }
	@Override public String description() {
		return "Looks for packet-rate bursts, custom-payload concentration, connection errors, and timeline pressure. It warns but does not drop or rewrite packets.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("WINDOW_SECONDS", "10", false, false, "Recent analysis window, 2-30 seconds"),
			new ModuleOption("BURST_WARN", "250", false, false, "Informational per-second packet threshold, 50-500")
		);
	}

	@Override public ModuleResult run(ModuleContext context, Map<String, String> options) {
		int seconds = boundedInt(options.get("WINDOW_SECONDS"), 10, 2, 30);
		int threshold = boundedInt(options.get("BURST_WARN"), 250, 50, 500);
		List<PacketEvent> recent = context.packetFlow().recentEvents().stream()
			.filter(event -> event.ageMillis() <= seconds * 1_000L).toList();
		Map<Long, Long> perSecond = recent.stream().collect(Collectors.groupingBy(event -> event.ageMillis() / 1_000L, Collectors.counting()));
		long peak = perSecond.values().stream().mapToLong(Long::longValue).max().orElse(0L);
		long inboundEvents = recent.stream().filter(event -> event.direction() == PacketEvent.Direction.INBOUND).count();
		long inboundCustom = recent.stream().filter(event -> event.direction() == PacketEvent.Direction.INBOUND)
			.filter(event -> event.packetType().toLowerCase(Locale.ROOT).contains("custompayload")).count();
		double customPercent = inboundEvents == 0 ? 0.0 : inboundCustom * 100.0 / inboundEvents;
		long uniqueInbound = recent.stream().filter(event -> event.direction() == PacketEvent.Direction.INBOUND)
			.map(PacketEvent::packetType).distinct().count();

		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(peak > threshold ? Finding.Severity.MEDIUM : Finding.Severity.PASS,
			"Packet-rate peak", peak + "/s observed; warning threshold=" + threshold + "/s",
			"A burst is not automatically malicious. Reproduce in a lab and compare server tick/load telemetry."));
		findings.add(new Finding(Finding.Severity.INFO, "Inbound custom-payload concentration",
			String.format(Locale.ROOT, "%d/%d inbound event(s), %.1f%% in %ds", inboundCustom, inboundEvents, customPercent, seconds),
			"Inspect registered channels server-side; packet payloads are intentionally not retained here."));
		findings.add(new Finding(Finding.Severity.INFO, "Inbound class diversity", uniqueInbound + " distinct class(es)",
			"Unexpected diversity can accompany configuration transitions, dimension changes, or modded channels."));
		findings.add(new Finding(context.packetFlow().connectionErrors() > 0 ? Finding.Severity.LOW : Finding.Severity.PASS,
			"Connection exceptions observed", Long.toString(context.packetFlow().connectionErrors()),
			"This lifetime counter includes ordinary disconnect/network errors and is not a decoder-crash verdict."));
		findings.add(new Finding(Finding.Severity.INFO, "Guard posture",
			"observe-only; payload capture=off; packet mutation=off; timeline cap=512",
			"Install current server-side fixes for authoritative crash/DoS protection; a client observer cannot patch the server."));
		return new ModuleResult("Client Resilience audit completed", findings);
	}

	private static int boundedInt(String value, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
