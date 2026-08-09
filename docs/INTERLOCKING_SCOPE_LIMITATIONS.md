# Interlocking Scope Limitations

**Last Updated:** 2026-08-09
**Related Issue:** #893 (path-reservation review, Phase 6)

## Purpose

This document records the deliberate abstraction-level simplifications in the simulator's interlocking model versus real ESA-11 / SŽ (Správa železnic) interlocking practice. It exists so future readers know what is **intentionally not modelled**, why each omission is still safe in this simulator, and where the abstraction would have to grow to match a real installation. It is the doc-only companion to the Phase 5 code fixes (F1/F2/F3, commit `d99862bc`) and the G1–G7 signal-clearing invariants documented in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) v1.1 §5.

The findings F4 (simplifications) and F5 (revisiting-route caveat) are gemma4 Phase-4 rulings: SIMPLIFICATION-ACCEPTABLE with a fail-safe caveat.

---

## B1. Cancel = Release Conflation (F4) — SIMPLIFICATION-ACCEPTABLE

Real railways distinguish two operationally distinct release concepts:

- **storno** (cancel *before* occupancy): a dispatcher command. The signal goes to **Stop first**, then a mandatory **time-release timer** must elapse before the *závěr* (route lock) frees — SŽ čj. 51861/2024-SŽ-GŘ-O14 prescribes 180 s (non-ETCS) / 202 s (ETCS L2) for train routes, 60 s for shunt routes. The timer protects against a train already approaching the signal on a sighted (now-cancelled) proceed aspect from overrunning into the newly-freed route.
- **uvolnění** (release *after* occupancy): automatic / progressive / sectional release behind the train as it clears each section.

The simulator has **one** release path (the six call sites in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) §6) and **no approach-locking timer**. The distinction between *storno* and *uvolnění* is conflated into a single "free the blocks" operation.

### Why this is acceptable (the structural reason)

**Entry is gated on reservation, not signal-sighting.** A train is admitted to the network only once the interlocking has reserved the blocks ahead AND cleared the START signal — there is no sighting/visibility predicate anywhere in the admission gate:

- `InOutWorker.kt:50-64` — `pathFree` is a `Condition` polling `isPathToAnyNextSemaphoreAvailable(inOut, next)`.
- `InOutWorker.kt:72` — `waitUntil(pathFree)`: the train waits until a free path exists.
- `InOutWorker.kt:93-95` — `reservePathToAnyNextSemaphore(trainId, inOut, next)`; only on `Success` (`:99-106`) does the train proceed.

Because the block ahead is reserved for this train alone before it is ever cleared to enter, an **overshoot-collision is impossible** — there is no opposing movement to protect against with a timer. The *storno* time-release timer is a real-world defence against human/sighting error (a dispatcher cancelling a route a driver has already sighted); this gate removes that hazard by construction (the driver is never given proceed authority until the blocks are reserved). Therefore the approach-locking timer is **not safety-required** in this simulator. (gemma4 Phase 4 Q2 ruling: SIMPLIFICATION-ACCEPTABLE.)

A grep of `core/src/commonMain` and `dispatcher-agent/src/main` confirms that "storno" and "sighting" appear nowhere in the production source — the concepts are absent by design, not by oversight.

---

## B2. Flank-Protection Switch-Lock Scope (F4)

`PathReservationService` locks **only on-route (running) switches**, NOT flank-protection (*odvratné*) switches/derailers:

- `extractUniqueSwitches` (`DefaultPathReservationService.kt:1953-1965`) derives switches solely from `pathInfo.reservedPath` — the running path.
- `configureAndRegisterSwitches` (`:2450-2469`) → `registry.registerSwitches` (`:2464`) locks each on-route switch. No flank switches are provisioned or locked.

Flank protection is a concept of the **legacy `InterlockingFacade` path only**:

- `InterlockingFacade.kt:24-30` — ESA-11 condition 2: "All flank-protection switches (odvratné výhybky) are in their required (safe) position."
- `TrainRoute.flank` (`lang/vocab/TrainRoute.kt:22` KDoc, `:33` field) — `val flank: List<SwitchSetting> = emptyList()`.
- `DefaultInterlockingFacade.kt:150` (condition-2 check), `:181` (lock count), `:326` (condition-2 method), `:340` (`for (switchSetting in route.flank)`), `:374` (`route.running + route.flank` locked).

**Known scope limitation:** the reservation-service path does not provision flank protection. It relies on the route's running switches being sufficient, or on the facade path for flank-sensitive layouts. A topology that requires flank-protection switches to be locked for safety, accessed only through the reservation-service path, would not have them locked.

---

## B3. Overlap Locking Beyond the Route (F4)

Real interlocking locks an **overlap** — a safety margin beyond the route's exit, in case the train overruns the destination signal. The simulator does not model overlap locking: a reservation covers exactly the blocks from START to destination separator, nothing beyond.

This is a **deliberate simplification**, consistent with the entry-gated-on-reservation safety argument (§B1): the block ahead is reserved for this train alone, so an overrun stays within reserved territory. Overlap would only matter for the exit end, which is an InOut / destination — not a conflicting route. There is no opposing movement at the exit to protect against.

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
| *Volnost jídní cesty* (Route freedom) | `areAllFree()` / `isPathAvailable()` — the FREE/availability check |
| *Závěr* (Route lock) | The atomic `reservePath` reservation + on-route switch lock (`registry.registerSwitches`) |
| Mutual exclusion (conflicting routes) | `RegistrationResult.Conflict` / `AllPathsBlocked` |
| Section locking (track-circuit hold) | The `OCCUPIED` state in the `FREE → RESERVED → OCCUPIED → FREE` state machine — a block stays `OCCUPIED` while the train is physically present, independent of whether the route is still "locked" |

The conflation is a **known simplification**: the simulator does not distinguish the three mechanisms because the single-threaded, reservation-gated model makes the distinctions unnecessary for the safety properties it enforces. A real installation's three-tier release discipline (approach-timer expiry → route release → section release) is collapsed into one.

---

## B5. Revisiting / Circular-Route Partial-Release Caveat (F5)

`resetSemaphoresForReleasedBlocks` is **fail-safe but not proven-safe** for a revisiting / circular route. Per the interface KDoc (`PathReservationService.kt:680-693`):

> This is proven safe for **suffix / rearmost releases on a non-revisiting route**: the un-travelled tail of a route the train will never traverse again. It is NOT proven safe for an arbitrary mid-route subset of `blocks` — the semaphore governing a released block can also be the one a DIFFERENT, still-reserved downstream block on the same route depends on. A route that loops back and becomes adjacent to a released block again has the same exposure: the semaphore this call resets may be the one that governs re-entry into the loop.

**Failure direction is fail-safe** — `Signal.STOP` authorises nothing, so the worst case is a stalled train, never an unauthorized opposing movement. The caller is responsible for staying within the proven-safe scope (`DefaultPathReservationService.kt:2649-2651`): the impl performs no route-position validation of its own.

Cross-reference: the same caveat appears in [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) v1.1 §5 (Proven-Safe Scope).

---

**End of Document**