package dev.champions.api.java;

/**
 * Entrypoint for an explicitly trusted runtime Java addon.
 *
 * Implementations are unrestricted Java code loaded into the Minecraft JVM.
 * They must release their own resources from stop().
 */
public interface ChampionRuntimeAddon {
	void start(ChampionJavaContext context) throws Exception;

	default void stop() throws Exception {}
}
