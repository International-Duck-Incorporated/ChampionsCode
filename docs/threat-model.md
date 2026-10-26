# Threat model

What the execution gate is for, what it is not for, and which claims it can support.

The short version: **the gate is a safety control, not a sandbox.** It exists so that a
module, a payload or an automation cannot do something destructive by accident or by
being written badly. It is not a defence against code running in this process that is
actively trying to defeat it, and it does not make a hostile payload safe.

## Assets

| Asset | Why it matters |
| --- | --- |
| the target server | reaching a privileged sink on someone's server is the harm this project exists to avoid |
| the operator's client and machine | a payload is not allowed to run commands, read files or open sockets |
| the meaning of the verdict | a report that says "verified" when nothing was verified is the failure mode of every scanner |
| the audit trail | a wrong decision has to be explainable afterwards |

## Trust boundaries

| Component | Trusted? | Why |
| --- | --- | --- |
| `ExecutionGate` | yes — it is the control | small, pure, one `decide` method |
| `ReproductionEngine` | yes | the only class that performs effects, and only after a decision |
| fixtures | yes, for what they model | they are this build's code; their fidelity is declared and reported |
| payload providers (`PortedPayloads`) | **partly** | first-party, but *trusted to be pure*: see "planned, not solved" below |
| mechanisms | **partly** | they run inside the fixture boundary; their success claims are cross-checked, not believed |
| third-party Java modules (`mods/`) | no | they are user code; the gate stands between them and every effect |
| a modified jar | no | see "what the gate cannot do" |
| the JVM / reflection | no | out of scope by construction |

## What the gate guarantees

These are enforced in code and covered by `GateIntegrityTest`, `ExecutionGateTest` and
`VerificationFlowTest`.

1. **A permitting decision cannot be manufactured through the public API.** `GateDecision`
   is a sealed interface with private implementations; `permit(...)` requires a token that
   `ExecutionGate` holds in a private field and never returns.
2. **No effect happens without a decision.** `attemptOnFixture` throws
   `IllegalStateException` without an allowed decision, and it is the only path to a sink.
3. **Execution-capable payloads never run against a live server**, whatever the operator
   authorises. The gate refuses `LIVE` and `NONE` for that kind, in that order, before
   anything else is considered.
4. **The decision is about a value, and values do not change.** Every `PayloadMaterial` is
   a record with final components; the plan carries those objects, and execution runs
   exactly them. An approval cannot go stale through mutation because there is nothing to
   mutate.
5. **An approval is not transferable.** Each stage is re-gated immediately before it runs,
   with its own request. The plan the operator acknowledges is the plan that executes;
   the module refuses `ACK` when the configuration changed since the plan was shown.
6. **A provider that misbehaves is refused, not obeyed.** Throwing, returning null,
   attempting to mutate the options map, or answering differently on a second call all
   end in a refusal or in the planned material running — never in the second answer
   running.

## What the gate cannot do — planned, not solved

These are limits of an in-process control, stated here rather than discovered later.

**Reflection.** Code that can call `setAccessible(true)` on `ExecutionGate.AUTHORITY` can
mint its own approvals. A JVM without module encapsulation cannot prevent this, and
pretending otherwise would be the kind of claim this document exists to avoid. The
boundary is drawn at "the public API cannot forge an approval"; anything with reflective
access to the gate's internals is already able to rewrite the gate.

**Providers are trusted to be pure during planning.** `plan()` calls `material()` and
`control()` to show what would run. A provider with side effects therefore acts while the
operator is reading, before any acknowledgement. The shipped providers are this build's
own classes and the interface documents the requirement, but it is a promise, not a
mechanism. A third-party provider would need review. What the engine does guarantee is
that planning executes no *stage*: the fixture is untouched and no sink is entered, which
is what `aProviderThatActsWhilePlanningIsNotInvisible` asserts.

**A modified build is a different program.** The gate constrains what this code does. If
someone edits the source, replaces the jar, or registers a mechanism that ignores its
fixture, the controls they bypassed are not evidence of anything. The `--self-test` and the
test suite are the check for that, and neither is signed.

**The gate does not make a payload safe.** It decides *whether* an operation may happen and
*where*, not whether the operation is a good idea. A payload can still be useless,
misleading, or wrong about what it demonstrates; that is what the control stages and the
verdict ladder are for.

**Fixtures are not sandboxes either.** `outbound_fetch` binds a loopback socket and writes
an HTTP response; `serialisation` runs a real `ObjectInputStream`. Both are confined to
this process and to loopback, and both can be blocked by an environment (no loopback, a
security manager, a restricted sandbox), in which case the fixture reports the weaker
fidelity rather than the stronger claim.

## What each verdict is entitled to rest on

| Verdict | Evidence required | Not evidence for |
| --- | --- | --- |
| `FIXTURE_VERIFIED` | an effect the control did not produce, naming the payload's own data, on every required sample, through the same sink operation | any implementation, any build, any server |
| `IMPLEMENTATION_VERIFIED` | the same, where **every** observation ran a real implementation of the pattern | that a named plugin release ships that code unpatched |
| `TARGET_VERIFIED` | the authorised target echoing a control and a marker inside the bounded window | the target's state outside the run, or the plugin bug under test |

The tier is decided by `ReproductionEngine.evidenceTier`, which reads the fidelity of each
attempt rather than the best one, and one modelled observation drops the claim to the
model.

## Testing this document

| Claim | Test |
| --- | --- |
| no forged approvals | `GateIntegrityTest.anApprovalCannotBeManufacturedThroughThePublicApi` |
| the token is not handed out | `GateIntegrityTest.theAuthorityTokenIsNotReachableFromOutsideTheGate` |
| material cannot change after approval | `everyPieceOfMaterialIsAValueThatCannotChangeAfterApproval`, `aProviderThatChangesItsAnswerDoesNotChangeWhatRuns` |
| a provider cannot switch authorisation mid-run | `aProviderThatSwitchesKindAfterApprovalCannotSmuggleAnExecutionContext` |
| misbehaving providers are refused | `aProviderThatThrowsWhileProducingMaterialIsRefused`, `...AControlIsRefused`, `aProviderThatProducesNothingIsRefused`, `aProviderCannotMutateTheOptionsTheNextStageReads` |
| planning runs no stage | `aProviderThatActsWhilePlanningIsNotInvisible`, `planningExecutesNothing` |
| no live execution, ever | `anExecutionCapablePayloadCannotRunLiveWhateverTheAuthorisation` |
| acknowledgements are not transferable | `ChampionConsoleTest.verificationReleasesThePlanThatWasShownAndNothingElse` |
| tier 2 needs every observation to be real | `tierTwoRequiresEveryObservationToHaveRunRealCode` |
