# Roadmap

## 0.1 foundation (implemented)

- Minecraft 26.3/Fabric client project
- Movable, resizable F8 Linux-style terminal with selectable and copyable output
- Dedicated F7 purple Java Studio with bounded workspace editing and runtime controls
- Desktop-style minimize, maximize/restore, and close controls on primary windows
- Sorted-list ClickGUI plus a separate movable multi-window category and settings interface
- Built-in passive modules
- One bounded active module with SILENT marking and filtered AGGRESSIVE blank/version/v probes
- Payload-free packet flow and bounded metadata timeline
- Read-only container integrity and client resilience audits
- Passive menu-action/update class correlation with no packet manipulation
- Passive, non-verdict remote-player motion evidence
- In-game exact-target authorization management, one-run confirmation, and audit log
- Hash-gated runtime Java addon compilation with a persistent reload lifecycle
- Clean-room older-project ports for plugin review, declared dependency posture,
  defensive chat normalization, and non-solving chat-game fairness analysis
- Separately warned Exploits workspace with entry and per-run legal-risk popups
- Minecraft 26.3 one-based pointer handling for movable terminal and GUI windows
- CI build plus automatic immutable-tagged GitHub prereleases on main pushes
- Unit tests

## Candidate 0.2 work

- Export redacted findings as JSON and Markdown
- Per-module risk/capability declarations shown in the UI
- Better searchable output and copy-to-clipboard controls
- Runtime Java lifecycle diagnostics and safer callback cleanup patterns
- Offline JAR/SBOM import for administrator-supplied inventories, building on
  the implemented bounded text inventory review
- Test-server fixtures for reproducible plugin configuration audits

## maybe?
### 1. **`Cheat detection`**
* **Purpose:** Detect cheaters client side.
* **Functionality:** basically a anitcheat inside of the mode.
* **Configurable Settings:** not sure

## Out of scope

- Downloading, remotely supplying, or automatically starting runtime Java
- Runtime JAR, Lua, JavaScript, native library, or remote-code loaders
- Third-party server exploitation or indiscriminate scanning
- Credential/session-token handling
- Stealth, anti-cheat bypass, persistence, or destructive modules
- Network behavior that is not explicit, bounded, authorized, and auditable
