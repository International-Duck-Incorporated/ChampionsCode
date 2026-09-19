# Roadmap

## 0.1 foundation (implemented)

- Minecraft 26.3/Fabric client project
- Movable, resizable F8 terminal with selectable and copyable output
- Sorted, movable, resizable ClickGUI module and option browser opened from the terminal
- Built-in passive modules
- One bounded active module with SILENT marking and filtered AGGRESSIVE blank/version/v probes
- Payload-free packet flow and bounded metadata timeline
- Read-only container integrity and client resilience audits
- Passive menu-action/update class correlation with no packet manipulation
- Passive, non-verdict remote-player motion evidence
- In-game exact-target authorization management, one-run confirmation, and audit log
- Hash-gated runtime Java addon compilation with a persistent reload lifecycle
- Unit tests and CI build

## Candidate 0.2 work

- Export redacted findings as JSON and Markdown
- Per-module risk/capability declarations shown in the UI
- Better searchable output and copy-to-clipboard controls
- Runtime Java lifecycle diagnostics and safer callback cleanup patterns
- Offline analysis of administrator-supplied server/plugin inventories
- Test-server fixtures for reproducible plugin configuration audits

## Out of scope

- Downloading, remotely supplying, or automatically starting runtime Java
- Runtime JAR, Lua, JavaScript, native library, or remote-code loaders
- Third-party server exploitation or indiscriminate scanning
- Credential/session-token handling
- Stealth, anti-cheat bypass, persistence, or destructive modules
- Network behavior that is not explicit, bounded, authorized, and auditable
