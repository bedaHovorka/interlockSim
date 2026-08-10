# SP2c.11 raw sweep results (#834)

One JSON per run, exactly as written by `aiSweep`. **These are the evidence behind
`docs/GOAL_10_SP2C11_SWEEP_REPORT.md`** — they are committed because `build/reports/` is gitignored
and is destroyed by `./gradlew clean`, which would otherwise make a published measurement
unreproducible and uncheckable.

## What is here, and what is not

- **`*.json`** — the per-run records. Each carries its own `RunParameters` (model, temperature,
  `tickPeriodMs`, `historyN`, `maxActionsPerTick`, `inferenceTimeoutSeconds`, `promptVariant`), the
  tick taxonomy, rejection and apply-failure codes by code, latency percentiles, `railwayOutcome`
  (journeys, trains entered/exited, block transitions, conflicts, failed reservations), the FATAL
  counters, and the end cause. ~3 KB each.
- **`report-interim.md`** — the aggregator's own rendering, kept as a cross-check against the
  hand-written report.
- **The per-run logs are NOT committed.** They total ~194 MB for this campaign (a run whose
  inference deadline expires produces an enormous log). They live under
  `build/reports/sp2c11/logs/` until the next `clean`.

## Reading them

The run id encodes the whole cell, e.g.

```
sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r07
         example        model                temp  tick hist act timeout variant  repeat
```

so the grid position of any record is readable without opening it.

## Caveats that belong with the numbers

The report is the place for the full list, but two matter even when reading a single file:

- `journeysCompleted` is **not** the authoritative outcome figure — it derives from a counter that
  increments when a train's reservation count reaches zero, with no termination or movement
  predicate (issue #906). Use `trainsExited`, which is termination-gated.
- A non-zero `fatalExceptionCount` here does **not** mean an uncaught exception escaped. On this
  campaign it counts a caught, fully rolled-back non-adjacent-START route rejection (issue #911), and
  the scanner undercounts the underlying malformation because the same cause surfaces at three sites
  and it matches one.

## Reproducing

Grids: `../sp2c11-prompt-ab-grid.json` (stage 1, prompt A/B) and `../sp2c11-sweep-grid.json`
(stage 2, factorial). Instructions: `../SP2C11_SWEEP_RUN_INSTRUCTIONS.md`.
