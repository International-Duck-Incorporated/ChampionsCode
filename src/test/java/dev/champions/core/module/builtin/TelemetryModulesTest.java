package dev.champions.core.module.builtin;

import dev.champions.core.module.InventorySnapshot;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.PacketEvent;
import dev.champions.core.module.PacketFlowSnapshot;
import dev.champions.core.module.PlayerMotionObservation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryModulesTest {
	@Test void wireLensAndTraceUseMetadataOnlySnapshot() {
		ModuleContext context = context();
		var wire = new WireLensModule().run(context, Map.of("WINDOW_SECONDS", "10", "TOP_TYPES", "5"));
		var trace = new SessionTraceModule().run(context, Map.of("WINDOW_SECONDS", "10", "MAX_EVENTS", "20", "DIRECTION", "both"));
		assertTrue(wire.findings().stream().anyMatch(finding -> finding.detail().contains("ClientboundSetSlotPacket")));
		assertTrue(trace.findings().stream().anyMatch(finding -> finding.detail().contains("S→C")));
	}

	@Test void inventoryResilienceAndMotionProduceBoundedFindings() {
		ModuleContext context = context();
		assertTrue(new ContainerIntegrityModule().run(context, Map.of()).findings().stream()
			.anyMatch(finding -> finding.detail().contains("GenericContainerMenu")));
		assertTrue(new ClientResilienceModule().run(context, Map.of()).findings().stream()
			.anyMatch(finding -> finding.title().contains("Packet-rate")));
		assertTrue(new MotionEvidenceModule().run(context, Map.of()).findings().stream()
			.anyMatch(finding -> finding.title().equals("RemoteTester")));
	}

	@Test void containerIntegrityComparesRepeatedBoundedSnapshots() {
		ContainerIntegrityModule module = new ContainerIntegrityModule();
		module.run(context(), Map.of());
		var second = module.run(context(), Map.of());
		assertTrue(second.evidence().stream().flatMap(node -> node.children().stream())
			.anyMatch(node -> node.label().equals("State revision") && node.value().contains("delta 0")));
		assertTrue(second.evidence().stream().flatMap(node -> node.children().stream())
			.anyMatch(node -> node.label().equals("Classes") && node.value().contains("Container")));
	}

	@Test void traceKeepsNewestRowsWhenCapped() {
		List<PacketEvent> events = IntStream.range(0, 8)
			.mapToObj(index -> new PacketEvent(PacketEvent.Direction.INBOUND, "Type" + index, 800L - index * 100L))
			.toList();
		var result = new SessionTraceModule().run(context(new PacketFlowSnapshot(
			8, 0, 0, 0, Map.of(), Map.of(), events)), Map.of("MAX_EVENTS", "5", "DIRECTION", "inbound"));
		String rendered = result.findings().stream().filter(finding -> finding.title().equals("Ordered metadata"))
			.findFirst().orElseThrow().detail();
		assertFalse(rendered.contains("Type0"));
		assertTrue(rendered.contains("Type7"));
	}

	@Test void resilienceDefaultThresholdIsReachableWithinTimelineBound() {
		List<PacketEvent> events = IntStream.range(0, 251)
			.mapToObj(index -> new PacketEvent(PacketEvent.Direction.INBOUND, "ClientboundKeepAlivePacket", 100L))
			.toList();
		var result = new ClientResilienceModule().run(context(new PacketFlowSnapshot(
			251, 0, 0, 0, Map.of(), Map.of(), events)), Map.of());
		assertTrue(result.findings().stream().anyMatch(finding -> finding.title().equals("Packet-rate peak")
			&& finding.severity() == dev.champions.core.module.Finding.Severity.MEDIUM));
	}

	@Test void transactionEchoPassivelyPairsActionAndUpdateClasses() {
		List<PacketEvent> events = List.of(
			new PacketEvent(PacketEvent.Direction.OUTBOUND, "ServerboundContainerClickPacket", 900),
			new PacketEvent(PacketEvent.Direction.INBOUND, "ClientboundContainerSetSlotPacket", 850),
			new PacketEvent(PacketEvent.Direction.OUTBOUND, "ServerboundContainerButtonClickPacket", 500),
			new PacketEvent(PacketEvent.Direction.INBOUND, "ClientboundContainerSetContentPacket", 300),
			new PacketEvent(PacketEvent.Direction.OUTBOUND, "ServerboundContainerClickPacket", 100));
		var result = new TransactionEchoModule().run(context(new PacketFlowSnapshot(
			2, 3, 0, 0, Map.of(), Map.of(), events)), Map.of());
		assertTrue(result.findings().stream().anyMatch(finding -> finding.title().equals("Coarse response timing")
			&& finding.detail().contains("2/3") && finding.detail().contains("min=50ms")));
		assertTrue(result.findings().stream().anyMatch(finding -> finding.title().equals("Unpaired action metadata")
			&& finding.detail().startsWith("1 currently unpaired")));
	}

	private static ModuleContext context() {
		List<PacketEvent> events = List.of(
			new PacketEvent(PacketEvent.Direction.INBOUND, "ClientboundSetSlotPacket", 100),
			new PacketEvent(PacketEvent.Direction.INBOUND, "ClientboundCustomPayloadPacket", 200),
			new PacketEvent(PacketEvent.Direction.OUTBOUND, "ServerboundContainerClickPacket", 300));
		PacketFlowSnapshot flow = new PacketFlowSnapshot(20, 10, 0, 0,
			Map.of("ClientboundSetSlotPacket", 10L), Map.of("ServerboundContainerClickPacket", 5L), events);
		return context(flow);
	}

	private static ModuleContext context(PacketFlowSnapshot flow) {
		InventorySnapshot inventory = new InventorySnapshot(true, "GenericContainerMenu", 2, 4, 54, 6, 8, 0, "Diamond", 1);
		List<PlayerMotionObservation> motion = List.of(new PlayerMotionObservation(
			"RemoteTester", 20, 0.2, 0.0, 9.0, 1, 0, List.of("teleport/lag may explain it")));
		return new ModuleContext(true, false, false, true, "lab.test", "Paper", "26.3", 1,
			10, 2, "enabled", "Tester", "0, 0, 0", "minecraft:overworld", 60, 2, 1,
			List.of(), List.of(), flow, inventory, motion);
	}
}
