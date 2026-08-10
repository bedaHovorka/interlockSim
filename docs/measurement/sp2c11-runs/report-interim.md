# Dispatcher Reliability Report

Generated: 2026-08-10 04:26:35 UTC

## Arm Comparison

| Arm | Runs | Passing | Gate | LLM Success (median) | NoOp (median) | validAt1 (median) | correctAt1 (median) | p95 latency ms | C7 clean |
|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | 0 | 0 | ❌ FAIL | 0.000 | 0.000 | 0.000 | n/a | 0 | yes |
| LLM_TOOL_CALLING | 20 | 0 | ❌ FAIL | 0.543 | 0.127 | 0.000 | n/a | 4392 | no |
| LLM_CONSTRAINED_JSON | 0 | 0 | ❌ FAIL | 0.000 | 0.000 | 0.000 | n/a | 0 | yes |

## FATAL Exceptions

> A `FATAL` `SimulationException` was thrown and absorbed by kDisco's `SupervisorJob` — the run still completed and exited 0. Every run listed here measured something invalid and should be treated as a discarded data point, not a passing one; the gate predicate above does not do this automatically.

| Arm | RunId | Count | First message |
|---|---|---|---|
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r03 | 1 | SimulationException[FATAL]: Path separator must be an end of this track at time 474.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r04 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 338.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r09 | 1 | SimulationException[FATAL]: Path separator must be an end of this track at time 198.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r01 | 6 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r02 | 4 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r03 | 4 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r04 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r05 | 4 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r07 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r08 | 3 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r09 | 6 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r10 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |

## Per-Run Detail

| Arm | RunId | Ticks | LLM_ACTIONS | LLM_NO_OP | LLM_REPAIRED | TIMEOUT_NOOP | RULE_FALLBACK | End cause | C7 clean | Fallback tick | FATAL exceptions |
|---|---|---|---|---|---|---|---|---|---|---|---|
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r01 | 21 | 13 | 2 | 0 | 0 | 6 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r02 | 27 | 14 | 2 | 0 | 0 | 11 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r03 | 32 | 10 | 5 | 0 | 0 | 17 | NATURAL_COMPLETION | no | - | 1 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r04 | 29 | 13 | 3 | 0 | 0 | 13 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r05 | 27 | 13 | 3 | 0 | 0 | 11 | NATURAL_COMPLETION | no | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r06 | 15 | 7 | 1 | 0 | 0 | 7 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r07 | 26 | 13 | 3 | 0 | 0 | 10 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r08 | 31 | 9 | 2 | 0 | 0 | 20 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r09 | 29 | 11 | 3 | 0 | 0 | 15 | NATURAL_COMPLETION | no | - | 1 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-BASELINE-r10 | 29 | 13 | 2 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r01 | 29 | 10 | 5 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 6 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r02 | 29 | 10 | 5 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 4 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r03 | 29 | 12 | 4 | 0 | 0 | 13 | NATURAL_COMPLETION | no | - | 4 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r04 | 28 | 12 | 5 | 0 | 0 | 11 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r05 | 29 | 10 | 4 | 0 | 0 | 15 | NATURAL_COMPLETION | no | - | 4 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r06 | 26 | 11 | 4 | 0 | 0 | 11 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r07 | 29 | 12 | 6 | 0 | 0 | 11 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r08 | 27 | 14 | 3 | 0 | 0 | 10 | NATURAL_COMPLETION | no | - | 3 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r09 | 29 | 10 | 4 | 0 | 0 | 15 | NATURAL_COMPLETION | no | - | 6 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-90_pv-REVISED-r10 | 29 | 9 | 6 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 2 |

## Failure Modes (Rejection Codes)

> Read Failure Modes together with Apply Failures: a **high noOpRate with low ALL_PATHS_BLOCKED** is correct restraint; a **low noOpRate with high ALL_PATHS_BLOCKED** is thrashing.

| Rejection Code | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| UNKNOWN_TRAIN | 0 | 8 | 0 |
| BLANK_ARGUMENT | 0 | 1 | 0 |
| UNKNOWN_ENDPOINT | 0 | 0 | 0 |
| ENDPOINT_IS_BLOCK_ID | 0 | 3 | 0 |
| TRAIN_ALREADY_ACTIVE | 0 | 32 | 0 |
| TRAIN_NOT_QUEUED | 0 | 0 | 0 |
| CAPACITY_FULL | 0 | 0 | 0 |
| TRAIN_ALREADY_EXITED | 0 | 0 | 0 |
| ROUTE_ALREADY_HELD_TO_SAME_TARGET | 0 | 0 | 0 |
| ROUTE_HELD_TO_DIFFERENT_TARGET | 0 | 0 | 0 |
| TRAIN_NOT_ADMITTED | 0 | 0 | 0 |
| TARGET_NOT_TRAIN_DESTINATION | 0 | 15 | 0 |
| NO_FREE_PATH | 0 | 0 | 0 |
| ORIGIN_NOT_AT_TRAIN_POSITION | 0 | 70 | 0 |
| NO_ROUTE_HELD | 0 | 0 | 0 |
| TRAIN_ON_RESERVED_BLOCK | 0 | 0 | 0 |
| DUPLICATE_ACTION_THIS_TICK | 0 | 0 | 0 |
| ACTION_LIMIT_EXCEEDED | 0 | 30 | 0 |

## Apply Failures

| Apply Failure Code | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| ALL_PATHS_BLOCKED | 0 | 57 | 0 |
| CONFLICT | 0 | 0 | 0 |
| NO_ROUTE_EXISTS | 0 | 0 | 0 |
| APPROVE_REJECTED | 0 | 0 | 0 |
| CAP_EXCEEDED_APPLY | 0 | 71 | 0 |
| ORIGIN_NOT_CONTIGUOUS | 0 | 2 | 0 |
| DROPPED_INVALID | 0 | 0 | 0 |

## Author Attribution

> Must be `{LLM: n, everything else: 0}` for a passing LLM arm.

| Action Author | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| LLM | 0 | 562 | 0 |
| TIMEOUT_NOOP | 0 | 0 | 0 |
| RULE_BASED | 0 | 0 | 0 |
| RULE_FALLBACK | 0 | 190 | 0 |
| SAFETY_NET | 0 | 0 | 0 |
| OPERATOR | 0 | 0 | 0 |

## Latency

| Arm | Tick period ms | p50 latency ms | p95 latency ms | Max latency ms | Deadline misses |
|---|---|---|---|---|---|
| RULE_BASED | 0 | 0 | 0 | 0 | 0 |
| LLM_TOOL_CALLING | 0 | 580 | 4392 | 90004 | 0 |
| LLM_CONSTRAINED_JSON | 0 | 0 | 0 | 0 | 0 |

## Parameter Sweep

> One row per distinct parameter cell. `Runs`/`Passing` are that cell's own.

### Legend

- **LLM Success** — median `llmSuccessRate` (ticks counted as an LLM success ÷ total ticks) across the cell's runs.
- **Invalid-action rate** — rejected actions ÷ emitted actions (`rejectionsByCode` sum ÷ `emittedByActionType` sum), median across the cell's runs. This is **not** the same figure as `invalidOutputRate` (`TIMEOUT_NOOP` ticks ÷ total ticks): one counts actions, the other counts ticks, and they can disagree sharply on the same run. `n/a` when no run in the cell emitted any action.
- **No-op rate** — `noOpRate`: `LLM_NO_OP` ticks ÷ total ticks, median across the cell's runs.
- **Repair-success rate †** — `repairSuccessRate`: `LLM_REPAIRED` ticks ÷ total ticks. † As of SP2c.11, `LLM_REPAIRED` has no live producer on the async path, so this column is structurally `0` for every cell — read it as "not yet measurable", not as a finding.
- **p50 / p95 latency ms** — median / 95th percentile of the cell's per-run tick-latency percentiles.
- **C7 clean** — whether every run in the cell was C7-clean (no RULE_FALLBACK/SAFETY_NET-authored action).
- **RULE_FALLBACK ticks** — total ticks across the cell's runs where the rule dispatcher ran as fallback, from `ticksByOutcome`. Per #847, a cell can show `C7 clean = yes` while this is nonzero: `c7Clean` counts attributed *actions*, not whether the rule dispatcher ran at all.
- **Journeys completed / Trains entered / Trains exited / Max concurrent / Block transitions / Conflicts / Failed reservations** — RailwayOutcome fields, summed across the cell's runs (max, for "max concurrent"), skipping any run where the figure was not measured. `n/a` when no run in the cell measured it — never rendered as `0`, which would misrepresent "not measured" as "measured as none".
- **Ranking (issue #906)** — both tables are ranked by **Trains exited**, descending, cells with no measured `trainsExited` sorted last. Not by `journeysCompleted`: that counter increments whenever a train's reservation count reaches zero, with no termination or movement predicate, so a stale swept route can credit a journey to a train that never moved, and the counter can fire more than once per train. `trainsExited` is termination-gated and is the authoritative per-cell outcome figure; `journeysCompleted` is still reported alongside it, for comparison only.

### Decision Hygiene

| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | Runs | Passing | Gate | LLM Success | Invalid-action rate | No-op rate | Repair-success rate † | validAt1 | correctAt1 | p50 latency ms | p95 latency ms | C7 clean | RULE_FALLBACK ticks |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 90 | REVISED | 10 | 0 | ❌ FAIL | 0.534 | 0.178 | 0.163 | 0.000 | 0.000 | n/a | 566 | 3159 | no | 128 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 90 | BASELINE | 10 | 0 | ❌ FAIL | 0.543 | 0.253 | 0.099 | 0.000 | 0.000 | n/a | 587 | 3722 | no | 124 |
| RULE_BASED | rule-based | 0.0 | 0 | 0 | 0 | unset | 30 | unspecified | 0 | 0 | ❌ FAIL | 0.000 | n/a | 0.000 | 0.000 | 0.000 | n/a | 0 | 0 | yes | 0 |
| LLM_CONSTRAINED_JSON | rule-based | 0.0 | 0 | 0 | 0 | unset | 30 | unspecified | 0 | 0 | ❌ FAIL | 0.000 | n/a | 0.000 | 0.000 | 0.000 | n/a | 0 | 0 | yes | 0 |

### Railway Outcomes

| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | Runs | Journeys completed | Trains entered | Trains exited (authoritative) | Max concurrent | Block transitions | Conflicts | Failed reservations |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 90 | REVISED | 10 | 75 | 150 | 70 | 2 | 150 | 4 | 4 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 90 | BASELINE | 10 | 57 | 150 | 55 | 2 | 129 | 10 | 0 |
| RULE_BASED | rule-based | 0.0 | 0 | 0 | 0 | unset | 30 | unspecified | 0 | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| LLM_CONSTRAINED_JSON | rule-based | 0.0 | 0 | 0 | 0 | unset | 30 | unspecified | 0 | n/a | n/a | n/a | n/a | n/a | n/a | n/a |

