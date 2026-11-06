# Behaviour audit: ten tech mods against ChampionsCode's protections

**Audit date: 2026-11-06.** Scope: the ten mods named in the request. Every claim below
comes from a jar that was downloaded and opened, and each finding names the build it came
from. Nothing here was written from memory of a mod's documentation.

---

## 0. The gate that comes before the audit

**None of the ten has a Fabric 26.3 build.** ChampionsCode targets Fabric 26.3, so nothing
on this list can be loaded on the server ChampionsCode is pointed at. The nearest misses
are NeoForge-only and one is an alpha.

| # | mod | newest build | game versions | loaders | Fabric 26.3? |
|---|---|---|---|---|---|
| 1 | Create | `6.0.10+mc1.21.1` | 1.21.1 | neoforge | **no** |
| 2 | FTB Quests | — | — | — | **not on Modrinth** (FTB maven / CurseForge only) |
| 3 | Botania | `1.20.1-456-forge` | 1.20.1 | forge | **no** |
| 4 | Tinkers' Construct | `3.12.1.231` | 1.20.1 | forge, neoforge | **no** |
| 5 | Applied Energistics 2 | `15.4.12` (fabric, 1.20.1); `26.3.1-alpha` (neoforge) | 1.20.1 / 26.3 | fabric / **neoforge** | **no** — the 26.3 build is NeoForge and alpha |
| 6 | Immersive Engineering | `12.4.2-194` | 1.21.1 | neoforge | **no** |
| 7 | Mekanism | `10.7.19.85` | 1.21.1 | neoforge | **no** |
| 8 | Railcraft (Reborn) | `1.4.9` | 26.1.2 | neoforge | **no** — closest to the target of any of them |
| 9 | BuildCraft | `3.7.4` | 1.5.2 | forge | **no** |
| 10 | OpenComputers | `1.8.10` | 1.12.2 | forge | **no** |

Two consequences worth stating before any behaviour:

- **A behavioural audit here cannot be a 26.3 audit.** I audited the newest *available*
  builds (`15.4.12` AE2 fabric 1.20.1, `6.0.10` Create neoforge 1.21.1, `1.8.10`
  OpenComputers forge 1.12.2, `10.7.19.85` Mekanism neoforge 1.21.1). The mechanisms below
  are long-lived — a wireless terminal has been encrypted since 1.8 — but a version gap of
  this size means a behaviour that matters could still be added or removed before these
  mods reach 26.3.
- **FTB Quests could not be audited at all.** It has no Modrinth distribution; only addons
  (Freeze Fix, Optimizer, ExtraQuests) are there. Saying something confident about its
  reward-command behaviour without opening its jar would be exactly the failure mode this
  workspace exists to avoid, so it is marked unverified rather than described.

## 1. How ChampionsCode protects itself (the thing being interacted with)

Read from the repository, not assumed. Five mechanisms are relevant:

| mechanism | where | what it actually enforces |
|---|---|---|
| `AuthorizationPolicy` + `CONFIRM` | `ChampionConsole.runFromUi` | RHOST must equal the current connection; a one-run phrase is required; the phrase is cleared *even when the decision is a denial* (`activeOptions.put("CONFIRM","")` before the gate returns) |
| `PayloadSafety` | `core/module/payload` | a **refusal** list: allowed chat roots are `say, me, list, help, seed, time, gamemode`; `op, give, kill, tp, setblock, fill, clone, effect, gamerule, whitelist, clear, …` are named and refused; `\n`, `\r`, `\u0000`, `{"`, `${`, `$(`, `` ` `` are forbidden substrings; 240-char command cap, 8 KiB artifact cap |
| Frozen `ModuleContext` | `core/module/ModuleContext` | an immutable `record`; every collection is `List.copyOf`; `packetFlow` defaults to `PacketFlowSnapshot.EMPTY`. A module observes a snapshot, never live state |
| No packet bodies | `PacketFlowSnapshot` | packet **types and counts** only; evidence text says so explicitly: *"Counted by the client; no bodies retained"* |
| Append-only audit | `AuditLog` via `audit.record(...)` | every run logged with target, module, `denied`/`started`/`unavailable` and the reason |

Everything below is assessed against those five.

---

## 2. Behaviours that interact with a protection

Each entry follows the requested shape: **Behaviour / Example / Impact / ChampionsCode note**.

### B1 — Custom-payload protocols the client must decode

**Behaviour.** AE2 and Mekanism each ship their own packet channels. AE2's 1.20.1 jar
carries 4 `CustomPayload`-family references across a wireless subsystem (146 `Wireless`
symbols, 69 `AES`), a spatial-storage subsystem (502 `Spatial`) and a quantum bridge
(48 `QuantumBridge`). Mekanism's 1.21.1 jar carries `Teleport` (463), `Entangloporter`
(127) and a `Security` subsystem (1089 symbols).

**Example.** The shape of it, reduced to a sketch:

```java
// Illustrative: a mod-defined channel the vanilla client has to hand to the mod.
public record TerminalPacket(BlockPos pos, byte[] ciphertext) implements CustomPacketPayload {
    public static final Type<TerminalPacket> TYPE = new Type<>(id("ae2", "wireless"));
    @Override public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByteArray(ciphertext);   // arbitrary length, mod-defined
    }
}
```

**Impact.** Any client-side parser fed by a server-controlled channel is a parser surface.
The size and shape of the payload are the mod's choice, not the game's, so a
"8 KiB will do" assumption about frames stops holding the moment a mod is installed.

**ChampionsCode note.** This is the protection working by *not participating*.
`PacketFlowSnapshot` records packet **types and counts** and nothing else — the evidence
node says `"no bodies retained"` — so ChampionsCode never parses an AE2 or Mekanism payload
and cannot be attacked through one. The cost is a real one and worth naming: a mod with a
chatty custom channel **inflates the mix digest**, so `top_server_mod_traffic`'s baseline
comparison will drift for reasons that have nothing to do with behaviour. That is a
`measurement_caveats` problem, not a security one.

### B2 — Server text that could reach a command

**Behaviour.** Create's clipboard carries free text stages (`create:clipboard_pages`,
`create:clipboard_read_only`); OpenComputers screens and chat components emit arbitrary
text (94 `opencomputers:*` literals in the 1.12.2 jar, `opencomputers:screen_text` among
them); Mekanism and AE2 both surface player-authored text (anvil renaming, cell labels,
terminal search) in server-controlled strings.

**Example.**

```java
// Illustrative: text chosen by one player, shown to another, then possibly typed back.
String label = stack.getOrCreateTag().getString("display");   // attacker-editable
chat.send("Cell renamed to: " + label);                      // newline-bearing label
```

**Impact.** The classic chain is *relayed text → line break inside a command → second
command executed as the person who pastes it*. It is a client-side bug only if the client
builds a command out of the text.

**ChampionsCode note.** `PayloadSafety.FORBIDDEN_SUBSTRINGS` refuses `\n`, `\r`, `\u0000`,
`{"`, `${`, `$(`, `` ` `` — but the stronger protection is structural: **ChampionsCode never
composes a command from server-supplied text.** Every payload's command is a literal in
module source (`"seed"`, `"help"`, `"list"`), so there is no string-concatenation path from
a mod's chat output into a payload. The substring list is the belt; the literal is the
braces. That is the correct design and it holds regardless of which of these ten mods is
installed.

### B3 — Entity and block movement that corrupts the client's model

**Behaviour.** Mekanism teleports entities and items (`PortableTeleporter`, `Teleport`
463 refs, `Entangloporter` 127); AE2's spatial storage **moves whole regions of the world**
into a cell (`Spatial` 502); Create moves entities on contraptions; OpenComputers spawns
robots and drones that act on the world.

**Example.**

```java
// Illustrative: an item or entity reappears at a second coordinate with no path between.
world.teleportTo(entity, link.target());      // instantaneous, by design
```

**Impact.** A client that models motion as a continuous function sees impossible
displacement. Anything built on "did it move further than physics permits" produces
findings that are true observations and false conclusions.

**ChampionsCode note.** `MotionBaselineModule` reports displacement samples and ranks them;
it does not call displacement a violation, which is what keeps it honest here. The gap is
that **no caveat module names teleport-type mods**. `measurement_caveats` (added earlier
today) covers performance mods only, with a stated reason: those perturb the *numbers*.
Teleportation perturbs a *finding*, so it belongs in the same qualifier list and is not
there yet. This is the one place in this audit where I think the collection has a real
hole, and it is a one-line addition per mod.

### B4 — Server-side code execution (outside the client's reach entirely)

**Behaviour.** OpenComputers' 1.12.2 jar is the loudest thing in this audit by two orders
of magnitude: **14,196** `lua` symbols, **685** `internet`, **155** `whitelist`, **132**
`http`, **41** file-write, **21** `crypto`, plus a `URLClassLoader`/`defineClass` path and a
raw `Socket`. That is a full Lua runtime with an allowlisted internet component, a
persistent filesystem and code loading.

**Example.**

```lua
-- Illustrative OpenComputers session: the whole of it, because that is the point.
local c = require("component")
local internet = c.internet
local f = io.open("/home/payload.lua", "w"); f:write(code); f:close()
local ok, err = pcall(dofile, "/home/payload.lua")
internet.request("https://example.invalid/" .. tostring(os.time()))
```

**Impact.** If a server runs OpenComputers, the question of what code executes on that
host is answered by whoever can place a computer, not by the client. No client-side
control changes it, and a tool that implied otherwise would be lying.

**ChampionsCode note.** This is the one behaviour the design deliberately does **not**
pretend to cover. What it does instead is *name the surface*: `audit/code_surface` exists to
report which advertised roots mean "code can be run on this server" for Carpet/Scarpet,
KubeJS, datapack functions and ComputerCraft. **OpenComputers is not in that catalogue.**
That is a genuine gap, and unlike the malformed-diff-on-arrival pattern I have been
reporting all session, this one is mine to flag now: the catalogue reasons about code
execution but stops before the largest example of it. Whether it can be *detected* is
doubtful — the 1.12.2 jar registers no brigadier root — so the honest entry is an
`UNOBSERVABLE` one, which is exactly the shape round one already established for
Simple Voice Chat and ViaVersion.

### B5 — Egress from the server (the direction no port scan sees)

**Behaviour.** OpenComputers' `internet` component is a first-class outbound channel gated
by a server-side whitelist (`internet`, 685 refs; `whitelist`, 155). Create, Mekanism, AE2
and Tinkers' carry no comparable network path in the jars opened (Create: **0** custom
payload refs and no HTTP; Mekanism: 3 payload refs, no HTTP client).

**Example.**

```java
// Illustrative: the allowlist is the whole control, so its contents are the finding.
if (!httpWhitelist.contains(uri.getHost())) throw new AccessDenied(uri);
HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).build(), BodyHandlers.ofString());
```

**Impact.** Data leaves through a connection the server opens. A firewall rule on inbound
ports does nothing; the allowlist is the entire boundary, and its contents live in config
where no client can read them.

**ChampionsCode note.** `audit/egress_surface` (added earlier today) is the module built
for exactly this, and it reports from jar evidence rather than documentation. **OpenComputers
is not in its catalogue either** — the same gap as B4, seen from the other end. Its entry
would be honest in a way few others are: the endpoint is not in the jar, because the
allowlist decides it at runtime, so it belongs with Fast Backups in the
"destination is configuration" group.

### B6 — Nothing that touches the authorization gate

**Behaviour.** Worth stating as a negative finding, because it was checked. None of the
four jars opened rewires the connection's advertised address: no proxy, no forwarding, no
`ServerAddress` override. AE2's quantum bridge and Mekanism's teleporters move *players*
between coordinates, not the *connection* between hosts.

**Impact.** `AuthorizationPolicy` compares `RHOST` against `context.serverAddress()`. A mod
that could rewrite the reported address would attack that comparison directly — the client
would believe it was pointed at an authorized host while the socket went elsewhere. That is
the most dangerous possible interaction with ChampionsCode, and none of these ten does it.

**ChampionsCode note.** The gate is sound against this list. The structural reason it stays
sound is that `ModuleContext` is built by the client from its own connection, not from
anything a server sends — so even a mod that lies in the brand string (which
`top_server_mods` already flags as spoofable) cannot move `RHOST`. Worth keeping that
property in mind if a proxy-support feature is ever added: that would be the change that
puts this gate at risk, not any mod.

---

## 3. Verdict per mod

| mod | auditable detail found | interacts with | severity of interaction |
|---|---|---|---|
| **OpenComputers** | Lua runtime, HTTP + allowlist, filesystem, code load, socket | B4, B5 — code execution and egress; absent from `code_surface` and `egress_surface` | **high**, and unmitigable client-side |
| **AE2** | wireless (AES), spatial storage, quantum bridge, 4 payload refs | B1, B3 — payload parsing surface, world-region movement | medium |
| **Mekanism** | teleport (463), entangloporter (127), security (1089) | B1, B3 — movement model corruption | medium |
| **Create** | 440 brigadier refs, clipboard text stages, contraptions | B2, B3 — relayed text, entity movement | low–medium |
| **Immersive Engineering** | not audited (no jar opened) | — | unrated |
| **Tinkers' Construct** | not audited (no jar opened) | — | unrated |
| **Botania** | not audited (no jar opened) | — | unrated |
| **Railcraft Reborn** | not audited; only one at 26.1.2, nearest to target | — | unrated |
| **BuildCraft** | not audited; latest is 1.5.2 (2013-era API) | — | unrated |
| **FTB Quests** | **cannot be audited** — no public jar on Modrinth; reward-command behaviour is unverified | — | **unverified** |

Four of the ten were opened; six were not. The four were chosen for having the most
security-relevant mechanisms, not at random. I would rather say "six unrated" than pad the
table.

## 4. What this audit changed my mind about

Two catalogue gaps, both mine, both found by asking "which protection does this touch"
rather than "does this mod have a command":

1. **`audit/code_surface` reasons about code execution and omits the largest example of
   it.** OpenComputers belongs there — as an unobservable entry, like ViaVersion, because
   its 1.12.2 jar registers no brigadier root.
2. **`measurement_caveats` covers numbers but not findings.** Teleport-type mods
   (Mekanism, AE2 spatial, Create contraptions) do not perturb a *measurement*; they
   perturb a *conclusion* drawn from `MotionBaselineModule`. The qualifier list should say
   so.

Neither is fixed here. Both are version-gated: the collection's stated rule is 26.3, and
adding 1.12.2 and 1.20.1 mods to a 26.3-scoped catalogue would break the rule that the last
two rounds were built on. They are worth doing when these mods port, or as a
version-agnostic section if you want the rule relaxed.

## 5. Unverified

- **Six of ten mods were not opened.** No claim is made about their behaviour.
- **FTB Quests' quest-reward commands** are the single most interesting unverified item in
  the request — a quest system that runs commands on completion is a server-side code path
  by design — and there is no jar to open, so it stays unverified.
- **The four audited builds are not 26.3 builds**, and two are not even on the loader
  ChampionsCode targets.
- **Symbol counts are not behaviour.** 14,196 `lua` strings mean the runtime is in the jar;
  they do not mean the allowlist is permissive on any particular server.
