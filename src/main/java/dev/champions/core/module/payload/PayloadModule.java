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
	 * What this payload will do, declared before anything runs.
	 *
	 * <p>It must be a function of the module's options and nothing else. The console
	 * calls it before the authorization gate and again in the warning the user
	 * acknowledges, so an effect that depended on run-time state would be a
	 * different effect by the time it executed — which is exactly the dishonesty
	 * the declaration exists to prevent. A module that selects among several
	 * artifacts overrides {@link #effect(Map)} rather than storing the choice.</p>
	 */
	PayloadEffect effect();

	/**
	 * The declared effect for a specific set of options. Default: the constant
	 * form, for modules whose effect never varies.
	 */
	default PayloadEffect effect(java.util.Map<String, String> options) {
		return effect();
	}

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
