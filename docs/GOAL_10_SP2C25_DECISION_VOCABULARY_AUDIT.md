# Goal 10 / SP2c.25 — `RuleBasedDispatcher` Decision-Vocabulary Audit

**Issue:** [#848](https://github.com/bedaHovorka/interlockSim/issues/848) (SP2c.25) ·
sub-issue of [#822](https://github.com/bedaHovorka/interlockSim/issues/822) (Goal 10 SP2c)

**Status:** ✅ Final — spike deliverable (analysis only, no production code changed).
Gates [SP2c.3](https://github.com/bedaHovorka/interlockSim/issues/822) (the `DispatchAction`
sealed vocabulary + `ActionValidator`) and the SP2c.5 P10 correctness gate (byte-identical
determinism of `RuleBasedDispatcher`'s decision sequence under the new control loop).
**Last Updated:** 2026-07-30

**Findings also posted as comments on
[#822](https://github.com/bedaHovorka/interlockSim/issues/822)** (so the parent’s §5.2 stays
accurate) and the traffic-simulation-expert ruling as a comment on
[#848](https://github.com/bedaHovorka/interlockSim/issues/848) itself. This document consolidates
those comments into a durable, reviewable form (formatting/sectioning may differ from the originals).

---

## 1. Objective

Audit what `RuleBasedDispatcher.decide()` actually emits, and confirm every decision it
produces is expressible in the four-action `DispatchAction` vocabulary proposed by #822 §5.2:
`ApproveTrain(trainId)` · `RequestRoute(trainId, target)` · `CancelRoute(trainId)` ·
`NoOp(reason)`.

## 2. `RuleBasedDispatcher.decide()` is type-restricted to three subtypes

`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/RuleBasedDispatcher.kt:122-125`:

```kotlin
override fun decide(observed: DispatchObservation): List<DispatchDecision> {
	val decisions = decideAdmissions(observed) + checkAllInputs(observed)
	return decisions.ifEmpty { listOf(DispatchDecision.NoAction) }
}
```

- `decideAdmissions` (RuleBasedDispatcher.kt:129-138) is declared `List<DispatchDecision.ApproveTrain>`.
  FIFO-takes up to `freeSlots = maxConcurrentTrains - observed.approvedTrainCount` from
  `observed.unapprovedTrains`, emitting `ApproveTrain(queued.trainId)` at line 136. Empty when
  `freeSlots <= 0`.
- `checkAllInputs` (RuleBasedDispatcher.kt:142-143) is declared `List<DispatchDecision.ReservePath>`,
  built via `(observed.innerBlockInputs + observed.outerBlockInputs).mapNotNull(::checkInput)`.
  - `checkInput` (RuleBasedDispatcher.kt:154-213) is declared `DispatchDecision.ReservePath?`, a
    `when(input.state)` over the three `TrackFacility.State` values:
    - `FREE` (line 156): always `null`.
    - `OCCUPIED` (158-184): `null` unless `isApproachingThisInput && !pathAlreadyExtendedBeyond
      && toSeparatorName != null`; otherwise `ReservePath(trainId, towardSemaphoreName,
      toSeparatorName)` at 178-182.
    - `RESERVED` (186-212): `null` unless `pathSetUpTowardThisInput && !pathAlreadyExtendedBeyond
      && toSeparatorName != null`; otherwise `ReservePath(...)` at 206-210.

Because the composed return value is `List<ApproveTrain> + List<ReservePath>` then
`.ifEmpty { listOf(NoAction) }`, **the type system itself guarantees `decide()` can only ever
return `ApproveTrain`, `ReservePath`, or `NoAction`.** A text grep of `RuleBasedDispatcher.kt`
confirms zero occurrences of `HoldTrain(`, `SetSignalAspect(`, `SetSwitchPosition(`,
`ReleaseRoute(`, or `RequestRoute(`.

## 3. Repo-wide construction-site audit (production code only, tests excluded)

| `DispatchDecision` subtype | Production constructor site(s) | Emitted by `RuleBasedDispatcher`? |
|---|---|---|
| `ApproveTrain` | `RuleBasedDispatcher.kt:136`; `dispatcher-agent/.../planner/KoogAgentPlanAdapter.kt:282` (LLM admission safety net, `maybeForceAdmission` — the one #822 §6 marks for deletion); `dispatcher-agent/.../DefaultDispatchLoopActuatorPort.kt:46` (`approve_train` LLM tool) | **Yes** |
| `ReservePath` | **`RuleBasedDispatcher.kt` only** (lines 178, 206) — no other production constructor exists anywhere in the repo | **Yes** |
| `NoAction` | `RuleBasedDispatcher.kt:124` (the only production constructor; `data object`) | **Yes** — 8 distinct scenarios in `RuleBasedDispatcherTest.kt` (e.g. lines 151-157, 163-169, 233-252) exercise this branch directly |
| `HoldTrain` | **None found anywhere in production code** — all ~25 grep hits for `DispatchDecision.HoldTrain(` are in test files (`DispatchDecisionSp2b1Test.kt`, `DispatchDecisionSp2b5Test.kt`, `SynchronousDispatcherWiringTest.kt:191`, `dispatcher-agent/.../DispatchDecisionApplierTest.kt`, `DispatchDecisionApplierModeGatingTest.kt`, `DispatchDecisionListenerHubTest.kt`) constructing it as a fixture to test the *applier's* handling | **No** |
| `SetSignalAspect` | `core/.../sim/PathCommandTranslator.kt:189,197` | **No** |
| `SetSwitchPosition` | `core/.../sim/PathCommandTranslator.kt:158` | **No** |
| `ReleaseRoute` | `dispatcher-agent/.../agents/tools/CancelRouteTool.kt:70` (LLM `cancel_route` tool) | **No** |
| `RequestRoute` (tool-driven, 3-arg) | `dispatcher-agent/.../agents/tools/RequestRouteTool.kt:132` (LLM `request_route` tool) | **No** |

`PathCommandTranslator` (the only producer of `SetSignalAspect`/`SetSwitchPosition`) is itself
unwired: a grep for `PathCommandTranslator(` / `.translate` outside its own file and test found
only a KDoc mention in `ToolGroupRegistry.kt:191`, no call site. `RuleBasedDispatcher`'s own KDoc
(RuleBasedDispatcher.kt:74-76) confirms this is deliberate-but-incomplete:

> "This per-tick `decide` currently reserves a single pre-computed section per input and does
> not yet route through `CandidatePathRuleEngine`; wiring the engine into multi-route selection
> here is deferred to SP2b.5 (Issue #560)."

So `SetSignalAspect`/`SetSwitchPosition` are unreachable from **any** live production path
today, not merely from `RuleBasedDispatcher.decide()` — consistent with, and stronger evidence
for, constraint C1's design intent that switch/signal control stay unreachable from the agent
by construction.

## 4. `vyhybna.xml` / `shuntingLoopAI` reachability confirmed

`RuleBasedDispatcher()` is instantiated at three production sites, all exercised on
`vyhybna.xml`:

- `desktop-ui/.../ExampleRegistry.kt:284` — `fallbackDispatcher` for the `shuntingLoopAI` GUI
  example (registered `ExampleRegistry.kt:82`)
- `dispatcher-agent/.../di/DispatcherAgentModule.kt:123` — `single<Dispatcher> { RuleBasedDispatcher() }`
- `core/.../sim/SynchronousDispatcherWiring.kt:95`

So `ApproveTrain`, `ReservePath`, `NoAction` are all real, frequently-hit ticks on
`vyhybna.xml` — not theoretical.

## 5. Mapping to the §5.2 four-action vocabulary

| Subtype | Reachable from `RuleBasedDispatcher.decide()` on `vyhybna.xml`? | Maps to |
|---|---|---|
| `ApproveTrain` | Yes | `ApproveTrain` — direct 1:1 |
| `ReservePath` | Yes | `RequestRoute` — **see semantic-merge caveat, §6** |
| `NoAction` | Yes | `NoOp` — direct 1:1 |
| `HoldTrain` | No | N/A for both dispatcher arms (nothing constructs it) |
| `SetSignalAspect` | No | N/A by design (C1) — confirmed unreachable in practice too |
| `SetSwitchPosition` | No | N/A by design (C1) — confirmed unreachable in practice too |
| `ReleaseRoute` | No | Maps conceptually to `CancelRoute`, but not emitted by `RuleBasedDispatcher` |
| `RequestRoute` (tool-driven) | No | `RequestRoute` (already same name) |

## 6. `ReservePath`/`RequestRoute` semantic-merge caveat

`ReservePath` reserves exactly **one section** (pre-computed `fromSemaphoreName` →
`toSeparatorName`) and increments the sim-thread block-transition test counter
(`ShuntingLoop.incrementBlockTransition`). The tool-driven `RequestRoute` reserves an
**end-to-end path** via the interlocking's own route-finding and, by design, does **not**
increment that counter (`DispatchDecision.kt:269-283`). Collapsing both into a single
`DispatchAction.RequestRoute` is a many-to-one semantic merge, not a rename.

## 7. `HoldTrain` — the flagged open question, resolved

**`RuleBasedDispatcher.decide()` never emits `HoldTrain`, and no current production planner
(rule-based or LLM) constructs `DispatchDecision.HoldTrain` anywhere in the repository.** The
applier-side consuming plumbing exists (`TrainLifecyclePort.holdTrain`, applier branches, all
covered by tests that construct the decision directly as a fixture) but nothing produces it.
It is fully dead from a production-code-path perspective today.

Independent re-verification (see §9) additionally found a same-named but **unrelated**
`ConflictResolution.HoldTrain` in `sim/conflict/ConflictResolution.kt`, actively constructed by
`DefaultConflictResolver.generateHoldTrain` (live Goal 9 machinery, a distinct sealed hierarchy
with no bridge to `DispatchDecision.HoldTrain`). The name collision is coincidental and does not
change this finding — it means the *concept* of holding a train is already live under a
different type, relevant to future wiring but not to this audit's scope.

## 8. Recommendation

**(a) four actions suffice, combined with (c)** for the unmappable subtypes: `HoldTrain`,
`SetSignalAspect`, `SetSwitchPosition` are declared out of scope for both dispatcher arms — not
permanently, but because nothing in the current codebase constructs them on a live path, so
building `DispatchAction` cases for them now would be speculative. Cost of (b) [a fifth
action]: none avoided today, since nothing needs it. Cost of (a)+(c): SP2c.3 must carry the
`ReservePath`/`RequestRoute` semantic-merge discriminant through the new vocabulary so P10's
byte-identical determinism gate (SP2c.5) doesn't fail — see §9.

## 9. Traffic-simulation-expert ruling

The ruling required by #848 was recorded on 2026-07-30. It independently re-verified every
file:line citation above against the source and confirmed the audit. Its operative finding is
§10 below: the section-versus-path reservation semantics must be preserved explicitly in the
merged vocabulary, and this must not be deferred to SP2c.5, where it would surface only as a
failing determinism test with no clear root cause.

## 10. Binding constraint for SP2c.3

`DispatchAction.RequestRoute`'s payload must carry an explicit discriminant preserving
`ReservePath`'s single-section/counter-incrementing semantics separately from tool-driven
`RequestRoute`'s end-to-end/non-incrementing semantics, so SP2c.5's P10 byte-identical
determinism gate does not fail on an unreproducible merge.

## Related

- [#822](https://github.com/bedaHovorka/interlockSim/issues/822) — Goal 10 SP2c parent (§5.2
  action vocabulary, this audit's target)
- [#848](https://github.com/bedaHovorka/interlockSim/issues/848) — this spike
- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/RuleBasedDispatcher.kt`
- `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/DispatchDecision.kt`
