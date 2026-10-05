# External Java research modules

External Java research modules are drop-in `.java` files. They live outside the
repository's `src/` tree, compile in the running Minecraft client, and are
registered automatically in the F8 console, ClickGUI, and category GUI. You do
not add a registration line to `ChampionsClient`, edit `ModuleRegistry`, or
rebuild the mod for each module.

The workflow is plug and play: **write a file, and the module is there.**

```text
.minecraft/config/champions-mod/modules/MyModule.java        -> loaded
.minecraft/config/champions-mod/modules/pack/deep/More.java  -> loaded
```

## Drop folder

The live client recursively searches this folder:

```text
.minecraft/config/champions-mod/modules/
├── MyModule.java
├── experiments/
│   └── AnotherModule.java
└── helpers/nested/EvenDeeper/
    └── Helper.java
```

- **Recursive at any depth.** Subfolders are searched to any depth, so module
  packs can keep their own folder layout and imports.
- **All `.java` files compile together**, so helper and companion classes in
  sibling folders are supported.
- **Generated classes** go to the hidden `modules/.build/` folder, which is
  excluded from discovery, hashing, and change detection.
- **Names starting with a dot are skipped** (`.build/`, editor scratch files,
  VCS folders), and only files ending exactly in `.java` count, so
  `Module.java~`, `Module.java.bak`, and README files never load.
- The first time the folder is created ChampionsCode writes a short
  `README.txt` next to your files describing the rules above.

The exact `.minecraft` directory depends on the launcher and game instance. Run
`java modules folder` in the F8 console to print the path, or `java modules
status` for the full state. Files can be placed anywhere below `modules/`;
ChampionsCode deliberately does not scan the rest of the computer.

A working example is in
[`examples/modules/ExampleResearchModule.java`](../examples/modules/ExampleResearchModule.java).
Copy it into the runtime `modules/` folder, or use it as a template. The example
implements `ResearchModule`, reads only the immutable client-session snapshot,
and sends nothing.

## Use a module

1. Put one or more `.java` files anywhere under the runtime `modules/` folder,
   ideally while the game is running. Every discovered module must be a public
   top-level class implementing `dev.champions.core.module.ResearchModule`,
   with a public no-argument constructor and a unique, lower-case `id()`.
2. That is the whole setup. Within about two seconds ChampionsCode notices the
   changed folder, compiles the whole tree, and registers the modules:

   ```text
   Drop folder changed: 2 source file(s), hash=8c2f41a9e6d0
   Compiling 2 external Java source file(s) (plug-and-play); source hash=8c2f41a9e6d0...
   Plug-and-play loaded 2 external research module(s): auxiliary/custom/one, auxiliary/custom/two
   ```

3. Use them immediately like any built-in module: `show modules`, `use
   auxiliary/custom/one`, `show options`, `set ...`, `run`, `clickgui`,
   `categorygui`. The normal per-run exact-target authorization, one-run
   `CONFIRM` phrase, and audit logging still apply to the modules you wrote.
4. Edit a file and save: the new revision replaces the old one, and the console
   reports the new source hash. Delete every `.java` file and the loaded group
   is unregistered again. Nothing has to be typed for any of this.

Modules are also loaded once at client start, so a freshly copied file is
already available the next time the game launches.

### How change detection works

ChampionsCode polls a metadata fingerprint of the drop folder once a second and
recompiles only when it changes. Two rules keep this quiet and predictable:

- a change is only acted on after the folder has been unchanged for a short
  quiet period, so saving a file in stages is one reload, not several;
- a state that already failed to compile is left alone until the files change
  again, so a broken file prints its compiler errors once instead of every
  second.

A reload never interrupts a running module: if a module is mid-run, the change
is applied when it finishes. Every automatic load, unload, and failure is
recorded in `audit.jsonl` with `auto=true`, and the last loaded source hash is
recorded in the console output.

### Console controls

```text
java modules folder          print the drop folder path and file count
java modules status          state, hashes, loaded ids, plug-and-play flags
java modules auto            show plug-and-play, auto-reload, and counters
java modules auto on|off     enable or disable automatic loading
java modules auto now        reload the folder immediately
java modules auto watch off  load once at startup, but do not watch for changes
java modules sources         list every source file that would compile
java modules unload          unregister the loaded group (stays unloaded until
                             the files change, or until java modules auto now)
java modules diagnostics     last compiler diagnostics
```

`java modules unload` is respected: the group does not come back until a file
actually changes. Disabling plug-and-play with `auto off` leaves already loaded
modules registered and stops watching the folder; a restart or `auto now` applies
changes again.

## Options

`config/champions-mod/java-runtime.properties` controls the workflow:

| Key | Default | Meaning |
|---|---|---|
| `plugAndPlay` | `true` | Compile and register every `.java` file below `modules/` automatically. Set `false` to require the manual review-and-confirm workflow. |
| `autoReload` | `true` | Watch the folder while the game runs. Set `false` to load once at startup only. Requires `plugAndPlay=true`. |
| `developerMode` | `false` | Gates the single-entrypoint `java/` addon and the manual hash confirmation used when `plugAndPlay=false`. |
| `confirmedSourceHash` | empty | Manual confirmation for the `java/` addon workspace. |
| `confirmedModuleHash` | empty | Manual confirmation for the `modules/` folder when `plugAndPlay=false`. |

Changes are re-read within a second, so `java modules auto on|off` in the console
(which writes the same file) applies without a restart.

### Manual mode: review, confirm, reload

With `plugAndPlay=false` the older, stricter workflow applies. Nothing compiles
until the user reviews the files and confirms the exact source hash:

```text
java modules status
java modules sources
java modules hash
java modules confirm <64-character-sha256>
java modules reload
```

Run `java modules status` and `java modules sources`, review every listed file
yourself - a `.java` file is executable code, not a data file - then confirm the
hash that `java modules hash` prints. A source edit, added file, deletion, or
rename changes the hash and requires a new review and confirmation. This mode
also needs `developerMode=true`. `java modules compile` validates compilation
and discovery without instantiating or registering anything.

Manual loading is atomic: if compilation, validation, or registration fails, the
previous group stays registered. Duplicate IDs (including collisions with
built-in modules) are rejected, and a module that fails in the middle of a swap
leaves the working group in place.

## Module API

The external source API is the project's `ResearchModule` contract and its
immutable data types in `dev.champions.core.module`. The framework applies the
normal per-run exact-target authorization and audit controls. A module that
implements `ExploitResearchModule` is also shown behind the separate Exploit
workspace warning. See [`module-authoring.md`](module-authoring.md) for the full
contract and safer authoring rules.

For example, the core shape is:

```java
public final class MyModule implements ResearchModule {
    @Override public String id() { return "auxiliary/custom/my_module"; }
    @Override public String name() { return "My module"; }
    @Override public String description() { return "Reviews a client snapshot."; }
    @Override public List<ModuleOption> options() { return List.of(); }

    @Override
    public ModuleResult run(ModuleContext context, Map<String, String> options) {
        return new ModuleResult("Review complete", List.of(
            new Finding(Finding.Severity.INFO, "Server brand", context.serverBrand(),
                "Treat client metadata as an observation, not server-side proof.")
        ));
    }
}
```

The `ResearchModule` contract defaults to `requiresAuthorization() == true`.
When authorization is required, include the expected `RHOST` and one-run
`CONFIRM` options so the console can enforce the exact-current-target workflow.
Follow the API types used by `examples/modules/ExampleResearchModule.java` for a
complete example.

## Important security and runtime notes

- **Plug-and-play means plug-and-play.** With the default settings, dropping a
  `.java` file in the folder compiles and runs it inside Minecraft without a
  confirmation step. This is unrestricted Java execution in the game process,
  **not a sandbox**: loaded code has the permissions of Minecraft, including
  access to files, network APIs, Minecraft internals, reflection, and process
  APIs. Treat the folder like a mods folder and only keep files there that you
  wrote or have fully reviewed. `java modules auto off`, or
  `plugAndPlay=false`, restores the review-and-confirm workflow described above.
- Keep research modules passive, bounded, and fast. `ResearchModule.run()` is
  synchronous and runs on the client thread; slow work can freeze the game.
  Avoid persistent threads, callbacks, sockets, arbitrary packets, file access,
  or process execution. Java has no safe forced-unload mechanism for code that
  leaves live threads or callbacks behind.
- `java modules unload` unregisters modules and closes the URL classloader, but
  Java class unloading is best-effort and does not undo side effects that a
  module may have caused. Restart Minecraft if cleanup is uncertain.
- Source sets are limited to 128 Java files and 2 MiB total. Symbolic-link
  sources are rejected. Compilation requires a full JDK 25, because a stripped
  Java runtime does not include `javax.tools.JavaCompiler`; if it is missing, the
  console says so once and leaves the folder alone.
- External modules are distinct from the single-entrypoint runtime addon in
  `config/champions-mod/java/`. See [`runtime-java.md`](runtime-java.md) for that
  workflow.
