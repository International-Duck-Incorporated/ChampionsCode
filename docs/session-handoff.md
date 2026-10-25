# Session handoff — external front end + version strategy (0.2.0-desktop)

What was actually built, what was verified, and what is still open. Read this first
if you pick the branch up cold.

---

## 1. Deliverables

| Area | Where | State |
|---|---|---|
| External app (terminal, modules, code space) | `src/main/java/dev/champions/desktop/**` (9 classes) | implemented, compiled, screenshotted |
| Loopback bridge (game ⇄ app) | `src/main/java/dev/champions/bridge/DesktopBridge.java` | implemented, compiles in `main` |
| Wire protocol + JSON | `src/main/java/dev/champions/net/ipc/**` | implemented, round-trip tested |
| Shared view models | `src/main/java/dev/champions/surface/ConsoleBuffer.java` | implemented, unit-checked in self-test |
| Mod-side wiring | `src/client/java/dev/champions/client/desktop/**`, `ChampionsClient` | written; **not compiled** (needs the Fabric toolchain) |
| Version capability contract | `src/main/java/dev/champions/core/mc/**` + `ChampionConsole` gate | implemented, self-test covers refusal |
| Dependency-free logging | `src/main/java/dev/champions/core/log/Log.java` | implemented; core compiles with **no** classpath |
| Build/CI | `build.gradle` (`Main-Class`, `desktopSelfTest`), `.gitlab-ci.yml` | written |
| Docs | `docs/external-desktop-plan.md`, `docs/version-compatibility.md`, `README.md`, `CHANGELOG.md` | written |
| Screenshot | `docs/branding/external-desktop.png` | captured from the real JAR |

Total new code: ~4 300 lines across 30 files (of which ~2 700 are the desktop app and
bridge).

---

## 2. How it was verified here

Environment: Debian 13, Temurin **JDK 25.0.5**, no Minecraft, no Gradle (no network
access to the Fabric/Mojang repositories was needed because everything built is
Minecraft-free).

```bash
# core + desktop compile with NO external classpath at all
javac --release 25 -d /tmp/out $(find src/main/java -name '*.java')

# the packaged JAR runs as an application
java -jar champions-mod-0.2.0-desktop.jar --self-test      # 28 checks, all pass
java -jar champions-mod-0.2.0-desktop.jar --demo           # Swing UI on Xvfb
```

`--self-test` currently asserts:

* bounded scrollback (1 000 lines, front-drop, delta windows, revision counter)
* history cursor behaviour
* wire codec round-trips (tones, offsets, nested arrays, unicode, control chars)
* **no Minecraft/Fabric class is loadable**, i.e. the desktop app is truly standalone
* a full gated console session against the simulated `lab.demo.invalid` target:
  unauthorized → denied, authorize → runs, second run → denied (CONFIRM consumed)
* `audit.jsonl` gets the run, the denial, and is valid JSON line by line
* the version gate refuses `plugin_surface` on a `minimal` build, names the missing
  feature, and still runs an ungated module
* the drop-in compiler compiles, registers, selects and unloads a `.java` file with
  **no game running**

Screenshots (Xvfb, 1600×1010) show all three windows rendering the real console
output produced by the real modules.

---

## 3. Deliberately not verified here (and why)

1. **`src/client/**` compilation.** Needs Fabric Loom, Minecraft 26.3 and Fabric API;
   that toolchain cannot run in this sandbox. The new client files
   (`DesktopSettings`, `DesktopLauncher`, `DesktopRuntime`) and the `ChampionsClient`
   edits use only APIs already present in the code base
   (`Minecraft.getInstance().execute`, `FabricLoader.getModContainer`,
   `SessionPlayers.names`, `ExploitWarningPrompt.Request`).
   **First action on a dev machine: `./gradlew build`.**
2. **The bridge against a live game.** Protocol, framing, thread hand-off and the
   prompt round-trip are implemented, but the game half needs a running client.
   Suggested smoke test: launch the client, open F8, confirm the app spawns, run
   `status`, then a gated passive module from the modules window.
3. **Mouse/keyboard automation in the sandbox.** Xvfb has no window manager, so
   xdotool clicks never reach the app. UI interaction is exercised only through the
   self-test's model-level calls.
4. **The multi-version matrix.** Specified in `docs/version-compatibility.md`; the
   capability layer it depends on is implemented, the Stonecutter build is not.

---

## 4. Architecture in one paragraph

`core` (console, 17 modules, auth, audit, JSON, compiler discovery) has **zero**
Minecraft imports and now also **zero** third-party imports. The desktop app is a
second view over that same console: in standalone mode it owns a `ChampionConsole`
directly; attached, it talks to the game's console through a loopback JSON-lines
bridge and only ever sends the same calls the in-game windows make. `ConsoleBuffer`
is the shared terminal model, so behaviour (tones, bounds, history) is identical
while rendering stays native to each toolkit. Every gated action — allowlist,
`CONFIRM`, exploit warning, audit — still executes inside the game process; the app
can display and request, never authorize.

---

## 5. Known gaps / next steps (ordered)

1. **Run `./gradlew build`**; fix any Fabric-side drift (most likely candidates: the
   `desktop` package name colliding with anything, and `build.gradle` Groovy syntax
   for the new `jar`/`desktopSelfTest` blocks).
2. **Bridge smoke test** against the real client (30 min): spawn, attach, `status`,
   one gated run, detach, reconnect.
3. **Code-space parity:** `java.read`/`java.write`/`java.list` currently route to the
   game's own `java modules …` commands; the app edits files on disk directly. If the
   game and the app must be able to edit the same file, add a file-sync frame with the
   same 2 MiB/128-file bounds.
4. **`ui.mode` first-run dialog** — decided: default `both`, no dialog; the setting is
   documented in `desktop.properties` and in `status` output.
5. **Stonecutter matrix** (see the other doc). The cheapest first target is 26.3 + the
   latest 1.21.x, with `champions_capabilities=minimal` for the 1.21.x node until the
   screen layer is ported.
6. **Updater asset selection** per `+mc<version>` suffix (specified, not implemented).
7. **Polish:** dockable log panel in the terminal window, Swing syntax highlighting in
   the code space, remembered window bounds in `desktop/settings.json`.

---

## 6. Rollback

Everything is additive and switchable:

* `ui=in-game` in `config/champions-mod/desktop.properties` restores 0.1.x behaviour
  exactly (no bridge, no process, in-game windows only).
* Deleting the new `desktop`/`bridge`/`net`/`surface` packages leaves core changes
  behind: `Log` facade (drop-in replacement for SLF4J calls), `lineSequence()` and
  `setCapabilitiesSupplier()` on `ChampionConsole` — all inert if unused.
* The version gate is a no-op when no capability supplier is set, so existing tests
  and third-party drop-in modules behave as before.
