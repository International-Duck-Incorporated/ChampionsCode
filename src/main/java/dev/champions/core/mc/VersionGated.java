package dev.champions.core.mc;

import java.util.Set;

/**
 * Declares that a module needs capabilities this build may not have.
 *
 * <p>{@code ResearchModule} implementations opt in; the console refuses to run a
 * module whose features are missing and says exactly which ones, instead of
 * returning empty or misleading evidence on a version where the data cannot be
 * captured.</p>
 */
public interface VersionGated {
	Set<ClientCapabilities.Feature> requires();

	default boolean availableOn(ClientCapabilities capabilities) {
		return capabilities == null || capabilities.supportsAll(requires());
	}
}
