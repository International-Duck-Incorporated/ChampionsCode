package dev.champions.core.module.builtin;

import dev.champions.core.module.EvidenceNode;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.active.ActiveModuleRuntime;
import dev.champions.core.module.active.ActiveModuleRuntime.ChatCommandRequest;
import dev.champions.core.module.active.ActiveModuleRuntime.ChatTranscript;
import dev.champions.core.module.payload.PayloadEffect;
import dev.champions.core.module.payload.PayloadModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Reference payload: sends one {@code /say} line and reports whether the server
 * echoed it back.
 *
 * <p>This is the smallest honest proof a chat-based console can offer. Chat
 * filtering, a mute, a permission gate, or a client that silently drops the
 * command all look identical from inside the terminal — none of them show up in
 * a snapshot. Sending one line and seeing whether it appears settles it: the
 * command reached the server and the server accepted it from this account.</p>
 *
 * <p>The effect is fixed at two words and states nothing about state: no player,
 * container or world is touched, and there is nothing to revert. If the echo does
 * not arrive, the module says so rather than assuming success.</p>
 */
public final class ChatReachProofModule implements PayloadModule {
	private static final String MARKER_PREFIX = "champions-proof";
	private static final int TIMEOUT_TICKS = 60;
	private static final int MAX_MESSAGES = 4;

	@Override public String id() { return "auxiliary/payloads/chat_reach_proof"; }
	@Override public String name() { return "Chat reachability proof"; }
	@Override public String description() {
		return "Sends one visible /say line and confirms whether the server accepted and echoed it.";
	}

	@Override
	public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Must match the current connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase: I_HAVE_AUTHORIZATION"),
			new ModuleOption("TAG", "a", false, false, "One short token appended to the marker so runs are distinguishable")
		);
	}

	@Override
	public PayloadEffect effect() {
		// Declared up front and constant: the console checks this before the gate.
		return PayloadEffect.chatCommand("/say " + MARKER_PREFIX + " " + tag("a"));
	}

	@Override
	public String revertPlan() {
		return "nothing to revert: one chat line, no state created";
	}

	@Override
	public boolean performsActiveAction(Map<String, String> options) {
		return true;
	}

	@Override
	public CompletionStage<ModuleResult> runActive(ModuleContext context,
			Map<String, String> options, ActiveModuleRuntime runtime) {
		if (!context.connected()) {
			return CompletableFuture.completedFuture(new ModuleResult(
				"No connection: a chat proof needs a live session",
				List.of(new Finding(Finding.Severity.INFO, "Not connected",
					"There is no server connection to send a proof line to.",
					"Connect to the authorized target first.")),
				List.of(EvidenceNode.observed("Connection", "none", "Nothing was sent."))));
		}

		String tag = tag(options.getOrDefault("TAG", "a"));
		String marker = MARKER_PREFIX + " " + tag;
		ChatCommandRequest request = ChatCommandRequest.proof("say", List.of(marker.split(" ")),
			TIMEOUT_TICKS, MAX_MESSAGES);

		return runtime.execute(request).handle((transcript, error) -> {
			if (error != null) {
				return failure("The proof could not be sent: " + safe(error), transcript);
			}
			return interpret(transcript, marker);
		});
	}

	private static ModuleResult interpret(ChatTranscript transcript, String marker) {
		if (transcript == null) return failure("No transcript returned", null);
		List<Finding> findings = new ArrayList<>();
		String echoed = transcript.messages().stream()
			.filter(message -> message.toLowerCase(Locale.ROOT).contains(marker))
			.findFirst().orElse(null);

		boolean completed = transcript.status() == ActiveModuleRuntime.Status.COMPLETED;
		if (echoed != null) {
			findings.add(new Finding(Finding.Severity.HIGH, "Chat reachability confirmed",
				"The server accepted the command from this account and echoed the marker.",
				"If this account should not be able to speak, or a mute/filter is expected here, it is not in force."));
		} else if (completed) {
			findings.add(new Finding(Finding.Severity.MEDIUM, "Command accepted, echo not seen",
				"The capture window closed with " + transcript.messages().size()
					+ " message(s) and none carried the marker.",
				"A filter, a mute, or a channel restriction may be swallowing it. Confirm server-side before concluding either way."));
		} else {
			findings.add(new Finding(Finding.Severity.LOW, "Proof did not complete: " + transcript.status(),
				transcript.detail(),
				switch (transcript.status()) {
					case REJECTED -> "The server or the safety policy refused the command; nothing was sent.";
					case TIMED_OUT -> "No response inside the bounded window; the server may not answer /say to this client.";
					case DISCONNECTED -> "The connection ended during the proof; treat the result as unknown.";
					case BUSY -> "Another capture held the channel; retry when the console is idle.";
					default -> "No usable result; nothing should be concluded from this run.";
				}));
		}

		findings.add(new Finding(Finding.Severity.INFO, "What this does not prove",
			"Whether the line was visible to other players",
			"An echo to this client says nothing about what other clients render or how the server logs it."));

		List<EvidenceNode> evidence = List.of(EvidenceNode.group("Chat proof", List.of(
			EvidenceNode.observed("Command", "/" + transcript.request().wireCommand(),
				"One state-neutral command sent by this client."),
			EvidenceNode.observed("Status", transcript.status().name(),
				"Outcome of the bounded capture window."),
			EvidenceNode.observed("Messages captured", Integer.toString(transcript.messages().size()),
				"Bounded to " + MAX_MESSAGES + " messages inside " + TIMEOUT_TICKS + " ticks."),
			EvidenceNode.observed("Server messages",
				transcript.messages().isEmpty() ? "(none)" : String.join(" | ", transcript.messages()),
				"Only the lines that arrived during the capture window."))));

		String summary = echoed != null
			? "Chat reachability confirmed with one /say line"
			: "Chat proof inconclusive: " + transcript.status();
		return new ModuleResult(summary, findings, evidence);
	}

	private static ModuleResult failure(String detail, ChatTranscript transcript) {
		return new ModuleResult(detail,
			List.of(new Finding(Finding.Severity.LOW, "Proof did not run", detail,
				"Nothing was demonstrated. Fix the cause before drawing a conclusion.")),
			List.of(EvidenceNode.observed("Transcript",
				transcript == null ? "none" : transcript.status().name(), detail)));
	}

	/** Keeps the marker inside the safe literal rules: one short lowercase token. */
	private static String tag(String value) {
		String cleaned = value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		if (cleaned.isEmpty()) return "a";
		return cleaned.length() > 8 ? cleaned.substring(0, 8) : cleaned;
	}

	private static String safe(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
	}
}
