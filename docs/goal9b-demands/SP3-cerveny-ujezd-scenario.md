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

## Railway conflicts the scenario exposes

- **Crossing routes in the throat.** The two west entry lines and the two east exit lines meet in
  a switch ladder (switches on columns 7–9 and 33–35). Two trains with routes through the same switches
  are separated by block and switch ownership (`PathReservationRegistry`), not by an explicit conflict
  list. A route with 2 or more switches exists and is exercised.
- **Bidirectional station tracks.** Each station track has a signal at both ends, so one track serves
  trains from either side. Opposite-direction use of one track is possible and is kept apart by block
  ownership.
- **Capacity above vyhybna.** Capacity is 4 (the track count), against 2 on vyhybna.

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

The GUI example `cervenyUjezd` uses the dispatcher-agent stack, which still reads the vyhybna capacity
(2) from `RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS`; the console and native examples use
`wireSynchronousDispatcher`, which reads the station capacity (4). This repeats SP2 demand D1 and is
owned by SP8 (#1155). Signal direction in the station-track rule (SP2 demand D4) is owned by #1192.

### D6 — Names for signals

A loaded network with unnamed signals cannot be dispatched (see the finding above). The XML loader and the
editor should give every semaphore a stable name, or the dispatcher should address signals by position.

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
