# Notice

ChampionsCode is published by **International Duck Incorporated (IDI)**. The governing
licence for this repository and for every release from `0.2.0-beta.12` onward is
[`LICENSE.md`](LICENSE.md) — All Rights Reserved.

## Material ported from other projects

This project contains ports. The knowledge, mechanism descriptions and dispositions of
material originating in the archives listed in Schedule A of the
[Master Mod Porting License v1.0](PORTING-LICENSE.md) were ported under that agreement.
Ownership of the original modules remains with their respective rights holders; nothing in
this repository transfers that ownership to IDI.

| Original work | What was taken | Where it lives now | What was changed |
| --- | --- | --- | --- |
| `venom4plugin` (Meteor Client addon, 48 module classes) | the table of which plugin exposes which mechanism, and the reproduction each one needs | `core/catalog/ExploitCatalog.java`, `core/exploit/mechanism/` (10 mechanisms), `core/exploit/payload/` (9 payloads), and the `auxiliary/*` modules in this jar and in the modules collection | re-implemented as Java 25 research instruments: no chat command batteries, no config deployment, no live injection. The per-module disposition is recorded in [`docs/porting-venom4plugin.md`](docs/porting-venom4plugin.md). |
| other archives named in Schedule A | reference only — no code copied | — | — |

The port is a rewrite, not a copy: no source file, class, identifier or comment from the
original addon is reproduced here. What carries over is knowledge — which plugin, which
class, which mechanism — and that is attributed above.

IDI does not claim authorship of the original modules. Modifications made by the port are
documented in `docs/porting-venom4plugin.md`, and each mechanism's javadoc states what its
original module did and what this build does instead.

## Withdrawal

Schedule A and §11 of the porting licence let a rights holder withdraw permission. Where
that happens, the affected material is removed or rewritten on request; the disposition
record is kept so the removal is auditable.

## The MIT notice in this repository

[`LICENSE-MIT-historical.txt`](LICENSE-MIT-historical.txt) (MIT) is retained verbatim and
is **not** the licence of this project. It is the notice that covered the upstream project
and the releases up to and including `0.2.0-beta.11`, and MIT requires that notice to
travel with the code it covered. Recipients of those earlier releases keep the rights they
were granted.

From `0.2.0-beta.12` onward the published jar declares `All Rights Reserved` in its
`fabric.mod.json`, and the All Rights Reserved licence governs.

## Dependencies

Third-party dependencies keep their own licences and are not covered by anything stated
here. The runtime dependencies are Minecraft, Fabric Loader and Fabric API; the test
dependencies are JUnit 5. No third-party code is vendored into this repository.
