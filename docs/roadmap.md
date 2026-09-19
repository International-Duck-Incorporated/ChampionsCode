# Roadmap

## 0.1 foundation (implemented)

- Minecraft 26.3/Fabric client project
- F8 research GUI with separate Console and Volt tabs
- Built-in passive modules
- One bounded active module with SILENT marking and filtered AGGRESSIVE blank/version/v probes
- Payload-free packet flow and bounded metadata timeline
- Read-only container integrity and client resilience audits
- Passive menu-action/update class correlation with no packet manipulation
- Passive, non-verdict remote-player motion evidence
- Exact-target allowlist, one-run confirmation, and audit log
- Read-only ChampionScript runtime
- Unit tests and CI build

## Candidate 0.2 work

- Export redacted findings as JSON and Markdown
- Per-module risk/capability declarations shown in the UI
- Better searchable output and copy-to-clipboard controls
- Declarative, data-only module definitions (no arbitrary class loading)
- Offline analysis of administrator-supplied server/plugin inventories
- Test-server fixtures for reproducible plugin configuration audits

## Out of scope

- Arbitrary code/JAR/Lua/JavaScript loading
- Third-party server exploitation or indiscriminate scanning
- Credential/session-token handling
- Stealth, anti-cheat bypass, persistence, or destructive modules
- Network behavior that is not explicit, bounded, authorized, and auditable
