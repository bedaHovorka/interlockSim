# #895 raw re-baseline results — two arms

One JSON per run, exactly as `aiSweep` wrote it, plus each arm's own `report.md`. **These are the
evidence behind `docs/GOAL_10_I895_REBASELINE_REPORT.md`.** They are committed because
`build/reports/` is gitignored and is destroyed by `./gradlew clean`, which would otherwise make a
published measurement unreproducible.

## Campaign identity

| | |
|---|---|
| measured tree | `b2ed3402` (`goal-10` tip) plus the Arm 2 grid files, documentation only |
| model | `qwen2.5:7b-instruct` |
| network | `vyhybna.xml` |
| horizon | 600 simulated seconds |
| date | 2026-08-23 |
| wall time | Arm 1 1 h 34 m, Arm 2 1 h 27 m, control arms about 20 s each |

## What is here

- `arm1/` — `sp2c24-sweep-grid.json` and `sp2c24-baseline-grid.json`, unchanged from #847.
  20 LLM runs at `historyN = 3` plus 10 rule-based runs.
- `arm2/` — `i895-shipped-defaults-grid.json` and its baseline. 20 LLM runs at the parameters #834
  committed as configuration (`historyN = 0`, `promptVariant = REVISED`,
  `inferenceTimeoutSeconds = 30`) plus 10 rule-based runs.
- `report.md` in each — the aggregator's own rendering, kept as a cross-check against the
  hand-written report.

**The per-run logs are not committed.** They live under `build/reports/i895-arm*/logs/` until the
next `clean`. The stall and deadlock evidence quoted in the report comes from those logs.

**Two output directories, not one.** `RunReportAggregator` groups by `DispatcherArm`, not by grid,
so a single shared directory would merge both LLM arms into one row and five of the seven report
sections would lose their meaning.

## Reading a run id

```
sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r07
         example        model                temp  tick hist act timeout variant  repeat
```

**Read the cell from `.params`, never from the run id.** Arm 1's grid leaves
`inferenceTimeoutSeconds` and `promptVariant` unset, so its slug says `it-default_pv-default` while
the run actually executed with `inferenceTimeoutSeconds = 30` and `promptVariant = REVISED`,
inherited from the committed defaults. The recorded parameters tell the truth; the filename does
not.

## Two caveats that matter even when reading a single file

- `journeysCompleted` is **not** the authoritative outcome figure. It increments when a train's
  reservation count reaches zero, with no termination and no movement predicate (Issue #906). This
  campaign contains a run with `journeysCompleted = 1` and `trainsExited = 0`. Use `trainsExited`.
- The `report.md` files here were rendered **after** the gate changes this issue made
  (`MIN_ACTIONABLE_RATE`, `MIN_TRAINS_EXITED`, and the rule-based arm scoping). No run was re-run:
  the driver skipped all 60 and re-rendered only. Before the change both control arms printed
  `0/10 FAIL`; no LLM verdict moved.
