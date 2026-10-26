# Verification audit

Five questions about the verification flow, asked before it was called done, and what
reading the code actually found. Every claim below points at a file and a test.

Status: **four of the five were real defects**, one was a design gap that the code had
papered over with a prose note. All five are now enforced in code and covered by tests.

---

## 1. Control validity — does the control traverse the same vulnerable path?

**Before.** The control was the right idea with nothing behind it. `plan()` compared the
control's *shape* to the payload's and stopped there. Nothing compared what the two
actually did, so a control that took a different route through the fixture — or one that
was simply a different string — would still have been reported as the discriminator.

**Found.** Two concrete holes:

- no check that the control enters the same sink operation;
- no check that the control is *the payload minus the trigger* rather than an unrelated
  value. A control that does its own work can make any payload look discriminating.

**Now.**

- `VulnerableFixture.trail()` records the sink operation entered on each attempt
  (`runConsoleEffect`, `findByName`, `loadUnsafe`, `readUnsafe`, `fetch`, `runAlias`,
  `evaluate`). The engine compares the control's trail with every payload attempt's and
  reports `INCONCLUSIVE` when they differ — `report.samePath()`.
- The control's effects must be a **subset** of the payload's
  (`ReproductionEngine.judge`). A control that produces an effect the payload does not
  is not a control, and the run says so instead of crediting the difference.
- A control identical to the payload is refused at plan time, before anything runs.

Tests: `controlTravelsTheSameSinkOperationAsThePayload`,
`aControlThatIsThePayloadIsRefusedRatherThanRun`.

**Still a property of the provider, not a proof.** No black-box check can establish that
two inputs differ *only* in one property. What the engine can do is refuse the cases
where they demonstrably differ in more: different shape, different sink operation,
control effects outside the payload's, or identical material. The claim that the control
is the payload with the separator removed is made by the provider and testable per
payload — `ConsoleInjectionPayload.control()` now returns the marker alone, so the
payload's second dispatch is the only difference.

---

## 2. Sink observation — is the system observing the sink?

**Before.** No. `attemptOnFixture` read `outcome.reachedSink()`, a boolean the *mechanism*
computed and returned. The mechanism's heuristic was `after.size() > 1` for the console
sinks: more than one dispatch counted as success, whether or not the injected command was
the payload's. The engine never looked at the fixture itself.

**Found.** The verdict was made of a mechanism's opinion. A mechanism with a bad
predicate, or one that reported success it could not show, would have produced
`VERIFIED` with no observation behind it.

**Now.** The engine reads the sink and treats the mechanism as a witness, not a judge:

- `VulnerableFixture.recorded()` is specified as **privileged effects**, not inputs.
  `SqlConcatenationFixture` records bypasses (all rows returned) and keeps statements in
  `statements()`; `PermissionAliasFixture` records escalations only; `OutboundFetchFixture`
  records arrivals, not attempts. An empty effect list means the privileged action did not
  happen.
- Attribution is two conditions, both necessary: the control did not produce the effect,
  **and** the effect names the payload's own data (`PayloadMaterial.marker()`).
- Effect comparison normalises the marker away, so "returned 2 rows for …alice" and
  "returned 2 rows for …bob" compare equal — otherwise two different strings would pass
  for two different behaviours.
- `StageOutcome.mechanismClaim()` keeps the mechanism's opinion next to
  `StageOutcome.reached()`, which is the engine's. When they disagree the report says so
  and the engine's observation wins.

Tests: `theVerdictIsBuiltFromWhatTheSinkRecordedNotFromTheMechanismsOpinion` (the
mechanism claims success; the effects name nothing of the payload's; verdict is
`INCONCLUSIVE`, not verified), `theEffectListIsTheSinksOwnRecord`,
`aSinkThatBehavesTheSameForBothValuesIsInconclusiveNotVerified`.

**Still not proof of a plugin.** This is observation of *this build's* sink model. Which
is what question 4 is about.

---

## 3. Repeat validity — are two observations enough?

**Before.** `REPEAT` was hard-coded once, for every mechanism. Two runs for a dispatch and
two runs for a race were the same number, and nothing said which case applied.

**Found.** A constant claiming to be a sample size. Two successes cannot certify a
timing-dependent bug, and the code did not distinguish.

**Now.**

- `ExploitMechanism.determinism()` declares `DETERMINISTIC` (2 samples) or `INTERMITTENT`
  (5). `Determinism` carries the minimum per case.
- The plan publishes the count and the reason (`plan.samples()`, `plan.determinism()`), so
  an operator sees "2 samples because the mechanism is declared deterministic".
- The verdict requires **every** required sample to land: `attributed == requiredSamples`.
  A partial sample is `INCONCLUSIVE`, with a note that says either "intermittent: k of n"
  or "declared deterministic but did not land every time — a timing dependency the
  catalogue does not know about".
- Every mechanism in this build declares `DETERMINISTIC`, because every one of them
  dispatches to a sink in the same thread with the same input. That is a claim, and it is
  now a claim with a number attached that a reviewer can disagree with.

Tests: `theSampleCountFollowsTheMechanismsDeclaredDeterminism`; the
`attributed == requiredSamples` assertion in `realCodeProducesAnImplementationVerdictAndAModelDoesNot`.

**Still limited.** Five samples are not a rate measurement. An `INTERMITTENT` mechanism
gets a declared sample and an honest k/n, not a confidence interval, and the module says
so.

---

## 4. Verdict accuracy — can `VERIFIED` distinguish a model from the real thing?

**Before.** No. There was one `VERIFIED` and a `claimCeiling` string beside it. A model and
a real `ObjectInputStream` produced the same state and differed only in a sentence a
reader had to interpret, and `MODE` let an operator *request* implementation-grade
evidence with nothing checking that the code could supply it.

**Found.** The verdict conflated the two, and the prose was the only thing separating them.
A report could not be filtered, compared, or asserted against.

**Now.** Three states, and the state is what carries the limit:

| State | Established by | Does not establish |
| --- | --- | --- |
| `FIXTURE_VERIFIED` | the payload reaching a **modelled** sink | anything about any implementation |
| `IMPLEMENTATION_VERIFIED` | **real code** that implements the pattern doing the privileged thing | that a named plugin build ships it unpatched |
| `TARGET_VERIFIED` | the authorised target responding | the target's state outside the run |

- `ReproductionState.claim()` derives the sentence **from the state**, so the two cannot
  drift apart. `claimCeiling` is gone.
- `ReproductionState.tier()` orders them 1 < 2 < 3.
- `ReproductionMode.IMPLEMENTATION` exists so an operator can *demand* implementation-grade
  evidence. The gate refuses it when the mechanism has no reference implementation:
  "worldguard_yaml runs against a modelled sink; there is no real implementation of this
  pattern in the build, so an implementation-grade claim cannot be honoured."
- The state follows the code that ran, not the mode that was requested: `mythicmobs_deser`
  reports `IMPLEMENTATION_VERIFIED` even in `MODE FIXTURE`, because a real
  `ObjectInputStream` is what ran.

Tests: `aModelledSinkCannotProduceAnImplementationGradeVerdict`,
`realCodeProducesAnImplementationVerdictAndAModelDoesNot`.

**Which mechanisms can reach tier 2.** Two of ten, and only because the code is genuinely
real:

| Fixture | Fidelity | Why |
| --- | --- | --- |
| `serialisation` | `REFERENCE_IMPLEMENTATION` | a real `ObjectInputStream` with a real `resolveClass` |
| `outbound_fetch` | `REFERENCE_IMPLEMENTATION` for loopback hosts | a real socket, a real HTTP request, observed by the server |
| the other five | `MODELLED` | a recorder stands in for the plugin |

`outbound_fetch` degrades to `FIXTURE_VERIFIED` when it cannot bind a loopback service,
and *says* so rather than reporting tier 2 with a footnote.

---

## 5. Gate integrity — can anything bypass a per-stage check, or run while planning?

**Before.** Three gaps, in increasing order of seriousness.

1. **`GateDecision` was a plain record.** `new GateDecision(true, "trust me", kind)` compiled
   anywhere and was indistinguishable from a decision the gate made. The engine checked
   `.allowed()` and ran. The gate was advisory.
2. **Planning re-derived material; execution re-derived it again.** Both were gated, so it
   was not a bypass, but the material the operator read in the plan and the material that
   ran were two separate calls. A provider that answered differently on the second call
   changed what executed after the gate had approved something else.
3. `ReproductionEngine.attemptOnFixture` did not require a decision at all; it was private
   and called from the gated path, which is a convention rather than a guarantee.

**Now.**

- `GateDecision` is a **sealed interface** whose implementations are private.
  `GateDecision.permit(...)` requires a token that `ExecutionGate` holds in a private field
  and never returns; anything else throws. Refusals carry no authority and stay free to
  construct. `attemptOnFixture` throws `IllegalStateException` without an allowed decision.
  Reflection could still defeat this; a process that can reflect into the gate can rewrite
  it, and the threat model is a module or a payload, not the JVM.
- The plan **carries the material objects**. Execution gates and runs exactly those.
- Every stage is re-gated immediately before it runs, with its own request, so an approval
  is never reused across stages.
- Planning still runs nothing: `plan()` executes no stage, and the module's `effect()`
  returns `none` for `ACTION=PLAN`.

Tests: `anApprovalCannotBeConstructedOutsideTheGate`,
`everyStageCarriesItsOwnDecisionAndNothingRunsWhenOneIsRefused`,
`theMaterialThatRunsIsTheMaterialThatWasShown`, and the pre-existing
`planningExecutesNothing`.

**One thing that is not a bypass but is worth naming:** `plan()` calls the provider's
`material()` and `control()` to show them. A provider with side effects would therefore act
during planning. The providers are this build's own classes (`PortedPayloads`), the
interface documents them as pure, and the plan/show path is the only place a provider is
called before execution — but a third-party provider would need review, not a promise.

---

## What the ladder still cannot say

- **A fixture verdict is about the seam.** `FIXTURE_VERIFIED` is a statement about this
  build's model, and no amount of sampling changes that. Tier 2 moves the claim to real
  code, not to a plugin.
- **`IMPLEMENTATION_VERIFIED` is about the pattern.** Two fixtures run real code; neither is
  the named plugin. Tier 3 is the only one that says anything about a server, and it
  requires an authorised target and bounded payloads.
- **Attribution is as good as the marker.** If a payload's effect never names the payload's
  own data, the run is `INCONCLUSIVE` by design — it is better to admit the effect cannot be
  tied to the input than to credit it.

Verified: 191 JUnit tests (23 new in this pass), 128 self-test checks, clean
`gradlew build`, and the console flow exercised for all six outcomes (`FIXTURE_VERIFIED`,
`IMPLEMENTATION_VERIFIED`, `INCONCLUSIVE`, `NOT_REPRODUCED`, `BLOCKED_BY_GATE` for a
modelled sink asked for tier 2, `BLOCKED_BY_GATE` without `ACK`).
