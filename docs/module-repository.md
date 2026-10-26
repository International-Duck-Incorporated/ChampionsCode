# Module repository

ChampionsCode can pull module sources from private GitLab repositories instead of
making you copy files around. Any number of repositories can be configured, and
they are searched together. This document covers the client half — how it reads a
repository and what stops a bad file from being compiled.

## Why sources and not a jar

A `.java` file is compiled by the client against **its own** build, so one
collection works across client versions without shipping binaries that may not
match the runtime, and the source is reviewable before it runs. The cost is
that the client needs a JDK — the same requirement the drop-in folder already
has.

## Commands

| Command | Does |
| --- | --- |
| `repo status` | Every configured repository: url, ref, token state, cached index |
| `repo add <name> <url> [ref]` | Adds a repository; appended last, so existing ones keep priority |
| `repo remove <name>` | Forgets a repository and its token |
| `repo token <name> <token>` | Stores a read-only token for one repository |
| `repo refresh [name]` | Drops cached indexes so the next read hits the repository |
| `repo list` | Every module across every repository |
| `repo search <words>` | Filters by id, name, description and tags; every word must match |
| `repo install <id>` | Downloads, verifies and loads one module from whichever repository publishes it |
| `repo install <repo>:<id>` | The same, pinned to one repository |

**Order matters.** Repositories are searched in the order they were configured and
the first one that publishes an id wins; when a later repository also has it, the
console says so rather than silently choosing. A repository that cannot be reached
is reported and skipped, so one bad entry does not stop the rest.

The same command exists in the desktop app and in the in-game console, because
it lives in `ChampionConsole` — only the install target differs.

## Configuration

`<config>/repo.properties`, one block per repository:

```properties
repo.default.url=https://gitlab.com/group/project
repo.default.ref=main
repo.default.token=glpat-...

repo.team.url=https://gitlab.com/group/team-modules
repo.team.ref=main
```

Written owner-only (`0600`) where the filesystem supports it. The single-repository
form from 0.2.0-beta.3 (`url=`, `ref=`, `token=`) is still read and folded into a
repository named `default`, so an upgrade keeps its token; saving rewrites it into
the named form.

`CHAMPIONS_REPO_URL`, `CHAMPIONS_REPO_REF` and `CHAMPIONS_REPO_TOKEN` override the
`default` repository, so a token can be kept off disk entirely. Tokens are only
ever printed masked (`glpa...3aag`).

The token needs **read-only** access to the project. One that cannot write means a
leaked client config cannot alter the collection it pulls from.

## The index is the trust anchor

`index.json` pins a SHA-256 per module file:

```json
{
  "schema": 1,
  "modules": [
    {"id": "auxiliary/audit/world_context", "name": "World context audit",
     "version": "1.0.0", "category": "audit", "api": 1,
     "tags": ["dimension", "position"],
     "description": "States dimension, coordinates and session flags.",
     "file": "modules/WorldContextAuditModule.java",
     "sha256": "74ca59fb3b7a…"}
  ]
}
```

`repo install` refuses a file whose bytes do not hash to that value, refuses an
entry with no hash at all, and refuses an entry whose `api` is above the level this
build understands rather than compiling something written for a newer contract. A repository can rewrite its own index, but it
cannot make a changed file match a hash the client already read — so an
intercepted or swapped file never reaches the compiler. An index that is not a
JSON object with a `modules` array is rejected rather than read as an empty,
healthy repository (an HTML error page must not look like "nothing published").

## What a fetched module can do

It is compiled into the client process and has the client's reach. The
authorization gate, the one-run `CONFIRM` phrase and the audit log cover **running
a module against a server**, not loading it. Pull from a collection you control,
and read `modules/` before installing from a branch you have not reviewed.

## Adding modules

See the repository's own `README.md`. The short version: the tree mirrors the ids
(`modules/auxiliary/audit/WorldContextModule.java` is `auxiliary/audit/world_context`
in package `modules.auxiliary.audit`), the header block sits at the top of the
file, `python3 tools/build_index.py` regenerates the index, and the module plus the
index are committed together. `python3 tools/build_index.py --check` fails on a
module that was committed without its index entry, and on a module that imports
anything beyond the module contract.
