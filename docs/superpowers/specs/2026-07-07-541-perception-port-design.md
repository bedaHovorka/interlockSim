# Design: Network Perception Port (#541)

**Issue:** [bedaHovorka/interlockSim#541](https://github.com/bedaHovorka/interlockSim/issues/541)  
**Parent:** [bedaHovorka/interlockSim#532](https://github.com/bedaHovorka/interlockSim/issues/532) — Goal 10 DISPATCHER agent  
**Canonical authority:** [#532](https://github.com/bedaHovorka/interlockSim/issues/532) issue body. Where this
document conflicts with it, the #532 body wins.  
**Status:** Final design record (2026-07-07), retro-documenting the perception port shipped in PR #722.

---

## 1. Summary

This slice introduces a **read-only sensor layer** between the simulation and future
perception-driven dispatchers. `cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort` is a
KMP-clean interface in `:core` `commonMain` whose methods each return an immutable snapshot
(`*Reading` value type) of one facet of the network: signal aspects, block occupancies, train
positions, and train timetables. The default implementation,
`DefaultNetworkPerceptionPort`, is backed by the live `SimulationEnvironment` plus a supplier of
currently active trains.

The port is **wired into `DispatcherTickContext`** but **not yet consumed** by
`RuleBasedDispatcher` — the current dispatcher is a plain FIFO/topology dispatcher that needs no
perception. The port exists so that the Goal 10 LLM dispatcher track (SP1.6, Koog tools) can observe
the network through a stable, decoupled, value-typed surface instead of reaching into `sim/`
internals. Each interface method maps one-to-one to a future Koog tool.

No actuator behavior, dispatch logic, or simulation state changes belong in this slice. The port
is purely observational.

---

## 2. Goals and Non-Goals

### Goals

1. Give future dispatchers a read-only, string-keyed view of the network.
2. Return **immutable snapshots only** — no live mutable references out to callers.
3. Stay KMP-clean: no `java.*`/`javax.*` in `commonMain` (enforced by
   `:core:checkCoreCommonMainPurity`).
4. Introduce the abstraction **without changing dispatch behavior** — `RuleBasedDispatcher` keeps
   its current semantics.
5. Keep the wiring conservative for `sim/`: no new Koin bindings, no refactoring of working sim
   logic.

### Non-Goals

1. Not a dispatch API — the port observes; it does not decide or act.
2. Not consumed by `RuleBasedDispatcher` in this slice.
3. No thread-safety contract beyond single-threaded kDisco access (documented on the class).
4. No block-occupancy or signal change events/streaming — synchronous pull queries only.

---

## 3. Package and Source Location

All types live in `:core` `commonMain`, package `cz.vutbr.fit.interlockSim.ports`:

- `NetworkPerceptionPort.kt` — the interface.
- `DefaultNetworkPerceptionPort.kt` — the `SimulationEnvironment`-backed implementation.
- `SemaphoreReading.kt`, `BlockOccupancyReading.kt`, `TrainPositionReading.kt`,
  `TimetableReading.kt` — immutable `data class` value types (all `val`, primitives/`String`/
  `enum`/`Signal` only).

`commonMain` placement keeps the port available to both the JVM dispatcher and any future native
target. The purity gate guarantees no JVM-only API leaks in.

---

## 4. Public API Surface

`NetworkPerceptionPort` exposes, per facet:

- **Signal aspects:** `signalAspect(name): SemaphoreReading?`, `allSignalAspects(): List<SemaphoreReading>`.
- **Block occupancies:** `blockOccupancy(blockId): BlockOccupancyReading?`,
  `allBlockOccupancies(): List<BlockOccupancyReading>`.
- **Train positions:** `trainPosition(trainId): TrainPositionReading?`,
  `allTrainPositions(): List<TrainPositionReading>`.
- **Train timetables:** `trainTimetable(trainId): TimetableReading?`,
  `allTrainTimetables(): List<TimetableReading>`.

Each `*Reading` is a `data class` of immutable fields; `trainId`/`blockId` are `String?` or `String`.
The interface KDoc notes the intended one-method-per-Koog-tool mapping for SP1.6.

---

## 5. Key Decisions

**D1 — Backed by `SimulationEnvironment`, not a parallel state copy.**
The implementation reads the grid (`getRailWayNetGrid()`) and the graph (`getGraph()`) directly
from the simulation environment. There is no duplicate shadow state to keep in sync; the
environment is the single source of truth and the port is a read lens over it.

**D2 — Eager, lifetime-of-port caches; live state read at query time.**
Both semaphores (`semaphoreCache` / `semaphoreByName`) and blocks (`blockCache` / `blockById`)
are scanned once at construction and indexed by name/id. Cells and graph edges are stable for the
lifetime of a simulation context (no cells/edges are added or removed at runtime), so the caches
store **references** to the dynamic objects and are never invalidated. Live state
(`sem.signal`, `block.getState()` / `trainName` / `occupant`) is read on those cached references at
each query. This makes `signalAspect` / `blockOccupancy` O(1) lookups and `allSignalAspects` /
`allBlockOccupancies` O(n) with no per-call graph re-scan. (The block cache was added after the
initial PR in response to code review — see §7.)

**D3 — Direct construction in `ShuntingLoop.createTickContext()`, not Koin.**
`perceptionPort` is built by direct construction inside `ShuntingLoop.createTickContext()`, with
`activeTrains = { approvedTrains.toList() }` supplied as a lambda so it always reflects the
currently approved trains at query time. This deliberately avoids a Koin binding, consistent with
the conservative, minimal-change policy for the `sim/` package: the port is rebuilt per tick
context, owns no global state, and introduces no DI lifecycle complexity. The Koin restriction for
`sim/` was lifted 2026-03-20, but the conservative choice to *not* use it here is intentional and
recorded.

**D4 — `Train` exposes narrow read-only scalar properties, not the live `Timetable`.**
The port needs four timetable-derived values (origin name, destination name, scheduled departure
time, scheduled arrival time). Rather than expose `fun getTimetable(): Timetable` — which would
hand callers a live reference to the mutable `Timetable` (its `in`/`out`/`length` are `var`, and its
`DynamicInOut` endpoints expose mutable semaphore state) — `Train` exposes four `val` properties
(`timetableOriginName`, `timetableDestinationName`, `scheduledDepartureTime`,
`scheduledArrivalTime`) delegating to the private `timetable` field. This keeps the perception
data flow strictly one-directional: snapshots out, nothing mutable back in. (This was tightened
after the initial PR in response to code review — see §7.)

---

## 6. Traceability to Goal 10

- **Parent:** #532 (Goal 10 DISPATCHER agent).
- **Downstream consumers:** SP1.6 (Koog tool wiring — one port method → one tool, per the
  `NetworkPerceptionPort` KDoc), and the SP3.1 LLM dispatcher track documented in
  `docs/GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md`.
- **Sibling design:** `docs/superpowers/specs/2026-07-04-570-operating-vocabulary-dsl-design.md`
  (the typed vocabulary the dispatcher will emit/consume; the perception port is the *input* side).
- **Paramount example:** `vyhybna.xml` — the port must fully describe this network's signals,
  blocks, and the single train's timetable.

---

## 7. Implementation Checklist

This slice shipped in PR #722 ("Define sensor ports for perception interface", closes #541). This
document is the retro-record. The two post-review tightenings are:

- [x] D4: replaced `Train.getTimetable()` with four narrow `val` properties (encapsulation fix).
- [x] D2: added the `blockCache` / `blockById` index so `blockOccupancy(id)` is an O(1) map lookup
      instead of an O(n) graph re-scan with recomputed `blockId` per block.

Tests: `DefaultNetworkPerceptionPortTest` (20 unit tests, MockK) covers the port contract;
`RuleBasedDispatcherDeterminismTest` (10 real `vyhybna.xml` runs through kDisco) is the
end-to-end guard that the `ShuntingLoop` rewrite + port wiring did not alter simulation behavior.