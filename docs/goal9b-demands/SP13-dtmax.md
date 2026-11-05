# Goal 9B demand — SP13: the global integration step (`dtMax`)

**Date:** 2026-11-05
**Source:** [#1160](https://github.com/bedaHovorka/interlockSim/issues/1160) (Goal 1B SP13), a follow-up of
PR #1146 ("Run Engine ahead of the velocity integration"); parent
[#1147](https://github.com/bedaHovorka/interlockSim/issues/1147).
**Purpose:** decide one global `dtMax` for the velocity integration, record why the 1e-1 rung of the Issue #760
ladder failed, and state the integration step a Goal 9B planner may assume and the accuracy it costs.

## Decision

**`dtMax` = 1e-3 s, one global value.** It is the constant `Generator.DT_MAX`; `Generator.startAction` is its only
production use. Every scenario run gets it from there: `MultiTrainLoop` (the Goal 1B exit harness) and `ShuntingLoop`
through their generators. No scenario has an override of its own. The value is unchanged, so the exit parameters of
SP11, the native seed-1 pin, the #895 numbers and the JVM/native parity pin do not move.

| Where | What |
| --- | --- |
| `Generator.DT_MAX` | the constant, 1e-3 s |
| `Generator.startAction` | `dtMax = DT_MAX`, the one production statement |
| `LowTrafficExitScenario.DT_MAX` (`core` `commonTest`) | `= Generator.DT_MAX`, the exit parameter |
| `DtMaxSingleSiteTripwireTest` (`core` `jvmTest`, untagged) | greps every module: in production source sets the only `dtMax = …` statement is `dtMax = DT_MAX` in `Generator.kt`; in test source sets only `EngineStandaloneTest`, `SimpleTestProcess`, `TrainHoldAtStationIntegrationTest` and the SP13 reproducer may set their own step, and each of them must still do so |
| `DtMaxTenthStallReproducerTest` (`core` `jvmTest`, untagged) | the 1e-1 reproducer (below), and a check that `MultiTrainLoop` and `ShuntingLoop` run at `Generator.DT_MAX` |

Why 1e-3 and no raise:

- The owner ruling on #1147 keeps the exit at 1e-3, and SP11 passed 10 of 10 JVM seeds and the native seed there.
- There is no cost pressure. SP11 (demand D1) measured 1–2 s of JVM wall time per simulated hour on Praha at
  1e-3, at 3, 5 or 15 admitted trains. Simulation cost is not the limit at the Goal 1B traffic levels.
- A raise moves the native seed-1 pin, the #895 numbers and the JVM/native parity pin, and needs the Issue #760
  ladder re-run after PR #1146. That is an owner decision; nothing in SP13 asks for it.

## The 1e-1 stall: cause

**What failed.** PR #1133 walked the ladder 1e-3 → 1e-2 → 1e-1 → 1.0. At 1e-1, `:dispatcher-agent`'s
`OwnershipConflictStallWarningTest.stallWarnsTwiceAfterResolveAndRestall` failed 2 of 2 with "captured stall
errorStop for Train #27" null. The test holds the first train under way in an ownership-conflict wait until its WARN
(60 s), frees it, holds it again at the next separator and expects that second wait to reach the 180 s `errorStop`
horizon before the 400 s run ends.

**It was not a deadlock.** The freed train leaves a stand at `zB` and crawls the 5 m to `vB`, where it is held
again. Before PR #1146, the velocity integration read the step-start acceleration in every RK stage (Issue #1126).
At 0.1 s steps that left the run-up of this short leg slow, so the crawl took 4–5 times longer. The second hold
began about 30 s later, so its 180 s horizon moved past the horizon of another train queued behind it (Train #28,
held at `doB2` since about 94 s). The run then stopped on Train #28's `errorStop` at 273.8 s, and Train #27's
`errorStop` never came.

**Fixed by PR #1146.** Since the Engine runs ahead of the velocity integration, every stage uses its own
acceleration, and the crawl is the same at 1e-1 as at 1e-3.

### Recorded runs (2026-11-05)

The resolve-and-restall scenario on vyhybna, run in `:core` with the phases switched by simulated time (the
`:core` tests have no logback appender to watch for the WARN). "Crawl" is the simulated time from the moment the
held train is freed at `zB` to its first query at `vB`. The 1e-1 and 1e-2 steps were set by a wrapper main process
0.5 s after the generator's start.

| Revision | `dtMax` | Freed at (s) | Held again at (s) | Crawl (s) | First `errorStop` |
| --- | ---: | ---: | ---: | ---: | --- |
| `4683bee7` (parent of PR #1146) | 1e-3 | 71.68 | 78.96 | 7.28 | held train, 258.96 s |
| `4683bee7` | 1e-2 | 71.70 | 79.62 | 7.92 | held train, 259.63 s |
| `4683bee7` | 1e-1 | 71.88 | 108.40 | 36.52 | held train, 288.40 s |
| `813e6442` (this branch's base) | 1e-3 | 71.68 | 78.90 | 7.22 | held train, 258.90 s |
| `813e6442` | 1e-2 | 71.69 | 79.05 | 7.36 | held train, 259.05 s |
| `813e6442` | 1e-1 | 71.70 | 79.36 | 7.66 | held train, 259.40 s |

A second probe of the same runs sampled the held train every second (the extra events shorten the steps, so its
crawl at 1e-1 on `4683bee7` was 19.5 s instead of 36.5 s). It shows the shape of the crawl from 100 m to 105 m: about
0.26 m/s at 1e-1 on `4683bee7` against 0.71 m/s at 1e-3.

`:dispatcher-agent` `OwnershipConflictStallWarningTest` with `Generator`'s step changed to 1e-1 for the run only:

| Revision | Result |
| --- | --- |
| `4683bee7` | 4 of 5 pass; `stallWarnsTwiceAfterResolveAndRestall` fails: no `errorStop` for Train #27; the run stopped on "Train #28: no route extension at 'Dynamic[doB2, signal=STOP]' after 180 s" at 273.76 s |
| `813e6442` | 5 of 5 pass |

### Reproducer

`DtMaxTenthStallReproducerTest` runs the scenario twice: once with `dtMax` set to 1e-1 by its own main process
(in that process's `startAction`, after the generator's start), once at the global step. It asserts that the crawls
differ by at most 1.0 s, that the 1e-1 run stops on the held train's `errorStop`, and that the second run (a fresh
context) sees `Generator.DT_MAX` again: kDisco keeps `dtMax` per simulation context, so the 1e-1 value cannot leak.
Each run is bounded by its simulated `endTime` (400 s); the `@Timeout` is only a safety ceiling.

- Red on `4683bee7`: "crawl difference (s) to be less than or equal to 1.0 but was 29.25".
- Green on `fe3a45e5` (the PR #1146 merge) and on this branch.

So the 1e-1 stall cannot be reproduced on the current code; the test pins the cause instead.

## E1 bound: what a Goal 9B planner may assume

"E1" is the per-step bound of the Issue #760 design memo: within one integration step a speed can change by at most
the largest acceleration times the step, `|a|max × dtMax`, with `|a|max` = 3 m/s² (the braking and acceleration
bound). At the chosen step:

| Quantity | Bound at `dtMax` = 1e-3 s | For comparison at 1e-1 s |
| --- | --- | --- |
| Integration step | at most 1 ms of simulated time; events and root-finding cut it further | 100 ms |
| E1 speed error of one step | 3 m/s² × 1e-3 s = 0.003 m/s | 0.3 m/s |
| Distance covered in one step | `v × dtMax`, 0.02 m at 20 m/s | 2 m at 20 m/s |
| Polled waits (`waitUntil`, e.g. the ownership-conflict horizon and the path-available condition) | resolve at most one step late: ≤ 1 ms | ≤ 100 ms |
| Root-found waits (block boundaries, tail entry, every Engine wait) | located inside the step by kDisco; no step error | same |
| Stand exit | at most half a step late: ≤ 0.5 ms | ≤ 50 ms |
| Stand residual at the clearance gate (Issue #1057 ramp case, after PR #1146) | 0.015 m/s (one 5 ms sample at the bound) | not measured on this code |

Demand for Goal 9B: a planner may treat the simulator's event times as exact to within 1 ms of simulated time and
its speeds to within 0.003 m/s per step. That is far below any headway or dwell a dispatcher plans with, so a plan
needs no margin for the integration step. A planner must not assume a coarser step for speed: the cost is already
small (SP11 D1), and a coarser step moves the pinned numbers (see "Decision").

## Not in scope

- No kDisco change and no `kdiscoVersion` bump.
- No re-run of the Issue #760 ladder after PR #1146 (the P2 stand residual and the goldens at 1e-2 and 1e-1). A
  raise would need it, together with the owner sign-off named above.
