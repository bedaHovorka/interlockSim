# Goal 10 SP2c.11 — prompt rebuild and dispatcher parameter sweep

**Issue:** #834 (SP2c.11) · **Parent:** #822 (Goal 10 SP2c) · **Date:** 2026-08-11
**Model:** `qwen2.5:7b-instruct` · **Network:** `vyhybna.xml` · **Horizon:** 600 simulated seconds
**`:core` revision, both arms:** `7d3fd0ee` · **Records:** `docs/measurement/sp2c11-runs/`
**Dataset:** 90 LLM-arm runs (9 cells × 10) + 10 rule-based baseline runs

This is the second measured dispatcher sweep in Goal 10, after `docs/GOAL_10_SP2C24_SWEEP_REPORT.md`
(#847). It answers two questions #847 left open: whether the rebuilt prompt beats the one PR #896
shipped, and whether the per-cycle history block earns its cost. Both are answered, and the second
answer is the one that matters.

## Verdict

**The A4 gate still does not pass — but the arm went from *never* C7-clean to C7-clean in 8 of 10
runs, and the axis responsible is `historyN`.**

Be precise about the gate arithmetic, because "8/10" is easy to misread:

```
gatePassed = runCount >= 10  &&  passingRuns >= 8  &&  snapshots.all { c7Clean }
```

At `historyN = 0` the arm satisfies the first two clauses and **fails the third**. A4 therefore does
not pass at the chosen defaults. That is neither a pass to be claimed nor "another failure" to be
filed alongside #847's 5/10 and 6/10: 0/10 → 8/10 on the clause that decided every failure in #847
is a categorical change, and the report says so without rounding it up.

| | #847 (2026-08-07) | this sweep, `historyN=3` | this sweep, `historyN=0` |
|---|---|---|---|
| runs C7-clean | 5/10 and 6/10 | **0/10** in all four cells | **8/10** in all four cells |
| A4 gate | ❌ FAIL | ❌ FAIL | ❌ FAIL (all-`c7Clean` clause) |

Two further results stand alongside it, and neither flatters the work:

- **The LLM arm remains far below the rule-based arm on railway outcomes.** 7 trains exited against
  11, 15 block transitions against 33, on the same network and the same horizon. Decision hygiene
  improved; the railway did not catch up.
- **`REVISED` beats `BASELINE`**, on journeys with an exact one-sided permutation p = 0.0215 — but
  on `trainsExited`, the metric ruling C1/#906 makes authoritative, the same test gives p = 0.0598.
  The prompt result is directional and consistent across three outcome metrics; it is not
  significant at 0.05 on the authoritative one. Section "Stage 1" states both.

## Chosen defaults

Committed as configuration in `61dc979f`
(`dispatcher-agent/src/main/resources/.../dispatcher-defaults.properties`), not as code constants.
Every value is traceable to a cell in this report.

| Parameter | Shipped value | Changed? | Evidence |
|---|---|---|---|
| `promptVariant` | `REVISED` | yes (was `BASELINE`) | Stage 1, `t=0.28/h=3/it=90`, n=10 per arm: journeys median 8.0 vs 6.0, U = 76.5/100, p = 0.0215; `trainsExited` 8.0 vs 5.5; `blockTransitions` 16.0 vs 13.0 |
| `historyN` | `0` | yes (was `3`) | Stage 2, all four `h=0` cells `c7Clean` 8/10 vs all four `h=3` cells 0/10; outcome cost ≤ +1 journey median, inside the ±1 noise floor |
| `temperature` | `0.28` | no | Stage 2: `t=0.28` and `t=0.5` are indistinguishable — 16/40 `c7Clean` each, `trainsExited` median 7.0 each. Kept because nothing measured moves it |
| `inferenceTimeoutSeconds` | `30` | no | Stage 2: `it=30` and `it=90` indistinguishable — 16/40 `c7Clean` each, p95 median 2 698 ms vs 2 716 ms. See "The 30 s deadline" below |
| `maxActionsPerTick` | `3` | no | Not swept (held at 3). `ACTION_LIMIT_EXCEEDED` fires 119 times across 90 runs; #834's prompt work did not fix over-emission and was not expected to |
| `tickPeriodMs` | `0` | no | Not swept (held at 0). Inert in practice at these latencies, per #847 |
| `model` | `qwen2.5:7b-instruct` | no | Single-model sweep. The model axis is #838 (SP2c.15) |

## Stage 1 — prompt A/B

Grid `docs/measurement/sp2c11-prompt-ab-grid.json`: one cell per `promptVariant`, everything else
held at `t=0.28`, `historyN=3`, `maxActionsPerTick=3`, `inferenceTimeoutSeconds=90`, n=10 each.

`FB` = ticks classified `RULE_FALLBACK`; `aFB`/`aLLM` = actions attributed to `RULE_FALLBACK` / `LLM`;
`nA-START` = non-adjacent-START route rejections recorded in that run (see the section of that name —
this is **not** a count of escaping exceptions).

| variant | run | ticks | `LLM_ACTIONS` | `LLM_NO_OP` | FB | aFB | aLLM | exited | journeys | blockTr | p50 ms | p95 ms | nA-START |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| BASELINE | r01 | 21 | 13 | 2 | 6 | 3 | 27 | 2 | 2 | 8 | 1061 | 2808 | 0 |
| BASELINE | r02 | 27 | 14 | 2 | 11 | 4 | 36 | 5 | 6 | 12 | 780 | 3478 | 0 |
| BASELINE | r03 | 32 | 10 | 5 | 17 | 17 | 30 | 9 | 9 | 18 | 348 | 3148 | 1 |
| BASELINE | r04 | 29 | 13 | 3 | 13 | 5 | 34 | 7 | 7 | 16 | 528 | 2416 | 2 |
| BASELINE | r05 | 27 | 13 | 3 | 11 | 6 | 37 | 6 | 6 | 14 | 620 | 3722 | *absent* |
| BASELINE | r06 | 15 | 7 | 1 | 7 | 10 | 16 | 5 | 5 | 10 | 1096 | **90 004** | 0 |
| BASELINE | r07 | 26 | 13 | 3 | 10 | 9 | 30 | 7 | 7 | 16 | 988 | 2304 | 0 |
| BASELINE | r08 | 31 | 9 | 2 | 20 | 4 | 21 | 3 | 3 | 9 | 331 | 2093 | 0 |
| BASELINE | r09 | 29 | 11 | 3 | 15 | 5 | 31 | 3 | 4 | 9 | 555 | 2254 | 1 |
| BASELINE | r10 | 29 | 13 | 2 | 14 | 8 | 34 | 8 | 8 | 17 | 534 | 3036 | 0 |
| REVISED | r01 | 29 | 10 | 5 | 14 | 15 | 20 | 8 | 8 | 17 | 515 | 1432 | 6 |
| REVISED | r02 | 29 | 10 | 5 | 14 | 14 | 23 | 9 | 9 | 17 | 514 | 2242 | 4 |
| REVISED | r03 | 29 | 12 | 4 | 13 | 14 | 28 | 9 | 9 | 17 | 606 | 2566 | 4 |
| REVISED | r04 | 28 | 12 | 5 | 11 | 5 | 34 | 3 | 6 | 10 | 635 | 2228 | 2 |
| REVISED | r05 | 29 | 10 | 4 | 15 | 13 | 36 | 7 | 8 | 16 | 491 | 4392 | 4 |
| REVISED | r06 | 26 | 11 | 4 | 11 | 12 | 22 | 7 | 8 | 15 | 631 | 3159 | 0 |
| REVISED | r07 | 29 | 12 | 6 | 11 | 10 | 23 | 8 | 8 | 16 | 619 | 2023 | 2 |
| REVISED | r08 | 27 | 14 | 3 | 10 | 5 | 32 | 3 | 3 | 8 | 660 | 2171 | 3 |
| REVISED | r09 | 29 | 10 | 4 | 15 | 17 | 22 | 8 | 8 | 19 | 504 | 2628 | 6 |
| REVISED | r10 | 29 | 9 | 6 | 14 | 14 | 26 | 8 | 8 | 15 | 526 | 2697 | 2 |

| metric | BASELINE (n=10) | REVISED (n=10) | U (REVISED > BASELINE) | exact one-sided p |
|---|---|---|---|---|
| `journeysCompleted` | median 6.0, mean 5.7, range 2–9 | median 8.0, mean 7.5, range 3–9 | 76.5 / 100 | **0.0215** |
| `trainsExited` (authoritative) | median 5.5, mean 5.5, range 2–9 | median 8.0, mean 7.0, range 3–9 | 71.0 / 100 | 0.0598 |
| `blockTransitions` | median 13.0, range 8–18 | median 16.0, range 8–19 | 65.5 / 100 | 0.1261 |
| `c7Clean` | 0/10 | 0/10 | — | — |
| `completedNaturally` | 10/10 | 10/10 | — | — |

The p-values are exact permutation tests over all 184 756 relabellings, one-sided, on the
Mann-Whitney U statistic (`journeysCompleted`: 3 978 relabellings reach or exceed the observed U).
The same enumeration on the *mean difference* rather than U gives p = 0.0388 for journeys — quoted
so nobody re-derives a different number and assumes an error.

**`REVISED` is adopted**, on three consistent outcome medians and on the tighter distribution
(`BASELINE` spans 2–9; `REVISED` is 8 or 9 in 8 of 10 runs). But read the result at the strength the
data supports: on `trainsExited` — the metric this project has ruled authoritative — the separation
is p ≈ 0.06, not p ≈ 0.02. It did not change the decision, because `REVISED` wins the point estimate
on every outcome metric and loses none, and because stage 2's whole factorial was then run on it.

Three things about this A/B specifically:

- **It measures a bundle, not prompt length.** `REVISED` changes three things at once: about 20 %
  fewer tokens (3 558 → 2 835 rendered characters, 615 → 482 words), the turn-termination affordance
  moved to line 1, and an explicit idle-output instruction added. **No part of this difference may be
  attributed to token count alone.**
- **`REVISED` is flattered by construction on tick classes.** Teaching `no_op` in the
  cap-full/nothing-to-do state converts ticks `BASELINE` scores `RULE_FALLBACK` into `LLM_NO_OP`
  *successes* without necessarily moving a train — visible in the data as `LLM_NO_OP` 3–6 in
  `REVISED` against 1–5 in `BASELINE`. That is why the A/B is decided on journeys and railway
  outcomes above, and why no tick-class ratio appears in the decision.
- **Latency behaved as predicted.** `BASELINE` r06 spent a full 90 004 ms inference deadline in a
  single cycle; `REVISED`'s worst p95 was 4 392 ms. One run is one run, but it is the only
  full-deadline miss in the A/B and it is on the longer prompt.

A separate live-Ollama probe (task β2) found that `BASELINE`, which states the action budget three
times, produced the *same* 5 `ACTION_LIMIT_EXCEEDED` rejections over three cycles as `REVISED`, which
states it once. Restating the budget does not help; what stops over-emission is `SinkHolder`'s
terminal rejection, received after the fact. Consequently **`ACTION_LIMIT_EXCEEDED` counts in this
report are not a prompt-quality signal** — neither variant reliably self-limits.

## Stage 2 — factorial on `REVISED`

Grid `docs/measurement/sp2c11-sweep-grid.json`: 2 temperature × 2 `historyN` × 2
`inferenceTimeoutSeconds`, n = 10 per cell, `promptVariant=REVISED`, `maxActionsPerTick=3`,
`tickPeriodMs=0`. All values are medians over the cell's 10 runs unless stated.

| t | `historyN` | timeout s | exited | journeys | blockTr | `c7Clean` | LLM success | invalid-action | no-op | repair | FB ticks | aFB | p50 ms | p95 ms |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.28 | **0** | 30 | 7.0 | 7.0 | 15.5 | **8/10** | 0.827 | 0.303 | 0.090 | 0.000\* | 4.5 | 0 | 1042 | 2824 |
| 0.28 | **0** | 90 | 7.0 | 7.0 | 15.0 | **8/10** | 0.808 | 0.293 | 0.139 | 0.000\* | 5.0 | 0 | 1035 | 3157 |
| 0.28 | 3 | 30 | 7.0 | 7.0 | 15.0 | 0/10 | 0.549 | 0.233 | 0.196 | 0.000\* | 12.0 | 8.5 | 636 | 2670 |
| 0.28 | 3 | 90 | 8.0 | 8.0 | 16.0 | 0/10 | 0.534 | 0.178 | 0.163 | 0.000\* | 13.5 | 13.5 | 566 | 2404 |
| 0.5 | **0** | 30 | 7.0 | 7.0 | 15.5 | **8/10** | 0.875 | 0.279 | 0.103 | 0.000\* | 3.0 | 0 | 1050 | 2944 |
| 0.5 | **0** | 90 | 7.0 | 7.0 | 15.0 | **8/10** | 0.849 | 0.257 | 0.142 | 0.000\* | 3.5 | 0 | 1064 | 2800 |
| 0.5 | 3 | 30 | 8.0 | 8.0 | 15.5 | 0/10 | 0.582 | 0.273 | 0.158 | 0.000\* | 11.5 | 9.5 | 587 | 2553 |
| 0.5 | 3 | 90 | 8.0 | 8.0 | 16.5 | 0/10 | 0.576 | 0.250 | 0.132 | 0.000\* | 11.5 | 12.0 | 580 | 2604 |
| — | — | — | **11.0** | **11.0** | **33.0** | **10/10** | — | — | — | — | 0 | 0 | — | — |

Last row is the `RULE_BASED` control arm (n=10). `aFB` = actions attributed to `RULE_FALLBACK`, the
quantity `c7Clean` is computed from. "invalid-action" is rejections ÷ emissions per run;
"no-op" is `noOpRate`.

\* **`repairSuccessRate` has no live producer and reads 0.000 in every run of every cell.** It is a
column of structural zeroes, not a measurement of anything. Same for `validAt1`, `correctAt1`,
`oracleAgreementAt1` and `terminalFallbackEngaged` (all absent or hardcoded), and for the tick
classes `LLM_REPAIRED`, `LLM_EXCEPTION`, `LLM_ABANDONED` and `TIMEOUT_NOOP`, which are **0 across all
90 runs**. `timeoutNoOpByCause` is likewise all-zero in every cell. These are pre-existing SP2c.5
wiring gaps (#835), not something this sweep broke.

### 1. `historyN` is the dominant axis, and the history block does not earn its cost

`historyN = 0` gives `c7Clean` 8/10 in all four cells. `historyN = 3` gives 0/10 in all four. Nothing
else in the grid comes close.

| pooled over 40 runs each | `historyN = 0` | `historyN = 3` |
|---|---|---|
| `c7Clean` | **32/40** | **0/40** |
| `RULE_FALLBACK`-attributed actions, median | **0** | 12 |
| `RULE_FALLBACK` ticks, median | 4 | 12 |
| LLM success rate, median | 0.844 | 0.563 |
| `trainsExited`, mean / median | 6.83 / 7.0 | 6.90 / 7.5 |
| `trainsExited`, population σ | **0.54** | **2.07** |
| `journeysCompleted`, mean | 6.90 | 7.28 |

The outcome cost of dropping the block is at most +1 journey median (8.0 vs 7.0 in two of four
pairings, equal in the other two) and +0.07 trains exited on the mean — **inside the ±1 journey noise
floor** established for this harness. #847 raised exactly this question and left it open — *"whether
the history earns that has never been measured"* — and the answer is that it does not. The history
block was the thing keeping the arm from ever being C7-clean.

A second, unasked-for result strengthens it: `historyN = 0` is not merely equal on outcomes, it is
**four times tighter**. Its worst run exited 5 trains; `historyN = 3` produced runs that exited 1 and
3. On a reliability gate, the arm with the same median and a quarter of the spread is the better arm
even before `c7Clean` is considered.

One expectation from #847 is **not** reproduced and is recorded as such: #847 inferred that the
history block costs roughly a fifth of the decision rate. Here `historyN = 0` runs have *fewer*
control cycles (median 25 vs 28) and a *slower* median cycle (p50 1 043 ms vs 623 ms) than
`historyN = 3`. The plausible reading is that p50 tracks whether a cycle actually emits tool calls
(`LLM_ACTIONS` ticks: median 18 at `h=0` vs 10–12 at `h=3`) rather than prompt length — but this
sweep did not test that, and it is offered as an observation, not a mechanism.

### 2. Temperature moves nothing

`t = 0.28` and `t = 0.5` are indistinguishable on every axis measured: 16/40 `c7Clean` each,
`trainsExited` median 7.0 each, and no cell pair separated by more than one journey. This reproduces
#847's finding at four times the sample size. Report it as *no measured difference*, not as one value
being better.

### 3. The 30 s inference deadline is vindicated

PR #896 deferred this decision here explicitly: production shipped 30 s, at which `qwen2.5:7b`
timed out on essentially every cycle with the old prompt, while 90 s produced 3–6 clean cycles per
run but was measurement-only configuration.

With `REVISED` and `historyN = 0`, a cycle takes ~1.0 s at p50 and ~2.8 s at p95, and the two
deadlines are indistinguishable: 16/40 `c7Clean` each, `trainsExited` median 7.0 each, p95 median
2 698 ms (30 s) against 2 716 ms (90 s). **30 s is no longer a binding constraint.**

Full-deadline misses have not vanished, and the report does not claim they have: 2 of the 40 runs at
`it=30` contained at least one cycle that consumed the whole 30 s, and 3 of the 50 runs at `it=90`
contained one that consumed the whole 90 s. The rate is roughly the same at both settings — which is
the argument *for* the shorter deadline. A rare stalled cycle happens either way, and a 90 s budget
merely makes each stall three times as long: the single worst cycle in the campaign was 90 004 ms.

### 4. Action vocabulary and rejection profile

Across all 90 LLM runs, 3 405 emissions and 877 rejections:

| emitted | count | | rejection code | count |
|---|---|---|---|---|
| `RequestRoute` | 1 194 | | `ORIGIN_NOT_AT_TRAIN_POSITION` | 393 |
| `ApproveTrain` | 969 | | `TRAIN_ALREADY_ACTIVE` | 156 |
| `request_route` | 670 | | `ACTION_LIMIT_EXCEEDED` | 119 |
| `ReservePath` | 362 | | `UNKNOWN_TRAIN` | 100 |
| `approve_train` | 205 | | `TARGET_NOT_TRAIN_DESTINATION` | 72 |
| `ReleaseRoute` / `cancel_route` | 3 / 2 | | `ENDPOINT_IS_BLOCK_ID` / `BLANK_ARGUMENT` | 31 / 6 |

Apply failures: `CAP_EXCEEDED_APPLY` 187, `ALL_PATHS_BLOCKED` 184, `ORIGIN_NOT_CONTIGUOUS` 36,
`NO_ROUTE_EXISTS` 0, `CONFLICT` 0, `APPROVE_REJECTED` 0, `DROPPED_INVALID` 0.

Two things worth naming:

- **Hallucination codes are back.** #847 recorded zero `UNKNOWN_TRAIN`, `ENDPOINT_IS_BLOCK_ID` and
  `BLANK_ARGUMENT` and concluded the vocabulary problem was solved. This campaign records 100, 31 and
  6. The two campaigns ran different prompts on different code and their counts are not directly
  comparable (see disclosure 10 below), so this is not "a regression of X %" — but the claim that
  hallucinated identifiers are gone does not survive the larger sample and should not be repeated.
- `historyN = 0` shifts the action mix sharply: `ReservePath` almost disappears (1–2 emissions per
  10-run cell against 63–93 at `historyN = 3`) in favour of `RequestRoute`/`request_route`. The
  mechanism is unexamined; it is recorded because it is large and because any later change to the
  route-request surface will land on top of it.

## The rule-based control arm

Ten runs, same commit, same horizon, `sp2c24-baseline-grid.json`.

| | value |
|---|---|
| `trainsExited` | 11 in 9 runs, 10 in 1 |
| `journeysCompleted` | 11 in 9 runs, 10 in 1 |
| `blockTransitions` | 33 in 8 runs, 32 in 2 |
| `trainsEntered` | 15 in all 10 |
| `conflicts` | 0 in all 10 |
| `c7Clean` | 10/10 (vacuous — no LLM to fall back from) |
| non-adjacent-START rejections | 0 |

**The LLM arm is not close.** 7 trains exited against 11, 15 block transitions against 33 — the LLM
dispatcher moves the railway at roughly half the rule-based rate. The prompt rebuild improved
decision hygiene by a lot and outcomes by a little; a reader who takes "8/10 `c7Clean`" as the
headline without this paragraph has the wrong picture.

The 10/10 `c7Clean` on this arm is close to vacuous and is not a quality statement, exactly as #847
said: that arm has no LLM to fall back from, so `c7Clean` is true by construction.

## Non-adjacent-START route rejections

The run recorder gained a per-run counter (`fatalExceptionCount` / `fatalExceptionFirstMessage`)
under expert condition C2, to stop a run with a dead simulation process being recorded as clean. On
this campaign it fires often, and **what it counts is not what its name suggests**.

Every occurrence in all 90 runs carries the same message —
`SimulationException[FATAL]: Path separator must be an end of this track` — and every one of them is
a **non-adjacent-START route rejection (caught, rolled back, route re-granted on a later control
step)**. It must never be reported as "FATAL exceptions occurred". The throw at
`DefaultPathReservationService.kt:2394` sits inside a `try` whose `catch (e: Exception)` logs a WARN
and returns `false`; the stack trace in the log is that WARN's attached throwable, not an escape. The
caller's `!signalConfigured` path then runs the full hardened rollback (the PR #901 F3 path), and
because the throw precedes both `setUpSpeed` and `recordClearedSemaphore`, **no aspect is written and
nothing is recorded** — the cleanest possible failure point. The route is granted on a later control
step. Measured escape rate on this campaign: **0**.

Cause, per the traffic-simulation-expert: on a *route extension*, `reservePath` is re-invoked with
the train's original InOut start while the adjacent blocks are already owned by the same train, so
`forwardBlocks.first()` is a block further down the path and `configureStartSignal` Case 2 calls
`maxSpeed(start)` assuming an adjacency that does not hold. It is G4's untreated twin: #893 A1
hardened the *semaphore*-START branch to fail open; the InOut branch reaches the same non-adjacency
through `maxSpeed` and fails closed. Pre-existing — the call dates to `fca91873` (2026-06-14) — and
both arms share the code path.

Counts, and their two important properties:

| | runs with ≥ 1 | events |
|---|---|---|
| all 90 LLM runs | 53 (of 89 measured) | 137 |
| stage 1 `BASELINE` | 3/10 | 4 |
| stage 1 `REVISED` | 9/10 | 33 |
| stage 2 `historyN=0` cells | 3–6 of 10 | 8–13 per cell |
| stage 2 `historyN=3` cells | 7–9 of 10 | 18–23 per cell |
| rule-based control arm | 0/10 | 0 |

1. **The rejection rate is higher in the better arm** — 9/10 in `REVISED` against 3/10 in
   `BASELINE`. This is a route-extension artifact (#911) and **must not be read as ill health**:
   `REVISED` dispatches more successfully, so it extends more routes, so it meets the benign
   rejection more often. Within stage 2 the ordering is the other way round (`historyN=3`, the worse
   arm, sees more), so the counter does not track quality in either direction and should not be used
   as a proxy for it.
2. **The counter undercounts (#913).** The same root cause surfaces at three sites —
   `configureSemaphoreSignal`'s internal catch (message-only WARN), `configureStartSignal`'s InOut
   branch (WARN + trace) and `startFacesTravelDirection`'s fail-open catch (DEBUG, invisible) — and
   `FatalExceptionScanner` matches one of the three. On the five-run diagnostic sample the scanner
   reported 2/5 where the **true malformation rate was ~80 % (4/5)**. Treat the 53/89 above as a
   floor on how many runs met the malformation, not an estimate of it. `FatalExceptionScanner` also
   cannot distinguish a `SupervisorJob`-absorbed uncaught FATAL from a caught-and-logged one, which
   is the substance of #913.

**No run was excluded on this basis, and none should have been.** Under expert condition C2 a run
with a non-zero counter is not a clean data point, and the gate predicate deliberately does not
exclude it. Both facts are stated here rather than resolved by filtering: the two highest-scoring
`BASELINE` runs (9 and 7 journeys) both carry the counter, so post-hoc exclusion on a misread
instrument would have removed the best outcomes and *lowered* the measured rate on a false pretext.

**One run's counter is absent, not zero:** `BASELINE` r05 records `fatalExceptionCount: null`. The
schema distinguishes absent from zero precisely so this cannot be misread; 89 of 90 runs carry a
measured counter.

## What this measurement does and does not establish

**Does.** At the shipped defaults (`REVISED`, `historyN=0`, `t=0.28`, `it=30`) the LLM arm is
C7-clean in 8 of 10 runs, up from 0 of 10 with the history block and from #847's 5–6 of 10. `A4`
still does not pass, on the all-`c7Clean` clause. `REVISED` beats `BASELINE` on journeys
(p = 0.0215) and directionally on trains exited (p = 0.0598). `historyN` is the only axis in the grid
that moves anything; temperature and the inference deadline do not. 30 s is an adequate production
deadline for this prompt and model.

**Does not.** Everything below is a limit on the reading, and each was established by a review or an
expert ruling rather than added as colour.

1. **`trainsExited` is authoritative, `journeysCompleted` is not** (expert condition C1, #906).
   `journeysCompleted` derives from a counter that increments when a train's active reservation count
   reaches zero — no termination predicate and no movement predicate — so a swept stale route can
   credit a journey to a train that never moved, and `knownTrains` is never pruned so it can fire more
   than once per train. Both are presented throughout; where they disagree, `trainsExited` governs.
   They *do* disagree in this data: stage 1 `REVISED` r04 records 6 journeys against 3 trains exited.
2. **The `REVISED` arm measures a bundle, not token count.** Length, turn-termination moved to line 1,
   and an explicit idle-output instruction all ride together. Nothing may be attributed to tokens
   alone.
3. **`REVISED` is flattered by construction on tick classes**, so the A/B is decided on railway
   outcomes. See "Stage 1".
4. **A residual share of `ALL_PATHS_BLOCKED` is permanent impossibility, not contention** (#903). G4
   rear-facing-START and unconfigurable-switch failures are still misclassified inside `:core`'s own
   `ReservationResult` vocabulary, on both branches. The share is **unquantified**; 184
   `ALL_PATHS_BLOCKED` apply failures were recorded here and the bucket is not clean.
5. **#847's figures are not comparable across `34af56c1`.** The denial-cause fix changed what counts
   as a hard failure: `Sp2c21MetricsRecorder` excludes `ALL_PATHS_BLOCKED` from its hard-failure
   count, and post-fix a permanently-impossible request lands in `NO_ROUTE_EXISTS` and *does* count.
   #847 additionally compared two arms instrumented by **different code paths** — its "baseline 0 vs
   LLM 173 `ALL_PATHS_BLOCKED`" row compared two classifiers, not two dispatchers. Nothing here
   silently re-baselines #847; the discontinuity is the reason the two reports' rejection profiles are
   only ever compared qualitatively.
6. **`repairSuccessRate` has no live producer** and reads zero in all 90 runs. So do `validAt1`,
   `correctAt1`, `oracleAgreementAt1`, `terminalFallbackEngaged`, and the `LLM_REPAIRED` /
   `LLM_EXCEPTION` / `LLM_ABANDONED` / `TIMEOUT_NOOP` tick classes. A column of zeroes here is a
   missing instrument, not a result (#835).
7. **Station capacity is fixed at 2 and is not reachable as a grid axis.**
   `RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS` is hard-defaulted at all five `ShuntingLoop`
   construction sites. With 15 arrivals over 600 s it is plausibly the single most outcome-determining
   number in the experiment, and this sweep could not vary it.
8. **A run with a non-zero non-adjacent-START counter is not a clean data point**, even though the
   gate predicate deliberately does not exclude it. 53 of 89 measured runs carry one; see the section
   above for why none were dropped and why the true rate is higher than the counter says.
9. **No run was re-run after a spurious `TERMINATED_EARLY`.** All 100 runs in the dataset completed
   naturally (`endCause = NATURAL_COMPLETION`, `completedNaturally = true` in 100/100), so no
   re-run and no discarded run needs disclosing. The load-sensitivity that makes such a re-run
   necessary is disclosure 17 below.
10. **`model` is fixed at `qwen2.5:7b-instruct`.** Nothing here says anything about a larger model
    (#838/SP2c.15), about the constrained-JSON arm (#890, zero runs here), or about whether the
    interface or the model capacity is the binding constraint.

### Provenance disclosures (expert-specified, binding)

11. **The rule-based control arm is not run-to-run deterministic, by design.** The kernel is
    bit-reproducible, but `createShuntingLoopExample` wires the dispatcher onto a second OS thread with
    no barrier: `ExampleRegistry.kt:710-717` signals the driver and drains the queue in the same
    breath without waiting for that tick's decisions; `DefaultSnapshotSignal.signal()` deliberately
    drops an unconsumed permit (`SnapshotSignal.kt:158-162`) and `await()` is a 50 ms wall-clock
    `tryAcquire` (`:174`); `NoOpSimulationController.throttle()` is a no-op
    (`SimulationController.kt:171-175`); and `ShuntingLoop.kt:358-360` plus `ExampleRegistry.kt:160`
    complete the path. Driver latency then converts into *lost journeys*, because the admission cap is
    evaluated on the sim thread at apply time against a live count — with cap 2 against 15 arrivals,
    every admission is a race. Filed as #907.
12. **This campaign's own control arm shows it.** Ten runs, one commit, one grid cell, **four distinct
    action profiles**: `CAP_EXCEEDED_APPLY` 9/9/9/10 with `ApproveTrain` 26/27/24 and `ReservePath`
    50/51, and `trainsExited` 11 in nine runs and 10 in one. #847's ten runs on its own commit showed
    three profiles. The variance is intrinsic and predates this branch.
13. **The pre/post `:core` check was equivalence in distribution at N=5 per side, not bit-identity.**
    The vectors are `[11,11,11,10,10]` (pre-fix) and `[10,11,11,11,11]` (post-fix), Fisher exact
    p = 1.0. Read it as "no detectable regression at N=5", not as "identical".
14. **The byte-identical 333 s result establishes kernel determinism under no contention only.** At
    333 s roughly 7 arrivals meet journeys of ~50 s, so cap = 2 rarely binds; at 600 s, 15 arrivals
    make it bind continuously. A clean short run does not license a determinism claim at the sweep's
    horizon.
15. **The exact hard-gate result:** `example shuntingLoopSync 600` — the synchronous
    `wireSynchronousDispatcher` variant, inline on the sim thread, no second thread — run on the
    pre-fix and post-fix commits gave **14 journeys on both**, with **byte-identical journey
    timestamps** (empty diff), **406 block enter/leave events on both sides**, and **zero merge-abort
    lines**. That is what licenses the single-`:core`-revision claim, and it licenses it only on the
    deterministic path.
16. **The noise floor is ±1 journey per 600 s run. Any LLM-vs-rule or cell-vs-cell difference smaller
    than that is not a result.** This is the single most important sentence in this section, and it is
    what disqualifies the `historyN=3` cells' +1 journey median from counting against `historyN=0`.
17. **CPU-load sensitivity.** One control-arm run in twelve recorded `TIMEOUT_ABORT`
    (`RunOutcome.TERMINATED_EARLY`) while a 10-minute heavy-test suite competed for CPU on the same
    machine; eleven re-runs of the same cell were clean. Every run in this dataset was taken with no
    concurrent build, test suite or second sweep — only idle Gradle daemons resident. A
    `TIMEOUT_ABORT` under competing load is a measurement artifact, not a dispatcher failure, and
    `TIMEOUT_ABORT` itself conflates two different failures (#909).
18. **`seed` is null in every run because Koog 1.1.1 has no path to Ollama's `seed` option** (#894,
    `docs/GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md`). The LLM arm therefore carries a **second,
    independent source of non-determinism** on top of the control-arm race in 11. No run in this
    report is reproducible in the seed sense; the only guarantee this sweep has is "same recorded
    snapshot sequence ⇒ byte-identical prompt sequence" (P8, prompt half only).
19. **`RuleBasedDispatcherDeterminismTest` passing does not certify the production arm.** Its runner
    acquires `decisionsApplied` *before* `applier.onControlStep()`
    (`RuleBasedDispatcherDeterminismRunner.kt:191`, `:206-211`), converting the production race into
    lock-step. The green test describes a differently-wired system.

## Reproducing this

Requires Ollama at `http://localhost:11434` with `qwen2.5:7b-instruct` pulled. The driver forks one
JVM per run. **Manual-only** — nothing in `test`, `integrationTest`, `build` or CI runs this, and
nothing should. Full procedure, including the six sweep-provenance preconditions that must be green
first, is `docs/measurement/SP2C11_SWEEP_RUN_INSTRUCTIONS.md`.

```bash
./gradlew :desktop-ui:shadowJar

# 4a. Rule-based baseline FIRST, as a pre-flight (~30 s)
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c24-baseline-grid.json \
  --out build/reports/sp2c11
grep -rl "non-contiguous merge" build/reports/sp2c11/logs/   # must find nothing
grep -rl "duplicated new start" build/reports/sp2c11/logs/   # must find nothing

# 4b. Stage 1 — prompt A/B, 20 runs (~1.8 h)
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c11-prompt-ab-grid.json \
  --out build/reports/sp2c11

# 4c. Stage 2 — factorial on the winning prompt, 80 scheduled runs (~7.3 h)
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c11-sweep-grid.json \
  --out build/reports/sp2c11
```

Both arms ran on `:core` revision **`7d3fd0ee`** — a single revision, never mixing a pre-fix baseline
with a post-fix LLM arm. Use a fresh `--out`: the `pv-` slug segment this sweep introduced resets
every resume key, so an existing `dispatcher-sweep` directory will fully re-run rather than resume.

**Run accounting.** Stage 2's grid schedules 80 runs across 8 cells, but its `t=0.28/h=3/it=90` cell
has the same slug as stage 1's `REVISED` cell, so the driver resumed those 10 rather than re-running
them. The campaign was then stopped with the **final 5 scheduled stage-2 runs deliberately skipped**.
The dataset on disk is 90 LLM run JSONs and every one of the 9 LLM cells carries **exactly 10 runs**
(verifiable by file count under `docs/measurement/sp2c11-runs/`), so no cell is short of the n ≥ 10
that #822 §7's A4 wording requires.

## Related issues

Spin-offs filed during this measurement, none of which blocked it:

| Issue | What it is |
|---|---|
| #903 | G4 rear-facing START / unconfigurable-switch failures misclassified as contention — the unquantified residual in `ALL_PATHS_BLOCKED` (disclosure 4) |
| #904 | `reservePath`'s merge-abort exit should release resources transactionally rather than leave them to the sweeper — deliberately not fixed before this sweep |
| #905 | Origin-abandon silently ends a journey; the per-run FATAL capture was folded in as its AC3 |
| #906 | `completedTrains` counts route releases, not journeys — the basis of the `trainsExited` ruling (disclosure 1) |
| #907 | Control-arm wall-clock race: dispatcher latency costs journeys, not just latency (disclosures 11–12) |
| #908 | `OrphanReservationSweeper` KDoc wrongly says the non-AI `shuntingLoop` example is not swept |
| #909 | `TIMEOUT_ABORT` conflates "simulation ended short of its horizon" with "sweep driver killed the JVM on wall-clock" (disclosure 17) |
| #910 | Shutdown iterates an identity-hashed worker map — latent ordering hazard in an otherwise insertion-ordered kernel |
| #911 | The InOut START branch should mirror #893 A1's fail-open, as `startFacesTravelDirection` already does; gated behind a fresh measurement of both arms |
| #913 | `FatalExceptionScanner` counts caught-and-logged exceptions as absorbed FATALs and undercounts the underlying malformation (three sites, one matched) |

Next measurements, in the order that makes them comparable: #911 and #903 change what the rejection
profile means, so fix-then-re-measure against **exactly these grid files**; #838 (SP2c.15) adds the
model axis; #835 (SP2c.12) owns the un-wired instruments in disclosure 6, without which
`repairSuccessRate`, `correctAt1` and the repair tick classes stay unmeasurable. #895's
re-measurement is a different thing from this sweep — it re-runs #847's exact grids; this one ran new
grids on a new prompt.
