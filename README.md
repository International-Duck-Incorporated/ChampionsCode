# Champions Mod

<img src="docs/branding/ChampionsCode.png" alt="Champions Mod logo" width="180">

A **client-side Fabric mod for Minecraft Java Edition 26.3** that provides an
msfconsole-inspired workflow for authorized, defensive server/plugin research,
plus a bounded Volt-style runtime called **ChampionScript**.

> **Status:** `0.1.0-alpha.6` — working foundation. The project builds, tests,
> and produces an installable Fabric JAR. It now includes packet-flow, metadata
> replay, inventory-integrity, passive transaction-flow, client-resilience, and
> passive motion foundations.

## What is implemented

### Research console

Press **F8** in game to open the GUI. It has separate **Console** and **Volt**
tabs; the key can be rebound as **Open Champions Console** under the Champions
Mod controls category.

- `show modules`, `search`, `use`, `info`, `show options`
- `set`, `unset`, `run`, `back`, `status`, `reload`
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

### ChampionScript (“Volt” mode)

ChampionScript runs at client runtime, but is intentionally **not arbitrary JVM
code execution or game injection**. It is a small, non-Turing-complete language
with a read-only API. The dedicated **Volt** tab accepts ChampionScript directly
and recognizes `list`, `show <name>`, and `run <name>` shortcuts. The same runtime
is available through console commands:

```text
volt list
volt show demo
volt run demo
volt eval print "brand=" + server.brand; assert connected
```

Supported statements:

- `print <expression>` and `notify <expression>`
- `let name = <expression>` and `get name`
- `status`, `server`, `player`, `position`, `modules`
- `assert connected`, `assert disconnected`, `assert online_mode`

Runtime values include `server.address`, `server.brand`, `server.version`,
`server.protocol`, `server.players`, `server.ping`, `player.name`,
`player.position`, `player.dimension`, `client.connected`, and `client.fps`.

The sandbox has source, statement, output, and time budgets. It exposes no file,
socket, packet, chat, reflection, native-process, or game-mutation API.

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

This starter intentionally does **not** include exploit delivery, credential or
token collection, stealth, arbitrary remote scanning, packet fuzzing, arbitrary
command execution, permission/namespace bypasses, destructive checks, native/JVM
script execution, or code injection into Minecraft.

## Requirements

- Minecraft Java Edition **26.3**
- Fabric Loader **0.19.5+**
- Fabric API **0.161.0+26.3**
- Java **25**

This is a normal client-side Fabric mod: one JAR, with no server-side component
or companion process. It runs alongside other compatible Fabric mods.

## Install

1. Install Fabric Loader for Minecraft 26.3.
2. Put Fabric API for 26.3 in the instance's `mods` folder.
3. Put `champions-mod-0.1.0-alpha.6.jar` in the same folder.
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
└── scripts/
    └── demo.champ
```

## Build from source

```bash
# JDK 25 must be active
./gradlew build
```

The mod and source JARs are written to `build/libs/`. Tests cover command
parsing, authorization, bounded telemetry/transaction correlation, and
ChampionScript capability boundaries.

## Project layout

```text
src/main/java/dev/champions/core/
├── console/       # command shell and safe parsing
├── module/        # module SPI, context, findings, built-ins
├── script/        # ChampionScript interpreter and script repository
└── security/      # exact-host authorization and audit logging

src/client/java/dev/champions/client/
├── ChampionsClient.java
├── MinecraftResearchContext.java
├── PacketTelemetry.java
├── InventoryTelemetry.java
├── PlayerMotionTelemetry.java
├── mixin/ChampionsConnectionMixin.java
└── ui/ChampionsConsoleScreen.java
```

See [`docs/championscript.md`](docs/championscript.md),
[`docs/module-authoring.md`](docs/module-authoring.md),
[`docs/research-influences.md`](docs/research-influences.md),
[`docs/roadmap.md`](docs/roadmap.md), and [`PUBLISHING.md`](PUBLISHING.md) for
the Volt language reference, extension rules, clean-room design notes, planned
work, and PowerShell publishing steps.

## License

MIT. See [`LICENSE`](LICENSE).
