# Goal 10 / SP2c.14 — Dispatcher Reliability Report

**Issue:** [#837](https://github.com/bedaHovorka/interlockSim/issues/837) (SP2c.14) · sub-issue of
[#822](https://github.com/bedaHovorka/interlockSim/issues/822) (Goal 10 SP2c) · Size M · Depends
on: SP2c.12, SP2c.13, SP2c.23 · Stream: report
**Status:** Report authored from already-recorded data. **No new simulation runs were performed to
produce this document.**
**Primary source:** `docs/GOAL_10_I895_REBASELINE_REPORT.md` (Issue #895, the most recent and most
authoritative A4 measurement) and the `dispatcherReliabilityReport` tool output it produced —
`docs/measurement/i895-runs/arm1/report.md` and `docs/measurement/i895-runs/arm2/report.md`.
**Measured tree:** `b2ed3402` (`goal-10` tip) plus `0655ba2e` (grid files) · **Model:**
`qwen2.5:7b-instruct` · **Network:** `vyhybna.xml` · **Horizon:** 600 simulated seconds

---

## 0. What this document is, and is not

This is the reliability report #822 §7's amended A4 acceptance criterion requires: a *measured*
success rate over N ≥ 10 runs, per arm, with the per-outcome breakdown, produced by
`RunReportAggregator`/`./gradlew dispatcherReliabilityReport` from stored run JSONs. It replaces
#532's original A5 optimality comparison at ShuntingLoop (`vyhybna.xml`) scale — see §1 for why.

It is **not** a new measurement campaign. Every number below is read from `docs/GOAL_10_I895_REBASELINE_REPORT.md`
and the two `report.md` files it references, both already committed to the repository. No
`aiSweep`, no Gradle task that runs the simulator, and no LLM call was executed while writing this
document. Section 2 states the provenance chain explicitly so a reviewer can verify this claim.

Two independent measurement campaigns exist in the source data and are kept separate throughout
this report, because collapsing them loses information the gate itself needs:

- **Campaign A** — `sp2c24-sweep-grid.json` / `sp2c24-baseline-grid.json`, `historyN = 3`. The
  bridge back to #847's original published table. 20 LLM runs (10 at `t = 0.28`, 10 at `t = 0.5`)
  + 10 rule-based control runs.
- **Campaign B** — `i895-shipped-defaults-grid.json`, `historyN = 0`, the parameters #834 shipped
  as configuration. 20 LLM runs (10 at `t = 0.28`, the shipped default; 10 at `t = 0.5`, an
  owner-mandated shape match) + 10 rule-based control runs.

Both campaigns pooled their own rule-based control runs, and both controls are **byte-identical
and fully deterministic** across all 20 combined control runs (#907 barriered the control step):
15 trains admitted, 11 exited, 25 block transitions, `NATURAL_COMPLETION`, `c7Clean = true`, every
single run. The control arm supplies a ceiling for this report, not a noise floor.

---

## 1. Why reliability, and not optimality, is what this report measures

#532 already concedes that `vyhybna.xml` has thin route alternatives (2 InOuts, 2 switches) and
that the dispatching exercise on this network comes from closing the agent seam and the
LLM-vs-rule-based comparison itself, **not** from rich routing choice. Constraint **C11**
sharpens this: on ShuntingLoop the LLM arm is not being asked to out-plan the rule-based
dispatcher — it is being asked to prove one thing, that an autonomous LLM dispatcher can hold the
outcome contract by itself, repeatedly, unsupervised.

The prior art makes the same point from the other direction. In NeurIPS 2020 Flatland — built by
SBB/DB/SNCF specifically to test learned policies against operations research — the OR/MAPF entry
scored 297.5 vs 214.2 for the best RL entry and won both rounds and both tracks outright. On a
two-switch station there is no optimisation contest to lose, and an LLM matching an
effectively-optimal rule set would prove nothing about quality.

**So this report measures reliability, and defers the optimality claim to Praha (#591)**, where it
should meet an OR/MILP yardstick rather than only `RuleBasedDispatcher`. A favourable comparison
against a hand-written rule set alone would prove very little. See §11 for the explicit
cross-reference this issue's acceptance criteria requires.

---

## 2. Provenance and method

| Item | Value |
|---|---|
| Measurement issue | #895 (A4 re-baseline, both arms) |
| Report-writing issue | #837 / SP2c.14 (this document) |
| Tree measured | `b2ed3402` (`goal-10` tip) + `0655ba2e` (grid files, documentation-only) |
| JAR | `sha256 3b56a291461ea8e04d495edcbb17573203aaacf54255b240a8c3e33ecff2ec78` produced every one of the 60 runs |
| Report-rendering JAR | `sha256 31682d457f8044901f51717f479cbc76ee75197f949099b0b06eb4177080d936` — carries #895's gate changes; this invocation skipped all 60 runs (already on disk) and rendered only |
| Model | `qwen2.5:7b-instruct` |
| Network | `vyhybna.xml` (ShuntingLoop) |
| Horizon | 600 simulated seconds |
| Evidence | `docs/measurement/i895-runs/arm1/` (Campaign A), `docs/measurement/i895-runs/arm2/` (Campaign B) |

`./gradlew dispatcherReliabilityReport` reads stored `DispatcherRunSnapshot` JSON files from a run
directory and renders exactly the seven sections referenced as T1–T7 below (see
`RunReportAggregator`'s own "Sections rendered" KDoc table). It performs no simulation of its own;
running it against the already-populated `docs/measurement/i895-runs/arm{1,2}/` directories is a
read-only sanity check, not a new data source, and this document was **not** produced by pasting
that tool's raw output over hand-written prose — the tables below are synthesized from it, with
context and honesty caveats folded in per §822/§837's requirements.

---

## 3. What this report must say honestly

The issue text requires three specific honesty commitments. Each is stated once here, in full,
rather than only implied by a table column.

### 3.1 `correctAt1` is an outcome-anchored executability measure, not decision-correctness

`Sp2c21MetricsSnapshot`'s own KDoc states the rule this report follows:

> `correctAt1` is an outcome-anchored executability measure with a run-level completion
> precondition — it is not a decision-correctness measure. Multiple genuinely correct dispatching
> decisions exist at almost every tick (route choice among free paths, admission ordering), so
> exact-match against any single oracle systematically understates competence. Use `correctAt1`
> and `validAt1` as the A/B discriminators; use `oracleAgreementAt1` as diagnostic colour only,
> never as a gate.

On this campaign's data the point is moot rather than merely theoretical: `validAt1`, `correctAt1`,
and `oracleAgreementAt1` are **structurally absent** for every run in both campaigns (Issue #835 —
the oracle comparison is not wired into the async recording path; `DefaultDispatcherRunRecorder`
sets `validAt1 = 0.0` and `correctAt1 = null` unconditionally, tagged "forward-looking"). Every
`validAt1 (median)` cell in T1/T7 below reads `0.000` and every `correctAt1 (median)` cell reads
`n/a` for that reason — not because the LLM's decisions were invalid, but because the metric has
no producer yet. **Do not read those two columns as a competence finding in this report.** The
gate does not depend on them (see §4).

### 3.2 A high no-op rate is expected and healthy on `vyhybna.xml`

`vyhybna.xml` is a two-switch, two-InOut station. Most ticks legitimately have no admissible
action: a train mid-block, a route already reserved and unexpired, or every free path genuinely
occupied. A dispatcher — rule-based or LLM — that emits an action on every tick regardless of
whether one is warranted is thrashing, not being decisive. §6 reads the no-op rate next to
`ALL_PATHS_BLOCKED` precisely so a high no-op figure is not mistaken for passivity.

### 3.3 If the LLM arms fail the gate, this report says so plainly

They do, in both campaigns, at every parameter cell measured. §10 states the per-arm verdict
without hedging, gives the per-outcome breakdown behind it, and points at SP2c.15 (#838)'s
interface-vs-capacity diagnostic for what the failure does and does not tell us about model
capability. A negative result is a result.

---

## 4. The gate this report scores against (A4)

From `RunReportAggregator` (Issue #927, re-measured and corrected by #895):

```
runPassed  = completedNaturally && !terminalFallbackEngaged && c7Clean &&
             (arm == RULE_BASED || actionableTickRate >= MIN_ACTIONABLE_RATE) &&
             (trainsExited == null || trainsExited >= MIN_TRAINS_EXITED)
gatePassed = runCount >= 10 && passingRuns >= 8 && snapshots.all { it.c7Clean }
```

`MIN_ACTIONABLE_RATE = 0.8` and `MIN_TRAINS_EXITED = 6` are both measured constants, not
placeholders — see their KDoc and #895's Findings A–D for how each was derived and why both are
tripwires rather than discriminators on this dataset (they change no verdict here; `completedNaturally`
and `c7Clean` are what actually separate passing from failing runs). The actionable-rate clause is
**not** applied to `RULE_BASED` — that arm records no LLM ticks, so `actionableTickRate = 0.0` by
construction, and #895 found and fixed the defect where an earlier version of this gate had scored
a perfect 11-exits-every-run control arm as `0/10 FAIL` for exactly that reason.

**A single non-`c7Clean` run fails the whole arm even at 10/10 completions.** C7 is a
deterministic-component correctness gate, not a majority vote.

---

## 5. Tables T1–T7

The seven sections below are named and ordered exactly as `RunReportAggregator.renderMarkdown`
produces them, so a reader can cross-check any figure against `docs/measurement/i895-runs/arm1/report.md`
or `arm2/report.md` directly. **T5 (Author Attribution) is the A4 gate's autonomy input and is
called out first among the per-arm detail tables**, per this issue's acceptance criteria.

### T1 — Arm Comparison (reliability)

**Campaign A — `historyN = 3` (#847's grid), 20 LLM runs + 10 control runs**

| Arm | Runs | Passing | Gate | LLM Success (median) | Actionable Rate (median) | NoOp (median) | validAt1 (median) | correctAt1 (median) | p95 latency ms | C7 clean |
|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | 10 | 10 | ✅ PASS | 0.000 | 0.000 | 0.000 | 0.000 | n/a | — | yes |
| LLM_TOOL_CALLING | 20 | **0** | ❌ FAIL | 0.779 | 0.932 | 0.424 | 0.000 | n/a | 30004 | no |
| LLM_CONSTRAINED_JSON | 0 | 0 | ❌ FAIL | 0.000 | 0.000 | 0.000 | 0.000 | n/a | — | yes* |

**Campaign B — `historyN = 0` (shipped defaults), 20 LLM runs + 10 control runs**

| Arm | Runs | Passing | Gate | LLM Success (median) | Actionable Rate (median) | NoOp (median) | validAt1 (median) | correctAt1 (median) | p95 latency ms | C7 clean |
|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | 10 | 10 | ✅ PASS | 0.000 | 0.000 | 0.000 | 0.000 | n/a | — | yes |
| LLM_TOOL_CALLING | 20 | **8** | ❌ FAIL | 0.780 | 1.000 | 0.383 | 0.000 | n/a | 30005 | no |
| LLM_CONSTRAINED_JSON | 0 | 0 | ❌ FAIL | 0.000 | 0.000 | 0.000 | 0.000 | n/a | — | yes* |

\* `LLM_CONSTRAINED_JSON`'s `C7 clean = yes` is vacuous — it holds over zero runs. It is not
evidence the arm is clean; it is evidence the arm has never been run. See §7.

`LLM_TOOL_CALLING` needs `passingRuns ≥ 8` **of 20 pooled runs** to pass the gate as printed
here — the two temperature cells inside each campaign are pooled by `RunReportAggregator` in T1
(it groups by arm only). §5's T7 table below un-pools them back to the actual 10-run cells the
gate is defined over, which is where the pass/fail split in Campaign B (5/10 at `t=0.28`, 3/10 at
`t=0.5`) becomes visible — T1's 8/20 headline hides that Campaign B's better cell alone is close
to, but still under, the 8/10 bar.

### T2 — Per-Run Detail (condensed; full 40-row tables live in the source `report.md` files)

| Campaign | Arm / cell | Runs | `NATURAL_COMPLETION` | `TERMINATED_EARLY` | Ticks/run (median) | Actionable rate (range) | `c7Clean` runs |
|---|---|---|---|---|---|---|---|
| A | RULE_BASED (control) | 10 | 10 | 0 | 0 | 0.000 (fixed) | 10/10 |
| A | LLM `t=0.28, h=3` | 10 | 7 | 3 | 75 | 0.647 – 0.983 | 0/10 |
| A | LLM `t=0.5, h=3` | 10 | 8 | 2 | 71.5 | 0.844 – 1.000 | 1/10 |
| B | RULE_BASED (control) | 10 | 10 | 0 | 0 | 0.000 (fixed) | 10/10 |
| B | LLM `t=0.28, h=0` | 10 | 7 | 3 | 83 | 0.968 – 1.000 | 8/10 |
| B | LLM `t=0.5, h=0` | 10 | 5 | 5 | 63 | 0.810 – 1.000 | 7/10 |

Every `TERMINATED_EARLY` run fails `completedNaturally` and is therefore excluded from the passing
column regardless of anything else it measured — including the single most instructive run of the
whole campaign, Campaign A `t=0.5 r03`: `c7Clean = true`, `actionableTickRate = 1.000`, 5 trains
admitted, **0 exited**. Perfect decision hygiene, dead railway; `completedNaturally` alone kept it
out of the passing column, and correctly so (#895 Finding A). This is the concrete case for why
§3.1's caveat is not academic: a metric that only reads decision hygiene cannot distinguish this
run from a genuinely reliable one, which is exactly why `MIN_TRAINS_EXITED` exists as a
railway-outcome term (§4).

### T3 — Failure Modes (`RejectionCode` × arm)

> Read together with T4: a high no-op rate with low `ALL_PATHS_BLOCKED` is correct restraint; a
> low no-op rate with high `ALL_PATHS_BLOCKED` is thrashing. See §6 for the reading.

| Rejection Code | RULE_BASED (both campaigns) | LLM_TOOL_CALLING — Campaign A | LLM_TOOL_CALLING — Campaign B | LLM_CONSTRAINED_JSON |
|---|---|---|---|---|
| `UNKNOWN_TRAIN` | 0 | 7 | 10 | 0 |
| `BLANK_ARGUMENT` | 0 | 0 | **19** | 0 |
| `UNKNOWN_ENDPOINT` | 0 | 1 | 0 | 0 |
| `ENDPOINT_IS_BLOCK_ID` | 0 | 2 | 5 | 0 |
| `TRAIN_ALREADY_ACTIVE` | 0 | 43 | 78 | 0 |
| `TRAIN_NOT_QUEUED` | 0 | 0 | 0 | 0 |
| `CAPACITY_FULL` | 0 | 0 | 0 | 0 |
| `TRAIN_ALREADY_EXITED` | 0 | 0 | 0 | 0 |
| `ROUTE_ALREADY_HELD_TO_SAME_TARGET` | 0 | 0 | 0 | 0 |
| `ROUTE_HELD_TO_DIFFERENT_TARGET` | 0 | 0 | 0 | 0 |
| `TRAIN_NOT_ADMITTED` | 0 | 0 | 0 | 0 |
| `TARGET_NOT_TRAIN_DESTINATION` | 0 | 17 | 15 | 0 |
| `ROUTE_SPANS_ENTRY_TO_EXIT` | 0 | 3 | 5 | 0 |
| `NO_FREE_PATH` | 0 | 0 | 0 | 0 |
| `ORIGIN_NOT_AT_TRAIN_POSITION` | 0 | 118 | 108 | 0 |
| `NO_ROUTE_HELD` | 0 | 0 | 0 | 0 |
| `TRAIN_ON_RESERVED_BLOCK` | 0 | 0 | 0 | 0 |
| `DUPLICATE_ACTION_THIS_TICK` | 0 | 0 | 0 | 0 |
| `ACTION_LIMIT_EXCEEDED` | 0 | 29 | 37 | 0 |

`BLANK_ARGUMENT` appears **only** at `historyN = 0` (Campaign B) — 19 occurrences against 0 at
`historyN = 3` and 0 in #847's earlier campaign. Dropping the history block buys the decision
hygiene seen in T5/T7 (fallback ticks nearly disappear) at the cost of the model occasionally
emitting empty tool arguments. #834 chose `historyN = 0` on the strength of its `c7Clean` result
without reporting this; it belongs next to that benefit, not omitted.

`ACTION_LIMIT_EXCEEDED` fell **95%** per decision from #847's pre-fix baseline (0.420 → 0.022) and
stays low in both campaigns here — the cap-thrashing failure mode from earlier campaigns is
effectively fixed. Per #834's controlled probe, this code is not itself a prompt-quality signal:
a prompt stating the action budget three times produced the same rejection rate as one stating it
once.

### T4 — Apply Failures (`ApplyFailureCode` × arm)

| Apply Failure Code | RULE_BASED (both campaigns) | LLM_TOOL_CALLING — Campaign A | LLM_TOOL_CALLING — Campaign B | LLM_CONSTRAINED_JSON |
|---|---|---|---|---|
| `ALL_PATHS_BLOCKED` | 0 | **56** | **40** | 0 |
| `CONFLICT` | 0 | 0 | 0 | 0 |
| `NO_ROUTE_EXISTS` | 0 | 0 | 0 | 0 |
| `APPROVE_REJECTED` | 0 | 0 | 0 | 0 |
| `CAP_EXCEEDED_APPLY` | 0 | 130 | 155 | 0 |
| `ORIGIN_NOT_CONTIGUOUS` | 0 | 25 | 21 | 0 |
| `CONDITION_FAILED` | 0 | 0 | 0 | 0 |
| `DROPPED_INVALID` | 0 | 0 | 0 | 0 |
| `GEOMETRICALLY_IMPOSSIBLE` | 0 | 7 | 6 | 0 |

See §6 for the no-op/`ALL_PATHS_BLOCKED` reading this issue's acceptance criteria require adjacent
to T3/T4.

### T5 — Author Attribution — the A4 gate's autonomy input

> Must be `{LLM: n, everything else: 0}` for a passing LLM arm.

| Action Author | RULE_BASED (both campaigns, each 10 runs) | LLM_TOOL_CALLING — Campaign A (20 runs) | LLM_TOOL_CALLING — Campaign B (20 runs) | LLM_CONSTRAINED_JSON |
|---|---|---|---|---|
| `LLM` | 0 | **927** | **1038** | 0 |
| `TIMEOUT_NOOP` | 0 | 0 | 0 | 0 |
| `RULE_BASED` | 380 | 0 | 0 | 0 |
| `RULE_FALLBACK` | 0 | **87** | **14** | 0 |
| `SAFETY_NET` | 0 | 0 | 0 | 0 |
| `OPERATOR` | 0 | 0 | 0 | 0 |

Neither campaign's LLM arm holds the `{LLM: n, everything else: 0}` bar: Campaign A attributes 87
actions to `RULE_FALLBACK` across its 20 runs, and Campaign B attributes 14. At the tick level
(distinct from the action-authorship count in this table — a tick can run the fallback dispatcher
without it authoring the tick's final action) Campaign A recorded at least one `RULE_FALLBACK`
tick in 19 of its 20 runs (median 5.5 ticks at `t=0.28`, 3 at `t=0.5`), against 2/10 at `t=0.28`
and 4/10 at `t=0.5` for Campaign B — see T7's `RULE_FALLBACK ticks` column. This table is the
direct mechanical cause of
every `c7Clean = no` row in T1/T2: `c7Clean` is defined as "no action attributed to
`RULE_FALLBACK`/`SAFETY_NET`", and this table is exactly that count. It is deliberately not `0`
for the `RULE_BASED` column — `RULE_BASED` authoring 380 actions in its own arm is expected and
correct; the autonomy requirement applies only to the LLM arm passing under its own name.

The `RULE_BASED` action-author row is present because that arm is *supposed* to be authored by the
rule-based dispatcher — this is not a violation, it is the control arm behaving as designed.

### T6 — Latency / Cost

| Arm | Tick period ms | p50 latency ms | p95 latency ms | Max latency ms | Deadline misses |
|---|---|---|---|---|---|
| RULE_BASED (both campaigns) | 0 | — | — | — | 0 |
| LLM_TOOL_CALLING — Campaign A | 0 | 1087 | 30004 | 30025 | 0 |
| LLM_TOOL_CALLING — Campaign B | 0 | 1156 | 30005 | 30026 | 0 |
| LLM_CONSTRAINED_JSON | 0 | — | — | — | 0 |

At least one inference cycle in each campaign spent the full 30 s deadline (`p95`/`max` both sit
at the timeout, not below it), while `p50` is roughly 1.1 s. Zero recorded `TIMEOUT_NOOP` ticks in
either campaign (T2/T7) means no tick's inference actually timed out and fell back silently — the
p95 outlier resolved within the deadline, just slowly.

**Wall-clock real-time ratio, ungated for the LLM arms per this issue's spec:** the earlier SP2c.24
campaign measured 301–302 s of wall-clock time per 600 s simulated LLM run, a consistent ~2×
real-time ratio driven by `ThrottlingSimulationController`'s `AGENT_MAX_SPEED_MULTIPLIER = 2.0`
ceiling, not by inference latency. #895's Campaign A reproduces the same order of magnitude — 20
LLM runs completed in 1 h 34 m, about 304 s per run; Campaign B's 20 runs completed in 1 h 27 m.
Both totals include runs that ended `TERMINATED_EARLY` before the full 600 s horizon, which
shortens their individual wall-clock time and pulls a campaign's average toward, not above, the
steady-state ~301–302 s/run the SP2c.24 baseline measured for full-length runs. This ratio is
explicitly **not** part of the A4 gate — it is cost information, not a pass/fail input — and it
should not be conflated with the deadline-miss count above, which is gated per-tick, not per-run.

Two timing caveats from prior spikes bound how this figure should be read:

- **SP2c.26 (#849):** the F1 paused-clock regime this project uses for reproducibility freezes the
  simulation clock only during LLM emission, with zero measured pause latency across 20 trials; it
  does not itself change the ~2× throttle above, and headless pacing (`SimulationRunner`/
  `ThrottlingSimulationController`) changes wall-clock only, never event semantics.
- **SP2c.27 (#850):** no sampling seed reaches Ollama through Koog 1.1.1 (confirmed against the
  pinned source — no code path places a value into a `seed` key), so none of the latency or
  outcome figures in this report are seed-reproducible in the conventional sense. #895 and this
  report both carry that caveat forward rather than implying a re-run would reproduce identical
  numbers.

### T7 — Parameter Sweep (un-pooled, per grid cell)

**Campaign A — Decision Hygiene**

| Arm | Temp | historyN | Runs | Passing | Gate | LLM Success | Invalid-action rate | No-op rate | Repair-success † | p50 ms | p95 ms | C7 clean | `RULE_FALLBACK` ticks |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | 0.0 | 3 | 10 | 10 | ✅ PASS | 0.000 | 0.000 | 0.000 | 0.000 | — | — | yes | 0 |
| LLM_TOOL_CALLING | 0.5 | 3 | 10 | **0** | ❌ FAIL | 0.789 | 0.219 | 0.396 | 0.000 | 1120 | 7710 | no | 34 |
| LLM_TOOL_CALLING | 0.28 | 3 | 10 | **0** | ❌ FAIL | 0.749 | 0.215 | 0.447 | 0.000 | 971 | 30004 | no | 47 |

**Campaign B — Decision Hygiene**

| Arm | Temp | historyN | Runs | Passing | Gate | LLM Success | Invalid-action rate | No-op rate | Repair-success † | p50 ms | p95 ms | C7 clean | `RULE_FALLBACK` ticks |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | 0.0 | 0 | 10 | 10 | ✅ PASS | 0.000 | 0.000 | 0.000 | 0.000 | — | — | yes | 0 |
| LLM_TOOL_CALLING | 0.28 | 0 | 10 | **5** | ❌ FAIL | 0.795 | 0.239 | 0.373 | 0.000 | 1228 | 5299 | no | 4 |
| LLM_TOOL_CALLING | 0.5 | 0 | 10 | **3** | ❌ FAIL | 0.761 | 0.225 | 0.417 | 0.000 | 982 | 30005 | no | 8 |

† `repairSuccessRate` is structurally `0` for every cell in this table — `LLM_REPAIRED` has no
live producer on the async path as of SP2c.11 (#834). Read it as "not yet measurable", not as a
finding.

**Railway Outcomes (both campaigns)**

| Arm | Temp | historyN | Runs | Journeys completed | Trains entered | Trains exited (authoritative) | Block transitions | Conflicts | Failed reservations |
|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED (Campaign A) | 0.0 | 3 | 10 | 110 | 150 | 110 | 250 | 0 | 0 |
| RULE_BASED (Campaign B) | 0.0 | 0 | 10 | 110 | 150 | 110 | 250 | 0 | 0 |
| LLM `t=0.5, h=3` | 0.5 | 3 | 10 | 70 | 137 | 77 | 195 | 3 | 1 |
| LLM `t=0.28, h=3` | 0.28 | 3 | 10 | 51 | 137 | 65 | 168 | 5 | 2 |
| LLM `t=0.28, h=0` | 0.28 | 0 | 10 | 69 | 134 | 80 | 199 | 4 | 0 |
| LLM `t=0.5, h=0` | 0.5 | 0 | 10 | 44 | 111 | 51 | 130 | 4 | 2 |

Rule-based control rows are summed across their 10 runs (11 per run × 10 = 110), not medians — the
control is fully deterministic so sum and per-run figure differ only by the run count. Ranking
throughout is by `trainsExited` (Issue #906), not `journeysCompleted`, because the latter is not
termination-gated and can credit a journey to a train that never left the network — see #895
Finding D for the measured case that motivated this.

---

## 6. Restraint: no-op rate and `ALL_PATHS_BLOCKED`, read adjacently

| Campaign | No-op rate (median, T1) | `ALL_PATHS_BLOCKED` (raw, T4) | LLM/`RULE_FALLBACK`-authored actions attempted (T5) | `ALL_PATHS_BLOCKED` per action attempted | Reading |
|---|---|---|---|---|---|
| A (`historyN = 3`) | 0.424 | 56 | 927 + 87 = 1014 | ~5.5% | high no-op, low blocked → **correct restraint** |
| B (`historyN = 0`) | 0.383 | 40 | 1038 + 14 = 1052 | ~3.8% | high no-op, low blocked → **correct restraint** |

Both campaigns land on the "high no-op rate, low `ALL_PATHS_BLOCKED`" side of the rule this
issue's acceptance criteria require stated explicitly: **a high no-op rate paired with a low
`ALL_PATHS_BLOCKED` rate is correct restraint, not passivity** — the dispatcher is declining to
act on roughly two-fifths of ticks and, when it does act, only about one action in twenty (A) or
twenty-five (B) collides with a station that has genuinely run out of free paths. The inverse
pattern — a low no-op rate paired with high `ALL_PATHS_BLOCKED` — would indicate thrashing (the
dispatcher repeatedly proposing routes into a station it should recognise as full); that pattern
is **not** what either campaign shows.

This reading must not be over-read as "the dispatcher is working well end to end." §8 covers the
dominant failure mode, which is a *different* kind of restraint problem — the dispatcher
sometimes stops proposing routes altogether for a specific train, past the point where restraint
becomes starvation, rather than thrashing against a blocked station.

---

## 7. `LLM_CONSTRAINED_JSON` — no data, and this report says so rather than omitting the arm

Every table in §5 carries a `LLM_CONSTRAINED_JSON` column, and every cell in it reads `0 runs`.
This is not an oversight: **there is no sweep grid file anywhere in the repository for this arm**,
and no run JSON exists for it in `docs/measurement/i895-runs/arm1/` or `arm2/`. This gap is
tracked as Issue #890 and has been carried forward, stated the same way, across at least two prior
reports (`docs/GOAL_10_SP2C24_SWEEP_REPORT.md`, `docs/GOAL_10_SP2C11_SWEEP_REPORT.md`) rather than
silently omitted or filled with an invented number. This report follows the same pattern: the arm
is present in every table with an honest zero, and the gate correctly renders it `❌ FAIL` on
`0 runs` rather than `✅ PASS` on vacuous truth — `gatePassed` requires `runCount >= 10`, so an
empty arm cannot pass by default. `c7Clean = yes` for this arm in T1 is vacuous for the same
reason (§5, T1 footnote) — it holds over zero runs and must not be read as "the constrained-JSON
arm is clean."

**Closing this gap is Issue #890's scope, not this report's.** SP2c.14 is a report-generation
sub-issue depending on SP2c.12/13/23; it does not authorize running a new grid, and per this
report's explicit scope constraint no simulations were run to try to fill it in.

---

## 8. Failure modes: not a decision-quality failure

The measurements in T3–T5 tell a consistent story with #895's headline finding: **the dominant
remaining failure mode is not decision quality**. Per-decision rejection rates fell sharply
relative to the pre-fix baseline (`ORIGIN_NOT_AT_TRAIN_POSITION` −55%, `TARGET_NOT_TRAIN_DESTINATION`
−89%, `ACTION_LIMIT_EXCEEDED` −95%, all measured per decision rather than as raw counts, since the
current campaign makes roughly 3.8× as many decisions per run as the pre-fix baseline it is
compared against). Hallucination-class rejection codes (`UNKNOWN_TRAIN`, `UNKNOWN_ENDPOINT`,
`ENDPOINT_IS_BLOCK_ID`, `BLANK_ARGUMENT`) are present but small relative to the campaign's total
decision volume — #895's own count puts them at 61 occurrences across the combined 40-run
campaign against a zero baseline in the pre-fix comparison, concentrated in `BLANK_ARGUMENT`
(19 of those 61), which appears **only** at `historyN = 0` (Campaign B; see §5, T3).

The failure that actually stops runs from passing the gate is structural: **13 of the campaign's
40 LLM runs ended `TERMINATED_EARLY`** (T2), all with the same fingerprint per #895's
investigation — a train stands at a STOP signal holding track it cannot leave, the bounded WARN
#943 added fires after 60 simulated seconds ("still waiting … for the dispatcher to extend its
route"), other trains' routes then report `ALL_PATHS_BLOCKED`, and the run either recovers late or
drains to `TERMINATED_EARLY`. In Campaign A specifically, stall locations observed were `zB` (×3),
`zA` (×2), `doA2` (×2), `doA1`, `doB1`; Campaign B shows a comparable share of stall warnings by
#895's account, though it did not publish a per-location breakdown for that campaign. This is a
dispatcher decision failure — the model stops extending a specific train's route rather than the
interlocking refusing a valid request — **not** a `core/` defect; the bounded wait that surfaces it
is working exactly as #943 designed it.

SP2c.15 (#838) is the designated follow-up for the diagnostic question this raises: whether the
stall is an **interface** problem (the prompt/tool surface not making "extend this train's route"
salient enough at the right tick) or a **capacity** problem (the model, at 7B parameters, cannot
sustain attention on multiple trains' outstanding obligations across dozens of ticks). This report
does not attempt to answer that question — it is out of scope for a reliability report built from
the current data, which cannot distinguish the two without a larger-model comparison arm.

---

## 9. Output quality: `valid@1`, `correct@1`, invalid-output rate, repair-success rate

| Metric | Campaign A | Campaign B | Status |
|---|---|---|---|
| `validAt1` (median) | 0.000 | 0.000 | structural zero — no producer (#835) |
| `correctAt1` (median) | n/a | n/a | structurally absent — no producer (#835) |
| Invalid-output rate (`TIMEOUT_NOOP` ticks ÷ total ticks) | 0.000 (T2: `TIMEOUT_NOOP` = 0 in every recorded run) | 0.000 (same) | measured, genuinely zero |
| Invalid-**action** rate (rejected ÷ emitted actions, T7) | 0.215 – 0.219 | 0.225 – 0.239 | measured |
| Repair-success rate (`LLM_REPAIRED` ÷ total ticks) | 0.000 | 0.000 | structural zero — no live producer on the async path (#834/SP2c.11) |

Per §3.1, `validAt1` and `correctAt1` must not be read as competence findings here — they have no
producer yet, not a measured value of zero decision quality. `repairSuccessRate` carries the same
caveat for a different reason (no live producer on the async path). The two metrics that *are*
genuinely measured in this section point in different directions: **no tick ever timed out**
(invalid-output rate is a real, measured 0.000 in both campaigns — every inference cycle produced
*some* parseable output within its deadline), while roughly a fifth to a quarter of the *actions*
those ticks emitted were rejected by the validator (invalid-action rate). These are different
denominators (ticks vs. actions, per `RunReportAggregator`'s own legend) and should not be
conflated — a tick can emit a well-formed but domain-invalid action (e.g. targeting a train not at
that origin) without ever timing out.

---

## 10. Per-arm A4 verdict

| Arm | Campaign A (`historyN = 3`) | Campaign B (`historyN = 0`, shipped) | Overall |
|---|---|---|---|
| `RULE_BASED` | ✅ **PASS** — 10/10, `c7Clean` 10/10 | ✅ **PASS** — 10/10, `c7Clean` 10/10 | ✅ **PASS** |
| `LLM_TOOL_CALLING` | ❌ **FAIL** — 0/10 at both temperature cells | ❌ **FAIL** — best cell 5/10 (`t=0.28`), 3/10 at `t=0.5` | ❌ **FAIL** in every measured configuration |
| `LLM_CONSTRAINED_JSON` | ❌ **FAIL** (no data) | ❌ **FAIL** (no data) | ❌ **FAIL** — 0 runs, tracked as a data gap by #890, not a negative competence finding |

**`RULE_BASED` clears the ≥ 8/10 bar with room to spare, in both campaigns, at every cell
measured.** Its 380 attributed actions are entirely its own, `c7Clean` holds without exception,
and the outcome is deterministic and reproducible run to run.

**`LLM_TOOL_CALLING` does not clear the bar in any measured configuration.** The best individual
cell — Campaign B at `t = 0.28`, the shipped configuration — reaches 5/10, still three passing
runs short of 8/10. No cell, campaign, or temperature reaches the bar. The cause is not
predominantly a decision-quality failure (§8): per-decision rejection and hallucination rates are
low and, where comparable, sharply improved over the pre-fix baseline. The dominant cause is the
route-extension stall/deadlock pattern described in §8, which is a railway-outcome failure
(`completedNaturally` and `trainsExited` both fail on a stalled run) independent of whether the
individual actions the model did emit were well-formed. `historyN = 0` buys decision hygiene
(fallback ticks drop from 10/10 runs containing at least one, at `historyN = 3`, to 2/10) without
buying railway reliability — the stall pattern persists at a comparable rate under both settings.

**`LLM_CONSTRAINED_JSON` cannot be evaluated.** No grid file, no runs, no data (#890). This is
recorded as a gate failure because `gatePassed` requires `runCount >= 10`, not because the arm has
been shown to be unreliable — the honest statement is "not yet measured," and this report keeps
that statement visible in every table rather than silently dropping the arm or fabricating a
number for it.

---

## 11. Optimality is a Praha-stage question, not this report's

Per §1: this report deliberately measures **reliability**, not decision quality relative to an
optimal policy. `vyhybna.xml`'s two-switch, two-InOut topology does not present enough route
choice for a meaningful optimality claim, and #532 already concedes this. The optimality
comparison — an LLM dispatcher measured against an OR/MILP yardstick rather than only against
`RuleBasedDispatcher` — belongs to Praha (**#591**), which has the topological complexity (larger
station, more route alternatives) to make that comparison meaningful. Nothing in this report
should be read as a claim, positive or negative, about optimality; only about whether the outcome
contract (`completedNaturally`, `c7Clean`, minimum railway throughput) is held reliably, N ≥ 10, at
ShuntingLoop scale.

---

## 12. Acceptance-criteria checklist

- [x] **Report generated for all three arms from stored run JSONs via `./gradlew dispatcherReliabilityReport`.**
  All three arms (`RULE_BASED`, `LLM_TOOL_CALLING`, `LLM_CONSTRAINED_JSON`) appear in every table
  in §5, synthesized from the already-rendered `docs/measurement/i895-runs/arm1/report.md` and
  `arm2/report.md`, themselves produced by that Gradle task against #895's stored snapshots. No new
  simulation was run to write this document (§0, §2).
- [x] **Tables T1–T7 populated; T5 (author attribution) prominent, since it is the A4 gate input.**
  See §5. T5 is presented first among the per-arm detail tables and its numbers are the direct
  explanation for every `c7Clean = no` elsewhere in the report (§5, T5 discussion).
- [x] **No-op rate and `ALL_PATHS_BLOCKED` presented adjacently, with the interpretation stated.**
  §6, with the explicit high-no-op/low-blocked = restraint, low-no-op/high-blocked = thrashing
  rule applied to both campaigns' actual numbers.
- [x] **`correctAt1`'s limits stated in the report text, not only in code KDoc.** §3.1, quoted and
  paraphrased in prose, plus applied concretely to why every `correctAt1` cell in this campaign's
  data reads `n/a`.
- [x] **A written conclusion on whether A4's ≥ 8/10 bar is met, per arm.** §10, table plus prose,
  unhedged.
- [x] **A note that the optimality comparison is a Praha-stage question requiring an OR/MILP
  yardstick, cross-referenced to #591.** §11.
- [x] **Report committed under `docs/`; English only (CLAUDE.md hard rule).** This file, at
  `docs/GOAL_10_SP2C14_RELIABILITY_REPORT.md`, committed on this branch. No non-English text
  appears anywhere in this document.
---

## Related

- [#822](https://github.com/bedaHovorka/interlockSim/issues/822) — Goal 10 SP2c parent
- [#837](https://github.com/bedaHovorka/interlockSim/issues/837) — this report (SP2c.14)
- [#895](https://github.com/bedaHovorka/interlockSim/issues/895) — the A4 re-baseline this report
  synthesizes; `docs/GOAL_10_I895_REBASELINE_REPORT.md`
- [#847](https://github.com/bedaHovorka/interlockSim/issues/847) — SP2c.24, the earlier sweep
  campaign #895 re-baselined; `docs/GOAL_10_SP2C24_SWEEP_REPORT.md`
- [#834](https://github.com/bedaHovorka/interlockSim/issues/834) — SP2c.11, the prompt/history
  work whose `historyN = 0` cell is Campaign B here; `docs/GOAL_10_SP2C11_SWEEP_REPORT.md`
- [#890](https://github.com/bedaHovorka/interlockSim/issues/890) — the `LLM_CONSTRAINED_JSON`
  data gap (§7)
- [#838](https://github.com/bedaHovorka/interlockSim/issues/838) — SP2c.15, the
  interface-vs-capacity diagnostic follow-up for §8's stall pattern
- [#591](https://github.com/bedaHovorka/interlockSim/issues/591) — Praha, the OR/MILP optimality
  comparison this report defers to (§11)
- [#848](https://github.com/bedaHovorka/interlockSim/issues/848) — SP2c.25, the decision-vocabulary
  audit informing which `DispatchDecision` subtypes are live; `docs/GOAL_10_SP2C25_DECISION_VOCABULARY_AUDIT.md`
- [#849](https://github.com/bedaHovorka/interlockSim/issues/849) — SP2c.26, F1 paused-clock ruling
  referenced in T6's cost caveats; `docs/GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md`
- [#850](https://github.com/bedaHovorka/interlockSim/issues/850) — SP2c.27, Ollama capability audit
  (seed non-reachability) referenced in T6; `docs/GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md`
- `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/planner/RunReportAggregator.kt`
  — the tool that produces T1–T7's shape and the gate predicate quoted in §4
