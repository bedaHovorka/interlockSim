# SP7: event-driven retry and deadlock watchdog (Goal 1B, Issue #1154)

Goal 9B demands recorded while replacing the polling retry of `MultiTrainLoop` and adding a
wait-for-cycle watchdog. Inputs read first: `SP1-fail-fast-harness.md` (demand 2, retry trigger),
`SP9-kdisco-review.md` (F8: `Resource.reserve` re-entry re-links a waiter at the FIFO tail).

## What changed

| Part | Before | After |
|---|---|---|
| Retry of a blocked entry | Every dispatcher tick (2 simulated seconds) re-ran `reservePath` for every unstarted train | A refused train waits in the loop's own FIFO deque. A release event (`BlockReleased` or `ReservationExpired`; since Issue #1271 also `OccupancyCleared` and `SwitchClaimsReleased`) wakes one retry process at the simulation time of the release; it attempts the waiting trains in queue order |
| `maxEntryAttempts` | Attempts, one per tick | The first attempt plus the retries that releases trigger. A run with no release makes no retry; the train stays queued to the run end and the run-end residue log names it |
| Stuck run | Silent until `endTime` | `SimulationStalled` and `errorStop` when a wait-for cycle persists |

Design choices:

- **No kDisco `Resource` queue.** SP9 F8: a re-entered `reserve` puts the waiter at the tail, so FIFO
  would not hold. The loop keeps its own `ArrayDeque`.
- **One admission policy.** The loop-level expiry runs at the top of the tick and a pending wake
  drains synchronously right after, before `startApprovedTrains`. Neither a release whose event
  queued before the tick, nor a stale holding released by an attempt's own `reservePath`, can let
  a fresh train outrun an older waiter (both bypasses were proven reachable in the review round).
- **Every release wakes.** A gate-block relevance model (wake only when the released block lies in
  a waiter's candidate gate) was tried in the review round and reverted: the two-trains-on-the-
  y-junction regression proved that the state change a waiter waits for can ride on the release of
  a block outside its gate -- the releasing train's switch position and occupancy free together
  with its last blocks. A wake is one cheap pass over the queue; irrelevant wakes burn attempts
  the cap already absorbs. A safe relevance model would need the switch/occupancy layer, which is
  Goal 9B demand 1.
- **Releases that queue before the wake are coalesced.** Several `BlockReleased` events at one
  simulation time (one per block of a released route) wake the retry process once; a release that
  arrives after the wake re-wakes the retry at the same instant and the metric counts each instant
  once.
- **A failing drain is contained.** An exception inside one drain kills neither the run nor the
  retry process: the waiters stay queued and later releases keep waking the retry.
- **An attempt's own rollback is not a release.** A refused `reservePath` rolls its candidate back
  inside the call; where such a rollback emits `BlockReleased` for its own train (the bypass
  rollback of `reservePathToAnyNextSemaphore`), the listener ignores events whose train is the one
  attempting.
  Without this a refused attempt would wake itself in a zero-time loop.
- **Listeners only record.** Both listeners run inside the reservation service; they set a flag and
  reactivate the retry process (`Process.reactivate`), nothing more.
- **FIFO equals polling order.** `RetryOrderEqualsPollingBaselineTest` pins the order of the
  successful entry grants, recorded on the polling implementation (`ef61e908`, before this change)
  as generation order `[0, 1, 2, 3]` for four trains contending for the single `A -> B` route.

## Wait-for graph the watchdog needs

Nodes are trains. As first shipped (#1154), an edge `waiter -> holder` existed when `waiter` was
approved but not started and `holder`, an approved train, owned a block of one of `waiter`'s
candidate routes (the route-search blocks the entry gate caches per `(entry, exit)` pair), found by
inverting `PathReservationService.getReservedBlocks` of every approved train. Since Issue #1269 the
edges come from what actually refused the waiter; see "Exact wait-for edges" below.

The persistence rules (`DeadlockWatchdog`): either the same cycle (same member set) on
`stallPersistTicks` (default 5, ten simulated seconds) successive dispatcher ticks, or any cycle on
ten times as many ticks (the churn bound -- member sets that alternate per tick never satisfy the
same-member rule), with the clock strictly later on each. A tick at an unchanged clock value is
ignored, so a frozen clock never fires it: that case is SP4's node budget. A tick without a cycle
restarts both counts; a cycle with other members restarts the same-member count.

Known approximations (these do not change today's behaviour, only what Goal 9B must know):

1. **AND model on an OR wait.** A waiter needs only one free candidate route, but the graph points at
   the holders of all of them. A cycle through such edges is not always a deadlock. The persistence
   rule bounds the risk; an exact test needs a knot search on the per-route graph. Since Issue #1269
   the per-route data exists: `MultiTrainLoop` keeps each waiter's `BlockedCandidate` list.
2. **Started trains wait for nobody.** `MultiTrainLoop` reserves the whole path before it starts a
   train, so a started train never waits on another train's reservation and never has an outgoing
   edge (it can be the target of one, Issue #1269). With full-path reservation the graph has no cycle in production (`fiveTrainCompleteness`
   asserts `stall == null` on JVM and native). The watchdog is a guard for the moment this
   invariant breaks, which is exactly what incremental reservation by a Goal 9B dispatcher does.
3. **Constructed cycle in tests.** `WatchdogDetectsCycleTest` stages the holdings with a fake
   reservation service, because the real service cannot produce the cycle. Since Issue #1269 the
   fake publishes them through `getBlockOwner` and names the route blocks in its refusals.
4. **The expiry hoist subsumes only pure-TTL staleness.** The loop's top-of-tick expiry clears
   whatever an attempt's own internal `expireStaleReservations` would find. The service's deferred
   releases (the #1220 tail-stall retry and the approach-lock booking window) can still free a
   block inside a `reservePath` call, so for that rare case a fresh train releasing-and-taking
   ahead of an older waiter is not excluded.

## Demands for Goal 9B

1. **Publish who blocks whom.** A dispatcher that reserves incrementally must expose, per waiting
   train, the owner of the block it needs next (`PathReservationService` needs a block-owner query
   and a "blocked by" result). The watchdog graph then gets exact edges, including started trains.
   The service half is done (Issue #1269, below); what remains for Goal 9B is to carry the
   blocked-by result through the multi-target reservations and the dispatcher ports.
2. **Avoid cycles by ordering.** A cycle needs two trains each holding what the other needs. A
   dispatcher avoids it by granting a train a route only when its whole remaining path is free
   (the current atomic rule), or by granting incremental routes in one global block order.
3. **Keep the release events.** Retry is only as good as the events: any state change that can
   unblock a train (block release, expiry, switch release, a physically freed block) must reach the
   retry as a `BlockReleased`, `ReservationExpired`, `OccupancyCleared` or `SwitchClaimsReleased`. A
   release without an event is a lost wake-up: the train waits until another release or the run
   ends. The audit below (Issue #1271) lists every path and its event or its justified exception.
4. **Treat `SimulationStalled` as a verdict, not a retry hint.** It carries the cycle, the number of
   persisted ticks and the time; a dispatcher should resolve the cycle (release one holding) before
   the persistence bound, not after.
5. **The attempt cap counts releases.** Size `maxEntryAttempts` for the number of releases a
   scenario produces, not for its duration.

## Exact wait-for edges (2026-10-31, Issue #1269)

Goal 9B demand 1, service half. Two additions to `PathReservationService`:

- **Block-owner query** `getBlockOwner(block)`, declared in the new `BlockOwnerQuery` interface that
  `PathReservationService` extends (the detekt function budget of `PathReservationService` is full,
  as for `BlockReleaseSteps`): the registry owner of the block; else the train the block records as
  reserved for (`DynamicTrackBlock.trainName`) or standing on it, the Issue #893 shape of a block
  held without a registry entry; `null` when no train is attributed. O(1), the inverse of
  `getReservedBlocks`.
- **Blocked-by result** `ReservationResult.AllPathsBlocked.blockedBy`: one `BlockedCandidate` per
  candidate refused on contention, with its index in the candidate list, `blockedBlocks` (each block
  that is not FREE or held by another train, with its owner at refusal time) and `lockedSwitches`
  (each switch of the candidate whose live claim belongs to another train). The field has a default
  (`emptyList()`), so every existing constructor keeps compiling.

The record is taken by `CandidateBlockerScan` after the candidate's own rollback, so it shows only
other trains' holdings. Which candidates are listed:

| Candidate refused at | Listed | Why |
|---|---|---|
| Step 2b, a forward block not free | yes | contention |
| Step 2c, the atomic block reservation failed | yes | contention (a TOCTOU race) |
| Step 2f, a switch locked by another train | yes | contention (Issue #1065: transient) |
| A later step that records no geometric failure (merge abort, a failed START write) | yes, possibly with no blocker | not proven permanent; an empty record says "no train holds anything here" |
| Step 2f unconfigurable switch, or a screened-out candidate (rear-facing end, divergent) | no | permanent: no release can make it reservable |

A stale switch claim is not listed: the waiter's next attempt reclaims it (`CandidateCommitter`
Step 2e.5), so it never refuses an entry. Only `reservePath` fills `blockedBy`; the multi-target
forms (`reservePathToAny`, `reservePathToAnyNextSemaphore`) and the facade/port mappings
(`DenialCause.AllPathsBlocked`, `RouteRequestResult.AllPathsBlocked`) still carry the count alone.

The watchdog graph (`MultiTrainLoop.buildWaitForGraph`) now draws from the waiter's last refusal: an
edge to the CURRENT owner (`getBlockOwner`) of each block the refusal named -- a block freed since adds
no edge -- and to the holder of each switch claim it named. The record cannot be staler than the
last release, because every release wakes a new attempt that replaces it. A holder no longer has to
be a train of the loop, nor share a block with the waiter's candidate routes:
`WaitForEdgesFromOwnersTest` runs two trains on the y-junction; once the first, started, train's
tail has cleared the shared entry block it holds only its exit block, yet its claim on the junction
refuses the second train. The candidate-route builder showed no edge in that window (red at 28.5 to
38.5 s); the new builder shows `waiter -> started train` on every sample of it.

Pins: `BlockOwnerQueryTest` (owner of a reserved, a free and an unattributed block; a refusal names
the train holding its block, and the train holding its switch), `WaitForEdgesFromOwnersTest`, and
`WatchdogDetectsCycleTest` and `fiveTrainCompleteness` unchanged in their assertions.

## Release-path audit (2026-10-31, Issue #1271)

Every state change that can turn a refused entry into a granted one, the event that reaches
`MultiTrainLoop.onReleaseSignal`, and the test that pins the wake. The tests stage a holder (a
reservation without a train process) that keeps one waiting train out, drive one path on it at a
known instant, and assert the retry activation at that instant and the waiter's grant:
`BlockReleasePathWakeTest` (linear track; tail expiry on `vyhybna.xml`) and
`SwitchReleasePathWakeTest` (y-junction). Both are `commonTest`, so they run on the JVM and on
linuxX64.

What a `reservePath` refusal can depend on, and so what a wake must cover: a block that is owned by
another train or not physically FREE (Step 2b, `isBlockAvailable`), a switch locked or claimed by
another train (Step 2f, `SwitchConfigOutcome.Locked`). The START signal check (G4) is static: it
reads the semaphore's facing, never its aspect.

### Paths with an event

| Path (production caller) | Code | Event | Test |
|---|---|---|---|
| Tail clears a block (`Train.Tail.separatorAction`) | `RouteReleaser.unregisterBlock` -> `dropFreedBlock`; `DynamicTrackBlock.leave` before it | `BlockReleased` (and `OccupancyCleared` from `leave`, coalesced) | `tailClearanceOfEachBlockWakesTheWaiter` |
| Train exit (`Train.actions` -> `releaseTrainReservations`) | `RouteReleaser.unregister` | `BlockReleased` per remaining block | `trainExitUnregisterWakesTheWaiter` |
| Committed per-block release (`RegistryPartialRouteReleaser`) | `RouteReleaser.releaseBlock` | `BlockReleased` | `releaseBlockWakesTheWaiter` |
| Unconditional route release (tests only) | `RouteReleaser.releasePath` -> `releaseAllBlocks` | `BlockReleased` per block | `unconditionalReleasePathWakesTheWaiter` |
| `cancel_route`, orphan sweeper (`NetworkActuatorPort.releaseRoute`) | `RouteReleaser.releasePathDetailed` -> `releaseAllBlocks` or `releaseAllBut` | `BlockReleased` per freed block | `cancelRouteReleaseWakesTheWaiterOnTheCallThatFreesTheRoute` |
| Expiry phase 1, loop tick or another train's `reservePath` | `DefaultReservationExpiry.expireStale` (whole holding) | `BlockReleased` + `ReservationExpired` | `wholeHoldingExpiryWakesTheWaiter` |
| Expiry phase 2 (stalled tail of a standing train) | `DefaultReservationExpiry.expireStale` -> `releaseAllBut` | `BlockReleased` + `ReservationExpired` | `tailExpiryOfAStandingTrainWakesTheWaiter` |
| Plain switch claim freed with its last adjacent block | `PathReservationRegistry.unregisterBlock` reclaims before `dropFreedBlock` emits | the block's `BlockReleased` | `switchReleaseRidesOnTheEventOfItsLastAdjacentBlock` |
| **New:** flank claim released at train exit after the last block is gone | `RouteReleaser.unregister` | **`SwitchClaimsReleased`** (internal) | `flankClaimReleasedAtTrainExitWakesTheWaiter` (red before) |
| **New:** a block left by a train the registry does not attribute it to (the #893 shape) | `DynamicTrackBlock.leave`; `unregisterBlock` has nothing to drop | **`OccupancyCleared`** (now a wake) | `leavingABlockTheRegistryDoesNotAttributeWakesTheWaiter` (red before) |

The two lost wake-ups the audit found, both fixed:

1. **Flank claim at the teardown.** A flank claim (`DefaultInterlockingFacade.lockRouteAtomic`)
   never goes stale, so it survives the train's last block and is released only by the wholesale
   unlock in `RouteReleaser.unregister`. That train holds no block any more, so no `BlockReleased`
   went out, and a waiter refused on that switch waited forever. `unregister` now emits the internal
   `SwitchClaimsReleased` for any claim it releases, and the loop wakes on it. `MultiTrainLoop`
   itself makes no flank claims (`reservePath` registers plain claims, and none born stale since
   #1173), so this closes the gap for a run that mixes it with a facade dispatcher. `releaseAllBlocks`
   needs no such event: `RouteReleaser.release` reaches it only for a train that still holds blocks,
   whose `BlockReleased` events go out in the same call.
2. **Unattributed occupancy.** The Tail's `unregisterBlock` drops only a block the leaving train
   owns. A block it stands on without a registry entry -- the #893 shape the expiry's occupancy scan
   already guards against -- became FREE with only `DynamicTrackBlock.leave`'s `OccupancyCleared`,
   which the loop ignored. The loop now wakes on `OccupancyCleared` too. For an owned block the
   `BlockReleased` of the same Tail step follows at once; both reach the loop before the retry runs,
   so the wake count and the retry order do not change.

### Paths without an event (justified)

| Path | Code | Why no wake is needed | Test |
|---|---|---|---|
| A refused candidate's own rollback | `CandidateCommitter.rollbackRegistryConflict`, `rollbackAndClassifyReservationFailure`, `rollbackUnconfigurableCandidate` (`RouteReleaser.rollbackBlock`, `rollbackReservation`, `SwitchConfigurator.releaseCandidateSwitches`), the merge-abort and START-signal rollbacks | `reservePath` never suspends, so no other process sees the candidate's blocks or switches held; after the rollback the state equals the state before the call, so nothing is free that was not free before. A wake would only burn an attempt (and the own-train filter of `onReleaseSignal` would drop one: the rollback runs inside the attempting train's call) | `candidateRollbackOfAnotherAttemptIsNotAWake` |
| Stale foreign switch claim reclaimed by somebody else's attempt | `SwitchConfigurator.reclaimStaleForeignSwitchOwnership`, `PathReservationRegistry.reclaimStaleSwitchOwnership` (also inside `rollbackBlock`) | Self-healing: the waiter's own attempt runs the same reclaim (Step 2e.5) before its switch check, so a stale claim never refuses an entry | `staleForeignSwitchClaimNeedsNoWake` |
| Orphan switch lock (locked, no owner) | `SwitchConfigurator.releaseOrphanSwitchLocks` | Self-healing, as above (Step 2e.5) | `orphanSwitchLockNeedsNoWake` |
| Approach-lock deferral and refused untrimmable release | `RouteReleaser.deferUntrimmable`, `refuseUntrimmable`; deferred expiry | Frees no block (signals go to STOP only); the later call that frees the blocks emits | first call of `cancelRouteReleaseWakesTheWaiterOnTheCallThatFreesTheRoute` |
| Signal resets | `ClearedSignalLedger.resetClearedSemaphores`, `resetSemaphoresForReleasedBlocks` | `reservePath` has no aspect precondition (the G4 check reads the facing only), so a reset cannot unblock a waiter; resets also always come with, and before, the block release of the same call | none: no refusal reads an aspect |
| Bypass rollback of `reservePathToAnyNextSemaphore` | `DefaultPathReservationService.releaseBypassRollbackBlocks` | Emits `BlockReleased` anyway (it balances the announced reservation, #961); in-call like the rows above, and `MultiTrainLoop` does not call it | none needed |
| Facade rollbacks | `DefaultInterlockingFacade.rollbackBlocks`, `rollbackRouteLocks` | In-call like the candidate rollback: they undo only what the same request acquired; the facade is the dispatcher's path, not the loop's | none needed |
| The loop's own `BlockResourceRegistry` gate | `BlockResourceRegistry.releaseFreeResources` | The gate is inert: it never refuses an attempt (see its KDoc) | none needed |

The own-train filter of `onReleaseSignal` stays: an attempting train's own release is in-call by
the first row. A train that releases blocks while some other train is attempting cannot exist,
because no other process runs inside `reservePath`.

## Measurements

- **Option H** (Goal 9 inventory) is not referenced from production code. Grep over `core`,
  `dispatcher-agent`, `desktop-ui` and `fast-sim` main sources for `option h` / `optionH`
  (case-insensitive): no match.
- **`sim/` golden output** unchanged: the retry changes when an attempt happens, not what a
  successful attempt reserves; the full gate (JVM and linuxX64 tests) is green.

### #895 timing re-baseline (local, JDK 21)

| Step | Before (`ef61e908`) | After |
|---|---:|---:|
| `:core:linuxX64Test` (native, 1196 -> 1207 tests) | 1:44 incl. compile (cold) | 0:25 test run only (`--rerun`) |
| Full gate `build detekt ktlintCheck test integrationTest` | 4:41 (first run, detekt failed late) | 2:47 |

The native test run takes 25 s with the new tests: the four new `commonTest` classes and the
`ReleasingMultiTrainLoop` helper together run
in well under 5 s on the JVM (largest: `EventDrivenRetryTest`, below 1 s), inside the 30 s budget.
The "before" native time includes the compile of a cold build and is not like-for-like; the CI step
times (`Run linuxX64 native tests`) are to be read from the PR run.
