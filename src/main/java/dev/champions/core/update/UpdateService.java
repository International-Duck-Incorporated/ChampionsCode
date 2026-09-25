package dev.champions.core.update;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Read-only update checks plus explicit, checksum-verified downloads.
 * Implementations must only talk to this project's own release infrastructure,
 * must never touch the connected Minecraft session, and must verify a
 * download against the release checksum manifest before writing anything.
 */
public interface UpdateService {
	CompletionStage<Optional<UpdateCheck>> checkLatest();

	CompletionStage<UpdateResult> download(UpdateCheck check);

	static UpdateService disabled() {
		return new UpdateService() {
			@Override public CompletionStage<Optional<UpdateCheck>> checkLatest() {
				return CompletableFuture.completedFuture(Optional.empty());
			}

			@Override public CompletionStage<UpdateResult> download(UpdateCheck check) {
				return CompletableFuture.completedFuture(UpdateResult.failure("The update service is unavailable in this environment."));
			}
		};
	}
}
