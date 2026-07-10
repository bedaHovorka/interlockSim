# ANALYSIS-742: `PathReservationRegistry.mergePathInfo` fork-overlap vs circular-loop revisit

Issue: bedaHovorka/interlockSim#742  
Scope: analysis only; no source or test changes.

## 1. Code map

### 1.1 Dispatcher-side target discovery: `findNextReservationTarget`

**Function:** `DefaultPathReservationService.findNextReservationTarget(start)`
(`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/DefaultPathReservationService.kt:822-859`).

- **Inputs:** an `OrientedPathSeparator` named `start`.
- **Outputs:** the first currently free `DynamicPathSeparator` reachable one section ahead of `start`, or `null`.
- **State read:**
  - converts `start` to the dynamic wrapper (`environment.toDynamic(start)`) and derives the forward segment from InOut/semaphore orientation (`DefaultPathReservationService.findNextReservationTarget`, lines 827-834);
  - reads the grid location and graph edge for that forward segment (`DefaultPathReservationService.findNextReservationTarget`, lines 835-846);
  - enumerates reachable targets with `findNextSemaphoresVia(dynamicStart, next)` (`DefaultPathReservationService.findNextReservationTarget`, line 848);
  - checks path availability for each candidate with `isPathAvailable(dynamicStart, it)` and returns the first free one (`DefaultPathReservationService.findNextReservationTarget`, lines 853-858).
- **State mutated:** none in this function; its interface KDoc explicitly describes it as the read-only twin of `reservePathToAnyNextSemaphore` (`PathReservationService.findNextReservationTarget`, `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationService.kt:341-359`).
- **Information it does not have:** it returns only a separator. It does **not** return which earlier candidates were blocked, whether the returned target is the primary candidate or an alternate, nor any train physical position (`DefaultPathReservationService.findNextReservationTarget`, lines 822-859; `PathReservationService.findNextReservationTarget`, lines 341-375).

`findNextSemaphoresVia(start, next)` is the target enumerator used by both read-only discovery and reserving calls. It performs BFS from `(start, next)` (`DefaultPathReservationService.findNextSemaphoresVia`, lines 1300-1335), records InOuts and forward-facing semaphores as stopping targets (`processReachedSeparator`, lines 1344-1374), explores all outgoing branches at junctions (`exploreOutgoingPaths`, lines 1380-1415), and returns distinct InOuts before semaphores (`prioritizeInOuts`, lines 1420-1427). This is the structural source of alternate-branch candidates: the KDoc explicitly says multiple reachable semaphores are returned for parallel paths and that an alternate can be tried when the first path is blocked (`DefaultPathReservationService.kt:1240-1247`).

**Call-chain use in SP0.11 dispatcher shell:** `ShuntingLoop.toBlockInputObservation` calls `pathReservationService.findNextReservationTarget(to)?.let(::nameOf)` and publishes the result as `BlockInputObservation.toSeparatorName` (`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/ShuntingLoop.kt:348-384`). `RuleBasedDispatcher.checkInput` echoes that name into `DispatchDecision.ReservePath(trainId, towardSemaphoreName, toSeparatorName)` when an occupied/reserved block is eligible and the path is not already extended (`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/RuleBasedDispatcher.kt:130-158`, `162-187`).

### 1.2 InOutWorker reserving path-to-any-next-semaphore

**Function:** `InOutWorker.iteration()`
(`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/sim/InOutWorker.kt:66-155`).

- **Inputs:** the first queued `Train` from the InOut queue and the InOut's precomputed `next` track section.
- **Outputs:** either a reserved entry path for the train, or a retry/exception depending on `ReservationResult`.
- **State read:** queue head (`InOutWorker.iteration`, lines 68-83), train ID (`lines 84-96`), and `pathFree` condition, which calls `PathReservationService.isPathToAnyNextSemaphoreAvailable(inOut, next)` (`InOutWorker.kt:50-64`).
- **State mutated:** invokes `pathReservationService.reservePathToAnyNextSemaphore(trainId, inOut, next)` (`InOutWorker.iteration`, lines 93-96), which may reserve blocks and register PathInfo; after success it waits for the train to leave the queue (`lines 143-152`).
- **Information it does not have:** the worker has the `Train` object briefly, but it passes only `trainId`, `inOut`, and `next` to the reservation service (`InOutWorker.iteration`, lines 84-96). It does not pass the train's current separator/section to `PathReservationRegistry.mergePathInfo`.

### 1.3 Reserving to first reachable free target: `reservePathToAnyNextSemaphore`

**Function:** `DefaultPathReservationService.reservePathToAnyNextSemaphore(trainId, start, next)`
(`DefaultPathReservationService.kt:648-763`).

- **Inputs:** `trainId`, a dynamic separator `start`, and a required first `TrackSection` `next`.
- **Outputs:** `ReservationResult.Success`, `NoPathExists`, `AllPathsBlocked`, or `Conflict`.
- **State read:**
  - calls `findNextSemaphoresVia(start, next)` to enumerate all reachable semaphores/InOuts (`lines 659-666`);
  - extracts `nextBlock` from `next` for later validation (`lines 672-680`);
  - for each candidate, delegates to `reservePath(trainId, start, semaphore)` (`lines 682-692`).
- **State mutated:** indirectly through `reservePath`; if a successful reservation does not include the required `nextBlock`, this function rolls it back by cancelling path setup and unregistering each block (`lines 695-714`). On valid success it may configure the start semaphore signal (`lines 716-730`).
- **Information it does not have:** it records `attemptCount` and knows whether an earlier candidate returned `AllPathsBlocked` (`lines 682-739`), but that provenance is not included in `ReservationResult.Success` and is not passed to `registerPathInfo`.

### 1.4 Explicit reservation and the already-owned fast path: `reservePath`

**Function:** `DefaultPathReservationService.reservePath(trainId, start, target, maxDepth)`
(`DefaultPathReservationService.kt:167-434`).

- **Inputs:** `trainId`, dynamic `start`, dynamic `target`, and `maxDepth` (`lines 167-172`).
- **Outputs:** a `PathReservationService.ReservationResult` (`PathReservationService.kt:88-127`).
- **State read:**
  - candidate paths via `findCandidatePaths` (`DefaultPathReservationService.reservePath`, line 173; `findCandidatePaths`, lines 117-137);
  - blocks in each path via `extractUniqueBlocks(path)` (`reservePath`, lines 183-187);
  - each block's current `trainName` to filter blocks already owned by the same train (`lines 189-197`);
  - block availability with `areAllFreeOrOwnedBy(trainId)` (`lines 230-235`).
- **State mutated on normal success:**
  - reserves forward blocks via `tryAtomicReservation` (`line 244`);
  - registers ownership via `registry.registerAtomic(trainId, forwardBlocks)` (`lines 254-256`);
  - builds `PathInfo` (`lines 259-268`);
  - calls `registry.registerPathInfo(trainId, pathInfo)` (`lines 270-271`);
  - registers/locks/configures switches and signals and emits block events (`lines 277-387`).
- **Already-owned fast path:** when `forwardBlocks.isEmpty()`, all blocks in the candidate path are already owned by this train. The code comments identify two causes: occupied blocks already visited and blocks "Already reserved from a (possibly different) separator" (`DefaultPathReservationService.reservePath`, lines 189-193). In that case the function configures the start signal if needed (`lines 199-220`), builds `PathInfo` for the candidate path (`lines 221-223`), calls `registry.registerPathInfo(trainId, pathInfo)` (`line 223`), clears blocked tracking, and returns `Success(blocks)` (`lines 225-227`).
- **Information it does not have:** it does not know the train's true physical front/tail separator. It receives `trainId` as a string and endpoints, not a `Train` object (`reservePath`, lines 167-172). It can tell whether a candidate path's blocks are already owned (`lines 189-199`) and whether a specific call came through its own normal or fast path, but that fact is not carried into `PathReservationRegistry.registerPathInfo` (`lines 221-223`, `270-271`).

### 1.5 PathInfo registration: `registerPathInfo`

**Function:** `PathReservationRegistry.registerPathInfo(trainId, newPathInfo)`
(`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/PathReservationRegistry.kt:692-728`).

- **Inputs:** `trainId` and a `PathInfo` value.
- **Outputs:** no return value.
- **State read/mutated:** reads the existing `trainToPathInfo[trainId]` (`lines 692-697`). If none exists, stores `newPathInfo` (`lines 698-706`). Otherwise calls `mergePathInfo(trainId, oldPathInfo, newPathInfo)` (`lines 709-711`) and replaces `trainToPathInfo[trainId]` with the result (`line 711`).
- **Information it does not have:** it receives no reservation provenance, no selected-candidate index, no "fallback/alternate" flag, no `ReservationResult`, and no physical train state. The stored `PathInfo` type contains only `start`, `target`, `reservedPath`, and `entryDirections` (`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/objects/paths/PathInfo.kt:57-62`).

### 1.6 Merge and cycle detection: `mergePathInfo` + `addElementWithCycleDetection`

**Function:** `PathReservationRegistry.mergePathInfo(trainId, old, new)`
(`PathReservationRegistry.kt:830-886`).

- **Inputs:** `trainId`, previous `PathInfo`, and new `PathInfo` (`lines 830-834`).
- **Outputs:** merged `PathInfo`, or the old `PathInfo` if `addElementWithCycleDetection` aborts (`lines 864-867`, `880-885`).
- **State read/mutated:** it is a pure path-data operation; KDoc states it does not acquire/release track blocks or switches and that resource locking is independent (`PathReservationRegistry.kt:775-789`). It constructs a new `ArrayPath`, copies all old path elements (`lines 847-851`), and appends new elements with only one overlap rule: `skipFirst = (new.start == old.target)` (`lines 853-860`). It merges entry directions by copying old and then `putAll(new.entryDirections)` (`lines 870-872`).
- **Information it does not have:** no physical train position, no current block/section, no reservation provenance, and no knowledge of whether `new.start` is an alternate fork point or a circular revisit. That absence follows directly from its signature (`lines 830-834`) and `PathInfo` fields (`PathInfo.kt:57-62`).

**Function:** `PathReservationRegistry.addElementWithCycleDetection(element, mergedPath, trainId, old, new)`
(`PathReservationRegistry.kt:799-828`).

- **Inputs:** the element being appended plus the partially built `mergedPath`, `trainId`, `old`, and `new` (`lines 799-805`).
- **Outputs:** `null` to keep appending, or `old` to abort a merge (`lines 810-817`, `826-827`).
- **State read/mutated:** if `element` is a separator already present in `mergedPath`, it counts occurrences (`lines 806-810`). A 3rd occurrence aborts and returns `old` (`lines 810-817`). A 2nd occurrence is logged as a legitimate circular route and allowed (`lines 819-824`). It then appends the element to `mergedPath` (`line 826`).
- **Information it does not have:** it sees only the partially merged path and the two `PathInfo` shapes. It cannot tell why the repeated separator appeared.

## 2. Two concrete traces

The important structural predicate is:

```text
new.start appears in old.reservedPath, but new.start != old.target
```

At `mergePathInfo` level, that predicate is all the code can observe: the function has `old`, `new`, and `trainId` only (`PathReservationRegistry.mergePathInfo`, lines 830-834), and `PathInfo` has only `start`, `target`, `reservedPath`, and `entryDirections` (`PathInfo.kt:57-62`).

### 2a. Fork-overlap bug trace (constructed; failing run details still OPEN QUESTION)

**ASSUMPTION:** The exact intermittent `RuleBasedDispatcherDeterminismTest` failing trace has not been reproduced in this analysis. The following is the minimal `PathInfo` shape described by issue #742, using `vyhybna.xml`-style separator names.

Let the train already have a registered path that passed a fork point `vB` and ended on one branch:

```text
old.start        = B
old.target       = doB1
old.reservedPath = [B, t(B-zB), zB, t(zB-vB), vB, t(vB-doB1), doB1]
```

An alternate-branch reservation is then selected from the earlier fork point `vB` because the intended continuation is blocked:

```text
new.start        = vB
new.target       = doB2
new.reservedPath = [vB, t(vB-doB2), doB2]
```

Current `mergePathInfo` computes `skipFirst = (new.start == old.target)`, which is false (`vB != doB1`) (`PathReservationRegistry.mergePathInfo`, lines 853-860). It copies all old elements first (`lines 847-851`) and then appends all new elements. When `addElementWithCycleDetection` sees `vB`, the existing occurrence count is 1, so it logs/allows a "LEGITIMATE CIRCULAR ROUTE" second occurrence (`PathReservationRegistry.addElementWithCycleDetection`, lines 806-824). The merged path shape becomes:

```text
[B, t(B-zB), zB, t(zB-vB), vB, t(vB-doB1), doB1, vB, t(vB-doB2), doB2]
```

This is exactly the false-positive duplicate described by the issue: a fork-point overlap is appended as if it were a circular revisit. The mechanism above is verified against the current merge code; the exact `vB/doB1/doB2` failing runtime values remain an OPEN QUESTION.

### 2b. Genuine circular-loop revisit trace from `Issue316RegressionTest`

`Issue316RegressionTest.trains9plusOnCircularRouteNoDeadlock` defines a repeated segment list over `vyhybna.xml` elements. It documents loop 1 as `B → zB → vB → doB1 → vA → zA → A`, loop 2 reintroducing `B`, and a later loop-3 attempt where `B` would be a 3rd occurrence (`core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/navigation/Issue316RegressionTest.kt:175-219`). The test builds each `PathInfo` as `[start, track, end]` (`Issue316RegressionTest.kt:221-240`) using actual elements loaded from `vyhybna.xml` (`Issue316RegressionTest.kt:84-168`).

Immediately before segment 6, the test's registered `old` path is the result of segments 1-5:

```text
old.start        = B
old.target       = A
old.reservedPath = [
  B, t(B-zB), zB,
  t(zB-vB), vB,
  t(vB-doB1), doB1,
  vA, t(vA-zA), zA,
  t(zA-A), A
]
```

Segment 6 is explicitly `Triple(inOutB, trackBtoZB, semaphoreZB)` (`Issue316RegressionTest.kt:210-213`), so the next `new` is:

```text
new.start        = B
new.target       = zB
new.reservedPath = [B, t(B-zB), zB]
```

Here, too:

```text
new.start appears in old.reservedPath at an earlier position, and new.start != old.target
B appears in old.reservedPath, and B != A
```

The current merge code therefore has the same local facts as in the fork-overlap example: `skipFirst` is false (`PathReservationRegistry.mergePathInfo`, lines 853-860), and the first `B` appended from `new.reservedPath` is a second occurrence that `addElementWithCycleDetection` explicitly allows (`PathReservationRegistry.addElementWithCycleDetection`, lines 806-824).

### 2c. Side-by-side structural equivalence at `PathInfo` level

| Feature visible to `mergePathInfo` | Fork-overlap example | Issue316 circular revisit |
|---|---|---|
| `old.target` | `doB1` | `A` |
| `new.start` | `vB` | `B` |
| `new.start == old.target` | false | false |
| `old.reservedPath` contains `new.start` before the tail | yes (`vB`) | yes (`B`) |
| `new.reservedPath` contains `new.start` exactly once | yes | yes; the test helper builds `[start, track, end]` (`Issue316RegressionTest.kt:221-240`) and `mergePathInfo` requires exactly one occurrence (`PathReservationRegistry.kt:840-845`) |
| Provenance available to `mergePathInfo` | none | none |
| Physical train position available to `mergePathInfo` | none | none |

This table is the crux: the two cases are distinguishable in railway semantics, but not by `PathInfo` shape alone.

## 3. Disambiguation-signal inventory

### 3.1 Train's actual physical position

- **Origin:** `Train` maintains physical progress inside private `Site` state: local `where` in `Site.actions()` (`Train.kt:127-136`, advanced at `246-256`), private `current`/`next` fields (`Train.kt:118-124`), and public read-only properties derived from the front site (`frontSection`, `frontPosition`, `totalDistance`, `trainEntrySeparator`) (`Train.kt:1247-1293`).
- **Currently exposed elsewhere:** `DefaultNetworkPerceptionPort` snapshots train positions into `TrainPositionReading(trainId, velocity, acceleration, totalDistance, frontSectionName)` (`DefaultNetworkPerceptionPort.kt:212-219`; `TrainPositionReading.kt:40-46`). It does **not** include `trainEntrySeparator`, current separator, or a path-index cursor. `SimulationSnapshot` is immutable and may be consumed off-thread after capture (`SimulationSnapshot.kt:12-40`).
- **Accessibility to merge site:** `PathReservationRegistry` stores only registry maps and `PathInfo` (`PathReservationRegistry.kt:135-173`) and `registerPathInfo` receives only `trainId` and `PathInfo` (`PathReservationRegistry.kt:692-728`). No `Train` object or snapshot is available there.
- **Thread safety:** `DefaultNetworkPerceptionPort.snapshot()` is off-thread safe because it reads a `@Volatile` immutable snapshot (`DefaultNetworkPerceptionPort.kt:245-262`). Live mutation remains sim-thread-bound; `DefaultPathReservationService` KDoc says it is not thread-safe and assumes single-threaded access (`DefaultPathReservationService.kt:73-75`).
- **Invasiveness:** high. It would require passing live `Train` state or a richer, on-thread position snapshot from `ShuntingLoop`/ports through dispatcher decisions or reservation calls into `registerPathInfo`.
- **Circular misfire risk:** low if the signal is an authoritative current separator/path cursor; high if only `frontSectionName` or stale `SimulationSnapshot.totalDistance` is used, because current snapshots do not encode which occurrence of a repeated separator is meant.

### 3.2 Reservation-call provenance: fallback/alternate target

- **Origin:** `findNextReservationTarget` knows the ordered target list and selected first free target (`DefaultPathReservationService.findNextReservationTarget`, lines 848-858). `reservePathToAnyNextSemaphore` knows `attemptCount` and sees earlier `AllPathsBlocked` attempts before a later success (`DefaultPathReservationService.reservePathToAnyNextSemaphore`, lines 682-739). `findNextSemaphoresVia` is explicitly designed to enumerate parallel targets and allow alternatives when the first path is blocked (`DefaultPathReservationService.kt:1240-1247`).
- **Currently exposed:** no. `findNextReservationTarget` returns only `DynamicPathSeparator?` (`PathReservationService.findNextReservationTarget`, lines 341-375). `ReservationResult.Success` carries only `reservedBlocks` (`PathReservationService.kt:88-96`).
- **Accessibility to merge site:** medium. Add an internal reservation/registration provenance object or merge intent to calls that already invoke `registry.registerPathInfo`: the already-owned fast path (`DefaultPathReservationService.kt:221-223`) and normal success path (`DefaultPathReservationService.kt:259-271`).
- **Invasiveness:** medium. It changes `PathReservationRegistry.registerPathInfo` call sites and probably tests, but does not require making `Train` physical state globally accessible.
- **Circular misfire risk:** low if the default remains "append/continuation" and the fork-overlap mode is set only by the alternate-target selector. `Issue316RegressionTest` directly calls `registerPathInfo` with synthetic circular segments (`Issue316RegressionTest.kt:221-241`, `286-329`), so it would continue to exercise default circular behavior unless the implementation changed the default.

### 3.3 Already-owned fast-path signal

- **Origin:** `reservePath` computes `forwardBlocks = blocks.filterNot { block.trainName == trainId }` (`DefaultPathReservationService.kt:189-197`). If empty, all blocks in the candidate path are already owned by the train and the code comments call out a "possibly different" separator (`lines 189-193`, `199-227`).
- **Currently exposed:** no; this branch still calls `registry.registerPathInfo(trainId, pathInfo)` with the same signature as the normal branch (`lines 221-223`).
- **Accessibility to merge site:** medium-low. A merge intent such as `AlreadyOwnedFromPossiblyDifferentSeparator` could be passed to `registerPathInfo` from this branch only.
- **Invasiveness:** low-to-medium if limited to a new overload/default parameter.
- **Circular misfire risk:** not fully known. A genuine circular revisit can also involve already-owned blocks in tests or edge cases; the fast-path flag alone says "all blocks owned by same train", not "fork fallback". Used alone, it could still conflate circular and fork cases.

### 3.4 Explicit path cursor / registered-path consumption index

- **Origin:** `TrainNavigationService.determineNextFromPathInfo` currently scans from the beginning and returns the first matching separator's next track (`DefaultTrainNavigationService.determineNextFromPathInfo`, lines 225-285). Physical `Train.Site.actions()` advances `where` and `current` over time (`Train.kt:127-256`).
- **Currently exposed:** no per-train cursor is stored in `PathReservationRegistry`; only `trainToPathInfo` exists (`PathReservationRegistry.kt:165-173`).
- **Accessibility to merge site:** could be added as registry state updated by TrainNavigationService or Train when a separator/block is consumed.
- **Invasiveness:** high. It changes navigation semantics and requires careful updates from front/tail movement sites.
- **Circular misfire risk:** low if correct, because a cursor distinguishes the first `B` from the second `B`; high implementation risk because cursor updates must stay synchronized with train movement and tail cleanup.

### 3.5 Entry-direction differences

- **Origin:** `PathInfo.entryDirections` maps each reserved block to the most recent entry `TrackSection` (`PathInfo.kt:45-61`; `PathInfoBuilder.buildPathInfo`, lines 88-110). `mergePathInfo` merges directions by `old.toMutableMap().putAll(new.entryDirections)` (`PathReservationRegistry.kt:870-872`).
- **Currently exposed:** available inside `mergePathInfo` via `old.entryDirections` and `new.entryDirections`.
- **Invasiveness:** low.
- **Circular misfire risk:** high. The direct `Issue316RegressionTest` can use repeated/reused blocks and does not require physically continuous topology for the structural cycle guard (`Issue316RegressionTest.kt:188-196`), so entry-direction heuristics would be brittle. Also, the fork-overlap may use disjoint blocks after the fork, making direction differences insufficient to prove intent.

### 3.6 Duplicate-decision suppression state from PR #740

- **Origin:** `DispatchDecisionApplier` records successfully applied `(trainId, from, to)` triples in `appliedReservations` (`dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/DispatchDecisionApplier.kt:92-104`) and skips repeat triples before calling `NetworkActuatorPort.requestRoute` (`DispatchDecisionApplier.applyReservePath`, lines 176-184, 190-205).
- **Currently exposed:** local to the applier.
- **Accessibility to merge site:** not available in `core`; also irrelevant to `InOutWorker` callers.
- **Invasiveness:** high and wrong layer if used to solve this shared registry issue.
- **Circular misfire risk:** not applicable to alternate branch; it only suppresses exact duplicate triples.

## 4. Rejected-fix autopsy: why `lastIndexOf(new.start)` truncation corrupts Issue316

The rejected idea was: if `new.start` appears anywhere in `old.reservedPath`, use the last occurrence as the overlap point, truncate old to that point, then append `new` after that point. That is a path-position-only rule.

Mechanically, it fails the circular trace from section 2b at segment 6 in `Issue316RegressionTest.trains9plusOnCircularRouteNoDeadlock`:

1. The test constructs segments 1-5 as `B → zB → vB → doB1 → vA → zA → A` (`Issue316RegressionTest.kt:200-216`). After those registrations, the registered `old.reservedPath` is the 12-element path shown in section 2b, and `old.target = A`.
2. Segment 6 is `Triple(inOutB, trackBtoZB, semaphoreZB)` (`Issue316RegressionTest.kt:210-213`), so `new.start = B` and `new.reservedPath = [B, t(B-zB), zB]`.
3. `B` appears in `old.reservedPath` at index 0, while `old.target` is `A`.
4. A `lastIndexOf(new.start)` truncation would keep only the old prefix through that `B` and append the new suffix, producing approximately:

   ```text
   [B, t(B-zB), zB]
   ```

   instead of preserving the already accumulated loop path and appending a legitimate second occurrence.
5. The test explicitly guards against this class of regression: after each registration, it asserts the path size never decreases (`Issue316RegressionTest.kt:268-270`) and updates the last valid size only when it grows (`lines 273-276`). Truncating from the 12-element old path to a 3-element path would violate that assertion.

This is why the fix must not be "find the overlap by path position" without an independent signal that the call is an alternate-fork replacement rather than a circular revisit.

## 5. Recommendation

Recommend pursuing **explicit reservation provenance / merge intent**, not physical-position plumbing as the first implementation direction.

Sketch:

1. Keep `registerPathInfo(trainId, newPathInfo)` default behavior as today's append/circular-compatible merge. This preserves direct callers and `Issue316RegressionTest`'s synthetic circular registrations (`Issue316RegressionTest.kt:221-241`, `286-329`).
2. Add an internal merge intent/provenance path for reservation-service calls that know they are applying an alternate target after the primary path was blocked. Candidate origins are:
   - `reservePathToAnyNextSemaphore`, which already tracks ordered attempts and `AllPathsBlocked` before success (`DefaultPathReservationService.kt:682-739`);
   - `findNextReservationTarget`, if its read-only API is expanded or paired with an internal richer result that records selected target index / whether earlier targets were unavailable (`DefaultPathReservationService.kt:848-858`).
3. Use that explicit intent to select alternate-fork handling inside/near `registerPathInfo`; do not infer it from `new.start`'s position alone. The implementation may still need to name the fork separator, but that separator should be a field of the provenance supplied by the selector, not something `mergePathInfo` guesses from `lastIndexOf`.
4. Treat the already-owned fast path as related but insufficient by itself. It should probably carry a specific intent if it is reached for an alternate branch, because its own comment says blocks may be "Already reserved from a (possibly different) separator" (`DefaultPathReservationService.kt:189-193`), but that branch alone does not prove fork-overlap.

Why this direction:

- It introduces the disambiguating fact at the layer that has it: target selection / reservation attempt ordering (`DefaultPathReservationService.kt:682-739`, `848-858`).
- It avoids reaching from the registry into live `Train` internals; `PathReservationRegistry` is not thread-safe and currently has no train object access (`PathReservationRegistry.kt:65-75`, `135-173`).
- It avoids changing the default circular behavior that Issue316 relies on (`PathReservationRegistry.addElementWithCycleDetection`, lines 806-824; `Issue316RegressionTest.kt:175-219`).

Risks:

- The exact provenance source must be designed carefully for both dispatcher and `InOutWorker` call paths. `findNextReservationTarget` is read-only and currently loses candidate-order information at its return boundary (`PathReservationService.findNextReservationTarget`, lines 341-375), while `reservePathToAnyNextSemaphore` has attempt-order information but is not the function used by the SP0.11 dispatcher shell (`ShuntingLoop.toBlockInputObservation`, lines 348-384).
- If the implementation marks a genuine circular revisit as "alternate fork", it can reintroduce the rejected truncation failure. Therefore the new regression tests must include both fork-overlap and the existing Issue316 circular tests in the same targeted run.
- Carrying provenance through public interfaces may affect `:dispatcher-agent` and tests. Prefer an internal overload/defaulted parameter if possible.

### New fork-overlap regression test sketch

Goal: deterministic unit/integration-level test, not the flaky 5-train end-to-end determinism loop.

Proposed shape:

1. Load `vyhybna.xml` and obtain `PathReservationRegistry`, `PathReservationService`, and `TopologyNavigator`, following the setup pattern in `PathReservationRegistryMergingTest` (`PathReservationRegistryMergingTest.kt:92-166`) and `Issue316RegressionTest` (`Issue316RegressionTest.kt:84-168`).
2. Choose a real parallel-branch start whose `findNextSemaphoresVia` order has at least two targets; the likely candidates are the `vA/vB` parallel routes documented by `findNextSemaphoresVia` KDoc (`DefaultPathReservationService.kt:1272-1280`). Confirm the exact separator/target names in the test by querying `findNextReservationTarget` / reserving candidates, not by assuming order.
3. Reserve or otherwise occupy the first/preferred branch with a blocker train so the tested train's first target would be `AllPathsBlocked` but a second parallel target remains free. The service already surfaces `AllPathsBlocked` for blocked candidate paths (`DefaultPathReservationService.reservePath`, lines 420-434) and `reservePathToAnyNextSemaphore` continues to later targets after `AllPathsBlocked` (`lines 732-739`).
4. Pre-register for the tested train an `old PathInfo` that contains the fork separator earlier in `old.reservedPath` and has `old.target` on the abandoned/preferred branch, matching section 2a. Use actual `PathInfoBuilder` or the `ArrayPath` helper style from existing tests (`Issue316RegressionTest.kt:349-370`) so the path elements are real dynamic objects.
5. Invoke the same unit-level reservation path that production uses for the alternate target (`reservePath` or `reservePathToAnyNextSemaphore`, depending on where the provenance is implemented), and assert:
   - the alternate reservation succeeds;
   - the resulting `PathInfo` is structurally valid (no disconnected duplicate fork separator in the wrong place);
   - `TrainNavigationService.findReservedPathForTrain(trainId, forkSeparator)` returns an available path for the intended alternate branch.
6. Run this new test together with `Issue316RegressionTest` so the circular revisit remains protected.

## 6. Open questions for the implementer

1. **Exact failing fork trace:** The section 2a trace is an ASSUMPTION based on issue #742 and merge mechanics. Instrument a failing `RuleBasedDispatcherDeterminismTest` run to capture `old.start`, `old.target`, `old.reservedPath`, `new.start`, `new.target`, and `new.reservedPath` at `PathReservationRegistry.mergePathInfo` (`PathReservationRegistry.kt:830-886`) before finalizing the implementation.
2. **Which SP0.11 path produces the alternate:** Verify whether the remaining failure enters via `findNextReservationTarget` + `DefaultNetworkActuatorPort.requestRoute` (`ShuntingLoop.kt:348-384`; `DefaultNetworkActuatorPort.kt:85-127`) or via an `InOutWorker.reservePathToAnyNextSemaphore` path (`InOutWorker.kt:84-123`; `DefaultPathReservationService.kt:648-763`). The provenance origin differs.
3. **Candidate order in `vyhybna.xml`:** Confirm the deterministic target order returned by `findNextSemaphoresVia` for the specific fork; the code returns distinct InOuts first, then semaphores (`DefaultPathReservationService.prioritizeInOuts`, lines 1420-1427), and BFS branch ordering follows graph edge iteration (`exploreOutgoingPaths`, lines 1391-1414).
4. **Already-owned branch involvement:** Confirm whether the corrupting call uses `reservePath`'s `forwardBlocks.isEmpty()` fast path (`DefaultPathReservationService.kt:189-227`) or the normal success path (`lines 254-271`). This decides whether a fast-path-specific signal is useful or only incidental.
5. **Minimal public API change:** Determine whether provenance can remain internal to `DefaultPathReservationService`/`PathReservationRegistry`, or whether `PathReservationService.findNextReservationTarget` must return a richer value than `DynamicPathSeparator?` (`PathReservationService.kt:341-375`).

## Validation performed for this analysis

- Read issue #742 and PR #740 metadata through GitHub tools.
- Investigated the current PR branch's recent failed workflow run. The latest failed build for PR #740 failed in `:dispatcher-agent:test` due to `AgentLoopDriver` pacing tests, not because this analysis document changes code.
- Ran targeted existing tests before creating this document:

  ```bash
  JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :core:jvmTest \
    --tests 'cz.vutbr.fit.interlockSim.context.navigation.Issue316RegressionTest' \
    --tests 'cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistryMergingTest'
  ```

  Result: `:core Test Results: SUCCESS`, 20 tests passed, 0 failed.
