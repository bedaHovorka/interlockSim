# SP2 — Station abstraction: demands on Goal 9B

**Issues:** bedaHovorka/interlockSim#1149 (Goal 1B, SP2) — leaves #1161 (topology-derived blocks
and signals) and #1162 (capacity, ids, log names, `:fast-sim`) · **Date:** 2026-10-11

SP2 replaced the hard-coded vyhybna lookup in `ShuntingLoop` (SIM-004) with `Station`
(`core/src/commonMain/.../sim/Station.kt`), derived from the track graph of any loaded network.
`ShuntingLoop` stays the vyhybna factory and name; it reads its blocks, signals and capacity
from `Station`. The vyhybna grid-coordinate constants are gone (`grep -rn COORD_ core fast-sim`
is empty); `:fast-sim`'s `ShuntingLoopNetworkValidator` and the `ShuntingLoop` constructor now
share one rule, `ShuntingLoop.compatibilityIssues(station)`.

`Station` is general; `ShuntingLoop` is not. It rejects the structures it is known not to run:
it requires at least one platform track, exactly 2 InOuts (its train generator pairs random
InOuts), a home signal on every entry/exit block, and no RailSemaphore that is neither a home
signal nor a platform-track end (the dispatcher only reads block inputs at those signals). Červený Újezd (4 InOuts) and
Praha (11 InOuts, 6 without a home signal, 14 unattached signals) are therefore rejected with an
English message — `fast-sim sim <file>` exits with code 2 — until SP3 (#1150) adds the first
runnable Červený Újezd scenario. The rule is structural only: track lengths, connectivity and
signal direction are not checked (`Station` checks signal direction since #1192, `ShuntingLoop` does not). A vyhybna-shaped network whose
shortest InOut-to-InOut distance is shorter than a train still passes it and aborts at run time
(found in the SP2 review; it predates SP2 — the old coordinate validator accepted it too).

This file records which station facts a dispatcher can now take from the topology, and which
still come from per-scenario configuration or from vyhybna-era constants.

## Station facts derived from the topology

| Fact | Rule (`Station.from`) | vyhybna | Červený Újezd | Praha hl. n. |
| --- | --- | --- | --- | --- |
| Entry/exit blocks | the block at each InOut, in InOut document order | kB, kA | 4 | 11 |
| Home signal of an entry/exit block | the RailSemaphore at the other end of that block, if any | zB, zA | 4 of 4 | 5 of 11 |
| Platform tracks (station tracks) | blocks with a RailSemaphore at both ends, neither end a home signal; row order | k1, k2 | 4 | 9 |
| Signals per block | both end semaphores of a platform track; the home signal of an entry/exit block | as before | | |
| Switches | every RailSwitch at a graph node | 2 | 12 | 50 |
| **Capacity** | `topologyCapacity` = number of platform tracks | **2** | **4** | **9** |
| Block id | the block's two end points in row order, e.g. `(16,8)-(25,8)`; unique per station | | | |
| Block name | `k` + InOut name for entry/exit blocks, `k1`, `k2`, … for platform tracks | k1 k2 kB kA | | |

**Capacity rule.** Capacity is the number of signal-bounded station tracks a train can stand on,
not the platform count of the station description. Praha has 8 platforms *and* a bypass station
track between signals (Y=20), so its capacity is 9; the signal-bounded block next to the N-Bypass
home signal (`(4,20)-(8,20)`) is part of the throat and does not count. A scenario override wins
(`Station.capacity(scenarioOverride)`, `ShuntingLoop(maxConcurrentTrains = n)`).

**The dispatcher default 2 no longer leaks** through the core seam: `ShuntingLoop` resolves its
admission gate from the station (`ShuntingLoop.stationCapacity`), and `wireSynchronousDispatcher`
defaults the `RuleBasedDispatcher` cap to that value. On vyhybna both still equal
`RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS` (2), which is why the A3 and #895 checks
below are unchanged.

**Logging.** `Station.from` writes one English INFO line naming every block with its unique id,
its signals and the capacity, for example
`Station derived: capacity 2; 2 platform track(s) [k1 (16,8)-(25,8) signals doA1, doB1; …]; …`
(asserted by `StationLoggingTest`).

## Facts that still come from per-scenario configuration

- **Capacity override** — `maxConcurrentTrains` on `ShuntingLoop` / `wireSynchronousDispatcher`
  / `MultiTrainLoop` (the Goal 1B exit parameters fix 4 for the Praha exit run).
- **Traffic** — which trains enter where, their destinations, lengths and headways (`Generator`
  for `ShuntingLoop`, `TrainSpec` lists for `MultiTrainLoop`), and the run's `endTime`.
- **Real platform numbering** — `Station` names platform tracks `k1…kn` in grid row order. Real
  track numbers (odd/even from the station building) are not in the XML.

## Demands

### D1 — The agent stack still reads the vyhybna constant

The Goal 10 dispatcher-agent code still takes capacity from
`RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS`: `KoogAgentFactory` (the `approve_train` cap,
two sites), `KoogDispatchAgentImpl` (prompt text), `DispatcherObservation` /
`DispatcherObservationProjector` (`capacity`), `DispatchDecisionApplier` (default cap) and the
`RunReportAggregator` re-derivation note. On vyhybna that is correct; on any other station it is
the leak this SP removed from the core seam. Removing station literals from prompts and tools
is SP8 (#1155).

**Demand:** the agent stack reads capacity from the `Station` (`ShuntingLoop.stationCapacity` of the
loop it drives), never from the vyhybna constant.

### D2 — Home signals reached through the throat

An entry/exit block whose InOut is joined to a switch (6 of 11 Praha InOuts, the south side) has
no adjacent home signal. `Station` reports it with no signal, and `ShuntingLoop` rejects such a
station. A dispatcher on such a station cannot see "the signal a train from
this InOut stops at".

**Demand:** the home signal (or signals) reached from an InOut through switch-only throat
blocks, per running direction.

### D3 — Capacity beyond a track count

Capacity counts station tracks. It does not see track length against train length, platform
tracks that only some lines can reach, or throat conflicts that cap simultaneous movements below
the track count.

**Demand:** a per-track reachability (which entry lines can reach which station tracks) and
usable length, so capacity can be stated per line and per train, not per station.

### D4 — Signal direction in the station-track rule (resolved by #1192)

SP2 shipped a platform-track rule that ignored which way the end signals face, and recognised
throat blocks only one block deep (the block next to a home signal). It would have counted an
automatic block section between two same-facing signals, or a signal-bounded throat block two
blocks in, as a station track. The fixtures happened to be right: Praha's excluded
`(4,20)-(8,20)` has same-facing signals, and all 15 real station tracks of the three fixtures have
opposite-facing end signals.

**Resolved in #1192** (`Station`, "Signal direction" in its KDoc): a station track is a block whose
two end signals both face away from the block (a departure signal at each end, so the signals face
away from each other); the home-adjacency special case is gone; the home signal of an entry/exit
block must face away from that block too, that is towards the station, so it faces the train
entering from the InOut. "Faces away from the block" is read from the signal's travel direction
(`orientation` false = `F`/`H`, true = `A`/`C`) against the graph edge attached at that segment, so it
holds for any block shape. `StationSignalDirectionTest` pins it on two new `:core-test` fixtures
(`station-throat-and-automatic-block.xml`, `station-home-signal-facing-away.xml`); the capacities
stay 2 / 4 / 9.

## Acceptance runs (2026-10-11)

Both leaves ran the full gate (`./gradlew build detekt ktlintCheck test integrationTest`) and
the #895 control sweep on their own head. The sweep reports live in the agent scratchpad, not in
`docs/`, per the sweep's keep-measurements-out-of-`docs/` policy.

### A3 — RuleBasedDispatcher 10-run determinism on `vyhybna.xml`

`RuleBasedDispatcherDeterminismTest` (`:dispatcher-agent:integrationTest`) passed in both gates:
10/10 identical rule-based runs on the leaf-1 head b9dde3d0 and on the leaf-2 code head
de8e6bd7. The absolute gates held on every run.

### #895 re-baseline — rule-based control arm

Manual `aiSweep` control arm: grid `endTimeSeconds 600, repeat 10, perRunTimeoutSeconds 900,
axes: example: ["shuntingLoop"]`, remaining axes at their defaults. Railway outcomes summed over
the 10 runs.

**Leaf 1 (#1161)** — before: Goal 1B tip 9953beb4; after: leaf-1 head.

| Revision | Runs | Journeys | Entered | Exited | Max concurrent | Block transitions | Conflicts | Failed reservations |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| base 9953beb4 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| head b9dde3d0 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |

**Leaf 2 (#1162)** — before: leaf-1 head; after: leaf-2 head.

| Revision | Runs | Journeys | Entered | Exited | Max concurrent | Block transitions | Conflicts | Failed reservations |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| leaf 1 b9dde3d0 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| head 852ec237 † | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |

† Measured on the first leaf-2 revision 852ec237. The review round that followed (folded into
de8e6bd7) only tightened `ShuntingLoop.compatibilityIssues`, deduplicated entry/exit blocks
that join two InOuts, and renamed the capacity field; the vyhybna run path is unchanged, so the
sweep was not repeated.

Every sweep: all 10 runs `NATURAL_COMPLETION`, control gate PASS, C7 clean. base == head for
both leaves: `Station` reproduces the vyhybna lookup exactly, and the topology capacity resolves
to the old default 2.
