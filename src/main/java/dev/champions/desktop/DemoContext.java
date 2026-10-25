package dev.champions.desktop;

import dev.champions.core.module.ModuleContext;

import java.util.List;

/**
 * Simulated client snapshot used by the demo harness and the self-test.
 *
 * <p>It exists so the full gated pipeline — allowlist, one-run {@code CONFIRM},
 * module run, findings, audit — can be exercised without a game and without
 * touching any real server. The target is always {@code *.invalid}, which can
 * never resolve, and a banner says so in the terminal.</p>
 */
public final class DemoContext {
	public static final String DEMO_TARGET = "lab.demo.invalid";

	private DemoContext() {}

	public static ModuleContext capture() {
		return new ModuleContext(
			true,          // connected
			false,         // integratedServer
			false,         // memoryTransport
			true,          // onlineMode
			DEMO_TARGET,
			"Paper 1.21.11",
			"1.21.11",
			774,
			38L,
			24,
			"optional",
			"Researcher",
			"128 71 -256",
			"minecraft:overworld",
			144,
			21.5f,
			6.0f,
			List.of("about", "plugins", "version", "warp", "spawn", "kit", "baltop", "essentials",
				"worldedit", "wand", "msg", "list", "help")
		);
	}

	/** Standalone snapshot: no connection, so authorization cannot be granted. */
	public static ModuleContext offline() {
		return new ModuleContext(
			false, false, false, false,
			"unknown", "unknown", "unknown", -1,
			-1L, -1, "unknown",
			"unknown", "unknown", "unknown",
			0, 0f, 0f,
			List.of()
		);
	}
}
