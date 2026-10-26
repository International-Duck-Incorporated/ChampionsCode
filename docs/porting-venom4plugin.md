# Porting `venom4plugin` into ChampionsCode

The old addon is 47 Meteor Client modules over 65 source files -- 48 classes named
`*Module.java`, of which `PocModule` is the abstract base the other 47 extend, so 47 is the
number that can be registered and run. Most of them do one of two things: send a
list of chat commands to whatever server the client is connected to, or write a
configuration file that an operator deploys to a server
they control. The second thing is the valuable one. The first is why the port is
not a copy.

This document is the record of what happened to each module, and why.

## What a port means here

A Meteor module is a toggle in a cheat client's menu. A ChampionsCode module is a
research instrument with a declared tier, a gate and evidence. Porting therefore
means deciding, for each source module, which tier its work belongs to — not
copying its `onActivate`.

| Tier | What the ported module does | Where it lives |
| --- | --- | --- |
| **check** | Reads the `ModuleContext` snapshot and reports what it suggests. Sends nothing. | core or collection |
| **verify** | Sends one bounded request and records the response. | core |
| **payload** | Writes or performs one declared reproduction. | core |
| **mechanism** | Reproduces the vulnerability against a controlled fixture, with any compatible payload. | `core.exploit` |

The mechanism tier is the second pass over this port, and it changed the verdicts
below. A module whose effect was "send this at the server" was not refused for its
knowledge — it was refused for its delivery. That knowledge now has somewhere to
live that can actually demonstrate it: `docs/exploit-separation.md` describes the
mechanism / payload / gate split, and every module that previously read "refused"
now names the mechanism it became.

## The knowledge survived; the delivery did not

The addon's real content is a table: which plugin, which class, which mechanism.
That table is now `ExploitCatalog` in the core, with 15 entries covering the
plugins the addon looked at. Each entry carries the surface (how the plugin
betrays itself), the mechanism (the class or feature that makes the bug real), the
impact, a severity, and — where one exists — a portable reproduction.

Two ported modules read it:

- `auxiliary/audit/plugin_exploit_surface` (check) joins the catalogue against the
  command tree the server already advertised, and reports the mechanism for each
  match. It sends nothing, which is the improvement over the original: the old
  `plugin-detect` tab-completed its way across the tree and slept 50 ms between
  commands.
- `auxiliary/payloads/poc_artifact` (payload) writes one declared reproduction
  into the client's audit folder. The declared effect is checked before the gate,
  the file is a bare name with an inert extension inside a fixed folder, and the
  revert plan is a deletion the module performs.

An artifact is a document. Nothing in ChampionsCode applies it, deploys it or
sends it anywhere, and the module says so in its own findings.

## The mapping

Every module in the archive, and what became of it.

| Source module | Disposition | Why |
| --- | --- | --- |
| `PluginDetectModule` | **ported** — `auxiliary/scanner/plugin_enumeration` (collection) | Same job from the advertised tree; no commands sent. |
| `SkriptDeployModule` | **ported twice** — artifact `skript_console`, mechanism `skript_console` | The artifact was the module; the mechanism behind it is console dispatch, now reproducible. |
| `SkriptConsoleOpModule`, `SkriptConsoleCmdModule` | **ported as mechanism** — `skript_console` | The live injection is gone; the sink it exploited is reproduced against `console_dispatch` with harmless `say` commands. |
| `ConditionalEventsOpModule` | **ported as mechanism** — `conditional_events_console` | Same sink, different trigger; shares the payload with Skript. |
| `ChatPluginsCmdModule`, `VotePluginsCmdModule`, `WorldPluginsCmdModule`, `EconomyPluginsCmdModule`, `CosmeticPluginsCmdModule`, `UtilityPluginsCmdModule` | **merged into mechanisms** | Their knowledge is the console/expansion/placeholder families; their command batteries are not. |
| `AuthPluginsCmdModule` | **catalogue only** — `auth_plugins` | The mechanism is token disclosure, which needs a server-side observation, not a client action. |
| `EssentialsXCmdModule` | **ported as mechanism** — `essentialsx_alias` | Its `/essentials:op me` was reaching for an alias permission bypass. The bypass is now reproduced against a permission model, not a server. |
| `DeluxeMenuConsoleRceModule`, `DeluxeMenuJsRceModule` | **ported as mechanism** — `deluxemenus_script` | One mechanism, one payload; the `Java.type` runtime call is documented but not shipped. |
| `DeluxeMenuPlaceholderInjectionModule`, `UltraCosmeticsConsoleCommandInjectionModule` | **merged** — `placeholder_expansion` mechanism + `placeholder_injection` entry | Same sink: expanded text reaching a dispatch. |
| `WorldGuardYamlDeployModule`, `WorldGuardYamlDeserialisationModule` | **ported as mechanism** — `worldguard_yaml` | The parser resolving a `!!tag` is reproduced; only the fixture's probe classes are nameable. |
| `MythicMobsUnsafeDeserializationModule` | **ported as mechanism** — `mythicmobs_deser` | The fixture uses a real `ObjectInputStream`; only the fixture's own probe object is in the stream. |
| `DiscordSRVSqlInjectionModule`, `CoreProtectDeepRollbackModule`, `QuickShopDeepModule` | **ported as mechanism** — `sql_identifier` + per-plugin catalogue entries | One seam, three plugins; the payload proves the row-count change and writes nothing. |
| `GeyserSSRF_Module` | **ported as mechanism** — `outbound_fetch` | The URL resolution is reproduced against an internal-host table. |
| `BlueMapSsrfModule` | **merged** — `outbound_fetch` mechanism + `bluemap_ssrf` entry | Same seam, told apart by the plugin. |
| `EssentialModExploitModule`, `EssentialUpnpModule` | **catalogue only** — `auth_plugins` | Session-state mutation on a live peer; a fixture would model nothing the reader could check. |
| `ItemPluginsDeployModule` | **ported as mechanism** — `item_plugin_actions` | Console dispatch with an item as the trigger. |
| `NetworkPluginsDeployModule`, `DatabaseSqliModule`, `EconomySqliModule`, `CosmeticSqliModule` | **merged** — `sql_identifier` mechanism and the corresponding entries | SQL-by-concatenation is one class across several plugins. |
| `ZAuctionHouseSqliDeployModule` | **merged** — `sql_identifier` | Same seam. |
| `ArmorStandToolsProtectedGriefingModule`, `BlueSlimeCoreDebugEventModule`, `BungeeCordBungeeGuardTokenLeakModule`, `BuycraftXApiKeyTheftModule`, `ChatGamesConsoleCommandInjectionModule`, `DeluxeMenuBungeeConnectModule`, `UltraCosmeticsSqlInjectionModule`, `UltraCosmeticsConsoleCommandInjectionModule` | **catalogue entries, no mechanism** | Each needs an observation this client cannot make without a live target (a leaked token, a griefed entity, a debug event). Their knowledge is kept; nothing pretends to reproduce them. |
| `ViaBackwardsSnapshotModule`, `SkriptDeepModule`, `EssentialXDeepModule` | **check tier** | They were report-only in the original; they remain report-only. |
| `EssentialsXConfigModule` | **merged** — `placeholder_expansion` mechanism | Its artefact is an Essentials config file with `%player%` in a welcome message. The sink is a placeholder expansion reaching a dispatch, which is already reproduced; a server configuration file is not something this client deploys. |
| `ZAuctionHouseSqlInjectionModule` | **excluded** | Its artefact is a migration config that drops a table on the source database. Destructive by construction, so it is outside the standing rule regardless of authorisation; the SQL seam itself is covered by `sql_identifier`. |
| `ZAuctionHouseDeserializationRceModule` | **catalogue only** — `zauctionhouse_deser` | A deserialisation gadget chain aimed at a live server's plugin classpath. The seam is `mythicmobs_deser`; a second fixture would model the same thing, and the gadget itself is not shipped. The finding survives as catalogue entry `zauctionhouse_deser`, with no artifact. |
| `PocModule` | **base class, not a module** | The abstract class every one of the 47 extends. Its `onActivate` sends `getPlayerCommands()` at the server and drops a payload file in the game directory -- the two things this port does not do. Nothing is inherited from it; it is listed here only so the count of 48 `*Module.java` files reconciles with 47 modules. |
| `WorldEditSchematicDeserializeModule` | **catalogue only** — `worldedit_schematic` | A historical `//schematic` path traversal, marked not exploitable on WorldEdit 7.4.4+. Kept as catalogue entry `worldedit_schematic`; there is nothing to reproduce on a current build. |

Four of the 47 modules had no row in the first version of this table
(`EssentialsXConfig`, `ZAuctionHouseSqlInjection`, `ZAuctionHouseDeserializationRce`,
`WorldEditSchematicDeserialize`), and the header said 34 modules when the archive contained
48 `*Module.java` files, 47 of them modules. Both fixed here.

The old 34 came from the addon's own exit log line -- `34 modules injected` -- not from
counting. `Venom4Plugin.onInitialize` contains 33 `Modules.get().add(...)` calls. So the
archive is: 48 classes named `*Module.java`, 47 concrete modules, 33 of them actually
registered, and 14 that were written but never added to the module list. All 47 have a
disposition; the `registered` column of `port-manifest.tsv` records which ones ever ran.

The same table is machine-readable at `port-manifest.tsv` in the jar (and in
`src/main/resources/META-INF/`), one row per class, and `PortManifestTest` fails the build
if a row is dropped, a disposition is unknown, or a catalogue-only row names an entry that
`ExploitCatalog` does not contain.

### What the port produced, by tier

| Tier | Count | Where |
| --- | --- | --- |
| mechanism | 10 | `core/exploit/mechanism/` |
| payload | 9 | `core/exploit/payload/` |
| module in this jar | 21 | `core/module/builtin/` |
| module in the collection repo | 7 | `championscode-modules/modules/` |
| catalogue entry | 18 | `core/catalog/ExploitCatalog.java` |

Source files outside `poc/meteor/modules` — the `poc/<plugin>/…` classes, the
`PocModule` base, `Venom4Plugin`, `ShellPopper`, `VulnerableServerStart` — are
scaffolding: a Meteor addon entrypoint, a base class that writes into the game
directory, and a local test harness. None of it ports; the equivalent concepts are
`ModuleRegistry`, `PayloadModule` and the audit folder.

## What was deliberately not carried over

- **Chat chatter.** The original reported to public chat via `ChatUtils.sendPlayerMsg`.
  A research tool that announces itself in the target's chat is doing something
  other than auditing.
- **Silent mode.** The original's "silent" mode still enumerated; it just did not
  mention it. Reading the advertised tree is genuinely silent, and there is
  nothing to switch off.
- **Bundled jars.** `poc-jars.zip` contains fourteen prebuilt PoC plugins and a
  vulnerable server harness. They are third-party binaries under unknown terms,
  and nothing in this client needs them.
- **The `- Copy` duplicates.** Two source trees, identical.

## Third-party material

The archive's licence covers `venom4plugin`; it explicitly does not cover
`cobalt-like.zip`, `poc-jars.zip`, `VChatGames-Source.zip` or
`meteor_addon_java_modules.zip`. Nothing was taken from those for this port. The
meteor addon stubs in `meteor_addon_java_modules.zip` are empty shells in any
case, and `VChatGames.java` is a separate plugin's source.

`ChatFilterBypassAll.java` and `ChatGamesAutoAnswer.java` were not ported: filter
evasion and automated gameplay are outside the collection regardless of what a
licence permits. Neither has a vulnerability mechanism behind it that a fixture
could reproduce — they are features for winning arguments with a filter and for
playing a game without playing it, which is a different project.

## Second pass: what changed and why

The first port kept the knowledge and dropped the delivery, and stopped there. The
second pass built the part that makes the knowledge demonstrable: a mechanism tier
that reproduces the actual sink against a controlled fixture, with the payload as a
separate, swappable piece.

Nothing was relaxed to achieve it. The execution-capable payloads are refused by the
gate against a live target *even when the operator has authorised it*, which is
stricter than the original code was — the original fired those commands at whatever
server the client was connected to. The difference is that the demonstration now
happens against something that records it instead of something that suffers it.
