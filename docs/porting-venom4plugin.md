# Porting `venom4plugin` into ChampionsCode

The old addon is 34 Meteor Client modules over 65 source files, most of which do
one of two things: send a list of chat commands to whatever server the client is
connected to, or write a configuration file that an operator deploys to a server
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

| Source module | Port | Why |
| --- | --- | --- |
| `PluginDetectModule` | **ported** — `auxiliary/scanner/plugin_enumeration` (collection) | Same job from the advertised tree, no commands sent. |
| `SkriptDeployModule` | **ported** — `poc_artifact`, entry `skript_console` | The artifact is the module; the deploy path was always documentation. |
| `ConditionalEventsOpModule` | **ported** — `poc_artifact`, entry `conditional_events_console` | Artifact kept; the console-dispatches-op effect is on the server, not in our client. |
| `DeluxeMenuConsoleRceModule`, `DeluxeMenuJsRceModule` | **ported** — `poc_artifact`, entry `deluxemenus_js` | One entry for the class; two source modules were one mechanism. |
| `DeluxeMenuPlaceholderInjectionModule`, `UltraCosmeticsConsoleCommandInjectionModule` | **merged** — `poc_artifact`, entry `placeholder_injection` | Same sink: expanded text reaching a console dispatch. |
| `WorldGuardYamlDeployModule`, `WorldGuardYamlDeserialisationModule` | **ported** — `poc_artifact`, entry `worldguard_yaml` | Artifact describes the class rather than carrying a live type tag. |
| `NetworkPluginsDeployModule` | **merged** — entries `discordsrv_sql`, `placeholder_expansion` | It was two configs for two different classes of bug. |
| `DatabaseSqliModule`, `EconomySqliModule`, `CosmeticSqliModule` | **merged** — `poc_artifact`, entry `quickshop_economy` and companions | SQL-by-concatenation is one class across several plugins. |
| `ItemPluginsDeployModule` | **merged** — `poc_artifact`, entry `item_plugin_actions` | Item actions are ConditionalEvents with a different trigger. |
| `BlueMapSsrfModule` | **ported** — `poc_artifact`, entry `bluemap_ssrf` | Artifact is a checklist, which is what it always was. |
| `EssentialUpnpModule` | **merged** — entry `auth_plugins` | An exploit guide, not code; it belongs in prose, not in a payload. |
| `CoreProtectDeepRollbackModule` | **ported** — check tier, entry `coreprotect_rollback_sql` | Report-only: the original's only action was sending `/co`. |
| `DiscordSRVSqlInjectionModule` | **ported** — check tier, entry `discordsrv_sql` | Report-only. |
| `EssentialXDeepModule` | **ported** — check tier, entry `essentialsx_alt_commands` | Report-only. |
| `QuickShopDeepModule` | **ported** — check tier, entry `quickshop_economy` | Report-only. |
| `SkriptDeepModule` | **ported** — check tier, entry `skript_console` | Report-only. |
| `ViaBackwardsSnapshotModule` | **ported** — check tier, entry `viabackwards_snapshot` | Report-only; it was always a build-version note. |
| `GeyserSSRF_Module` | **ported** — check tier, entry `geyser_skin_ssrf` | Report-only, with the CVE cited. |
| `MythicMobsUnsafeDeserializationModule` | **ported** — check tier, entry `mythicmobs_deser` | Report-only: the original sent `/mythicmobs` and nothing else. |
| `EssentialsXCmdModule` | **refused** | Its action is `/op me`, `/give me diamond 64`, `/nuke me` against the target. Standing policy: no module whose effect is gaining operator access or items. |
| `SkriptConsoleOpModule`, `SkriptConsoleCmdModule` | **refused** | Live console injection against the connected server. The class is documented by the `skript_console` entry instead. |
| `ChatPluginsCmdModule`, `VotePluginsCmdModule`, `WorldPluginsCmdModule`, `EconomyPluginsCmdModule`, `CosmeticPluginsCmdModule`, `UtilityPluginsCmdModule`, `AuthPluginsCmdModule` | **refused** | Command batteries (`/op me`, injection strings) fired at the target. Their knowledge is in the catalogue. |
| `EssentialModExploitModule` | **refused** | Sends `/essential` commands that mutate session state on the target. |
| `ZAuctionHouseSqliDeployModule`, `SkriptDeployModule` (command form) | **merged** | Covered by their families above. |

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
licence permits.
