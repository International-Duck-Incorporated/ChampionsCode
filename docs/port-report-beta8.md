# Port report — 0.2.0-beta.8

## 1. Audit of what was there before this change

Read before writing anything: `ResearchModule`, `ActiveResearchModule`,
`ExploitResearchModule`, `PayloadModule`, `ModuleContext`, `ModuleResult`,
`EvidenceNode`, `Finding`, `ModuleOption`, `ModuleRegistry`, `ExploitCatalog`,
`PayloadSafety`, `PayloadEffect`, `AuditFolder`, `ChampionConsole.runActive`,
`ActiveModuleRuntime`, `ChatCommandRuntime`, `RuntimeModuleDiscovery`,
`ModuleSourceInstaller`, `LegacyModulePortsTest`, and the 65 Java files in the
`venom4plugin` archive.

What the architecture already had, and what this change reused rather than rebuilt:

| Concern | Existing piece | Reused? |
| --- | --- | --- |
| Module lifecycle | `ResearchModule.run`, `ActiveResearchModule.runActive` | Yes |
| Snapshot | `ModuleContext` | Yes |
| Vulnerability metadata | `ExploitCatalog.Entry` | Yes — mechanisms reference entries by id |
| Tiers | check / verify / payload | Yes — payload tier now delegates to the new engine |
| Bounded effects | `PayloadSafety`, `PayloadEffect` | Yes — the gate calls them |
| Confined writes | `AuditFolder` | Yes — the engine writes through it |
| Authorization | `AuthorizationPolicy`, one-run `CONFIRM` | Yes — the gate reads the booleans |
| Findings/evidence | `Finding`, `EvidenceNode`, `ModuleResult` | Yes — no parallel result model |
| Repository install | `RepoConfig`, `ModuleSourceInstaller` | Untouched |

What did **not** exist and is what this change adds: a unit that owns a
vulnerability independently of its demonstration, a payload contract with declared
identity/effect/cleanup, an authorisation decision that can be tested as a pure
function, and a result state that distinguishes "verified" from "documented".

Two gaps in the previous port that the audit found:

- `PayloadModule.effect()` was documented as "must be a constant", so a module that
  selected its target at run time declared `no side effect` and then wrote a file.
  Fixed in beta.7; the new engine has no such hole because the descriptor is
  re-checked against the material.
- The previous port had no way to demonstrate a mechanism at all: a `VERIFIED`
  result was only reachable through a live chat echo.

## 2. Changed files

New package `src/main/java/dev/champions/core/exploit/`:

```
ExploitMechanism.java  PayloadProvider.java  PayloadDescriptor.java
PayloadKind.java       PayloadShape.java     PayloadMaterial.java
ExecutionGate.java     GateRequest.java      GateDecision.java
ReproductionEngine.java ReproductionResult.java ReproductionState.java
ReproductionBasis.java  MechanismRegistry.java
fixture/     VulnerableFixture, FixtureRegistry, ConsoleDispatchFixture,
             ScriptEvaluationFixture, TypeTagFixture, SerialisationFixture,
             SqlConcatenationFixture, OutboundFetchFixture, PermissionAliasFixture
mechanism/   PortedMechanisms, SkriptConsoleDispatchMechanism,
             ConditionalEventsConsoleMechanism, ItemActionConsoleMechanism,
             PlaceholderConsoleMechanism, DeluxeMenusScriptMechanism,
             WorldGuardTypeTagMechanism, MythicMobsDeserialisationMechanism,
             SqlIdentifierMechanism, OutboundFetchMechanism,
             EssentialsAliasMechanism
payload/     PortedPayloads, InertArtifactPayload, ChatMarkerPayload,
             ConsoleInjectionPayload, SqlTautologyPayload, TypeTagPayload,
             ScriptDispatchPayload, SerialisedProbePayload, InternalUrlPayload,
             AliasEscalationPayload
```

Modified:

- `module/builtin/FixtureReproductionModule.java` (new) — the console-facing
  workspace; registered in `ModuleRegistry`.
- `module/ModuleRegistry.java` — one registration line.
- `src/test/java/.../console/ChampionConsoleTest.java` — module counts (21/22, 4).
- `docs/exploit-separation.md` (new), `docs/porting-venom4plugin.md` (second pass),
  `docs/port-report-beta8.md` (this file), `CHANGELOG.md`, `gradle.properties`.

New tests: `ExploitSeparationTest` (7), `ExecutionGateTest` (11),
`FixtureReproductionTest` (10).

## 3. Build and test results

Run on this machine, JDK 25.0.5, `--no-daemon --max-workers=1`, 448 MB heap:

```
./gradlew clean build      BUILD SUCCESSFUL
junit total=168 failures=0   (28 new)
--self-test                  104 checks, SELF-TEST PASSED
```

Console flows exercised against the real `ChampionConsole`:

| Flow | Observed |
| --- | --- |
| `MECHANISM skript_console`, `PAYLOAD console_injection`, `MODE FIXTURE` | `[HIGH] Reproduced`, state `VERIFIED`, basis `FIXTURE`, fixture reset afterwards |
| same payload, `MODE LIVE`, authorised + confirmed | `[LOW] Blocked by the gate`, state `BLOCKED_BY_GATE`, nothing ran |
| `PAYLOAD inert_artifact` | `ARTIFACT_ONLY`, exactly one file in `artifacts/` |
| `PAYLOAD chat_marker`, `MODE LIVE` | allowed by the gate as a bounded verification |

## 4. Known limitations, stated rather than papered over

1. **A fixture is not the plugin.** `VERIFIED` means the payload reached the seam.
   Whether a given build is unpatched remains a server-side fact, and the module
   prints that in its recommendation on every fixture result.
2. **`script_evaluation` is a model, not a JavaScript engine.** It recognises
   `dispatch("…")`. The DeluxeMenus finding also involves `Java.type` reachability,
   which this does not test and does not claim to.
3. **`console_dispatch` does not parse any plugin's script language.** It reproduces
   the concatenation-into-dispatch seam that four plugin families share.
4. **`sql_concatenation` recognises a token class, not SQL.** It matches quote-break
   plus tautology and returns two rows. It is a demonstration of the seam, not a
   fuzzer.
5. **Seven mechanisms, seven fixtures, one payload shape each** where the sink
   demands it. There is exactly one payload per shape, so "several payloads of the
   same shape for one mechanism" is asserted through the gate's rules rather than
   demonstrated with two shipped console payloads.
6. **No live reproduction of any console/RCE mechanism** exists or is planned: the
   gate refuses execution-capable payloads against live targets by design, so
   `VERIFIED` on a real server is only reachable through bounded verification.
7. **`MODE LIVE` + execution-capable** is refused even with authorisation and
   confirmation. That is deliberate, and it is the one place where this client is
   stricter than the operators' own permission would be.

## 5. Authorization and licensing questions outstanding

1. **The master porting licence is unsigned.** All three signature blocks in
   `MASTER MOD PORTING LICENSE AND TERMS OF USE v1.0` are empty and the effective
   date is "the date of the last required signature". The work here proceeds on the
   operator's instruction, but the instrument is not executed.
2. **§4 items 1–4 are outside that grant.** `cobalt-like.zip`,
   `poc-jars.zip`, `VChatGames-Source.zip` and `meteor_addon_java_modules.zip` are
   listed as "intended to cover" but §4 and §7 say an archive name establishes
   nothing and a signature does not waive another party's terms. Nothing from those
   four was used.
3. **`venom4plugin` ownership is still "to be confirmed"** in Schedule A item 5.
   Everything ported here comes from it.
4. **No third-party binaries were incorporated.** The 14 PoC jars and the vulnerable
   server harness in `poc-jars.zip` were not extracted into the repository, and the
   build has no new dependencies.
5. **Standing product exclusions were kept** despite §3 of the licence permitting
   crash-prone, automation and check-bypassing modules: no dupe, lag, crash,
   force-op, kill-aura or anticheat-bypass module exists in this build, and
   `ChatFilterBypassAll` / `ChatGamesAutoAnswer` were not ported. If any of those is
   wanted, it is an explicit decision to make, not something this change assumed.
