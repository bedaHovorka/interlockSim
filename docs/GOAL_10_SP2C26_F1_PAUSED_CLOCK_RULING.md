# Goal 10 SP2c.26 — F1 paused-clock feasibility and R8 headless-pacing ruling

**Issue:** [#849](https://github.com/bedaHovorka/interlockSim/issues/849) (SP2c.26, sub-issue of [#822](https://github.com/bedaHovorka/interlockSim/issues/822))
**Status:** ✅ **COMPLETE** — ruling recorded
**Date:** 2026-08-01
**Authority:** traffic-simulation-expert (simulation timing and event-scheduling correctness)
**Evidence:** `desktop-ui/src/test/kotlin/cz/vutbr/fit/interlockSim/timing/` — all figures below come from
actual runs of those tests, not estimates.

---

## 1. Ruling (summary)

1. **F1 paused-clock is FEASIBLE** as the acceptance timing regime in #822 §5.5, subject to four
   binding constraints (§4). The reproducibility argument behind P8 survives: the simulation clock is
   measurably frozen for the whole emission window, with **zero** measured pause latency.
2. **The deadlock hazard flagged in the #822 design review is REAL** and has been reproduced. It is
   avoided — not by luck but by construction — because SP2c.5 chose optimistic in-process
   re-validation (option A). That choice is now **evidence-based rather than assumed**, which is what
   #849 §2 asked the spike to establish.
3. **R8 resolves as option (a):** headless async runs require a real pacing controller.
   `assertPlannerPacingCompatible` **stays in force** and is **not** relaxed. Option (b) is rejected —
   F1 freezes only the emit window, not the inter-tick interval, so it does not make pacing intrinsic.
4. **R8 is much cheaper to close than #822 assumes.** It is a module-placement problem, not a missing
   capability: `SimulationRunner` already is a fully working headless pacing controller (§5).
5. **#822 §7 A6 is NOT flagged for revision.** F1 is feasible, so the A6 amendment stands as written.
6. **Sequencing confirmed: ship F2 first, F1 second** (#833). F2 has no pause interaction at all.

---

## 2. Why the deadlock is real

`DefaultSimulationContext.advanceControlledStep` calls `controller.awaitIfPaused()` from kDisco's
**before-event** hook (`core/.../context/DefaultSimulationContext.kt:1452`). A pause therefore parks the
kernel *before* it runs the next event, so `ShuntingLoop.iteration()` never executes — and with it
neither `snapshotCaptureHook` nor `DispatcherObservationProjector.captureOnSimThread()`
(`core/.../sim/ShuntingLoop.kt:378`).

Consequence: **anything that pauses the clock and then waits for a fresh observation waits forever**,
because the only thread that could publish that observation is the one the pause just parked.

Measured (`PausedClockCaptureHookTest`, `PausedClockFreshCaptureDeadlockTest`):

| Observation | Result |
|---|---|
| Projector tick advance across a 750 ms pause | **0** (frozen) — at 10x a tick is published roughly every 100 ms |
| Control: tick advance after the pause is released | resumes immediately — the run was alive throughout |
| Fresh capture obtained inside a paused `emit` window | **never** (750 ms bounded wait, through a real `DispatchTickLoop`) |
| Same tick using only the immutable `obs0` it was handed | **completes normally**, ~400 ms emission |

`DispatchTickLoop` is already safe: it reads `obs0` once before `emit`
(`dispatcher-agent/.../DispatchTickLoop.kt:192`) and re-validates via `applyOptimistically`
(`:341`), which is pure in-process work and never touches the sim thread.

---

## 3. Measurements

Every figure below comes from a test under
`desktop-ui/src/test/kotlin/cz/vutbr/fit/interlockSim/timing/`. Run those tests for the current
numbers; the values here are one run on one machine and vary several-fold by hardware. What the
tests enforce are the bounds, not the observed values.

| Question | Test | Result |
|---|---|---|
| Is the simulation clock genuinely frozen while the dispatcher emits? (AC2) | `PausedClockSimTimeInvarianceTest` | **Yes.** Zero measured pause latency over 20 trials at 10x. The test asserts a principled upper bound of one `ShuntingLoop` tick, not the observed zero, so it does not turn brittle on slower hardware. |
| What does a pause/resume cycle cost? (AC3) | `PauseResumeOverheadTest` | Mean 0.29 µs, p99 7 µs, max 28 µs over 200 cycles. The enforced property is **p99 < 1 ms**. Against `ShuntingLoop`'s 2.0-second control step this is immaterial — under one part per thousand. Pause/resume cost is not a design constraint for F1. |
| Does it interfere with the speed multiplier? (AC4) | `PausedClockSpeedMultiplierInteractionTest` | No. Pausing is orthogonal to the multiplier, rate fidelity holds, and no catch-up debt accumulates. Measurement quantum is 0.333 simulated s/s, so rates land on that lattice rather than exactly on the nominal speed. |

**Note on #849's wording.** The criterion as written — "verify `obs.simTime` is genuinely
unchanged" — is vacuous. `DispatcherObservation` is an immutable data class passed to `emit` by
value, so its `simTime` cannot change whatever the simulation does. The spike measured the
simulation clock itself instead, reconstructed from the deltas the controlled event loop reports
to `SimulationController.throttle`, with a running control window measured by the same probe so
that a dead probe cannot masquerade as a frozen clock.


### 3.4 A failure mode the draft ruling omitted

Emission is the step most likely to fail — an LLM call can throw, and `DeadlineTickBudget` abandons it
mid-flight via `withTimeoutOrNull`. `PausedClockResumeOnFailureTest` establishes both halves:

| Scenario | Outcome |
|---|---|
| Throwing emission, resume in `finally` | Exception surfaces; clock resumes; run stays diagnosable |
| Throwing emission, **no** `finally` | **Simulation stays parked indefinitely** |

The second case does not crash — the run silently stops advancing, which is far harder to diagnose than
a thrown exception. Hence constraint C4 in §4.

---

## 4. Binding constraints on any F1 implementation

Any `PausedClockTickBudget` (SP2c.10, #833) **must**:

- **C1** — bracket **only** `EmissionStrategy.emit`. Nothing else may run inside the paused window.
- **C2** — keep intra-tick re-validation on the **optimistic in-process projection** (SP2c.5 option A).
  Real re-projection inside the paused window is the deadlock in §2.
- **C3** — **never** request or await a sim-thread capture while paused, directly or transitively.
- **C4** — release the pause in a **`finally`**, so a thrown or timed-out emission cannot park the
  simulation permanently (§3.4).

---

## 5. R8 — headless pacing ruling

### 5.1 Decision

**Option (a).** Headless async runs require a real pacing controller.
`assertPlannerPacingCompatible` remains in force, unchanged, and continues to reject an async planner
bound to `NoOpSimulationController`. `PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER = 2.0` is
preserved. Clamping the live runner to that value remains SP1.4 (#549); until then the binary guard is
the only enforcement, which is precisely why it must not be weakened.

**Option (b) is rejected on the evidence.** F1 freezes only the emit window. Between ticks
`DispatchTickLoop` still calls `controller.awaitIfPaused()` and `controller.throttle()`
(`DispatchTickLoop.kt:284-286`) and still depends on external pacing. F1 does **not** make tick pacing
intrinsic, so it cannot justify relaxing the guard.

### 5.2 R8 is cheaper to close than #822 assumes

`SimulationRunner` contains **zero** `javax.swing` / `java.awt` imports — verified — and depends only on
`java.beans.PropertyChangeSupport`. It is a complete `SimulationController` with wall-clock throttling
that merely *lives* in `:desktop-ui`. It is GUI-**located**, not GUI-**coupled**.

`HeadlessPacingFeasibilityTest` demonstrates this end-to-end with no Swing, no `Frame`, no EDT:

| Check | Result |
|---|---|
| `assertPlannerPacingCompatible(asyncPlanner, SimulationRunner)` | **passes** headlessly |
| Control: same planner on `NoOpSimulationController` | still **rejected** — the guard is not vacuously passing |
| Full `vyhybna.xml` dispatcher run, unpaced (`NoOpSimulationController`) | `trainsExited=0, maxConcurrentTrains=2` |
| Same run, paced by `SimulationRunner` at 50x | `trainsExited=0, maxConcurrentTrains=2` — **identical** |

The A/B is the substance of the ruling: **real pacing changes wall-clock only, never event semantics**,
which is exactly the property the headless sweep (SP2c.24, #847) needs before it can trust a paced
controller. R8 is therefore a **module-placement and example-registration** task, not a "build a
headless runner" task.

### 5.3 Observation recorded, not a defect claim

Both A/B arms report `trainsExited=0` over the 300 s horizon that the P10 determinism gate uses. Because
the paced and unpaced arms agree exactly, this is a property of the current `DispatchTickLoop` +
`RequestRoute` stack, **not** of pacing. It is consistent with the P10 gate being `@Disabled` pending
#829 and with `applyRequestRoute` not incrementing block-transition counters (noted in
`RuleBasedDispatcherDeterminismRunner`'s KDoc). Recorded here for whoever re-enables that gate;
**this spike makes no claim that it is a defect** and did not investigate it.

---

## 6. The one blocking gap

`SimulationController` exposes `requestPause()` and **no resume**;
`DelegatingSimulationController` deliberately forwards neither `awaitIfPaused` nor the step polls,
because step-request ownership belongs to the simulation thread. A `PausedClockTickBudget` is
therefore **not implementable against the current interface** — it needs an API addition, tracked
as follow-up I1 below. The spike itself needed no such change, because `SimulationRunner.isPaused`
is a public settable `var`.

---


## 7. Follow-up work

Each lands on its own branch and PR; none is in scope for #849.

| Ref | Work | Blocks |
|---|---|---|
| **I1** | Add a resume path to `SimulationController` (`requestResume()` / `setPaused(Boolean)`) and forward it through `DelegatingSimulationController`. | Hard prerequisite for any `PausedClockTickBudget` — SP2c.10 (#833) F1 arm |
| **I2** ✅ | Add `ThrottlingSimulationController` to `:core` (Issue #873) — a headless pacing controller using `platformSleep`; register `shuntingLoopAI` in the console `examples` map wired to it. `assertPlannerPacingCompatible` unchanged. | SP2c.24 (#847) headless sweep → A4 |

---

## 8. Reproducing the evidence

```bash
# Deadlock, clock-freeze, speed-multiplier and resume-on-failure evidence
./gradlew :desktop-ui:test --tests 'cz.vutbr.fit.interlockSim.timing.*'

# Overhead and headless-pacing evidence
./gradlew :desktop-ui:integrationTest --tests '*PauseResumeOverhead*' --tests '*HeadlessPacing*'
```

Measured values are logged at INFO by the `cz.vutbr.fit.interlockSim.timing` logger, which
`desktop-ui/src/test/resources/logback-test.xml` routes to a dedicated unfiltered appender — both
default appenders carry a WARN/ERROR `ThresholdFilter`, so raising the logger level alone would not
surface them. Numbers appear in the `<system-out>` section of the JUnit XML report.

The prototype harness (`PausedClockSpikeHarness`) lives in the test tree so the ruling's claims
stay re-runnable. It deliberately does **not** modify `RuleBasedDispatcherDeterminismRunner`,
which is the P10 gate.
