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
(the A3 determinism check on `vyhybna.xml` is unchanged), one reader instead of two. Growing
the vocabulary is Goal 9B's work.
