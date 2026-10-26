# Module repository

ChampionsCode can pull module sources from a private GitLab repository instead of
making you copy files around. The collection lives at
`international-duck-incorporated/archive-department/championscode-modules`; this
document covers the client half — how it reads the collection and what stops a
bad file from being compiled.

## Why sources and not a jar

A `.java` file is compiled by the client against **its own** build, so one
collection works across client versions without shipping binaries that may not
match the runtime, and the source is reviewable before it runs. The cost is
that the client needs a JDK — the same requirement the drop-in folder already
has.

## Commands

| Command | Does |
| --- | --- |
| `repo status` | Where the repository is, which ref, whether the index has been fetched |
| `repo list` | Every module in the index |
| `repo search <words>` | Filters by id, name, description and tags; every word must match |
| `repo install <id>` | Downloads, verifies and loads one module |
| `repo refresh` | Drops the cached index so the next read hits the repository |
| `repo config <url> [ref]` | Points the client at a project |
| `repo config token <token>` | Stores a read-only token |
| `repo config clear` | Forgets both |

The same command exists in the desktop app and in the in-game console, because
it lives in `ChampionConsole` — only the install target differs.

## Configuration

`<config>/repo.properties`:

```properties
url=https://gitlab.com/group/project
ref=main
token=glpat-...
```

Written owner-only (`0600`) where the filesystem supports it. `CHAMPIONS_REPO_URL`,
`CHAMPIONS_REPO_REF` and `CHAMPIONS_REPO_TOKEN` override the file, so a token can
be kept out of a shared or synced config folder entirely. The token is only ever
printed masked.

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

`repo install` refuses a file whose bytes do not hash to that value, and refuses
an entry with no hash at all. A repository can rewrite its own index, but it
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

See the repository's own `README.md`; the short version is: header block at the
top of the file, `python3 tools/build_index.py`, commit the module and the index
together.
