package dev.champions.core.update;

import java.util.Objects;

/** Result of an explicit update download: verified success or a refusal reason. */
public record UpdateResult(boolean success, String message, String savedPath) {
	public UpdateResult {
		Objects.requireNonNull(message, "message");
		savedPath = Objects.requireNonNullElse(savedPath, "");
	}

	public static UpdateResult success(String message, String savedPath) {
		return new UpdateResult(true, message, savedPath);
	}

	public static UpdateResult failure(String message) {
		return new UpdateResult(false, message, null);
	}
}
