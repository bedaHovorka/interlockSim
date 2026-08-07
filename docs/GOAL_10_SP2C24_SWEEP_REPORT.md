# Goal 10 SP2c.24 — measured dispatcher reliability sweep

**Issue:** #847 (SP2c.24) · **Parent:** #822 (Goal 10 SP2c) · **Date:** 2026-08-07
**Model:** `qwen2.5:7b-instruct` · **Network:** `vyhybna.xml` · **Horizon:** 600 simulated seconds

This is the first measurement of the LLM dispatcher arm that anyone can check. #822 §7 amended A4
from "the outcome contract holds every run" to a **measured success rate over N ≥ 10 runs, gated at
≥ 8/10, with zero `RULE_FALLBACK` and zero `SAFETY_NET` action attributions in any run** — and that
claim cannot be made without performing the runs and recording each one. The run recorder (#845) and
the cross-run aggregator (#846) were both built for a producer that did not exist until #847.

## Verdict

**The A4 gate fails, on both parameter cells.** That is a result, not a malfunction: R1 in #822 §9
predicted exactly this once the admission safety net was removed, and said so in as many words —
*"that is the point — the failure becomes visible instead of concealed."*

| Arm | Model | Temperature | historyN | maxActions | Runs | Passing | Gate |
|---|---|---|---|---|---|---|---|
| `LLM_TOOL_CALLING` | `qwen2.5:7b-instruct` | 0.28 | 3 | 3 | 10 | **5** | ❌ FAIL |
| `LLM_TOOL_CALLING` | `qwen2.5:7b-instruct` | 0.5 | 3 | 3 | 10 | **6** | ❌ FAIL |
| `RULE_BASED` | — | — | 3 | 3 | 10 | 10 | ✅ PASS |

A run passes when `completedNaturally && !terminalFallbackEngaged && c7Clean`. Every one of the 30
runs completed naturally and none engaged the terminal fallback, so **`c7Clean` alone decides every
LLM failure here** — the rule-based fallback originated at least one applied action.

The `RULE_BASED` ✅ is close to vacuous and should not be read as a quality statement. That arm has
no LLM to fall back from, so `c7Clean` is true by construction; the gate was written for the LLM arm
and passing it proves only that the harness scores a healthy run as healthy.

### Per-run detail

`FB` = ticks classified `RULE_FALLBACK`; `aLLM`/`aFB` = actions attributed to `LLM` / `RULE_FALLBACK`;
`CAP`/`TGT`/`ORI` = `ACTION_LIMIT_EXCEEDED` / `TARGET_NOT_TRAIN_DESTINATION` /
`ORIGIN_NOT_AT_TRAIN_POSITION` rejections.

**temperature 0.28 — 5/10 passing**

| run | ticks | LLM | FB | CAP | TGT | ORI | aLLM | aFB | c7Clean |
|---|---|---|---|---|---|---|---|---|---|
| r01 | 14 | 8 | 6 | 4 | 4 | 3 | 28 | 1 | no |
| r02 | 13 | 8 | 5 | 10 | 0 | 3 | 41 | 1 | no |
| r03 | 16 | 12 | 4 | 7 | 4 | 3 | 44 | 0 | yes |
| r04 | 16 | 12 | 4 | 6 | 5 | 5 | 37 | 2 | no |
| r05 | 16 | 12 | 4 | 2 | 3 | 4 | 33 | 0 | yes |
| r06 | 18 | 15 | 3 | 1 | 2 | 4 | 32 | 0 | yes |
| r07 | 14 | 8 | 6 | 6 | 0 | 3 | 29 | 1 | no |
| r08 | 15 | 12 | 3 | 9 | 0 | 3 | 43 | 0 | yes |
| r09 | 18 | 15 | 3 | 20 | 3 | 4 | 60 | 0 | yes |
| r10 | 19 | 14 | 5 | 27 | 4 | 4 | 67 | 2 | no |

**temperature 0.5 — 6/10 passing**

| run | ticks | LLM | FB | CAP | TGT | ORI | aLLM | aFB | c7Clean |
|---|---|---|---|---|---|---|---|---|---|
| r01 | 17 | 13 | 4 | 10 | 1 | 3 | 48 | 0 | yes |
| r02 | 16 | 11 | 5 | 4 | 1 | 2 | 36 | 1 | no |
| r03 | 19 | 15 | 4 | 6 | 4 | 3 | 43 | 0 | yes |
| r04 | 20 | 17 | 3 | 1 | 3 | 3 | 34 | 0 | yes |
| r05 | 20 | 15 | 5 | 3 | 1 | 3 | 36 | 0 | yes |
| r06 | 19 | 15 | 4 | 9 | 0 | 5 | 45 | 3 | no |
| r07 | 20 | 14 | 6 | 0 | 1 | 4 | 27 | 1 | no |
| r08 | 19 | 16 | 3 | 5 | 2 | 3 | 42 | 0 | yes |
| r09 | 18 | 14 | 4 | 10 | 0 | 4 | 46 | 0 | yes |
| r10 | 21 | 16 | 5 | 6 | 5 | 2 | 47 | 1 | no |

**1 of 20 vs 0 of 20 is not the difference between the cells.** 5/10 against 6/10 across ten runs
each is a single run's worth of separation, which at this sample size distinguishes nothing.
Temperature 0.28 versus 0.5 should be reported as *no measured difference in reliability*, not as
0.5 being better.

## Three findings the gate verdict does not carry

### 1. C7 is violated in **20 of 20** runs, and `c7Clean` does not say so

C7 states that `RuleBasedDispatcher` **must not run** during LLM operation — terminal fallback only.
`c7Clean` is computed from `actionsByAuthor`, so a fallback tick on which the rule dispatcher ran and
decided to do nothing scores as clean.

Every single LLM run — including all eleven that "pass" — had **3 to 6 ticks classified
`RULE_FALLBACK`**. The rule-based dispatcher ran on 15–30 % of ticks in every run. What varies is
only whether it happened to emit anything.

The metric is not wrong; it measures what it says it measures, and #822 §7 words A4 in terms of
*attributions*. But a reader who sees `c7Clean = yes` and concludes "the LLM ran autonomously" would
be mistaken, and this report is the place to say so rather than leave the inference available.

### 2. The dispatcher is not moving trains — #893 dominates the outcome

The gate scores decision *hygiene*. It says nothing about whether the railway worked, and the railway
did not.

| per 600 s run | rule-based | LLM (both cells) |
|---|---|---|
| trains admitted | 13 | **2** (median; range 2–3) |
| train movement events | 173 | **2** (median; range 2–23) |
| `ALL_PATHS_BLOCKED` apply failures | 0 | 173 across 20 runs |

The LLM arm admits two trains and then largely stops making physical progress, while issuing 27–67
applied actions per run. That is #893 exactly — *correctly directed but wrongly placed routes*: the
dispatcher reserves a section on the train's route but not the one in front of it, so nothing
releases the train and the reservation blocks everyone else until the orphan sweeper reclaims it.

**#893 was deliberately left unfixed for this measurement**, so these numbers are the baseline any
fix to it must be compared against.

### 3. The per-cycle action cap fires constantly, and erratically

`maxActionsPerTick` was `-1` (not applicable) before #847 because nothing in production enforced it.
Now that it does, **146 calls were rejected across 20 runs** — and the distribution is the
interesting part, not the total:

- t=0.28: 92 rejections over 10 runs, from **1** (r06) to **27** (r10, in 19 ticks)
- t=0.5: 54 rejections over 10 runs, from **0** (r07) to **10**

The model has no stable sense of how much to do per cycle; some runs stay inside the budget
unprompted and others blow through it four times over. §5.5 sets the budget at 0–3 actions per step
and the prompt never states it. That is prompt work, reported on #834.

The two direction guards added by `e2f14ae6` also stay busy: 68 `ORIGIN_NOT_AT_TRAIN_POSITION` and 43
`TARGET_NOT_TRAIN_DESTINATION` rejections across 20 runs, present in nearly every run. Hallucination
codes are **zero**: no `UNKNOWN_ENDPOINT`, no `ENDPOINT_IS_BLOCK_ID`, no `UNKNOWN_TRAIN`, no
`BLANK_ARGUMENT`. The vocabulary problem that dominated rounds 2 and 3 is gone; what remains is
entirely about *where* and *how much*, not *what things are called*.

## Which grid axes are real

#847 sweeps five parameters. Three of them did not exist in production before this task —
`tickPeriodMs`, `historyN` and `maxActionsPerTick` were consumed only by `DispatchTickLoop` and
`ActionValidator`, neither of which is ever constructed outside tests. They were made live rather
than swept as sentinels.

| Axis | Status | Where it lands |
|---|---|---|
| `model` | live | `OllamaExecutorConfig` → Ollama |
| `temperature` | live | `OllamaExecutorConfig` → Ollama |
| `maxActionsPerTick` | live (new) | `SinkHolder` per-cycle cap → `ACTION_LIMIT_EXCEEDED` |
| `historyN` | live (new) | `CycleHistory` block in the per-cycle prompt |
| `tickPeriodMs` | live (new), **inert in practice** | minimum wall-clock spacing in `AgentLoopDriver` |
| `seed` | **impossible** | no path through Koog 1.1.1 — see #894 |

`tickPeriodMs` deserves the qualifier. On the asynchronous path the only period the driver can impose
is a wall-clock floor between cycles — the *simulated*-time period #822 §5.5 describes belongs to
`ShuntingLoop`'s control step, which lives in `core/` and is off limits (C10). At the 10–25 s
inference latency measured here, any value below p95 latency changes nothing, so the sweep held it at
`0` rather than spend hours proving that.

`seed` has **no axis at all**, and that is the honest position rather than an omission: Koog 1.1.1
forwards only `temperature` and the context length to Ollama, so a seed column would claim a
reproducibility guarantee the runs do not have. P8's "pinned seed + recorded snapshot sequence" is
therefore **half held** — the snapshot sequence is deterministic (`CycleHistory` renders purely from
recorded state, no accumulated chat), the seed is not. Filed as **#894**.

### The history block has a measured cost

`historyN = 3` is on in every run here, and ticks per run dropped from round 4's 20–22 (#892's table,
same horizon, same model, no history block) to **13–19**. A longer prompt means longer inference, and
every control tick that fires during inference is coalesced away by `DefaultSnapshotSignal`'s
at-most-one-pending rule. So the history block costs roughly a fifth of the decision rate.

This is a confound in the comparison against #892's table and is stated rather than hidden: this
sweep measures the post-#847 system, not the one #892 measured. Whether the history earns its cost
needs `historyN = 0` as a third cell, which is one line in the grid file and 50 minutes of machine
time — worth doing before the prompt rebuild in #834 commits to it.

## Structurally empty columns

These are reported as zero in every run and are **not** measurements:

- `latencyP50Ms` / `latencyP95Ms` / `latencyMaxMs` — hardcoded `0L` in
  `DefaultDispatcherRunRecorder`; the latency wiring is an open SP2c.22 follow-up
- `validAt1`, `correctAt1`, `oracleAgreementAt1` — produced by `Sp2c21MetricsRecorder`, which is
  constructed only by tests
- `terminalFallbackEngaged` — hardcoded `false`; `TerminalFallbackGuard` is likewise test-only
- `LLM_NO_OP`, `LLM_REPAIRED`, `TIMEOUT_NOOP` — all zero, because the emission/repair path that
  produces them lives in `DispatchTickLoop`

All four are pre-existing gaps in the SP2c.5 loop not reaching production (#835), not something this
sweep broke. They are listed here so nobody reads a column of zeroes as a finding.

## Reproducing this

```bash
./gradlew :desktop-ui:shadowJar

# 20 LLM runs, ~1 h 45 m; resumable — re-invoke after an interrupt and it continues
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c24-sweep-grid.json \
  --out build/reports/dispatcher-sweep

# 10 rule-based baseline runs into the same directory, ~30 s
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c24-baseline-grid.json \
  --out build/reports/dispatcher-sweep
```

Requires Ollama at `http://localhost:11434` with `qwen2.5:7b-instruct`. Outputs: one JSON per run
under `<out>/<arm>/`, one log per run under `<out>/logs/`, and `<out>/report.md`.

Measured wall clock: 301–302 s per 600 s LLM run (a real-time ratio of ~2×, consistent across all 20
— `ThrottlingSimulationController` at `AGENT_MAX_SPEED_MULTIPLIER = 2.0` is the binding constraint,
not inference); ~2.6 s per rule-based run.

**Manual-only.** Nothing in `test`, `integrationTest`, `build` or CI runs this, and nothing should.

## What this does and does not establish

**Does:** A4's measured rate is **5/10 and 6/10** on `qwen2.5:7b-instruct` at this horizon, so the
arm does not hold the gate. The failure mode is specific and countable — the rule-based fallback
originating actions, on top of an interlocking-level defect (#893) that stops trains moving at all.
Hallucination is no longer a factor.

**Does not:** say anything about a larger model (that is SP2c.15, #839), about the constrained-JSON
arm (#890, zero runs recorded here), or about whether the interface or the model capacity is the
binding constraint. It also does not measure decision *quality* — `correctAt1` is structurally
absent, and #822 §7's A5 amendment already demotes optimality at this scale in favour of reliability.

The next measurement should be taken after #893 and the #834 prompt work land, against exactly these
grid files so the two are comparable.
