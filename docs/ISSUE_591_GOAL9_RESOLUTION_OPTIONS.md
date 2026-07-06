# Issue #591 — How Goal 9 (Automatic Conflict Detection and Resolution) Can Help Solve the Multi-Train Scale Problem

**Date:** 2026-07-06
**Author:** traffic-simulation-expert (TEAM.md role) — lead analysis; kotlin-tech-lead consulted on API sections
**Issue:** [#591 — Goal 1 SP7: Scale validation for 5-train correctness and 20-train performance](https://github.com/bedaHovorka/interlockSim/issues/591)
**Parent analysis:** [`ISSUE_591_SCALE_LIVELOCK_ANALYSIS.md`](ISSUE_591_SCALE_LIVELOCK_ANALYSIS.md) (root cause and missing model behaviour)
**Scope:** Research only — no implementation. Catalogues **options**, their mechanisms, trade-offs, gaps, and recommended combinations.

---

## 1. Executive Summary

Goal 9 (✅ COMPLETE, SP1–SP7) shipped a full **detect → generate → rank → select →
learn** conflict-resolution pipeline that the `MultiTrainLoop` dispatcher does not
use at all today. The parent analysis showed the 20-train hang is a CPU livelock
caused by a **blind, polling, path-independent retry loop**; almost every element of
that loop has a Goal 9 counterpart designed to replace it:

| Livelock ingredient (parent doc §3/§5) | Goal 9 feature that addresses it |
|---|---|
| Dispatcher never learns *why* reservation failed | `ConflictDetectedEvent` carries the contested block **and its owner** (SP1) |
| Polling retry every sim-second at full search cost | Event-driven wake-up: conflict + `BlockEvent.BlockReleased` subscriptions (SP1 + #569) |
| No backoff / no alternative strategy on `AllPathsBlocked` | `ConflictResolution.HoldTrain` / `Reroute` / `SpeedAdjust` candidates (SP3) |
| No prioritisation between competing blocked trains | `ConflictResolutionRanker` impact scoring (SP4) |
| No headless decision-maker | `AutoConflictResolutionService.applyTopRanked` (SP5) — currently inert by design |
| Conflicts only become visible as a CI timeout | Contention tracking (`blockedSince`, `flushUnresolvedConflicts`) is watchdog-ready telemetry |
| No prediction — conflicts detected only on collision course | `TemporalConflictDetector` lookahead (SP2) — currently inert (no projection provider) |

**Headline findings:**

1. **Goal 9 can eliminate the livelock's *retry* dimension entirely** (Options A, B, F):
   an event-driven dispatcher that reacts to `ConflictDetectedEvent`/`BlockReleased`
   instead of polling removes the per-second re-search that multiplies the hang.
2. **Goal 9 can raise effective concurrency** (Options C, D, E, G): reroute and
   speed-adjust resolutions spread trains across the Praha ladder instead of letting
   them all pile up on the cheapest route, and temporal lookahead prevents conflicting
   admissions in the first place.
3. **One serious caveat:** naïvely invoking `DefaultConflictResolver.generateResolutions`
   from the dispatcher would make things *worse*, not better — reroute generation
   enumerates **all ~110 InOut pairs** through `RouteFinder.findRoutes`, i.e. the same
   exhaustive search the parent doc measured at ~2.7 s *per pair* on Praha (§4.2).
   Every option below that touches `Reroute` is gated on bounding that search first.
4. **Goal 9 does not remove the need for the P0 hot-loop fix** (parent doc §3) or the
   route-search cost work (§4): those are prerequisites, not alternatives. Goal 9
   features slot in *after* the clock can advance again (§6).
5. **Determinism warning for #591's "10 consecutive runs" criterion:** the SC4
   preference-learning loop (`StrategyPreferenceStore`) makes ranking dependent on
   the *history of previous choices*, so dispatcher decisions can differ between runs
   and even within one run as preferences accumulate. This is the "non-deterministic
   reasoning" concern the review raised in the Goal 10 context; §7 describes how to
   keep the scale test reproducible.

---

## 2. Problem Recap (one paragraph)

`MultiTrainLoop.reserveEntryPath` (MultiTrainLoop.kt:289–350) loops over up to 760
topological candidates and calls a **path-independent** `reservePath(trainName, inIo,
outIo)` per candidate; after the first `AllPathsBlocked` all remaining calls are
guaranteed identical ~2.7 s failures, freezing the kDisco clock for ~34 minutes per
dispatcher iteration. Trains 1–4 park mid-journey holding their full entry-to-exit
reservations, so the blockage can never clear. Full detail, evidence, and cost model:
[`ISSUE_591_SCALE_LIVELOCK_ANALYSIS.md`](ISSUE_591_SCALE_LIVELOCK_ANALYSIS.md) §2–§4.

---

## 3. Inventory — What Goal 9 Actually Shipped

All items below exist on `develop` today (Goal 9 marked ✅ COMPLETE 2026-07-06 in
`LONG_TERM_GOALS.md`). File references are to `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/`.

| # | Feature | Where | Production status |
|---|---|---|---|
| SP1 | `ConflictDetectedEvent(block, trainId, conflictingTrainId, time)` | `sim/conflict/ConflictDetectedEvent.kt` | **Live.** Emitted by `DefaultPathReservationService` at reservation time — on registry `Conflict` (line ~455) and on the **first** observation of a blocked-path contention (`recordContentionAndEmitIfNew`, deduplicated per `(trainId, block)`) |
| SP1 | `SimulationEnvironment.onConflictDetectedEvent(listener)` | `context/SimulationEnvironment.kt:508`, impl `DefaultSimulationContext.kt:1211` | **Live**, no production subscriber |
| SP2 | `TemporalConflictDetector` + `ProjectedOccupancy` + `TemporalConflictEvent` | `sim/conflict/TemporalConflictDetector.kt` | **Wired but inert** — its own KDoc: "no production code path calls `registerProjectionProvider` yet … it never emits a `TemporalConflictEvent` in a real simulation run" |
| SP3 | `ConflictResolution` sealed hierarchy: `HoldTrain`, `Reroute`, `SpeedAdjust`, each with `EstimatedImpact` | `sim/conflict/ConflictResolution.kt` | Model only — advisory, nothing enacts them |
| SP3 | `DefaultConflictResolver.generateResolutions` (hold always; reroutes via `RouteFinder` over all InOut pairs, deduped, cost-sorted, capped; speed-adjust always) | `sim/conflict/DefaultConflictResolver.kt` | Live for the UI path; **position-agnostic** (its KDoc: actionability deferred to SP4/#568) |
| SP4 | `ConflictResolutionRanker` — deterministic impact scoring, plus preference-weighted overload | `sim/conflict/ConflictResolutionRanker.kt` | Live |
| SP5 | `AutoConflictResolutionService.applyTopRanked(event)` — headless top-choice picker | `sim/conflict/AutoConflictResolutionService.kt`, `DefaultAutoConflictResolutionService.kt` | **Intentionally inert** — documented as "the headless wiring point for Goal 10's deliberative dispatcher" |
| SP6/SC4 | `StrategyPreferenceStore` + `DispatcherPreferenceStore` — per-conflict-type strategy learning | `sim/conflict/StrategyPreferenceStore.kt`, `DispatcherPreferenceStore.kt` | Live (operator Apply path) |
| SP7 | `ConflictResolutionPanel` operator UI | `desktop-ui/.../gui/conflict/` | Live (GUI only; irrelevant to headless scale test) |
| — | Contention telemetry: `blockedSince` map, `clearBlockedTracking`, `flushUnresolvedConflicts` | `context/navigation/DefaultPathReservationService.kt` | Live |

Two structural observations drive everything below:

- **The dispatcher and the resolution pipeline are currently disjoint.**
  `MultiTrainLoop` neither subscribes to `ConflictDetectedEvent` nor consults any
  resolver; it re-polls blindly. The entire Goal 9 pipeline hangs off the *same*
  reservation failures the dispatcher already experiences — the events are being
  emitted into the void during the hang.
- **`ConflictDetectedEvent` fires exactly at the livelock's trigger point.** When
  `reservePath` returns `AllPathsBlocked`, `recordContentionAndEmitIfNew` emits one
  deduplicated event naming the first blocked block and its owning train. The
  dispatcher's missing knowledge ("*why* did this fail, and *what* am I waiting for?")
  is literally already computed and published.

---

## 4. Options Catalogue

Each option lists: **mechanism**, **which Goal 9 feature it uses**, **what it buys for
#591**, **gaps/risks**, and a rough **cost/benefit**. Options are composable; §6 gives
recommended combinations. Options A–B are dispatcher-control-flow changes; C–E are
capacity/throughput changes; F–H are architecture/observability changes.

### Option A — Event-driven dispatcher retry keyed on `ConflictDetectedEvent` + `BlockReleased`

**Mechanism.** Replace the poll-every-sim-second retry in
`MultiTrainLoop.startApprovedTrains` with a subscription model:

1. On `AllPathsBlocked`, park the train in a `waitingTrains` set keyed by the
   contested block from the `ConflictDetectedEvent` the reservation attempt just
   emitted (the dispatcher can capture it via `env.onConflictDetectedEvent` or by
   returning the block in the reservation result — `ReservationResult.Conflict`
   already carries `conflictingBlock`).
2. Subscribe once to `BlockEvent.BlockReleased` (#569 API,
   `context/navigation/BlockEvent.kt:34`). When a release event names a block some
   waiting train is contending for, wake **only that train's** reservation retry.
3. Retry is therefore triggered by *state change*, not by the clock.

**Goal 9 features used:** SP1 event + emission dedup; #569 block events (a Goal 1
deliverable that SP1/SP2 build on).

**What it buys.** Eliminates the *frequency* dimension of the livelock: instead of
`O(blockedTrains × 2.7 s)` of frozen-clock search **per sim-second**, search runs only
when a release makes success plausible. On Praha with 20 trains this reduces
dispatcher search invocations by roughly the ratio of sim-seconds to block-release
events (~10–50×, scenario-dependent). This is the direct realisation of parent doc
§5.3 using shipped Goal 9/SP1 machinery, and it is the same event-driven pattern
`Train.createPathAvailableCondition` (#582) already uses successfully at the train
level.

**Gaps / risks.**
- A release event does not guarantee a *full path* is now free — full-path
  reservation (parent §5.1) means most wake-ups still fail. Works, but shines only
  combined with Option C/incremental reservation.
- The per-`(trainId, block)` dedup in `recordContentionAndEmitIfNew` means the
  dispatcher sees a conflict event only **once** per contention — correct for
  subscription-based waiting (subscribe once, wake on release), but a naive
  "retry on every conflict event" design would starve.
- kDisco listener callbacks run inside the emitting event; the dispatcher must
  convert wake-ups into scheduled continuations (e.g., `waitUntil` on a condition
  variable flipped by the listener) rather than re-entering reservation from the
  listener. The `InOutWorker` `RETRY_HOLD_SECONDS` guard documents the known kDisco
  pitfall: `waitUntil` returns immediately when the condition is already true, so a
  `hold()` must accompany the retry loop.

**Cost/benefit:** Medium effort (dispatcher rework, no new services), high benefit.
**Prerequisite:** P0 hot-loop fix (parent §7 item 1) — without clock progress there
are no release events to react to.

---

### Option B — `HoldTrain` resolutions as a principled backoff policy

**Mechanism.** When reservation fails, ask the resolver for candidates and honour a
`HoldTrain` resolution literally: `hold(resolution.holdDurationSeconds)` before the
next attempt, instead of the fixed 1-sim-second cadence. Escalate the hold duration
on consecutive failures (exponential backoff capped at, say, 60 sim-seconds).

**Goal 9 features used:** SP3 `ConflictResolution.HoldTrain` (carries
`holdDurationSeconds` and an `EstimatedImpact` with `delaySeconds` for metrics);
SP5 `applyTopRanked` as the selection mechanism; `DispatcherPreferenceStore` records
each auto-choice for post-run analysis.

**What it buys.** Cheapest possible integration: `HoldTrain` is *always* generated
(no `RouteFinder` call needed if reroute generation is skipped or capped — see the
caveat in §5), so the dispatcher can adopt the Goal 9 decision loop without paying
the reroute enumeration cost. Reduces wasted search from once per sim-second to once
per backoff interval; converts "will retry" log spam into recorded, impact-annotated
dispatcher decisions (`DispatcherPreferenceStore.getChoices()` becomes the audit
trail #591's performance summary needs).

**Gaps / risks.**
- Pure backoff is still polling — it reduces but does not eliminate redundant
  search. Strictly dominated by Option A when both are available; valuable as the
  low-risk first step or as A's fallback (a maximum-wait timer so a missed event
  cannot strand a train forever).
- Fixed default `holdDurationSeconds` is a static heuristic; a hold shorter than the
  owner's remaining transit re-fails deterministically. A smarter duration needs
  Option E's projections.

**Cost/benefit:** Low effort, moderate benefit. Good "first Goal 9 wire-in".

---

### Option C — `Reroute` resolutions to break head-of-line blocking and spread the ladder

**Mechanism.** When a train's preferred route is blocked, apply a
`ConflictResolution.Reroute`: reserve `alternativeRoute` (a concrete `Route` whose
segments verifiably exclude the contested block) instead of re-contending for the
same corridor. Two integration variants:

- **C1 — reroute at admission:** before a train enters, if its cheapest route is
  contended, admit it on the best conflict-free alternative. Praha's ladder (117
  blocks, 50 switches, parallel platform tracks) exists precisely to offer such
  alternatives; today all trains fight for the cost-optimal route first.
- **C2 — reroute a *waiting* train:** when the wait (Option A/B) exceeds a
  threshold, switch the parked train to an alternative route. This is where the SP3
  KDoc's **position-agnostic limitation** bites: candidates are enumerated over all
  InOut pairs with no knowledge of where the train currently is; for a train already
  inside the network most candidates are unreachable. C2 therefore needs the
  position-aware filtering deferred to #568 (Goal 9 SP4 follow-up).

**Goal 9 features used:** SP3 `Reroute` + `DefaultConflictResolver.generateRerouteCandidates`
(dedup by segment set, cost-sorted, capped at `maxRerouteCandidates`); SP4 ranker to
choose among alternatives.

**What it buys.** This is the only Goal 9 option that directly attacks the
**capacity** limit (parent §5.1's finding that effective concurrency ≈ the number of
block-disjoint routes): distributing 20 trains across near-disjoint ladder routes is
what makes `trainsExited == 20` arithmetically reachable at all under full-path
reservation. Also removes the head-of-line pattern where one unroutable train's
retries starve the dispatch queue.

**Gaps / risks.**
- **The cost caveat (critical).** `generateRerouteCandidates` calls
  `routeFinder.findRoutes` for **every ordered InOut pair** — 11 InOuts ⇒ 110 pairs
  ⇒ at the measured ~2.3–5.9 s per exhaustive Praha enumeration, **one conflict's
  candidate generation could cost minutes** inside a frozen kDisco event. Invoking
  the resolver from the dispatcher hot path is **prohibited until** the route-search
  work lands (parent §4: static per-pair route cache, k-shortest/lazy generation,
  availability-aware pruning). With an 110-entry route cache the same call becomes
  microseconds. For the dispatcher use-case the pair loop should also be constrained
  to the blocked train's actual `(inIo, outIo)` pair — a 110× reduction available by
  API parameterisation alone.
- Reroute equality relies on dynamic-block identity (`route.segments.none { it ==
  conflict.block }`); the resolver **must** be built via
  `DefaultConflictResolver.forEnvironment` on the same simulation context, or the
  contested-block filter silently stops filtering (documented context-coupling trap
  in the resolver KDoc).
- A reroute changes the train's exit or platform assignment; the #591 test's
  `TrainSpec(inName, outName)` fixtures must either accept alternate same-exit routes
  only, or the completeness assertion must accept rerouted exits.

**Cost/benefit:** Medium-high effort (route caching prerequisite + dispatcher
integration), **highest throughput benefit** of all options under full-path
reservation.

---

### Option D — `SpeedAdjust` resolutions for temporal de-confliction

**Mechanism.** Apply `ConflictResolution.SpeedAdjust(speedReductionFactor)` to a
train whose *projected* arrival at a contested block conflicts with its current
owner: slow the follower so the block frees before it arrives, rather than stopping
it at a signal (a stop-and-restart costs far more sim-time than a 30 % slowdown, and
a stopped train holds its blocks longer — Motor physics make braking/re-acceleration
the dominant delay term).

**Goal 9 features used:** SP3 `SpeedAdjust`; meaningful trigger requires SP2
projections (Option E), because a *spatial* conflict event arrives when the train is
already stopped at the contested block — too late for speed adjustment to help.

**What it buys.** Fewer hard stops ⇒ shorter block-holding times ⇒ fewer downstream
`AllPathsBlocked` results ⇒ fewer dispatcher wake-ups. On a 20-train Praha run this
compounds: each avoided stop shortens the critical path of every train queued behind.
It is also the only strategy that *reduces the number of conflicts generated* rather
than resolving them after the fact.

**Gaps / risks.**
- Inert without Option E's lookahead — explicitly a second-wave option.
- The SP3 impact estimate for `SpeedAdjust` is a placeholder (`delaySeconds = 0.0`,
  documented as heuristic); ranking it fairly against Hold/Reroute needs a real
  delay model (train's remaining distance ÷ reduced speed), which the projection
  provider (Option E) would supply anyway.
- Applying a factor to a `Continuous`-integrated Motor mid-journey touches the
  conservative `sim/` core; needs its own test coverage per the sim/-package rules.

**Cost/benefit:** Medium effort after E; steady-state benefit, not a livelock fix.

---

### Option E — Wire `TemporalConflictDetector` as predictive admission control

**Mechanism.** Implement the projection provider the SP2 detector is waiting for
(its KDoc names `MultiTrainLoop` as the intended production wirer):

1. `MultiTrainLoop` knows each dispatched train's reserved block sequence and the
   Motor's velocity; converting these to `ProjectedOccupancy(enterOffset, exitOffset)`
   lists is straightforward kinematics.
2. Register via `detector.registerProjectionProvider { trainId -> … }`.
3. Use emitted `TemporalConflictEvent`s in two places:
   - **Admission gate:** don't approve a new train whose projected occupancy
     overlaps an active train's projection inside the lookahead window — conflicts
     are prevented *before* a single search is wasted on them.
   - **Trigger for Option D** speed adjustments while both trains are still moving.

**Goal 9 features used:** SP2 detector, `ProjectedOccupancy`, dedup window; the
detector is already subscribed to `BlockReserved`/`BlockReleased` — only the provider
is missing.

**What it buys.** Shifts conflict handling from *reactive* (train stopped at red) to
*predictive* (conflict known `lookaheadWindowSeconds` in advance). For the 20-train
generator this is effectively **admission control**: instead of injecting train #6
into a saturated throat and letting it thrash the reservation service, hold it at the
entry InOut until its projection is clean. Directly reduces the number of
`AllPathsBlocked` episodes — the livelock trigger — rather than handling them better.

**Gaps / risks.**
- The pairwise scan is documented O(trains² × occupancies²) per block event; at 20
  trains × ~30-block Praha routes that is ~360 k comparisons per event — likely fine
  (comparisons are cheap), but the detector's own KDoc flags it for profiling at
  this exact scale.
- Projections under full-path reservation are trivially pessimistic (a train "occupies"
  its whole route). Projections become accurate — and this option becomes powerful —
  together with incremental reservation (parent §5.1).
- Constant-velocity projection degrades near stops; acceptable within a 30–60 s
  window, but the window becomes a tuning parameter of the scale test.

**Cost/benefit:** Medium effort (provider + admission gate), high strategic benefit;
also the enabler for D and for smarter B hold durations.

---

### Option F — Promote `AutoConflictResolutionService` to the dispatcher's decision core

**Mechanism.** Make the Goal 9 SP5 headless service the *single* decision point the
options above plug into, exactly per its documented "SP2b pattern":

```kotlin
env.onConflictDetectedEvent { event ->
    val resolution = autoResolver.applyTopRanked(event) ?: return@onConflictDetectedEvent
    when (resolution) {
        is HoldTrain   -> dispatcher.scheduleHold(resolution)      // Option B
        is Reroute     -> dispatcher.scheduleReroute(resolution)   // Option C
        is SpeedAdjust -> dispatcher.scheduleSpeedAdjust(resolution) // Option D
    }
}
```

**Goal 9 features used:** SP5 service (today "intentionally inert — the headless
wiring point for Goal 10's deliberative dispatcher"), SP4 ranker, SP6 preference
recording (every auto-decision lands in `DispatcherPreferenceStore` with source
`AUTO`).

**What it buys.** Architecture rather than raw performance: one strategy-selection
surface instead of ad-hoc dispatcher branches, a persisted decision log for #591's
"performance bottleneck summary" acceptance item, and — decisively — **the exact seam
Goal 10 needs**. Wiring the scale-test dispatcher through SP5 means Goal 10's
deliberative dispatcher later replaces the *picker*, not the plumbing. This answers
the review note that the problem "needs at least Goal 3, 9": Goal 3's
`CollisionWarning` feeds Goal 9 detection; Goal 9's SP5 output feeds Goal 10.

**Gaps / risks.**
- `applyTopRanked` calls `generateResolutions`, so it inherits the Option C cost
  caveat in full — the resolver behind it must be cheap (route cache / pair-scoped
  search) before SP5 may run inside dispatcher events.
- The listener-context constraint from Option A applies: `applyTopRanked` may be
  *selected* inside the event callback, but *enactment* (hold/reroute) must be
  scheduled as kDisco process work.
- "Top-ranked" with the current placeholder impact estimates will often pick
  `Reroute` (`delaySeconds = 0.0`) over `HoldTrain` even when the reroute is not
  actionable — the SP4 ranking is only as good as SP3's impact model (see D's gap).

**Cost/benefit:** Low incremental effort once B/C/D exist; the main payoff is
Goal 10 alignment and observability.

---

### Option G — Preference learning (`StrategyPreferenceStore`) to specialise strategy per bottleneck

**Mechanism.** Keep SC4's learning loop on in the scale scenario: the conflict-type
key is the contested block's name, so over a 1200 s run the store learns per-block
policies — e.g. throat blocks (short transit, frequent conflicts) converge on
`HOLD_TRAIN`, while ladder-entry blocks (long occupation) converge on `REROUTE`.
`DefaultConflictResolver` already switches to the preference-weighted ranker overload
when a store is supplied.

**What it buys.** Self-tuning without hand-written per-block policy tables; the
recorded choices (`selectionCount`, `preferenceAdjustment`) double as the contention
heat-map for #591's bottleneck documentation.

**Gaps / risks — determinism (important).** Preference-adjusted ranking makes run *N*'s
decisions depend on choices made earlier in the run, and — if the store outlives a
context — on previous runs. #591's acceptance criterion is *10 consecutive
deterministic-outcome runs*; the review thread flagged exactly this
("Undeterministic reasoning in Goal 10"). Mitigations, in preference order:

1. **Scale test runs with `preferenceStore = null`** (base deterministic ranking) —
   zero-cost, fully reproducible; learning is validated separately.
2. Fresh store per run (`reset()` between the 10 stress iterations) — deterministic
   *across* runs, adaptive *within* one; acceptable if within-run adaptivity is
   itself deterministic given a fixed event order (it is: kDisco is single-threaded
   and the ranker's tie-breaks are documented deterministic).
3. Persistent store across runs — research mode only; never for the acceptance gate.

**Cost/benefit:** Zero implementation (exists); the work is *policy*: pick mode 1 or 2
for the test and document it.

---

### Option H — Goal 9 contention telemetry as the livelock/deadlock watchdog substrate

**Mechanism.** The parent doc (§5.4) calls for a watchdog; Goal 9 already maintains
the needed state inside `DefaultPathReservationService`:

- `blockedSince` — earliest sim-time each `(train, block)` contention began
  (`recordBlockedContention` keeps the earliest; `clearBlockedTracking` forgets
  resolved ones);
- `flushUnresolvedConflicts` — end-of-run reporting of contentions that never
  resolved;
- `ConflictDetectedEvent` — the wait-for **edge** `(trainId → block →
  conflictingTrainId)` needed to build a wait-for graph over
  `PathReservationRegistry`.

A watchdog process subscribing to conflict events can therefore: (a) raise a
structured *livelock* diagnosis when `time() - min(blockedSince) > threshold` while
trains are pending, and (b) detect true *deadlock* cycles in the conflict-edge graph
(A waits on block owned by B, B waits on block owned by A) — making #591's "no
deadlocks or livelocks in 10 runs" criterion **directly assertable** (the test fails
with train names, blocks, and owners) instead of being inferred from a 10-minute CI
timeout.

**Gaps / risks.** `blockedSince` and `flushUnresolvedConflicts` are currently
`private`/end-of-run; exposing a read-only snapshot (or having the watchdog maintain
its own map from the public events, which requires no production change at all) is a
design choice for the follow-up. Cycle detection on ≤ 20 nodes is trivial.

**Cost/benefit:** Low effort, high diagnostic value; recommended in the **same PR**
as the P0 fix since it is test-infrastructure-adjacent.

---

## 5. What Goal 9 Cannot Solve (boundaries)

To keep expectations honest, four things remain outside Goal 9's reach:

1. **The P0 defect itself.** The path-independent 760-iteration loop (parent §3) is
   a dispatcher bug; no amount of conflict resolution helps while a single event
   freezes the clock for 34 minutes. P0 lands first, unconditionally.
2. **Route-search cost.** Goal 9 *consumes* the Goal 2 pathfinding API; it inherits,
   and via reroute generation **amplifies** (110-pair scan), the exhaustive-search
   cost of parent §4. Route caching / k-shortest / pair-scoped search is a
   prerequisite for Options C and F, not a Goal 9 deliverable.
3. **Full-path reservation capacity ceiling.** Hold/reroute/speed-adjust redistribute
   trains among block-disjoint routes but cannot create more of them; if Praha admits
   ~4–6 disjoint entry-to-exit routes, 20 *simultaneous* trains still require
   incremental (signal-to-signal) reservation (parent §5.1/§5.2). Goal 9's role there
   is complementary: incremental reservation *creates* the opposing-route deadlock
   hazard, and Goal 9's detection + Option H's wait-for graph are the natural
   avoidance/detection layer for it.
4. **Enactment plumbing.** SP3 resolutions are advisory data classes; `holdTrain()`,
   `rerouteTrain()`, `adjustSpeed()` effects on a live `Train`/`Motor` are new
   dispatcher capabilities (the SP5 KDoc assigns them to the caller). This is
   dispatcher work in the conservative `sim/` package — tests first.

---

## 6. Comparative Matrix and Recommended Combinations

| Option | Attacks | Effort | Prereqs | Livelock relief | Throughput gain | #591 criterion served |
|---|---|---|---|---|---|---|
| A — event-driven retry | retry frequency | M | P0 fix | ★★★ | ★ | no-livelock, RT-ratio |
| B — HoldTrain backoff | retry frequency | **L** | P0 fix | ★★ | ★ | no-livelock |
| C — Reroute | capacity / HOL blocking | M–H | route cache (+#568 for C2) | ★ | ★★★ | 20-train completion |
| D — SpeedAdjust | conflict generation | M | E | ★ | ★★ | RT-ratio |
| E — temporal lookahead | conflict generation / admission | M | none (provider only) | ★★ | ★★ | no-livelock, completion |
| F — SP5 auto-resolution core | architecture / Goal 10 seam | L (after B–D) | cheap resolver | — | — | bottleneck summary |
| G — preference learning | strategy tuning | policy-only | none | — | ★ | ⚠ determinism — see §4.G |
| H — watchdog on telemetry | observability | **L** | none | — (diagnosis) | — | no-deadlock **assertable** |

**Recommended sequencing** (extends parent doc §7 with the Goal 9 mapping):

1. **Wave 0 (unblock):** P0 hot-loop fix + **Option H** watchdog + per-scenario test
   timeout. The suite then *fails fast with a diagnosis* instead of hanging.
2. **Wave 1 (livelock relief):** **Option B** (trivial) then **Option A**
   (event-driven). Route cache work from parent §4 proceeds in parallel.
3. **Wave 2 (throughput):** **Option C1** (admission-time reroute over cached routes)
   + **Option E** (projection provider + admission gate). Expected to make the
   5-train case robust and the 20-train case a genuine measurement.
4. **Wave 3 (scale target):** incremental reservation (parent §5.1) with Goal 9
   detection + Option H's wait-for graph as its deadlock-avoidance layer; **Option D**
   and **Option F** land here, giving Goal 10 its finished SP2b seam.
5. **Throughout:** **Option G** fixed to deterministic mode (null store or per-run
   reset) for the acceptance runs.

---

## 7. Cross-Goal Notes (Goal 3 / Goal 10, per review thread)

- **Goal 3 (collision detection)** is the event substrate Goal 9 detection builds on:
  `ConflictDetectedEvent`'s KDoc explicitly differentiates it from
  `CollisionWarning.ReservationConflict` (immediate, non-pausing, resolution-layer
  signal vs. end-of-run safety warning). Options A/E/H consume the Goal 9 side; the
  Goal 3 side stays the safety net that must **keep firing unchanged** while the
  dispatcher changes — a regression guard the scale test should assert.
- **Goal 10 (AI dispatcher)** is where "many options" become one deliberative policy.
  Everything in §4 is deliberately shaped so that Goal 10 replaces only the
  *selection function* (today: SP5's "first ranked candidate"; tomorrow: rule-based,
  later ML). The determinism concern raised in review ("undeterministic reasoning in
  Goal 10") is handled by keeping the acceptance-test configuration on the
  deterministic base ranker (§4.G) and confining learned/AI policies behind the SP5
  seam where they can be swapped per scenario.

---

## 8. Summary Answer to the Review Question

*How can features from Goal 9 help solve this problem?* — In five distinct ways:

1. **Replace blind polling with informed, event-driven dispatch** (SP1 events +
   #569 releases): removes the retry-frequency dimension of the livelock (Options A/B).
2. **Give the dispatcher alternatives instead of retries** (SP3 Hold/Reroute/
   SpeedAdjust + SP4 ranking): converts contention into redistribution, raising
   effective concurrency toward the 20-train bar (Options C/D).
3. **Prevent conflicts instead of resolving them** (SP2 temporal lookahead as
   admission control) — the only shipped mechanism that reduces how often
   `AllPathsBlocked` occurs at all (Option E).
4. **Make the "no deadlock/livelock" criterion assertable** (SP1 dedup + contention
   telemetry as a watchdog/wait-for-graph substrate) instead of a CI-timeout
   inference (Option H).
5. **Provide the Goal 10-ready decision seam** (SP5 + SP6) so the scale-test
   dispatcher and the future AI dispatcher share one architecture, with determinism
   preserved for acceptance runs (Options F/G).

None of these substitutes for the P0 hot-loop fix, the route-search cost work, or —
for full 20-train completion — incremental reservation; Goal 9 is the *control and
observability layer* that makes those fixes effective and verifiable.
