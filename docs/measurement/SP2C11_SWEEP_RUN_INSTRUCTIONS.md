# SP2c.11 sweep — run instructions (Issue #834)

Grid files: `sp2c11-prompt-ab-grid.json` (stage 1, prompt A/B), `sp2c11-sweep-grid.json`
(stage 2, factorial on the winning prompt). Both live next to the existing `sp2c24-*.json` grids
in this directory. This document is the "how to run it" companion; the "what to measure" reasoning
lives in the task brief and its addendum
(`.superpowers/sdd/test-driven-development-do-834-read-wiggly-narwhal/task-13-brief.md`).

**This document does not authorize running the sweep.** The sweep itself is controller-executed
and owner-gated — roughly nine hours of machine time — and must not start until every precondition
below is green.

## 0. Preconditions (must already be green — verify, do not assume)

These were imposed by the traffic-simulation-expert when approving the `:core` changes this branch
carries. The reasoning: a control arm measured on different code is not a control arm.

1. Full gate green: `./gradlew clean build detekt ktlintCheck test integrationTest`.
2. `:core:heavyTest` **and** `:desktop-ui:heavyTest`, including `ThreeTrainLoopRaceHeavyTest`
   (mandatory here, not discretionary — this branch touches `sim/`, `context/navigation/` and
   kDisco integration, all three of CLAUDE.md's heavy-test triggers).
3. Baseline-arm equivalence, pre-fix vs post-fix, N >= 5 runs of `sp2c24-baseline-grid.json` on the
   commit before the `:core` changes and on the branch head. Journeys completed, decisions applied
   and apply-failure counts must be identical. **Rule-based arm only** — the LLM arm's before/after
   is expected to differ in its observation channel (see the brief's precondition 3 for why) and a
   difference there is not evidence of a regression.
4. Inertness proof: the `mergePathInfo` WARN must not fire during a clean `vyhybna` baseline run.
5. The sim-thread-liveness test from Task 15, green.
6. A single `:core` revision used for both arms. Record the exact SHA in the results report. Never
   mix a pre-fix baseline with a post-fix LLM arm.

Do not proceed past section 1 until all six are confirmed and the `:core` SHA is written down.

## 1. Build and dry-run (safe, no Ollama needed, no machine time spent)

```bash
./gradlew :desktop-ui:shadowJar

java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c11-prompt-ab-grid.json --dry-run

java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c11-sweep-grid.json --dry-run
```

Confirm the dry-run output before doing anything else:

- Stage 1 must report **20 runs, 2 cells**, one cell per `promptVariant` (`BASELINE`, `REVISED`),
  with slugs differing only in their `pv-` segment.
- Stage 2 must report **80 runs, 8 cells** (2 temperature x 2 historyN x 2 inferenceTimeoutSeconds),
  every cell carrying the same `promptVariant`.

If either count is off, stop — do not run anything until the grid is fixed and re-dry-run.

### Stage-2 grid: confirm the winning `promptVariant` before running it

`sp2c11-sweep-grid.json` is committed with `"promptVariant": ["REVISED"]` — the working hypothesis,
not a measured result. **Before running stage 2**, read the stage-1 report and confirm `REVISED`
actually won on the criteria in section 4 below (journeys/`trainsExited`, not tick classes alone —
see caveat G). If `BASELINE` won instead, edit this one line to `"promptVariant": ["BASELINE"]` and
re-run the stage-2 dry-run to reconfirm the 80/8 count before launching the real thing.

## 2. Requires

Ollama reachable at `http://localhost:11434` with `qwen2.5:7b-instruct` pulled. The driver forks one
JVM per run.

## 3. Use a FRESH output directory — do not reuse `build/reports/dispatcher-sweep`

Cell slugs for this sweep carry a new `pv-` segment that the sp2c24 grids' slugs never had. That
segment feeds the resumption key (`AiSweepDriver` decides what to skip by scanning for a run id
among the JSONs already present under `--out`). Point `--out` at a directory that has never held an
sp2c24 run — e.g. `build/reports/sp2c11` — for two reasons:

- An **existing** directory from a prior sp2c24 sweep contains run ids with no `pv-` segment. Every
  run this sweep produces will look new against those ids and the sweep will fully **re-run**
  rather than resume anything — correct behaviour (precedented by the `it-` segment added in Issue
  #893 iteration 2), but confusing if you expected resumption and don't know why it isn't
  happening. Using a directory this sweep has never touched avoids the question entirely.
- All three grids below (baseline, stage 1, stage 2) write into the **same** fresh `--out` so one
  final `report.md` covers all of them together.

## 4. Command sequence — baseline pre-flight FIRST, not last

The brief's own "Running it" section lists the baseline grid last (it exists there to add the
rule-based comparison arm to the report). The addendum overrides that ordering: run the baseline
grid **first**, as a pre-flight check, before any LLM-arm run. Both purposes are served by the same
single command — there is no need to run the baseline grid twice.

Why first: the inertness evidence for the `mergePathInfo` fix was gathered on
`wireSynchronousDispatcher`, but this sweep's baseline arm runs through `wireDispatcherAgent`
instead, which has a residual reachable path (`DispatchTickLoop` not updating `targetName` when a
train already holds a reservation, so a second `RequestRoute` in the same tick can reach
`mergePathInfo` with `new.start != old.target`). The baseline grid's ~30 s runtime makes checking
this cheap; the stage-1/stage-2 grids' ~9 h runtime makes discovering it late very expensive.

```bash
# --- 4a. Baseline pre-flight (~30 s). Run this BEFORE stage 1 or stage 2. ---
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c24-baseline-grid.json \
  --out build/reports/sp2c11
```

Then grep every log this run just wrote:

```bash
grep -rl "non-contiguous merge" build/reports/sp2c11/logs/
grep -rl "duplicated new start" build/reports/sp2c11/logs/
```

**Both greps must find nothing.** If either matches, the baseline arm is not comparable to prior
control measurements — stop, do not proceed to stage 1 or stage 2, and re-baseline from scratch
once the cause is understood. Do not treat a hit as something to note and continue past.

```bash
# --- 4b. Stage 1: prompt A/B (~1.8 h) ---
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c11-prompt-ab-grid.json \
  --out build/reports/sp2c11
```

Read the resulting `build/reports/sp2c11/report.md`. Decide the winning `promptVariant` using
section 5 below — on journeys/railway outcomes, not on tick-class counts alone (caveat G). Edit
`sp2c11-sweep-grid.json`'s `promptVariant` value if the winner isn't `REVISED`, and re-dry-run per
section 1.

```bash
# --- 4c. Stage 2: full factorial on the winning prompt (~7.3 h) ---
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid docs/measurement/sp2c11-sweep-grid.json \
  --out build/reports/sp2c11
```

Total unattended machine time for 4a-4c: roughly 9 hours. The driver is resumable within a single
grid's own runs — re-invoking the same command after an interruption skips runs already recorded
under `--out` — but switching grids (baseline -> stage 1 -> stage 2) always adds new runs, it never
replaces what came before, because each grid's cells carry distinct slugs.

## 5. Do not run this alongside another heavy job

One control-arm run in twelve recorded `TIMEOUT_ABORT` (`RunOutcome.TERMINATED_EARLY`) while a
10-minute heavy-test suite was competing for CPU on the same machine; eleven re-runs of the same
cell were clean. A run that hits this fails `completedNaturally` and therefore `runPassed`,
spuriously — not because anything about the dispatcher regressed.

- Do not launch `:core:heavyTest`, `:desktop-ui:heavyTest`, another sweep, or any other CPU-heavy
  job while section 4 is running.
- If a run comes back `TIMEOUT_ABORT` and something else heavy was running on the machine at the
  time, re-run that one cell/repetition rather than accepting the number — do not treat a single
  such run as a genuine dispatcher failure without first ruling out resource contention.

## 6. Caveats for whoever reads the resulting report

- **Rank on `trainsExited`, not `journeysCompleted`.** `journeysCompleted` increments when a
  train's reservation count reaches zero — no termination and no movement predicate — so a swept
  stale route can credit a "journey" to a train that never moved, and the counter can fire more
  than once per train. `trainsExited` is termination-gated and is the figure `RunReportAggregator`
  itself already sorts cells by (Issue #906). State this explicitly when presenting the numbers;
  don't let a `journeysCompleted` figure stand in for it unqualified.
- **A run with `fatalExceptionCount > 0` is not a clean data point.** kDisco's `SupervisorJob`
  absorbs a FATAL `SimulationException` from any process coroutine, so a run can complete and exit
  0 with a dead train inside it. The sweep records `fatalExceptionCount` /
  `fatalExceptionFirstMessage` per run and the report surfaces them, but the gate predicate
  deliberately does **not** exclude such runs from `runPassed`. Say so explicitly wherever those
  runs' other numbers are quoted — do not let a fatal-tainted run's decision-hygiene score stand in
  for a clean one.
- **What the prompt A/B can and cannot show.** `REVISED` teaches an explicit `no_op` for the
  cap-full/nothing-to-do state, which converts ticks `BASELINE` would score `RULE_FALLBACK` into
  `LLM_NO_OP` *successes* without necessarily moving a single train. Read the stage-1 comparison on
  journeys and railway outcomes, not on tick-class counts alone, or `REVISED` is flattered purely
  by construction. Separately, `REVISED` also moved the turn-termination instruction to the first
  line of the prompt — a second, uncontrolled change bundled into a variant whose stated hypothesis
  is "fewer tokens." The report must say the arm measures a bundle, not attribute whatever moves to
  token count alone.
- **Station capacity is fixed at 2 and is not a grid axis.**
  `RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS` is hard-defaulted at all five `ShuntingLoop`
  construction sites and cannot be swept. It is plausibly the single most outcome-determining
  number in this experiment — state it as a limit on what the sweep can show, not leave it unsaid.
- **No `seed` axis anywhere.** Koog 1.1.1's `OllamaClient` has no path to Ollama's `seed` option
  (#894, `docs/GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md`). Do not describe any run as reproducible
  in the seed sense — only "same recorded snapshot sequence implies byte-identical prompt sequence"
  is a guarantee this sweep actually has.
- **`model` is fixed at `qwen2.5:7b-instruct`.** The model axis belongs to Issue #838 (SP2c.15),
  not this sweep — do not read a single-model result as covering model choice.
