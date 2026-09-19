package champions.runtime;

import dev.champions.api.java.ChampionJavaContext;
import dev.champions.api.java.ChampionRuntimeAddon;

public final class Example implements ChampionRuntimeAddon {
    @Override
    public void start(ChampionJavaContext context) {
        context.info("Connected to " + context.snapshot().serverAddress());
        context.onStop(() -> context.info("Example cleanup completed"));
    }

    @Override
    public void stop() {
        // Release listeners, threads, and other resources here.
    }
}
