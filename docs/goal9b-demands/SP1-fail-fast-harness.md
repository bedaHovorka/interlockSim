# Goal 9B demand — SP1: fail-fast harness and bounded entry reservation

**Date:** 2026-10-10
**Source:** Goal 1B SP1 ([#1148](https://github.com/bedaHovorka/interlockSim/issues/1148)), part of Goal 1B
([#1147](https://github.com/bedaHovorka/interlockSim/issues/1147)); P0 item of the
[#591](https://github.com/bedaHovorka/interlockSim/issues/591) livelock analysis.
**Purpose:** record the observed route-search cost per entry attempt on the Praha fixture
(`praha-hlavni-nadrazi.xml`, 117 blocks, 50 switches, 11 InOuts), as input to the Goal 9B demand list.

## What SP1 changed

1. **One `reservePath` call per attempt.** `MultiTrainLoop.reserveEntryPath` used to list every
   topological entry-to-exit path and call `PathReservationService.reservePath(train, inIo, outIo)`
   once per path. The call ignored the loop variable, so after the first failure every further call
   repeated the identical route search. SP1 makes one call per attempt.
2. **Capped attempts and a structured failure.** At most `maxEntryAttempts` attempts per train
   (default 3600, one per two-second dispatcher cycle). The loop records a
   `MultiTrainLoop.EntryFailure` when the cap is reached (`ATTEMPTS_EXHAUSTED`), when `reservePath`
   answers a permanent result (`NO_ROUTE`, `GEOMETRICALLY_IMPOSSIBLE`; not retried), or when a spec
   names an unknown InOut (`UNKNOWN_IN_OUT`; no train is created).
3. **Gate blocks from the route search.** The blocks of the (inert) `BlockResourceRegistry` gate come
   from `RouteFinder`, the same candidates `reservePath` tries, once per `(entry, exit)` pair. Before,
   they came from a listing of every topological path, on every attempt.
4. **Train length check without a path listing.** `Train.validateTrainLength` ran in every `Train`
   constructor and listed every topological path to find the shortest one. It now calls
   `TopologyNavigator.findShortestTopologicalDistance`, a Dijkstra search over the same switch-blind
   moves. On all 174 ordered InOut pairs of the test fixtures (Praha included) it gives the same value
   as the listing. The cheap Praha pairs (28 ordered pairs of at most 100 topological paths each) keep
   that equality pinned by `ShortestTopologicalDistanceTest.matchesPinnedListingDistancesOnPraha`. It
   checks the search against listing values measured once, because listing even these pairs at test
   time took 39 s on the JVM and 776 s on linuxX64 debug (past the 5 min CI limit).

## Cost of listing every path

The listing grows exponentially with the number of switches. On Praha:

| Pair | Topological paths |
|---|---:|
| `N-Lib-1 → S-Vin-1` | 24 |
| `N-Bypass → S-Vrs-3` | 760 |
| `N-Vys-2 → S-Vrs-2` | 912 |

Measured on this branch (local machine, 2026-10-10):

| Step | Listing every path | After SP1 |
|---|---:|---:|
| Gate blocks, 5 pairs of `fiveTrainCompleteness` (JVM) | 8.7 s in total (0.27–4.3 s per pair) | 6 ms in total |
| Shortest distance, all 174 fixture pairs (JVM) | 98.7 s | 3 ms |
| `reservePath`, successful, 5 Praha pairs (JVM) | 1–22 ms | unchanged |
| `reservePath`, successful, 5 Praha pairs (linuxX64 debug) | — | 4–35 ms |

`reservePath` itself does not list topological paths for an InOut-to-InOut request: it asks
`RouteFinder`, which lists switch-constrained paths only. The #591 analysis measured about 2.7 s for one
**failing** `reservePath` on Praha (JVM); this branch does not change that path and did not re-measure it.

## Wall time of `fiveTrainCompleteness`

| Target | Before SP1 fixes (`3ee61770`) | After |
|---|---:|---:|
| JVM (CI) | 35.5 s | 1.6 s |
| JVM (local) | 18.5 s | 1.8 s |
| linuxX64 debug (CI) | > 3 min, step killed at 5 min | about 21 s (console timestamps) |
| linuxX64 debug (local) | 272 s, failed the 60 s budget | 9.8 s |

On linuxX64 the old run spent 133 s inside the first `Train` constructor (the length check of
`N-Lib-1 → S-Vin-1`) before the first entry attempt, and most of the rest in the constructors of the
other four trains. Stack samples (`eu-stack`) showed `Train.validateTrainLength` →
`findAllTopologicalPaths` in every sample.

## Attempt counts on Praha

| Scenario | Entry attempts per train | Outcome |
|---|---:|---|
| `fiveTrainCompleteness` (5 block-disjoint routes, `maxEntryAttempts = 10`) | 1 | measured: all 5 exit, no `EntryFailure`, no gate resource left held |
| `twentyTrainStress` before #1179 (20 trains, `maxEntryAttempts = 60`) | 1 for the 13 refused trains | measured: fails fast at simulated time 22 s; 13 of 20 trains get `NO_ROUTE` |

The 13 refused trains of `twentyTrainStress` asked for pairs that have topological paths but **no
switch-legal route** on the Praha fixture (for example `N-Bypass → S-Vrs-3`: 760 topological paths, 0
legal routes; also `N-Lib-1 → S-Bypass`, `N-Vys-1 → S-Vin-1`, `N-Vys-2 → S-Vin-2`). The stress test
therefore failed because of its train specs, not because of a livelock. Before SP1 these trains retried
a route search that could never succeed until the cap; after SP1 each failed on its first attempt.

### Demand 3 closed (#1179, 2026-10-11)

**Per-pair decision (railway-civil-engineer): the specs were wrong, the fixture is right — for all
13 pairs.** The modelled Praha throats fan each north group into the platforms and back out to the
matching exit group, and the bypass (Y=20) is a through line, not a platform approach. Every one of
the 13 pairs would need a reversal through a switch, that is a shunting move; no dispatcher routes a
through train that way. No crossover was added to `praha-hlavni-nadrazi.xml`: changing the fixture
would also move the pinned topological distances of
`ShortestTopologicalDistanceTest.matchesPinnedListingDistancesOnPraha`.

`twentyTrainStress` now rotates over the switch-legal pairs only
(`MultiTrainStressSpecs.LEGAL_PAIRS` in `core/src/commonTest`): the five block-disjoint pairs of
`fiveTrainCompleteness` plus `N-Lib-2 → S-Vin-1`, `N-Vys-2 → S-Vrs-1` and `N-Lib-2 → S-Vrs-3`. All
20 trains enter and exit and the run records no `EntryFailure`. Because a reused pair serializes its
trains, the scenario runs on a longer simulated horizon (`endTime = 3600 s`,
`maxEntryAttempts = 1800`, one attempt per two simulated seconds).

The fast `MultiTrainStressSpecsTest` guard (`commonTest`, so JVM `test` and `:core:linuxX64Test`)
asserts that every pair the scenario uses has a `RouteFinder` route on Praha, so a spec change cannot
reintroduce an impossible pair unnoticed. It only asks about pairs that do have a route, which costs
milliseconds; a search for a pair without one enumerates every switch-constrained path first.

## #895 timing re-baseline (CI step times)

| CI step | `develop` `69fdbfa4` (run 38045303713) | PR head `3ee61770` (run 38051800780) | `499484cc` (run 38057526300) |
|---|---:|---:|---:|
| Run unit tests | 2:40 | 3:40 | 2:54 |
| Run integration tests | 1:35 | 1:42 | 1:44 |
| Run linuxX64 native tests (5 min limit) | 1:30 | timed out (5:13) | 2:14 |

The native step is 44 s longer than on `develop` because SP1 adds tests to `commonTest`, which
`:core:linuxX64Test` runs in full; `fiveTrainCompleteness` alone takes about 21 s of it.

## Demands for Goal 9B

1. **Never list every path in a hot path.** Listing grows exponentially with switches. Every caller
   that only needs a minimum, a set of blocks or one free route must use a search that stops early
   (Dijkstra, a bounded candidate search, a static route cache per InOut pair). SP1 fixed the gate
   and the train length check. A failing `reservePath` still lists all switch-constrained paths before
   it applies its candidate cap (~2.7 s JVM on Praha, from #591).
2. **Retry trigger.** Blocked entries are re-polled every dispatcher cycle. The already-emitted
   `ConflictDetectedEvent` (or a block-release event) should trigger the retry instead.
3. **Route pairs must be legal.** ✅ **Done (#1179).** A scale scenario must draw its
   `(entry, exit)` pairs from the switch-legal routes of the network (`RouteFinder`), not from all
   InOut combinations: 13 of the 20 original `twentyTrainStress` pairs had no legal route on Praha.
   `MultiTrainStressSpecs.LEGAL_PAIRS` now supplies the pairs and `MultiTrainStressSpecsTest`
   guards them.
4. **Structured refusal as input.** `EntryFailure` (kind, attempts, last `ReservationResult`) is the
   hand-off point for a conflict resolver: it says which train gave up, after how many attempts and why.
