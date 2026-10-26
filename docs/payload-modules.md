# The three tiers: check, verify, payload

A finding without a reproduction is a rumour. ChampionsCode separates what a
module can do into three tiers, and the tier decides what evidence a result
carries and how much ceremony surrounds it.

| Tier | Interface | What it does | Evidence |
| --- | --- | --- | --- |
| **check** | `ResearchModule` | Reads the `ModuleContext` snapshot and reports what it suggests. Sends nothing. | Inferred, marked as such |
| **verify** | `ActiveResearchModule` | Sends one bounded request and records the response. | Observed |
| **payload** | `PayloadModule` | Performs the smallest observable action that shows the issue is real, declared in advance, then reverts anything it created. | Confirmed |

## Why a payload tier exists

"Your server advertises `/plugins`" is a check. "/plugins returned this list,
which names WorldEdit 7.3.0, which is in a known CVE range" is a verify. "A
plain `/say` line from this account was accepted and echoed by the server" is a
payload — and it is the difference between a report someone acts on and one they
file.

The tier is deliberately narrow. A payload proves **reach**, not damage:

- allowed: one visible chat command from a short allowlist (`say`, `me`, `list`,
  `help`, `seed`, `time query`, `gamemode query`), or one small marker file inside
  the audit folder
- refused: anything that grants, gives, kills, teleports, kicks, edits world
  state, or writes outside the audit folder

Nothing that destroys data, duplicates items, floods a server, breaks a chunk, or
gains a gameplay advantage is in the vocabulary, and an effect that does not
match the vocabulary is refused rather than reinterpreted as a "probe".

## What runs around a payload

A payload is an `ExploitResearchModule`, so it inherits the full ceremony:

1. The declared effect is printed **before** the authorization gate, and checked
   by `PayloadSafety`. An unacceptable declaration is refused before anything is
   sent.
2. The per-run legal-risk acknowledgement, as for any exploit-research module.
3. Exact-host authorization and the one-run `CONFIRM` phrase, revalidated after
   the acknowledgement.
4. One bounded action, sent through the client runtime's `PROOF` path.
5. An audit line that names the declared effect and the revert plan.
6. Findings and evidence, with an explicit statement of what the run does *not*
   prove.

## The runtime path

`ActiveModuleRuntime.ChatCommandRequest` carries an `Origin`:

- `PROBE` — walks the advertised command surface. Restricted by
  `CommandSafetyPolicy.mayProbe()` to **non-default** roots, so an audit never
  pokes at vanilla commands.
- `PROOF` — one allowlisted, state-neutral command. Cleared by `PayloadSafety`
  instead, because the proof *is* the use of a vanilla command such as `/say`.

Both are validated again inside `ChatCommandRuntime` immediately before
`sendCommand`, so the check happens where the send happens rather than trusting
the caller. A `PROOF` request for `give` or `op` is rejected by the constructor
and again by the runtime.

## Reference module

`auxiliary/payloads/chat_reach_proof` is the worked example: one `/say` line with
a marker token, and the proof is the echo coming back inside the bounded capture
window. It reports `HIGH` when the echo arrives, `MEDIUM` when the command was
accepted but no echo was captured, and names the reason when the proof did not
run at all — it never assumes success.

## Writing one

```java
public final class MyProofModule implements PayloadModule {
    @Override public String id() { return "auxiliary/payloads/my_proof"; }
    @Override public String name() { return "My proof"; }
    @Override public String description() { return "..."; }
    @Override public PayloadEffect effect() {            // constant, declared up front
        return PayloadEffect.chatCommand("/say my-proof-marker");
    }
    @Override public String revertPlan() { return "nothing to revert"; }
    @Override public CompletionStage<ModuleResult> runActive(
            ModuleContext context, Map<String, String> options, ActiveModuleRuntime runtime) {
        return runtime.execute(ChatCommandRequest.proof("say", List.of("my-proof-marker"), 60, 4))
            .thenApply(transcript -> /* build findings from what came back */ null);
    }
}
```

Payload modules live in-tree (`core/module/builtin/`), not in the drop-in
collection: the collection forbids `java.net` and `java.io` on purpose, and a
payload needs the runtime. That is the structural reason a "heavy" module is a
built-in.
