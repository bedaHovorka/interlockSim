# SP12 — Routing decomposition: status report for Goal 9B

**Issue:** bedaHovorka/interlockSim#1159 (Goal 1B, SP12) · **Status on:** 2026-10-25 · **Reports on:** SP12.1 …
SP12.10, the SP12.5 follow-ups #1207–#1209, #1173 (SP12.7), #1174 (SP12.8) and #1200

**SP12 is still open.** This is an interim status report, not the close-out: #1159 has open sub-issues
(#1218, #1220, #1221, #1222, #1224, #1228, #1229, #1230, see [section 6](#6-open-follow-ups)), and the page is
updated as they land. The PR that adds it closes only #1205 (the doc-anchor tripwire).

SP12 cut `context/navigation/` into small units behind one facade-style entry, found the duplicate
routing checks, and kept extraction (pure moves) apart from behaviour fixes. This page collects the
per-leaf records in one place. The leaf pages stay as the detailed evidence (what moved, test pins,
review rounds, the #895 control-arm tables); this page is the one a Goal 9B author reads.

Contents: [1. Cross-reference](#1-cross-reference) · [2. Goal 9B seam list](#2-goal-9b-seam-list) ·
[3. Duplicate-check report](#3-duplicate-check-report) · [4. Size outcome](#4-size-outcome) ·
[5. Side finding: swallowed log-lambda exceptions](#5-side-finding-kotlin-logging-swallows-log-lambda-exceptions) ·
[6. Open follow-ups](#6-open-follow-ups) · [7. Evidence so far](#7-evidence-so-far)

## 1. Cross-reference

| Slice | Issue | New unit(s) / change | Leaf page | PR | State on 2026-10-25 |
| --- | --- | --- | --- | --- | --- |
| SP12.1 | #1167 | `ReservationContentionTracker` | [SP12.1](SP12.1-contention-tracking.md) | #1186 | merged |
| SP12.2 | #1168 | `RouteReleaser` | [SP12.2](SP12.2-release-rollback.md) | #1194 | merged |
| SP12.3 | #1169 | `ClearedSignalLedger`, `RouteSignalConfigurator` | [SP12.3](SP12.3-signal-configuration.md) | #1195 | merged |
| SP12.4 | #1170 | `SwitchConfigurator` | [SP12.4](SP12.4-switch-configuration.md) | #1196 | merged |
| SP12.5 | #1171 | `ReservationTargetDiscovery` | [SP12.5](SP12.5-target-discovery.md) | #1197 | merged |
| SP12.6 | #1172 | `BlockOwnershipTable`, `SwitchOwnershipTable`, `PathInfoStore`, `PathInfoMerger` out of `PathReservationRegistry` | [SP12.6](SP12.6-registry-split.md) | #1187 | merged |
| SP12.5 follow-ups | #1207, #1208, #1209 | oriented-only `reservePathToAnyTargets`; one next-target primitive (`evaluateTargets`); sealed `TargetAttempt` | [SP12.5](SP12.5-target-discovery.md), "Follow-ups #1207-#1209" | #1219 | merged |
| SP12.7 | #1173 `[sim]` | `SwitchConfigurator.registerSwitches` skips a switch with no adjacent held block; `DefaultInterlockingFacade` rolls back only the switches a failed route newly locked | [SP12.4](SP12.4-switch-configuration.md), "`registerSwitches` — the born-stale rule" (no page of its own) | #1223 | merged |
| (END twin of #1188) | #1200 `[sim]` | `RouteCandidateScreen.rejectNonOrientedEnd`; END precondition in the `PathReservationService` contract | this page (3.8) and the `GeometricallyImpossible` KDoc | #1225 | merged |
| SP12.8 | #1174 `[sim]` | `ReservationExpiry`, `PathReservationRegistry.expiredHoldings`, kernel-side expiry in `reservePath`, `reservePathToAny` and both loops | [SP12.8](SP12.8-reservation-expiry.md) | #1226 | merged |
| SP12.9 | #1210 | `RouteCandidateScreen`, `GeometricFailures`, `separatorLabel` | [SP12.9](SP12.9-candidate-screen.md) | #1212 | merged |
| SP12.10 | #1211 | `CandidateCommitter` (and the removal of `@Suppress("LargeClass")`, `@Suppress("LongMethod")`) | [SP12.10](SP12.10-candidate-commit.md) | #1216 | merged |
| Test base | #1204 | `PathReservationServiceTestBase` adopted across the navigation suite | n/a | #1213 | merged |
| Release funnel | #1202 | wholesale release funnels contain a throwing block-occupancy listener | [SP12.2](SP12.2-release-rollback.md), "Known gaps" | #1206 | merged |
| Status report and tripwire | #1205 (part of #1159) | this page; `DocLineAnchorTripwireTest`; doc anchors retargeted to symbols | this page | #1227 | open |

Every leaf PR above is merged into `goal-1B`; the last one, #1226, landed as `f72d09f2`.

## 2. Goal 9B seam list

The rule that holds across every leaf: a Goal 9B dispatcher **observes and requests, and never
mutates ownership state directly**. It acts through `PathReservationService`, `InterlockingFacade`
and the ports (`NetworkActuatorPort`, `NetworkPerceptionPort`); everything below is the detail of
that rule. `TargetSelectionSingleEntryTest` (SP5) allow-lists the classes that may store the
service, so a second door cannot appear silently.

### 2.1 May be called, consumed or read

| Seam | How | Leaf |
| --- | --- | --- |
| `ReservationTargetQuery.findReservationTargetCandidates(start, ownerTrainId)` | Call. *The* target-selection seam: every settable next separator one section ahead with an evaluated availability; choose through `ReservationTargetPolicy.pick`. | SP12.5 |
| `PathReservationService.isPathToAnyNextSemaphoreAvailable` | The shell's (`InOutWorker`) "may a train enter" probe. A dispatcher does not need it; the candidate list answers the same per target. | SP12.5 |
| `PathReservationService.reservePath` / `reservePathToAnyNextSemaphore` / `reservePathToAny` | Call. A dispatcher asks for a route, never for an aspect: signals are cleared as the last step of an atomic grant. | SP12.3, SP12.8 |
| `PathReservationService.releasePathDetailed` (via `NetworkActuatorPort.releaseRouteDetailed`) | Call. The release a dispatcher means by "cancel this route": signals to STOP first, approach lock honoured, the caller retries while `RouteRelease.deferredBlockIds` is non-empty (as `ToolDrivenDecisions` and `OrphanReservationSweeper` do). Do not drive a retry from `releaseRoute`: its Boolean is `true` for a deferred release too. | SP12.2 |
| `PathReservationService.releaseBlock` (after `resetSemaphoresForReleasedBlocks`), `dropFreedBlock` | Only through `RegistryPartialRouteReleaser`; refuse a block another train owns / a block not FREE. | SP12.2 |
| `PathReservationService.hasClearedSignals(trainId)` | Read. Exact, cheap "is any proceed aspect still lit for this train". | SP12.3 |
| `PathReservationService.recordExternalClearedSemaphore` | Call only when the dispatcher itself writes an aspect outside a reservation, as `DefaultInterlockingFacade` and `DefaultNetworkActuatorPort` do. | SP12.3 |
| `PathReservationService.resetSemaphoresForReleasedBlocks` | Call only inside its documented scope (suffix/rearmost releases on a non-revisiting route); it does no route-position validation. | SP12.3 |
| `ConflictDetectedEvent` (kDisco custom event) | Subscribe. Emitted once per contention by the `AllPathsBlocked` arm, once per detected race by the atomic-registry-race arm; de-duplicate by (train, block). | SP12.1 |
| `ReservationExpiry.expireStaleReservations()` via `RoutingServices.getReservationExpiry()` | Idempotent, rule-bound. Not needed by a dispatcher: the loops and every `reservePath` already call it. | SP12.8 |
| Read-only registry queries: `getOwner`, `getBlocks`, `isRegistered`, `isBlockAvailable`, `getOccupant*`, `getOccupiedBlocks`, `trainCount`, `blockCount`, `getRegisteredAtSimTime`, `getSwitches`, `getSwitchOwner`, `isFlankProtected`, `isOwnedByOtherTrain`, `isStaleSwitchOwnership`, `getPathInfo`, `isPathExtendedBeyond`, `nonContinuingCandidateReason`, `pathInfoEndAfter`, `isValidPathInfoEnd`, `canTrimPathInfoTo`; `addBlockOccupancyListener` / `removeBlockOccupancyListener`; `createBlockAvailableCondition` (for a kDisco process that waits, never for polling) | Read. Synchronous on the simulation thread; a listener must not call back into a mutating seam. | SP12.6 |

The result types a dispatcher sees are unchanged by SP12: `ReservationResult.Success`, `Conflict`,
`AllPathsBlocked`, `NoPathExists`, `NonContiguousStart`, `GeometricallyImpossible`,
`DivergesFromHeldRoute`. Every screening, commit and rollback step of the decomposition is reported
through them; none of the extracted classes is an entry point.

**Reconciled between leaves.** SP12.3 lists `releasePath` and `unregister` as dispatcher-callable
because they reset every aspect the train still owns; SP12.2, written later and looking at the
release side as a whole, marks both as simulation-private. SP12.2 wins: `releasePath` is
unconditional (no approach lock, so it can free a block a train is booking across a proceed aspect,
the Issue #1050 FATAL) and `unregister` is wholesale end-of-journey cleanup. A dispatcher releases
through `releasePathDetailed` only.

### 2.2 Must stay private to the simulation

| Seam | Owner | Why |
| --- | --- | --- |
| `PathReservationService.flushUnresolvedConflicts` | `DefaultSimulationContext.run` | End-of-run only; it clears the tracking, and a mid-run call reintroduces the Issue #612 false-positive auto-pause. Still public on the facade; convention-only until a read-only snapshot exists (demand D1 below). |
| `releasePath`, `unregister`, `unregisterBlock` | teardown and tests; `DefaultSimulationContext`; `Train.Tail` | Unconditional / wholesale / physical-progress driven (see the reconciliation above). |
| `rollbackBlock` (`BlockRollbackStep`), `rollbackReservation` | `RouteReleaser`, reservation code, `DefaultInterlockingFacade` | Undo a reservation that was never announced; they publish no release event. |
| `emitBlockReleased`, the approach-lock deferral state (`approachLockedBlocks`, `approachLockDeferredUntil`) | `RouteReleaser` | Internal to a normal release: `emitBlockReleased` publishes both `BlockEvent.BlockReleased` and the legacy block-occupancy event, and the deferral state encodes the Issue #1050 booking window. Called alone they announce a release that did not happen or reopen the #1050 FATAL. |
| `ReservationContentionTracker`: `recordContentionAndEmitIfNew`, `clearBlockedTracking`, `findFirstBlockedConflict`, `blockedSince` | the service | Mutate dedup state or must stay coupled to a real Success / whole-route release. `findFirstBlockedConflict` is a candidate for a read-only query through the service (SP5 demand D5). |
| `RouteSignalConfigurator.configure*`, the `ClearedSignalLedger` reset helpers, `facesDirectionOfTravel`, `startFacesTravelDirection` | `CandidateCommitter` / the service | Signal clearing is correct only after blocks and switches (the order *switches before signals* is a safety property of `reservePath`); each reset encodes one rollback situation and needs the caller's snapshot. Facing information comes from perception, not the kernel. |
| `SwitchConfigurator`: `configureSwitchesInPath`, `registerSwitches`, `releaseOrphanSwitchLocks`, `reclaimStaleForeignSwitchOwnership`, `releaseCandidateSwitches` | `CandidateCommitter` | Correct only inside Step 2f after the blocks are reserved and after Step 2e.5; calling them alone makes an orphan lock, or is a back door around interlocking (SI-5). |
| `ReservationTargetDiscovery`: `findNextSemaphoresVia`, `reservePathToAnyTargets`, `isPathAvailableFor`, `resolveForwardSection`, `allSemaphores`, `evaluateTargets` | the service | The raw enumeration is a second opinion about which route to set (what SP5 removed); `reservePathToAnyTargets` is the interlocking's own fallback order; availability reaches a dispatcher only as `CandidateTarget.available`. |
| `RouteCandidateScreen`: `rejectNonOrientedStart`, `rejectNonOrientedEnd`, `rejectNonContiguousStart`, `screenRearFacingEnds`, `screenDivergentCandidates`, `forwardOnlyPathInfo`, `footprintOf`, `standsOnAnyBlock` | `reservePath` | Request-level guards and screens of one `reservePath` call; their verdicts reach a dispatcher as the typed denials above. |
| `CandidateCommitter`: `tryAtomicReservation`, `registerAndFinalizeCandidate`, `rollbackUnconfigurableCandidate` | `reservePath` | Steps of one transaction; the rollback is correct only with the `priorSwitches` snapshot taken for the same candidate. |
| `PathReservationRegistry.expiredHoldings` (internal), `isTrainOnBlock` (a top-level internal function in `RouteReleaser.kt`), the reservation TTL | `DefaultReservationExpiry`, `RouteReleaser` | A query that only makes sense together with the release policy that acts on it (`DefaultReservationExpiry` is the only caller of `expiredHoldings`); the TTL is a behavioural constant. |
| Every mutating registry operation: `registerAtomic`, `registerSwitches`, `registerFlankSwitches`, `registerPathInfo`, `unregister*`, `reclaimStaleSwitchOwnership`, `restorePathInfo`, `trimPathInfoTo*`, `clear` | reservation transactions | Called alone each can leave blocks, switches, signals and PathInfo disagreeing. |
| `BlockOwnershipTable`, `SwitchOwnershipTable`, `PathInfoStore`, `PathInfoMerger`, `getSwitchClaim` | `internal` to `:core` | A dispatcher module cannot see them; Goal 9B should not ask for them to be widened. What it needs is a query on the registry facade. |

### 2.3 Goal 9B demands collected from the leaves

None of these is addressed by SP12; each names where the change would land.

| Demand | Leaf | Where it would land |
| --- | --- | --- |
| Read-only snapshot of open contentions (train, block, owner, since) or a "resolved" event, one of the two | SP12.1 D1, D2 | a query on `PathReservationService`; `blockedSince` already records the "since" time, kept (#1221) as the source of that column until this consumer exists |
| Which train a lit signal belongs to (semaphore → train view) | SP12.3 D1 | the facade or perception, never the ledger |
| One semaphore cache instead of two (`allSemaphores()` and `DefaultNetworkPerceptionPort`) | SP12.3 D2 | kept for layering, see 3.10 |
| Carry `switch`, `holder`, `held`, `required` of `SwitchConfigOutcome.Locked` through the reservation result | SP12.4 (SP5 D3) | the result type; today `Locked` is folded into `AllPathsBlocked` |
| Per-candidate cost, structured unavailability reason, multi-hop horizon | SP12.5 (SP5 D1, D3, D4) | `findReservationTargetCandidates`, `isPathAvailableFor`, a new enumeration beside `findNextSemaphoresVia` |
| A new registry query for *why* a switch is held (flank or running) or *when* a holding became stale | SP12.6 | `PathReservationRegistry` facade |
| A `ReservationExpired` event (train, age, TTL); per-train or per-route-class TTL; phase 2 stalled RESERVED tail | SP12.8, #1220 | the expiry seam; a physical-progress signal |
| Machine-readable denial instead of the English reason string | SP12.9 | `rearFacingEndReason`, `GeometricFailures.reason` |
| Commit-time precondition (a dispatcher-supplied hold re-checked before registration); structured rejection instead of `null` | SP12.10 | `registerAndFinalizeCandidate` |
| A stricter origin policy (origin must bound the train's front block) | SP12.9 | a new guard beside `rejectNonContiguousStart` |

## 3. Duplicate-check report

Every duplicate routing check found over `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/navigation/`,
verified against the current code on 2026-10-25 and cited by symbol. PR #1227 changes no
routing logic: "kept" means kept with a reason, "removed" names the change that removed it.

| # | Check | Verdict |
| --- | --- | --- |
| 3.1 | Signal-facing, three statements | kept |
| 3.2 | "Segment toward a section" lookups, four statements | kept |
| 3.3 | Next-target loops | **removed** (#1208) |
| 3.4 | Orientation-free sort arm of `reservePathToAnyTargets` | **removed** (#1207) |
| 3.5 | Availability predicates | kept (deliberate, #1060) |
| 3.6 | Contiguity, two different checks | kept; shared predicate already single-sourced |
| 3.7 | "Forward blocks = not owned by this train" filter | kept, tracked by #1224 |
| 3.8 | Oriented start / end guards | kept (deliberate, #1188 / #1200) |
| 3.9 | Switch-ownership layering | kept (defence in depth) |
| 3.10 | Owned-blocks / occupancy scans, semaphore caches | kept (#893) |
| 3.11 | `SwitchConfigurator` "no next track" skip | kept (guards non-traversed interior switches) |
| 3.12 | Smaller duplicates removed during SP12 | **removed** |

### 3.1 Signal-facing checks — kept

Three places answer "does this signal face the way the train moves", and they ask three different
questions of different geometry:

- `ReservationTargetDiscovery.processReachedSeparator` (called by `findNextSemaphoresVia`) compares
  `separator.direction() == travelDirection`, where `travelDirection` is the segment the train *leaves
  the start* by (`calculateTravelDirection`). It is a coarse BFS filter that decides which separators are
  worth offering as the first layer of targets and where to stop exploring.
- `RouteCandidateScreen.rearFacingEndReason` (G8, Issue #1064) compares `target.direction() ==
  anti(arrival)`, where `arrival` is the segment of the target's cell through which *this candidate's last
  section* reaches it (`arrivalSegmentAt`). It is exact per candidate and produces the denial reason.
- `semaphoreFacesNextBlock` (`SemaphoreFacing.kt`) answers the *departure* question (a train leaving a
  semaphore into the next block) and is the single shared source for `RouteSignalConfigurator`
  (`facesDirectionOfTravel`, G4, G8's intermediate rule) and `PathInfoStore`'s trim (Issue #1067 gap 2), so
  those two always agree.

Kept because the inputs differ (start-segment direction vs arrival segment vs departure block) and so does
the failure policy (the shared helper fails open on an unresolvable segment, `rearFacingEndReason`
returns `null` when there is nothing to judge, the BFS filter throws on a start with no location). Merging
the first two would change which separators the enumeration offers. A later behaviour-neutral
unification would need a property test over every fixture network to prove the enumerated set and the
G8 verdicts are unchanged.

### 3.2 "Segment toward a section" lookups — kept

Four statements of "find the cell segment whose edge is this section / block":

- `ReservationTargetDiscovery.calculateTravelDirection(start, next)` — `assignedEdges(location)` entry
  whose block `== next`; **throws** when none matches.
- `RouteCandidateScreen.arrivalSegmentAt(target, lastSection)` — the same lookup on the target's cell;
  returns **`null`** when not adjacent.
- `DefaultTopologyNavigator.getSegment(location, current)` — compares the **static refs** of both sides
  (`staticRefOf`), because the navigator's constraint must hold in simulation contexts where the graph
  edges are dynamic wrappers (Issue #797 follow-up); returns `null`.
- `SimulationContext.getSegment(separator, a, b)`, used by `semaphoreFacesNextBlock` and
  `SwitchConfigurator` — the context's own lookup; **throws** for a non-adjacent pair.

Kept: the null-vs-throw contract and the static-ref normalisation are deliberate and each is pinned by
its own caller's tests; one shared helper would need a flag for each. No behavioural benefit, and the
three navigation lookups sit on different sides of the `TopologyNavigator` / `ReservationTargetDiscovery`
boundary.

### 3.3 Next-target loops — removed (#1208, PR #1219)

`findReservationTargetCandidates` and `isAnyNextTargetAvailable` were the same `findNextSemaphoresVia`
plus per-target `isPathAvailableFor` composition at two entry levels. Both now enumerate once and
evaluate on `ReservationTargetDiscovery.evaluateTargets`, which returns a lazy `Sequence`; the candidate
query materialises it, the probe short-circuits on the first available target. The early stop is pinned
by counting `findAllSwitchConstrainedPaths` calls (`ReservationTargetDiscoveryTest.NextTargetPrimitive`).
The `#966` call-count pins stayed green.

### 3.4 Orientation-free sort arm — removed (#1207, PR #1219)

`reservePathToAnyTargets` took any `DynamicPathSeparator` and sorted InOuts by distance only for a
non-oriented start, a case `reservePathToAny` already refuses up front (Issue #1188). It now takes an
`OrientedPathSeparator` and the arm is gone. The sibling `TargetWithPaths` pair (a `Lazy?` that was `null`
for a semaphore) became the sealed `TargetAttempt` (#1209).

### 3.5 Availability predicates — kept (deliberate, #1060)

`ReservationTargetDiscovery.isPathAvailableFor` (any switch-constrained path whose blocks are all FREE, or
FREE-or-owned when an `ownerTrainId` is given) and `DefaultPathReservationService.reservePath` Step 2b
(`forwardBlocks.areAllFreeOrOwnedBy(trainId)`) both ask "can this train take these blocks". They differ in
input (every block of a switch-constrained path vs only the forward, not-yet-owned blocks of one
candidate) and the owner-aware variant exists *so that* the probe agrees with `reservePath` about the
train's own blocks (#1060). The per-block rule has one source, `DynamicTrackBlock.isFreeOrOwnedBy`, which
`areAllFreeOrOwnedBy` and `ReservationContentionTracker.findFirstBlockedConflict` both use.

### 3.6 Contiguity — kept; two different checks

- `RouteCandidateScreen.rejectNonContiguousStart` (Issue #893, Step 0): the **origin** must bound a block
  of the train's footprint. A request-level guard.
- `PathInfoMerger`'s Step 0a ("the new path must continue the stored one"): the **candidate** must start
  where the stored PathInfo ends. Its predicate and wording are single-sourced and shared with the
  read-only probe `PathReservationRegistry.nonContinuingCandidateReason`, which
  `RouteCandidateScreen.screenDivergentCandidates` (Step 1.6, Issue #1066) calls before anything is
  mutated. The merge's own Step 0a abort stays as the fail-safe behind the probe
  (`CandidateCommitter.rollbackIfMergeAborted`).

They guard different invariants (where the train is vs where its route ends), so there is nothing to merge;
the one genuine duplicate (probe vs merge) was single-sourced by SP12.6.

### 3.7 "Forward blocks = not owned by this train" filter — kept, tracked by #1224

The exclusion `filterNot { it.trainName == trainId }` is written twice: in `DefaultPathReservationService.reservePath`
(Step 2a.5, over the unique blocks of a candidate path, which also drives the already-owned early return) and in
`RouteCandidateScreen.screenDivergentCandidates` (the Step 1.6 probe, over `extractUniqueBlocks` of the candidate).
The two must keep selecting the same candidates: the probe predicts the merge that Step 2i would perform, so a
divergence would let the probe lie. Hosting one shared form, the way `extractUniqueBlocks` is a file-level helper,
is a code change and was ruled out of the PR that added this report (#1227, docs and tests only); it is filed as
**#1224** and is a two-line move.

### 3.8 Oriented start / end guards — kept (deliberate, #1188 / #1200)

`RouteCandidateScreen.rejectNonOrientedStart` (#1188) and `rejectNonOrientedEnd` (#1200) run at Step 0 of
`reservePath`; `reservePathToAny` repeats the START guard up front so the refusal is not re-derived per
candidate target; `reservePathToAnyTargets` now takes an `OrientedPathSeparator` (#1207); and
`rearFacingEndReason` keeps a `target !is OrientedPathSeparator` arm that only narrows the type (unreachable
after the END guard, a no-op). The repetition is the point: a switch is interior track, never an endpoint,
and a violation before any block is registered must be a typed refusal rather than the leak #1188 fixed.
All refusals return `GeometricallyImpossible`, so a dispatcher sees one class.

### 3.9 Switch-ownership layering — kept (defence in depth)

A switch claim is checked at five levels, each for a different failure mode:

1. `DynamicRailSwitch.setUpPath` refuses to move a switch locked in a different position
   (`SwitchLockedException`, SI-5) — the physical object's own invariant.
2. `SwitchConfigurator.switchOwnershipPreCheck` classifies the refusal as transient `Locked` *before*
   `setUpPath`, so contention is never mistaken for `Unconfigurable` (#742 vs #1065).
3. `SwitchOwnershipTable.registerSwitches` (via `PathReservationRegistry.registerSwitches`) rejects a switch
   registered to a different train atomically, pre-scan before any mutation, so the two ownership maps
   cannot disagree.
4. `SwitchOwnershipTable.wouldBeStaleClaim` / `isStaleSwitchOwnership`, applied by `SwitchConfigurator.registerSwitches`
   (born-stale claims, #1173) and by `reclaimStaleSwitchOwnership` on every block release (#1103).
5. `DefaultInterlockingFacade.lockSwitches` re-checks `isOwnedByOtherTrain` for the legacy facade path
   (a TOCTOU guard under a future concurrent model).

Kept: the layers do not repeat each other's work; each catches what the one below it cannot see (a lock
from outside the registry, a stale claim, a facade path with no `SwitchConfigurator`).

### 3.10 Owned-blocks / occupancy scans and semaphore caches — kept

- `RouteCandidateScreen.footprintOf` / `standsOnAnyBlock` scan the **graph** for the train's occupancy,
  next to the registry's O(1) `getBlocks` / `getOccupiedBlocks`. The scan is the only one that sees a
  train standing on a block the registry does not attribute to it (the Issue #893 stall at t=17); the
  registry reads are blind to that state. Kept.
- "A train stands on this block" has one definition, `isTrainOnBlock` (`RouteReleaser.kt`), shared by the
  route release and the kernel expiry (`PathReservationRegistry.expiredHoldings`) so they cannot disagree.
- `ReservationTargetDiscovery.allSemaphores` (Issue #965) and `DefaultNetworkPerceptionPort`'s cache scan
  the same grid and keep the same list. Kept (SP12.3 D2): the kernel must not depend on a port and the port
  must not reach into the kernel.

### 3.11 `SwitchConfigurator` "no next track" skip — kept

`configureSwitchAt` skips a switch with no next track (and, separately, one whose segments are
undeterminable). After the END-side guard (#1200) this branch is unreachable from `reservePath`. It is kept
because `configureSwitchesInPath` is `internal` to the package and receives candidates that list interior
switches the path does not traverse (the `zA → vA` shape described in the SP12.4 page); removing the
guard would turn that into a configuration of a switch nobody is passing. The lenient skip is a
documented non-pinnable coverage leftover (SP12.4 page).

### 3.12 Smaller duplicates removed during SP12

- The per-block availability rule was hoisted to `DynamicTrackBlock.isFreeOrOwnedBy` (SP12.1 review round).
- `recordBlockedContention` was inlined into `recordContentionAndEmitIfNew` (SP12.1).
- The PathInfo merge precondition (Step 0a) is single-sourced for the merge and the read-only probe (Issue #1066).
- The private `label()` helper of the discovery tests was replaced by the shared `separatorLabel`, and the
  single-caller `isPathAvailableFor` wrapper on the service was inlined (SP12.5 review round 2).
- The test-only `allSemaphores()` pass-through was dropped, and `DEFAULT_MAX_PATH_DEPTH` was tied to the interface
  `maxDepth` defaults, so the enumeration bound and the defaults cannot drift (SP12.5).
- The service's one-line `rollbackReservation` forwarder is gone; `CandidateCommitter` calls `RouteReleaser`
  directly (SP12.10).
- Nine stale class-qualified KDoc links and many `(:913)`-style anchors were retargeted to symbols
  (SP12.2, SP12.3); the remainder were retargeted in PR #1227 and are now guarded by `DocLineAnchorTripwireTest`.

## 4. Size outcome

`DefaultPathReservationService` class body, in code lines as detekt counts them (blank lines, `//` lines and
`/* … */` blocks excluded):

| Point | File lines | Class body code lines |
| --- | --- | --- |
| Before SP12 (`9953beb4`) | 4,280 | — (over the limit, `@Suppress("LargeClass")`) |
| After SP12.1–SP12.6 and SP12.5 (`e3fade0f`) | 1,962 | 916 |
| After SP12.9 | 1,621 | 752 |
| After SP12.10 | 979 | 441 |
| `goal-1B` after #1226 (adds #1173, #1200 and the #1174 kernel expiry; measured on #1227) | 1,072 | 469 |

`@Suppress("LargeClass")` and `@Suppress("LongMethod")` on `reservePath` are gone from the service;
`:core:detekt` is green without them. The only `@Suppress` left in `context/navigation/` is
`@Suppress("TooManyFunctions")` on `PathReservationRegistry`, which predates SP12 and which the split kept.
`PathReservationRegistry.kt` went from 1,758 to 797 physical lines (160 class-body code lines).

The 441 → 469 growth is the #1174 expiry orchestration that stays in the service (the `ReservationExpiry`
seam and the top-of-request expiry call; the expiry policy itself lives in `DefaultReservationExpiry`) and
the #1200 END guard; both are routing behaviour, not decomposition debt, and 469 is 59 % of the limit.

Every file in the package, measured with a script (strip `/* … */`, drop blank and `//` lines, brace-match
each top-level class). The script's figures reproduce the recorded 916 and 752 exactly (441 vs 442 at the
SP12.10 head; the one-line difference is detekt's own counting). Measured on #1227 (2026-10-25). No file has
more than 486 code lines, so no class can approach the 800 limit.

| File | Physical lines | Code lines (file) | Largest class | Class body code lines |
| --- | --- | --- | --- | --- |
| `DefaultPathReservationService.kt` | 1072 | 486 | `DefaultPathReservationService` | 469 |
| `DefaultTopologyNavigator.kt` | 871 | 353 | `DefaultTopologyNavigator` | 338 |
| `CandidateCommitter.kt` | 747 | 333 | `CandidateCommitter` | 301 |
| `RouteReleaser.kt` | 626 | 313 | `RouteReleaser` | 299 |
| `DefaultTrainNavigationService.kt` | 521 | 286 | `DefaultTrainNavigationService` | 274 |
| `ReservationTargetDiscovery.kt` | 610 | 275 | `ReservationTargetDiscovery` | 245 |
| `SwitchConfigurator.kt` | 551 | 225 | `SwitchConfigurator` | 201 |
| `RouteSignalConfigurator.kt` | 463 | 194 | `RouteSignalConfigurator` | 180 |
| `PathInfoStore.kt` | 519 | 227 | `PathInfoStore` | 175 |
| `RouteCandidateScreen.kt` | 422 | 188 | `RouteCandidateScreen` | 173 |
| `PathReservationRegistry.kt` | 797 | 178 | `PathReservationRegistry` | 160 |
| `ClearedSignalLedger.kt` | 340 | 159 | `ClearedSignalLedger` | 151 |
| `BlockOwnershipTable.kt` | 291 | 161 | `BlockOwnershipTable` | 150 |
| `SwitchOwnershipTable.kt` | 229 | 133 | `SwitchOwnershipTable` | 128 |
| `PathInfoMerger.kt` | 376 | 136 | `PathInfoMerger` | 126 |
| `PathReservationService.kt` | 846 | 95 | `PathReservationService` | 86 |
| `DefaultReservationExpiry.kt` | 155 | 61 | `DefaultReservationExpiry` | 58 |
| `ReservationContentionTracker.kt` | 184 | 57 | `ReservationContentionTracker` | 51 |
| `TopologyNavigator.kt` | 280 | 38 | `TopologyNavigator` | 33 |
| `BlockEvent.kt` | 85 | 32 | `BlockEvent` | 29 |
| `TrainNavigationService.kt` | 213 | 18 | `TrainNavigationService` | 15 |
| `RouteSearch.kt` | 52 | 16 | `RouteSearch` | 13 |
| `PathResult.kt` | 118 | 9 | `PathResult` | 7 |
| `RoutingServices.kt` | 115 | 8 | `RoutingServices` | 7 |
| `BlockReleaseSteps.kt` | 58 | 8 | `BlockReleaseSteps` | 6 |
| `BlockRollbackStep.kt` | 47 | 8 | `BlockRollbackStep` | 6 |
| `BoundedShortestDistance.kt` | 67 | 41 | `Arrival` | 6 |
| `ReservationTargetQuery.kt` | 77 | 13 | `ReservationTargetQuery` | 6 |
| `PathCandidate.kt` | 61 | 7 | `PathCandidate` | 5 |
| `ApproachLockedPathRelease.kt` | 106 | 10 | `PathRelease` | 4 |
| `GeometricFailures.kt` | 43 | 9 | `GeometricFailures` | 4 |
| `SwitchClaim.kt` | 26 | 5 | `SwitchClaim` | 4 |
| `ReservationExpiry.kt` | 41 | 4 | `ReservationExpiry` | 3 |
| `SemaphoreFacing.kt` | 40 | 12 | (top-level functions only) | - |
| `SeparatorLabel.kt` | 29 | 12 | (top-level functions only) | - |
| `UniqueBlocks.kt` | 47 | 21 | (top-level functions only) | - |

36 files (the issue counted 18 when it was written). The package is no longer dominated by one
4,280-line file: the largest class is 469 lines.

## 5. Side finding: kotlin-logging swallows log-lambda exceptions

Found in the SP12.9 fix round (the `logback-test.xml` DEBUG entry for `RouteCandidateScreen`, commit
`ecbb47dd`). `core/src/jvmTest/resources/logback-test.xml` pins `context.navigation` at WARN and adds a
DEBUG entry for each extracted class, with the rationale that the DEBUG level "makes the lambda bodies
actually evaluate … so a broken string interpolation in a log statement fails the build".

That rationale is weaker than stated. kotlin-logging (7.0.3, `MessageInvokerKt.toStringSafe`, which catches
`Exception`) evaluates a message lambda inside its own guard: an exception thrown while building the
message is caught and logged as an error, and the test does not fail. The DEBUG entries therefore make the
lambdas **run** (so a broken interpolation is exercised and shows up as an error line), but they do not by
themselves **fail** a test on one. Anyone relying on them as an
exception tripwire should assert on the captured log output instead. The comments in `logback-test.xml`
were not changed in PR #1227 (test resource, no behaviour); no new issue was opened for it.

## 6. Open follow-ups

| Issue | What | State |
| --- | --- | --- |
| #1218 | Facade rollback does not demote a plain switch claim that a failed route promoted to flank (safe direction, follow-up of #1173) | open |
| #1220 | Reservation expiry phase 2: expire the stalled RESERVED tail of a train that occupies part of its route (needs a physical-progress signal and its own `[sim]` ruling) | open |
| #1224 | Host one shared form of the forward-block exclusion (3.7) | open |
| #1221 | `ReservationContentionTracker.blockedSince` is a set masquerading as a map (the `Double` values are never read) | open |
| #1222 | Narrow `PathReservationServiceTestBase.inOutNamed` to `DynamicInOut` (removes five test casts) | open |
| #1228 | Minor follow-ups from the final review of the SP12 stack | open |
| #1229 | SP12.8 follow-ups: expiry trigger shape, seams and test cleanups (from the #1226 review round) | open |
| #1230 | Declare the docs and `.kt` files that `DocLineAnchorTripwireTest` scans as `:core:jvmTest` inputs (a doc-only change can reuse a cached result locally) | open |
| #1205 | the doc-anchor tripwire | PR #1227 open; closes with it |
| Goal 9B demands | section 2.3 | recorded, none addressed by SP12 |

Closed along the way: #1202 (PR #1206), #1204 (PR #1213), #1203 (PR #1214), #1188 (PR #1189); the leaves
#1167–#1174, #1200, #1210, #1211 and the SP12.5 follow-ups #1207–#1209 with the PRs in section 1.

## 7. Evidence so far

### Acceptance criteria of #1159 — status on 2026-10-25

#1159 stays open until its open sub-issues (section 6) are done; the rows below say where each criterion
stands now, not that SP12 is finished.

| Criterion | Where |
| --- | --- |
| Leaves #1167 to #1174 are closed | done; see section 1 |
| Every class in `context/navigation/` under the detekt `LargeClass` limit of 800 and `@Suppress("LargeClass")` gone from the service | section 4; `:core:detekt` green |
| `PathReservationServiceTest` groups (SP6.1) and `TargetSelectionSingleEntryTest` pass at every leaf's merge | each leaf page, "Acceptance evidence"; to be repeated for the open sub-issues |
| `reservationExpiryFollowsProgress` passes | `ReservationExpiryTest` (SP12.8 page) |
| Duplicate-check report | section 3 |
| `heavyTest` run on the final state | Green on the current routing code (`f72d09f2`); see "heavyTest" below. To be re-run when the open sub-issues land. |
| #895 re-baseline recorded before and after | Base == head on every column so far; see "#895 re-baseline" below. Per-leaf control-arm tables are on the leaf pages. |

### #895 re-baseline — rule-based control arm

Manual `aiSweep` control arm, grid `endTimeSeconds 600, repeat 10, perRunTimeoutSeconds 900,
axes: example: ["shuntingLoop"]`, other axes at their defaults. Railway outcomes summed over the 10 runs:

| Revision | Runs | Journeys | Entered | Exited | Max concurrent | Block transitions | Conflicts | Failed reservations |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| base `e3fade0f` (before SP12.9) | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| SP12 stack head before the #1226 review round (`f2d25ab9` build) | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| #1226 after its review round (`2982c3e7`, the `DefaultReservationExpiry` extraction) | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |

Both sweeps: every run `NATURAL_COMPLETION`, control gate PASS, C7 clean. Base == head on every column.
The same identity held at every leaf head in between (SP12.9, SP12.10, the SP12.5 follow-ups, #1173,
#1200, #1174); each PR records its own row.

### heavyTest

On the SP12 stack head before the #1226 review round (`840b0302`), solo, all three modules:
`BUILD SUCCESSFUL in 11m 15s`. The #1226 review round re-ran `:core` and `:dispatcher-agent` on `2982c3e7`
with the same totals (2,003 / 0 and 3,020 / 0). `fac13c69`, the #1226 head that was squash-merged as
`f72d09f2`, adds only the two Sonar fixes over `2982c3e7`; #1227 changes no routing code over `f72d09f2`
(KDoc, comments, docs and one test).

| Module | Tests | Failures | Errors | Notes |
| --- | --- | --- | --- | --- |
| `:core` | 2,003 | 0 | 0 | includes the Praha `MultiTrainScaleStressTest` |
| `:dispatcher-agent` | 3,020 | 0 | 0 | includes `RuleBasedDispatcherDeterminismHeavyTest` |
| `:desktop-ui` | — | — | — | has no heavy tests; recorded as empty, not as evidence |

Gate (`build detekt ktlintCheck test integrationTest`) on `fac13c69`: 7,155 tests, 0 failures, 0 errors.

### PR #1227

- `DocLineAnchorTripwireTest` (`:core` jvmTest, `cz.vutbr.fit.interlockSim.docs`): every markdown page under
  `docs/` is scanned for `<Name>.kt:<line>` and `<Name>.kt:<first>-<last>` anchors (dotted names such as
  `Resources.jvm.kt` included); it fails, listing page, line, anchor and reason, when the file is gone or
  ambiguous, the range is invalid (line 0, a backwards range) or longer than the file, or (only where a
  doc line names one anchor and one backticked identifier) no longer contains the identifier within three
  lines. It fails and never fixes. Before it was committed, the stale anchors it found in
  `PATH_RESERVATION_ARCHITECTURE.md`, `INTERLOCKING_SCOPE_LIMITATIONS.md` and eight older pages were retargeted
  to symbols (the two living pages entirely).
- The stale "rear-facing START or unconfigurable switch" enumerations in `MultiTrainLoop`, `InOutWorker`,
  `ReservationResultMapping`, `DefaultPathReservationService`, `DispatchDecisionApplier`, `ApplyFailureCode`,
  `InterlockingFacade`, `NetworkActuatorPort` and two dispatcher renderers now list every
  `GeometricallyImpossible` cause: a rear-facing START or END, an unconfigurable switch, a non-oriented route
  origin or end. Where a caller cannot produce the non-oriented refusals (`InOutWorker`, `MultiTrainLoop`,
  `recordAttemptResult`), the comment says so. KDoc and comments only.
- The `releaseRoute` KDoc points a retrying caller at `releaseRouteDetailed`, and the
  `GeometricallyImpossible` KDoc says one request returns it only when every attempted candidate is
  impossible (#937). KDoc only.
