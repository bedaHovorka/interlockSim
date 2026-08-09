# Interlocking Scope Limitations

**Last Updated:** 2026-08-09
**Related Issue:** #893 (path-reservation review, Phase 6)

## Purpose

This document records the deliberate abstraction-level simplifications in the simulator's interlocking model versus real ESA-11 / SŽ (Správa železnic) interlocking practice. It exists so future readers know what is **intentionally not modelled**, why each omission is still safe in this simulator, and where the abstraction would have to grow to match a real installation. It is the doc-only companion to the Phase 5 code fixes (F1/F2/F3, commit `d99862bc`) and the G1–G7 signal-clearing invariants documented in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) v1.2 §5.

The findings F4 (simplifications) and F5 (revisiting-route caveat) are gemma4 Phase-4 rulings: SIMPLIFICATION-ACCEPTABLE with a fail-safe caveat.

---

## B1. Cancel = Release Conflation (F4) — SIMPLIFICATION-ACCEPTABLE

Real railways distinguish two operationally distinct release concepts:

- **storno** (cancel *before* occupancy): a dispatcher command. The signal goes to **Stop first**, then a mandatory **time-release timer** must elapse before the *závěr* (route lock) frees — SŽ čj. 51861/2024-SŽ-GŘ-O14 prescribes 180 s (non-ETCS) / 202 s (ETCS L2) for train routes, 60 s for shunt routes. The timer protects against a train already approaching the signal on a sighted (now-cancelled) proceed aspect from overrunning into the newly-freed route.
- **uvolnění** (release *after* occupancy): automatic / progressive / sectional release behind the train as it clears each section.

The simulator has **one** release path (the six call sites in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) §6) and **no approach-locking timer**. The distinction between *storno* and *uvolnění* is conflated into a single "free the blocks" operation.

### Why this is acceptable (the actual structural reason)

**Admission is gated on reservation, and every signal is re-read live at its boundary.** Two distinct mechanisms are at play, and only the second one carries safety *after* a route is changed:

1. **Entry gate** — a train is admitted to the network only once the interlocking has reserved the blocks ahead AND cleared the START signal. There is no sighting/visibility predicate anywhere in the admission gate:
   - `InOutWorker.kt:50-64` — `pathFree` is a `Condition` polling `isPathToAnyNextSemaphoreAvailable(inOut, next)`.
   - `InOutWorker.kt:72` — `waitUntil(pathFree)`: the train waits until a free path exists.
   - `InOutWorker.kt:93-95` — `reservePathToAnyNextSemaphore(trainId, inOut, next)`; only on `Success` (`:99-106`) does the train proceed.

   This covers the *admission* hazard: a train is never given proceed authority over unreserved track at entry.
2. **Live-aspect re-read at every semaphore** — the train's `Front` consults the signal when it reaches the semaphore's decision point (`Train.kt` ~:440-461): on `Signal.STOP` it halts and suspends in `waitUntil(allowingSignal(...))`. The aspect consulted is the live one, not one sighted earlier and acted upon past the decision point, and the kinematics stop the train exactly at the signal boundary (no overrun physics — see §B3).

The *storno* time-release timer is a real-world defence against a human driver who has already sighted a (soon-cancelled) proceed aspect and can no longer be stopped short of the signal. This simulator has no such driver: a cancellation reverts the aspect to STOP, and a train approaching the now-cancelled boundary reads STOP and halts there. Note precisely which mechanism the absence of the timer rests on: **`releaseRoute` has no stopped-train/velocity guard — after a cancellation removes the reservation, reservation gating alone no longer protects a train that was previously cleared.** Safety therefore rides on the live-aspect re-read plus the ideal stop at the boundary, not on "reservation before clear" alone. (gemma4 Phase 4 Q2 ruling: SIMPLIFICATION-ACCEPTABLE.)

**Invariant for future changes:** any change to braking, signal response, or the admission gate must preserve "the train consults the live aspect at the semaphore and stops at the boundary on STOP". Weakening that invariant (e.g. caching a previously-seen proceed aspect, letting a train carry velocity past a reverted signal, or adding overrun physics) silently imports exactly the hazard the *storno* timer exists to prevent — at which point approach locking becomes mandatory here too.

A grep of `core/src/commonMain` and `dispatcher-agent/src/main` confirms that "storno" and "sighting" appear nowhere in the production source — the concepts are absent by design, not by oversight.

### The "cancelled between clearance and first movement" edge case — measured (#834, SP2c.11)

The one moment the guard-less `releaseRoute` noted above could bite hardest is the narrowest one: after `Train.actions()`'s admission gate (`waitUntil { isPathReservedForTrain(...) }`) has passed but before the `Front` has moved a metre. The `Front`'s first loop iteration sits at the entry `InOut` with `current == null`, and `Train.kt`'s `if (path == null || next == null) { if (where is DynamicInOut) break }` makes a missing route there an immediate exit from the movement loop rather than the wait-for-dispatcher branch every later position takes.

Two things were established about it, and neither required a `core/` change:

- **It is not currently reachable by cancelling a route.** Every releaser — `DispatchDecisionApplier` applying `cancel_route`/`ReleaseRoute`, and `OrphanReservationSweeper.sweep` — runs synchronously inside `ShuntingLoop.iteration()`, before the loop's `hold()`. The admission chain (`InOutWorker` reserving → `Train.actions()` resuming → `Process.activate(front)` → the `Front`'s first query) runs strictly afterwards in the same simulated instant on the single kDisco scheduler thread, with no releaser interleaved. `CancelRouteTool` only emits a `DispatchAction` for that same sim thread, so the agent thread cannot short-circuit it. `isPathReservedForTrain` is implemented on top of `findReservedPathForTrain`, so the gate and the `Front`'s query are the same predicate over the same state.
- **If the state is produced anyway, it does not inflate any counter.** Forced deterministically (`EntryRouteLossAccountingTest`, `:dispatcher-agent`), the `Front` breaks with zero movement, but `Train.actions()` stays parked forever in `waitUntilCrossing { (getLength() - dtMin) - front.getTotalDistance() }` — a distance the train will never cover — so the `Train` process never terminates and `ShuntingLoop`'s `trainsExitedCount`, which counts only terminated trains, never credits it. The exited counter under-reports here; it does not over-report.

What it *does* cost is a permanent stall: the train holds its admission slot and the head of its `InOutWorker` queue for the rest of the run, and `Front.separatorAction` throws a FATAL `SimulationException` ("Path to semaphore first element must match current position: null") on the `Front`'s own coroutine on the way out, where it is reported but does not stop the run. Making the entry case take the wait-for-dispatcher branch instead of `break` is a `core/` behaviour change and needs a traffic-simulation-expert ruling; it is recorded here rather than made.

---

## B2. Flank-Protection Switch-Lock Scope (F4)

`PathReservationService` locks **only on-route (running) switches**, NOT flank-protection (*odvratné*) switches/derailers:

- `extractUniqueSwitches` (`DefaultPathReservationService.kt:1996-2008`) derives switches solely from `pathInfo.reservedPath` — the running path.
- `configureAndRegisterSwitches` (`:2493-2512`) → `registry.registerSwitches` (`:2507`) locks each on-route switch. No flank switches are provisioned or locked.

Flank protection is a concept of the **legacy `InterlockingFacade` path only**:

- `InterlockingFacade.kt:24-30` — ESA-11 condition 2: "All flank-protection switches (odvratné výhybky) are in their required (safe) position."
- `TrainRoute.flank` (`lang/vocab/TrainRoute.kt:22` KDoc, `:33` field) — `val flank: List<SwitchSetting> = emptyList()`.
- `DefaultInterlockingFacade.kt:150` (condition-2 check), `:181` (lock count), `:326` (condition-2 method), `:340` (`for (switchSetting in route.flank)`), `:374` (`route.running + route.flank` locked).

**Known scope limitation:** the reservation-service path does not provision flank protection. It relies on the route's running switches being sufficient, or on the facade path for flank-sensitive layouts. A topology that requires flank-protection switches to be locked for safety, accessed only through the reservation-service path, would not have them locked.

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

The simulator has **one** release path that conflates all three: `releasePath` / `unregister` / `unregisterBlock` free a block when it transitions to FREE (on `leave()`) or when the route is cancelled, with no separate approach-timer or section-circuit distinction.

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

`resetSemaphoresForReleasedBlocks` is **fail-safe but not proven-safe** for a revisiting / circular route. Per the interface KDoc (`PathReservationService.kt:693-706`):

> This is proven safe for **suffix / rearmost releases on a non-revisiting route**: the un-travelled tail of a route the train will never traverse again. It is NOT proven safe for an arbitrary mid-route subset of `blocks` — the semaphore governing a released block can also be the one a DIFFERENT, still-reserved downstream block on the same route depends on. A route that loops back and becomes adjacent to a released block again has the same exposure: the semaphore this call resets may be the one that governs re-entry into the loop.

**Failure direction is fail-safe** — `Signal.STOP` authorises nothing, so the worst case is a stalled train, never an unauthorized opposing movement. The caller is responsible for staying within the proven-safe scope (`DefaultPathReservationService.kt:2717-2719`): the impl performs no route-position validation of its own.

Cross-reference: the same caveat appears in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) v1.2 §5 (Proven-Safe Scope).

---

**End of Document**