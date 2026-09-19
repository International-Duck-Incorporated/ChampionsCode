package dev.champions.core.module.builtin;

import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;
import dev.champions.core.module.ResearchModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class PluginSurfaceModule implements ResearchModule {
	private static final Set<String> COMMON_ADMIN_ROOTS = Set.of(
		"plugins", "pl", "version", "ver", "bukkit", "paper", "spigot", "about", "icanhasbukkit"
	);

	@Override public String id() { return "auxiliary/audit/plugin_surface"; }
	@Override public String name() { return "Advertised plugin surface"; }
	@Override public String description() {
		return "Inventories command roots already advertised to the connected player. It does not execute commands or identify plugins conclusively.";
	}
	@Override public List<ModuleOption> options() {
		return List.of(
			new ModuleOption("RHOST", "CURRENT", true, false, "Must match the current connection"),
			new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase: I_HAVE_AUTHORIZATION"),
			new ModuleOption("MAX_COMMANDS", "40", false, false, "Maximum advertised roots to display (1-100)")
		);
	}

	@Override
	public ModuleResult run(ModuleContext c, Map<String, String> options) {
		int max = parseBounded(options.get("MAX_COMMANDS"), 40, 1, 100);
		List<String> commands = c.advertisedCommands().stream()
			.map(s -> s.toLowerCase(Locale.ROOT))
			.distinct().sorted().limit(max).toList();
		List<Finding> out = new ArrayList<>();
		out.add(new Finding(Finding.Severity.INFO, "Advertised command roots",
			commands.isEmpty() ? "No roots available in the client command tree." : String.join(", ", commands),
			"Apply least-privilege command permissions and verify tab-completion exposure for untrusted roles."));
		List<String> exposed = commands.stream().filter(COMMON_ADMIN_ROOTS::contains).toList();
		if (exposed.isEmpty()) {
			out.add(new Finding(Finding.Severity.PASS, "Common implementation commands", "No common implementation/admin roots appeared in the advertised subset.", "Verify with each intended permission group; absence is not proof of inaccessibility."));
		} else {
			out.add(new Finding(Finding.Severity.LOW, "Implementation-oriented roots advertised",
				exposed.stream().map(s -> "/" + s).collect(Collectors.joining(", ")),
				"Review command permissions and tab-completion visibility. Do not depend on command hiding as the only control."));
		}
		return new ModuleResult("Advertised surface inventoried", out);
	}

	private static int parseBounded(String value, int fallback, int min, int max) {
		try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
		catch (RuntimeException ignored) { return fallback; }
	}
}
