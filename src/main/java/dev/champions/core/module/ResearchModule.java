package dev.champions.core.module;

import java.util.List;
import java.util.Map;

/**
 * A bounded, defensive research module. Implementations may inspect only the
 * supplied snapshot and explicit option values: no sockets, packets, file access,
 * reflection, or process execution.
 */
public interface ResearchModule {
	String id();
	String name();
	String description();
	List<ModuleOption> options();
	default boolean requiresAuthorization() { return true; }
	ModuleResult run(ModuleContext context, Map<String, String> options);
}
