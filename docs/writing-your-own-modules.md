# Writing your own modules

A hands-on cookbook for adding your own research modules to ChampionsCode. This
guide is deliberately example-driven; the reference documentation for each
mechanism lives elsewhere and is linked at the end of every section:

| You want to… | Read the section | Reference doc |
|---|---|---|
| Add a Java file to the repo that shows up in every GUI | [Drop-in module](#1-drop-in-module-in-the-exploit--payload-folders) | `exploit-and-payload-files.md` |
| Add a builtin module to the core registry | [Builtin module](#2-builtin-module-in-the-core-source-set) | `module-authoring.md` |
| Add code without rebuilding the mod jar | [Runtime Java addon](#3-runtime-java-addon-no-rebuild) | `runtime-java.md` |

All three routes end in the same place: your module appears in the F8 terminal
(`show modules`, `use <id>`, `info`, `show options`, `set`, `run`), the sorted
ClickGUI (F8 → `clickgui`), and the category GUI — with the authorization gate,
audit logging, and the exploit warning popup applied automatically based on the
interfaces your module implements.

---

## 1. Drop-in module (in the `exploit` + `payload` folders)

The fastest route. One self-contained class in
`src/client/java/com/internationalduck/championscode/module/exploit/` (or
`.../module/payload/`) plus one registration line. No core changes.

### Step by step

1. **Copy the template** — duplicate `ExampleExploitModule.java` (or
   `ExamplePayloadModule.java`) in the same folder and rename the class.
2. **Fill in the contract** — `id()`, `name()`, `description()`, `options()`,
   `run(...)`. The template already shows the conventions; the important ones:
   - `id()` is stable and lower-case. Prefixes map to workspaces:
     `auxiliary/…` = general, `auxiliary/exploits/…` = gated Exploits
     workspace, `payload/…` = payload workspace.
   - `options()` declares every knob. `required=true` options must be set
     before `run`; `secret=true` options are armed for one run and never
     echoed, logged, or stored.
   - `run()` receives an **immutable snapshot** (`ModuleContext`) plus the
     resolved option map, and returns structured `Finding` values. Never
     throw to report a problem — return a `Finding` with a severity.
3. **Register it** in `ChampionsClient.onInitializeClient`, next to the
   existing lines:

   ```java
   console.registerModule(new ExampleExploitModule());
   console.registerModule(new MyProbeModule());          // your new one
   ```

   Registration is the only wiring: the console, ClickGUI, category GUI,
   help texts, and the module counter pick the module up automatically.
4. **Build and test**: `./gradlew build` (or push and let CI build). In game:
   F8 → `use auxiliary/exploits/my_probe` → `show options` → `set …` → `run`.

### Worked example: a passive latency outlier probe

This is a complete, compiling module that reads only the session snapshot —
copy, rename, adjust, register:

```java
package com.internationalduck.championscode.module.exploit;

import dev.champions.core.module.ExploitResearchModule;
import dev.champions.core.module.Finding;
import dev.champions.core.module.ModuleContext;
import dev.champions.core.module.ModuleOption;
import dev.champions.core.module.ModuleResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Passive probe: flags a session whose latency is far above the value you
 * consider normal for the authorized target. Sends nothing.
 */
public final class LatencyOutlierModule implements ExploitResearchModule {

    @Override public String id() { return "auxiliary/exploits/latency_outlier"; }
    @Override public String name() { return "Latency outlier probe"; }
    @Override public String description() {
        return "Compares the current session ping against a threshold you set. "
            + "Useful as a smoke test before timing-sensitive techniques.";
    }

    @Override public List<ModuleOption> options() {
        return List.of(
            new ModuleOption("RHOST", "CURRENT", true, false, "Current explicitly authorized connection"),
            new ModuleOption("CONFIRM", "", true, true, "One-run authorization phrase"),
            new ModuleOption("EXPECTED_MS", "80", true, false, "Ping you consider normal for this target (1-1000)")
        );
    }

    @Override
    public ModuleResult run(ModuleContext context, Map<String, String> options) {
        int expected = bounded(options.get("EXPECTED_MS"), 80, 1, 1000);
        List<Finding> findings = new ArrayList<>();
        if (!context.connected()) {
            return new ModuleResult("No session to measure", List.of(
                new Finding(Finding.Severity.MEDIUM, "Session",
                    "Not connected.", "Connect to the authorized target first.")));
        }
        int ping = context.pingMillis();
        if (ping > expected * 3) {
            findings.add(new Finding(Finding.Severity.MEDIUM, "Latency",
                "ping=" + ping + " ms is more than 3x the expected " + expected + " ms",
                "Investigate before running timing-sensitive techniques."));
        } else {
            findings.add(new Finding(Finding.Severity.PASS, "Latency",
                "ping=" + ping + " ms vs expected " + expected + " ms",
                "Conditions look nominal."));
        }
        return new ModuleResult("Measured ping " + ping + " ms against expected " + expected + " ms", findings);
    }

    private static int bounded(String raw, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(raw == null ? "" : raw.strip()), min, max);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
```

Register it:

```java
console.registerModule(new LatencyOutlierModule());
```

Why this shape works everywhere at once:

- `RHOST` + `CONFIRM` + `requiresAuthorization()` (inherited from
  `ExploitResearchModule`) mean the console refuses to run it until the exact
  host is allowlisted and the one-run phrase is armed — the gate is applied by
  the framework, not by your code.
- Implementing `ExploitResearchModule` puts it behind the Exploits workspace
  warning and the per-run legal-risk popup. Implement plain `ResearchModule`
  instead if the module is purely defensive and should run without the popup.
- The ClickGUI shows a player-name dropdown automatically for any option whose
  name or description mentions *player*, *username*, *UUID*, or *nickname* —
  mention one of those words in your option description if the option takes a
  player identity.

### When your module must do more than read the snapshot

If you need to *act* (send a bounded probe, evaluate a server response),
implement `ActiveResearchModule` and read the active-capabilities section of
`module-authoring.md` first. The runtime enforces sequential, rate-limited,
filtered probes for aggressive actions, and the audit trail records every run.
For modules that need direct Minecraft client classes (packet construction,
world access), the drop-in folders are the right home precisely because they
sit in the client source set.

Full folder rules (one class per file, no-arg constructor, no
session-dependent static state, deterministic bounds):
`exploit-and-payload-files.md`.

---

## 2. Builtin module (in the core source set)

Use this when the module is defensive by nature and needs no Minecraft client
classes — it can then ship inside the core and be unit-tested headlessly.

1. Create `src/main/java/dev/champions/core/module/builtin/MyAuditModule.java`
   implementing `ResearchModule` (passive) or `ActiveResearchModule` (bounded
   active).
2. Register it in the `ModuleRegistry` constructor, one line:

   ```java
   register(new MyAuditModule());
   ```

3. Add a JUnit test next to the existing builtin tests (see
   `src/test/java/dev/champions/core/module/` for the pattern: build a
   `ModuleContext` fixture, run the module, assert on the findings).

The contract details (required properties, snapshot semantics, telemetry
modules) are in `module-authoring.md`.

### Testing a module of any kind

Modules are plain objects, so tests need no game client:

```java
@Test void flagsHighLatency() {
    ModuleContext snapshot = new ModuleContext(true, true, true, true,
        "example.org", "vanilla", "26.3", 250, 10, 260, "enabled",
        "Tester", "0, 0, 0", "minecraft:overworld", 60, 1, 1, List.of());
    ModuleResult result = new LatencyOutlierModule().run(snapshot,
        Map.of("EXPECTED_MS", "80", "CONFIRM", "x"));
    assertTrue(result.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.MEDIUM));
}
```

Drop-in (client-side) modules cannot live in `src/test`, but you can keep the
`run()` logic free of client classes and test that logic from a builtin-style
test — or temporarily mirror the fixture as done for the example modules.

---

## 3. Runtime Java addon (no rebuild)

For iterating on client-side logic without rebuilding the mod jar. Runtime Java
is **unrestricted code execution** and ships disabled; enabling it requires a
recorded confirmation of the exact source hash.

1. Enable the system: F7 (Java Studio) or `java compile` in the console; the
   console walks you through `java hash` / `java confirm <sha256>`.
2. Put your sources in `.minecraft/config/champions-mod/java/` with a
   `champion-java.json` manifest and an entrypoint implementing
   `ChampionRuntimeAddon`:

   ```java
   package champions.runtime;

   import dev.champions.api.java.ChampionJavaContext;
   import dev.champions.api.java.ChampionRuntimeAddon;

   public final class PingWatch implements ChampionRuntimeAddon {
       @Override
       public void start(ChampionJavaContext context) {
           context.info("Session target: " + context.snapshot().serverAddress());
           context.onStop(() -> context.info("PingWatch stopped"));
       }

       @Override
       public void stop() {
           // Release anything this addon owns.
       }
   }
   ```

3. `java compile` → `java start`. Re-confirm after every source change (the
   hash must match the sources that get compiled).

`ChampionJavaContext` gives you the same `ModuleContext` snapshot plus console
output and client-thread scheduling — see the full requirements, workspace
layout, limits (128 files / 2 MiB), and audit behavior in `runtime-java.md`.

---

## Guardrails that always apply

Whatever route you take, the framework enforces these — your module benefits
automatically, and no module can opt out:

- **Authorization gate.** Modules that declare `requiresAuthorization()` do not
  run until the exact current host is allowlisted (`authorize current`) and, if
  they declare `CONFIRM`, the one-run phrase is armed. Denials are audit-logged.
- **Exploit workspace.** `ExploitResearchModule` implementations live behind
  the entry warning and re-confirm a popup before *every* run.
- **Audit trail.** Every start/finish/denial lands in the append-only JSONL
  audit log with the normalized target.
- **Secret hygiene.** `secret=true` option values are never echoed, logged, or
  persisted, and are consumed at run time — even when the run is denied.
- **Bounds.** Active probes are sequential, rate-limited and filtered; the
  charter (`exploit-research.md`) explains the legal framing.

A good rule of thumb: start from the worked example, keep `run()` reading the
snapshot until you have a reason to do more, and promote to
`ActiveResearchModule` only when the technique genuinely needs a probe.
