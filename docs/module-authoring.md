# Defensive module authoring

Champions has two module types:

- `ResearchModule` receives an immutable `ModuleContext` and performs passive analysis of that snapshot plus bounded, explicit option values.
- `ActiveResearchModule` may request a narrow, enumerated capability from
  `ActiveModuleRuntime` after the console's target and confirmation gates pass.

Both are registered in `ModuleRegistry`. `ExploitResearchModule` is a marker for
research workflows that require the separate Exploits workspace and a fresh
legal-risk popup before every run; it does not grant an additional capability.
Build-time modules follow the restrictions below as an authoring contract.
External Java modules and runtime addons are explicitly unsafe, unrestricted
code inside the Minecraft process; their source-hash confirmation is a review
step, not a sandbox or an enforcement boundary. See
`external-java-modules.md`, `runtime-java.md`, and `exploit-research.md` for the
loading, lifecycle, and review models.

## Required properties

A module must:

- have a stable, lower-case identifier such as `auxiliary/audit/example`;
- describe exactly what it observes or sends and avoid overstating evidence;
- return structured `Finding` values with actionable recommendations;
- keep `RHOST` and one-run `CONFIRM` options when authorization is required;
- avoid secrets in result text and audit details;
- have deterministic bounds for duration, requests, and captured output;
- implement `ExploitResearchModule` when it belongs in the Exploits workspace,
  so entry and per-run warnings cannot be skipped; and
- define expected impact, cleanup, stop conditions, and evidence limitations for
  any active research proposal.

A module must not:

- open sockets or create another network client;
- send arbitrary Minecraft packets, chat, commands, or plugin messages;
- access arbitrary files or launch processes;
- use reflection, class loading, JNI, or external script engines;
- perform denial-of-service, brute force, exploit delivery, or evasion;
- retry through aliases/namespaces to bypass server permissions;
- bypass `AuthorizationPolicy` or target a host other than the current session.

## Passive module skeleton

```java
public final class ExampleModule implements ResearchModule {
    public String id() { return "auxiliary/audit/example"; }
    public String name() { return "Example audit"; }
    public String description() { return "Reviews an existing client signal."; }

    public List<ModuleOption> options() {
        return List.of(
            new ModuleOption("RHOST", "CURRENT", true, false, "Current server"),
            new ModuleOption("CONFIRM", "", true, true, "One-run phrase")
        );
    }

    public ModuleResult run(ModuleContext context, Map<String, String> options) {
        return new ModuleResult("Review complete", List.of(
            new Finding(Finding.Severity.INFO, "Signal", context.serverBrand(),
                "Validate this observation against server-side configuration.")
        ));
    }
}
```

## Active capabilities

Active modules cannot supply arbitrary console text. `ChatCommandRequest`
contains a normalized advertised root plus one enum variant: `BLANK`, `VERSION`,
or `V`. `CommandSafetyPolicy` filters vanilla, Bukkit/Paper defaults, malformed
roots, and hazardous local names. The client runtime then revalidates the request
against the live Brigadier tree immediately before sending it.

`BLANK` is allowed only when the advertised root is not executable without
arguments. `VERSION` and `V` are allowed only when those exact literal children
are advertised. Probes run sequentially with a ten-tick minimum gap and bounded
message/time budgets.

`ChatPluginEnumeratorModule` demonstrates both `SILENT` (no runtime call) and
`AGGRESSIVE` modes. Add new variants only after separate safety review and tests.
Register each module in `ModuleRegistry` and include filtering, parser, bounds,
denial, and error-path tests.

## Telemetry modules

`ChampionsConnectionMixin` may record only packet class, direction, and timing.
Do not add packet bodies, chat contents, NBT, credentials, encryption material,
or unbounded histories to `PacketTelemetry`. The timeline limit is 512 events.
Replay modules may render or export bounded metadata but must never reinject it.

Inventory modules must remain read-only: no click fabrication, state-ID guessing,
packet delay, desynchronization, close suppression, or flush queues. Class-level
action/update correlations must be labeled approximate and must not be presented
as proof of acknowledgement, desynchronization, duplication, or exploit timing.
Motion modules must present observations and uncertainty, not cheat verdicts or
bypass guidance. See `research-influences.md` for the clean-room boundary.
