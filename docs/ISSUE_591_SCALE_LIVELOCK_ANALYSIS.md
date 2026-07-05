# Issue #591 — Multi-Train Scale Livelock: Root-Cause Analysis and Missing Simulation Model Behaviour

**Date:** 2026-07-05
**Author:** traffic-simulation-expert (TEAM.md role) — lead analysis; kotlin-tech-lead consulted on architecture sections
**Issue:** [#591 — Goal 1 SP7: Scale validation for 5-train correctness and 20-train performance](https://github.com/bedaHovorka/interlockSim/issues/591)
**Branch:** `feat/issue-591-scale-validation` (PR #631)
**Status of CI:** `Run unit tests` times out after 10 minutes on both runs

---

## 1. Executive Summary

The 20-train stress test (`MultiTrainScaleValidationTest.twentyTrainStress`) does not
fail an assertion — it **freezes the simulation clock** inside a single dispatcher
event and burns CPU for tens of minutes. This is a **CPU-bound livelock**, not a
classic wait-for-graph deadlock:

1. `MultiTrainLoop.reserveEntryPath` enumerates **all** topological entry-to-exit
   paths for a blocked train (**760 paths** for `N-Bypass → S-Vrs-3` on the Praha
   fixture, measured) and, for each one, calls
   `PathReservationService.reservePath(trainName, inIo, outIo)`.
2. That `reservePath` call is **independent of the loop's candidate path** — its
   arguments never change across iterations — so after the first
   `AllPathsBlocked` result, all remaining 759 calls are guaranteed to return the
   identical result.
3. Each `reservePath` call internally performs a **full exhaustive route
   enumeration** of the station (~2.7 s wall-clock on the Praha fixture, measured),
   so one dispatcher iteration for one blocked train costs
   **760 × 2.7 s ≈ 34 minutes** of CPU.
4. All of this happens inside a single kDisco event. kDisco is a single-threaded
   discrete-event engine, so **simulated time is frozen** during the entire
   computation: trains 1–4 stop moving mid-journey, never release their blocks,
   `AllPathsBlocked` can never clear, and the generator never injects train #6.

The 5-train test passes because its five routes are pairwise block-disjoint: the
**first** candidate path succeeds for every train and the pathological loop is
never entered.

Beyond the immediate defect, the deeper finding is that the simulation model is
missing the interlocking behaviours that real railways use precisely to avoid
this failure mode: **incremental (signal-to-signal) route reservation**,
**bounded/cached route search**, **dispatcher fairness**, and **deadlock/livelock
detection**. These are catalogued in §5.

---

## 2. Evidence

All evidence was gathered by running the failing test locally
(`./gradlew :core:jvmTest --tests "…MultiTrainScaleValidationTest.twentyTrainStress"`,
JDK 21) with debug logging temporarily enabled for `cz.vutbr.fit.interlockSim.sim`.

### 2.1 Simulation-time freeze at t ≈ 21.5 s

The debug log shows normal operation up to sim-time ~21.5 s, then nothing but
dispatcher retries — the sim clock never advances again:

```
… t=20.0  MultiTrainLoop: generated Train #5 (N-Bypass -> S-Vrs-3)
… t=21.3  BLOCK_TRANSITION: Train 2 leaving block
… t=21.5  POSITION: Train 2 tail at separator Dynamic[SW13 …]
…         MultiTrainLoop: approved Train #5 for dispatch
…         MultiTrainLoop: all paths blocked for Train #5, will retry   ← repeats
…         MultiTrainLoop: all paths blocked for Train #5, will retry     forever,
…         MultiTrainLoop: all paths blocked for Train #5, will retry     one line
                                                                          every ~2.7 s
```

- 77 "all paths blocked" lines in 209 wall-seconds ⇒ **~2.72 s per line**.
- Train #6 (`inTime = 25.0`) is **never generated** ⇒ the generator's `hold(1.0)`
  never fires ⇒ virtual time is frozen.
- No `BLOCK_TRANSITION` / `POSITION` events after t ≈ 21.5 ⇒ trains 1–4 are
  parked mid-journey holding their reserved blocks.

### 2.2 Thread dump of the hung test worker

`jstack` on the hung Gradle test worker shows a single `RUNNABLE` coroutine thread
(223 s of CPU at capture time) inside the dispatcher:

```
"Test worker @coroutine#2" … RUNNABLE  (cpu=223646ms)
  at …AbstractCell.joinsOnLine(AbstractCell.kt:25)
  at …RailSemaphore.getFollowingSegment(RailSemaphore.kt:50)
  at …DefaultTopologyNavigator.getAllNextTrackBlocks(DefaultTopologyNavigator.kt:595)
  at …DefaultTopologyNavigator.findAllSwitchConstrainedPaths(DefaultTopologyNavigator.kt:505)
  at …DefaultAutomaticPathFindingService.findAllPaths(DefaultAutomaticPathFindingService.kt:120)
  at …DefaultRouteFinder.findRoutes(DefaultRouteFinder.kt:38)
  at …DefaultPathReservationService.findCandidatePaths(DefaultPathReservationService.kt:98)
  at …DefaultPathReservationService.reservePath(DefaultPathReservationService.kt:147)
  at …MultiTrainLoop.reserveEntryPath(MultiTrainLoop.kt:319)
  at …MultiTrainLoop.startApprovedTrains(MultiTrainLoop.kt:263)
  at …MultiTrainLoop.iteration(MultiTrainLoop.kt:231)
```

No thread is blocked on a lock or resource — this is CPU-bound work inside one
event, i.e. a **livelock**, not a deadlock.

### 2.3 Combinatorial path counts on the Praha fixture (measured)

A throwaway diagnostic calling
`TopologyNavigator.findAllTopologicalPaths(inIo, outIo, maxDepth = 100)` on
`praha-hlavni-nadrazi.xml` (117 blocks, 50 switches, 11 InOuts):

| Route | Topological paths | Enumeration time |
|---|---:|---:|
| `N-Lib-1 → S-Vin-1` | 24 | 5.9 s (first call, JIT-cold) |
| `N-Bypass → S-Vrs-3` | **760** | 2.3 s |
| `N-Vys-2 → S-Vrs-2` | **912** | 2.7 s |

Station ladders multiply alternatives: the path count grows roughly with the
product of parallel-track choices at each throat, i.e. **exponentially** with
route length across the ladder.

### 2.4 Cost model of the hang

For one blocked train with `P` candidate paths and a per-`reservePath` cost `C`:

```
one dispatcher iteration  ≈  P × C  =  760 × 2.7 s  ≈  34 minutes  (Train #5)
```

During those 34 minutes the sim clock is frozen, so nothing that could unblock
the train can happen. When the loop finally exhausts all candidates, the
dispatcher `hold(1.0)`s, trains move for one sim-second, and the next iteration
repeats the same ~34-minute computation — now potentially for **several** blocked
trains (`startApprovedTrains` loops over all approved-but-unstarted trains, up to
20). The 10-minute CI wall is hit long before the first iteration completes.

Even the *successful* first reservation for Train #1 took ~12 wall-seconds at
t = 0–2 — already incompatible with the ≥ 1× real-time-ratio acceptance
criterion.

---

## 3. Root Cause 1 — Redundant path-independent `reservePath` loop (defect)

`MultiTrainLoop.reserveEntryPath` (MultiTrainLoop.kt:285–348):

```kotlin
val candidatePaths = topologyNavigator.findAllTopologicalPaths(inIo, outIo, maxDepth = 100)
for (path in candidatePaths) {                      // 760 iterations for N-Bypass→S-Vrs-3
    val blocks = extractUniqueBlocks(path)
    if (!blockResources.areAllAvailable(blocks)) continue
    …reserve kDisco resources for `blocks`…
    when (pathReservationService.reservePath(train.name, inIo, outIo)) {   // ← ignores `path`!
        is Success -> return true
        is AllPathsBlocked -> { /* log */ }          // ← same result guaranteed next time
        …
    }
    …release resources…
}
```

Two independent mistakes compound:

1. **The `reservePath` call does not use the loop variable.** It receives
   `(trainName, inIo, outIo)` — identical on every iteration. `reservePath`
   internally re-runs its own candidate search (`RouteFinder.findRoutes` →
   exhaustive `findAllSwitchConstrainedPaths`) and tries **all** of its own
   candidates. If it returns `AllPathsBlocked` once, iterating the outer loop
   cannot change the outcome (no other process can run — the event loop is
   occupied). The outer loop multiplies a ~2.7 s global failure by 760.
2. **Duplicate path search.** The dispatcher enumerates all topological paths
   itself (`findAllTopologicalPaths`), and `reservePath` then enumerates them
   again internally. The outer enumeration exists only to feed the kDisco
   `Resource` gate — whose blocks-per-candidate check also becomes meaningless
   after the dispatcher released all resources post-reservation (they are
   *always* available at check time; the journey-time exclusivity lives in
   `PathReservationService`, exactly as the KDoc says).

**Why the 5-train test passes:** each of its five routes finds a free path on the
first candidate; `Success` short-circuits the loop; the pathological
760-iteration branch is never reached.

**Minimal fix direction (for a follow-up PR, not part of this analysis):**
`reservePath(trainName, inIo, outIo)`'s result is path-independent, so
`AllPathsBlocked` / `NoPathExists` must terminate the candidate loop immediately
(`return false`), and the outer `findAllTopologicalPaths` enumeration should be
removed or replaced by a single `reservePath` attempt per train per iteration.
That alone converts the 34-minute frozen event into one ~2.7 s attempt per
sim-second — still slow (§4), but no longer a hang.

---

## 4. Root Cause 2 — Exhaustive, uncached route enumeration (performance)

`DefaultAutomaticPathFindingService.findAllPaths` enumerates **all**
switch-constrained paths up to `maxDepth = 100` *before* sorting by cost and
truncating to `maxPaths = 100`:

```kotlin
val rawPaths = navigator.findAllSwitchConstrainedPaths(start, target, maxDepth)  // exhaustive DFS
…
return if (results.size <= maxPaths) results else results.take(maxPaths)         // cap applied AFTER
```

Consequences on a station-scale topology:

- Every `reservePath` call costs ~2.3–5.9 s on Praha regardless of `maxRoutes`,
  because the cap is applied after full enumeration (the KDoc on
  `AutomaticPathFindingService` line 94 admits "the real bound on enumeration
  breadth" is `maxDepth`, not `maxPaths`).
- The topology is **static and frozen** (`BaseContext.freeze()`), yet the
  enumeration is recomputed from scratch on every reservation attempt of every
  train, every sim-second. Nothing is memoized.
- The search enumerates paths, then filters by block availability. On a busy
  station, availability filtering rejects most candidates, so nearly all
  enumeration work is wasted.

**Fix directions (follow-up work):**

1. **k-shortest-paths instead of exhaustive DFS** — Yen's algorithm or
   cost-ordered lazy generation, yielding candidates one at a time; stop at the
   first free route. Real dispatch systems never enumerate all routes.
2. **Static route cache** — routes per `(InOut, InOut)` pair depend only on the
   frozen topology; compute once per context (or lazily) and reuse. 11 InOuts
   ⇒ ≤ 110 ordered pairs.
3. **Availability-aware pruning** — treat blocks owned by other trains as
   removed edges *during* the search, so blocked ladders prune whole subtrees
   instead of being enumerated and rejected afterwards.

---

## 5. Missing Simulation-Model Behaviour (railway-domain analysis)

The immediate defect (§3) can be patched, but the scale bar of #591 exposes
behaviours a real interlocking has and this model does not yet have. These are
what actually needs to exist for 20 concurrent trains on a station topology.

### 5.1 Incremental route reservation (Fahrstraße / signal-to-signal routes)

**Missing.** `MultiTrainLoop` reserves the **full entry-to-exit path** before a
train may start (its own KDoc documents this as a first-slice limitation). Real
interlockings reserve **signal-to-signal routes** and extend them as the train
advances; a train occupying platform track 5 does not lock the entire southern
throat.

Consequences of the current model at scale:

- With 20 trains over 5 north entries and 6 south exits, almost every pair of
  routes shares at least one throat block ⇒ `AllPathsBlocked` is the *common*
  case, which is exactly the trigger of the §3 hot loop.
- Effective concurrency is bounded by the number of block-disjoint entry-to-exit
  routes (≈ 4–6 on Praha), so `trainsExited == 20` within `endTime = 1200` is
  unreachable regardless of CPU speed. (This confirms review finding #5 on
  PR #631.)

The infrastructure for incremental reservation already half-exists:
`InOutWorker`/`ShuntingLoop` use `reservePathToAnyNextSemaphore` (semaphore-hop
granularity), and the `Tail` site already releases blocks behind the train
(`env.unregisterBlock`). What is missing is a dispatcher that **extends** a
train's route hop-by-hop toward its destination instead of pre-reserving it all.

### 5.2 Deadlock avoidance for incremental reservation

Once reservation becomes incremental, a new hazard appears that full-path
reservation was (correctly) avoiding: two trains advancing toward each other can
each hold half of a corridor — a real deadlock. The model needs one of:

- **Route-conflict pre-check** (classic interlocking flank/opposing-route
  protection): do not grant a route segment whose continuation is opposed by an
  already-granted route.
- **Banker's-style admission**: only grant a hop if a complete continuation to
  the destination remains *reachable* (not necessarily free).
- **Directional corridor locks** for single-track segments (the standard
  token-block equivalent).

None of these exist today; they are prerequisites for removing the full-path
constraint that #591's scale bar effectively requires.

### 5.3 Dispatcher fairness, retry policy and head-of-line blocking

**Missing.** `startApprovedTrains` iterates `approvedTrains` in insertion order,
retrying every blocked train **every sim-second** at full path-search cost, with
no backoff, no prioritisation, and no notion of "nothing changed since the last
attempt". A real dispatcher is event-driven: it re-attempts route setting only
when a **block-release event** occurs on a relevant route.

The event API for this already exists on this branch (#569's
`onBlockEvent`/`BlockEvent.Released`) and `Train` already uses event-driven
waiting (`createPathAvailableCondition`, #582). The dispatcher should adopt the
same pattern: after `AllPathsBlocked`, subscribe/wait for a block release
instead of polling each sim-second.

### 5.4 Livelock/deadlock detection and simulation watchdog

**Missing.** The model has no runtime detector for "no train has moved for N
sim-seconds while M trains are pending", and no wall-clock budget per dispatcher
event. #591's acceptance criterion ("no deadlocks or livelocks in 10 runs") is
currently only *observable as a CI timeout*, which is the worst possible
diagnostic. Needed:

- **Model-level watchdog process**: track last block-transition sim-time; if
  `time() - lastProgress > threshold` while trains are pending, stop the run
  with a structured report (train positions, owned blocks, wait-for edges).
  A wait-for graph over `PathReservationRegistry` (train → wanted blocks →
  owning train) makes true deadlocks detectable and distinguishable from
  contention.
- **Test-level guards**: per-scenario timeout (`assertTimeoutPreemptively` on
  JVM, or a kDisco event/step ceiling in commonTest) so a hang fails fast with
  a named scenario. This confirms review finding #4 on PR #631.

### 5.5 Bounded, cached, cost-ordered route search

**Missing** (detailed in §4): lazy k-shortest-path candidate generation, static
route caching per InOut pair, and availability-aware pruning. Without these,
even a correct dispatcher cannot meet the ≥ 1× real-time ratio on a
station-scale topology: a *single* uncached exhaustive search (~2.7 s) already
exceeds the 1 s sim-time budget of a dispatcher tick.

### 5.6 kDisco `Resource` gate is currently inert

The `BlockResourceRegistry` gate can never be contended: resources are acquired
and released within one uninterruptible (never-suspending) dispatcher section,
and `areAllAvailable` is always checked when nothing is held. As implemented it
adds cost but no protection — journey-time exclusivity is enforced solely by
`PathReservationService`. Either give resources journey-scoped lifetime (the
"per-block guard process" already sketched in `BlockResourceRegistry`'s KDoc) or
remove the gate from the dispatch hot path.

---

## 6. Why This Was Invisible in Earlier Slices

| Scenario | Topology | Trains | Contention | Outcome |
|---|---|---|---|---|
| `TwoTrainLoop` (#587) | vyhybna (2 tracks) | 2 | disjoint by construction | first candidate wins |
| `ThreeTrainLoop` (#584/#589) | vyhybna | 3 | low; tiny path count (≤ ~4) | blocked loop cheap |
| 5-train (#591) | Praha | 5 | routes chosen block-disjoint | first candidate wins |
| 20-train (#591) | Praha | 20 | shared throats unavoidable | §3 hot loop × 760 paths |

The defect is O(P × C) with P = topological path count and C = per-search cost.
Both P and C were negligible on `vyhybna.xml` (P ≤ ~4, C ≈ ms). The Praha
station is the first fixture where both explode simultaneously.

---

## 7. Recommended Sequencing (for follow-up issues)

1. **P0 – break the hot loop** (§3): make `AllPathsBlocked`/`NoPathExists`
   terminate `reserveEntryPath` immediately; drop the redundant outer
   `findAllTopologicalPaths`. Restores clock progress; test then *fails fast*
   on unmet throughput instead of hanging. Test-only guard (§5.4 test-level)
   can land in the same PR.
2. **P1 – event-driven dispatcher retry** (§5.3): reuse #569 block-release
   events; retry route setting only on release. Removes per-second re-search.
3. **P1 – route search cost** (§5.5/§4): static route cache per InOut pair +
   first-free-candidate short-circuit. Targets the ≥ 1× real-time ratio.
4. **P2 – incremental reservation + deadlock avoidance** (§5.1, §5.2): the
   actual capacity unlock needed for 20 trains to *complete* on Praha. This is
   the "future slice" `MultiTrainLoop`'s KDoc already anticipates and should be
   its own design spec.
5. **P2 – watchdog/deadlock reporter** (§5.4): makes #591's "no deadlock in 10
   runs" criterion directly assertable rather than inferred from CI timeouts.

Until at least items 1–3 land, the #591 acceptance criteria cannot be met on
this branch; with items 1–3, the 5-train case stays green and the 20-train case
becomes a genuine measurement (expected to fail `trainsExited == 20` until
item 4, which is a scope decision for the issue).

---

## 8. Reproduction Notes

- 5-train test passes locally: `JAVA_HOME=…jdk-21 ./gradlew :core:jvmTest
  --tests "…MultiTrainScaleValidationTest.fiveTrainCompleteness"` (~3 min).
- 20-train test hangs locally exactly as on CI (killed after 500 s; sim clock
  frozen at t ≈ 21.5 with dispatcher in the §3 loop).
- Path counts in §2.3 measured with a temporary diagnostic calling
  `TopologyNavigator.findAllTopologicalPaths(inIo, outIo, maxDepth = 100)` on
  `praha-hlavni-nadrazi.xml` (not committed).
