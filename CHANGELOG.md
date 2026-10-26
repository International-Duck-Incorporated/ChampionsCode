# Changelog

## [Unreleased]

Nothing yet.

## 0.2.0-beta.16 - 2026-10-26

### Added
- **`auxiliary/audit/mod_posture`** — the mod-side counterpart to the plugin
  inventory. It lists the mods this client loaded, names the ones that change what
  a server accepts (Cobblemon, LuckPerms, Create, Lithium, ViaVersion) and the ones
  that open a service beside the game port (Simple Voice Chat, Geyser, spark's
  viewer, the LuckPerms web editor), flags working-tree builds and duplicate mod
  jars, and lists the mods it does not recognise as unreviewed rather than unsafe.
- **Channel identifiers on the wire.** `PacketTelemetry` now records the
  identifier of each custom payload it sees -- `cobblemon:main`, not the bytes.
  It is bounded to 64, never written to the audit log, and it is the only evidence
  a client can have about which mods the other end is running.
- `ModCatalog` (24 mod families) and `ModPosture` (pure analysis) live in
  `core`, so the whole report is testable without a game; the client module only
  fills in the loader and telemetry.

### Kept honest
The inventory is this client's, and the report says so in a finding of its own: a
client cannot see a server's mod list, and a channel identifier is only nameable
when this client has the mod that decodes it. Both limits are stated in the module
output, not in a footnote.

## 0.2.0-beta.15 - 2026-10-26

### Added
Four modules that turn the command surface into an inventory, and eight more plugin
families in the review table. All four read the tree the server already sent this
account and send nothing.

- `auxiliary/scanner/plugin_fingerprint` — names the plugin families the advertised
  roots point at, lists which of them the exploit catalogue has entries for, and
  hashes the sorted roots so two scans of one server compare equal and a changed
  surface does not. That makes "what changed since the last audit" a diff of two
  fingerprints instead of a memory test.
- `auxiliary/audit/admin_command_exposure` — classifies the tree into authority, code,
  moderation, world, player and disclosure capabilities, and reports which this
  account was offered. A root in the tree is usually a root the server believes the
  account may run; the ones that should not be there are either a permission mistake
  or a plugin that never filtered its tree, and both matter.
- `auxiliary/audit/alias_shadowing` — reports bare names claimed by more than one
  provider, vanilla names a plugin now answers, and duplicate subcommand literals.
  Registration order is a server-side fact, so it reports that a contest exists
  rather than guessing the winner.
- `auxiliary/audit/client_trust_posture` — offline mode, platform brand, protocol
  self-description and resource-pack policy, each with what it does and does not
  establish. Offline mode is the one that changes what every other finding means.

`PluginRiskReviewModule` gained LuckPerms, PlaceholderAPI, Plan, Dynmap, LiteBans,
Simple Voice Chat, AuthMe and CMI profiles (16 to 24 families), and its profile table
is now shared with the fingerprint module so the two name a family the same way.

### Kept honest
Every one of these reports absence as absence of evidence, not evidence of absence:
the tree is filtered by permission for the account that asked for it, and each module
says so in the finding that would otherwise read as a clean bill of health.

## 0.2.0-beta.14 - 2026-10-26

### Fixed
- **A plug-and-play payload module had nowhere to write.** The console handed the
  audit folder to modules registered before it was constructed, but not to
  modules registered afterwards -- which is how every drop-in, runtime-compiled and
  example module arrives. `registerModule` and `replaceExternalModules` now inject
  it, so a payload module in the payload folder can write the artifact it
  declares instead of reporting "this front end has no artifact folder".
- **The payload template did not implement the payload contract.** `ExamplePayloadModule`
  -- the file the docs tell you to copy -- implemented only `ResearchModule`, so a
  module built from it declared no effect, was not checked by `PayloadSafety`, and
  showed no payload banner. It now implements `PayloadModule` and
  `ArtifactFolderAware`, declares `example_plan.txt`, writes it, and sends nothing.

### Added
- Three console tests: a drop-in payload module receives the audit folder and
  writes its artifact, a runtime-replaced module receives it too, and a drop-in
  module whose declared effect names a path is refused before its body runs.

## 0.2.0-beta.13 - 2026-10-26

### Fixed
- **F8 opened both windows whatever the interface was set to.** The external app's
  Settings tab wrote `ui=` to `desktop.properties`, but the running game kept the
  values it read at startup, so the choice only took effect after a restart.
  `DesktopSettings` now notices an outside edit (timestamp and size) and re-reads
  before anything is routed, and the Settings tab sends `interfaces reload` to an
  attached game. A request that ends with nothing opened now logs why instead of
  returning silently.
- **The resize cursor stayed on screen.** The window resizer only received motion
  events while the pointer was over the root pane, so moving onto a tab or a table
  left the last diagonal cursor set, and every child without a cursor of its own
  inherited it. The cursor is now driven from every motion event in the window and
  set on the frame, so children that declare their own cursor still win. The corner
  map was wrong too: the bottom-right corner reported north-east. Both the mask and
  the cursor are now pure functions with tests.
- **The window icon had no background.** The taskbar icon was built from the
  title-bar artwork, whose black background is keyed to transparency for use on the
  window chrome. Window icons are now opaque black squares built from the untouched
  asset; the title-bar mark keeps its transparent background.

### Added
- `DesktopSettingsTest` (10 tests), `ShellCursorTest` (6), `BrandIconTest` (3).
  `DesktopSettings` moved to `dev.champions.desktop` so the desktop app and the
  client share one implementation of the file format instead of two.

## 0.2.0-beta.12 - 2026-10-26

### Fixed
- **The licence in the repository was MIT.** The governing licence is IDI's All Rights
  Reserved text; `LICENSE.md` now carries it verbatim, and the jar's `fabric.mod.json`
  declares `All Rights Reserved` instead of `MIT`. `PORTING-LICENSE.md` carries the
  Master Mod Porting License v1.0 under which the port was made.
- **No attribution for ported material.** `NOTICE.md` now names the original work, states
  that ownership of the original modules is unchanged, points at the per-module
  disposition, and records the §11 withdrawal path. The README and
  `docs/legacy-module-review.md` no longer claim the port is MIT or wholly original.
- **The port record was wrong.** `docs/porting-venom4plugin.md` said 34 modules. The
  archive holds 48 classes named `*Module.java`: 47 modules plus the abstract `PocModule`
  they extend. Four of the 47 had no disposition at all (`EssentialsXConfig`,
  `ZAuctionHouseSqlInjection`, `ZAuctionHouseDeserializationRce`,
  `WorldEditSchematicDeserialize`). All four now have rows, with the destructive one
  excluded on principle rather than ported.
- **Two dispositions named nothing.** `zauctionhouse_deser` and `worldedit_schematic` are
  now real catalogue entries, so "catalogue only" points at an entry a reader can open
  rather than a sentence.

### Added
- `META-INF/port-manifest.tsv` ships in the jar: one row per source class, with its
  disposition and the catalogue entry it maps to. `PortManifestTest` (8 tests) and two
  `--self-test` checks fail the build if a row disappears, a disposition is invented, a
  reference dangles, or a catalogue entry has no source module behind it.

### Kept deliberately
- The MIT notice stays, verbatim and scoped, as `LICENSE-MIT-historical.txt`. It covered the upstream project and
  every release up to `0.2.0-beta.11`; MIT requires the notice to travel with that code,
  and recipients of those releases keep the rights they were granted. `NOTICE.md` says so
  explicitly so the two files cannot be mistaken for each other.

## 0.2.0-beta.11 - 2026-10-26

Reading commit `807795a` against the audit document that described it found six places
where the document described the design and the code implemented a nearby one. All six
are fixed; `docs/verification-audit.md` §6 records each, including the two this document
had overstated.

### Fixed
- **`discriminative` was a copy of the verdict.** It is computed from the effects now, and
  a run can be inconclusive while still having discriminated, which is the case that
  proves the field is not derived from the outcome.
- **Tier 2 read one fidelity after the run.** Fidelity is recorded per attempt and
  `evidenceTier` requires every observation to have run real code; one modelled sample
  drops the claim to the model.
- **`verify()` re-planned**, so the material approved at PLAN time was not necessarily the
  material executed. `verify(VerificationPlan, ...)` executes a plan; the module releases
  the plan it showed and refuses an acknowledgement whose configuration has changed.
- **A downgrade was silent.** A declared reference implementation that ran modelled code
  now says so, and so does a run that asked for tier 2 and could not supply it.
- **Mechanisms inherited determinism rather than declaring it.** Each one now overrides it
  with a reason.
- **Effect comparison normalised with one value.** Each side is normalised by its own, so
  "returned 2 rows for …bob" and "…alice" compare as the same behaviour.

### Added
- **`docs/threat-model.md`**: what the gate guarantees, what it cannot do (reflection,
  provider purity during planning, a modified build), and the test behind each claim.
- **`GateIntegrityTest`** (16 tests): forged-approval attempts, the private token, material
  immutability, providers that change their answer, switch kind, throw, return null or
  mutate the options map, and every mode combination that must not reach a live sink.
- **`ControlValidityTest`** (14 tests): the trigger stated per mechanism, and controls that
  change something else rejected rather than certified.
- `ReproductionEngine.evidenceTier(...)`: the boundary rule as a pure, testable function.

## 0.2.0-beta.10 - 2026-10-26

Verification, audited against the five questions a verdict has to survive. Four of the
five were real defects; `docs/verification-audit.md` records each one with the file it
was in and the test that now holds it.

### Changed
- **`VERIFIED` split into `FIXTURE_VERIFIED`, `IMPLEMENTATION_VERIFIED` and
  `TARGET_VERIFIED`.** The state names what was established, and `claim()` derives the
  sentence from the state, so a model of a sink and a real `ObjectInputStream` can no
  longer produce the same verdict. `claimCeiling` is gone.
- **`MODE IMPLEMENTATION` demands tier 2 and the gate refuses it when it cannot be
  honoured.** A modelled sink asked for implementation-grade evidence is refused rather
  than granted and described in a footnote.
- `outbound_fetch` runs a real loopback HTTP service for loopback hosts: a socket, a
  request, an answer observed on the server side. It degrades to `FIXTURE_VERIFIED`, and
  says so, when it cannot bind.

### Fixed
- **The engine observed the mechanism's opinion, not the sink.** `attemptOnFixture` read
  a boolean the mechanism computed (`size() > 1` for the console sinks). It now reads the
  fixture's effect log, credits an effect only when the control did not produce it and it
  names the payload's own data, and records the mechanism's claim separately so a
  disagreement is visible instead of decisive.
- **Effects are compared with the payload's value normalised away**, so a sink that
  behaves identically for both values is `INCONCLUSIVE` even though the two effect strings
  differ by a word.
- **A control must be the payload minus the trigger**: same shape, same sink operation
  (`trail()`), and its effects a subset of the payload's. A control identical to the
  payload is refused before anything runs.
- **The sample size is declared, not hard-coded.** `Determinism.DETERMINISTIC` needs 2,
  `INTERMITTENT` needs 5, and a verdict requires every required sample to land.
- **`GateDecision` is a capability.** Sealed, privately implemented, and only mintable
  with a token `ExecutionGate` holds privately: `new GateDecision(true, ...)` no longer
  compiles, and nothing outside the gate can manufacture an approval.
- **The plan carries its material objects**, so the payload that was shown and approved is
  the payload that executes.
- `SqlIdentifierMechanism` read the effect log for evidence and threw when a lookup
  produced no effect; it reads the statement log.
- `OutboundFetchFixture.reset()` no longer erases the fidelity of the last attempt, which
  the engine reads after the reset.

## 0.2.0-beta.9 - 2026-10-26

### Added
- **Verification as a flow, separate from activation.** `run` now produces a
  `VerificationPlan` and executes nothing; `ACTION VERIFY` with the `ACK` phrase
  releases it. A plan names every stage, the material it would supply, and the gate's
  verdict for each, without performing a write, a send or an invocation.
- **Control stages, and the `INCONCLUSIVE` state.** A verification runs CONTROL,
  PAYLOAD and REPEAT, each gated separately. The control carries the same shape with a
  benign value and must not reach the sink. If it does, the verdict is `INCONCLUSIVE`
  rather than `VERIFIED` — a mechanism that fires on everything is not a check. A
  payload that reaches once and not twice is also `INCONCLUSIVE`, which is where a
  race-condition bug surfaces.
- `PayloadProvider.control(...)`: every execution-capable payload now declares a
  control of the same shape, and a payload that declares none cannot be certified.
- Every report carries a `claimCeiling` — what its verdict may be used to assert — so
  a fixture result reads as "the mechanism is real", never as "your server is
  vulnerable".

## 0.2.0-beta.8 - 2026-10-26

### Added
- **Mechanism / payload / gate separation** (`core.exploit`). A vulnerability is now
  a `ExploitMechanism` that reproduces its sink against a `VulnerableFixture`, a
  `PayloadProvider` that declares and supplies only data, and an `ExecutionGate`
  that authorises every effectful operation. 10 mechanisms, 9 payloads, 7 fixtures.
- **`auxiliary/exploits/fixture_reproduction`**: pick a mechanism and a payload, and
  the engine reports one of `VERIFIED`, `NOT_REPRODUCED`, `BLOCKED_BY_GATE`,
  `ARTIFACT_ONLY`, `NOT_TESTED` or `ERROR`, with the basis (`FIXTURE`, `LIVE`,
  `ARTIFACT`) recorded separately from the verdict.
- **28 tests** across the new package: separation (one payload serving several
  mechanisms, one mechanism accepting several payloads), the gate (including
  refusals that survive operator authorisation), behaviour against fixtures, and
  cleanup.

### Changed
- The porting report is a second pass: every module that was previously refused now
  names the mechanism it became, and the ones that stayed catalogue-only say why.
- Payload compatibility checks apply to execution-capable payloads only. A document
  and a chat marker never touch the vulnerable sink, so they are universal; the
  shape rule exists to stop a payload reaching a sink it was never written for.

## 0.2.0-beta.7 - 2026-10-26

### Added
- **The ported catalogue.** `ExploitCatalog` carries what the old exploit addon
  actually knew: 15 plugin families, each with the surface that reveals it, the
  mechanism that makes it exploitable, the impact, a severity, and a portable
  reproduction where one exists. The knowledge survived the port; the delivery
  did not.
- **`auxiliary/audit/plugin_exploit_surface`** (check tier): joins the catalogue
  against the command tree the server already advertised and reports the
  mechanism for every match. Sends nothing — the module it was ported from
  tab-completed its way across the tree and slept between commands.
- **`auxiliary/payloads/poc_artifact`** (payload tier): writes one declared
  reproduction into the audit folder. `PayloadEffect.Kind.AUDIT_ARTIFACT` is held
  to a bare name, an inert extension (`.yml .yaml .sk .txt .md .json`) and an 8 KB
  ceiling, and the module's revert plan is a deletion it performs.
- **`AuditFolder`**: the one folder a payload may write into, fixed when the
  console is built, with the traversal check done twice. An artifact is a
  document — nothing in this client applies, deploys or sends one.
- `docs/porting-venom4plugin.md`: the mapping for every module in the archive,
  including what was refused and why.
- A collection module, `auxiliary/scanner/plugin_enumeration`, ported from the
  old `plugin-detect`: the same plugin list, read from the advertised tree.

### Changed
- **`PayloadModule.effect(Map)`**: the declared effect is now computed from the
  options that are about to run. It used to be read from a constant, which meant
  a module that selected its target at run time declared `no side effect` and
  then wrote a file — the declaration has to describe *this* run or it is theatre.
- The payload banner describes the actual kind: an artifact is "written to the
  audit folder", never "sent".
- Console line truncation uses an ASCII ellipsis; the renderer does not carry the
  Unicode one, and it was printing as `?`.

## 0.2.0-beta.6 - 2026-10-26

### Added
- **`ChatCommandRequest.proof(root, timeoutTicks, maxMessages)`**, a proof for a
  command that takes no arguments. The literal factory requires at least one
  node, so a payload could not express `/seed` or `/list` without reaching past
  it for the canonical constructor. **Module API level 2**; a client older than
  this build refuses an `api: 2` entry with an update message rather than
  failing to compile it.

### Fixed
- The payload banner called every payload "reversible", including a read-only
  query. Only an effect that creates state is reverted now; the rest are
  described as state-neutral.

## 0.2.0-beta.5 - 2026-10-26

### Added
- **Payloads: the third tier.** A `PayloadModule` proves an impact instead of
  describing it, with the effect declared before anything runs and checked by
  `PayloadSafety`: one visible chat command from a short allowlist, or one small
  marker file inside the audit folder. Grant, give, kill, teleport, kick and
  world-state commands are refused by name, and a declaration that does not match
  the vocabulary is refused rather than reinterpreted. Payloads inherit the
  full ceremony: declared effect printed first, per-run legal-risk
  acknowledgement, exact-host authorization, one-run `CONFIRM`, audit line naming
  the effect and revert plan.
- **`Origin.PROOF` on the runtime.** The client runtime could previously only
  send *probes*, restricted to non-default command roots, so a `/say` proof was
  impossible by design. A proof is now cleared by `PayloadSafety` instead, and
  validated again inside `ChatCommandRuntime` immediately before the send.
- **`auxiliary/payloads/chat_reach_proof`** as the worked example: one `/say`
  line, proof is the echo, `HIGH` when confirmed, and an explicit statement of
  what the run does not prove.
- `docs/payload-modules.md` describing the check / verify / payload split.

## 0.2.0-beta.4 - 2026-10-26

### Added
- **Several module repositories at once.** `repo add <name> <url> [ref]`,
  `repo remove <name>` and `repo token <name> <token>`; `repo search` and
  `repo list` query every configured repository, and `repo install <id>` takes
  the first that publishes it (`repo install <repo>:<id>` pins one). Config is
  one `repo.<name>.*` block per repository; the single-repository file from
  beta.3 is read and migrated. An unreachable repository is reported and
  skipped rather than failing the command.
- **`api` is enforced.** An entry whose module API is newer than this build
  refuses to install, instead of compiling source written for another contract.

### Changed
- **The module collection is restructured.** Its tree now mirrors the ids
  (`modules/auxiliary/audit/WorldContextModule.java` is
  `auxiliary/audit/world_context`), `build_index.py` validates the tree and the
  imports rather than only reading headers, and the repository gained a schema,
  a changelog, a licence and a CI check. See the collection's own README.

## 0.2.0-beta.3 - 2026-10-26

### Added
- **`repo` — pull modules from a private collection.** `repo list`,
  `repo search <words>`, `repo install <id>`, `repo refresh` and
  `repo config <url>|token|clear`. The client reads `index.json` over the GitLab
  API with a read-only token, downloads one `.java`, refuses it unless it hashes
  to the value the index pinned, writes it into the normal drop-in folder and
  lets the existing loader compile it. Works the same in the desktop app and
  in the in-game console; only the install target differs. Configuration lives
  in `repo.properties` (owner-only) and can be overridden by
  `CHAMPIONS_REPO_URL` / `CHAMPIONS_REPO_REF` / `CHAMPIONS_REPO_TOKEN`.
- **The collection itself** lives in the private repository
  `archive-department/championscode-modules`: five modules, `index.json` with
  pinned hashes, and `tools/build_index.py` to regenerate it from the header
  block at the top of each module file.

## 0.2.0-beta.2 - 2026-10-26

### Fixed
- **The Settings page no longer leaves a gap above its footer.** Both cards
  reported an unbounded maximum height, so the vertical stack handed the
  interface card every spare pixel: the rows sat at the top and the action
  footer was pinned to the bottom of a tall empty box. Cards are now held at
  their content height and the slack collects below them. A headless check in
  `--self-test` fails the build if a card ever stretches again.
- **Settings footer spacing and clipped messages.** The action row sat 4 px
  under the rows; it now has 10 px above and 12 px below. The status note moved
  under the buttons instead of beside them — the messages after a click were
  wider than the space left on that line and were cut off mid-sentence.
- **A saved window size can no longer restore below the minimum.** A geometry
  remembered from a build with the old 880x540 limit is clamped to the current
  1040x720 rather than reopening a window the Settings pane does not fit in.

## 0.2.0-beta.1 - 2026-10-26

### Added
- **`interfaces` console command.** `interfaces` prints the current placement,
  `interfaces both|external|in-game` switches it, `interfaces reload` re-reads
  `desktop.properties`, `interfaces spawn on|off` toggles autostart and
  `interfaces ingame on|off` keeps/drops the in-game windows. It is the chat-side
  twin of the app's Settings tab, backed by `InterfacePreferences` in `core` so
  both front ends edit the same file. `ingameWindows` joins `ui` and
  `spawnOnOpen` as a user setting.
- **Settings tab** in the desktop app: segmented interface mode, ClickGUI style,
  autostart toggle, the file locations, and the exact chat command to do the same
  in game.

### Fixed
- **Settings rows no longer clip.** The interface card used a scrolling column
  with a fixed height, so the third switch could end up under the action bar and
  a stray scrollbar appeared. The rows are plain rows again, sized by the card;
  the buttons moved into a pinned footer.
- **Editor scrollbars are themed.** The platform's light 3D scrollbar showed up
  under the code editor and beside the tree; both now use a flat 10 px dark bar.
- **The title bar and taskbar use the real logo.** The bar drew a vector
  approximation of the mark; it now renders `assets/champions/logo.png`
  (black keyed out, dark crimson lifted to the UI accent) at 40 px, and the
  same artwork feeds the window icons at 16-256 px, so Windows no longer
  falls back to the generic Java icon.

### Changed
- **New desktop look.** One frameless window (`Shell`) with a drawn title bar
  (brand mark, minimise / maximise / close), a 96 px icon rail, rounded cards,
  pills and a single status line, replacing the three separate OS windows. The
  palette is taken from the logo: crimson `#E8484F` on near-black `#0E0E12`,
  with monospace body text, sans headings, and green reserved for success.
  Geometry is remembered in `desktop-windows.properties` (one `shell=` entry).
- Terminal, Modules and Code space were rebuilt on the card layout: header chip
  per pane, crimson primary action, list selection that follows the open module,
  a painted search placeholder, a dark split gutter, tree glyphs instead of
  platform icons, tabs expanded to four spaces and the first module opened
  automatically.

## 0.2.0-desktop - 2026-10-25

### Added
- **External interface (the same JAR, outside the game).** `java -jar
  champions-mod-<version>.jar` now starts a native desktop application with three
  real OS windows: the **terminal** (msfconsole prompt, the same tones, history,
  1 000-line scrollback, copy/search keys), the **modules window** (external
  ClickGUI: module tree, search, typed option editors, dropdowns, run/back and
  target authorization) and the **code space** (file tree over the drop-in module
  folder, editor, template generator, compile/reload/unload). Nothing needs to be
  installed: Swing and NIO are part of Java 25, and no dependency was added.
- **Both front ends at once.** `config/champions-mod/desktop.properties` selects
  `ui=both` (default), `ui=external` or `ui=in-game`. F8/F7 and the `clickgui`
  command open the external window and, in `both`, the in-game window as well;
  `ui=in-game` restores the 0.1.x behaviour exactly.
- **Loopback bridge.** The game publishes an ephemeral-port JSON-lines bridge
  (`desktop-session.json`, per-launch 256-bit token, handshake before any other
  frame, single client, 8 KiB frame cap). The app sends the same calls the
  in-game UI makes, so the authorization allowlist, the one-run `CONFIRM` phrase,
  the per-run exploit warning (now answered in a desktop dialog, default-deny
  after 60 s) and the audit log all still execute in the game process. Detached,
  the app runs standalone with `ActiveModuleRuntime.disabled()`.
- **Shared surface models** (`dev.champions.surface`): `ConsoleBuffer` owns the
  bounded scrollback, history cursor and a revision counter so a view repaints
  only on change. The in-game screens are untouched and keep working.
- **Version capability contract** (`dev.champions.core.mc`): `ClientCapabilities`
  declares what a build can actually do (in-game screens, command tree, packet /
  container / motion telemetry, external UI) and `VersionGated` lets a module
  require features. The console refuses a module whose features are missing, with
  the exact reason, and audits it as `unavailable`; `status` prints the
  capability summary and the external app shows it in its status bar.
  `wire_lens`, `session_trace`, `transaction_echo`, `container_integrity`,
  `motion_evidence`, `plugin_surface` and `chat_plugin_enumerator` are gated.
- **`--self-test`** in the shipped JAR: headless verification of the terminal
  model, the wire codec, a full gated console session against the simulated
  `*.invalid` target, the version gate, and proof that no Minecraft or Fabric
  class is on the desktop classpath. Wired into `build.gradle`
  (`./gradlew desktopSelfTest`) and into GitLab CI, which also asserts
  `Main-Class` and the desktop classes in the packaged JAR.
- Docs: `docs/external-desktop-plan.md` (design) and
  `docs/version-compatibility.md` (why one JAR cannot span 1.21.11 and 26.3, the
  Stonecutter matrix, artifact naming and the updater change).

### Fixed
- **Crisp rendering on scaled displays.** The app now declares DPI awareness
  (`sun.java2d.dpiaware`, `sun.java2d.uiScale.enabled`) and enables LCD subpixel
  antialiasing before any AWT class loads, so Windows at 125/150/200 % no longer
  bitmap-stretches the window. The launcher passes the same flags when the game
  starts the app, and `--ui-scale 1|1.25|1.5|2` overrides detection.
- **Real monospace and UI fonts.** `Font.MONOSPACED` fell back to Courier New on
  Windows; `dev.champions.desktop.Fonts` now selects the first installed family
  from JetBrains Mono, Cascadia Mono, Consolas, SF Mono, Menlo, DejaVu Sans Mono,
  Liberation Mono, and the same for the interface font.
- **Window layout.** Windows are tiled against the usable screen area in logical
  pixels (never a fixed 1600x900 assumption), the modules window keeps a floor
  wide enough for its option editors, and geometry is remembered in
  `desktop-windows.properties` (delete it to re-tile).
- **Modules window polish.** Resizable list/editor split, ids shown without the
  shared `auxiliary/` prefix, constrained options as dropdowns, flat hover
  buttons, category filter chips, and a placeholder pane instead of an empty
  panel when nothing is selected.
- **Code space polish.** Line-number gutter, flat icon-free tree (folders green),
  flat toolbar buttons.

### Changed
- Logging in the version-free core goes through `dev.champions.core.log.Log`,
  which delegates to SLF4J inside the game and to stderr outside it. The desktop
  app therefore has zero dependencies.
- `ChampionConsole` gained `lineSequence()` (exact remote deltas) and
  `setCapabilitiesSupplier(...)`; no existing constructor or command changed.

### Not in this release
- The multi-version build matrix itself (Stonecutter nodes for 1.21.11 / 26.x) is
  specified in `docs/version-compatibility.md` but not enabled; this release
  still targets Minecraft 26.3 only, with the capability layer ready for it.
- `java.read` / `java.write` / `java.list` over the bridge are served by the
  game's own bounded runtime commands; the code space edits files directly.

## 0.1.0-beta.4 - 2026-10-06

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

