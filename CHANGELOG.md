# Changelog

## [Unreleased]

### Added
- **Plug-and-play module folder.** `.java` files below
  `config/champions-mod/modules/` are now compiled and registered automatically,
  so a drop-in module needs no hash confirmation, no `java modules reload`, and no
  console command at all. The folder is scanned at client start and then watched
  while the game runs: a file that is added, edited, renamed, or deleted reloads
  or unregisters the group within about two seconds, and reloads never interrupt a
  module that is mid-run. Set `plugAndPlay=false` in
  `config/champions-mod/java-runtime.properties` to restore the previous
  review-and-confirm workflow, or `autoReload=false` to load once at startup
  without watching. New console commands: `java modules folder`,
  `java modules auto [on|off|now|watch on|off]`, and an expanded `java modules
  status`. Changes are reported by source hash, counted in `java modules auto`,
  and recorded in `audit.jsonl` with `auto=true`; `java modules unload` stays
  respected until the files change.
- Recursive drop-folder hardening: module sources are recognised at any depth,
  dot-files and dot-directories (including the generated `.build/` folder, editor
  scratch files such as `.Module.java.swp`, and `Module.java~` backups) are
  ignored everywhere, and a short `README.txt` explaining the rules is written
  into the folder the first time it is created.
- Change detection that stays quiet: a metadata fingerprint of the folder is
  polled once a second, a change is only acted on after a short settled period so
  a staged file save is one reload, and a folder state that failed to compile is
  left alone until the files change so compiler errors print once instead of every
  second.
- Tests for the drop-folder workflow: quiet-period and double-report behaviour,
  ignoring generated and non-module files, reporting an emptied folder, manual
  actions surviving a poll, and an end-to-end drop/edit/delete cycle that compiles
  and discovers a nested module without any registration step.
- Game log window: **F6** (`logs` in the terminal, or the Java Studio sidebar)
  opens a live tail of the running game's `logs/latest.log` as another workspace
  window. It follows the end of the log until you scroll away from the bottom,
  wraps long lines, numbers and colors them by level, and searches as you type:
  every match is highlighted, Enter/Shift+Enter and the Prev/Next chips walk the
  matches with a `[n/total]` counter, `Aa` toggles case sensitivity, `Only`
  filters to matching lines, and `Level` cycles an ALL/INFO/WARN/ERROR minimum
  filter that keeps stack-trace continuation lines visible. Click or drag to
  select lines; Ctrl+C copies the selection, the matches, or the whole buffer.
  Reads are incremental and bounded (newest 4 000 lines, one short poll per
  400 ms), survive log rotation, and the window keeps its geometry like the
  others.

### Changed
- Public project links now point at the International Duck Incorporated
  website: the README install and update sections and `PUBLISHING.md` link
  <https://idi-official.pages.dev/projects/championscode>, and the clickable
  startup update notice opens that page instead of the GitLab release list.
  `fabric.mod.json` gained a `contact` block (`homepage` on the website,
  `sources` and `issues` on GitLab).
- The update system itself is unchanged and still reads the GitLab releases
  feed and downloads JARs and `SHA256SUMS.txt` from GitLab: the website hosts
  no release assets and no update feed. `GitLabUpdateService` keeps its
  gitlab.com-only URL allowlist, and the F8 `update check` command still
  prints the release's own page URL from the feed.

## 0.1.0-beta.3 - 2026-09-26

Re-release of the 0.1.0-beta.2 changes: the beta.2 tag pipeline built and
tested cleanly, but the release job failed to attach its assets (the CLI
file upload it used is not permitted for CI job tokens), so the release
page shipped empty. This release publishes through the project's generic
package registry with release-cli asset links instead, restoring the
`downloads/champions-mod-<version>.jar` URLs the in-game updater matches.
The mod content is identical to 0.1.0-beta.2; see that section below for
the full notes.

## 0.1.0-beta.2 - 2026-09-26

### Added
- Windowed workspace: the terminal, ClickGUI, category GUI, and Java Studio are
  now independent windows of one shared desktop instead of replacing each
  other. Open several at once, drag each by its title bar, and use the per-window
  close controls. F7/F8 toggle their window while the workspace is open; ESC or
  closing the last window closes everything. Navigation buttons ("Terminal",
  "List GUI", "ClickGUI") open or focus the matching window instead of swapping
  screens.
- Dropdowns for fixed-choice options: modules declare allowed values (the chat
  plugin enumerator's MODE is SILENT/AGGRESSIVE), the console's `set` validates
  them case-insensitively (storing the canonical spelling), and the ClickGUI
  renders them as dropdowns instead of free-text fields.
- Player dropdown and option-layout fixes in the ClickGUI: dropdowns now stay on
  screen (clamped horizontally, flipped above the field when the list would run
  past the bottom), the dropdown's UUID detection matches the option name only
  (fixing UUIDs being inserted into username fields), option labels and
  descriptions clip to the label column, and narrow windows switch to a stacked
  label/description/editor layout.
- `auxiliary/lookup/player_identity` auto-routing: a UUID pasted into USERNAME
  (or a username into UUID) is detected and resolved instead of failing
  validation.
- F8 terminal `exit` command (aliases: `close`, `quit`) that closes the terminal
  screen, alongside the existing window close control.
- ClickGUI module search box: type to filter the module list by id or name.
- ClickGUI players dropdown for options that take a player identity (name or
  description mentions player/username/UUID/nickname): lists every player seen
  during the session, including players who left, with online players
  highlighted. For UUID options the dropdown inserts the player's dashed UUID.
  The registry is built passively from the client's own player list and never
  sends anything.
- ARMED identification box on the main menu showing the ChampionsCode brand,
  status, and installed version.
- More logging: module registration (builtin and drop-in), console commands
  (secrets redacted), module run start/finish with duration, authorization
  denials, and update-check outcomes. Secrets and confirmation phrases are
  never logged.
- `docs/writing-your-own-modules.md`: a hands-on cookbook for writing your own
  modules, with a complete worked drop-in example, the builtin-module route,
  the runtime-Java route, a testing pattern, and the guardrails that always
  apply.
- `auxiliary/scanner/server_ping` module: opens one standard server-list
  status connection to the currently authorized, allowlisted server and
  reports what any unauthenticated pinger observes without joining (protocol,
  MOTD, player counts and sample, chat-security flags, mod-platform
  disclosure). Single target only; no ranges or third-party hosts.
- `auxiliary/lookup/player_identity` module: resolves one username or UUID
  against Mojang's public profile API (current name, dashed UUID, skin/cape
  textures). Contacts only Mojang endpoints, never the connected server.
- Startup update checks against the project's public GitLab releases feed with
  a clickable in-game notice, disableable via `checkOnStart=false` in
  `config/champions-mod/update.properties`. The check only contacts
  `gitlab.com`, never the connected server session, and is skipped in
  development environments.
- F8 console `update check` and `update download <version>` commands.
  Downloads fetch the latest release JAR into the `mods` folder only after
  verifying its SHA-256 against the release's `SHA256SUMS.txt` and never
  replace a loaded JAR; update checks and downloads are recorded in the local
  audit log.
- `.gitlab-ci.yml`: a GitLab CI pipeline that builds, tests, and verifies the
  mod on `main` and merge requests, and publishes a full GitLab Release
  (binary JAR, source JAR, `SHA256SUMS.txt`) with changelog-derived notes
  when a matching `v<version>` tag is pushed.

### Changed
- Fixed the terminal input-line offset: the typed text now sits exactly on the
  `champions@minecraft:~$` prompt line (borderless Minecraft 26.3 EditBox
  renders text at the box top, so the box now starts on the prompt line).
- The terminal, ClickGUI, category GUI, and Java Studio no longer dim the world
  behind them while open. The legal-risk warning popup keeps its dimming to
  stay unmistakable.
- Clean builds are now bit-for-bit reproducible: the built JAR is repacked
  once with the order of the `Fabric-Loom-Client-Only-Entries` manifest
  attribute sorted; everything else (entry order, timestamps, compression) is
  preserved.
- The sources JAR no longer contains `fabric.mod.json`, so loaders ignore it
  if it is dropped into a mods folder by mistake.
- README install and CI sections now point at GitLab Releases and the GitLab
  CI pipeline, with version-neutral release file names.
- Rewrote `PUBLISHING.md` around the tag-based GitLab release flow.

### Removed
- The committed `release/` artifact folder; GitLab Release assets are the
  single source of published binaries, and a local `release/` folder is now
  gitignored.
- The `.github/workflows/build.yml` GitHub Actions workflow, superseded by
  the GitLab CI pipeline.

## 0.1.0-beta.1 - 2026-09-25

### Added
- Plug-and-play `exploit` and `payload` drop-in folders under
  `src/client/java/com/internationalduck/championscode/module/`, with
  package-level conventions in each `package-info.java`.
- `ExampleExploitModule` and `ExamplePayloadModule`: fully commented example
  files in the two folders showing the complete plug-and-play path (module
  contract, options, findings, registration).
- `ChampionConsole.registerModule(...)` hook so client-side drop-in modules
  can register from the `ChampionsClient` entrypoint, which the core
  `ModuleRegistry` cannot reach across the split source sets.
- `docs/exploit-and-payload-files.md` describing what belongs in each folder,
  the plug-and-play rules, and how registered files surface in the console
  and GUIs.
- README coverage for the drop-in folders: the two example modules in the
  module table, a dedicated section on the folders and registration path, and
  the refreshed Exploits workspace module list.

### Changed
- Migrated the official ChampionsCode repository from GitHub to GitLab.
- Updated ChampionsCode-owned repository, release, issue, and clone links to GitLab.
- Retained external project links to their original upstream repositories.
- Added the project branding asset to the repository.
- Moved the rate-limit, boundary-validation, and custom-payload security test
  modules from `src/main/java` into the new client-side `exploit` and
  `payload` folders so their client-only imports resolve under the split
  source sets.

### Fixed
- Completed the three security test modules from the previous commit (missing
  closing braces) and updated them from Yarn names (`MinecraftClient`,
  `PlayerMoveC2SPacket`, `PacketByteBuf`) to the project's 26.3 mappings
  (`Minecraft`, `ServerboundMovePlayerPacket`, `FriendlyByteBuf`), so both
  source sets compile and the JUnit suite passes.
# Changelog

## 0.1.0-alpha.16 - 2026-09-20

- Removed the Java and Exploits tabs from the F8 terminal. The warning-gated
  Exploits filter remains available in the sorted ClickGUI, and exploit modules
  remain available in the category GUI.
- Added a dedicated F7 ChampionsCode Java Studio with a purple IDE-style layout,
  workspace file list, multiline source editor, runtime console, command field,
  lifecycle actions, and persistent movable/resizable geometry.
- Added bounded in-game editing for `.java` files and `champion-java.json` below
  the runtime workspace. Ctrl+S and Save file persist changes, emit the new
  source hash, invalidate stale confirmation naturally, and audit the file name
  and hash without recording source contents.
- Restyled the F8 console as a dark Linux-style terminal with a title bar,
  shell-like prompt, colored output, history, scrolling, and selectable text.
- Added shared close, maximize/restore, and minimize controls to the terminal,
  Java Studio, sorted ClickGUI, category panels, and module-settings window.
  Double-clicking a title bar also toggles maximize.
- Preserved one-based Minecraft 26.3 pointer handling, drag/resize behavior,
  ClickGUI exploit warnings, exact-target authorization, one-run confirmation,
  and audit controls.
- Standardized all user-visible product branding on ChampionsCode while retaining
  stable technical IDs and configuration paths.

## 0.1.0-alpha.15 - 2026-09-20

- Added an automatic GitHub prerelease job after successful pushes to `main`.
- The workflow reads and validates the prerelease version from
  `gradle.properties`, creates a `v<version>` tag, and marks the release as a
  prerelease.
- The release uploads the verified binary JAR, source JAR, and SHA-256 manifest.
- Pull requests and manual workflow runs continue to build without publishing.
- Added immutable-tag protection: re-running the same commit is supported, while
  reusing a version tag for a different commit requires a version bump.
- Correctly treats the GitHub API's `404 Not Found` response as an absent tag so
  the first prerelease can create it.
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

