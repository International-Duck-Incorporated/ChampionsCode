# Plan — External ClickGUI, Terminal and Code Space (still one JAR)

**Status:** implemented — see `CHANGELOG.md` (`0.2.0-desktop`) for the shipped
subset and `docs/version-compatibility.md` for the multi-version strategy. The
phases below are kept as the record of what was planned; phase 3 (IPC), 4
(modules window), 5 (code space) and 7 (self-test/CI) are done, phases 1 and 2
shipped in the lighter `surface`/`desktop` form described here.
**Target:** `0.2.0` — same mod JAR also ships a standalone desktop front end
**Author:** Arena agent, based on a read of `0.1.0-beta.4`

---

## 1. Goal

Today every ChampionsCode UI is a Minecraft `Screen` inside the game frame. The goal is to
move the three working surfaces — **ClickGUI**, **terminal (msfconsole)**, and **code space
(Volt-style script editor)** — into real OS windows that live outside Minecraft, while
keeping **one distributable JAR**, **one set of modules**, **one authorization/audit
pipeline**, and **the existing in-game screens as a working fallback**.

Non-goals (at least for this milestone):

* A second Minecraft render window (see §3 for why that is rejected).
* Remote/multiplayer control of someone else's client — the desktop app talks only to a
  game process on the same machine, over loopback, with a per-launch token.
* Replacing the in-game UI. It stays, unchanged, and is still what you get if the desktop
  app cannot start.

---

## 2. What the current code already gives us

This is the important part: the refactor is much smaller than it looks, because the console
core is already free of Minecraft.

| Evidence | Value |
|---|---|
| `net.minecraft.*` / `net.fabricmc.*` imports under `src/main/java/dev/champions/**` | **0** |
| `ChampionConsole` constructor | already takes `Supplier<ModuleContext>`, `ActiveModuleRuntime` (an interface), `RuntimeJavaCommands`, three `Runnable`s, `ExploitWarningPrompt`, `UpdateService`, `closeUi` |
| UI façade on the console | `lines()`, `history()`, `prompt()`, `emit()`, `moduleViews()`, `exploitModuleViews()`, `activeModuleView()`, `selectModuleFromUi()`, `setOptionFromUi()`, `runFromUi()`, `currentTargetView()`, `setCurrentTargetAuthorizedFromUi()`, `runningModuleId()` |
| Rendering primitives used by the 5 windows | only `graphics.fill(...)` ×88, `graphics.text(...)` ×74, `font.width(...)` ×33, `font.plainSubstrByWidth(...)` ×9 |
| MC touch-points inside the runtime-module compiler | **4**: `Minecraft.getInstance().execute(...)` ×2, `FabricLoader.getInstance().getAllMods()`, `addCodeSource(Minecraft.class)` / `(FabricLoader.class)` |
| MC touch-points inside `GitLabUpdateService` | 2: `getGameDir()`, `currentVersion()` |

In other words: **`ChampionConsole` is already a headless application core with a UI
face**, and the five `Screen`s are already thin views over that face. The desktop app is a
*second view*, not a second implementation.

Two things are *not* free: `ChatCommandRuntime` (active chat probes need a live
`ClientPacketListener` and the Brigadier tree) and `MinecraftResearchContext` (the
`ModuleContext` snapshot). Both stay in the game process — the desktop app never inspects
the game itself, it asks the game.

---

## 3. Options considered for "external window"

| # | Approach | Verdict |
|---|---|---|
| **A** | Second GLFW window inside the MC process (`GLFW.glfwCreateWindow` + context swap) | **Rejected.** MC 26.3 assumes a single `Window`; `RenderSystem`/`GlStateManager`, texture bindings, `Minecraft.getInstance().getWindow()` and every mixin target are global. Two GL contexts on the render thread means swapping contexts around MC's own frame, plus macOS main-thread rules and fullscreen transitions. High risk, high maintenance, no cheap fallback for users. |
| **B** | In-process Swing/AWT window on the AWT thread | Rejected for now. Cheapest on paper, but GLFW (NSApplication) and AWT both want the macOS app singleton, modal AWT dialogs can wedge the render loop, and a Swing crash takes the game with it. Kept as a later "lite" mode if §3C proves heavy. |
| **C** | **Second JVM: the same JAR launches itself as a Swing desktop app, attached to the game over loopback IPC** | **Chosen.** Real OS windows, zero extra dependencies (Swing + NIO are in the JDK), full crash isolation, works with the game closed (standalone mode), and still exactly one JAR because the launcher *is* the mod JAR. |
| D | Electron/JCEF/web UI | Rejected for "1 JAR": 40–150 MB of natives, extra update/verification surface. |
| E | `jpackage` a native installer | Rejected for 1 JAR; may be revisited for convenience shortcuts only. |

**Decision: C.** One JAR, two entrypoints:

* as a Fabric mod → `ClientModInitializer` (unchanged), which can spawn/attach the desktop app;
* as an application → `java -jar champions-mod-<version>.jar` → `dev.champions.desktop.DesktopMain`.

`DesktopMain` must never touch a Minecraft class. That is enforced by classloading
discipline plus a CI self-test (§8).

---

## 4. Target architecture

```
 ┌──────────────────────────── game process (Java 25, Fabric) ───────────────────────────┐
 │  ChampionsClient  ──keybinds F6/F7/F8, /clickgui, /logs──▶  UiRouter                  │
 │        │                                                     │      │                 │
 │        │ ui.mode=in-game                                     │      │ ui.mode=external│
 │        ▼                                                     │      ▼                 │
 │  ChampionsWorkspaceScreen (unchanged in-game windows)        │  DesktopBridge ───────┼──┐
 │                                                              │   (loopback server,    │  │
 │  ChatCommandRuntime ─┐                                       │    token, 1 client)    │  │
 │  MinecraftResearchContext ─┼─▶ ChampionConsole (core, MC-free)│                       │  │
 │  JavaModuleRuntime ──┘       ├─ ModuleRegistry (17 builtins) │                       │  │
 │                              ├─ AuthorizationPolicy ────────┼──▶ audit.jsonl (only     │  │
 │                              ├─ AuditLog (single writer)     │    the game writes)   │  │
 │                              └─ UpdateService                │                       │  │
 └──────────────────────────────────────────────────────────────┴───────────────────────┼──┘
                                                                                        │
                        newline-delimited JSON over 127.0.0.1:<ephemeral>, token         │
                                                                                        │
 ┌───────────────────────────── desktop process (Java 25, Swing) ────────────────────────▼──┐
 │  DesktopMain → Session (settings, bounds, theme)                                          │
 │      ├─ TerminalWindow   ← model: ConsoleBuffer (same tones, same prompt, same history)    │
 │      ├─ ModulesWindow    ← model: ModuleBrowser (moduleViews/activeModuleView/options)     │
 │      └─ CodeSpaceWindow  ← model: WorkspaceFiles (config/champions-mod/modules/**)         │
 │  reuses: dev.champions.core.* unchanged (ConsoleLine, ModuleOption, AuthorizationPolicy…)  │
 └───────────────────────────────────────────────────────────────────────────────────────────┘

standalone mode: no bridge, no game process — context = last cached/empty snapshot,
ActiveModuleRuntime.disabled(), everything passive still works (registry, codespace,
log viewer, allowlist, audit written by the desktop itself in this mode only).
```

### Module layout after the split

```
src/main/java/dev/champions/
  core/**            (unchanged: console, module, security, log, update, java)
  api/**             (unchanged)
  surface/**         NEW  pure-Java view models shared by in-game and desktop
  desktop/**         NEW  Swing app, IPC client, no MC imports, no Fabric imports
  net/ipc/**         NEW  framing + protocol records (pure Java, used by both sides)
src/client/java/dev/champions/client/
  ui/**              (unchanged in-game screens; only the 5 screens get thin adapters)
  ipc/DesktopBridge  NEW  server side: hands console calls to the render thread
  ipc/DesktopLauncher NEW  finds java 25, spawns the app, passes token/port
```

`surface/` is the key idea: **share the model, not the pixels.** Porting 3 000 lines of
`GuiGraphicsExtractor` drawing to a new toolkit is the expensive, low-value path. A
`ConsoleBuffer` that owns lines, scroll offset, selection, search and input editing is
toolkit-neutral and testable headlessly; `ChampionsConsoleScreen` and the Swing
`TerminalWindow` both render it their own way, so behaviour (tones, wrapping, history,
Ctrl+C, search) stays identical while the look stays native to each toolkit.

---

## 5. The four moving parts

### 5.1 Make `core` truly launchable outside the game (½ day)

1. Move `JavaRuntimeManager`, `JavaModuleRuntime`, `JavaRuntimeSettings`,
   `JavaRuntimeSettings`'s defaults and `GitLabUpdateService` from `src/client/java` into
   `dev.champions.runtime` (main source set), replacing the 6 MC calls with injected
   seams:
   * `Minecraft.getInstance().execute(r)` → `Executor uiExecutor`
   * `FabricLoader.getAllMods()` / `addCodeSource(Minecraft.class)` → `Supplier<List<Path>> classpath`
   * `getGameDir()` / mod version → `Supplier<Path> gameDir`, `Supplier<String> version`
2. Keep the Fabric glue in `src/client` as thin adapters supplying those values.
3. Net effect: no behaviour change, tests stay green, and the exact runtime-Java module
   system (`hash`/`confirm`/`auto`/plug-and-play, 2 MiB / 128 file bounds, `.build`
   exclusion) now works in both hosts.

### 5.2 Desktop app (Swing) — 2 to 3 days

* **Shell.** `JFrame` per window, custom dark title bar via `JRootPane` decorations off,
  own drag/resize (the same `WindowChrome` geometry contract: 22 px × 3 controls,
  minimize/maximize/close, one-based drag handling). Bounds persisted in
  `config/champions-mod/desktop/settings.json`; `alwaysOnTop` toggle; system tray icon
  with "Show terminal / Show modules / Show code space / Detach".
* **Terminal.** `JTextPane` + `StyledDocument` (or a custom `JComponent` painting with
  `FontMetrics` — the current drawing code is already `fill`+`text`, so the port is
  mechanical). Monospaced font, the six `ConsoleLine.Tone` colours, 1 000-line bound
  (same `MAX_LINES`), prompt `champions(module/…) >`, history with ↑/↓, Tab completion
  reusing `CommandTokenizer` logic, Ctrl+C copies selection, Ctrl+L clears view, right-click
  context menu. Poll the bridge at 20 Hz and diff by line index — no core changes needed.
* **Modules (external ClickGUI).** Left tree grouped by the module-id path
  (`auxiliary/scanner/...`), right pane = the `ActiveModuleView` option table rendered from the
  `ModuleOption` record (required / secret / constrained). Constrained options become
  `JComboBox`, secrets become `JPasswordField` with an "armed for one run" badge, and
  `RHOST`/`CONFIRM` get the same treatment they get in-game. Buttons map 1:1 onto
  `selectModuleFromUi`, `setOptionFromUi`, `runFromUi`, `clearSelectionFromUi`,
  `setCurrentTargetAuthorizedFromUi`. Recently-seen players for identity options arrive as
  a pushed `session.players` list from the game process (fallback: free text).
* **Code space (Volt-like).** Left: file tree of `config/champions-mod/modules/**`
  (`JTree`, `.java` + `champion-java.json`), toolbar: New file / New folder / Save /
  **Compile** / **Reload** / **Unload** / **Plug-and-play on/off**. Centre: tabbed editor
  (`JTextArea` + line-number gutter + the 4-space/tab policy the docs already assume).
  Bottom: output pane bound to the same console stream. Compile/Reload call the existing
  `JavaModuleRuntime` through the bridge, so all bounds, hashing, `MODULE_ID`/`OPTION_NAME`
  validation and the `.build` behaviour are unchanged; the editor cannot bypass them.
  Phase-2 polish (optional): syntax highlighting via a `DocumentFilter`-free repaint pass,
  bracket matching, `ChampionJavaContext` completion from a generated signature index.
* **Log viewer.** Not a fourth window: docked panel in the terminal window, plus a
  standalone "Log" frame. It reads `logs/latest.log` through the bridge when attached
  (same `LogTail`/`LogSearch` core classes) and directly from disk when standalone.
* **Toolkit constraints.** No external dependencies (single JAR). Swing ships with
  `java.desktop` in every Mojang/MultiMC JRE. Fonts: bundle nothing, use logical
  monospaced; DPI handled per-monitor by Swing, with a UI-scale override.

### 5.3 IPC bridge — 1.5 days

**Transport.** `ServerSocket` bound to `127.0.0.1` port 0 in the game process. Startup
writes `config/champions-mod/desktop/session.json` (`{"port":…,"pid":…,"token":…,
"started":…}`), mode `0600`, and passes port + token through the child's environment
(`CHAMPIONS_DESKTOP_PORT`, `CHAMPIONS_DESKTOP_TOKEN`) so the token never shows up in a
process listing. The app answers with one `hello` frame containing the token; **no other
frame is honoured before the handshake**, peer address must be loopback, one client at a
time, new client replaces the old only after a valid handshake.

**Framing.** Line-delimited JSON, UTF-8, 8 KiB per frame, `{"t":"<type>","id":<n>,…}`.
Parsing reuses `core/update/MiniJson`; a small `JsonWriter` is added next to it (no
dependency).

| Type | Direction | Payload |
|---|---|---|
| `hello` | app → mod | token, app version, protocol version |
| `hello.ok` / `hello.err` | mod → app | protocol version, mod version, mode (`attached`) |
| `state.push` | mod → app | throttled 1 Hz: `ModuleContext` (redacted), target + authorization state, `runningModuleId`, `moduleViews`, `players` |
| `console.submit` | app → mod | `{line}` — queued to the render thread → `ChampionConsole.submit` |
| `console.lines` | mod → app | `{from, lines:[{tone,text}]}` delta of the 1 000-line buffer |
| `modules.select` / `modules.set` / `modules.run` / `modules.clear` | app → mod | the `*FromUi` façade |
| `authorize.set` | app → mod | `authorize current` / `deauthorize current` |
| `exploit.prompt` | mod → app | `{requestId, module, target, risk}` |
| `exploit.decision` | app → mod | `{requestId, approved}` (60 s timeout ⇒ deny) |
| `java.list` / `java.read` / `java.write` / `java.compile` / `java.reload` / `java.unload` / `java.auto` | app ⇄ mod | code space operations, bounded (2 MiB / 128 files as today) |
| `log.tail` | mod → app | filtered `LogTail` deltas |
| `update.check` / `update.download` | app ⇄ mod | existing `UpdateService` |
| `window.focus` | mod → app | raise/focus the desktop window (best effort, see §11) |
| `bye` | both | graceful detach; the app stays open and shows "detached" |

**Threading.** Every message that touches `ChampionConsole` is dispatched through the
game's main-thread executor; the IPC reader only enqueues. `ModuleContext` is sampled on
the client tick into a volatile snapshot and pushed from the network thread, so the bridge
never touches Minecraft state off-thread.

**Backpressure.** Per-connection bounded outbound queue (1 MiB). When full: coalesce
`state.push`, drop the oldest `console.lines` delta and send one `console.notice`
("N lines dropped"). Inbound is rate-limited (100 msg/s) and oversized frames close the
connection.

**Failure behaviour.** If the game process dies, the app switches to standalone mode and
says so in the status bar. Reconnects are explicit (button), not silent, because the mod
issues a new token on every launch.

### 5.4 Routing, settings, launch — 1 day

`config/champions-mod/desktop/settings.json` (new, separate from `update.properties`):

```json
{ "ui": { "mode": "external", "spawnOnFirstOpen": true, "alwaysOnTop": false },
  "desktop": { "theme": "champions-dark", "uiScale": 1.0,
               "windows": { "terminal": [40,80,980,620], "modules": [1030,80,420,700],
                            "codespace": [40,720,980,520] } } }
```

* `mode: in-game | external | both` (default **external**, since that is the request;
  a first-run dialog explains how to switch back).
* `UiRouter` replaces the four `Runnable`s currently handed to `ChampionConsole`
  (`openClickGui`, `openCategoryGui`, `openLogViewer`, `closeUi`) and the F6/F7/F8 keybind
  handlers. In `external` mode those calls do "ensure app is running → send
  `window.show <id>`". In `both`, they also open the in-game window.
* **Launcher.** Find a Java 25 binary in this order: `ProcessHandle.current().info().command()`
  (the exact JRE the game runs on, guaranteed 25+) → `java.home/bin/java` →
  `JAVA_HOME` → `PATH` (`java -version` parse). Then
  `<java> -jar <this jar> --connect <port> --token <token>`; the JAR path comes from
  `ChampionsClient.class`'s code source. If the mod is running from a dev/`run/` classpath
  it appends the classes dir instead of a jar.
* **AV / corporate policy.** Spawning a child process can be flagged. Mitigations: it is
  opt-in in `settings.json`, the console prints the exact command it will run, and
  `desktop command` prints a copy-pasteable command for users who must start it themselves.
  If a process cannot be spawned, the mod falls back to `mode: in-game` for that session
  and logs why — the game never becomes unusable.
* First run writes `desktop/launch.cmd` and `desktop/launch.sh` (same command, absolute
  paths) so a desktop shortcut is one right-click away.

**As built (0.2.0).** The JSON sketch above became a flat properties file,
`config/champions-mod/desktop.properties`, because it has to be editable by a player
in Notepad and readable by the in-game command parser:

```properties
ui=both              # both | external | in-game
clickgui=clickgui    # clickgui (sorted list) | categorygui (movable panels)
ingameWindows=true   # false = keep the app, drop the in-game windows
spawnOnOpen=true     # false = the game never launches the app by itself
```

Three interchangeable front ends write the same file: the desktop app's **Settings**
tab, the in-game chat command `interfaces both|external|in-game|reload` plus
`interfaces spawn on|off` and `interfaces ingame on|off`, and the text editor. The
core exposes this as `InterfacePreferences` (`dev.champions.core.console`), so the
console command works in both processes and neither depends on the other.

Window geometry lives next door in `desktop-windows.properties` (one `shell=` entry
plus the last tab), written on close and ignored if it would land off-screen.

---

## 6. Parity matrix

| Capability | In-game (kept) | External | Shared code |
|---|---|---|---|
| Terminal output/tones/1 000-line buffer | ✔ | ✔ | `ConsoleBuffer` (new, in `surface`) |
| Prompt, history, Tab completion, Ctrl+C | ✔ | ✔ | `ConsoleBuffer` + `CommandTokenizer` |
| Module tree, search, option editors, dropdowns | ✔ | ✔ | `ModuleBrowser` model + console façade |
| Confirmation arming, explicit run status | ✔ | ✔ | console façade |
| Exploit legal warning | ✔ (screen) | ✔ (dialog) | `ExploitWarningPrompt` interface |
| Code space: tree/editor/tabs | ✔ Java Studio | ✔ | `WorkspaceFiles` model |
| Compile/reload/unload/hash-confirm | ✔ | ✔ | `JavaRuntimeManager` (relocated) |
| Log viewer with search/level filter | ✔ | ✔ | `LogTail`, `LogSearch` |
| Update check/download | ✔ | ✔ | `UpdateService` (relocated) |
| Authorization allowlist + audit | ✔ | ✔ | `AuthorizationPolicy`, `AuditLog` |
| Active chat probes | ✔ | ✔ (via attached game) | `ActiveModuleRuntime` |
| Passive modules | ✔ | ✔ (also when detached) | `ModuleRegistry` |
| Command surface enumeration | ✔ | ✔ (attached only) | `ModuleContext` snapshot |

---

## 7. Security and gating — the part that must not regress

1. **No new bypass.** The desktop app has no direct access to the registry; every action is
   the same `ChampionConsole` call the in-game UI makes. `CONFIRM`, the exact-host
   allowlist, and the one-run consumption of the phrase all happen in the game process.
2. **Audit single-writer.** Only the game process appends `audit.jsonl` while attached. The
   desktop-side actions are audited as `event:"module_run" … detail:"via desktop UI"`
   (plus `ui_attach`, `ui_detach`). In standalone mode the desktop becomes the writer;
   `AuditLog` gains an inter-process lock (append a `pid` field + `FileChannel.lock`) so two
   writers can never interleave records — worth doing regardless.
3. **Exploit warning stays per-run.** Requested over IPC, default-deny after 60 s, answer
   audited (`acknowledged` / `cancelled`), stale requests rejected by `requestId`, and the
   existing "module changed while the dialog was open" guard is preserved by re-checking
   `activeModule` in `approveExploitRun` afterwards.
4. **IPC hardening.** Loopback bind + peer check, 256-bit token via env (not argv, not
   world-readable file), constant-time compare, handshake-before-anything, single client,
   frame/size/rate caps, no arbitrary command execution in the message set (the only
   "execute" verbs are the bounded `java.*` module operations that already exist), and the
   session file is deleted on shutdown.
5. **Code space is not a new privilege.** It edits the folder the runtime already compiles;
   the 2 MiB / 128-file / symlink / `.build` bounds and the "unrestricted Java, not a
   sandbox" warning are unchanged, and the warning is shown in the app the first time a
   compile succeeds.
6. **Detached mode is deliberately weaker.** No connection ⇒ `ActiveModuleRuntime.disabled()`
   and `authorize` impossible (`"No active client connection"`), so AGGRESSIVE probes simply
   cannot run. Passive/local modules still work — same as running the mod offline today.

---

## 8. Packaging: one JAR, two entrypoints

* All new code goes into the existing `main` source set; the mod JAR already contains
  `src/main` + `src/client` classes. Desktop classes are inert for Fabric.
* `build.gradle`: `jar { manifest { attributes('Main-Class': 'dev.champions.desktop.DesktopMain') } }`.
  The existing reproducible-jar `doLast` hook only rewrites
  `Fabric-Loom-Client-Only-Entries`; it must preserve (and not sort) the new attribute —
  add it to the preserved-set test.
* `fabric.mod.json` gains nothing; Fabric ignores `Main-Class`.
* **CI gate (important):** after packaging, run
  `java -jar build/libs/champions-mod-<version>.jar --self-test --headless`. It boots
  `DesktopMain`, initialises config, runs a scripted console session against a fake
  `ModuleContext`, exercises the `surface` models and the JSON codec, and exits non-zero if
  any `net.minecraft.*` / `net.fabricmc.*` class was loaded (checked with
  `ClassLoader.getSystemClassLoader()` + a boot-strapping `Throwable` stack probe). This
  single test protects the whole "works outside Minecraft" property forever.
* Sources JAR keeps excluding `fabric.mod.json`; docs get a `docs/desktop-app.md`.
* Release assets unchanged (`champions-mod-x.jar`, `-sources.jar`, `SHA256SUMS.txt`), so
  the existing updater keeps working. `update download` now also means "the app you
  already run".

---

## 9. Repo change list

```
NEW  src/main/java/dev/champions/net/ipc/Frame.java            REQ/RESP records, codec
NEW  src/main/java/dev/champions/net/ipc/JsonWriter.java       minimal writer
NEW  src/main/java/dev/champions/surface/ConsoleBuffer.java    lines, scroll, selection, search, input
NEW  src/main/java/dev/champions/surface/ModuleBrowser.java    tree, filter, selection, option state
NEW  src/main/java/dev/champions/surface/WorkspaceFiles.java   tree, read/write, bounds checks
NEW  src/main/java/dev/champions/desktop/DesktopMain.java      entrypoint, --self-test
NEW  src/main/java/dev/champions/desktop/{TerminalWindow,ModulesWindow,CodeSpaceWindow,LogPanel}.java
NEW  src/main/java/dev/champions/desktop/{Session,IpcClient,Theme,WindowChromeSwing}.java
NEW  src/main/java/dev/champions/runtime/{JavaRuntimeManager,JavaModuleRuntime,GitLabUpdateService}.java  (moved)
NEW  src/client/java/dev/champions/client/ipc/{DesktopBridge,DesktopLauncher}.java
NEW  src/client/java/dev/champions/client/UiRouter.java
EDIT ChampionConsole           no API change; add ui-via-desktop audit detail + optional listener hook
EDIT AuditLog                  inter-process lock + pid field
EDIT ChampionsClient           keybinds → UiRouter; construct DesktopBridge
EDIT ChampionsScreens          unchanged; still used by mode=in-game
EDIT build.gradle              Main-Class, self-test CI step, preserved attributes
EDIT .gitlab-ci.yml            `java -jar … --self-test` gate, jar size sanity check
NEW  docs/desktop-app.md, docs/ipc-protocol.md
```

Nothing under `src/client/java/dev/champions/client/ui/**` has to change for the external
mode to work — the in-game screens keep running untouched, which is also the rollback path.

---

## 10. Phases and acceptance criteria

| Phase | Work | Effort | Done when |
|---|---|---|---|
| **0** | Move runtime/update classes to `dev.champions.runtime`, inject executor/classpath/gameDir/version; AuditLog locking | ½ d | `gradlew build` green, all 25 test classes pass, in-game behaviour unchanged |
| **1** | `surface/` models: `ConsoleBuffer`, `ModuleBrowser`, `WorkspaceFiles` + headless unit tests | 1 d | terminal editing/search/scroll and option validation behave identically in tests for both hosts |
| **2** | `AppCore` skeleton: `DesktopMain`, config, theme, window chrome, terminal window wired to a local console (no game) | 1–1.5 d | `java -jar` opens a working terminal; `help`, `show modules`, `use`, `set`, `run` (passive) work with no Minecraft running |
| **3** | IPC: codec, handshake, server, client, thread hand-off, backpressure, `state.push` | 1.5 d | game attached → `status` shows live target/player; AGGRESSIVE probe runs from the desktop; detach/reconnect clean |
| **4** | Modules window (external ClickGUI) + exploit-warning routing + `authorize` button | 1 d | a full gated run (allowlist → CONFIRM → warning → probes → audit) executed from the desktop only |
| **5** | Code space: tree, tabs, editor, compile/reload/unload/auto, output pane | 1.5 d | drop a `.java` in the folder from the app, compile, run a passive module, see output in both hosts |
| **6** | Routing (`ui.mode`), launcher discovery, first-run dialog, launch scripts, log panel | 1 d | F8 with the app closed spawns it and focuses the terminal; failure falls back to in-game with a logged reason |
| **7** | CI self-test, docs, parity pass, polish (always-on-top, tray, UI scale, fonts) | 1 d | `--self-test` in CI proves no MC class loads; docs complete |

**MVP = phases 0–4 (~3.5–4 days):** external terminal + external ClickGUI attached to a
running game. Everything after that is additive and individually shippable.

---

## 11. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Antivirus / corporate policy dislikes the mod spawning a JVM | Opt-in, printed command, `desktop command` for manual start, instant fallback to in-game |
| Launcher JRE lacks `jdk.compiler` (code space compile) | Detected already; message points at "launch with JDK 25". Desktop app shows the same, and offers "compile with system JDK" (spawn `javac` from a found JDK) as a phase-7 nicety |
| JRE without `java.desktop` (custom trimmed runtime) | Detect `GraphicsEnvironment.isHeadless()`/missing class and fall back to in-game with a clear log line |
| Window focus stealing is restricted on Linux/macOS (F8 while MC is focused) | Best effort only: `toFront()` + `setAlwaysOnTop(true)` briefly on Windows; otherwise a tray notification "terminal opened" and the app stays in the taskbar. Documented, not pretended |
| Two processes writing config | Audit log becomes single-writer + locked; every other config file is owned by exactly one side (`desktop/settings.json` = app, `authorized-targets.txt`/`update.properties` = game) |
| Behaviour drift between in-game and desktop views | `surface/` models are the single source of behaviour and are unit-tested headlessly; the in-game screens are progressively re-pointed at the same models |
| Jar size / startup cost | ~120 new classes, no new dependencies; app cold start target < 1.5 s |
| Users on `mode=external` who actually want the old UI | Keybind/command still opens in-game windows on `mode=both`, first-run dialog explains it, `settings.json` is one line to flip |

---

## 12. Open questions (need a call before phase 2)

1. **Default mode** — `external` (as asked) with a first-run explainer, or `both` for one
   release so nothing surprises existing users?
2. **Keep the in-game screens?** The plan keeps them (rollback + no display). Confirm they
   are not to be deleted.
3. **Standalone (game closed) mode** — planned as an automatic capability. Ship it in
   `0.2.0` or hold it for `0.2.1`?
4. **Code space scope** — file-tree + editor + compile/reload only, or also
   `ChampionJavaContext` autocomplete and a docs pane in the first pass?
5. **Distinct app name/icon** in the taskbar (`ChampionsCode Desktop`) versus matching the
   mod exactly.
6. **Terminal feature parity checklist** — is `search in the terminal` (currently only in
   the log window) in scope for the external terminal? It is cheap once `ConsoleBuffer`
   owns the text.

---

## 13. Appendix — first frames on the wire

```jsonc
// app → mod
{"t":"hello","id":1,"proto":1,"token":"9f2c…","app":"0.2.0"}
// mod → app
{"t":"hello.ok","id":1,"proto":1,"mod":"0.2.0","mode":"attached"}
// mod → app (1 Hz)
{"t":"state.push","connected":true,"target":"mc.example.net","authorized":true,
 "running":"","active":"auxiliary/scanner/chat_plugin_enumerator",
 "modules":[{"id":"auxiliary/scanner/server_ping","name":"…","exploit":false,"selected":false}],
 "players":["Notch","jeb_"]}
// app → mod
{"t":"console.submit","id":2,"line":"use auxiliary/scanner/server_ping"}
// mod → app
{"t":"console.lines","from":812,"lines":[{"tone":"ACCENT","text":"champions(auxiliary/scanner/server_ping) > use auxiliary/scanner/server_ping"}]}
// mod → app (a run that needs the legal warning)
{"t":"exploit.prompt","id":9,"requestId":"7c1","module":"auxiliary/exploits/example_preflight","target":"lab.local","risk":"…"}
// app → mod
{"t":"exploit.decision","requestId":"7c1","approved":true}
```
