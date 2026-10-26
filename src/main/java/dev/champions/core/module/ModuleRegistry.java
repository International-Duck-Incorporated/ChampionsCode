package dev.champions.core.module;

import dev.champions.core.log.Log;

import dev.champions.core.module.builtin.ChatGameFairnessModule;
import dev.champions.core.module.builtin.ChatNormalizationAuditModule;
import dev.champions.core.module.builtin.ChatPluginEnumeratorModule;
import dev.champions.core.module.builtin.ClientResilienceModule;
import dev.champions.core.module.builtin.ConnectionPostureModule;
import dev.champions.core.module.builtin.ContainerIntegrityModule;
import dev.champions.core.module.builtin.DependencyPostureModule;
import dev.champions.core.module.builtin.ExploitResearchWorkspaceModule;
import dev.champions.core.module.builtin.MotionEvidenceModule;
import dev.champions.core.module.builtin.PlayerIdentityModule;
import dev.champions.core.module.builtin.PluginExploitSurfaceModule;
import dev.champions.core.module.builtin.PluginRiskReviewModule;
import dev.champions.core.module.builtin.PocArtifactModule;
import dev.champions.core.module.builtin.PluginSurfaceModule;
import dev.champions.core.module.builtin.ServerFingerprintModule;
import dev.champions.core.module.builtin.ServerPingModule;
import dev.champions.core.module.builtin.SessionTraceModule;
import dev.champions.core.module.builtin.TransactionEchoModule;
import dev.champions.core.module.builtin.WireLensModule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


public final class ModuleRegistry {
	private final Map<String, ResearchModule> modules = new LinkedHashMap<>();

	public ModuleRegistry() {
		register(new ServerFingerprintModule());
		// Reference payload: the smallest observable proof a chat console can offer.
		register(new dev.champions.core.module.builtin.ChatReachProofModule());
		register(new ConnectionPostureModule());
		register(new PluginSurfaceModule());
		register(new PluginRiskReviewModule());
		// Ported from the old exploit addon: the knowledge, without the delivery.
		register(new PluginExploitSurfaceModule());
		register(new PocArtifactModule());
		register(new DependencyPostureModule());
		register(new ChatNormalizationAuditModule());
		register(new ChatGameFairnessModule());
		register(new ExploitResearchWorkspaceModule());
		register(new ChatPluginEnumeratorModule());
		register(new WireLensModule());
		register(new SessionTraceModule());
		register(new ContainerIntegrityModule());
		register(new TransactionEchoModule());
		register(new ClientResilienceModule());
		register(new MotionEvidenceModule());
		register(new ServerPingModule());
		register(new PlayerIdentityModule());
	}

	public synchronized void register(ResearchModule module) {
		registerAll(java.util.List.of(module));
	}

	/** Atomically registers a batch, rejecting duplicates before changing the registry. */
	public synchronized void registerAll(Collection<? extends ResearchModule> additions) {
		Map<String, ResearchModule> next = new LinkedHashMap<>(modules);
		List<Map.Entry<String, ResearchModule>> accepted = new ArrayList<>();
		for (ResearchModule module : additions) {
			Objects.requireNonNull(module, "module");
			String id = normalizeId(module.id());
			if (next.putIfAbsent(id, module) != null) {
				throw new IllegalArgumentException("Duplicate module id: " + id);
			}
			accepted.add(Map.entry(id, module));
		}
		modules.clear();
		modules.putAll(next);
		for (Map.Entry<String, ResearchModule> entry : accepted) {
			Log.info("module registered: {} ({})", entry.getKey(), entry.getValue().getClass().getName());
		}
	}

	/**
	 * Atomically replaces a previously loaded group, used by the runtime Java
	 * module loader. Builtins and unrelated registrations are left untouched.
	 */
	public synchronized void replace(Collection<String> removedIds, Collection<? extends ResearchModule> replacements) {
		Set<String> normalizedRemovals = new LinkedHashSet<>();
		for (String id : removedIds) normalizedRemovals.add(normalizeId(id));
		Map<String, ResearchModule> next = new LinkedHashMap<>(modules);
		normalizedRemovals.forEach(next::remove);
		List<Map.Entry<String, ResearchModule>> accepted = new ArrayList<>();
		for (ResearchModule module : replacements) {
			Objects.requireNonNull(module, "module");
			String id = normalizeId(module.id());
			if (next.putIfAbsent(id, module) != null) {
				throw new IllegalArgumentException("Duplicate module id: " + id);
			}
			accepted.add(Map.entry(id, module));
		}
		modules.clear();
		modules.putAll(next);
		for (String id : normalizedRemovals) Log.info("module unregistered: {}", id);
		for (Map.Entry<String, ResearchModule> entry : accepted) {
			Log.info("module registered: {} ({})", entry.getKey(), entry.getValue().getClass().getName());
		}
	}

	public synchronized Optional<ResearchModule> find(String id) {
		return Optional.ofNullable(modules.get(normalizeId(id)));
	}

	public synchronized Collection<ResearchModule> all() {
		return java.util.List.copyOf(modules.values());
	}

	private static String normalizeId(String id) {
		if (id == null || id.isBlank()) throw new IllegalArgumentException("Module id is missing");
		return id.toLowerCase(Locale.ROOT);
	}
}
