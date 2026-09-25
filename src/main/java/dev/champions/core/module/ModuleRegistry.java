package dev.champions.core.module;

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
import dev.champions.core.module.builtin.PluginRiskReviewModule;
import dev.champions.core.module.builtin.PluginSurfaceModule;
import dev.champions.core.module.builtin.ServerFingerprintModule;
import dev.champions.core.module.builtin.ServerPingModule;
import dev.champions.core.module.builtin.SessionTraceModule;
import dev.champions.core.module.builtin.TransactionEchoModule;
import dev.champions.core.module.builtin.WireLensModule;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ModuleRegistry {
	private final Map<String, ResearchModule> modules = new LinkedHashMap<>();

	public ModuleRegistry() {
		register(new ServerFingerprintModule());
		register(new ConnectionPostureModule());
		register(new PluginSurfaceModule());
		register(new PluginRiskReviewModule());
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

	public void register(ResearchModule module) {
		String id = module.id().toLowerCase(Locale.ROOT);
		if (modules.putIfAbsent(id, module) != null) {
			throw new IllegalArgumentException("Duplicate module id: " + id);
		}
	}

	public Optional<ResearchModule> find(String id) {
		return Optional.ofNullable(modules.get(id.toLowerCase(Locale.ROOT)));
	}

	public Collection<ResearchModule> all() {
		return java.util.List.copyOf(modules.values());
	}
}
