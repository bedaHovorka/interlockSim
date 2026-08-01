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

### 3.1 Is the simulation clock genuinely frozen? (AC2)

The criterion as written in #849 — "verify `obs.simTime` is genuinely unchanged" — is **vacuous**:
`DispatcherObservation` is an immutable data class passed to `emit` by value, so its `simTime` cannot
change regardless of what the simulation does. Asserting it would prove nothing about P8.

The spike measures the **simulation clock itself** instead, reconstructed from the deltas the
controlled event loop reports to `SimulationController.throttle`. Every measurement is two-sided: a
running control window is measured with the same probe, so a dead probe cannot masquerade as a frozen
clock.

`PausedClockSimTimeInvarianceTest`, harness at 10x:

| Window (600 ms) | Simulation-clock advance |
|---|---|
| Running (control) | **6.0 sim s** — exactly 10x, probe confirmed live |
| Paused | **0.0 sim s** |

**Pause latency** — simulation time elapsing between `isPaused = true` and the thread actually parking,
over 20 pause/resume cycles: **max 0.0 sim s, mean 0.0 sim s**.

*Interpretation, stated precisely:* the pause flag is checked at **every event boundary**, so the clock
cannot run past the boundary at which the pause is observed. A non-zero latency remains theoretically
possible in the sub-millisecond window where the simulation thread is executing an event rather than
sleeping in `throttle`; it was not observed in 20 trials. The test asserts the principled upper bound
of one `ShuntingLoop` tick period (`hold(1.0)`), not the observed zero, so it will not become brittle
on slower hardware.

### 3.2 Pause/resume overhead (AC3)

`PauseResumeOverheadTest`, 200 cycles:

| Metric | Value |
|---|---|
| Mean | **0.29 µs** |
| p99 | **7 µs** |
| Max | **28 µs** |

Against a 1 s tick this is **immaterial** — roughly seven parts per million at p99. Pause/resume cost
is not a design constraint for F1.

### 3.3 Interaction with real-time sync and the speed multiplier (AC4)

`PausedClockSpeedMultiplierInteractionTest`. Measurement quantum is 0.333 sim s/s
(`ShuntingLoop`'s `hold(1.0)` over a 3 s window), so rates land on that lattice rather than exactly on
the nominal speed:

| Speed | Rate before pause | Rate after resume |
|---|---|---|
| 1.0x | 0.667 sim s/s | 1.0 sim s/s |
| 2.0x | 1.667 sim s/s | 2.0 sim s/s |
| 5.0x | 4.667 sim s/s | 5.0 sim s/s |

Three properties hold at every speed:

- **Orthogonality** — a pause/resume cycle leaves `speedMultiplier` untouched.
- **Rate fidelity** — every measurement is within one quantum of nominal.
- **No catch-up debt** — the post-pause rate returns to the pre-pause rate. `SimulationRunner.throttle`
  sleeps proportionally to the simulation delta and keeps no wall-clock deadline, so a pause cannot
  leave the run "behind schedule" and make it sprint afterwards. **This matters for F1**: an inference
  pause must not be repaid by a burst of unthrottled simulation, which would defeat the very cap
  `AGENT_MAX_SPEED_MULTIPLIER` exists to enforce.

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

## 6. Corrections to the draft finding

The remote-agent finding that seeded this spike was substantially right — F1 feasible, deadlock only if
awaiting a fresh capture while paused, option (b) invalid, F2 first. Two claims needed correcting:

1. **`controller.resume()` does not exist.** `SimulationController` exposes `requestPause()` and no
   resume; `DelegatingSimulationController` deliberately forwards neither `awaitIfPaused` nor the
   step polls (step-request ownership belongs to the simulation thread). The sketched
   `PausedClockTickBudget` is therefore **not implementable against the current interface** — it needs
   an API addition (follow-up I1). The spike itself needed no such change because
   `SimulationRunner.isPaused` is a public settable `var`.
2. **"`obs.simTime` unchanged" is the wrong measurement** — vacuous, as shown in §3.1. The meaningful
   quantity is the simulation clock, which is what this spike measured.

One sharpening: the finding treated a headless pacing controller as something to be built. It already
exists (§5.2).

---

## 7. Follow-up work

Each lands on its own branch and PR; none is in scope for #849.

| Ref | Work | Blocks |
|---|---|---|
| **I1** | Add a resume path to `SimulationController` (`requestResume()` / `setPaused(Boolean)`) and forward it through `DelegatingSimulationController`. | Hard prerequisite for any `PausedClockTickBudget` — SP2c.10 (#833) F1 arm |
| **I2** | Extract `SimulationRunner`'s pacing core out of `:desktop-ui` (or add a sibling headless controller) and allow `shuntingLoopAI` to register headlessly. | SP2c.24 (#847) headless sweep → A4 |

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

The prototype harness (`PausedClockSpikeHarness`) is throwaway per #849's note, kept in the test tree
so the ruling's claims stay re-runnable rather than becoming folklore. It deliberately does **not**
modify `RuleBasedDispatcherDeterminismRunner`, which is the P10 gate.
