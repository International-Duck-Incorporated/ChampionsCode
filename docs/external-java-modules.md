# External Java research modules

External Java research modules are drop-in `.java` files. They live outside the
repository's `src/` tree, compile in the running Minecraft client, and are
registered automatically in the F8 console, ClickGUI, and category GUI. You do
not add a registration line to `ChampionsClient`, edit `ModuleRegistry`, or
rebuild the mod for each module.

## Drop folder

The live client recursively searches this folder:

```text
.minecraft/config/champions-mod/modules/
├── MyModule.java
└── experiments/
    └── AnotherModule.java
```

The exact `.minecraft` directory depends on the launcher and game instance. Run
`java modules status` to have ChampionsCode print the path. Files can be placed
in any subfolder below `modules/`; ChampionsCode deliberately does not scan the
rest of the computer. It compiles all `.java` files in that folder together, so
separate helper source files are supported. Generated class files live in the
managed `.build/` subfolder, which is excluded from module discovery and hashing.

A working example is in [`examples/modules/ExampleResearchModule.java`](../examples/modules/ExampleResearchModule.java).
Copy it into the runtime `modules/` folder, or use it as a template. The example
implements `ResearchModule`, reads only the immutable client-session snapshot,
and sends nothing.

## Load a module

1. Put one or more `.java` files under the runtime `modules/` folder. Every
   discovered module must be a public top-level class implementing
   `dev.champions.core.module.ResearchModule`, with a public no-argument
   constructor. Its `id()` must be unique and lower-case. One file may also
   contain helper classes or helper source files may sit beside it.
2. Open F8 and run `java modules status` and `java modules sources`. Review every
   listed source file yourself. A `.java` file is executable code, not a data
   file.
3. Runtime Java is disabled by default. Edit the reported
   `java-runtime.properties` file and set:

   ```properties
   developerMode=true
   ```

   Then run `java settings reload`.
4. Get the current bundle hash, review your files, and confirm that exact hash:

   ```text
   java modules hash
   java modules confirm <64-character-sha256>
   ```

   A source edit, added file, deletion, or rename changes the hash and requires
   a new review and confirmation.
5. Optionally validate compilation without instantiating the modules:

   ```text
   java modules compile
   ```

   To compile, instantiate, and register the modules:

   ```text
   java modules reload
   ```

   Compilation runs off the client thread. Registration and module construction
   are applied on the client thread. After the success message, the modules are
   available immediately through `show modules`, `use <id>`, `clickgui`, and
   `categorygui`.
6. To apply later edits, reconfirm the new source hash and run
   `java modules reload` again. To remove the currently loaded external group
   from the console and close its classloader, run:

   ```text
   java modules unload
   ```

   A reload is atomic: if compilation, validation, or registration fails, the
   previous loaded group stays registered. Duplicate IDs (including collisions
   with built-in modules) are rejected.

The Java Studio command field can run the same `java modules ...` commands.

If you are using the earlier session-modules ZIP, copy its actual module
implementation `.java` files (plus any helper source they truly depend on) into
this folder. Do **not** copy the ZIP's `ModuleRegistry.java`, framework API
classes, or JUnit test files over this repository; this loader discovers and
registers the implementations automatically.

## Module API

The current external source API is the project's `ResearchModule` contract and
its immutable data types in `dev.champions.core.module`. The current framework
applies the normal per-run exact-target authorization and audit controls. A
module that implements `ExploitResearchModule` is also shown behind the separate
Exploit workspace warning. See [`module-authoring.md`](module-authoring.md) for
the full contract and safer authoring rules.

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

- This is unrestricted Java execution inside the Minecraft process, **not a
  sandbox**. Loaded code has the permissions of the game process, including
  access to files, network APIs, Minecraft internals, reflection, and process
  APIs. The hash confirmation is a deliberate-review step, not a safety proof.
  Never load source you have not written or carefully reviewed.
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
  Java runtime does not include `javax.tools.JavaCompiler`.
- External modules are distinct from the single-entrypoint runtime addon in
  `config/champions-mod/java/`. The existing addon workflow remains available
  through `java start`, `java reload`, and `java stop`.
