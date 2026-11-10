# SP5 — Target selection: demands on Goal 9B

**Issue:** bedaHovorka/interlockSim#1152 (Goal 1B, SP5) · **Date:** 2026-10-10

SP5 made target selection a single path: the shell reports
`BlockInputObservation.candidateTargets` (a list of `CandidateTarget`), and every dispatcher —
the rule-based one, the LLM arm's `NextHopResolver`, and anything written later — picks from
that list through `ReservationTargetPolicy.pick`. The legacy single-name projection
(`toSeparatorName`) is gone, so there is no second opinion about which route to set.

That sharpens a question SP5 deliberately does not answer: **what can a dispatcher still not
say, because `CandidateTarget` cannot carry it?** Those gaps are recorded here as demands on
Goal 9B (the dispatcher capability work). `CandidateTarget` is *not* the Goal 12 protocol; no
protocol commitment is made by this list.

## What `CandidateTarget` carries today

```kotlin
data class CandidateTarget(
	val name: String,        // separator name — a legal route endpoint
	val kind: SeparatorKind, // IN_OUT or SEMAPHORE
	val available: Boolean   // a physically legal, all-FREE route to `name` exists right now
)
```

Three facts, one section ahead, per block input. Nothing else.

## Demands

### D1 — Priority / cost among candidates

`pick` is a fixed rule: station exits before signals, then the interlocking's own search order,
first available wins. A dispatcher cannot express *"this target is better"*: there is no route
length, no expected running time, no platform preference, no train class weighting. Every choice
between two available candidates is therefore arbitrary from the dispatcher's point of view, and
the only way to influence it today is to change the policy for everybody.

**Goal 9B demand:** a comparable cost or priority per candidate, chosen by the dispatcher rather
than baked into one global policy.

### D2 — Destination awareness

`candidateTargets` is destination-agnostic by design — it answers *"where could a route be set
from here"*, never *"where should this train go"*. The train's destination InOut is reported
separately (and in the LLM arm only as prose), so neither the rule-based dispatcher nor the
model can tell which candidate leads **toward** the destination and which leads away. On
`vyhybna.xml` the topology hides this: hops are forced. On a larger station it is the whole
problem.

**Goal 9B demand:** a per-candidate statement of progress toward the train's destination
(reachability, remaining distance, or a "this target is on a path to X" flag).

**Update 2026-10-31 (Issue #1278).** Červený Újezd (two exits per side) made the gap visible:
the nearest-exit rule sent trains out by the other east exit than their timetable's, and
`Train` reported `ends in wrong out` (2 of the 3 seeded trains of `CervenyUjezdRunTest`). The
first step is in: `BlockInputObservation.destinationName` carries the owning train's timetable
exit (filled by `ShuntingLoop` only where `candidateTargets` is), and `ReservationTargetPolicy.pick`
takes that exit when it is an *available* `IN_OUT` candidate; otherwise the fixed order stays.
Every dispatcher arm still picks through `chosenTargetName`, so `NextHopResolver` follows the
same choice. On `vyhybna.xml` (one exit per side) the pick is unchanged.

What it does not cover, and D2 still demands: the destination decides only among candidates one
section ahead. The signal a train is routed to inside the station is still the first available
one, and the policy never waits for the own exit: in the seeded run Train #2 stands at `e32`
while its exit `east_exit_1` is a candidate but not available (Train #1 holds the throat), so the
fallback takes `east_exit_2` — 1 of 3 wrong-out reports left. Waiting for the own exit, and
steering through the station toward it, belong to a destination-aware router (Goal 19).

**Update 2026-11-03 (Issue #1299).** The policy now waits for the own exit. When the timetable
exit is listed in `candidateTargets` but not available, `ReservationTargetPolicy.pick` picks
nothing (the train waits and is reconsidered next tick) instead of falling back to the other
exit. The wait is bounded in simulation time by `ReservationTargetPolicy.OWN_EXIT_WAIT_BOUND_SECONDS`
(120 s, confirmed by traffic-simulation-expert 2026-11-03); at the bound the fixed-order
fallback applies again, so a holder that never releases — or a train that needs the waiting
train's block to clear the throat; `ShuntingLoop` has no SP7 watchdog — cannot hold the train up
for ever (`OwnExitWaitBoundTest` pins this with a stand-in occupant that never leaves the exit block).

The clock lives in the shell, not in a dispatcher: `ShuntingLoop` records when a train started
waiting at an input (the first tick at which `ReservationTargetPolicy.isWaitingForOwnExit` holds
there) and reports the elapsed simulation seconds as `BlockInputObservation.destinationWaitSeconds`.
`chosenTargetName` passes it to the policy, so `RuleBasedDispatcher` and `NextHopResolver` see the
same wait and make the same pick. An observation without a wait (`null`) keeps the immediate
fallback of #1278.

Measured with an unbounded wait, sampled on the 2 s control tick: in the seeded
`CervenyUjezdRunTest` run Train #2 waits at `e32` while Train #1 clears the throat — 14 s
reported, `east_exit_1` free 16 s after the wait began — and then leaves by its own exit, so the
seeded run has no `ends in wrong out` report left. Over eleven `cerveny_ujezd.xml` runs (600 s,
seeds 0–9 and 1163, uncapped generator) the longest wait that ended within the run lasted 76 s
(median about 14 s); with the 120 s bound none reaches it, and the wrong-exit count drops from
15 to 0. The waits cost throughput in two of those runs (seed 5: 12 → 10 exited, seed 8:
12 → 11; the others unchanged). On `vyhybna.xml` (one exit per side) the wait never changes a
pick: a blocked own exit there leaves no other exit to fall back to.

The two runs that exit fewer trains were checked train by train (2026-11-03, same harness, bound 0
— the pre-#1299 behaviour — against bound 120). Every missing exit is a train still in the station
or still queued at its entry at 600 s; none is lost and none is stuck. Each train in the station at
the end moved on the last control tick, and the trains still waiting for their own exit at the end
had waited 50 s (seed 5) and 36 s (seed 8), both below the bound. Entered = exited + in the station
+ queued at the entry in all four runs:

| Seed | Bound | Entered | Exited | In the station at 600 s | Queued at the entry | Waiting for own exit at 600 s | Longest own-exit wait in the run |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5 | 0 s | 16 | 12 | 4 | 0 | — | 10 s |
| 5 | 120 s | 16 | 10 | 4 | 2 | 1 (50 s, still moving) | 74 s |
| 8 | 0 s | 14 | 12 | 2 | 0 | — | 18 s |
| 8 | 120 s | 14 | 11 | 3 | 0 | 1 (36 s, still moving) | 36 s |

With bound 0 the policy never holds a train; there the last column is how long the own exit stayed
blocked while no other exit was free either. The station is full (capacity 4) in
seed 5 at the end, so two generated trains are still queued at their entry: the waits delay the
later trains, they do not stall them.

**Condition (traffic-simulation-expert, 2026-11-03):** the SP10 Červený Újezd control-arm
re-baseline (capacity 4, PR #1350 / #1303) must log the longest natural own-exit wait. If it
exceeds 96 s (0.8 × the bound), the bound is reopened.

The wait clock restarts when the own exit drops out of the candidate list for even one tick (the
shell rebuilds its record every tick); the bound applies to the current uninterrupted wait.

D5 is what this step works around: the dispatcher still cannot see *when* the exit frees, so
the bound is a fixed number rather than an expected-free time.

### D3 — Why a candidate is unavailable

`available = false` is a single bit. The dispatcher cannot distinguish *occupied by a train*
from *reserved by another train*, *a switch is locked against us*, or *the route is
geometrically impossible for this approach*. Those call for different responses — wait, re-plan,
or never try again — and today all three look identical, so a dispatcher can only wait.

**Goal 9B demand:** a structured unavailability reason, reusing the vocabulary
`PathReservationService.ReservationResult` already has.

### D4 — Horizon beyond one section

The list stops at the next separator. Multi-hop planning ("reserve to B now because C is where I
must end up") cannot be expressed, so the dispatcher re-decides from scratch each cycle and
cannot commit to a plan.

**Goal 9B demand:** either a multi-hop candidate view, or an explicit statement that
one-section-at-a-time is a permanent constraint of the interlocking seam.

### D5 — Time

Nothing in `CandidateTarget` is time-valued: there is no "available in ~12 s once Train #3
clears". A dispatcher must choose between *now* and *never*, which is why waiting looks like
inaction.

**Goal 9B demand:** an expected-free time (or a "blocked by <train>" attribution the dispatcher
can watch) for unavailable candidates.

## Out of scope for SP5

Each demand above would change the observation vocabulary, and therefore the prompt and the
rule-based dispatcher at the same time. SP5's contract was the opposite: identical behaviour
(the A3 determinism check and the #895 re-baseline below are unchanged), one reader instead of
two. Growing the vocabulary is Goal 9B's work.

## Acceptance runs (2026-10-11)

The two Issue #1152 acceptance checks ran locally on the PR head 8b67c567 and on its base,
Goal 1B tip 8f6b5e24, so "before" and "after" refer to the same checkout pair throughout.

### A3 — RuleBasedDispatcher 10-run determinism on `vyhybna.xml`

`RuleBasedDispatcherDeterminismTest` (`:dispatcher-agent:integrationTest`): 10/10 repetitions
produced the identical outcome at the base revision and again at the head revision; the
absolute gates held on every run (`trainsExited > 0`, `maxConcurrentTrains = 2`,
`conflictEventCount <= 2`).

### #895 re-baseline — rule-based control arm

Manual `aiSweep` control arm (no `model` axis): grid `endTimeSeconds 600, repeat 10,
perRunTimeoutSeconds 900, axes: example: ["shuntingLoop"]` with the remaining axes at their
defaults (`tickPeriodMs 0`, `historyN 3`, `maxActionsPerTick 3`). The grid and the two rendered
`report.md` files live under `build/` only, per the sweep's keep-measurements-out-of-`docs/`
policy. Railway outcomes summed over the 10 runs:

| Revision | Runs | Journeys | Entered | Exited | Max concurrent | Block transitions | Conflicts | Failed reservations |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| base 8f6b5e24 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| head 8b67c567 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |

Both sweeps: every run `NATURAL_COMPLETION`, control gate PASS, C7 clean. Per run that is
15/15/15 (journeys/entered/exited) with 30 block transitions — *not* comparable to the #895-era
control ceiling (15/11/25 on goal-10 in August 2026); the network and its rules have moved since.
What the acceptance criterion needs is base == head, and it holds.

### #895 re-baseline for Issue #1299 (2026-11-03)

Same control-arm grid with `example: ["shuntingLoop", "cervenyUjezd"]`; base is `goal-1B` 375c54b1,
head is the #1299 branch with the bound confirmed (e217d2e4). Railway outcomes summed over 10 runs:

| Example | Revision | Runs | Journeys | Entered | Exited | Max concurrent | Block transitions | Conflicts | Failed reservations |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shuntingLoop | base 375c54b1 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| shuntingLoop | head e217d2e4 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| cervenyUjezd | base 375c54b1 | 10 | 180 | 190 | 170 | 2 | 380 | 0 | 100 |
| cervenyUjezd | head e217d2e4 | 10 | 180 | 190 | 170 | 2 | 370 | 0 | 140 |

Every run `NATURAL_COMPLETION`. vyhybna (`shuntingLoop`): base == head. Červený Újezd: the 10 runs
are deterministic, and every run changes the same way — block transitions 38 → 37, failed
reservations 10 → 14, `ReservePath` decisions emitted 48 → 51; journeys, entries and exits are equal.
The per-train block sequences of run r01 explain all three numbers:

- **Train #3 now leaves by its own exit.** In the base run it ended in the wrong out
  (`keast_exit_1`); at head it waits on track k3 at `e32` and leaves by `keast_exit_2`, its timetable
  exit. The run's one "ends in wrong out" report is gone. Its journey has the same number of blocks.
- **Train #5 is redirected and refused 4 times.** With k3 held by the waiting Train #3, Train #5's
  next section from `homeW2` becomes `e30` (track k1). That route needs switch `vW5` in BRANCH, and
  Train #4's live route holds `vW5` in MAIN. The interlocking refuses the request under SI-5
  (`Switch vW5 is locked in MAIN by 'Train #4' but train Train #5's candidate needs BRANCH`, then
  `all paths blocked for Train #5 (homeW2 → e30)`) on 4 consecutive dispatcher ticks, until Train #4
  releases `vW5`; Train #5 then enters and leaves normally by `keast_exit_2`, about 11 s later than in
  the base run. These 4 refusals are the +4 failed reservations and 4 of the extra emitted decisions.
- **Train #19 is one block behind at the 600 s end.** Delayed by the above, it reaches `k1` but not
  `vW5-w30` before the run ends: −1 block transition, and one hop fewer requested, so the emitted
  `ReservePath` count is 4 − 1 = +3.

The refusals are safety-correct, transient and deterministic, and no stall follows. The
traffic-simulation-expert accepted them as the cost of waiting for the own exit (2026-11-03). The
candidate list showed `e30` as available while `vW5` was locked against the route; that availability
gap predates #1299 and is tracked in #1359.
