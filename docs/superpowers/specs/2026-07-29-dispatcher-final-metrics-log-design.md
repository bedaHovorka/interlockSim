# Dispatcher Final Metrics Log on Simulation Stop

**Date:** 2026-07-29
**Status:** Approved (pending implementation)

## Problem

Issue #817 added `MeasuringPlanAdapter`, which tracks LLM (Ollama) success vs. rule-based
fallback counts, broken down by `FallbackReason`, and logs a periodic summary every
`REPORT_EVERY_N_CYCLES` cycles. But if the simulation stops between periodic checkpoints
(e.g. after 7 cycles when the interval is 10), the final state of the run is never logged —
the last visible summary can be stale by up to 9 cycles. There is currently no guaranteed
"statistics at end of simulation" log line.

## Scope

Targets **only** the existing `shuntingLoopAI` GUI example
(`ExampleRegistry.createShuntingLoopAIGuiExample`, reachable via `runExampleAIGui` /
Frame's Simulation → Start), which is the sole place `MeasuringPlanAdapter` is wired up
today. Output is a log line (kotlin-logging), not a GUI dialog. No new headless entry
point and no changes to `fast-sim` or the plain rule-based examples.

The log must fire on simulation end **for any reason** — natural completion or the user
pressing Stop.

## Design

### 1. `MeasuringPlanAdapter` (dispatcher-agent) — new `logFinalSummary()` method

Add a public method that reuses `getMetricsSnapshot()` and logs unconditionally (no
`% REPORT_EVERY_N_CYCLES == 0` gate), reusing the same log-line shape as
`logPeriodicSummary` but labeled `"final summary"` so it is grep-distinguishable from
periodic entries:

```
[MeasuringPlanAdapter] final summary — totalCycles=7 ollamaSuccess=5
fallback=2 (TIMEOUT=1, EXCEPTION=0, EMPTY_NO_TOOLS=1) successRate=71%
```

Must behave correctly with zero cycles (mirrors `PlannerMetricsSnapshot.ollamaSuccessRate`'s
existing "0.0 when totalCycles is zero" contract).

### 2. `ExampleRegistry.createShuntingLoopAIGuiExample()` (desktop-ui) — register the adapter

The `aiPlanner: MeasuringPlanAdapter` local is currently built, passed into
`wireDispatcherAgent(..., plannerOverride = aiPlanner)`, and then discarded — nothing
outside the factory function can reach it. Add:

```kotlin
context.scope.declare(aiPlanner)
```

immediately after construction, so it becomes retrievable later via
`context.scope.getOrNull<MeasuringPlanAdapter>()` — the same null-tolerant scoped-lookup
pattern `wireDispatcherAgent` already uses for `DispatcherModeState`,
`SemiAutoApprovalGateway`, etc.

### 3. `Frame.kt` — hook into the existing `STOPPED` branch

`SimulationController`'s `onStateChanged` callback already emits `SimulationStatus.STOPPED`
for both natural completion (monitor thread detects the runner thread exiting) and manual
`stop()`. No changes to `SimulationController` are needed. In Frame's existing `STOPPED`
branch:

```kotlin
currentSimulationContext?.scope?.getOrNull<MeasuringPlanAdapter>()?.logFinalSummary()
```

`currentSimulationContext` is still valid at this point — it is only cleared in
`setContext()`, not during stop. For rule-based examples or XML-loaded contexts (no
`MeasuringPlanAdapter` ever registered), the scoped lookup is `null` and this is a silent
no-op — zero behavior change for every other example.

### Data Flow

```
AI example runs
  → AgentLoopDriver calls planner.plan() each cycle (existing, unchanged)
  → MeasuringPlanAdapter counters increment, periodic logs fire (existing, unchanged)
  → simulation stops (natural completion OR manual Stop)
  → SimulationController emits SimulationStatus.STOPPED via onStateChanged
  → Frame's STOPPED handler resolves MeasuringPlanAdapter from context.scope
  → logFinalSummary() logs the final snapshot exactly once
```

### Error Handling

- `getOrNull<MeasuringPlanAdapter>()` avoids any crash when no adapter was wired for the
  active context.
- `logFinalSummary()` only reads `AtomicLong` counters via the existing
  `getMetricsSnapshot()` — no new failure modes.

## Testing

- **`dispatcher-agent`**: extend `MeasuringPlanAdapterTest` to assert `logFinalSummary()`
  can be called at any cycle count — including a count not aligned to
  `REPORT_EVERY_N_CYCLES`, and zero cycles — without throwing. Follows the file's existing
  style (asserting via `getMetricsSnapshot()`, not log-capture; this file has no
  log-capture tests today).
- **`desktop-ui`**: extend the `SimulationController`-level test suite (which already
  tests the `onStateChanged`/`STOPPED` wiring in isolation from full Swing, e.g.
  `SimulationControllerTest`) to cover:
  - adapter present in scope → final summary logged exactly once on natural stop
  - adapter present in scope → final summary logged exactly once on manual stop
  - adapter absent from scope → no-op, no exception

## Out of Scope

- New headless (console-only) LLM-dispatcher entry point.
- GUI dialog/report presentation of statistics.
- `fast-sim` native CLI changes.
- Statistics for the plain rule-based dispatcher examples or the Goal 6
  `MetricsSnapshot`/block-utilization metrics — unrelated to this feature.
