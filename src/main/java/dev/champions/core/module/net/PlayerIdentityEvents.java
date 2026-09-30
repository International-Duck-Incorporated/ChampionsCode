package dev.champions.core.module.net;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Client presentation hook kept separate from the platform-neutral profile lookup. */
public final class PlayerIdentityEvents {
	private static final AtomicReference<Consumer<PlayerProfileDetails>> LISTENER = new AtomicReference<>();

	private PlayerIdentityEvents() {}

	public static void setListener(Consumer<PlayerProfileDetails> listener) {
		LISTENER.set(Objects.requireNonNull(listener, "listener"));
	}

	public static void clearListener() {
		LISTENER.set(null);
	}

	public static void publish(PlayerProfileDetails details) {
		Consumer<PlayerProfileDetails> listener = LISTENER.get();
		if (listener != null) listener.accept(details);
	}
}
