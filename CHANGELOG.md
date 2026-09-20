# Changelog

## 0.1.0-alpha.15 - 2026-09-20

- Added an automatic GitHub prerelease job after successful pushes to `main`.
- The workflow reads and validates the prerelease version from
  `gradle.properties`, creates a `v<version>` tag, and marks the release as a
  prerelease.
- The release uploads the verified binary JAR, source JAR, and SHA-256 manifest.
- Pull requests and manual workflow runs continue to build without publishing.
- Added immutable-tag protection: re-running the same commit is supported, while
  reusing a version tag for a different commit requires a version bump.
- Limited write permission to the prerelease job; the build job remains
  read-only.

## 0.1.0-alpha.14 - 2026-09-20

- Added a dedicated Exploits workspace to the terminal, a ClickGUI Exploits
  filter, and an `exploits` category window.
- Added a modal legal and operational risk warning when entering an Exploits
  interface and a fresh mandatory popup before every exploit-research run.
- Added centralized per-run warning acknowledgement, cancellation, selection
  revalidation, exact-target authorization, and audit events.
- Added `ExploitResearchModule` and a non-executing research workspace module
  for engagement ID, legal basis, scope, expected impact, and stop conditions.
- Fixed dragging throughout the terminal, ClickGUI, category panels, and settings
  window by using Minecraft 26.3's one-based primary and secondary mouse-button
  values instead of the older zero-based assumptions.
- Prevented default and restored window sizes from consuming the complete
  viewport, preserving visible movement space at common GUI scales.
- Added tests for fresh per-run exploit warning acknowledgement and Minecraft
  26.3 pointer-button values.

## 0.1.0-alpha.13 - 2026-09-20

- Retrieved and inventoried the older source archives and loose Java modules,
  including 108 distinct source-archive module wrapper names across revisions.
- Added Plugin Risk Review, a passive mapping from advertised command roots to
  non-conclusive defensive hardening profiles.
- Added Declared Dependency Posture for bounded administrator-supplied
  `name@version` inventory checks without file or network access.
- Added defensive Chat Normalization without bypass generation or resend logic.
- Added Chat Game Fairness classification without solving, monitoring live chat,
  or sending answers.
- Documented duplicate, adapted, and excluded legacy modules, provenance limits,
  and the clean-room boundary.
- Excluded command abuse, credential theft, chat-filter bypass, automatic game
  answers, malicious configuration generation, SQLi, SSRF, deserialization,
  path traversal, griefing, destructive operations, and RCE delivery.

## 0.1.0-alpha.12 - 2026-09-20

- Added an original multi-window category interface inspired by the general
  layout concept of classic utility-client module menus.
- Added movable, collapsible, width-resizable Audit, Observe, Replay, Scanner,
  and Champions control windows with remembered layouts and z-order.
- Added a movable and resizable settings window with direct option editing,
  authorization arming, module execution, scrolling, and live run status.
- Added the `categorygui` command with `windowgui` and `panelgui` aliases while
  keeping the existing sorted-list `clickgui` interface.
- Added in-GUI navigation between the terminal, list GUI, category GUI, and
  current-target authorization control.

## 0.1.0-alpha.11 - 2026-09-20

- Added `authorize current` and `deauthorize current` for persistent in-game
  management of the connected exact host.
- Added an **Authorize current** / **Remove target** toggle and current-target
  authorization state to the ClickGUI.
- Kept the exact-host authorization gate and one-run confirmation while removing
  the need to edit or reload the target text file manually.
- Replaced filesystem-path denial output with concise in-game instructions.

## 0.1.0-alpha.10 - 2026-09-20

- Fixed terminal and ClickGUI title-bar dragging by explicitly maintaining the
  Minecraft drag state.
- Added bottom-right resize handles and remembered window dimensions to both
  interfaces.
- Added mouse text selection, selection highlighting, Ctrl+C support, and a
  Copy button to the terminal output.
- Sorted ClickGUI modules by category and identifier and labeled each row with
  its category.
- Added persistent ClickGUI idle/running state and visible run-result feedback.
- Shortened non-allowlisted target denials so local filesystem paths are no
  longer printed in the terminal or ClickGUI.

## 0.1.0-alpha.9 - 2026-09-20

- Changed the F8 terminal from a full-screen overlay into a movable floating
  window with a draggable title bar and remembered position.
- Added the `clickgui` command and a movable module browser with module and
  option lists, direct option editing, confirmation arming, run controls, and
  terminal navigation.
- Replaced the oversized default help dump with a concise quick-start view;
  full console and runtime Java references remain available through
  `help console` and `help java`.

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
