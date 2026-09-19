package dev.champions.core.module.active;

import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/** A module requiring one of the deliberately narrow runtime capabilities. */
public interface ActiveResearchModule extends ResearchModule {
	CompletionStage<ModuleResult> runActive(ModuleContext context, Map<String, String> options, ActiveModuleRuntime runtime);

	/** Whether the selected options will send anything to the connected server. */
	default boolean performsActiveAction(Map<String, String> options) { return true; }

	@Override
	default ModuleResult run(ModuleContext context, Map<String, String> options) {
		throw new IllegalStateException("Active modules must be launched through runActive");
	}
}
