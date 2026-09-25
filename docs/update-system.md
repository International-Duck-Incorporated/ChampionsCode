# Update system

ChampionsCode can tell players when a newer release exists and can fetch it
for them, without ever hot-swapping code inside a running game. This document
describes what the system does, the guarantees it keeps, and how to reason
about it when reviewing changes.

## Behavior

- **Startup check.** Once per launch, after the player joins a session, the
  client reads this project's public GitLab releases feed
  (`GET https://gitlab.com/api/v4/projects/international-duck-incorporated%2FChampionsCode/releases`).
  If a release newer than the running version exists, a chat notice appears
  with a clickable link to the release page and the exact console command to
  run. The check is skipped in development environments, is disabled with
  `checkOnStart=false` in `config/champions-mod/update.properties`, and fails
  silently when the feed is unreachable.
- **`update check` (F8 terminal).** Compares the running version against the
  newest published release and prints the release page link.
- **`update download <version>` (F8 terminal).** Resolves the latest release
  again, requires the requested version to be exactly that release, downloads
  `champions-mod-<version>.jar` and `SHA256SUMS.txt`, verifies the JAR's
  SHA-256 byte-exactly, and only then writes the JAR into the `mods` folder.
  The old JAR is never touched; the player removes it and restarts.

## Guarantees

- **The session is never involved.** The updater talks only to `gitlab.com`
  over HTTPS. It sends no packets on the Minecraft connection and reads
  nothing from it. It is therefore not part of the authorization-gated
  exploit path.
- **Fail-closed downloads.** A release without a `SHA256SUMS.txt` asset, a
  manifest without an entry for the exact JAR name, or a single mismatched
  byte aborts the download with nothing written to `mods`.
- **No hot-swap.** Nothing is executed, replaced, or unloaded at runtime. The
  new JAR lands beside the old one and takes effect after a manual restart.
- **Latest-only downloads.** `update download` refuses any version other than
  the newest release, so players cannot be talked into fetching stale jars.
- **Host-pinned URLs.** Every requested URL must be HTTPS on `gitlab.com`,
  even though the URLs themselves come from GitLab's own API response.
- **Auditable.** `update_check` and `update_download` events are appended to
  the local JSONL audit log with outcomes (`available`, `current`,
  `completed`, `error`).

## Version comparison

Tags are matched as `v?<major>.<minor>.<patch>(-<prerelease>)` and compared
with the usual ordering: releases outrank prereleases, prerelease identifiers
compare numerically when numeric, and numeric identifiers rank below
alphanumeric ones (`0.1.0-alpha.16 < 0.1.0-beta.1 < 0.1.0-beta.2 < 0.1.0`).
Anything that does not fit the scheme is ignored by the feed parser.

## Code map

- `dev.champions.core.update` — console-side, Minecraft-free logic:
  `MiniJson` (minimal JSON parser), `UpdateVersions` (ordering),
  `UpdateFeedParser` (GitLab releases JSON to `UpdateCheck`),
  `UpdateService` (interface plus a `disabled()` no-op), `UpdateResult`.
  Unit tests live next to the console tests.
- `dev.champions.client.update` — client implementation:
  `GitLabUpdateService` (HTTP, checksum verification, staged write into
  `mods`), `UpdateSettings` (`update.properties`), `UpdateNotifier` (startup
  chat notice). Wired into `ChampionsClient` with one constructor argument
  plus one `register()` call.

## Release coupling

The updater recognizes release assets by their exact names:
`champions-mod-<version>.jar` and `SHA256SUMS.txt`. The CI release job
already produces both (see `PUBLISHING.md`); keep those names stable or the
updater will treat the release as not downloadable.
