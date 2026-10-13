# Goal 9B demand — SP4: bounded, cached static route search

**Date:** 2026-10-11
**Source:** Goal 1B SP4 ([#1151](https://github.com/bedaHovorka/interlockSim/issues/1151)), acceptance
**B5** of Goal 1B ([#1147](https://github.com/bedaHovorka/interlockSim/issues/1147)); P1 item (route cache)
of the [#591](https://github.com/bedaHovorka/interlockSim/issues/591) livelock analysis.
**Purpose:** record the measured size and cost of the switch-constrained route enumeration per Praha
pair, the chosen node budget, and whether incremental reservation looks needed, as input to Goal 9B.

## What SP4 changed

All changes are in `DefaultTopologyNavigator` (`context/navigation/`); `DefaultPathReservationService`
is untouched.

1. **Static route cache.** `findAllSwitchConstrainedPaths` (behind `RouteFinder.findRoutes`, hence
   behind every InOut-to-InOut `reservePath`, and behind `isPathAvailable`) keeps every
   `(start, target, maxDepth)` search for the lifetime of the navigator. Separators are compared by
   identity. The cache is on when the navigator serves a `SimulationContext`, whose topology never
   changes; a different topology is a new context with a new navigator and an empty cache. An
   editing context changes its topology in place, so its navigator never caches.
2. **Topology only.** An entry holds the routes (which sections a route crosses), never whether
   their blocks are free, reserved or occupied. Every reservation is still decided on the live
   block state.
3. **Node budget.** Each enumeration takes at most `ROUTE_SEARCH_NODE_BUDGET` = **300,000** search
   nodes from its queue. Over the budget, `searchSwitchConstrainedPaths` answers the structured
   `RouteSearch.BudgetExceeded(start, target, nodeBudget, expandedNodes)` and logs one warning;
   `findAllSwitchConstrainedPaths` then answers an empty list, so `reservePath` reports
   `NoPathExists` (permanent for `MultiTrainLoop`, which is correct: the answer depends on the
   topology only and would never change). The over-budget answer is cached like any other.
4. **No behaviour change within the budget.** The enumeration code is the same BFS; the budget
   check sits before each dequeue. The routes and their order are those of the unbounded search
   (`RouteSearchCacheTest.cachedRoutesMatchAnUncachedEnumerationInOrder`). The switch-blind
   `findAllTopologicalPaths` has no budget and no cache (out of scope).

## Measured enumeration per Praha pair

JVM, local machine, 2026-10-11, uncached navigator, depth cap 100, second of two passes (warm JIT).
All 110 ordered InOut pairs of `praha-hlavni-nadrazi.xml`: **30 pairs have a switch-legal route,
80 have none.** All 110 searches together take 121 ms; the slowest one takes 2.6 ms.

The search expands the whole switch-legal tree reachable from the start, whatever the target, so
the expanded-node count depends on the start only (pairs without a route expand the same number):

| Start | Expanded nodes | Time per pair |
|---|---:|---:|
| `N-Bypass` | 11 | 0.08 ms |
| `S-Bypass` | 45 | 0.4 ms |
| `N-Lib-1` | 78 | 0.7 ms |
| `N-Lib-2` | 106 | 1.0 ms |
| `S-Vin-1` | 110 | 0.9 ms |
| `S-Vin-2` | 111 | 0.9 ms |
| `N-Vys-1` | 135 | 1.5 ms |
| `S-Vrs-2` | 138 | 1.2 ms |
| `S-Vrs-1` | 175 | 1.5 ms |
| `N-Vys-2` | 178 | 1.7 ms |
| `S-Vrs-3` | 236 | 2.0–2.6 ms |

Routes per legal pair: 1 to 16 (`N-Vys-2 → S-Vrs-3` and `S-Vrs-3 → N-Vys-2`: 16; the five
`fiveTrainCompleteness` pairs: 10, 10, 11, 8, 1). Other fixtures: `cerveny_ujezd.xml` at most 47
expanded nodes and 4 routes per pair; `vyhybna.xml` 13 nodes and 2 routes.

## Chosen budget

`ROUTE_SEARCH_NODE_BUDGET = 300,000` expanded nodes. Two measured points set it:

- **Shipped networks** need at most 236 nodes (Praha). `RouteSearchBudgetTest.everyFixtureInOutPairCompletesWithinTheDefaultBudget`
  is the tripwire for all 124 ordered pairs of vyhybna, Červený Újezd and Praha.
- **The existing test suite** needs a much larger search to complete:
  `DefaultAutomaticPathFindingServiceTest` ("findAllPaths on 15-stage branchy network ...") enumerates
  the synthetic `TestTopologies.branchySwitchChain(15)`, 32,768 routes and **229,371** expanded
  nodes, and must return 10 routes within 5 s. A first choice of 10,000 nodes (about 40 times
  Praha) cut that search and failed the test, so the budget keeps that contract with about 30 %
  headroom.

| `branchySwitchChain` stages | Routes | Expanded nodes | Within 300,000 |
|---:|---:|---:|---|
| 10 | 1,024 | 7,163 | yes |
| 15 | 32,768 | 229,371 | yes |
| 16 | 65,536 | 458,747 | no — `BudgetExceeded` |
| 17 | 131,072 | 917,499 | no — `BudgetExceeded` |

Each extra stage doubles the search, so the budget turns exponential growth beyond 15 stages into a
structured failure after seconds of search, once per `(start, target, maxDepth)` key (the 15-stage search took 0.1–10 s of JVM time
on the loaded local machine, so time per node varies widely with load). The constant's KDoc records
this basis.

## Praha pair under `integration-test` (B5)

`PrahaBoundedRouteSearchTest` (`:core:integrationTest`, JVM):

| Scenario | `reservePath` calls | Wall time | Enumerations |
|---|---:|---:|---:|
| Two trains on `N-Lib-1 → S-Vin-1`, the second waits for the first (`MultiTrainLoop`, `maxEntryAttempts = 1000`) | 25 (cap 50) | 0.33–0.64 s (cap 10 s) | 2, equal to the cached keys |
| `N-Bypass → S-Vrs-3` (no switch-legal route), 50 direct calls | 50 (cap 50), every one `NoPathExists` | 0.10–0.18 s (cap 10 s) | 1 |

The same two-train scenario on the base revision (no cache, measured with the same harness before the
change): 25 calls, 0.61 s. The cache does not change the outcome or the call count.

## Cost of a failing `reservePath` today

The #591 analysis (`docs/ISSUE_591_SCALE_LIVELOCK_ANALYSIS.md` on `feat/issue-591-scale-validation`)
quotes about 2.7 s per failing `reservePath` on Praha, and the SP4 issue text about 5 s of wall time
per simulated second for one blocked train. The 2.7 s is the time of the **switch-blind**
`findAllTopologicalPaths` listing, measured in its §2.3 (`N-Bypass → S-Vrs-3`: 760 paths, 2.3 s;
`N-Vys-2 → S-Vrs-2`: 912 paths, 2.7 s), and §1/§3 apply it to `reservePath` by mistake. An
InOut-to-InOut `reservePath` has gone through `RouteFinder.findRoutes` →
`findAllSwitchConstrainedPaths` since 9c4366ce (#601), with the same BFS logic at 9c4366ce and at
9953beb4; it never ran the switch-blind listing. The listing that did run per attempt was the
`MultiTrainLoop` one that SP1 (#1148) removed.

Measured on the Goal 1B base 9953beb4 (before SP4, JVM, first call included): a blocked
`reservePath` on `N-Lib-1 → S-Vin-1` took 1.4–9.5 ms, on `N-Vys-2 → S-Vrs-3` 2.9–5.3 ms, and a
`NoPathExists` on `N-Bypass → S-Vrs-3` 0.2–2.9 ms. **B5 was already met on the base**: the
two-train `N-Lib-1 → S-Vin-1` scenario made 25 `reservePath` calls in 0.61 s.
`PrahaBoundedRouteSearchTest` is therefore a regression guard, not a fix. SP4 does not make Praha
fast, it already was; it makes the bound explicit: one enumeration per pair per navigator, and a
hard node budget for networks larger than the fixtures.

## Acceptance evidence

| Criterion | Evidence |
|---|---|
| Cache: second query for the same `(in, out)` does not re-enumerate | `RouteSearchCacheTest.secondQueryForSamePairDoesNotReEnumerate` (`commonTest`, untagged) |
| Cache: a topology change builds a new navigator with an empty cache | `RouteSearchCacheTest.topologyChangeBuildsNewNavigatorWithEmptyCache`; editor side: `editingContextNavigatorDoesNotCache` |
| Over-budget enumeration returns a structured failure within the budget | `RouteSearchBudgetTest.overBudgetEnumerationReturnsStructuredFailureWithinTheBudget`, `overBudgetPairHasNoRoutesAndIsNotSearchedAgain` |
| Praha pair: ≤ 50 `reservePath` calls, ≤ 10 s | `PrahaBoundedRouteSearchTest` (`integration-test`), table above |
| Cache holds no reservation state | `RouteSearchCacheTest.cacheHoldsNoReservationState`: reserve, query again (same routes), second train `AllPathsBlocked`, release, second train `Success` |
| Existing `PathReservationService` tests unmodified and green | no existing test file changed; full gate `build detekt ktlintCheck test integrationTest` green on fcd08a4f (core 3872 tests, 0 failures) |
| #895 re-baseline before and after | table below |

The two `commonTest` classes also pass on `:core:linuxX64Test` (8/8).

## #895 re-baseline — rule-based control arm

Manual `aiSweep` control arm, grid as in SP5 (`endTimeSeconds 600, repeat 10,
perRunTimeoutSeconds 900, example: ["shuntingLoop"]`). Railway outcomes summed over the 10 runs:

| Revision | Runs | Journeys | Entered | Exited | Max concurrent | Block transitions | Conflicts | Failed reservations |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| base 9953beb4 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| head 15d42b13 † | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |

Both sweeps: every run `NATURAL_COMPLETION`, control gate PASS, C7 clean. Base == head.
† Pre-review revision of fcd08a4f; the review round changed only visibility, KDoc and the test's
entry-attempt cap, nothing the sweep exercises.

## Demands for Goal 9B

1. **Incremental reservation is not needed for route search cost.** On Praha one enumeration costs
   at most 2.6 ms and runs once per pair; a blocked entry costs a few milliseconds per attempt. If
   SP11 fails on capacity, the cause is serialization of full entry-to-exit reservations (trains
   that share any block wait for the whole route), not search cost. Incremental reservation stays
   deferred (contingent SP14), and its case must rest on capacity, not on route-search time.
2. **Route search must stay bounded by construction.** Any new search over the topology (for
   example a conflict resolver's alternative-route search, or a dispatcher tool that lists routes)
   goes through the cached, budgeted navigator, never through a fresh unbounded listing.
3. **Surface the budget failure.** Today an over-budget pair reaches callers only as an empty route
   list, with the structured `RouteSearch.BudgetExceeded` visible only at the navigator and in one
   warning. The empty list means two different things: an InOut-to-InOut `reservePath` reports
   `NoPathExists`, a permanent refusal (`InOutWorker` throws, `MultiTrainLoop` records `NO_ROUTE`,
   `ReservationResultMapping` gives `DenialCause.NoPath`), while `isPathAvailable` and
   `isPathToAnyNextSemaphoreAvailable` read it as "every path blocked", so a train asking them would
   wait forever with one warning in the log. No shipped network gets near the budget (at most 236
   expanded nodes against 300,000). A Goal 9B dispatcher that reasons about refusals needs a
   distinct reason ("route search over budget") in `ReservationResult`; that is a public API
   change left to Goal 9B.
4. **The switch-blind listing is the remaining unbounded search.** `findAllTopologicalPaths` (used
   for non-InOut `reservePath` starts and `findCandidatePaths`) has neither cache nor budget. It is
   not on a Praha hot path today, but it is the one that grows exponentially (760 paths for one
   Praha pair, SP1).
5. **Memoise the expansion per start.** The search expands the same tree from a start whatever the
   target, but the cache key includes the target, so each target of one start pays the full
   expansion once. A per-start memo (one tree, all targets) would cut that to one expansion per
   start; not needed at today's sizes.
