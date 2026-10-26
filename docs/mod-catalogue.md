# The mod catalogue

What this build recognises, why each family is worth a look, and when that was
checked. The table is generated from `ModCatalog` and `ModVerification`; a test
fails if a family is missing from this page, so the two cannot drift apart.

## How the list was built (2026-10-26)

Every id below was read out of the `fabric.mod.json` inside the published jar,
because the loader reports that id and a profile under the project name matches
nothing. Three families in the previous list failed that check:

| Profile said | The jar reports | Consequence |
| --- | --- | --- |
| `rei` | `roughlyenoughitems` | never matched an installed REI |
| `xaeros-minimap` | `xaerominimap` | never matched an installed Xaero's Minimap |
| `simple-voice-chat` | `voicechat` | matched only through a duplicate entry |

Versions come from the same jar metadata, filtered to builds that declare support
for the game version this project targets (26.3). Three families have no build for
26.3 on the day of the check and are recorded as they are rather than dropped:

```
cobblemon  1.8.3F          create  6.0.10+mc1.21.1     emi  1.1.24+1.21.1
```

No claim here is a vulnerability claim. A profile says what to review and what the
presence of the mod does not establish.

## Families

| id | mod | runs | audit question | newest release seen | 26.3 | checked |
| --- | --- | --- | --- | --- | --- | --- |
| `cobblemon` | Cobblemon | server-side | permissions | `1.8.3F` | no | 2026-10-26 |
| `voicechat` | Simple Voice Chat | service | service | `2.6.26+26.3` | yes | 2026-10-26 |
| `luckperms` | LuckPerms | server-side | permissions | `5.5.85` | yes | 2026-10-26 |
| `fabric-api` | Fabric API | library | none | `0.165.0+26.3` | yes | 2026-10-26 |
| `create` | Create | server-side | permissions | `6.0.10+mc1.21.1` | no | 2026-10-26 |
| `lithium` | Lithium | server-side | none | `0.26.2+mc26.3` | yes | 2026-10-26 |
| `sodium` | Sodium | client-only | none | `0.9.3-beta.1+mc26.3` | yes | 2026-10-26 |
| `iris` | Iris | client-only | none | `1.11.7+mc26.3` | yes | 2026-10-26 |
| `distanthorizons` | Distant Horizons | client-only | none | `3.3.4` | yes | 2026-10-26 |
| `terralith` | Terralith | worldgen | none | `2.6.5` | yes | 2026-10-26 |
| `jei` | Just Enough Items | client-only | none | `31.17.0.94` | yes | 2026-10-26 |
| `roughlyenoughitems` | Roughly Enough Items | client-only | none | `26.3.824` | yes | 2026-10-26 |
| `emi` | EMI | client-only | none | `1.1.24+1.21.1` | no | 2026-10-26 |
| `journeymap` | JourneyMap | client-only | data | `26.3-6.0.10` | yes | 2026-10-26 |
| `xaerominimap` | Xaero's Minimap | client-only | none | `26.6.2` | yes | 2026-10-26 |
| `modmenu` | Mod Menu | client-only | none | `21.0.0` | yes | 2026-10-26 |
| `cloth-config` | Cloth Config | library | none | `26.3.159` | yes | 2026-10-26 |
| `architectury` | Architectury API | library | none | `22.0.5` | yes | 2026-10-26 |
| `geyser` | Geyser | service | service | `2.12.0-SNAPSHOT` | yes | 2026-10-26 |
| `viaversion` | ViaVersion | server-side | protocol | `5.12.2-SNAPSHOT` | yes | 2026-10-26 |
| `spark` | spark | server-side | service | `1.10.187` | yes | 2026-10-26 |
| `floodgate` | Floodgate | server-side | auth | `2.2.7-SNAPSHOT` | yes | 2026-10-26 |
| `fabricproxy-lite` | FabricProxy-Lite | server-side | auth | `2.12.0` | yes | 2026-10-26 |
| `easyauth` | EasyAuth | server-side | auth | `3.4.4` | yes | 2026-10-26 |
| `viabackwards` | ViaBackwards | server-side | protocol | `5.12.2-SNAPSHOT` | yes | 2026-10-26 |
| `viafabricplus` | ViaFabricPlus | client-only | protocol | `5.1.2` | yes | 2026-10-26 |
| `bluemap` | BlueMap | service | service | `5.29` | yes | 2026-10-26 |
| `squaremap` | squaremap | service | service | `1.4.3` | yes | 2026-10-26 |
| `journeymap_webmap` | JourneyMap Web Map | service | service | `1.0.13` | yes | 2026-10-26 |
| `e4mc` | e4mc | service | service | `6.2.3+modern` | yes | 2026-10-26 |
| `plasmovoice` | Plasmo Voice | service | service | `2.2.0-beta.2` | yes | 2026-10-26 |
| `carpet` | Carpet | server-side | code execution | `26.3+v260915` | yes | 2026-10-26 |
| `computercraft` | CC: Tweaked | server-side | code execution | `1.120.3` | yes | 2026-10-26 |
| `minescript` | Minescript | client-only | code execution | `5.0` | yes | 2026-10-26 |
| `trustedclients` | Trusted Clients | server-side | client inspection | `0.7.0` | yes | 2026-10-26 |
| `openpartiesandclaims` | Open Parties and Claims | server-side | permissions | `0.33.0` | yes | 2026-10-26 |
| `fabric-permissions-api-v0` | Fabric Permissions API | library | permissions | `0.7.0` | yes | 2026-10-26 |
| `worldedit` | WorldEdit | server-side | permissions | `7.4.6+7648-00664b0` | yes | 2026-10-26 |
| `ledger` | Ledger | server-side | logging | `1.3.25` | yes | 2026-10-26 |
| `fastback` | FastBack | server-side | logging | `0.35.0+26.3.0` | yes | 2026-10-26 |
| `nochatreports` | No Chat Reports | client-only | telemetry | `26.3-v2.21.0` | yes | 2026-10-26 |
| `essential-container` | Essential | client-only | telemetry | `1.0.0` | yes | 2026-10-26 |
| `customskinloader-bootstrap` | CustomSkinLoader | client-only | data | `15.1` | yes | 2026-10-26 |
| `no-telemetry` | No Telemetry | client-only | telemetry | `1.10.0` | yes | 2026-10-26 |
| `chunky` | Chunky | server-side | none | `1.5.3` | yes | 2026-10-26 |

## Aliases

Entries kept only so an older or shorter id still resolves to the family above.
They carry no version of their own.

| alias | resolves to |
| --- | --- |
| `cobblemon-fabric` | `cobblemon` |
| `simple-voice-chat` | `voicechat` |
| `luckperms-web` | `luckperms` |
| `rei` | `roughlyenoughitems` |
| `xaeros-minimap` | `xaerominimap` |

## What the catalogue does not do

- It does not claim a mod is vulnerable, or that any version is unsafe.
- It cannot see the server's mod list. A profile matched against this client's
  inventory says something about this client; a channel identifier is the only
  weak evidence about the far end.
- It does not decide policy. A voice port, a web map and a code-execution mod are
  all normal on the servers that want them.

