# Goal 10 #895 — A4 reliability re-baseline, two arms

**Issue:** #895 · **Parent:** #822 (Goal 10 SP2c) · **Date:** 2026-08-23
**Measured tree:** `b2ed3402` (`goal-10` tip) plus `0655ba2e` (Arm 2 grid files, documentation only)
**JAR:** `sha256 3b56a291461ea8e04d495edcbb17573203aaacf54255b240a8c3e33ecff2ec78` — one binary
produced every one of the 60 runs, built once and never rebuilt while runs were in progress. The
reports were later re-rendered by `sha256 31682d457f8044901f51717f479cbc76ee75197f949099b0b06eb4177080d936`,
which carries this issue's gate changes; that invocation skipped all 60 runs and rendered only.
**Model:** `qwen2.5:7b-instruct` · **Network:** `vyhybna.xml` · **Horizon:** 600 simulated seconds
**Evidence:** `docs/measurement/i895-runs/arm1/` and `docs/measurement/i895-runs/arm2/`

#847 measured the LLM dispatcher deliberately **before** the known defects were fixed. This report
is the other half of that comparison. Between the two campaigns these landed on `goal-10`: #893,
#834, #903, #904, #905, #906, #907, #909, #910, #911, #913, #926, #927, #928, #929, #930, #931,
#936, #937, #938, #943, #944. That is far beyond #895's original two-dependency ask, and the scope
extension is stated here rather than absorbed silently.

## Verdict in one table

| | Arm 1 `h=3` t=0.28 | Arm 1 t=0.5 | Arm 2 `h=0` t=0.28 | Arm 2 t=0.5 | control |
|---|---|---|---|---|---|
| A4 gate | ❌ | ❌ | ❌ | ❌ | ✅ |
| runs passing | 0/10 | 0/10 | **5/10** | 3/10 | 10/10 |
| runs `c7Clean` | 0/10 | 1/10 | **8/10** | 7/10 | 10/10 |
| trains admitted (median) | 15 | 15 | 15 | 13.5 | 15 |
| trains exited (median) | 5 | 9 | **8.5** | 5.5 | **11** |
| block transitions (median) | 13 | 22 | **21** | 13 | **25** |
| ended early | 3/10 | 2/10 | 3/10 | 5/10 | 0/10 |

**The A4 gate still fails on every LLM cell.** It fails on the "all runs `c7Clean`" clause, which
8/10 cannot satisfy. But the four questions #895 asked all have clear answers, and three of them are
good news:

1. **Did the railway start working?** Yes, decisively. Admissions went from 2 per run to 15, the
   control arm's own figure. Exits reached 8.5 and block transitions 21, against a ceiling of 11 and
   25. #847's arm managed 2 and 2.
2. **Is C7 still violated in every run?** No. At the shipped `historyN = 0` the arm is `c7Clean` in
   8 of 10 runs, and only 2 of 10 runs contain any `RULE_FALLBACK` tick at all. At `historyN = 3` it
   is still 0/10 and 1/10 — reproducing #834.
3. **Does the cap still fire erratically?** No. Per decision it fell **95 %**, from 0.420 to 0.022.
4. **Did hallucination codes stay at zero?** **No.** 61 across the campaign against #847's zero, and
   one of them — `BLANK_ARGUMENT`, 19 occurrences — appears *only* at the shipped `historyN = 0`.

The dominant remaining failure is not a decision-quality failure at all: **13 of 40 runs deadlocked**
because the dispatcher stopped extending routes and trains stood at STOP signals holding track.

Two arms were run, as the owner directed:

- **Arm 1** — `sp2c24-sweep-grid.json` and `sp2c24-baseline-grid.json`, unchanged. The bridge back
  to #847's published table.
- **Arm 2** — `i895-shipped-defaults-grid.json`, the parameters #834 committed as configuration.
  Compared against `docs/GOAL_10_SP2C11_SWEEP_REPORT.md`.

---

## Read this before any number below

### 1. Arm 1 is not a replication of #847, and cannot be

Six of the eight axes reproduce #847 exactly. The prompt does not. `sp2c24-sweep-grid.json` sets no
`promptVariant`, so the cell inherits whatever the shipped default is — which #834 changed to
`REVISED`. #847 ran neither `REVISED` nor `BASELINE`: it predates PR #896, and `BASELINE` is defined
as "the prompt exactly as PR #896 shipped it, byte for byte". #847 ran a third prompt that no
`PromptVariant` value can select, and choosing `BASELINE` would not have fixed that.

Arm 1 is therefore a controlled before-and-after on `historyN = 3` under today's prompt. It is not a
replication.

**Read cells from `.params`, never from the run id.** Arm 1's slug says `it-default_pv-default`
while its recorded `params` say `inferenceTimeoutSeconds = 30, promptVariant = REVISED`. The
recorded parameters tell the truth.

### 2. Raw rejection counts are not comparable across the two campaigns

Arm 1 made **1336 decision ticks across its 20 runs. #847 made 348.** The arm makes 3.8 times as
many decisions, so a raw count of anything per-decision is inflated by that factor alone.

| rejection code | #847 raw | now raw | #847 per tick | now per tick | change per decision |
|---|---|---|---|---|---|
| `ORIGIN_NOT_AT_TRAIN_POSITION` | 68 | 118 | 0.195 | **0.088** | **−55 %** |
| `TARGET_NOT_TRAIN_DESTINATION` | 43 | 17 | 0.124 | **0.013** | **−89 %** |
| `ACTION_LIMIT_EXCEEDED` | 146 | 29 | 0.420 | **0.022** | **−95 %** |

Read raw, `ORIGIN_NOT_AT_TRAIN_POSITION` looks like it nearly doubled. Per decision it more than
halved. #847's own table quotes raw counts; quoting the two tables side by side reports a regression
that did not happen.

### 3. The gate predicate itself changed

#847 scored `completedNaturally && !terminalFallbackEngaged && c7Clean`. #927 added
`actionableTickRate >= MIN_ACTIONABLE_RATE`. Re-scoring #847's archived runs under today's predicate
gives **the same verdict** for its LLM arm — 5/10 and 6/10 — so the LLM passing column *is*
comparable. Those archived files are schema v1, so their `actionableTickRate` is defaulted from
`llmSuccessRate` on decode, not measured; the equality is real but should be read with that caveat.

Under today's predicate #847's **rule-based** arm drops from 10/10 to 0/10. That is not a regression;
it is the gate defect described in "Findings" below.

### 4. The control arm no longer supplies a noise floor

All twenty rule-based runs of this campaign — ten from each arm's grid — produced **byte-identical**
railway outcomes:

| | value, all 20 runs |
|---|---|
| trains admitted | 15 |
| trains exited | 11 |
| journeys completed | 11 |
| block transitions | 25 |
| end cause | `NATURAL_COMPLETION` |
| `c7Clean` | true |

#907 barriered the control step, and the control arm is now fully deterministic. The published
"±1 journey per 600 s run" noise floor was a property of the unbarriered wiring and is gone. The
control arm now supplies a **ceiling** (11 exits, 25 block transitions), not a floor. Every spread
quoted for an LLM arm below comes from that arm's own ten repeats.

Both mandatory `mergePathInfo` greps — `non-contiguous merge` and `duplicated new start` — found
nothing in either arm's control logs.

---

## Arm 1 — #847's grid verbatim (`historyN = 3`)

20 LLM runs in 1 h 34 m, about 304 s per run. `SweepSummary(planned=20, completed=15, failed=5)`;
all five failures are genuine `TERMINATED_EARLY`, none contention — the machine was idle throughout
and no other heavy job ran.

### Verdict against #847

| | #847 t=0.28 | now t=0.28 | #847 t=0.5 | now t=0.5 | control |
|---|---|---|---|---|---|
| A4 gate | ❌ 5/10 | ❌ **0/10** | ❌ 6/10 | ❌ **0/10** | 11 exits, fixed |
| runs `c7Clean` | 5/10 | **0/10** | 6/10 | **1/10** | 10/10 |
| ticks per run (median) | 16 | **75** | 19 | **71.5** | 0 |
| trains admitted (median) | 2 | **15** | 2 | **15** | 15 |
| trains exited (median) | — | 5 | — | **9** | 11 |
| block transitions (median) | 2 | **13** | 2 | **22** | 25 |
| runs with ≥ 1 `RULE_FALLBACK` **tick** | 10/10 | 10/10 | 10/10 | 9/10 | — |
| `RULE_FALLBACK` ticks (median) | — | 5.5 | — | 3 | 0 |
| `ACTION_LIMIT_EXCEEDED` (raw / per tick) | 92 / 0.42 | 16 / **0.022** | 54 / 0.42 | 13 / **0.022** | 0 |
| hallucination codes | 0 | **4** | 0 | **6** | 0 |
| actionable rate (median) | not measured | 0.921 | not measured | 0.936 | 0.000 |
| actionable rate (range) | — | 0.647 – 0.983 | — | 0.844 – 1.000 | — |
| ended early | 0 | **3/10** | 0 | **2/10** | 0 |

`trainsExited` per run, `t=0.5`: 0, 5, 5, 8, 8, 10, 10, 10, 10, 11. Five of ten runs reach the
control arm's own figure.

### 1. Did the railway start working? Yes, decisively

Admissions went from a median of 2 per run to **15**, which is exactly the control arm's number.
Movement went from 2 block transitions to 13 at `t=0.28` and **22** at `t=0.5`, against the control
arm's 25. #847's arm barely ran a railway; this one runs most of it.

### 2. Is C7 still violated? Yes, and worse than #847 — for a mechanical reason

`c7Clean` fell from 5/10 and 6/10 to 0/10 and 1/10. The cause is not a new defect. `c7Clean` latches
false on the first tick that attributes an action to the rule-based fallback, and the arm now makes
71–75 decisions per run instead of 16–19. Roughly four times the decisions means roughly four times
the chances to trip a flag that counts events rather than quality. #834 already measured
`historyN = 3` as `c7Clean` 0/10 in all four of its cells; this reproduces that exactly.

Reported at tick level, as #895 requires: **19 of 20 runs contain at least one `RULE_FALLBACK`
tick**, median 5.5 ticks at `t=0.28` and 3 at `t=0.5`.

### 3. Does the action cap still fire erratically? No — it is effectively fixed

29 rejections across 20 runs, against #847's 146. Per decision that is 0.022 against 0.420, a 95 %
fall. The per-run range collapsed from 0–27 to 0–7.

Carry forward #834's caveat: `ACTION_LIMIT_EXCEEDED` is not a prompt-quality signal. #834's
controlled probe showed a prompt stating the budget three times produced the same rejections as one
stating it once.

### 4. Did hallucination codes stay at zero? No

Ten across the arm: 7 `UNKNOWN_TRAIN`, 2 `ENDPOINT_IS_BLOCK_ID`, 1 `UNKNOWN_ENDPOINT`, against
#847's reported zero. Small in absolute terms — 0.0075 per decision — but not zero, and the direction
is wrong. #834 had already reported them returning.

### 5. The dominant failure mode: the dispatcher stops extending routes

Five of the twenty runs ended `TERMINATED_EARLY`, every one with the same fingerprint:

> a train stands at a STOP signal holding track it cannot leave, the WARN #943 added fires after
> 60 simulated seconds — `still waiting … for the dispatcher to extend its route` — other trains'
> routes then report `all paths blocked`, and the kDisco event queue drains before the end time.

Stall locations seen: `zB` (×3), `zA` (×2), `doA2` (×2), `doA1`, `doB1`. The stall is more common
than the deadlock: in the `t=0.28` cell 6 of 10 runs contained at least one stalled train, and 3 of
those never recovered. Sometimes the dispatcher extends the route late and rescues the run.

This is a dispatcher decision failure, not a `core/` defect — the bounded wait that reports it is
working exactly as #943 designed it.

### 6. Other codes

`TRAIN_ALREADY_ACTIVE` 43, `ROUTE_SPANS_ENTRY_TO_EXIT` 3 — the latter is #936's new refusal firing
as intended. Latency `p50` 1087 ms, `p95` 30 004 ms, max 30 025 ms: at least one cycle spent the
full 30 s inference deadline. No run recorded a logged FATAL `SimulationException`.

---

## Arm 2 — shipped defaults (`historyN = 0`, `REVISED`, `it = 30`)

20 LLM runs in 1 h 27 m. `SweepSummary(planned=20, completed=12, failed=8)`; all eight failures are
genuine `TERMINATED_EARLY`.

`t = 0.28` is the shipped configuration exactly. `t = 0.5` is the owner-mandated shape match, so
that both arms cost the same and are directly comparable.

| | #834 `t=0.28/h=0/it=30` | now t=0.28 | now t=0.5 | control |
|---|---|---|---|---|
| runs `c7Clean` | 8/10 | **8/10** | 7/10 | 10/10 |
| runs passing the full A4 predicate | — | **5/10** | 3/10 | 10/10 |
| gate | ❌ | ❌ | ❌ | ✅ |
| ticks per run (median) | — | 83 | 63 | 0 |
| runs with ≥ 1 `RULE_FALLBACK` **tick** | — | **2/10** | 4/10 | 0/10 |
| trains admitted (median) | — | 15 | 13.5 | 15 |
| trains exited (median) | 7.0 | **8.5** | 5.5 | 11 |
| block transitions (median) | 15.5 | **21** | 13 | 25 |
| `ACTION_LIMIT_EXCEEDED` | — | 24 | 13 | 0 |
| actionable rate (median, min) | — | 1.000, 0.968 | 1.000, 0.810 | 0.000 |
| ended early | — | 3/10 | **5/10** | 0/10 |
| hallucination codes | — | **24** | **10** | 0 |

### #834 reproduces exactly, and the railway improved on top of it

`c7Clean = 8/10` is #834's own number for this cell, reproduced on a tree that has changed a great
deal since. That is a good sign for the measurement itself. On top of it the railway improved:
exits 7.0 → 8.5 and block transitions 15.5 → 21, against a control-arm ceiling of 11 and 25.

Temperature 0.28 is vindicated as the shipped choice. `t = 0.5` loses half its runs to deadlock and
trails on every outcome figure.

### `historyN = 0` buys decision hygiene, not railway reliability

Compared with Arm 1 at the same temperature, the fallback almost disappears — runs containing any
`RULE_FALLBACK` tick fall from 10/10 to 2/10, and the median fallback-tick count from 5.5 to 0.

The deadlock does not. Stall WARNs appear in 11 of Arm 1's 20 runs and in a comparable share of
Arm 2's, and 8 of Arm 2's 20 runs still ended early against Arm 1's 5. Dropping the history block
makes the dispatcher's *decisions* cleaner without making its *railway* more reliable.

### A new cost of the shipped configuration: `BLANK_ARGUMENT`

| campaign | `BLANK_ARGUMENT` |
|---|---|
| #847, 20 runs | 0 |
| Arm 1 (`historyN = 3`), 20 runs | 0 |
| **Arm 2 (`historyN = 0`), 20 runs** | **19** |

The code appears only at `historyN = 0`. Dropping the history block makes the model emit empty tool
arguments. #834 chose `historyN = 0` on the strength of its `c7Clean` result and did not report
this; it is a real cost of the shipped default and it belongs next to the benefit.

---

## Findings that belong to the gate, not to the railway

### Finding A — the gate scores decision hygiene, and the two are independent

The single most instructive run of the campaign is Arm 1 `t=0.5 r03`:

| `c7Clean` | `actionableTickRate` | trains admitted | **trains exited** | end cause |
|---|---|---|---|---|
| **true** | **1.000** | 5 | **0** | `TERMINATED_EARLY` at 228 s |

It is the only `c7Clean` run in all twenty runs of Arm 1, it has a perfect actionable rate, and not
one train left the railway. Perfect decision hygiene, dead railway. Only `completedNaturally` kept
it out of the passing column.

This is the case #927's "actionable rate **AND** railway outcome" composition exists for. The shape
is right. The threshold is not — see Finding B.

**One thing this finding does *not* say.** Every `TERMINATED_EARLY` run fails `completedNaturally`,
so all thirteen of them already failed the gate. The coin flip is a statement about what the
*metrics* measure, not a hole the gate let anything through. The gate is coherent on this data:
all eight runs it passed had exited 8 to 11 trains.

### Finding B — `MIN_ACTIONABLE_RATE = 0.5` was inert on this workload

`actionableTickRate` across the campaign's forty LLM runs ranges **0.647 to 1.000**.

| threshold | runs clearing it |
|---|---|
| 0.3 – 0.6 | **40/40** |
| 0.7 | 39/40 |
| 0.8 | 39/40 |
| 0.9 | 33/40 |
| 0.95 | 25/40 |

Anything at or below 0.6 cannot fail a run. The clause #927 added changed no verdict in either
direction, including on the dead-railway run above, which scored 1.000.

### Finding C — the gate's arm scoping is defective

`RunReportAggregator.runPassed` applies the actionable-rate clause to every arm. A rule-based run
runs no LLM ticks, so `totalTicks = 0` and `actionableTickRate = 0.0`, and the clause fails it.

Arm 1's own `report.md`, printed by the shipped code:

```
| RULE_BASED       | 10 | 0 | ❌ FAIL | Actionable Rate 0.000 | C7 clean: yes |
```

Ten control runs, every one delivering 11 exits and 25 block transitions, scored **0/10 FAIL**. The
same happens to #847's and #834's archived control runs, both of which were published as 10/10 PASS.
This is a defect introduced by #927, not a railway regression.

### Finding D — the starvation rule is defeated by #906's counter

`RailwayProgress.progress()` returns `STARVED` only when `journeysCompleted == 0` **and**
`trainsExited == 0`. Arm 1 `t=0.5 r03` has `journeysCompleted = 1` with `trainsExited = 0`, so it is
classified `MADE_PROGRESS` although none of its five admitted trains ever left the railway.

`journeysCompleted` is not termination-gated (#906); `trainsExited` is. The zero-versus-nonzero rule
therefore leaks. `RailwayProgress`'s own KDoc defers the partial-starvation cut-off to this issue by
name, and this is the measured case that justifies one.

---

## What this issue changed in the gate, and what it did not

Four changes, all in `dispatcher-agent/` — **zero files under `core/`**, as #895 requires. Two fix
demonstrated defects; two are tripwires that change no verdict on this data, and are labelled as
such rather than presented as fixes.

| change | kind | effect on this campaign |
|---|---|---|
| `runPassed` no longer applies the actionable-rate clause to `RULE_BASED` | **defect fix** | both control arms go from `0/10 ❌ FAIL` to **`10/10 ✅ PASS`** |
| `RailwayProgress` reads `trainsExited` when it is present | **defect fix** | closes the `journeysCompleted = 1, trainsExited = 0` leak |
| `MIN_ACTIONABLE_RATE` `0.5 → 0.8` | tripwire | no verdict changes |
| new `MIN_TRAINS_EXITED = 6` outcome term | tripwire | no verdict changes |

### Why the two tripwires are still worth having

`MIN_ACTIONABLE_RATE` was shipped as an admitted placeholder whose own KDoc said "do not treat it
as a validated number". It is now measured: the lowest rate among the 23 runs that both completed
naturally and cleared the outcome floor is **0.810**, so `0.8` sits just under the healthy
population and catches a collapse without failing anything this campaign considers good.

`MIN_TRAINS_EXITED` is the gate's first real **railway-outcome** term. #927 described the gate as
"actionable rate AND railway outcome", but both of its terms measured decision hygiene and neither
read the railway. The value is derived from the **deterministic control arm** — 11 exits every run,
zero variance — and not from the LLM arm it judges: half of 11, rounded up, is 6. It is
network- and horizon-specific, and its KDoc says so.

### The re-render

Both `report.md` files were regenerated after the change by re-invoking the same four `aiSweep`
commands. All 60 runs were already on disk, so the driver skipped every one and re-rendered only —
no new LLM calls, no data touched.

---

## Reproducing this

```bash
git worktree add -b measure/895-a4-rebaseline .worktrees/measure-895-a4-rebaseline goal-10
cd .worktrees/measure-895-a4-rebaseline
./gradlew :desktop-ui:shadowJar

J=desktop-ui/build/libs/interlockSim.jar
O1=build/reports/i895-arm1-847-verbatim
O2=build/reports/i895-arm2-shipped-defaults

# control arms first (about 20 s each), then the greps
java -jar $J aiSweep --grid docs/measurement/sp2c24-baseline-grid.json --out $O1
java -jar $J aiSweep --grid docs/measurement/i895-shipped-defaults-baseline-grid.json --out $O2
grep -rl "non-contiguous merge" $O1/logs/ $O2/logs/    # must find nothing
grep -rl "duplicated new start" $O1/logs/ $O2/logs/    # must find nothing

# the two LLM arms, about 1 h 35 m each; resumable — re-invoke to continue
java -jar $J aiSweep --grid docs/measurement/sp2c24-sweep-grid.json          --out $O1
java -jar $J aiSweep --grid docs/measurement/i895-shipped-defaults-grid.json --out $O2
```

Use two output directories. `RunReportAggregator` groups by arm, not by grid, so one shared
directory merges both LLM arms into a single row and five of the seven report sections lose their
meaning.

Requires Ollama at `http://localhost:11434` with `qwen2.5:7b-instruct`. Manual only, never CI. Run
nothing else heavy on the machine while it runs.

## Standing caveats

- Rank on `trainsExited`, not `journeysCompleted` (#906) — see Finding D for what happens otherwise.
- A run with `loggedFatalSimExceptionCount > 0` is not a clean data point. None occurred here.
- `RunEndCause.STARVED` is new (#930, schema v7). #847's runs are schema v1 and could not record it,
  so a `STARVED` count has no counterpart in the old table.
- Station capacity is fixed at 2 (`RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS`) and cannot be
  swept. It is plausibly the most outcome-determining number in the experiment.
- There is no `seed` axis (#894). No run here is reproducible in the seed sense.
- One model only. Model choice is #838.
- `repairSuccessRate`, `validAt1`, `correctAt1`, `oracleAgreementAt1` and the `LLM_REPAIRED` /
  `LLM_EXCEPTION` / `LLM_ABANDONED` tick classes are structural zeroes (#835), not measurements.
