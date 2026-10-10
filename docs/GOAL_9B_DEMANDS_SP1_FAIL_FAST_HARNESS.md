# Goal 9B demand — SP1: fail-fast harness and bounded entry reservation

**Date:** 2026-10-10
**Source:** Goal 1B SP1 ([#1148](https://github.com/bedaHovorka/interlockSim/issues/1148)), part of Goal 1B
([#1147](https://github.com/bedaHovorka/interlockSim/issues/1147)); P0 item of the
[#591](https://github.com/bedaHovorka/interlockSim/issues/591) livelock analysis.
**Purpose:** record the observed reservation cost per entry attempt on the Praha fixture
(`praha-hlavni-nadrazi.xml`, 117 blocks, 50 switches, 11 InOuts), as input to the Goal 9B demand list.

## What SP1 changed

`MultiTrainLoop.reserveEntryPath` used to enumerate every topological entry-to-exit path and call
`PathReservationService.reservePath(train, inIo, outIo)` once per path. The call ignored the loop
variable, so after the first failure every further call repeated the identical full route search.
SP1 makes **one** `reservePath` call per attempt, caps the attempts per train (`maxEntryAttempts`,
default 3600, one attempt per two-second dispatcher cycle) and records a structured
`MultiTrainLoop.EntryFailure` when the cap is reached or the entry is statically impossible.
The topological enumeration that feeds the (inert) `BlockResourceRegistry` gate now runs once per
`(entry, exit)` pair instead of once per attempt.

## Reservation cost per attempt

| Quantity | Before SP1 | After SP1 |
|---|---:|---:|
| `reservePath` calls per entry attempt | up to *P* (topological paths of the pair) | 1 |
| *P* for `N-Lib-1 → S-Vin-1` | 24 | — |
| *P* for `N-Bypass → S-Vrs-3` | 760 | — |
| *P* for `N-Vys-2 → S-Vrs-2` | 912 | — |
| Wall time of one failing `reservePath` (JVM) | ~2.7 s | ~2.7 s (unchanged, out of SP1 scope) |
| Wall time of one blocked entry attempt, `N-Bypass → S-Vrs-3` (JVM) | 760 × 2.7 s ≈ 34 min | ≈ 2.7 s |
| Topological enumeration for the gate | every attempt (2.3–5.9 s each) | once per `(entry, exit)` pair |

"Before" figures are the measurements of the #591 analysis (`ISSUE_591_SCALE_LIVELOCK_ANALYSIS.md`
on branch `feat/issue-591-scale-validation`, §2.1–§2.4). "After" is the structural bound pinned by
`MultiTrainLoopBoundedEntryTest.reserveEntryPathBoundedAttempts` (counting fake: exactly
`maxEntryAttempts` calls, then one `EntryFailure`).

## Attempt counts on Praha

| Scenario | Entry attempts per train | Outcome |
|---|---:|---|
| `fiveTrainCompleteness` (5 block-disjoint routes, `maxEntryAttempts = 10`) | 1 (first attempt succeeds) | all 5 exit, no `EntryFailure` |
| `twentyTrainStress` (20 trains, shared throats, `maxEntryAttempts = 60`) | up to 60 per blocked train | expected to fail: full-path reservation bounds concurrency to the block-disjoint routes |

## Demands for Goal 9B

1. **Per-attempt route-search cost.** One `reservePath` still costs a full exhaustive route search
   (~2.7 s JVM on Praha) because the candidate cap is applied after enumeration. A dispatcher tick is
   one simulated second, so a single blocked train already runs below real time. Goal 9B needs a
   bounded or cached candidate search (static route cache per InOut pair, first-free short-circuit).
2. **Retry trigger.** Blocked entries are re-polled every dispatcher cycle. The already-emitted
   `ConflictDetectedEvent` (or a block-release event) should trigger the retry instead.
3. **Structured refusal as input.** `EntryFailure` (kind, attempts, last `ReservationResult`) is the
   hand-off point for a conflict resolver: it says which train gave up, after how many attempts and why.
