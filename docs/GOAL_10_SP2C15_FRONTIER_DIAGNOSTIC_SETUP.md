# Goal 10 #838 (SP2c.15) — frontier-model diagnostic: setup only, not yet executed

**Issue:** #838 · **Parent:** #822 (Goal 10 SP2c) · **Depends on:** SP2c.12 · **Date:** 2026-08-23

**Status of this document: preparation only.** It creates the grid file and verifies it parses,
so a human can later launch the diagnostic run described in #838. **No sweep has been executed,
no model has been pulled, and no verdict exists yet.** Every acceptance-criteria bullet in #838
beyond "prepare the run" — the measured comparison, the interface-or-capacity verdict, a minimum
model class, a follow-up filing, and posting to #822 — is out of scope for this document and
remains open until the real run lands.

## 1. Why `qwen2.5:14b-instruct`

`docs/GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md` already researched and named this exact model for
this exact role. §3's "Stretch" table lists it as **S1**:

> | S1 | `qwen2.5:14b-instruct` | ~9.0 GB | ~8 GB "better reasoning, fits 8 GB" | Default Q4 ≈ 9 GB
> **exceeds the 8 GB gate**. Only viable at Q3_K_M (~7.5 GB) with tiny KV-cache; keep as a
> quality-ceiling reference, not a runtime target. |

And §8's decision **D-4**: *"`qwen2.5:14b` is out (default Q4 ≈ 9 GB > 8 GB gate); kept only as a
quality reference."*

That "quality-ceiling reference, not a runtime target" framing is precisely the role #838 needs:
a diagnostic upper bound to attribute a reliability shortfall, not a production candidate. Two
properties make it the right choice over any other larger model:

- **Same model family as the baseline.** `qwen2.5:7b-instruct` (the current DISPATCHER default,
  D1 in SP3.1 §5.1) and `qwen2.5:14b-instruct` share architecture and training lineage; capacity
  (parameter count) becomes the only swept variable. A different family (e.g. a Llama or Mistral
  frontier tier) would confound capacity with architecture/training differences, and the #838
  verdict would no longer isolate "is it capacity" cleanly.
- **Already vetted, not newly proposed.** SP3.1 already weighed this model against the 8 GB
  runtime gate and rejected it for production use while explicitly reserving it for exactly this
  kind of ceiling measurement. #838 does not need to re-litigate the model choice — it needs to
  spend it.

`qwen2.5:14b-instruct` is **not pulled locally yet**. No `ollama pull` was run as part of this
setup, per the hard scope constraint on this task.

## 2. The grid file

`docs/measurement/sp2c15-frontier-grid.json`, created by copying
`docs/measurement/sp2c24-sweep-grid.json` (the grid `#895`'s Arm 1 and originally `#847` used)
and changing only the `model` axis:

```json
{
  "endTimeSeconds": 600,
  "repeat": 10,
  "perRunTimeoutSeconds": 900,
  "axes": {
    "example": ["shuntingLoopAI"],
    "model": ["qwen2.5:14b-instruct"],
    "temperature": [0.28, 0.5],
    "tickPeriodMs": [0],
    "historyN": [3],
    "maxActionsPerTick": [3]
  }
}
```

`endTimeSeconds`, `repeat`, `perRunTimeoutSeconds`, `example`, `temperature`, `tickPeriodMs`,
`historyN` and `maxActionsPerTick` are all unchanged from `sp2c24-sweep-grid.json`, so this is a
same-interface, same-workload, capacity-only diagonal swap — exactly what #838's first
acceptance-criteria bullet ("same four tools, same prompt, same renderers, same validator") asks
for. No new `sp2c15-frontier-baseline-grid.json` was created: the rule-based control arm does not
depend on the LLM model, and its numbers already exist (see §4).

## 3. Dry-run verification (executed — enumerate-only, no inference, no model pull)

Built the JAR first, since it did not exist in this worktree:

```
$ ./gradlew :desktop-ui:shadowJar
BUILD SUCCESSFUL in 12s
14 actionable tasks: 14 executed
```

Then ran the dry-run:

```
$ java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
    --grid docs/measurement/sp2c15-frontier-grid.json --dry-run
```

Output (trimmed to the summary line and two representative run lines; full output has 20 "would
run" lines, one per planned run):

```
kotlin-logging: initializing... active logger factory: Slf4jLoggerFactory
20:13:45.389 [main] INFO  c.v.f.i.d.sweep.AiSweepDriver.run(AiSweepDriver.kt:97) - [aiSweep] grid expands to 20 run(s): 2 cell(s) x 10 repeat(s), endTime=600s, perRunTimeout=900s, out=build/reports/dispatcher-sweep
20:13:45.396 [main] INFO  c.v.f.i.d.sweep.AiSweepDriver.run(AiSweepDriver.kt:104) - [aiSweep] would run sweep-ex-shuntingLoopAI_m-qwen2.5-14b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r01 (SweepCell(example=shuntingLoopAI, model=qwen2.5:14b-instruct, temperature=0.28, tickPeriodMs=0, historyN=3, maxActionsPerTick=3, inferenceTimeoutSeconds=null, promptVariant=null))
...
20:13:45.398 [main] INFO  c.v.f.i.d.sweep.AiSweepDriver.run(AiSweepDriver.kt:104) - [aiSweep] would run sweep-ex-shuntingLoopAI_m-qwen2.5-14b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r10 (SweepCell(example=shuntingLoopAI, model=qwen2.5:14b-instruct, temperature=0.5, tickPeriodMs=0, historyN=3, maxActionsPerTick=3, inferenceTimeoutSeconds=null, promptVariant=null))
```

Exit code `0`. The grid parses correctly and expands to the expected 20 runs (2 temperature cells
× 10 repeats), both cells correctly bound to `model=qwen2.5:14b-instruct`, and the JVM never
contacted Ollama — `--dry-run` is enumerate-only, consistent with CLAUDE.md's description of the
flag.

## 4. Commands to execute the diagnostic later — **NOT YET EXECUTED**

**Nothing below this line has been run.** These are the exact commands for whoever executes #838
for real.

```bash
# Step 1 — pull the model (~9 GB download; NOT run as part of this setup)
ollama pull qwen2.5:14b-instruct

# Step 2 — the diagnostic sweep itself (hours; resumable — re-invoke after an interrupt
# and it continues where it left off). NOT run as part of this setup.
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c15-frontier-grid.json \
  --out build/reports/sp2c15-frontier-diagnostic
```

Per CLAUDE.md's "Manual-only dispatcher sweep (`aiSweep`) — never in CI" section, this is a
manual-only, multi-hour, GPU-heavy CLI mode. It must be launched by a human, on a machine that is
otherwise idle for the duration (the I895 rebaseline report notes contention as a real risk for
`TERMINATED_EARLY` runs), with Ollama already serving `qwen2.5:14b-instruct` at
`http://localhost:11434`.

No rule-based control run is needed for this diagnostic: the control arm is model-independent,
and its numbers are already on record (§5 below) from the same `sp2c24-sweep-grid.json` lineage
this frontier grid was cloned from.

## 5. Baseline to compare against once the frontier run lands

The most recent measured numbers for `qwen2.5:7b-instruct` on this exact interface (same grid
shape: `shuntingLoopAI`, `historyN=3`, `maxActionsPerTick=3`, `tickPeriodMs=0`,
`endTimeSeconds=600`) come from `docs/GOAL_10_I895_REBASELINE_REPORT.md` (#895, 60 runs total,
measured 2026-08-23, tree `b2ed3402`). That report's **Arm 1** used
`sp2c24-sweep-grid.json` verbatim — the same grid this frontier grid was cloned from — so it is
the correct comparison baseline for #838.

### Arm 1 (`historyN=3`) — `qwen2.5:7b-instruct`, LLM_TOOL_CALLING

| | t=0.28 | t=0.5 |
|---|---|---|
| A4 gate | ❌ 0/10 | ❌ 0/10 |
| runs `c7Clean` | 0/10 | 1/10 |
| trains admitted (median) | 15 | 15 |
| trains exited (median) | 5 | 9 |
| block transitions (median) | 13 | 22 |
| `ACTION_LIMIT_EXCEEDED` (raw / per tick) | 16 / 0.022 | 13 / 0.022 |
| hallucination codes | 4 | 6 |
| actionable rate (median) | 0.921 | 0.936 |
| ended early (`TERMINATED_EARLY`) | 3/10 | 2/10 |

### Control (RULE_BASED, model-independent, deterministic, all 20 runs byte-identical)

| trains admitted | trains exited | block transitions | end cause | `c7Clean` | gate |
|---|---|---|---|---|---|
| 15 | 11 | 25 | `NATURAL_COMPLETION` | true | ✅ 10/10 |

The control arm is the outcome **ceiling**, not a floor (#907 barriered the control step; it is
now fully deterministic). #895's dominant finding for the 7B arm was **not** a decision-quality
failure: 13 of 40 LLM runs across both its arms deadlocked because the dispatcher stopped
extending routes while a train held a STOP-signal block, not because of malformed tool calls or
rejected actions. Whoever reads the frontier run's results should check whether that same
stall-and-deadlock pattern (see I895 §"The dominant remaining failure mode", stall locations `zB`,
`zA`, `doA2`, `doA1`, `doB1`) persists at 14B — a frontier model that still gets stuck at STOP
signals waiting to extend a route would be strong evidence for **interface**, not capacity,
since a bigger model does not fix a decision the interface never prompts for.

## 6. Recommended prerequisite: review the actual inputs sent to Ollama before treating the swap as decisive

#838's own cause/response table draws a sharp line between two very different explanations for a
reliability shortfall:

| Cause | Evidence | Response |
|---|---|---|
| Interface | A larger model also fails | Keep iterating on SP2c.2/.4/.11 |
| Capacity | A larger model passes comfortably | Document a minimum model class; no supervisor |

A model swap alone can only supply the "does a larger model still fail" half of that test. It
cannot, by itself, rule out that the *same* interface defect that trips up the 7B model is present
in what gets sent to the 14B model too — a larger model can fail for the same interface reason a
smaller one does, and a naive read of "bigger model also failed" would then wrongly get filed as
"capacity", when the real fix is still on SP2c.2/.4/.11.

**Before the model swap's result is treated as decisive evidence either way**, whoever executes
#838 should review the actual prompt text, tool/affordance definitions, and renderers that
`aiSweep` sends to Ollama for this grid — confirming they are unmodified from what the 7B baseline
used, and spot-checking that nothing in them is itself a source of the failure mode observed in
§5 (e.g. a renderer that never surfaces "you may extend this route now" as an affordance would
produce the same stall on any model, however large). This is a suggested prerequisite or parallel
step for the run's executor, not work performed as part of this setup — no interface review was
conducted here.

## 7. What remains open

This document satisfies only the first acceptance-criteria bullet of #838 ("one run on the
identical interface... with a larger model") in its *preparation* form: the grid exists and has
been dry-run verified. Everything else in #838's acceptance criteria is **not yet done**:

- No sweep has been executed — no `qwen2.5:14b-instruct` metrics exist yet.
- No interface-vs-capacity verdict has been reached. This document does not assert one.
- No minimum model class has been recommended for #532.
- No interface deficiency has been named, and no SP2c.2/.4/.11 follow-up has been filed.
- Nothing has been posted to #822.

This document unblocks #838 by making the run one command away; it does not close #838.
