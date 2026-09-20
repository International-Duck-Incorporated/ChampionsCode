# Runtime Java developer system

ChampionsCode can compile and load a local set of real Java source files while the
Minecraft client is running. This replaces ChampionScript and the old Volt
interpreter.

This feature is unrestricted code execution inside the Minecraft JVM. It is not
a sandbox. Loaded code can access Minecraft, other mods, files, credentials,
networking, reflection, process APIs, native libraries, and any other capability
available to the game process. Only run source that you wrote or fully reviewed.

## Requirements

- Minecraft Java 26.3
- A full JDK 25 installation configured as the Java executable for Minecraft
- `developerMode=true` in `config/champions-mod/java-runtime.properties`
- Confirmation of the exact current source-set SHA-256

A stripped Java runtime does not provide `javax.tools.JavaCompiler`; compilation
will remain unavailable until the game is launched with a full JDK 25.

## Workspace

ChampionsCode creates:

```text
.minecraft/config/champions-mod/
├── java-runtime.properties
└── java/
    ├── champion-java.json
    ├── Example.java
    └── .build/
```

All `.java` files below `java/`, excluding `.build/`, are compiled together.
The source set is limited to 128 files and 2 MiB. Symbolic-link source files are
rejected so the confirmation hash covers the files that will be compiled.

Generated classes are written beneath `.build/`. That directory is managed by
ChampionsCode and must not contain source files.

## Manifest

`champion-java.json` identifies the runtime addon and its entrypoint:

```json
{
  "id": "local-runtime",
  "name": "Local Runtime Addon",
  "version": "0.1.0",
  "entrypoint": "champions.runtime.Example"
}
```

The entrypoint must be a public class with a public no-argument constructor that
implements `dev.champions.api.java.ChampionRuntimeAddon`.

## Entrypoint

```java
package champions.runtime;

import dev.champions.api.java.ChampionJavaContext;
import dev.champions.api.java.ChampionRuntimeAddon;

public final class Example implements ChampionRuntimeAddon {
    @Override
    public void start(ChampionJavaContext context) {
        context.info("Connected to " + context.snapshot().serverAddress());
        context.onStop(() -> context.info("Cleanup completed"));
    }

    @Override
    public void stop() {
        // Release resources owned by this addon.
    }
}
```

`ChampionJavaContext` provides convenience access to:

- a current `ModuleContext` snapshot;
- the Java workspace path;
- the confirmed source hash;
- console information, warning, and error output;
- client-thread scheduling;
- cleanup callbacks that run during stop or reload.

`start()` and `stop()` run on the Minecraft client thread. Addon-created worker
threads should use `executeOnClient` before reading or mutating client-owned game
objects. The convenience `snapshot()` call should also be made from the client
thread.

These conveniences do not restrict direct JVM or Minecraft access.

## Java Studio

Press **F7** to open the dedicated ChampionsCode Java Studio. The movable,
resizable purple workspace provides:

- a multiline editor for `.java` files and `champion-java.json`;
- a workspace file list;
- Save, Compile, Start/Reload, and Stop actions;
- runtime output and a command field;
- minimize, maximize, and close controls.

The editor is restricted to supported files below the Java workspace and never
edits `.build/`. Save with the button or Ctrl+S. Saving records the file name and
new source hash in the audit log, but not the source content. Unsaved changes
must be saved or discarded before switching files.

First display the settings path from the Java Studio command field:

```text
settings
```

Edit `java-runtime.properties`:

```properties
developerMode=true
confirmedSourceHash=
```

Reload settings and inspect the source hash:

```text
settings reload
hash
```

Review every source and manifest file, then confirm the exact displayed hash:

```text
confirm <64-character-sha256>
```

Compile without starting:

```text
compile
```

Compile, load, and start:

```text
start
```

After editing any source, the hash changes and the old confirmation becomes
invalid. Review the changes, confirm the new hash, then run:

```text
reload
```

Stop and close the active classloader:

```text
stop
```

Additional commands:

```text
status
sources
classpath
diagnostics
help
```

The same operations remain available from the F8 terminal with a `java` prefix,
for example `java status` or `java reload`.

## Compilation and loading

ChampionsCode uses the standard JDK 25 compiler with annotation processing disabled.
It compiles the source set on a dedicated daemon thread against the current game
and mod classpath. Successful classes are loaded through a fresh child
`URLClassLoader` whose parent is the running Fabric game classloader.

`reload` compiles and validates the replacement before stopping the old addon.
It then calls the old `stop()`, runs registered cleanup callbacks, closes the old
classloader, creates the new entrypoint, and calls `start()` on the client thread.

Class unloading and cleanup are best-effort. Java classes unload only after no
live references remain. Fabric callbacks often cannot be unregistered, so an
addon that directly registers persistent callbacks may remain reachable after
reload. Runtime addons must design their own listeners, threads, and resources
for explicit cleanup. Restart Minecraft if clean unloading is uncertain.

## Audit behavior

ChampionsCode records source-hash confirmation, compilation, start, stop, and cleanup
outcomes in `audit.jsonl`. Source contents are not copied into the audit log.
The source confirmation is a deliberate-review control, not a security sandbox
or a guarantee that the code is safe.
