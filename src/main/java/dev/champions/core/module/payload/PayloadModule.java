package dev.champions.core.module.payload;

import dev.champions.core.module.ExploitResearchModule;
import dev.champions.core.module.active.ActiveResearchModule;

/**
 * A module whose point is to prove an impact, not just describe one.
 *
 * <p>The three tiers of an audit read like this:</p>
 * <ol>
 *   <li><b>check</b> — a {@code ResearchModule}: "this looks vulnerable", read
 *       from the snapshot alone;</li>
 *   <li><b>verify</b> — an {@code ActiveResearchModule} that sends one bounded
 *       request and records what came back;</li>
 *   <li><b>payload</b> — this: the smallest observable action that demonstrates
 *       the issue is real, declared in advance, bounded by
 *       {@link PayloadSafety}, and undone where it created anything.</li>
 * </ol>
 *
 * <p>A payload runs inside the same gate as everything else: exact-host
 * authorization, the one-run {@code CONFIRM} phrase, the per-run legal-risk
 * acknowledgement that every {@link ExploitResearchModule} carries, and an audit
 * line that names the declared effect. It does not get a quieter path for being
 * dramatic.</p>
 */
public interface PayloadModule extends ActiveResearchModule, ExploitResearchModule {

	/**
	 * What this payload will do, declared before anything runs. Must be a constant
	 * — a module that decides its effect from user input is refused, because the
	 * point of the declaration is that it can be checked before execution.
	 */
	PayloadEffect effect();

	/**
	 * How the run leaves things as it found them, for effects that create state.
	 * Shown to the user before the run and written to the audit log.
	 */
	default String revertPlan() {
		return effect().createsState() ? "removes what it created" : "nothing to revert";
	}

	@Override
	default String legalRiskSummary() {
		return "This module performs an observable action against the target to prove an impact. "
				+ "It is bounded to " + effect().describe() + ", but it is still a live action on a live server.";
	}
}
