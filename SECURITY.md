# Security policy

ChampionsCode is a defensive research tool. Only test systems you own or have
explicit permission to assess.

Do not publish a suspected vulnerability in a Minecraft server plugin before
the maintainer has had a reasonable opportunity to investigate and patch it.
A useful private report includes the affected version, configuration,
reproduction steps against a local test server, impact, logs, and a proposed
mitigation. Remove player data, tokens, addresses, and other secrets.

The project does not accept built-in research modules for credential theft,
stealth, destructive exploitation, denial of service, authorization bypass,
anticheat-bypass development, or testing unrelated third-party systems. Packet
telemetry must remain metadata-only and bounded: no packet bodies, chat contents,
NBT, authentication/session data, or encryption material. Online packet
cancellation, delayed flush queues, payload editors, click fabrication, GUI
desynchronization, and duplication/race automation remain out of scope.

## Exploit research warning

Entering an Exploits interface and every `ExploitResearchModule` run require
separate legal-risk acknowledgements. Acknowledgement is audited but does not
establish ownership, written permission, legal compliance, or safe operation.
Exact-host authorization and one-run confirmation still apply after the popup.
The marker groups modules and adds the warning; it grants no extra capability.
See `docs/exploit-research.md` for the intake and review requirements.

## Runtime Java warning

The optional runtime Java developer system is a deliberate exception to the
built-in module capability boundary. It compiles local source with a full JDK 25
and executes the resulting classes inside the Minecraft JVM. It is not sandboxed
and can access files, credentials, networking, reflection, processes, native
libraries, game state, packets, and every loaded mod.

Runtime Java is disabled by default. Enabling it requires a manual developer-mode
setting and confirmation of the exact current source-set SHA-256. The hash gate
records review intent but cannot make hostile Java safe. Never execute downloaded
or unreviewed source. Reports involving a user intentionally enabling and running
untrusted Java are not sandbox escapes because no sandbox is claimed.
