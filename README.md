# Champions Mod

<img src="docs/branding/ChampionsCode.png" alt="Champions Mod logo" width="180">

A **client-side Fabric mod for Minecraft Java Edition 26.3** that provides an
msfconsole-inspired workflow for authorized, defensive server/plugin research,
plus an explicitly enabled runtime Java addon development system.

> **Status:** `0.1.0-alpha.9` - working foundation. The project builds, tests,
> and produces an installable Fabric JAR. It now includes packet-flow, metadata
> replay, inventory-integrity, passive transaction-flow, client-resilience, and
> passive motion foundations.

## What is implemented

### Research console

Press **F8** in game to open the movable terminal window. Drag its title bar to
reposition it. It has separate **Console** and **Java** tabs; the key can be
rebound as **Open Champions Console** under the Champions Mod controls category.
Run `clickgui` in the Console tab to open the movable module and option browser.

- `clickgui`, `show modules`, `search`, `use`, `info`, `show options`
- `set`, `unset`, `run`, `back`, `status`, `reload`
- ClickGUI module list, structured option editors, confirmation arming, and run controls
- Module registry with typed options and structured findings
- Exact-host authorization allowlist
- One-run `CONFIRM` phrase
- Append-only JSONL audit log
- Command history, scrolling, colors, and redaction of the confirmation phrase

Included modules:

| Module | Purpose |
|---|---|
| `auxiliary/scanner/server_fingerprint` | Reports metadata already disclosed to the connected client. |
| `auxiliary/audit/connection_posture` | Reviews passive session and transport indicators. |
| `auxiliary/audit/plugin_surface` | Inventories command roots advertised to the current player. It does not execute them or claim conclusive plugin detection. |
| `auxiliary/scanner/chat_plugin_enumerator` | Filters default commands. SILENT marks non-default roots without sending; AGGRESSIVE probes only safe advertised blank/version/v paths and captures bounded responses. |
| `auxiliary/observe/wire_lens` | Aggregates packet class names, directions, rates, and bursts without retaining payloads. |
| `auxiliary/replay/session_trace` | Renders a bounded packet-metadata timeline for ordering analysis; it cannot reinject traffic. |
| `auxiliary/audit/container_integrity` | Reviews menu IDs/state, slots, carried stacks, item-component density, and inventory flow. |
| `auxiliary/observe/transaction_echo` | Passively correlates menu-action and menu-update packet classes for coarse response timing, without retaining IDs or slot data. |
| `auxiliary/audit/client_resilience` | Warns about packet-rate bursts, custom-payload concentration, connection errors, and timeline pressure. |
| `auxiliary/observe/motion_evidence` | Passively summarizes broad remote-player motion evidence without verdicts, reporting, or punishment. |

### Telemetry boundaries

The client keeps a fixed 512-entry rolling timeline containing only packet
**direction, Java class name, and timestamp**. Packet bodies, chat text, NBT,
credentials, and encryption material are not stored. Old entries are evicted.
The session-trace module displays this metadata but cannot resend it.

Container telemetry reads the already-visible local menu state without clicking,
closing, delaying, duplicating, or fabricating inventory packets. Transaction
Echo pairs packet class/timing metadata only: container IDs, state revisions,
slots, items, and payloads remain unavailable, so it cannot establish desync or
exploitability. Motion evidence uses rendered remote-player positions and
intentionally broad thresholds; lag, teleports, vehicles, and interpolation can
explain its notes.

### Runtime Java addons

The **Java** tab compiles real `.java` files from
`.minecraft/config/champions-mod/java/` with the standard JDK 25 compiler. All
sources are compiled together, loaded through a fresh classloader, and started
through the `ChampionRuntimeAddon` interface. Explicit reload calls `stop()`,
runs registered cleanup callbacks, closes the old classloader, compiles the
replacement, and invokes the new `start()` on the client thread.

```text
java status
java sources
java classpath
java hash
java confirm <current-sha256>
java compile
java start
java reload
java stop
```

Runtime Java is **unrestricted code execution inside Minecraft**. It is not a
sandbox and can access game internals, packets, files, credentials, networking,
reflection, process APIs, native libraries, and other loaded mods. The feature
is disabled by default, requires a full JDK 25, and requires both
`developerMode=true` and confirmation of the exact current source-set SHA-256.
Any source or manifest change invalidates that confirmation.

## Safety model

Champions Mod is designed for systems you own or are explicitly authorized to
assess.

1. Passive modules only receive an immutable snapshot of data already available
   to the current Minecraft client.
2. The active runtime cannot send arbitrary console input. It revalidates every
   root against the live advertised command tree, filters vanilla/Bukkit/default
   and hazardous roots, and permits only blank, `version`, or `v` probe variants.
   Blank probes are rejected for roots executable without arguments.
3. AGGRESSIVE probes run sequentially, at most two per second, across no more
   than 12 candidate roots with bounded response windows.
4. `RHOST` must be `CURRENT` or exactly match the active connection.
5. The host must be present in `authorized-targets.txt`; wildcards are rejected.
6. `CONFIRM` must be set to `I_HAVE_AUTHORIZATION` for each module run, and is
   consumed afterward.
7. Runs are recorded in `audit.jsonl` without secret option values.

The research modules intentionally do **not** include exploit delivery,
credential or token collection, stealth, arbitrary remote scanning, packet
fuzzing, arbitrary command execution, permission/namespace bypasses, or
destructive checks. Runtime Java is a separate, local developer feature with an
explicit unsafe-mode and source-hash gate; once started, its code is unrestricted
and is outside the module capability boundary.

## Requirements

- Minecraft Java Edition **26.3**
- Fabric Loader **0.19.5+**
- Fabric API **0.161.0+26.3**
- Java **25**; a full **JDK 25** is required for runtime Java compilation

This is a normal client-side Fabric mod: one JAR, with no server-side component
or companion process. It runs alongside other compatible Fabric mods.

## Install

1. Install Fabric Loader for Minecraft 26.3.
2. Put Fabric API for 26.3 in the instance's `mods` folder.
3. Put `champions-mod-0.1.0-alpha.9.jar` in the same folder.
4. Start Minecraft and press **F8**.

## First authorized run

Localhost and singleplayer are allowlisted by default:

```text
show modules
use auxiliary/scanner/server_fingerprint
show options
set CONFIRM I_HAVE_AUTHORIZATION
run
```

To mark candidates without sending commands:

```text
use auxiliary/scanner/chat_plugin_enumerator
set MODE silent
set CONFIRM I_HAVE_AUTHORIZATION
run
```

To run bounded active version discovery:

```text
set MODE aggressive
set MAX_COMMANDS 8
set CONFIRM I_HAVE_AUTHORIZATION
run
```

SILENT filters vanilla, Bukkit/Paper defaults, and hazardous roots, then marks
remaining advertised commands. AGGRESSIVE considers the same candidates and:

- sends a blank command only when the advertised root is not executable;
- sends `version` or `v` only when that literal child is advertised;
- revalidates each path immediately before sending;
- runs sequentially with a ten-tick minimum gap and bounded chat capture.

The intentionally misspelled value `agresive` is accepted as an alias.

Telemetry modules use the same authorization gate:

```text
use auxiliary/observe/wire_lens
set CONFIRM I_HAVE_AUTHORIZATION
run

use auxiliary/audit/container_integrity
set CONFIRM I_HAVE_AUTHORIZATION
run

use auxiliary/observe/transaction_echo
set CONFIRM I_HAVE_AUTHORIZATION
run

use auxiliary/observe/motion_evidence
set CONFIRM I_HAVE_AUTHORIZATION
run
```

For an authorized remote lab/server:

1. Connect to that server normally.
2. Add its **exact hostname** to
   `.minecraft/config/champions-mod/authorized-targets.txt`.
3. In the console, run `reload`.
4. Select a module, set the one-run confirmation phrase, and run it.

Generated files:

```text
.minecraft/config/champions-mod/
├── authorized-targets.txt
├── audit.jsonl
├── java-runtime.properties
└── java/
    ├── champion-java.json
    ├── Example.java
    └── .build/
```

## Build from source

```bash
# JDK 25 must be active
./gradlew build
```

The mod and source JARs are written to `build/libs/`. Tests cover command
parsing, authorization, bounded telemetry/transaction correlation, deterministic
runtime-source hashing, manifest validation, and JDK compilation.

The `Fabric 26.3 build` GitHub Actions workflow runs on pushes to `main`, pull
requests targeting `main`, and manual dispatch. It uses Java 25, verifies the
configured Fabric 26.3 dependency versions, runs the clean build and test suite,
checks the packaged `fabric.mod.json` and client mixin configuration, generates
SHA-256 sums, and uploads both JARs as a workflow artifact.

## Project layout

```text
src/main/java/dev/champions/
├── api/java/      # runtime addon entrypoint and context API
└── core/
    ├── console/   # command shell and safe parsing
    ├── java/      # manifest, source hashing, and JDK compiler wrapper
    ├── module/    # module SPI, context, findings, built-ins
    └── security/  # exact-host authorization and audit logging

src/client/java/dev/champions/client/
├── ChampionsClient.java
├── MinecraftResearchContext.java
├── PacketTelemetry.java
├── InventoryTelemetry.java
├── PlayerMotionTelemetry.java
├── java/JavaRuntimeManager.java
├── mixin/ChampionsConnectionMixin.java
└── ui/ChampionsConsoleScreen.java
```

See [`docs/runtime-java.md`](docs/runtime-java.md),
[`docs/module-authoring.md`](docs/module-authoring.md),
[`docs/research-influences.md`](docs/research-influences.md),
[`docs/roadmap.md`](docs/roadmap.md), and [`PUBLISHING.md`](PUBLISHING.md) for
the runtime Java lifecycle and risk model, extension rules, clean-room design
notes, planned work, and PowerShell publishing steps.

## License

MIT. See [`LICENSE`](LICENSE).
