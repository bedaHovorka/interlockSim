# Goal 10 (#532) — What Do We Actually Expect? Critique and Proposed Re-Description

**Date:** 2026-07-06
**Author:** traffic-simulation-expert (TEAM.md role) — lead; agent-architect and
kotlin-tech-lead perspectives consulted per TEAM.md collaboration patterns
**Issue:** [#532 — Goal 10: AI Dispatcher Routing](https://github.com/bedaHovorka/interlockSim/issues/532)
**Related analyses:**
[`ISSUE_591_SCALE_LIVELOCK_ANALYSIS.md`](ISSUE_591_SCALE_LIVELOCK_ANALYSIS.md) (dispatcher livelock root cause),
[`ISSUE_591_GOAL9_RESOLUTION_OPTIONS.md`](ISSUE_591_GOAL9_RESOLUTION_OPTIONS.md) (Goal 9 → Goal 10 seam)
**Scope:** Research only — no implementation. Critique of the current Goal 10
description across its three sources, and a concrete proposal for how to change it,
under the explicit constraint stated by the project owner: **limited resources, small
examples only for now, and the first example is the paramount of all future effort.**

> **⚠️ Owner decisions (2026-07-11, PR #631 review) — supersede the recommendations
> below where they differ:**
>
> 1. **Goal 10's paramount example finally remains the `vyhybna.xml` shunting loop
>    only** — candidate (a) in §5, not the Praha 5-train scenario this document
>    recommended (R3, §5, §6 A2).
> 2. **The Praha hlavní nádraží 5-train scenario becomes the paramount example of
>    Goal 1B** — the follow-up slice that closes out Goal 1's unverified scale bar
>    (#591: 5-train correctness, 20-train performance).
> 3. **Goal 9 as shipped is judged not realistic**; a more realistic **Goal 9B**
>    update will be prepared in the future, **after Goal 1B**.
>
> Read §5's recommendation and §6's Stage-A2 wording as historical analysis;
> the Praha scenario's acceptance role moves from Goal 10 Stage A to Goal 1B.

---

## 1. Executive Summary

Goal 10 is currently described in **three places that do not agree with each other**:

1. `LONG_TERM_GOALS.md` §Goal 10 — an "AI-powered dispatcher" with four success
   criteria, one of which ("matching or exceeding average human dispatcher") is
   unmeasurable as written.
2. The **#532 issue body** — a modest, well-scoped *rule-based* dispatcher
   (`Dispatcher`/`DispatchDecision`/`RuleBasedDispatcher`, three modes, rationale,
   ≤ 500 ms decisions) placed in `core/.../context/`.
3. The **agent-based companion plan** (#532 comment, 2026-06-28 rev. 2026-07-04, plus
   #533) — a 6-month, ~40-sub-issue programme (SP0–SP4): control/kernel refactor,
   Koog agent runtime, local Ollama LLM dispatcher, a Czech-regulation-inspired
   operating-language DSL, and pluggable planners.

The gap between (2) and (3) is roughly **an order of magnitude of effort**, and both
carry stale dependency claims (Goal 9 is now ✅ COMPLETE; Goal 3 is ✅ COMPLETE) and
assumptions invalidated by the #591 findings (a single exhaustive route search on
Praha costs ~2.7 s — the issue's own "decisions within 500 ms" criterion is
unachievable on that fixture with today's search).

**Core recommendations** (detail in §6–§8):

- **R1 — Declare one canonical description** and make the other two reference it.
- **R2 — Re-stage Goal 10 into three explicit stages** (A: deterministic MVP
  dispatcher on one small example; B: modes + explainability; C: pluggable
  planners/LLM as stretch), and gate the LLM/DSL programme (SP1, SP3, SP2b.8/9)
  behind Stage A's exit criteria instead of running it in parallel.
- **R3 — Pick the paramount example now and write it into the goal**: the
  **Praha 5-train scenario** (green in #591) as the Stage-A acceptance scenario,
  with the 20-train scenario as the Stage-C stretch measurement.
  *(Superseded by owner decision 2026-07-11: Goal 10's paramount example stays the
  shunting loop; Praha 5-train becomes Goal 1B's paramount example — see note above.)*
- **R4 — Replace the unmeasurable success criteria** with metric-based ones that the
  existing telemetry can already compute, and re-express the latency budget in terms
  that make sense for a discrete-event engine.
- **R5 — Make determinism a first-class requirement** ("Undeterministic reasoning in
  Goal 10" — review note on PR #631): acceptance runs always use the deterministic
  planner; LLM/learned policies live behind the SP5 seam and are excluded from
  acceptance gates.
- **R6 — Correct the dependency table**: Goals 3 and 9 are complete; the *real*
  remaining blockers are the #591 dispatcher-performance defects and Goal 6
  (performance metrics, still OPEN), which the "human-dispatcher" criterion silently
  requires.

---

## 2. What Goal 10 Currently Promises — Source by Source

### 2.1 `LONG_TERM_GOALS.md` (the strategic register)

> Users can enable an AI-powered dispatcher that automatically routes trains through
> complex junctions, managing switch positions and signal states without manual
> intervention. The AI explains its decisions and allows human override at any time.

Success criteria: autonomous routine routing · manual override · explainable
decisions · **performance matching or exceeding average human dispatcher**.
Estimate 6 months; priority Critical (top project priority since 2026-07-01);
dependency chain Goal 1 → 2 → 3 → 9 → 10 is the only Critical path.

### 2.2 Issue #532 body (the tactical design)

A four-part technical design, all deterministic:

| Part | Content |
|---|---|
| 1 | `Dispatcher` interface, `DispatchDecision` (path + switch/signal commands + rationale), Koin `single` binding, "swappable for future ML dispatcher" |
| 2 | Rule-based engine: shortest path · lowest conflict risk · fewest switch movements; consults `ConflictResolver` (Goal 9); uses Goal 2 pathfinding |
| 3 | `DispatcherMode`: `AUTO` / `SEMI_AUTO` / `MANUAL`, override persistence, GUI indicator |
| 4 | Rationale list per decision, "Why this route?" GUI button, decision logging |

Verification plan: multi-train autonomous routing · override mid-run · semi-auto
approval flow · rationale query · **5-train scenario with decisions ≤ 500 ms**.
Critical files put `Dispatcher.kt` etc. in `core/.../context/` and a
`DispatcherControlPanel` in `desktop-ui`.

### 2.3 The agent-based companion plan (#532 comments + #533)

A much larger construction: SP0 control/kernel seam + sensor/actuator ports; SP1
Koog agent runtime with Koin and a local Ollama LLM; SP2a algorithmic train agents
(decided 2026-07-04: never LLM); SP2b the LLM-driven dispatcher (one per controlled
area, static topology loaded once into context — SP2b.8 #695, rule-based fallback
SP2b.9 #566); SP3 a regulation-inspired operating-language DSL with pluggable
planners (#533 is "crucial to read for all subtasks"); SP4 a `ShuntingLoop`
vertical slice. Follow-up owner comments narrow it: Goals 3/11/12 are prerequisites
only for complex scenarios; Goals 5/6 can come later; the agent lives in a **new
submodule above the simulation core, not GUI-dependent**.

---

## 3. Critique — Internal Contradictions and Drift

### C1. Three descriptions, no declared canon

The issue body describes a rule-based dispatcher; the companion plan describes an
LLM agent system in which the rule engine is merely the *fallback* (SP2b.9). A
contributor picking up #532 cannot tell whether "done" means Part 1–4 of the body or
SP0–SP4 of the plan. The sub-issue numbering (~#533–#578, #695) tracks the plan, not
the body — so the body is *de facto* obsolete but *de jure* the issue's definition.
**Every acceptance argument will be contested until one text is canonical.**

### C2. Stale dependency statuses (both directions)

- The issue body and companion plan treat **Goal 9 (#531) as open** ("fully open,
  not started" — 2026-07-04 decision box). Goal 9 is **✅ COMPLETE** as of
  2026-07-06 (`LONG_TERM_GOALS.md` completion notes, SP1–SP7 shipped), including the
  `AutoConflictResolutionService` — whose KDoc explicitly reserves it as "the
  headless wiring point for Goal 10's deliberative dispatcher". Goal 10's design
  sections still describe *planning* to consult a resolver that now exists with a
  different, richer shape (ranked `ConflictResolution` candidates, preference
  stores) than the body's Part 2 sketch assumes.
- The companion plan's decision 3 names **Goal 3 SP5 (#615) as "the actual remaining
  Goal-10 blocker"**; `LONG_TERM_GOALS.md` now lists Goal 3 as ✅ COMPLETE. Either
  the plan is stale or the register is optimistic — the goal description must say
  which (this document assumes the register; verify #615 before editing #532).
- Conversely, the chain "Goal 1 → 2 → 3 → 9 → 10 all green except 10" **overstates
  readiness**: Goal 1 is marked complete, but its own scale-validation slice (#591)
  is red — the dispatcher-side livelock and the ~2.7 s/search cost documented in
  [`ISSUE_591_SCALE_LIVELOCK_ANALYSIS.md`](ISSUE_591_SCALE_LIVELOCK_ANALYSIS.md) §3–§4.
  Goal 10 inherits a *dispatcher substrate with known open defects* that no
  dependency line currently captures.

### C3. The 500 ms criterion is falsified by measurement

Issue body verification: "5-train scenario; routing decisions issued within 500 ms."
Measured on Praha (#591): **one** exhaustive `findRoutes` enumeration costs
2.3–5.9 s; `DefaultConflictResolver.generateRerouteCandidates` — the Goal 9 API the
body's Part 2 says the dispatcher must consult — scans ~110 InOut pairs of such
searches. Under the current search stack, a conflict-aware decision on a
station-scale fixture costs *minutes*, not 500 ms. The criterion is fine as an
aspiration but must be **conditioned on the route-cache / k-shortest work** (parent
analysis §4) or scoped to the small fixture (`vyhybna.xml`) where it is trivially
met today.

Moreover, for a discrete-event engine the criterion is **conceptually mis-typed**:
kDisco's clock does not advance while a decision is computed, so decision latency
can never cause a *simulation-correctness* failure — it only degrades the
**wall-clock real-time ratio** (exactly the #591 metric). The budget should be
expressed as "dispatcher decisions keep the N-train real-time ratio ≥ 1×", which is
measurable, engine-appropriate, and already has test scaffolding.

### C4. Non-determinism vs. acceptance ("Undeterministic reasoning in Goal 10")

The companion plan makes an LLM the dispatcher's brain. LLM output is
non-deterministic (sampling, model updates, context effects) and a *local* model's
output is additionally hardware/quantization-dependent. Meanwhile every acceptance
instrument this project has — #591's "10 consecutive runs", `@RepeatedTest` race
tests, JVM-vs-native smoke comparison — assumes **reproducible runs**. The Goal 9
preference-learning loop already raised the same issue in miniature (see
[`ISSUE_591_GOAL9_RESOLUTION_OPTIONS.md`](ISSUE_591_GOAL9_RESOLUTION_OPTIONS.md)
§4 Option G). The current Goal 10 description nowhere states which planner is used
for acceptance. This is the single most important sentence missing from the goal:
**acceptance criteria are evaluated only against the deterministic planner; LLM and
learned policies are demonstration/research modes behind the same interface.**

### C5. Scope vs. the stated resource constraint

The owner's constraint: *limited sources, small examples only now, the first example
is the paramount of future effort.* Against that:

- The companion plan is ~40 sub-issues across 6 sections including an agent
  framework adoption (Koog), local LLM ops (Ollama, model evaluation SP3.1), and a
  new DSL (#533 — itself "sizeable" by its own admission). Even its own Gantt puts
  the LLM-run milestone (SP2b.9) behind the *deep* SP3 chain (SP3.2→3.3→3.4→3.6).
- The issue body's Part 3 (three modes + GUI) and Part 4 (GUI button) front-load
  desktop-UI work, although the paramount example — and everything CI can verify —
  is headless. The owner's own follow-up decided the agent must be a **new
  submodule over core, not dependent on GUI**; the body's "Critical Files"
  (`core/.../context/Dispatcher.kt`, `desktop-ui/.../DispatcherControlPanel.kt`)
  contradict that decision and also collide with the module split (`:core`,
  `:desktop-ui`, `:fast-sim`) — dispatcher types in `context/` would put agent
  concerns inside the frozen-context package rather than the agreed new module.
- Success criterion "performance matching or exceeding average human dispatcher"
  requires (a) KPIs — Goal 6, **still OPEN**, explicitly deferred by the owner —
  and (b) a human-dispatcher baseline that this project has no means to collect.
  As written it is not falsifiable, and an unfalsifiable criterion on the top
  priority goal means Goal 10 can never be formally closed.

### C6. The paramount example is not named

Every source gestures at examples (`ShuntingLoop` in SP4, "5-train scenario" in the
body, "multi-train scenario" in verification) but none *fixes* the one scenario that
all future effort builds upon. Given that #591 already established the fixtures and
their behaviour — 5-train Praha **passes**, 20-train Praha **livelocks** — the choice
is essentially made by the evidence; it just needs to be written down (§6).

### C7. Duplication risk with shipped Goal 9 machinery

The body's Part 4 (rationale, decision log) and Part 2 (conflict-aware scoring)
would re-implement what Goal 9 shipped: `ConflictResolution.EstimatedImpact`
(human-readable consequence text), `ConflictResolutionRanker` (deterministic,
documented tie-breaks), `DispatcherPreferenceStore` (persisted decision log with
`AUTO`/`OPERATOR` source), and `ConflictResolutionPanel` (an operator
propose-approve UI — Part 3's SEMI_AUTO flow in embryo). A revised description
should *require* building on these rather than leaving convergence to chance.

---

## 4. What We Should Expect From Goal 10 — a Positive Definition

Stripped to its irreducible core, Goal 10 must deliver exactly four capabilities,
each already anticipated by an existing seam:

| Expectation | Existing seam to build on |
|---|---|
| **E1 — Autonomy**: for a defined scenario class, no human action is needed between "start" and "all trains exited" | `MultiTrainLoop` dispatcher (after #591 P0 fixes); `AutoConflictResolutionService.applyTopRanked` |
| **E2 — Authority handover**: a human can take/return control at any time, with the current authority always visible | `ConflictResolutionPanel` apply-flow (operator chooses among ranked candidates) generalizes to `SEMI_AUTO` |
| **E3 — Explainability**: every decision can answer "why?" from recorded data, after the fact | `EstimatedImpact.description`, ranker scores, `DispatcherPreferenceStore.getChoices()` |
| **E4 — Substitutability**: the decision policy is a swappable component (rule-based today; search/LLM later) with no kernel change | SP0 port seam + SP5 service interface + Koin `single` binding |

Everything else in the three sources — Koog, Ollama, the operating-language DSL,
multi-dispatcher hand-off, train agents — is **means**, not expectation, and belongs
in stretch stages that the goal text should mark as such.

---

## 5. The Paramount Example — Recommendation and Rationale

The owner's constraint makes this the most consequential single decision in the
goal. Candidates:

| Candidate | Pros | Cons |
|---|---|---|
| **(a) `vyhybna.xml` shunting loop, 2–3 trains** (SP4's choice) | Tiny; every search is milliseconds; existing `ShuntingLoop`/`ThreeTrainLoop` tests; zero risk | No real routing choice (≤ ~4 paths) — a dispatcher that only ever has one viable route demonstrates plumbing, not dispatching |
| **(b) Praha 5-train** (#591 `fiveTrainCompleteness`, currently green) | Real station ladder (117 blocks, 50 switches, 11 InOuts); genuine route alternatives; already has a passing acceptance test and fixtures in `commonTest`; directly redeems the #591 investment | Route search costs ~2.7 s until caching lands; test currently passes only because routes are block-disjoint |
| **(c) Praha 20-train** (#591 `twentyTrainStress`) | The true scale bar | Known-livelocked; blocked on P0–P2 of the #591 sequencing — cannot be the *first* example |

**Recommendation: (a) as the Stage-A smoke slice, (b) as the paramount example, (c)
as the Stage-C stretch measurement.**

> **Owner decision (2026-07-11) — supersedes this recommendation:** Goal 10's
> paramount example **remains (a), the shunting loop, only**. The Praha 5-train
> scenario (b) is instead the **paramount example of Goal 1B** (the slice closing
> #591's scale bar), and (c) stays a Goal 1B measurement. The staged-criteria
> structure below still applies to Goal 10, with (a) as its example throughout.

Concretely (as originally recommended):

- The **vertical slice** (SP4) stays on `vyhybna.xml` — it proves the port seam
  closes the loop, cheaply.
- The **paramount example written into Goal 10's success criteria** is:
  *"On `praha-hlavni-nadrazi.xml`, 5 trains with overlapping route options, the
  dispatcher autonomously selects routes such that all 5 exit with no conflict
  events and no operator action, with recorded rationale for every routing
  decision, deterministically across 10 consecutive runs."*
  This is #591's green test **upgraded from scripted `TrainSpec` routes to
  dispatcher-chosen routes** — the smallest example where dispatching (choosing
  *between* alternatives) is actually exercised, and precisely the example future
  effort (20 trains, LLM planner, DSL) scales from.
- The 20-train scenario is *measured and reported* (real-time ratio, exits,
  contention heat-map from Goal 9 telemetry) but is **not** an acceptance gate
  until the #591 P1/P2 work (route caching, incremental reservation) lands.

This choice keeps the example small (5 trains, one fixture, headless), maximally
reuses paid-for assets (#591 fixtures/tests, Goal 9 pipeline), and puts the known
performance debt (route search) on the critical path *visibly* instead of hiding it
behind a 500 ms number.

---

## 6. Proposed Re-Description of Goal 10 (drop-in replacement text)

The following is proposed replacement wording for `LONG_TERM_GOALS.md` §Goal 10 and
the #532 issue body header. (Proposal only — per the research-only scope, this
document does not edit either.)

> ### Goal 10: AI Dispatcher Routing
>
> **User Value:** Users can enable an autonomous dispatcher that routes trains
> through a station, managing switch positions and signal states without manual
> intervention. Every decision is explainable after the fact, a human can take or
> return control at any time, and the decision policy is a swappable component —
> deterministic and rule-based first, with search- or LLM-based planners as later
> drop-ins behind the same interface.
>
> **Staging (acceptance is per-stage, in order):**
>
> **Stage A — Deterministic MVP dispatcher (acceptance-gated):**
> - A1. `Dispatcher` decision seam in the new agent module (above `:core`, no GUI
>   dependency), consuming Goal 9's `AutoConflictResolutionService` and Goal 2
>   pathfinding; enactment of Hold/Reroute decisions on live trains.
> - A2. **Paramount example:** on `praha-hlavni-nadrazi.xml`, 5 trains with
>   overlapping route options, the dispatcher autonomously routes all 5 to exit —
>   no conflict events, no operator action, recorded rationale per decision,
>   **identical outcomes across 10 consecutive runs** (deterministic planner only).
> - A3. Vertical-slice smoke: same seam drives `vyhybna.xml` (SP4) end-to-end.
> - A4. Wall-clock: the 5-train paramount run keeps real-time ratio ≥ 1×.
>   *(Precondition: #591 P0 hot-loop fix and route-result caching; a per-decision
>   wall-clock budget is reported as a metric, not gated, until Goal 6 lands.)*
>
> **Stage B — Authority and explainability:**
> - B1. `DispatcherMode` AUTO / SEMI_AUTO / MANUAL with persistent override;
>   SEMI_AUTO reuses the Goal 9 propose-approve flow (`ConflictResolutionPanel`
>   pattern); mode always visible.
> - B2. "Why this route?" — rationale retrievable for any past decision from the
>   recorded decision log (`DispatcherPreferenceStore` + ranker scores); headless
>   API first, GUI button second.
>
> **Stage C — Pluggable planners (stretch, not acceptance-gated):**
> - C1. Second planner (search-based or local-LLM via the SP1/SP3 programme) behind
>   the same seam; **excluded from acceptance runs** — compared against the
>   deterministic planner on the paramount example using Goal 6 metrics.
> - C2. 20-train Praha measured and reported (real-time ratio, exits, contention
>   heat-map); becomes a gate only after #591 P1/P2 (route caching, incremental
>   reservation) land.
>
> **Dependencies (status as of 2026-07-06):** Goal 1 ✅ (except #591 scale slice —
> open defects are Stage-A preconditions), Goal 2 ✅, Goal 3 ✅ (verify SP5 #615),
> Goal 9 ✅. Goal 6 (metrics, OPEN) required before any "performance vs. baseline"
> claim; Goals 5, 11, 12 needed only for complex scenarios (per owner decision,
> 2026-06-28).
>
> **Non-goals for this goal:** multi-dispatcher hand-off (SP3.10, future); LLM train
> agents (decided 2026-07-04: never); "matching or exceeding average human
> dispatcher" — replaced by planner-vs-planner comparison on recorded metrics, since
> no human baseline is collectable in this project.

### Rationale for the key edits

| Edit | Replaces | Why |
|---|---|---|
| Staging A/B/C with per-stage gates | Flat 4-item success list | Limited resources: Stage A alone is shippable and useful; C is explicitly stretch |
| Paramount example named in the goal | "multi-train scenario" (unspecified) | Owner constraint: this example is the paramount of future effort — it must be in the definition, not folklore |
| Determinism clause (A2, C1) | *(absent)* | Resolves the "undeterministic reasoning" objection structurally: acceptance never depends on an LLM |
| Real-time-ratio budget + metric-only latency | "decisions within 500 ms" | 500 ms is falsified on Praha today (§C3) and mis-typed for a DES engine; ratio is measurable with existing #591 scaffolding |
| Planner-vs-planner comparison | "matching or exceeding average human dispatcher" | Unfalsifiable without a human baseline; comparison on Goal 6 metrics is falsifiable and cheaper |
| New agent module placement | `core/.../context/` critical files | Owner decision (submodule over core, no GUI dependency); keeps agent deps (Koog) out of `:core` and `:fast-sim` native builds |
| Dependency statuses corrected | Goal 9 "open", Goal 3 SP5 "blocker" | Goals 3 and 9 are ✅ COMPLETE in the register; real blockers are #591 defects and Goal 6 |
| Goal 9 reuse made mandatory (A1, B1, B2) | Part 2/4 re-sketches | Avoids re-implementing shipped ranker/rationale/log/panel (§C7) |

---

## 7. Suggested Concrete Edits to Issue #532 (checklist for the owner)

1. **Declare canon:** add a header note that the issue body defines *Stage A/B*
   and the companion plan (+#533) defines *Stage C and the runtime programme*;
   both subordinate to the (updated) `LONG_TERM_GOALS.md` text.
2. **Fix dependencies:** mark Goal 9 (#531) and Goal 3 (#610) complete; add
   "#591 P0 fix + route caching" and "Goal 6 for comparative metrics" as the live
   preconditions; confirm or clear the stale SP5 (#615) blocker claim.
3. **Replace the two broken criteria** (500 ms; human-dispatcher parity) per §6.
4. **Name the paramount example** (Praha 5-train, dispatcher-chosen routes) in the
   Verification Plan, and demote the 20-train run to "measured, not gated".
5. **Move "Critical Files"** out of `core/.../context/` into the agreed new agent
   module; keep GUI files out of Stage A.
6. **Add the determinism clause** to the Verification Plan ("all checks run against
   the rule-based planner; LLM planner excluded from acceptance").
7. **Re-order the sub-issue programme** so SP1 (Koog/Ollama) and the deep SP3 chain
   start only after Stage A2 is green — they are Stage-C work under the limited-
   resources constraint; SP0 (ports/seam) and SP3.2 (vocabulary only, as plain
   Kotlin types) remain early because Stage A needs the seam.

---

## 8. Risks of the Proposed Re-Description (self-critique)

- **Deferring the LLM may disappoint the "AI" in the title.** Mitigation: Stage C
  keeps it in the goal, and Stage A's seam (E4) is designed so the LLM drop-in is
  wiring, not rework; the agent-architect's SP1/SP3 design work can proceed on
  paper in parallel at near-zero implementation cost.
- **The paramount example depends on #591 P0/P1 fixes** landing first — Goal 10
  acquires a dependency on defect work in `sim/` (conservative package, tests
  first). This is honest sequencing, not new scope: the same fixes are prerequisites
  under *any* Goal 10 description, since the dispatcher cannot decide in frozen-clock
  minutes.
- **Dropping the human-parity criterion weakens the marketing claim.** The
  planner-vs-planner formulation is strictly more defensible for a BSc-thesis-scale
  project and can be upgraded later if a baseline (e.g., scripted "human-like"
  policy) is ever built.
- **Stage boundaries invite scope-creep negotiations.** Mitigation: each stage has
  a single named acceptance scenario; anything not needed by that scenario is by
  definition the next stage's work.

---

## 9. Summary

What we expect from Goal 10 is four capabilities — autonomy, authority handover,
explainability, substitutability (§4) — demonstrated first on one small, named,
deterministic, headless example that the rest of the programme scales from. The
current three-source description over-promises (human parity, 500 ms), under-states
real blockers (#591 defects, Goal 6), duplicates shipped Goal 9 machinery, and
leaves the paramount example and the determinism policy undefined. §6 provides a
drop-in replacement text and §7 a concrete edit checklist for #532 that align the
goal with the project's actual resources: **small now, paramount by design, AI when
the seam has earned it.**
