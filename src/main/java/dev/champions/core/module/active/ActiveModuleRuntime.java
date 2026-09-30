package dev.champions.core.module.active;

import dev.champions.core.module.CommandSafetyPolicy;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/** Narrow client capability for live-tree-validated, metadata-oriented chat probes. */
public interface ActiveModuleRuntime {
	Pattern SAFE_LITERAL = Pattern.compile("[a-z0-9_.-]+");
	CompletionStage<ChatTranscript> execute(ChatCommandRequest request);

	static ActiveModuleRuntime disabled() {
		return request -> CompletableFuture.completedFuture(new ChatTranscript(
			request, List.of(), Status.UNSUPPORTED, "Active client runtime is unavailable."));
	}

	enum ProbeVariant {
		BLANK(""), VERSION("version"), V("v"), PATH("");
		private final String suffix;
		ProbeVariant(String suffix) { this.suffix = suffix; }
		public String suffix() { return suffix; }
	}

	record ChatCommandRequest(String root, ProbeVariant variant, List<String> literalPath, int timeoutTicks, int maxMessages) {
		public ChatCommandRequest {
			root = CommandSafetyPolicy.normalize(root);
			Objects.requireNonNull(variant, "variant");
			literalPath = literalPath == null ? List.of() : literalPath.stream()
				.map(value -> value == null ? "" : value.strip().toLowerCase(Locale.ROOT)).toList();
			if (!CommandSafetyPolicy.mayProbe(root)) throw new IllegalArgumentException("Command root is not eligible for probing: " + root);
			if (literalPath.stream().anyMatch(value -> !SAFE_LITERAL.matcher(value).matches()))
				throw new IllegalArgumentException("Probe path must contain safe literal nodes only");
			if (variant != ProbeVariant.PATH && !literalPath.isEmpty()) throw new IllegalArgumentException("Only PATH probes accept a literal path");
			if (variant == ProbeVariant.PATH && literalPath.isEmpty()) throw new IllegalArgumentException("PATH probe requires at least one literal");
			if (timeoutTicks < 20 || timeoutTicks > 100) throw new IllegalArgumentException("timeoutTicks must be 20-100");
			if (maxMessages < 1 || maxMessages > 8) throw new IllegalArgumentException("maxMessages must be 1-8");
		}

		public ChatCommandRequest(String root, ProbeVariant variant, int timeoutTicks, int maxMessages) {
			this(root, variant, List.of(), timeoutTicks, maxMessages);
		}

		public static ChatCommandRequest path(String root, List<String> literals, int timeoutTicks, int maxMessages) {
			return new ChatCommandRequest(root, ProbeVariant.PATH, literals, timeoutTicks, maxMessages);
		}

		public String wireCommand() {
			String suffix = variant == ProbeVariant.PATH ? String.join(" ", literalPath) : variant.suffix();
			return suffix.isEmpty() ? root : root + " " + suffix;
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
