# Changelog

## 0.1.0-alpha.8 - 2026-09-19

- Replaced ChampionScript and the Volt interpreter with real runtime Java source
  compilation backed by the standard JDK 25 compiler.
- Added multi-file source sets, `champion-java.json`, and the
  `ChampionRuntimeAddon` start/stop entrypoint contract.
- Added persistent addon lifecycle management with explicit compile, start,
  reload, stop, diagnostics, client-thread scheduling, and cleanup callbacks.
- Added manual developer-mode opt-in and exact source-set SHA-256 confirmation;
  any source or manifest change invalidates the confirmation.
- Added deterministic source hashing, manifest validation, compiler tests, an
  example Java addon, audit events, and detailed unrestricted-code warnings.
- Replaced the Volt GUI tab with a Java developer tab.

## 0.1.0-alpha.7 - 2026-09-19

- Removed the Unicode em dash character from the current source tree,
  documentation, UI messages, and packaged release artifacts.
- Replaced the generic CI workflow with an explicit Fabric Minecraft 26.3 build.
- CI now validates the Gradle wrapper, Java 25, configured Minecraft/Fabric
  versions, tests, packaged Fabric metadata, client mixin presence, and checksums.
- GitHub Actions uploads the binary JAR, source JAR, and SHA-256 manifest for
  every main-branch push, pull request, and manual workflow run.
- Removed historical binary JARs from the tracked release directory so only the
  current verified build is published with the source tree.

## 0.1.0-alpha.6 - 2026-09-19

- Added distinct **Console** and **Volt** tabs to the F8 GUI.
- The Volt tab accepts ChampionScript statements directly and supports concise
  `help`, `list`, `show`, `run`, and `eval` script-management commands.
- Added a complete ChampionScript syntax, runtime-value, and budget reference.
- Preserved the runtime security boundary: no JVM/native injection, reflection,
  network or packet access, process execution, or game mutation.
- Added GitHub-ready source packaging and release verification.

## 0.1.0-alpha.5 - 2026-09-19

- Added the payload-free **Transaction Echo** observer for coarse menu-action to
  menu-update class correlation and response timing.
- Reviewed AUTISM Client, wakDuper, UI-Utils-Reborn variants, Doujin Dupe, and
  Dupe-Utils as clean-room research inputs.
- Explicitly excluded packet cancellation, delayed flush queues, fabricated
  clicks, retained GUIs, reconnect/race automation, and exploit-specific logic.
- Added transaction-flow tests and expanded the clean-room/license record.

## 0.1.0-alpha.4 - 2026-09-19

- Added the payload-free **Wire Lens** packet-flow module.
- Added the bounded, non-reinjecting **Session Trace** metadata timeline.
- Added the read-only **Container Integrity** synchronization/component audit.
- Added the observe-only **Client Resilience** burst/error/custom-payload audit.
- Added passive, non-verdict **Motion Evidence** remote-player telemetry.
- Added a 512-entry packet metadata ring, connection-error counter, inventory snapshots, and remote motion models.
- Added a client `Connection` mixin that records packet class/direction/timing only - never packet bodies.
- Added clean-room research notes and telemetry module tests.

## 0.1.0-alpha.3 - 2026-09-19

- Added `MODE=silent|aggressive` to the chat plugin enumerator (`agresive` alias accepted).
- SILENT now filters default commands and marks non-default candidates without sending anything.
- AGGRESSIVE performs sequential, rate-limited blank/version/v probes only on eligible advertised paths.
- Added live Brigadier-tree revalidation, hazardous/default command filtering, blank-command side-effect protection, and bounded per-probe capture.
- Added version-token extraction and grouped probe transcripts.
- Added command descriptor snapshots and safety-policy/mode tests.

## 0.1.0-alpha.2 - 2026-09-19

- Adopted the supplied Champions Code logo for the mod icon and branding.
- Added `auxiliary/scanner/chat_plugin_enumerator`.
- Added a capability-limited active runtime that can send exactly `/plugins` once.
- Added bounded server game-message capture and Bukkit-style plugin-list parsing.
- Added active-run lifecycle, busy-state protection, auditing, documentation, and parser tests.

## 0.1.0-alpha.1 - 2026-09-19

- Created the Minecraft 26.3 Fabric client project.
- Added the Champions research console and F8 key mapping.
- Added passive fingerprint, connection posture, and plugin-surface modules.
- Added exact-host authorization, one-run confirmation, and JSONL audit logs.
- Added the bounded, read-only ChampionScript runtime and demo script.
- Added unit tests, CI configuration, icon, documentation, and MIT license.
