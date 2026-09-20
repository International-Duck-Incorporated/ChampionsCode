# Older project module review

Reviewed on 2026-09-20 for the Champions Mod 0.1.0-alpha.13 port.

## Scope and source state

The public Drive folder was retrieved selectively after the initial recursive download was rate-limited. The review covered:

- `venom4plugin-0.2.0.zip` through `venom4plugin-0.3.0.zip`;
- the separate `venom4plugin-source-0.2.3.zip` and `venom4plugin-source-0.2.4.zip` archives;
- `ChatFilterBypassAll.java`;
- two byte-identical copies of `ChatGamesAutoAnswer`;
- `meteor_addon_java_modules.zip`; and
- `only_java_final.zip`.

The source archives contained 108 distinct Java class names under module directories across all revisions. The `0.3.0` archive contained 47 module wrappers, of which 34 were registered by its entrypoint. Its build metadata still targeted Minecraft `26.1.2`, Yarn `1.21.11+build.3`, Fabric Loader `0.19.2`, and a Meteor Client snapshot. It was not a normal standalone Champions module set for Minecraft 26.3.

The `0.3.0` archive includes a CC0 license and an unchanged Meteor addon template README. The loose Java bundles do not establish equally clear per-file provenance. Champions therefore does not copy source from these files. The compatible behavior below was reimplemented against the Champions API, tests, authorization gate, audit path, and Minecraft 26.3 build.

Third-party plugin JARs from the Drive folder are not bundled, shaded, or treated as permission to copy plugin code. Several old vulnerability statements were assertions based on local decompilation rather than independently validated advisories. Champions reports them only as administrator review leads, never as confirmed vulnerabilities.

## Classification result

### Already covered by Champions

| Older module | Disposition |
|---|---|
| `PluginDetectModule` | Duplicate of the existing passive Plugin Surface and SILENT/AGGRESSIVE Chat Plugin Enumerator workflows. No second sender was added. |
| `ServerPluginDetectModule` | Earlier duplicate of the same advertised-command inventory concept. |

### Adapted into bounded defensive modules

| Older input | Champions 0.1.0-alpha.13 result |
|---|---|
| `ChatFilterBypassAll` | `auxiliary/audit/chat_normalization` keeps only defensive canonicalization. It removes selected invisible controls, formatting, combining marks, confusables, and substitutions from one local sample. Bypass generation and auto-retry were removed. |
| Both `ChatGamesAutoAnswer` copies | `auxiliary/audit/chat_game_fairness` classifies one supplied prompt and reports automation exposure. It does not solve the prompt, monitor live chat, or send an answer. |
| `DependencyScanModule` and `DependencyScan_Module` | `auxiliary/audit/dependency_posture` reviews at most 32 administrator-supplied `name@version` entries for missing pins, conflicts, and moving builds. It does not read files, query registries, or claim live CVE coverage. |
| `ArchiveDeepScan_PoC`, `VulnerabilityReportModule`, `MiscPluginDefenseModule`, and `NewVulnerabilityDiscovery_PoC` | Their report-oriented concept became the passive `auxiliary/audit/plugin_risk_review`. It maps only already-advertised command roots to hardening profiles and labels every match non-conclusive. |
| `CoreProtectDeepRollbackModule`, `DiscordSRVSqlInjectionModule`, `EssentialXDeepModule`, `GeyserSSRF_Module`, `MythicMobsUnsafeDeserializationModule`, `QuickShopDeepModule`, `SkriptDeepModule`, and `ViaBackwardsSnapshotModule` | Only component names and general defensive review themes were retained in Plugin Risk Review. No trigger, exploit condition, payload, vulnerable-version claim, or remote command sequence was ported. |
| Loose `CoreProtect_Module`, `DiscordSRV_Malware_Module`, `EssentialsX_Module`, `GeyserSSRF_Module`, `MythicMobs_Module`, `QuickShop_Module`, `Skript_Module`, `DiscordSRVIntegrityModule`, and `GeyserSSRFDefenseModule` | Folded into the same non-conclusive review profiles or declared-inventory hygiene checks. Their stub output and unverified version assertions were not copied. |

These four ports are passive `ResearchModule` implementations. They retain `RHOST` and one-run `CONFIRM`, produce structured findings, appear in both GUI modes, and are covered by unit tests. None uses sockets, packet bodies, live chat, arbitrary files, reflection, class loading, process APIs, or external script engines.

### Excluded from built-in capabilities

The following behavior families were rejected:

- credential or token theft, including API-key and proxy-secret collection;
- operator, permission, or authentication bypass attempts;
- arbitrary console-command injection and privileged command testing;
- generated malicious YAML, Skript, JavaScript, SQL, serialized objects, gadget chains, or plugin snippets;
- SQL injection, SSRF triggering, unsafe-deserialization triggering, path traversal, or RCE delivery;
- griefing, destructive database actions, forced proxy moves, and malicious reward/configuration generation;
- chat-filter evasion transforms and automatic resend logic; and
- automatic chat-game solving, live monitoring, and answer sending.

The old base class automatically sent every string returned by `getPlayerCommands()` and wrote generated payloads under the game directory. That execution model is not compatible with Champions' passive module API and was not imported. The narrow Champions active runtime remains limited to filtered, advertised blank/version/v probes in the existing Chat Plugin Enumerator.

## Complete source-archive module-name inventory

Disposition codes:

- `D`: duplicate of an existing Champions capability;
- `A`: concept adapted only into one of the four bounded defensive modules above;
- `X`: excluded from built-in capabilities.

### D

```text
PluginDetectModule
ServerPluginDetectModule
```

### A

```text
CoreProtectDeepRollbackModule
DiscordSRVSqlInjectionModule
EssentialXDeepModule
GeyserSSRF_Module
MythicMobsUnsafeDeserializationModule
QuickShopDeepModule
SkriptDeepModule
ViaBackwardsSnapshotModule
```

### X

```text
AdvancedAchievementsCmdModule
AdvancedAchievementsModule
AdvancedAchievementsSqliModule
ArmorStandToolsProtectedGriefingModule
AuctionHouseCmdModule
AuctionHouseModule
AuthPluginsCmdModule
BlueMapModule
BlueMapPathModule
BlueMapSsrfModule
BlueSlimeCoreDebugEventModule
BungeeCordBungeeGuardTokenLeakModule
BuycraftXApiKeyTheftModule
ChatGamesConsoleCommandInjectionModule
ChatGamesSpongeConsoleModule
ChatGamesSpongeModule
ChatPluginsCmdModule
ConditionalEventsModule
ConditionalEventsOpModule
CosmeticPluginsCmdModule
CosmeticSqliModule
CrazyCratesCmdModule
CrazyCratesModule
CrazyCratesSqliModule
DailyRewardsCmdModule
DailyRewardsModule
DailyRewardsSqliModule
DatabaseSqliModule
DeluxeMenuBungeeConnectModule
DeluxeMenuConsoleRceModule
DeluxeMenuJsRceModule
DeluxeMenuPlaceholderInjectionModule
DeluxeMenuRceModule
DeluxeTagsConfigModule
DeluxeTagsModule
DeluxeTagsPlaceholderModule
DiscordSRVJdbcModule
DiscordSrvDeployModule
DiscordSrvSsrfModule
EconomyPluginsCmdModule
EconomyShopGUIModule
EconomyShopGuiCmdModule
EconomyShopGuiSqliModule
EconomySqliModule
EssentialExploitModule
EssentialModExploitModule
EssentialModule
EssentialUpnpModule
EssentialsXCmdModule
EssentialsXConfigModule
ExcellentEnchantsModule
ExcellentEnchantsPlaceholderModule
ExecutableItemsCmdModule
ExecutableItemsDeployModule
ExecutableItemsModule
GSitCmdModule
GSitModule
InteractiveChatClickjackModule
InteractiveChatCmdModule
InteractiveChatModule
InteractiveChatPlaceholderModule
ItemPluginsDeployModule
LotterySixModule
LotterySixSqliModule
MarriageMasterCmdModule
MarriageMasterModule
MarriageMasterYamlModule
NetworkPluginsDeployModule
PlayerWarpsCmdModule
PlayerWarpsModule
PyroFishingCmdModule
PyroFishingModule
SkriptCommandExecutionModule
SkriptConsoleCmdModule
SkriptConsoleOpModule
SkriptDeployModule
SkriptOpModule
SkriptRceModule
TABSnakeYamlModule
TabDeserDeployModule
TabSnakeYamlModule
UltraCosmeticsConsoleCommandInjectionModule
UltraCosmeticsSqlInjectionModule
UltraCosmeticsSqliDeployModule
UtilityPluginsCmdModule
VotePluginsCmdModule
VotingPluginAuthBypassModule
VotingPluginConsoleCmdModule
VotingPluginOpModule
WorldEditSchematicDeserializeModule
WorldEditSchematicModule
WorldGuardYamlDeployModule
WorldGuardYamlDeserialisationModule
WorldPluginsCmdModule
ZAuctionHouseDeserDeployModule
ZAuctionHouseDeserializationRceModule
ZAuctionHouseSqlInjectionModule
ZAuctionHouseSqliDeployModule
```

## Loose-file inventory

| File or class | Classification |
|---|---|
| `ChatFilterBypassAll.java` | A, defensive normalizer only; bypass and resend code excluded. |
| `ChatGamesAutoAnswer.java` and identical copy | A, prompt fairness classification only; solver and sender excluded. |
| `DependencyScanModule`, `VulnerabilityReportModule`, `MiscPluginDefenseModule`, `DiscordSRVIntegrityModule`, `GeyserSSRFDefenseModule` | A, report/profile concepts only. |
| `ArchiveDeepScan_PoC`, `DependencyScan_Module`, `CoreProtect_Module`, `DiscordSRV_Malware_Module`, `EssentialsX_Module`, `GeyserSSRF_Module`, `MythicMobs_Module`, `QuickShop_Module`, `Skript_Module`, `NewVulnerabilityDiscovery_PoC` | A, inventory or hardening concepts only. |
| `CoreProtect_DeepRollback_PoC`, `DiscordSRV_SQLInjection_PoC`, `MythicMobs_DeepDeserialization_PoC` | X as PoCs; only their component names contribute to non-conclusive review profiles. |
| Both `MeteorVulnAddon` stubs | X as obsolete Meteor entrypoints. |

## Compatibility and provenance decision

No old binary or source file is part of the Champions JAR. The new implementations are original, dependency-free Java 25 code under the Champions MIT project. They compile with the normal Fabric 26.3 build, use the current option and finding model, remain visible in the sorted list and category-window GUIs, and inherit the console's exact-target authorization and JSONL audit logging.
