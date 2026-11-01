# SP3 — Červený Újezd scenario: demands on Goal 9B

**Issues:** bedaHovorka/interlockSim#1150 (Goal 1B, SP3) — leaves #1163 (the `commonTest` run) and
#1164 (CLI, GUI and native registry entries) · **Date:** 2026-10-29

The issue text calls the fixture `rudyUjezd`. The traffic-simulation-expert review ruled that the
name stays `cerveny_ujezd` everywhere: the file `cerveny_ujezd.xml`, `NetworkResources.CERVENY_UJEZD_XML`,
`TestFixtures.loadCervenyUjezdXml()`, and the example name `cervenyUjezd`. This file replaces the
`SP3-rudy-ujezd-scenario.md` named in the issue.

## What SP3 delivered

- **A runnable second station.** `ShuntingLoop` now runs on Červený Újezd (4 InOuts, 12 switches,
  12 signals, 4 station tracks) with its blocks and capacity (4) taken from `Station`. The rule in
  `ShuntingLoop.compatibilityIssues` changed from "exactly 2 InOuts" to "InOuts of both orientations",
  and `Generator` pairs an InOut with one of the opposite side. With two InOuts (vyhybna) the pairing
  and the random draws are unchanged, so vyhybna output is unchanged (#895 below).
- **A deterministic run.** `CervenyUjezdRunTest` (`commonTest`, JVM and `:core:linuxX64Test`): 3 trains
  from seed 1163, run by the synchronous rule-based dispatcher (`wireSynchronousDispatcher`; no LLM
  agent, no dispatcher-agent threads). All 3 exit, no block is occupied or reserved at the end, at
  least one route holds 2 or more switches (read from `PathReservationRegistry.getSwitches` while the
  train is on the route), and a second run with the same seed gives the same exit order.
- **Launch by name.** `cervenyUjezd` is in the `ExampleRegistry` CLI map, the GUI map and
  `NativeExampleRegistry`. The XML is a copy in `:core` (`BuiltinNetworks.CERVENY_UJEZD_XML`); a test
  pins it to the `:core-test` fixture.

## Findings from making the station run

- **Unnamed semaphores cannot be dispatched.** The dispatcher addresses a signal by name. The fixture had
  12 unnamed `RailSemaphore` elements, so every `ReservePath` failed with `Unknown route endpoint:` and no
  train left. SP3 named them in `cerveny_ujezd.xml`: `homeW1`, `homeW2`, `homeE1`, `homeE2` (the entry
  signals) and `w30`–`w33`, `e30`–`e33` (the two ends of the station tracks on rows 30–33).
- **Train names are a process-wide counter.** Two runs in one process never share train names, so
  "identical exit order" is compared as generation ordinals (first, second, third generated train).
- **The seed is a generator seed only.** It fixes arrivals and the InOut pairs. The dispatcher is
  deterministic by construction, so the exit order is a function of the seed.
- **Trains left by the wrong exit (2026-10-31, Issue #1278).** With two exits per side the
  nearest-exit rule of `ReservationTargetPolicy` ignored the timetable's `out`, and `Train` reported
  `ends in wrong out`: 4 of the first 7 journeys of the first GUI run, 2 of the 3 seeded trains of
  `CervenyUjezdRunTest`. The interlocking stayed consistent; it was a policy mismatch. The policy now
  prefers the timetable's exit when it is an available candidate, which leaves 1 of 3 (the own exit
  was blocked by another train's route at the moment of the pick). Detail and the open part:
  [SP5 — D2 Destination awareness](SP5-target-selection-path.md#d2--destination-awareness).
- **Unnamed switches print `unknown` block labels (2026-10-31, #1279).** The first GUI run printed
  `Train #3 enter block unknown` for the throat blocks: `blockLabel` (and `BlockIdentity.stableBlockId`)
  fall back to `unknown` when a block and both its end separators are unnamed, and all 12 `RailSwitch`
  elements had no `name`. Both XML copies were named in ladder order, from the station boundary
  inward, row 31 before row 32: `vW1`–`vW6` (columns 7, 8, 9) and `vE1`–`vE6` (columns 35, 34, 33),
  after vyhybna's `vA`/`vB`. `CervenyUjezdBlockLabelTest` pins that no TRAIN_EVENTS block label of the
  seeded run is `unknown`. Routing, seeds and physics are unchanged. Side effect: the dispatcher-agent
  topology (`StationTopologySerializer.describe`) now lists the 12 switches, and throat blocks get
  distinct `<end>-<end>` ids instead of collapsing into one `unknown` id. After the reviewer flagged
  the doubled file, the `:core-test` fixture copy was removed (PR #1287 relink):
  `NetworkResources.CERVENY_UJEZD_XML` delegates to `BuiltinNetworks.CERVENY_UJEZD_XML`, and tests
  read the single `:core` copy that `:fast-sim` also embeds.

## Railway conflicts the scenario exposes

- **Crossing routes in the throat.** The two west entry lines and the two east exit lines meet in
  a switch ladder (switches on columns 7–9 and 33–35). Two trains with routes through the same switches
  are separated by block and switch ownership (`PathReservationRegistry`), not by an explicit conflict
  list. A route with 2 or more switches exists and is exercised.
- **Bidirectional station tracks.** Each station track has a signal at both ends, so one track serves
  trains from either side. Opposite-direction use of one track is possible and is kept apart by block
  ownership.
- **Capacity above vyhybna.** Capacity is 4 (the track count), against 2 on vyhybna.

## Follow-up: signal direction in the compatibility rule (2026-10-31, #1272)

`ShuntingLoop.compatibilityIssues` takes the signal direction from `Station`, the one place the rule is
decided (`Station.from`, "Signal direction" in its KDoc). An entry/exit block whose end semaphore faces
its InOut has no home signal; `Station` keeps that semaphore as `StationBlock.misdirectedSignals`, and the
issue names it (`station-home-signal-facing-away.xml`: "... kA ... has no home signal: its end
RailSemaphore zA governs travel towards the InOut (a departure signal), so no home signal (vjezdové
návěstidlo) protects the incoming train") instead of claiming the block does not end at a
RailSemaphore. The verdicts are unchanged: vyhybna and Červený Újezd are accepted (no misdirected
signals), Praha is rejected; `StationCapacityTest`, `StationSignalDirectionTest` and `:fast-sim`'s
`ShuntingLoopNetworkValidatorTest` pin it.

**Wording and the two-semaphore case (#1298, 2026-11-01).** The first text said zA "faces the InOut,
away from the incoming train". In railway usage a signal faces the train that sees it, but the rule
means the travel direction the signal governs, so the text now says "governs travel towards the InOut
(a departure signal)", and the `Station` KDoc ("Signal direction") states that "faces" means that travel
direction. #1298 also asked for a reason when both end semaphores of an entry/exit block face away. That
block cannot exist: one end of an entry/exit block is its InOut cell, and `Station.from` counts only
`DynamicRailSemaphore` grid cells at the block ends. The InOut's own embedded in/out semaphores
(`DynamicInOut.inSemaphore`, `outSemaphore`) are no separate cells, so the block has at most one end
semaphore — the home signal or one misdirected signal. The remaining text "it
does not end at a RailSemaphore" is right for the two real cases (the other end is a switch, or the block
joins two InOuts). `StationSignalDirectionTest.everyEntryExitBlockHasAtMostOneEndSemaphore` pins this over
vyhybna, Červený Újezd, Praha and the facing-away fixture.

## Left out — demands on Goal 9B

### D1 — Explicit conflict set of routes (Czech rule book D1)

The Czech operating rule book D1 works with a set of conflicting routes (*jízdní cesty*): routes that
share a track, cross in a switch, or run head-on. The simulator derives conflicts only from ownership at
reservation time: the first route to register a block or switch wins, the other waits. There is no
route-level conflict table, no flank protection list beyond `registerFlankSwitches`, and no check that a
cleared signal still has its *volnost jízdní cesty* (route free) and *závěr* (route locking) conditions.

**Demand:** a conflict set per route pair derived from the topology (shared block, shared switch in
opposite positions, flank), so a dispatcher can be told why two routes exclude each other and a test can
assert the table instead of the outcome.

### D2 — Traťový souhlas (line-clear consent)

Each InOut is a source and sink with no station behind it. A train enters when its first block is free.
Real stations ask the neighbouring station for *traťový souhlas* before a train is sent into the line
section, and a single-track section carries one direction at a time.

**Demand:** a neighbour-consent step at an InOut: a train may be admitted to the line only with consent,
and consent for one direction withholds the other on a single-track section.

### D3 — Posun (shunting)

Every train runs once from an InOut to an InOut. There is no shunting: no reversal on a station track, no
movement against the signal with a shunting permission, no coupling, and no wait on a track for a later
departure. The timetable carries an arrival and a departure time, but the train never reverses.

**Demand:** a shunting movement type (*posunová cesta*) with its own permissions and speed limit, and a
reversal at a station track, so the second half of a station's work is represented.

### D4 — Capacity per line and train

The topology capacity (4) counts station tracks. It does not see which tracks a given entry line can
reach, track length against train length (the station tracks are 100 m, the throat blocks 5 m), or throat
conflicts that cap simultaneous movements below 4. This repeats SP2 demand D3, now measurable.

**Demand:** reachability and usable length per entry line and train.

### D5 — Capacity and signals in the agent stack

The agent-stack arm examples read the vyhybna capacity (2) from
`RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS`: the GUI `cervenyUjezd` from SP3 onward, and since
the Issue #1276 rewire (PR #1282 review, 2026-11-01) the console `cervenyUjezd` too, so its `aiSweep`
runs write a run JSON. The synchronous builds — the `cervenyUjezdSync` parity twin and the native
`fast-sim` example — still use `wireSynchronousDispatcher`, which reads the station capacity (4).
This repeats SP2 demand D1 and is
owned by SP8 (#1155). Signal direction in the station-track rule (SP2 demand D4) is owned by #1192.

### D6 — Names for signals

A loaded network with unnamed signals cannot be dispatched (see the finding above). The XML loader and the
editor should give every semaphore a stable name, or the dispatcher should address signals by position.
**Resolved** for the loader by the 2026-10-31 decision below; the 2026-11-01 decision settles how a save
treats the generated names.

#### Decision 2026-10-31 (#1273): the loader names unnamed semaphores by grid position

Ruling (local gemma4, option c of three: auto-name, clear load error, auto-name plus a WARN):

- `XmlContextReader` gives a `RailSemaphore` without a `name` attribute the name `S_<X>_<Y>` of its grid
  position (`GeneratedSemaphoreName`), for example `S_15_10`. The name is a pure function of the
  position: stable across loads, independent of document order, and not taken from the editor's global
  `AutoNameGenerator` (whose `S1`, `S2`, ... never take this form).
- One WARN line per load reports how many semaphores were named this way.
- Explicit names are untouched. A generated name that is also the explicit name of another element fails
  the load with an `IllegalArgumentException` naming both positions.
- `XmlContextWriter` writes a semaphore name only when it differs from the generated name of the
  semaphore's own position, so saving a loaded network adds no names the user never wrote; the next
  load regenerates them. A semaphore the user renames in the editor is saved with its new name.
- Switches are not auto-named; their names are written by hand (#1279).

Effect: all 37 Praha signals are addressable by name (19 explicit, 18 generated), so the rule-based
`ReservePath` and the LLM topology prompt can use every one of them. No fixture changed, and the golden
outputs (fully named `vyhybna.xml`) are unchanged. Pinned by `XmlContextReaderSemaphoreAutoNameTest` and
`UnnamedSemaphoreRoutingTest`.

#### Decision 2026-11-01 (#1332): keep the writer-side inference

PR #1283 made `XmlContextWriter.generate()` decide statelessly: it omits a semaphore's name when the name
is the generated name of the semaphore's own position and no other node in the context writes the same
name. Two variants were open after its review; the owner chose to keep this behavior.

- **Not taken: reader-side provenance.** `XmlContextReader` would record which names it generated, the
  context would carry that set (with an empty default for contexts the editor creates), and the writer
  would omit exactly that set. It would make the omission exact instead of inferred, at the cost of new
  state on `DefaultEditingContext`.
- **Kept: the silent-drop variant.** A user who types a generated-form name (`S_15_10` on the semaphore at
  (15,10)) with no other node sharing it gets a file without that name attribute; the next load
  regenerates the identical name. The round trip is therefore stable, and no fixture or resource has the
  case (checked by grep in the PR #1283 review).
- A semaphore whose name is the generated form of **another** position (for example one moved from
  (14,10) to (15,10) that kept `S_14_10`) does not match its own position, so its name is written and
  survives the reload.
- `S_<X>_<Y>` is a position id, not a railway designation. A real signal name tells its role, track and
  direction; a generated name carries none of that, so it says nothing about whether the signal is a home
  (*vjezdové*) or a departure (*odjezdové*) signal.
- The "reload regenerates it" argument holds only while the `GeneratedSemaphoreName` format stays frozen.
  Files saved with dropped names depend on it, so the format is a file-format contract: changing it would
  rename the signals of every such file on the next load.

Pinned by `XmlContextReaderSemaphoreAutoNameTest` (`aTypedGeneratedFormNameWithNoSharerIsOmittedOnSaveAndRegeneratedOnReload`,
`aGeneratedFormNameOfAnotherPositionIsWrittenExplicitly`, next to the existing save tests).

## Evidence

### Run timing

| Run | Time |
| --- | --- |
| `CervenyUjezdRunTest` (two runs of 600 s simulated time), JVM | 2–8 s per test on a loaded machine |
| `CervenyUjezdRunTest`, `:core:linuxX64Test` | the whole Gradle invocation took 5 s; the run logs its wall time, the 40 s budget is gone (asserting a load-dependent number flaked the suite — #1011), the CI native step's timeout is the guard |
| `fast-sim example cervenyUjezd 600` (debug binary) | exit code 0, 19 trains generated, 14.1 s wall |

### #895 re-baseline — rule-based control arm

Manual `aiSweep` control arm: grid `endTimeSeconds 600, repeat 10, perRunTimeoutSeconds 900,
axes: example: ["shuntingLoop"]`. Railway outcomes summed over the 10 runs. The "before" jar was built
from the Goal 1B tip ef61e908; the "after" jar from the SP3 head.

| Revision | Runs | Journeys | Entered | Exited | Max concurrent (per run) | Block transitions | Conflicts | Failed reservations |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| before: goal-1B ef61e908 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| after: SP3 head | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |

Every run: `NATURAL_COMPLETION`, control gate PASS, C7 clean. before == after: the generator change keeps
the vyhybna draws, so the vyhybna scenario is unchanged. The sweep reports live in the agent scratchpad,
not in `docs/`, per the sweep's keep-measurements-out-of-`docs/` policy.

### Run-end accounting split (#1280, 2026-10-31)

The first GUI run of `cervenyUjezd 600` ended with `endCause=NATURAL_COMPLETION ... conflicts=2`
although nothing double-booked: the two "conflicts" were train #19's contentions behind train #18,
still open when the end time cut the run off and reported by the end-of-run flush
(`PathReservationService.flushUnresolvedConflicts`). The scenario's generator never stops, so every
run limited by its end time leaves the last trains on the network and reports them this way.

The run summary now keeps the two signals apart:

- `BlockEvent.ReservationConflictDetected.atRunEnd` marks the events of the end-of-run flush.
- `MetricsSnapshot.conflictCount` counts only the double-bookings the registry caught while the run
  was live (`CandidateCommitter`); `MetricsSnapshot.unresolvedAtRunEndCount` counts the flushed
  contentions.
- `RailwayOutcome.unresolvedAtRunEnd` carries the second figure into the run JSON (schema version
  10), the final summary line prints `unresolvedAtRunEnd=N`, and the sweep report's Railway Outcomes
  table has an "Unresolved at run end" column, appended last. Files of version 9 or older load with
  the figure absent and keep their `conflicts` as stored.

The motivating run now reads `conflicts=0 unresolvedAtRunEnd=2`. The end cause stays
`NATURAL_COMPLETION`: a new end cause for "cut off with trains on the network" would mark every
`cervenyUjezd` run as not passing, and the #895 vyhybna baseline (all journeys finish before the end
time, `conflicts=0`, no open contention at the end) is unchanged by the split.
