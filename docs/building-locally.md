# Building and verifying locally (Windows / PowerShell)

The GitLab job failed with **"No more compute minutes available"** — the runner never
started, so that was a quota problem, not a build failure. Until minutes are back, your
PC is the verifier. This is the exact sequence.

---

## 0. One-time check: the JDK

Loom 1.17 + Minecraft 26.3 need **JDK 25**, both for the Gradle daemon and for the mod.

```powershell
java -version
# openjdk version "25..." is what you want.
# If it prints 21 or 17: point JAVA_HOME at a JDK 25 install before running Gradle.
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.x-hotspot"
```

`build.gradle` compiles with `--release 25`, so an older daemon JVM fails immediately
with `invalid target release: 25`.

---

## 1. Clean build (compiles `src/main` **and** `src/client`, runs the JUnit suite)

```powershell
cd D:\CodeWorkspaceNProjects\z-workspace\IDI\ChampionsCode
.\gradlew.bat clean build --stacktrace
```

This is the job that would have run in CI. It is the first real compile of the new
Fabric-side adapter (`src/client/java/dev/champions/client/desktop/*`,
`ChampionsClient`, `build.gradle`).

Success looks like:

```
BUILD SUCCESSFUL
```

and `build\libs\` contains `champions-mod-<version>.jar` plus the `-sources.jar`.

---

## 2. Desktop self-test (the new gate)

```powershell
.\gradlew.bat desktopSelfTest
```

It runs the *packaged JAR* as an application (no Minecraft on the classpath) and prints:

```
Self-testing champions-mod-0.1.0-beta.4.jar
ChampionsCode desktop self-test 0.2.0-desktop
  ok   buffer keeps the 1000-line bound
  ...
  ok   drop-in compiler: unload removes it
SELF-TEST PASSED
```

33 checks. If this passes, the external half — terminal model, wire codec, gated console
session, version gate, drop-in compiler, and "no Minecraft class is loadable" — is
verified on your machine.

You can also run it by hand against the exact file users download:

```powershell
java -jar .\build\libs\champions-mod-0.1.0-beta.4.jar --self-test
```

---

## 3. Look at the app (no Minecraft needed)

```powershell
java -jar .\build\libs\champions-mod-0.1.0-beta.4.jar --demo
```

Three windows open: **Terminal**, **Modules**, **Code space**, running a scripted session
against the simulated target `lab.demo.invalid`. That target can never resolve, so nothing
leaves the machine. Try:

* `help`, `show modules`, `use auxiliary/audit/plugin_surface`, `show options`
* `set CONFIRM I_HAVE_AUTHORIZATION` then `run` → authorization gate denies first
* `authorize current` → run again → it completes; run a third time → denied again
  (`CONFIRM` is consumed per run, even on denial)
* Code space: **New module** → the template appears → **Save** → **Compile & load** →
  the module shows up in the Modules window
* Terminal keys: `↑`/`↓` history, `Ctrl+C` copy selection, `Ctrl+L` clear view,
  `PageUp`/`PageDown`/`Home`/`End` scroll

Standalone (no `--demo`) is the normal "game closed" mode: passive modules work, and
`authorize current` refuses because there is no connection — by design.

---

## 4. Smoke-test the in-game path (the part I could not compile here)

```powershell
.\gradlew.bat runClient
```

In the dev client:

1. Press **F8**. Expected: the in-game workspace opens **and** the desktop app starts
   and attaches (default is `ui=both`).
2. In the desktop terminal type `status`. Expected: `ATTACHED … AUTHORIZED? … target
   <your server>` and a `capabilities= mc 26.3 · mod … · target mc26.3 · …` line.
3. In the Modules window: pick `auxiliary/audit/connection_posture`, **Authorize**,
   set `CONFIRM`, **Run** — output must appear in both the desktop terminal and the
   in-game console.
4. Close the app, press F8 again → it respawns and re-attaches.
5. Switch to `ui=in-game` in `run\config\champions-mod\desktop.properties` and restart:
   F8 must behave exactly like 0.1.x (no external process).

Logs if something misbehaves:

| What | Where |
|---|---|
| Game log | `run\logs\latest.log` |
| Desktop app stdout/stderr | `run\config\champions-mod\desktop.log` |
| Bridge session (port/token) | `run\config\champions-mod\desktop-session.json` |
| Audit trail | `run\config\champions-mod\audit.jsonl` |

---

## 4b. If it looks blurry or pixelated

Fixed in `0.2.0-desktop`, but for reference: a Swing window is stretched by Windows
when the JVM is not DPI-aware, and `Font.MONOSPACED` falls back to Courier New. Both
are handled now — the app sets `awt.useSystemAAFontSettings=lcd`,
`sun.java2d.dpiaware=true` and `sun.java2d.uiScale.enabled=true` before loading AWT,
and picks the best installed monospace family.

If it still looks wrong on your setup:

```powershell
java -jar .\build\libs\champions-mod-*.jar --demo --ui-scale 1.25
```

* `--ui-scale 1` — window untouched by Windows scaling (text smaller, always sharp)
* `--ui-scale 1.25` / `1.5` / `2` — match your Windows display scaling exactly
* default `auto` — the JVM's per-monitor detection (correct on most setups)

The game-side launcher already passes the rendering flags, so an app spawned by the
mod inherits them.

---

## 5. If the build fails

The two spots I could not verify in my sandbox are `src/client/**` and the Groovy blocks in
`build.gradle`. Paste the first `error:` block and I will fix it in the workspace and push
to `main` again.

Known suspects, in order:

1. **`ChampionsClient.java`** — the `DesktopRuntime` wiring (unused imports are harmless;
   the constructor arguments and the `desktop.show(id, fallback)` lambdas are the risk).
2. **`build.gradle`** — `tasks.matching { … }.configureEach { archiveTask.manifest { … } }`
   and the `desktopSelfTest` `doFirst` block (Gradle 9.5.1).
3. **`DesktopBridge`** ↔ `ChampionConsole` façade — if a `*FromUi` signature differs, the
   error names it; the fix is in `main`, not in the client.
4. **Java 25 vs 21** — `invalid target release`.

---

## 6. About CI minutes

Every push to `main` will keep failing at `build` with *"No more compute minutes
available"* until the namespace has minutes again (Settings → Billing, or enable a
self-hosted runner). Until then:

* the two commands above are the equivalent of the pipeline;
* do **not** push a `v*` tag — the `release` job would also be skipped for quota, leaving a
  tag with no release assets, and tags are immutable in this project.

Optional: a self-hosted runner on this machine turns the pipeline back on for free — the
job only needs `eclipse-temurin:25-jdk`, `python3`, `curl`, which run fine in WSL/Docker
Desktop on Windows.
