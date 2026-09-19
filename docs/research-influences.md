# Clean-room research influences

Champions Mod does not vendor, copy, shade, or rename code from the projects
below. Their public descriptions were reviewed for capability ideas, then the
Champions implementations were designed independently against Minecraft 26.3.
The module names, APIs, data models, bounds, and code are original to this
project.

Reviewed on 2026-09-19:

- [UI-Utils](https://github.com/Coderx-Gamer/ui-utils), its archived Reborn
  variants, and the current [26.x CevAPI port](https://github.com/cev-api/UI-Utils-26x-CevAPI)
  — inventory synchronization and packet-flow testing concepts. Champions does
  not include soft-close/desync controls, packet fabrication, packet gates,
  delayed flush queues, auto-duping, account access, process launching, HTTP
  tooling, resource-pack bypasses, or packet mutation.
- [mcprotocol.c](https://github.com/sk8erboi17/mcprotocol.c) — strict decoding,
  bounded framing, deterministic replay, and fuzz-test design. It is an MIT C11
  native library targeting through 26.2; Champions does not bundle JNI/native
  code. Session Trace is metadata-only and cannot reinject packets.
- [NetUtils](https://modrinth.com/mod/netutils) — MIT Architectury networking
  and state-synchronization concepts; its published project metadata currently
  lists versions through 1.21.11 rather than 26.3. Champions keeps its inventory
  module read-only and does not add desynchronization or resource-pack bypass behavior.
- [oxidized-client-headless](https://github.com/oxidized-mc/client-headless) —
  separation of transport, protocol analysis, testing, and world state. The
  referenced project is MIT and pre-alpha; Champions does not launch bots,
  proxies, load generators, or external Rust processes.
- [Iustitia](https://github.com/ThoriaDevelopment/Iustitia) — passive,
  client-side evidence collection and explicit false-positive handling. Its
  published target is 1.21.11. Champions Motion Evidence uses broad local
  observations only and provides no verdict, enforcement, bypass research, or
  copied detection checks.
- [CheaterDeleter](https://github.com/CoolMineman/CheaterDeleter) and
  [Windfall](https://github.com/enis1enis2/Windfall-AntiCheat) — defensive
  server-side packet/exploit detection categories. Champions is client-side and
  does not reproduce their checks or punishment systems.
- [CrashExploitFixer](https://modrinth.com/mod/crashexploitfixer) — defensive
  handling of parser depth and excessive allocation classes. Its current
  project metadata lists a 26.3 build under GPL-3.0-only. Champions does not
  copy or bundle it; Client Resilience only reports coarse local metadata and
  recommends current protocol fixes and server-side patches.
- [AUTISM Client](https://github.com/AutismDevelopment/Autism-Client) — a
  GPL-3.0 modular client with packet, payload, macro, and addon systems. Only
  the general ideas of structured modules, bounded capture, explicit settings,
  and testable workflows were considered. Champions does not adopt its packet
  queue/editor/sender, custom-payload, multi-client coordination, account,
  anti-fingerprinting, or exploit-automation surfaces.
- [wakDuper](https://github.com/me-jndildap/wakDuper) — a 1.21.1 GUI/packet
  manipulation client whose repository marks its code all-rights-reserved.
  Champions copies no source and excludes soft close, GUI desynchronization,
  packet cancellation/delay/flush, fabricated clicks, and race orchestration.
- [Doujin Dupe](https://github.com/qnxt/doujin-dupe) — GPL-3.0 automation for a
  specific historical book-duplication exploit. Champions does not reproduce
  its sequence, reconnect behavior, preconditions, or exploit-specific logic.
- [Dupe-Utils](https://github.com/PhilipPanda/Dupe-Utils) — an archived
  GPL-3.0 1.21.11 GUI/packet manipulation utility. Its observable menu-flow
  vocabulary informed only a clean-room, class-metadata correlation view; no
  GUI retention, packet scheduler, fabrication UI, or source code is reused.

## Resulting original module families

- **Wire Lens** — payload-free packet class/direction/rate summaries.
- **Session Trace** — bounded metadata ordering timeline, not traffic replay.
- **Container Integrity** — read-only local menu and component-density review.
- **Transaction Echo** — passive class-level menu action/update correlation;
  no IDs, revisions, slot data, packet gating, fabrication, or exploit verdict.
- **Client Resilience** — observe-only burst/error/custom-payload warnings.
- **Motion Evidence** — broad remote-player telemetry with no cheat verdict.

If a future integration uses third-party source or binaries, its exact license,
copyright notice, source link, compatibility, and dependency boundary must be
preserved. Clean-room inspiration is not a license bypass.
