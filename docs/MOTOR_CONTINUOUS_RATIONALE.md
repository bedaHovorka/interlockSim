# Engine Class — Why it Extends `Continuous`

**Status:** Architectural record — resolves #373 (2026-04-20); class renamed/extracted in #1059
**Author:** traffic-simulation-expert (see [`TEAM.md`](../TEAM.md))
**Scope:** The top-level `Engine` class in
`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/Engine.kt`
(formerly the private inner `Motor` of `Train`).

## Context

During review of [PR #372](https://github.com/bedaHovorka/interlockSim/pull/372)
(kDisco Phase 1 migration) a reviewer flagged `Motor : Continuous()` as a
CRITICAL concern. The reasoning was that the project's decision documents had
concluded *"continuous simulation is NOT required"* — so why does the train
propulsion process extend kDisco's ODE-integration base class?

This document records the traffic-simulation-expert's (TSE) arbitration of
that concern. The code keeps `Engine : Continuous()` and a short KDoc at the
class site; the full argument lives here.

Issue [#1059](https://github.com/bedaHovorka/interlockSim/issues/1059) later
extracted the inner `Motor` into the top-level `Engine` class (English naming,
separate file, host interface). Runtime behaviour is unchanged; only the type
location and name moved.

## Type hierarchy (relevant classes)

```
Link                        ← kDisco
└── Process                 ← kDisco        (discrete-event entity)
    ├── Continuous          ← kDisco        (adds ODE integration: derivatives(), start(), stop())
    └── LoopProcess         ← interlockSim  (discrete-only cooperative loop)
```

- `Continuous : Process()` — kDisco class; the only base that provides an ODE
  integrator and per-phase activation/deactivation of that integrator.
- `LoopProcess : Process()` — project-local class in
  `cz.vutbr.fit.interlockSim.sim`; discrete-only, no `derivatives()`,
  no `start()`/`stop()`.

## Why `Continuous` is required

The `Engine` class models train kinematics. Its `derivatives()` override
is what actually advances velocity and position between discrete events.
Without `Continuous` there is nowhere to put `derivatives()`; without
`start()`/`stop()` the integrator cannot be gated to an acceleration phase.

Concretely, `Engine`:

- overrides `derivatives()` — only `Continuous` invokes this hook;
- calls `start()` when an acceleration phase begins, and `stop()` when it ends;
- uses `waitUntil(condition)` on the discrete side of the same object to
  interleave the integrator's activity with event-driven control flow.

`LoopProcess` (or plain `Process`) cannot host any of this.

## Framework decision ≠ implementation physics

The *"continuous simulation is NOT required"* line in the decision documents
is a **framework-level** statement. It answers the question *"should we adopt
a library built around ODE solvers (DSOL) or a discrete-event library that
we extend ourselves (kDisco)?"*. The answer was: kDisco.

That answer says nothing about how individual classes inside the project
compute motion. `Engine` (and the former `Motor`) has always used ODE-integrated
kinematics — before and after the jDisco→kDisco migration — because there is no
practical discrete substitute for continuously-evolving train dynamics during an
acceleration phase.

Cross-references:

- [`docs/SIMULATION_LIBRARY_DECISION.md`](./SIMULATION_LIBRARY_DECISION.md)
- [`docs/SIMULATION_LIBRARY_DECISION_ROUND2.md`](./SIMULATION_LIBRARY_DECISION_ROUND2.md)
- [`docs/DECISION_AUDIT_AND_EXPERTISE.md`](./DECISION_AUDIT_AND_EXPERTISE.md)

## `terminate` flag — duplication, not redundancy

`Engine` carries a private `terminate: Boolean` flag that mirrors the
cooperative-shutdown protocol in `LoopProcess`. This is duplicated *on
purpose*:

- `TrainReporter` (inner class of `Train`) extends `LoopProcess` and inherits
  the pattern for free.
- `Engine` cannot extend `LoopProcess` (it needs `Continuous`), so it
  reimplements the minimal pattern — one flag, checked in `actions()` — to
  achieve the same safe shutdown.

The duplication is narrow and stable; it does not warrant a shared
trait/mixin for two classes.

## Host interface (Issue #1059)

`Engine` is no longer an inner class of `Train`. It receives a narrow
`Engine.Host` implemented by `Train`, exposing only the kinematics surface the
ODE process needs (velocity/acceleration variables, distance-to-semaphore,
restrictive-signal query, speed limit, debug reporting). That keeps `Variable`
fields off the public Train API while allowing the propulsion process to live
in its own file.

## Non-goals

- No change to `Engine`'s runtime behavior relative to the former `Motor`.
- No new formulas, no change to kinematics.
- No reopening of the kDisco-vs-DSOL-vs-Kalasim framework decision.

## References

- Code: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/Engine.kt`
  — search for `internal class Engine` and `override fun derivatives()`
- Code: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/Train.kt`
  — `Engine.Host` implementation and `private val engine`
- Code: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/LoopProcess.kt`
- kDisco: <https://github.com/bedaHovorka/kdisco/>
- Issue: [#373](https://github.com/bedaHovorka/interlockSim/issues/373)
- Issue: [#1059](https://github.com/bedaHovorka/interlockSim/issues/1059)
- PR: [#372](https://github.com/bedaHovorka/interlockSim/pull/372)
