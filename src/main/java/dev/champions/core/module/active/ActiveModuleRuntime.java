package dev.champions.core.module.active;

import dev.champions.core.module.CommandSafetyPolicy;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Narrow capability surface for active modules. Requests identify an advertised
 * root and one constrained metadata-oriented probe variant; the client runtime
 * revalidates every request against the live Brigadier tree before sending it.
 */
public interface ActiveModuleRuntime {
	CompletionStage<ChatTranscript> execute(ChatCommandRequest request);

	static ActiveModuleRuntime disabled() {
		return request -> CompletableFuture.completedFuture(new ChatTranscript(
			request, List.of(), Status.UNSUPPORTED, "Active client runtime is unavailable."));
	}

	enum ProbeVariant {
		BLANK(""), VERSION("version"), V("v");

		private final String suffix;
		ProbeVariant(String suffix) { this.suffix = suffix; }
		public String suffix() { return suffix; }
	}

	record ChatCommandRequest(String root, ProbeVariant variant, int timeoutTicks, int maxMessages) {
		public ChatCommandRequest {
			root = CommandSafetyPolicy.normalize(root);
			Objects.requireNonNull(variant, "variant");
			if (!CommandSafetyPolicy.mayProbe(root)) throw new IllegalArgumentException("Command root is not eligible for probing: " + root);
			if (timeoutTicks < 20 || timeoutTicks > 100) throw new IllegalArgumentException("timeoutTicks must be 20-100");
			if (maxMessages < 1 || maxMessages > 8) throw new IllegalArgumentException("maxMessages must be 1-8");
		}

		public String wireCommand() {
			return variant.suffix().isEmpty() ? root : root + " " + variant.suffix();
		}

		public String displayCommand() { return "/" + wireCommand(); }
	}

	record ChatTranscript(ChatCommandRequest request, List<String> messages, Status status, String detail) {
		public ChatTranscript {
			Objects.requireNonNull(request, "request");
			messages = messages == null ? List.of() : List.copyOf(messages);
			Objects.requireNonNull(status, "status");
			detail = detail == null ? "" : detail;
		}
	}

	enum Status { COMPLETED, TIMED_OUT, DISCONNECTED, BUSY, REJECTED, UNSUPPORTED, ERROR }
}
