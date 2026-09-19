# ChampionScript (Volt runtime)

ChampionScript is a bounded, read-only language evaluated inside the running
Champions client. The GUI exposes it in a dedicated **Volt** tab. This is runtime
evaluation, not Java/JVM injection: it cannot load classes, call Minecraft
methods, install mixins, use reflection, open sockets, send packets or chat,
launch processes, or mutate game state.

## Using the Volt tab

Press **F8**, select **Volt**, and enter ChampionScript directly:

```text
print "brand=" + server.brand; assert connected
```

The tab also recognizes these script-management shortcuts:

```text
help
list
show demo
run demo
eval print "fps=" + client.fps
```

They map to the console commands `volt help`, `volt list`, `volt show`,
`volt run`, and `volt eval`. Output is written to the shared audited console
transcript.

Script files use the `.champ` extension and live under:

```text
.minecraft/config/champions-mod/scripts/
```

Only simple names containing letters, digits, `_`, or `-` are accepted. Paths
cannot escape that directory.

## Statements

Statements are separated by a newline, carriage return, or semicolon. `#`
starts a comment outside quoted text.

```text
help
status
server
player
position
modules
print <expression>
notify <expression>
let <name> = <expression>
get <name>
assert connected
assert disconnected
assert online_mode
```

Variables are strings and exist only for the current execution. Variable names
must begin with a letter or underscore and may then contain letters, digits, or
underscores, up to 64 characters.

## Expressions

Expressions concatenate terms with `+`:

```text
let target = server.address
print "target=" + $target + " brand=" + server.brand
```

A term may be:

- a single- or double-quoted string;
- a variable reference such as `$target`;
- one of the runtime values below.

Quoted strings support `\n`, `\t`, escaped quotes, and escaped backslashes.
There is intentionally no arithmetic, loop, function, import, object, or method
call syntax.

## Runtime values

```text
server.address
server.brand
server.version
server.protocol
server.players
server.ping
player.name
player.position
player.dimension
client.connected
client.fps
true
false
```

## Bounds

Each execution is limited to:

- 16 KiB of source (the single-line GUI field accepts up to 2,048 characters;
  `.champ` files can use the full source budget);
- 256 statements;
- 8 KiB of output;
- approximately 50 ms of interpreter time;
- 1,024 characters per evaluated expression.

Scripts fail closed on unknown statements, values, variables, assertions,
unclosed quotes, or exceeded budgets. These restrictions are part of the
security boundary and must not be bypassed by modules or addons.
