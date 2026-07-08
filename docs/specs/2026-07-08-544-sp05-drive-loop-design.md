# SP0.5 — Drive the dispatcher loop via the `SimulationController` pacing seam

**Issue:** #544 (sub-issue of #535 / Goal 10)
**Canonical authority:** #532 issue body (governs the tactical definition of "done") and its GOAL_10 Agent-Based Traffic Control design comment.
**Status:** Approved direction — 2026-07-08. Implementation not started.
**Supersedes:** PR #727 initial plan (which proposed replacing `hold(1.0)` with `controller.throttle()` inside `ShuntingLoop.iteration()` — rejected as architecturally unsound; see §"Rejected alternative").

---

## 1. Decision

**Lift the dispatcher out of the kDisco kernel.** Today `ShuntingLoop.iteration()` calls `dispatcher.approve(ctx)` / `dispatcher.advancePaths(ctx)` on a `hold(1.0)` simulation-time cadence, with the dispatcher reading live block state and mutating the simulation through callbacks on `DispatcherTickContext`. SP0.5 moves that decide loop into an **`AgentLoopDriver`** in **`:dispatcher-agent`**, paced by `SimulationController.awaitIfPaused()` / `throttle()`. `ShuntingLoop` keeps `hold(1.0)` and `interLoopSleep()` but no longer drives dispatch.

This is **Option 2** of the three considered. It is the only option #532's own acceptance criteria permit:

- **A1** explicitly requires "`ShuntingLoop` becoming a thin process shell driving the loop via the existing Goal 7 extension point `SimulationRunner.throttle()` / `awaitIfPaused()`." That *is* lifting the dispatcher out.
- **A5** requires side-by-side `RuleBasedDispatcher`-vs-LLM metrics on the same `vyhybna` run. Both policies must execute through the **same** integration path, so any metric difference is attributable to the policy, not the plumbing. (Rules out Option 1 — opt-in driver alongside an inline rule-based path — which confounds the comparison.)
- **A6** requires real-time ratio ≥ 1×. A local-Ollama LLM decision takes real wall-clock seconds; invoking it *inside* a kernel step would stall the kernel and sink A6. Driving from outside via `throttle()`/`awaitIfPaused()` lets the deliberative agent take its time between control steps. (Rules out Option 3 — a control-step hook inside `:core`.)
- The module boundary in #532 is explicit: agent concerns must **not** land in `:core` or `:fast-sim` native builds; `:dispatcher-agent` depends on `:core`, owns the Koog/Ollama deps, and is never depended on by `:fast-sim`. (Also rules out Option 3.)

## 2. Invariants (non-negotiable)

These are locked by the PR #727 review and are not open for the implementation to reinterpret:

1. **`hold(1.0)` stays.** `ShuntingLoop.iteration()` keeps its kDisco simulation-time `hold(1.0)`. `hold` is *simulation-time* scheduling; `controller.throttle()` is *wall-clock* pacing. They are not substitutes.
2. **`interLoopSleep()` stays.** `ShuntingLoop.interLoopSleep()` is the `time() >= endTime` termination check (`env.stop()`). It is structural in `LoopProcess`, not a removable helper. Removing it would break termination and passivate the loop forever.
3. **`SimulationController` is NOT added to `SimulationEnvironment` or `DispatcherTickContext`.** The controller is injected into the **driver**, not exposed to `sim/` or to dispatcher policy implementations.
4. **Pacing belongs to the driver, not the policy.** The `Dispatcher` / `DispatchDecision` seam is defined narrowly as *"given observed state, return decisions."* The policy receives no controller, no pacing, no thread. (Putting pacing in the policy would re-couple the LLM's latency into the contract SP0.5 decouples it from.)
5. **`:dispatcher-agent` depends on `:core` only** (the `SimulationController` interface + `NoOpSimulationController`). It must **not** depend on `:desktop-ui`. The concrete `SimulationRunner` is injected at runtime from `:desktop-ui`; `NoOpSimulationController` is used for headless / `:fast-sim`.
6. **`sim/` gains no new dependency on `SimulationController` or on `:dispatcher-agent`.** The kernel does not import the agent.

## 3. Architecture

### 3.1 Seam reshape — `Dispatcher` becomes pure

The current `Dispatcher` seam is imperative:

```kotlin
// Today (imperative — reads live state, mutates via callbacks, on the kDisco thread)
interface Dispatcher {
    fun approve(context: DispatcherTickContext)      // context.approveTrain(train), etc.
    fun advancePaths(context: DispatcherTickContext)  // context.reservePath(sem, name), etc.
}
```

SP0.5 reshapes it to a **pure decision function**. The policy observes a frozen snapshot and returns decisions; the **driver** applies them:

```kotlin
// After SP0.5 (pure — given observed state, return decisions; no pacing, no mutation)
interface Dispatcher {
    fun decide(observed: DispatchObservation): List<DispatchDecision>
}
```

`DispatchObservation` is the read-only input: the `SimulationSnapshot` (SP0.4 sense value: `simTime`, `semaphores`, `blocks`, `trainPositions`, `timetables`) plus the unapproved-train queue (names + destinations, read-only). It contains **no callbacks** and **no live mutable handles**.

`DispatchDecision` (already scaffolded in `:dispatcher-agent` from SP0.1) carries the concrete subtypes — `ApproveTrain(trainId)`, `ReservePath(trainId, fromSemaphoreName)`, `NoAction`, plus any needed for switch/signal/speed commands. The policy returns a list per tick; it may return `NoAction`.

This reshapes partially pulls #556 (SP2b.1 — "Dispatcher interface + DispatchDecision") forward. #556 remains owner of the LLM-specific decision richness; SP0.5 defines only what the lifted driver needs.

### 3.2 `AgentLoopDriver` (sense → decide → act), paced by the controller

A new component in `:dispatcher-agent` owns the loop:

```
each cycle:
    1. SENSE:   snapshot = perceptionPort.snapshot()        // SP0.4, read-only, off-thread-safe
    2. DECIDE:  decisions = dispatcher.decide(observedFrom(snapshot, queue))
                                          // pure; RuleBasedDispatcher today, LLM tomorrow
    3. ACT:     commandQueue.postAll(decisions)             // thread-safe handoff (see 3.3)
    4. PACE:    controller.awaitIfPaused()
               controller.throttle(snapshot.simTime - prevSimTime)
```

- `RuleBasedDispatcher` and the future Koog LLM dispatcher are both `Dispatcher` implementations behind the **same** seam, driven by the **same** `AgentLoopDriver` — one call site, two implementations (A5 confound-free).
- The driver runs on its own thread/coroutine; the kernel never blocks on it (A6-safe — the LLM may take real seconds between control steps while the sim keeps stepping).

### 3.3 Concurrency model — command queue + sim-thread applier (critical)

kDisco is single-threaded; `SimulationEnvironment` / `PathReservationService` are **not** thread-safe. The driver thread **must not** mutate simulation state directly. The handoff is a command queue:

- **Driver thread:** reads snapshot (read-only), decides (pure), **posts** `DispatchDecision`s to a thread-safe `ActuatorCommandQueue`. No sim mutation.
- **Sim thread (kDisco):** an **applier** drains the queue and applies each decision through the SP0.6 actuator ports (`NetworkActuatorPort`, `TrainActuatorPort`). All mutation stays on the sim thread.

The applier must run on the kDisco thread. Two candidate mechanisms, in order of preference (the implementation picks one and justifies it in the design note — §5):

1. **A kDisco `Process` applier in `:dispatcher-agent`** that waits on a condition and drains the queue, applying via the actuator ports. Activated at sim startup by wiring (Koin / `ExampleRegistry`), not by `ShuntingLoop`. Adds **no** hook in `:core`.
2. **An additive control-step listener** registered on `SimulationEnvironment` (interface in `:core`, implementation in `:dispatcher-agent`) that drains the queue at each kDisco event. The listener lives in `:dispatcher-agent`; `:core` only holds the registration surface. Acceptable only if (1) proves infeasible in kDisco, and only as a *generic* observer hook — never dispatcher logic in `:core`.

Either way: **no dispatcher-logic hook in `:core`**, **no kernel wait for the agent**.

### 3.4 `ShuntingLoop` becomes a thin shell

`ShuntingLoop` keeps only:

- Train-lifecycle pruning of terminated trains (`iteration()`).
- `hold(1.0)` (sim-time heartbeat) and `interLoopSleep()` (endTime stop, `generator.terminate()` + `env.stop()`).
- Test-observability counters (`trainsEntered/Exited`, `maxConcurrent`, `blockTransitions`) — counter increments for `placeTrain`/terminated-train removal stay here; reservation-transition increments move to the applier.
- `RealTimeSynch` inner class (GUI wall-clock sync) — unchanged.

`ShuntingLoop` **loses**:

- The `dispatcher` constructor parameter and its `RuleBasedDispatcher()` default.
- `createTickContext()` and the `DispatcherTickContext` construction (moves to the driver / applier).
- The `perceptionPort` / `actuatorPort` ownership and `tryReservePathFrom` helper (moves to the driver / applier — the actuator port already wraps `PathReservationService`).

### 3.5 Wiring / DI

- `:dispatcher-agent` Koin module binds `AgentLoopDriver`, `ActuatorCommandQueue`, the applier, and a `Dispatcher` (default `RuleBasedDispatcher`).
- `:desktop-ui` (`ExampleRegistry` / `Main`) injects the concrete `SimulationRunner` as `SimulationController` into the driver for GUI runs; headless / `:fast-sim` use `NoOpSimulationController`.
- The driver + applier are activated at sim startup alongside the main process.

## 4. Determinism & acceptance gates

### A3 — determinism (identical outcomes across 10 runs), before and after the lift

This is the gate that proves the extraction itself didn't change dispatch behavior:

- **Before the refactor:** run the existing `RuleBasedDispatcherDeterminismTest` (already in `:dispatcher-agent`) on the current in-kernel dispatch path. Record outcomes across 10 runs — must be identical.
- **After the refactor:** run the same 10-run check on the lifted driver path. Must be identical **and** match the before-refactor outcomes.

Why this is achievable despite the cadence change from sim-time `hold(1.0)` to driver-paced `throttle(simDelta)`: the `RuleBasedDispatcher` policy is **purely state-reactive and idempotent** — it re-checks block state and queue every tick and only acts when conditions hold. The exact wall-clock interleaving between "driver decides" and "train moves" does not change *which* train is approved or *which* path is reserved, because the rule is state-driven, not time-driven. The driver paces itself by `simTime` deltas (via `throttle(simDelta)`), approximating the prior 1.0-sim-sec cadence at speed = 1.0.

**Fallback (out of scope for #544, becomes a follow-up if A3 regresses):** introduce a sim-time barrier — the sim steps in 1.0-sim-sec chunks and waits for the driver's decision at each chunk boundary before continuing. This preserves determinism by construction but re-couples the kernel to the agent's latency for the rule-based path. Only invoked if re-validation fails; the default design is the decoupled async queue.

### A5 — rule-based vs LLM, same path

Both dispatchers run through `AgentLoopDriver` — same invocation timing, same pacing, same failure modes. Metric differences (delay, conflicts, throughput) are attributable to the policy.

### A6 — real-time ratio ≥ 1×

The kernel never blocks on the agent. The LLM may take real seconds between control steps; the sim keeps stepping at real-time ratio ≥ 1×. This is the core reason the dispatch loop is driven from *outside* via `throttle()`/`awaitIfPaused()` rather than from a kernel step.

### Existing `ShuntingLoop` tests must stay green (A1 gate)

Per #532 A1: existing `ShuntingLoop` tests stay green. The extraction is behavioral, not a rewrite of the kernel's own train/track mechanics. Tests that asserted dispatch *from inside `ShuntingLoop`* (e.g. `RuleBasedDispatcherTest` driving through `DispatcherTickContext` callbacks) are rewritten to drive through the lifted seam (`Dispatcher.decide` + applier) and must reproduce the same outcomes.

## 5. Required first deliverable — design note on the drainer mechanism

Before full implementation, the implementer posts a short design note (as a PR comment) choosing between the two applier mechanisms in §3.3 and answering:

- How the applier is activated on the kDisco thread at sim startup without a `:core` hook.
- How the driver signals "command available" cross-thread in kDisco (or why a polling applier is acceptable).
- How `DispatchObservation` is built from `SimulationSnapshot` + the unapproved-train queue without exposing live mutable handles to the policy.
- The exact `DispatchDecision` subtypes this slice introduces (vs. those deferred to #556).

This gate exists so the largest of the three refactors doesn't land with an under-designed concurrency seam.

## 6. Scope of changes per module

### `:core` — `sim/ShuntingLoop.kt`
- Remove `dispatcher` ctor param + `RuleBasedDispatcher()` default.
- Remove `createTickContext()`, `perceptionPort`/`actuatorPort` construction, `tryReservePathFrom`.
- Remove `dispatcher.approve/advancePaths` calls from `iteration()`.
- Keep `hold(1.0)`, `interLoopSleep()`, train-lifecycle pruning, counters, `RealTimeSynch`.
- `sim/Dispatcher.kt`, `sim/RuleBasedDispatcher.kt`: reshape `Dispatcher` to `decide(observed): List<DispatchDecision>`; rewrite `RuleBasedDispatcher` against `DispatchObservation` (pure). `DispatcherTickContext` is removed or repurposed (the callback surface moves to the applier).

### `:dispatcher-agent` (NEW components)
- `AgentLoopDriver` — the paced sense→decide→act loop.
- `ActuatorCommandQueue` — thread-safe handoff.
- Applier (kDisco `Process` or control-step listener) — drains queue on the sim thread, applies via actuator ports.
- `DispatchObservation` — read-only input to `Dispatcher.decide`.
- `DispatchDecision` — extend the existing skeleton with the subtypes this slice needs.
- Koin module binding the above.
- `RuleBasedDispatcherDeterminismTest` — updated to drive through the lifted seam; becomes the A3 before/after harness.

### `:desktop-ui`
- `ExampleRegistry` / `Main`: wire `AgentLoopDriver` + applier at sim startup; inject `SimulationRunner` as `SimulationController` (GUI) / `NoOpSimulationController` (headless).

### `:fast-sim`
- No new dependency on `:dispatcher-agent`. Headless runs use the driver with `NoOpSimulationController`.

## 7. Tests (mandatory)

- **Unit — `ActuatorCommandQueue`:** offer/poll/drain, backpressure, thread-safety stress.
- **Unit — `AgentLoopDriver` pacing:** fake `SimulationController` + fake snapshot feed; assert cycle order (sense → decide → post → `awaitIfPaused` → `throttle(simDelta)`) and that decisions are posted (not applied in-process).
- **Unit — `Dispatcher.decide`:** `RuleBasedDispatcher` against `DispatchObservation` snapshots; assert decisions match the prior in-kernel behavior for representative vyhybna states (approve-when-below-capacity, reserve-when-approaching, idempotent skip-when-already-extended, NoAction when FREE).
- **Concurrency — applier:** assert actuator calls execute on the sim thread (e.g. a thread-identity assertion); no sim-state mutation from the driver thread.
- **Integration — vyhybna end-to-end via the lifted driver:** all trains exit, no conflict events.
- **Determinism — A3:** 10-run identical-outcome check, before-refactor baseline captured and matched after.
- **Regression:** `ShuntingLoop` tests updated; `RuleBasedDispatcherTest` rewritten against the lifted seam; existing golden-output tests re-run (document any accepted delta).
- **Heavy tests** (`@Tag("heavy-test")`) on `sim/` per `CLAUDE.md` — `sim/` is touched, so the heavy suite is run manually before merge.

## 8. Out of scope

- Koog / LLM agent runtime (SP1+), and the LLM `DispatchDecision` richness (SP2b.1 / #556).
- The sim-time-barrier fallback (only if A3 regresses).
- Praha scale (#591).

## 9. Rejected alternative (PR #727 initial plan)

The original PR #727 plan proposed replacing `ShuntingLoop.iteration()`'s `hold(1.0)` with `controller.throttle(DISPATCH_INTERVAL)` / `awaitIfPaused()`, wiring `SimulationController` through `SimulationEnvironment`, removing `interLoopSleep()`, and adding `controller` to `DispatcherTickContext`. Rejected because:

- `hold(1.0)` is kDisco simulation-time scheduling; `throttle()` is wall-clock pacing — not substitutes.
- `throttle()`/`awaitIfPaused()` are already invoked per kDisco event by `DefaultSimulationContext.advanceControlledStep()` (`DefaultSimulationContext.kt:1437-1460`); re-invoking from `ShuntingLoop.iteration()` is redundant and would double-throttle.
- Removing `interLoopSleep()` drops the `endTime` termination check and reverts the loop to `passivate()` (no waker) → the sim hangs or runs to `Double.MAX_VALUE`.
- Exposing `SimulationController` via `SimulationEnvironment` / `DispatcherTickContext` inverts the dependency and re-couples pacing into the policy, contradicting the SP0.6 isolation and #532's "no agent concerns in `:core`."