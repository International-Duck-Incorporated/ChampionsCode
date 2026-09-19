package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.PacketEvent;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Passive, payload-free correlation of menu interaction and update packet classes. */
public final class TransactionEchoModule implements ResearchModule {
	@Override public String id() { return "auxiliary/observe/transaction_echo"; }
	@Override public String name() { return "Transaction Echo menu-flow observer"; }
	@Override public String description() {
		return "Correlates outbound menu-interaction class events with later inbound menu-update class events. It never delays, cancels, fabricates, or replays packets.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Current allowlisted connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
			new ModuleOption("WINDOW_SECONDS", "10", false, false, "Recent metadata window, 2-30 seconds")
		);
	}

	@Override public ModuleResult run(ModuleContext context, Map<String, String> options) {
		int seconds = boundedInt(options.get("WINDOW_SECONDS"), 10, 2, 30);
		List<PacketEvent> events = context.packetFlow().recentEvents().stream()
			.filter(event -> event.ageMillis() <= seconds * 1_000L)
			.sorted(Comparator.comparingLong(PacketEvent::ageMillis).reversed())
			.toList();

		ArrayDeque<PacketEvent> pending = new ArrayDeque<>();
		List<Long> responseMillis = new ArrayList<>();
		Map<String, Long> actionClasses = new LinkedHashMap<>();
		Map<String, Long> updateClasses = new LinkedHashMap<>();
		int maximumPending = 0;

		for (PacketEvent event : events) {
			if (event.direction() == PacketEvent.Direction.OUTBOUND && isMenuAction(event.packetType())) {
				pending.addLast(event);
				actionClasses.merge(event.packetType(), 1L, Long::sum);
				maximumPending = Math.max(maximumPending, pending.size());
			} else if (event.direction() == PacketEvent.Direction.INBOUND && isMenuUpdate(event.packetType())) {
				updateClasses.merge(event.packetType(), 1L, Long::sum);
				if (!pending.isEmpty()) {
					PacketEvent action = pending.removeFirst();
					responseMillis.add(Math.max(0L, action.ageMillis() - event.ageMillis()));
				}
			}
		}

		responseMillis.sort(Long::compareTo);
		long actions = actionClasses.values().stream().mapToLong(Long::longValue).sum();
		long updates = updateClasses.values().stream().mapToLong(Long::longValue).sum();
		String latency = responseMillis.isEmpty() ? "<no coarse matches>" : String.format(Locale.ROOT,
			"min=%dms median=%dms p95=%dms max=%dms",
			responseMillis.getFirst(), percentile(responseMillis, 0.50), percentile(responseMillis, 0.95), responseMillis.getLast());

		List<Finding> findings = new ArrayList<>();
		findings.add(new Finding(Finding.Severity.INFO, "Observed menu flow",
			actions + " outbound action-class event(s); " + updates + " inbound update-class event(s) in " + seconds + "s",
			"Class-level counts are useful for defensive workflow debugging but do not establish server state or exploitability."));
		findings.add(new Finding(Finding.Severity.INFO, "Coarse response timing",
			responseMillis.size() + "/" + actions + " action event(s) paired; " + latency,
			"Pairing is approximate because packet payloads, container IDs, revisions, and slot data are intentionally unavailable."));
		findings.add(new Finding(pending.isEmpty() ? Finding.Severity.PASS : Finding.Severity.INFO,
			"Unpaired action metadata", pending.size() + " currently unpaired; maximum pending depth=" + maximumPending,
			"Ordinary latency, unrelated update classes, disconnects, or timeline truncation can leave events unpaired; this is not a desync verdict."));
		findings.add(new Finding(Finding.Severity.INFO, "Action classes", formatCounts(actionClasses),
			"Transaction Echo observes existing traffic only and has no packet gate, queue, editor, sender, or flush operation."));
		findings.add(new Finding(Finding.Severity.INFO, "Update classes", formatCounts(updateClasses),
			"Use authoritative server transaction logs to validate ownership, revision, and conservation invariants."));
		return new ModuleResult("Transaction Echo menu-flow summary completed", findings);
	}

	private static boolean isMenuAction(String packetType) {
		String type = packetType.toLowerCase(Locale.ROOT);
		return type.contains("containerclickpacket")
			|| type.contains("containerbuttonclickpacket")
			|| type.contains("containerslotstatechangedpacket");
	}

	private static boolean isMenuUpdate(String packetType) {
		String type = packetType.toLowerCase(Locale.ROOT);
		return type.contains("containersetcontentpacket")
			|| type.contains("containersetslotpacket")
			|| type.contains("containersetdatapacket");
	}

	private static long percentile(List<Long> sorted, double percentile) {
		int index = (int) Math.ceil(percentile * sorted.size()) - 1;
		return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
	}

	private static String formatCounts(Map<String, Long> counts) {
		String value = counts.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.collect(Collectors.joining(", "));
		return value.isEmpty() ? "<none observed>" : value;
	}

	private static int boundedInt(String value, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
