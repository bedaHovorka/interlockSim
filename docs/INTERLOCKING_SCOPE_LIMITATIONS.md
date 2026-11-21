# Interlocking Scope Limitations

**Last Updated:** 2026-10-29
**Related Issue:** #893 (path-reservation review, Phase 6)

## Purpose

This document records the deliberate abstraction-level simplifications in the simulator's interlocking model versus real ESA-11 / SŽ (Správa železnic) interlocking practice. It exists so future readers know what is **intentionally not modelled**, why each omission is still safe in this simulator, and where the abstraction would have to grow to match a real installation. It is the doc-only companion to the Phase 5 code fixes (F1/F2/F3, PR #901, squash `0d348f2b`) and the G1–G7 signal-clearing invariants documented in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) §5.

The findings F4 (simplifications) and F5 (revisiting-route caveat) are gemma4 Phase-4 rulings: SIMPLIFICATION-ACCEPTABLE with a fail-safe caveat.

---

## B1. Cancel = Release Conflation (F4) — SIMPLIFICATION-ACCEPTABLE

Real railways distinguish two operationally distinct release concepts:

- **storno** (cancel *before* occupancy): a dispatcher command. The signal goes to **Stop first**, then a mandatory **time-release timer** must elapse before the *závěr* (route lock) frees — SŽ čj. 51861/2024-SŽ-GŘ-O14 prescribes 180 s (non-ETCS) / 202 s (ETCS L2) for train routes, 60 s for shunt routes. The timer protects against a train already approaching the signal on a sighted (now-cancelled) proceed aspect from overrunning into the newly-freed route.
- **uvolnění** (release *after* occupancy): automatic / progressive / sectional release behind the train as it clears each section.

The simulator has **one** release path (the nine release paths in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) §6) and **no approach-locking timer**. The distinction between *storno* and *uvolnění* is conflated into a single "free the blocks" operation.

### Why this is acceptable (the actual structural reason)

**Admission is gated on reservation, and every signal is re-read live at its boundary.** Two distinct mechanisms are at play, and only the second one carries safety *after* a route is changed:

1. **Entry gate** — a train is admitted to the network only once the interlocking has reserved the blocks ahead AND cleared the START signal. There is no sighting/visibility predicate anywhere in the admission gate:
   - `InOutWorker.pathFree` is a `Condition` polling `isPathToAnyNextSemaphoreAvailable(inOut, next)`.
   - `InOutWorker` calls `waitUntil(pathFree)`: the train waits until a free path exists.
   - `InOutWorker` then calls `reservePathToAnyNextSemaphore(trainId, inOut, next)`; only on `Success` does the train proceed.

   This covers the *admission* hazard: a train is never given proceed authority over unreserved track at entry.
2. **Live-aspect re-read at every semaphore** — the train's `Front` consults the signal when it reaches the semaphore's decision point (the `Signal.STOP` check in `Train.Front`): on `Signal.STOP` it halts and suspends in `waitUntil(allowingSignal(...))`. The aspect consulted is the live one, not one sighted earlier and acted upon past the decision point, and the kinematics stop the train exactly at the signal boundary (no overrun physics — see §B3).

The *storno* time-release timer is a real-world defence against a human driver who has already sighted a (soon-cancelled) proceed aspect and can no longer be stopped short of the signal. This simulator has no such driver: a cancellation reverts the aspect to STOP, and a train approaching the now-cancelled boundary reads STOP and halts there. Note precisely which mechanism the absence of the timer rests on: **the release has no stopped-train/velocity guard of its own — its guard is the approach lock (a block that is approach-locked, occupied or inside the deferral window is kept reserved, Issues #1050 and #974), and once a cancellation does remove a reservation, reservation gating alone no longer protects a train that was previously cleared.** Safety therefore rides on the live-aspect re-read plus the ideal stop at the boundary, not on "reservation before clear" alone. (gemma4 Phase 4 Q2 ruling: SIMPLIFICATION-ACCEPTABLE.)

**Invariant for future changes:** any change to braking, signal response, or the admission gate must preserve "the train consults the live aspect at the semaphore and stops at the boundary on STOP". Weakening that invariant (e.g. caching a previously-seen proceed aspect, letting a train carry velocity past a reverted signal, or adding overrun physics) silently imports exactly the hazard the *storno* timer exists to prevent — at which point approach locking becomes mandatory here too.

A grep of `core/src/commonMain` and `dispatcher-agent/src/main` confirms that "storno" and "sighting" appear nowhere in the production source — the concepts are absent by design, not by oversight.

### The "cancelled between clearance and first movement" edge case — measured (#834, SP2c.11; behaviour changed by #905)

The one moment the release's missing velocity guard (noted above) could bite hardest is the narrowest one: after `Train.actions()`'s admission gate (`isPathReservedForTrain`) has passed but before the `Front` has moved a metre. The `Front`'s first loop iteration sits at the entry `InOut` with `current == null`. Since Issue #905 (PR #918) a missing route there no longer ends the movement loop: the `Front` takes the same wait-for-dispatcher branch as every later position (`waitForPathOrReportStall` for an ownership conflict, `holdOrStopAtOriginWithoutPath` when the network offers no topological continuation).

Two things were established about it, and neither required a `core/` change:

- **It is not currently reachable by cancelling a route.** Every releaser — `DispatchDecisionApplier` applying `cancel_route`/`ReleaseRoute`, and `OrphanReservationSweeper.sweep` — runs synchronously inside `ShuntingLoop.iteration()`, before the loop's `hold()`. The admission chain (`InOutWorker` reserving → `Train.actions()` resuming → `Process.activate(front)` → the `Front`'s first query) runs strictly afterwards in the same simulated instant on the single kDisco scheduler thread, with no releaser interleaved. `CancelRouteTool` only emits a `DispatchAction` for that same sim thread, so the agent thread cannot short-circuit it. `isPathReservedForTrain` is implemented on top of `findReservedPathForTrain`, so the gate and the `Front`'s query are the same predicate over the same state.
- **If the state is produced anyway, it does not inflate any counter.** Forced deterministically (`EntryRouteLossAccountingTest`, `:dispatcher-agent`), the `Front` now waits in the origin branch instead of exiting, so the `Train` process never terminates and `ShuntingLoop`'s `trainsExitedCount`, which counts only terminated trains, never credits it. The exited counter under-reports here; it does not over-report.

What it *does* cost is a stall, now bounded: the train holds its admission slot and the head of its `InOutWorker` queue until the dispatcher reserves a path. An ownership-conflict wait logs one WARN after `OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS` (60 s of simulated time) and stops the run through `errorStop` at `OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS` (180 s, Issue #943); a missing topological path stops it after `MAX_ORIGIN_NO_PATH_RETRIES` (Issue #905).

---

## B2. Flank-Protection Switch-Lock Scope (F4)

`PathReservationService` locks **only on-route (running) switches**, NOT flank-protection (*odvratné*) switches/derailers:

- `SwitchConfigurator.extractUniqueSwitches` derives switches solely from `pathInfo.reservedPath` — the running path.
- `CandidateCommitter.configureAndRegisterSwitches` → `SwitchConfigurator.registerSwitches` → `registry.registerSwitches` locks each on-route switch. No flank switches are provisioned or locked.
- Since Issue #1065, that lock is now HONOURED by the reservation path itself, not only by the legacy facade below: `DynamicRailSwitch.setUpPath` refuses to move a switch a live route already holds locked in a different position (safety property SI-5, `changeConf` already honoured it; `setUpPath` did not). Before #1065, `configureSwitchesInPath` silently re-threw an on-route switch already locked for a DIFFERENT train's live route.

Flank protection is a concept of the **legacy `InterlockingFacade` path only**:

- The `InterlockingFacade` class KDoc — ESA-11 condition 2: "All flank-protection switches (odvratné výhybky) are in their required (safe) position."
- `TrainRoute.flank` (`lang/vocab/TrainRoute.kt`, the `flank` property and its `@property` KDoc) — `val flank: List<SwitchSetting> = emptyList()`.
- `DefaultInterlockingFacade`: the condition-2 call `checkSwitchPositions(route)` in `requestRoute`, the granted-route log's switch count (`route.running.size + route.flank.size`), the `checkSwitchPositions` method itself, its `for (switchSetting in route.flank)` loop, and `lockSwitches(trainId, runningSwitches, flankSwitches)` (the `running + flank` switches locked).

**Known scope limitation:** the reservation-service path does not provision flank protection. It relies on the route's running switches being sufficient, or on the facade path for flank-sensitive layouts. A topology that requires flank-protection switches to be locked for safety, accessed only through the reservation-service path, would not have them locked.

**Consequence for Issues #1065/#1076/#1103's stale-lock reclamation:** `PathReservationRegistry.reclaimStaleSwitchOwnership` -- run by `unregisterBlock` for the freed block's ends on every per-block release path since Issue #1103 (whole-route teardown goes through `registry.unregister`, which releases the switch claims wholesale itself), and by `SwitchConfigurator.reclaimStaleForeignSwitchOwnership` for a candidate's switches (Step 2e.5, Issue #1076) -- releases a switch once its owner holds no BLOCK adjacent to it. That predicate assumes a switch protects only the route(s) it is physically adjacent to. Facade flank grants are the exception — a flank switch protects a route it is NOT adjacent to — so since the Issue #1076 review round the registry marks them: `lockRouteAtomic` registers pure flank switches through `PathReservationRegistry.registerFlankSwitches`, and `PathReservationRegistry.isStaleSwitchOwnership` -- the one staleness rule BOTH reclamation call sites use -- never reports a marked switch as stale while its registration stands (the marker is cleared by `unregisterSwitch`/`unregisterSwitches` on release). A future reservation-service flank provisioning (F4) must use the same marker, or extend the reclamation predicates, or both.

---

## B3. Overlap Locking Beyond the Route (F4)

Real interlocking locks an **overlap** — a safety margin beyond the route's exit, in case the train overruns the destination signal. The simulator does not model overlap locking: a reservation covers exactly the blocks from START to destination separator, nothing beyond.

The right defence of this simplification is NOT "an overrun stays within reserved territory" — it cannot. The reservation terminates at the destination separator, and an overrun is by definition *beyond* that endpoint, on track the reservation never covered. Nor are all destinations topologically harmless exits: a destination semaphore inside the network can have another route's reserved territory directly behind it, so an overrun there could enter a conflicting path.

The actual reason this is a **deliberate simplification**: **signal overrun is not modeled at all**. A train reading `Signal.STOP` at a semaphore stops exactly at the signal boundary — the continuous kinematics decelerate the `Front` to standstill at the separator (`Train.kt`), with no braking-distance shortfall and no momentum carrying the train past the stop line. With overruns physically impossible, an overlap would have nothing to protect against. **If non-ideal stopping or braking distances are ever introduced, overlap locking (or an equivalent margin) must be added at destination semaphores — this simplification silently becomes unsafe otherwise.**

---

## B4. Three Locking Mechanisms Conflated Into One Release Path (F4)

Real interlocking has three distinct locking mechanisms with different release conditions:

1. **Approach locking** — timer-gated cancel (see §B1: the *storno* time-release timer). The simulator has none.
2. **Route locking** (*Závěr*) — the route itself, released progressively behind the train.
3. **Section locking** (track-circuit hold) — a section stays locked while physically occupied, independent of route.

The simulator has **one** release path that conflates all three: `releasePath` / `unregister` / `unregisterBlock` (and the approach-locked `releasePathDetailed`, which the production port's `releaseRouteDetailed` and the facade's `releaseRoute` both use and which keeps approach-locked, occupied and deferral-window blocks reserved) free a block when it transitions to FREE (on `leave()`) or when the route is cancelled, with no separate approach-timer or section-circuit distinction.

### Mapping to ESA-11 terminology

| ESA-11 concept | Simulator analogue |
|---|---|
| *Volnost jízdní cesty* (Route freedom) | `areAllFree()` / `isPathAvailable()` — the FREE/availability check |
| *Závěr* (Route lock) | The atomic `reservePath` reservation + on-route switch lock (`registry.registerSwitches`) |
| Mutual exclusion (conflicting routes) | `RegistrationResult.Conflict` / `AllPathsBlocked` |
| Section locking (track-circuit hold) | The `OCCUPIED` state in the `FREE → RESERVED → OCCUPIED → FREE` state machine — a block stays `OCCUPIED` while the train is physically present, independent of whether the route is still "locked" |

The conflation is a **known simplification**: the simulator does not distinguish the three mechanisms because the single-threaded, reservation-gated model makes the distinctions unnecessary for the safety properties it enforces. A real installation's three-tier release discipline (approach-timer expiry → route release → section release) is collapsed into one.

---

## B5. Revisiting / Circular-Route Partial-Release Caveat (F5)

`resetSemaphoresForReleasedBlocks` is **fail-safe but not proven-safe** for a revisiting / circular route: it is proven safe only for suffix / rearmost releases on a non-revisiting route, and the semaphore it resets may govern a still-reserved downstream block, or re-entry into a loop. The failure direction is fail-safe (`Signal.STOP` authorises nothing, so the worst case is a stalled train), and the caller is responsible for staying within the proven-safe scope. The full statement and the interface KDoc quote are in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) §5 (Proven-Safe Scope).

---

**End of Document**