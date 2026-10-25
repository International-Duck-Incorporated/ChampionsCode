# Version compatibility — running ChampionsCode on 1.21.11 *and* 26.3

**Status:** capability layer implemented in this tree; the multi-version build matrix is specified
but not enabled (it is a build-system change, see §6).

---

## 1. The problem, precisely stated

> "Some servers require a mod/version to be installed. ChampionsCode is on 26.3 and only goes up
> to 1.21.11, so you are stuck."

Two separate things are tangled together here, and they need different answers:

**(a) Server-side requirements are not about ChampionsCode.** The mod registers **no** custom
payload channels — `grep -rn "PayloadTypeRegistry\|ClientPlayNetworking"` finds nothing outside a
code comment. A Fabric client mod that registers no channels never appears in
`minecraft:register`, so no server can require it and no server can detect it. Verified in this
tree.

**(b) The real lock is that a ChampionsCode build only exists for one Minecraft version.** If the
server (or another required mod, or a friend group, or a server's ViaVersion setup) pins you to
1.21.11, you cannot install a 26.3-only mod at all. That is the thing to fix.

---

## 2. Why one JAR cannot span 1.21.11 and 26.3

It is not a packaging problem. The 26.x line changed the toolchain underneath every mod:

| | 1.21.x | 26.x |
|---|---|---|
| Java bytecode / toolchain | **Java 21** | **Java 25** (`build.gradle` here sets `options.release = 25`) |
| Mojang obfuscation | obfuscated → **remapping Loom** (intermediary/Mojang mappings) | **not obfuscated** → *loom-no-remap* |
| Fabric API line | 0.1xx + 1.21.x | 0.161.0+26.3 |
| Client GUI API | `GuiGraphics`, `drawString`, … | `GuiGraphicsExtractor`, `extractRenderState`, new input events (`KeyEvent`, `MouseButtonEvent`, `CharacterEvent`) |

A class file compiled for Java 25 cannot load on a Java 21 runtime, and code written against
`GuiGraphicsExtractor` cannot link against 1.21.11's `GuiGraphics`. Every mod in the ecosystem that
supports both lines does it with **one source tree and one JAR per target** (Stonecutter + a
per-version property file), not with one universal JAR. That is the standard, and it is what this
project should do.

---

## 3. What makes it cheap *here*

The external-app work already done for this release is also the compatibility strategy, because it
split the mod into a version-free core and a thin adapter:

| Layer | Files | Version-sensitive? |
|---|---|---|
| `core/**` — console, 17 modules, authorization, audit, JSON, log tail, runtime compiler | 60+ | **No** |
| `bridge/**` + `desktop/**` + `surface/**` — loopback bridge, Swing app, terminal model | 22 | **No** |
| `src/client/**` — Fabric glue, 5 in-game screens, telemetry capture | 30 | **Yes** |

The version-sensitive part is roughly **25 MC classes**, and 22 of them are GUI/input classes used
by the in-game screens. The non-GUI, genuinely load-bearing ones are:

```
Minecraft · ClientPacketListener · Connection · Packet · ItemStack · AbstractContainerMenu
PlayerInfo · ClientLevel · ServerData · ResolvableProfile · Identifier · Component
```

So a version where the screens are not ported still costs almost nothing: ship core + bridge and
let the **desktop app be the entire UI**.

---

## 4. The capability contract (implemented now)

`dev.champions.core.mc.ClientCapabilities` declares, per build, what actually works:

```java
ClientCapabilities.full(...)       // reference build: everything (current 26.3 build)
ClientCapabilities.headless(...)   // no in-game screens; UI = desktop app
ClientCapabilities.minimal(...)    // text modules only; no tree/packet/container access
```

`VersionGated` lets a module declare what it needs, and the console **refuses to run it with an
explicit reason** instead of returning empty evidence:

```
champions(auxiliary/audit/plugin_surface) > run
[!] auxiliary/audit/plugin_surface needs advertised command tree, which this build for
    Minecraft 1.21.11 does not provide.
    Use a build of ChampionsCode for this Minecraft version, or run the module on a supported target.
```

Refusals are audited (`outcome: "unavailable"`), and the capability summary is printed by
`status`, pushed to the external app's status bar, and covered by `--self-test`.

Modules already gated: `wire_lens`, `session_trace`, `transaction_echo` → packet telemetry;
`container_integrity` → container snapshots; `motion_evidence` → motion samples;
`plugin_surface`, `chat_plugin_enumerator` → command tree.

---

## 5. Version matrix to publish

One row per shipped JAR. `both` means in-game screens **and** desktop app (each JAR still contains
both), `desktop` means external UI only.

| Target | Java | Loom | UI | Module coverage |
|---|---|---|---|---|
| **26.3** (current) | 25 | no-remap | both | full |
| 26.1 / 26.2 | 25 | no-remap | both | full (same API line) |
| 1.21.11 → 1.21.9 | 21 | remapped | desktop | full core; screens optional |
| 1.21.8 → 1.21.5 | 21 | remapped | desktop | telemetry subset, gated by `ClientCapabilities` |
| older / new snapshots | — | — | desktop | best-effort `minimal` |

The mid-range rows are honest about decay: telemetry classes change between minor versions, so a
build for those versions declares a smaller capability set rather than pretending.

---

## 6. Enabling it: Stonecutter in this repository

Stonecutter (0.9.x, the current standard) pre-processes marked comments per version node, so the
**one** source tree produces `:fabric:26.3`, `:fabric:1.21.11`, … each with its own Loom config.
The 2026 ecosystem convention also matches what this repo needs: remapping Loom + JDK 21 for
1.21.x, no-remap Loom + JDK 25 for 26.x.

```kotlin
// settings.gradle.kts (sketch)
pluginManagement { repositories { maven("https://maven.kikugie.dev/releases"); maven("https://maven.fabricmc.net/"); mavenCentral() } }
plugins { id("dev.kikugie.stonecutter") version "0.9.8" }
stonecutter {
    create(rootProject) {
        vers("1.21.11", "1.21.11")
        vers("26.3", "26.3")          // becomes the active node
    }
}
```

```properties
# versions/1.21.11/gradle.properties  (per-node values; core code is shared)
minecraft_version=1.21.11
java_version=21
loom_remap=true
fabric_api_version=0.141.3+1.21.11
champions_build_target=mc1.21.11
champions_capabilities=minimal
```

```properties
# versions/26.3/gradle.properties
minecraft_version=26.3
java_version=25
loom_remap=false
fabric_api_version=0.161.0+26.3
champions_build_target=mc26.3
champions_capabilities=full
```

Only three files then need `//?` conditions, because everything else is version-free:

1. `MinecraftResearchContext` — telemetry capture (`//? if >=1.21.9 { ... }`)
2. `ChatCommandRuntime` — Brigadier tree/`acceptsMessages` naming
3. `ChampionsClient` — Fabric API event packages + the `ClientCapabilities` factory it reports

The five in-game screens are compiled only for nodes whose `champions_capabilities=full|headless'`
screens flag is on. Everything else (`core`, `bridge`, `desktop`, `surface`) compiles **once** and
is copied into every node — Stonecutter does this automatically for unmarked files.

### Adding a Minecraft version later = 4 steps

1. `stonecutter { vers("1.22.0", "1.22.0") }` + a `versions/1.22.0/gradle.properties`.
2. `./gradlew ":1.22.0:build"` and read the compiler errors — they point at the 3 adapter files.
3. Set `champions_capabilities` to what actually works (`full` / `headless` / `minimal`).
4. Add the row to the matrix above and to the release notes.

---

## 7. Artifact naming and the updater

```
champions-mod-0.2.0+mc26.3.jar        <- install this one if you play 26.3
champions-mod-0.2.0+mc1.21.11.jar     <- install this one if you play 1.21.11
champions-mod-0.2.0+mc1.21.11-sources.jar
SHA256SUMS.txt
```

* `fabric.mod.json` in each JAR declares that node's exact `minecraft`/`java`/`fabric-api` bounds,
  so Fabric Loader refuses a mismatched JAR instead of crashing mid-session.
* The updater already matches assets by exact name. It is extended to select the asset whose
  `+mc<running version>` matches `Minecraft.getInstance().getVersionType()`/loader metadata, and to
  refuse (with a reason) when no build exists for the running version — no silent wrong download.
* The desktop app is unaffected: it is inside every JAR and is not version-bound at all.

---

## 8. CI: build the matrix, test every node

```yaml
# .gitlab-ci.yml (skeleton, added alongside the existing build job)
.build-matrix:
  parallel:
    matrix:
      - MC_NODE: ["26.3", "1.21.11"]
  image: eclipse-temurin:25-jdk          # Gradle daemon on 25; nodes compile with their own release
  script:
    - ./gradlew ":$MC_NODE:build" --no-daemon --stacktrace
    - java -jar "$MC_NODE/build/libs/champions-mod-"*.jar --self-test   # headless desktop proof
    - ./gradlew ":$MC_NODE:verifyCapabilities"                          # refuses full-UI claims without GUI classes
```

`--self-test` already exists and is version-independent: it proves the JAR runs as a desktop
program, that the console's authorization/audit/CONFIRM pipeline works, and that no Minecraft class
is on the desktop classpath. Run it for **every** matrix node.

---

## 9. What still cannot be fixed, stated plainly

* **One JAR for all Minecraft versions is impossible** for a mod that reads client internals. Java
  21 vs 25 bytecode and remapped vs non-remapped 26.x make it a hard boundary. The fix is one JAR
  *per version*, built from one tree, with identical behaviour — which is what this document
  specifies.
* **A 26.3 client cannot join a server that requires a specific older client mod.** No client-side
  trick fixes a server-side version requirement; the answer is installing the ChampionsCode build
  that matches the version you actually play.
* **Snapshots will always lag.** New snapshots get a `minimal` build first (passive modules + the
  desktop app work), with telemetry following once the adapter is updated.

---

## 10. Sources for the ecosystem facts

* Stonecutter is the current multi-version Gradle plugin (pre-processed comments, one project per
  version node) — `codeberg.org/stonecutter/stonecutter`.
* Public multi-version Fabric/NeoForge templates in 2026 build 1.21 → 26.3 from one source tree and
  split toolchains explicitly: **JDK 21 + remapping Loom for 1.21.x, JDK 25 + loom-no-remap for
  26.x** (`OddHero/minecraft-mod-template`, `UltraXn/... PR #15`, `Hoknyy/mcpfabric`).
