# Dispatcher Reliability Report

Generated: 2026-08-23 07:53:58 UTC

## Arm Comparison

| Arm | Runs | Passing | Gate | LLM Success (median) | Actionable Rate (median) | NoOp (median) | validAt1 (median) | correctAt1 (median) | p95 latency ms | C7 clean |
|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | 10 | 10 | ✅ PASS | 0.000 | 0.000 | 0.000 | 0.000 | n/a | — | yes |
| LLM_TOOL_CALLING | 20 | 8 | ❌ FAIL | 0.780 | 1.000 | 0.383 | 0.000 | n/a | 30005 | no |
| LLM_CONSTRAINED_JSON | 0 | 0 | ❌ FAIL | 0.000 | 0.000 | 0.000 | 0.000 | n/a | — | yes |

## Logged FATAL Simulation Exceptions

No run recorded a FATAL `SimulationException` occurrence in its log. (A run whose log could not be scanned shows `n/a`, not a count, for `logged FATAL sim exceptions` in Per-Run Detail below — absence of a finding, not a clean bill.)

## Per-Run Detail

| Arm | RunId | Ticks | LLM_ACTIONS | LLM_NO_OP | LLM_REPAIRED | LLM_SILENT_NONACTIONABLE | TIMEOUT_NOOP | RULE_FALLBACK | Actionable Rate | End cause | C7 clean | Fallback tick | logged FATAL sim exceptions |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-0_a-3_it-default_pv-default-r01 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-0_a-3_it-default_pv-default-r02 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-0_a-3_it-default_pv-default-r03 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-0_a-3_it-default_pv-default-r04 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-0_a-3_it-default_pv-default-r05 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-0_a-3_it-default_pv-default-r06 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-0_a-3_it-default_pv-default-r07 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-0_a-3_it-default_pv-default-r08 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-0_a-3_it-default_pv-default-r09 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-0_a-3_it-default_pv-default-r10 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r01 | 81 | 36 | 31 | 0 | 14 | 0 | 0 | 1.000 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r02 | 86 | 32 | 33 | 0 | 19 | 0 | 2 | 0.970 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r03 | 35 | 26 | 8 | 0 | 1 | 0 | 0 | 1.000 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r04 | 59 | 17 | 18 | 0 | 24 | 0 | 0 | 1.000 | TERMINATED_EARLY | yes | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r05 | 96 | 41 | 38 | 0 | 17 | 0 | 0 | 1.000 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r06 | 88 | 38 | 32 | 0 | 18 | 0 | 0 | 1.000 | TERMINATED_EARLY | yes | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r07 | 85 | 37 | 30 | 0 | 18 | 0 | 0 | 1.000 | TERMINATED_EARLY | yes | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r08 | 97 | 36 | 41 | 0 | 20 | 0 | 0 | 1.000 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r09 | 81 | 26 | 34 | 0 | 19 | 0 | 2 | 0.968 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r10 | 36 | 27 | 8 | 0 | 1 | 0 | 0 | 1.000 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r01 | 70 | 26 | 27 | 0 | 17 | 0 | 0 | 1.000 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r02 | 52 | 22 | 17 | 0 | 11 | 0 | 2 | 0.951 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r03 | 22 | 12 | 5 | 0 | 1 | 0 | 4 | 0.810 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r04 | 12 | 4 | 7 | 0 | 1 | 0 | 0 | 1.000 | TERMINATED_EARLY | yes | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r05 | 76 | 19 | 37 | 0 | 20 | 0 | 0 | 1.000 | TERMINATED_EARLY | yes | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r06 | 63 | 30 | 20 | 0 | 13 | 0 | 0 | 1.000 | TERMINATED_EARLY | yes | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r07 | 89 | 28 | 40 | 0 | 20 | 0 | 1 | 0.986 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r08 | 8 | 1 | 5 | 0 | 1 | 0 | 1 | 0.857 | TERMINATED_EARLY | no | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r09 | 63 | 35 | 19 | 0 | 9 | 0 | 0 | 1.000 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r10 | 76 | 20 | 34 | 0 | 22 | 0 | 0 | 1.000 | TERMINATED_EARLY | yes | - | n/a |

## Failure Modes (Rejection Codes)

> Read Failure Modes together with Apply Failures: a **high noOpRate with low ALL_PATHS_BLOCKED** is correct restraint; a **low noOpRate with high ALL_PATHS_BLOCKED** is thrashing.

| Rejection Code | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| UNKNOWN_TRAIN | 0 | 10 | 0 |
| BLANK_ARGUMENT | 0 | 19 | 0 |
| UNKNOWN_ENDPOINT | 0 | 0 | 0 |
| ENDPOINT_IS_BLOCK_ID | 0 | 5 | 0 |
| TRAIN_ALREADY_ACTIVE | 0 | 78 | 0 |
| TRAIN_NOT_QUEUED | 0 | 0 | 0 |
| CAPACITY_FULL | 0 | 0 | 0 |
| TRAIN_ALREADY_EXITED | 0 | 0 | 0 |
| ROUTE_ALREADY_HELD_TO_SAME_TARGET | 0 | 0 | 0 |
| ROUTE_HELD_TO_DIFFERENT_TARGET | 0 | 0 | 0 |
| TRAIN_NOT_ADMITTED | 0 | 0 | 0 |
| TARGET_NOT_TRAIN_DESTINATION | 0 | 15 | 0 |
| ROUTE_SPANS_ENTRY_TO_EXIT | 0 | 5 | 0 |
| NO_FREE_PATH | 0 | 0 | 0 |
| ORIGIN_NOT_AT_TRAIN_POSITION | 0 | 108 | 0 |
| NO_ROUTE_HELD | 0 | 0 | 0 |
| TRAIN_ON_RESERVED_BLOCK | 0 | 0 | 0 |
| DUPLICATE_ACTION_THIS_TICK | 0 | 0 | 0 |
| ACTION_LIMIT_EXCEEDED | 0 | 37 | 0 |

## Apply Failures

| Apply Failure Code | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| ALL_PATHS_BLOCKED | 0 | 40 | 0 |
| CONFLICT | 0 | 0 | 0 |
| NO_ROUTE_EXISTS | 0 | 0 | 0 |
| APPROVE_REJECTED | 0 | 0 | 0 |
| CAP_EXCEEDED_APPLY | 0 | 155 | 0 |
| ORIGIN_NOT_CONTIGUOUS | 0 | 21 | 0 |
| CONDITION_FAILED | 0 | 0 | 0 |
| DROPPED_INVALID | 0 | 0 | 0 |
| GEOMETRICALLY_IMPOSSIBLE | 0 | 6 | 0 |

## Author Attribution

> Must be `{LLM: n, everything else: 0}` for a passing LLM arm.

| Action Author | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| LLM | 0 | 1038 | 0 |
| TIMEOUT_NOOP | 0 | 0 | 0 |
| RULE_BASED | 380 | 0 | 0 |
| RULE_FALLBACK | 0 | 14 | 0 |
| SAFETY_NET | 0 | 0 | 0 |
| OPERATOR | 0 | 0 | 0 |

## Latency

| Arm | Tick period ms | p50 latency ms | p95 latency ms | Max latency ms | Deadline misses |
|---|---|---|---|---|---|
| RULE_BASED | 0 | — | — | — | 0 |
| LLM_TOOL_CALLING | 0 | 1156 | 30005 | 30026 | 0 |
| LLM_CONSTRAINED_JSON | 0 | — | — | — | 0 |

## Parameter Sweep

> One row per distinct parameter cell. `Runs`/`Passing` are that cell's own.

### Legend

- **LLM Success** — median `llmSuccessRate` (ticks counted as an LLM success ÷ total ticks) across the cell's runs. **Not comparable to pre-#834 runs:** #834 reclassified idle ticks (former `RULE_FALLBACK`) to `LLM_NO_OP` and REVISED's cap-full `no_op` converts former fallback ticks into LLM successes, so this rate is structurally higher than a pre-#834 run's even at identical railway behaviour — read it as a within-#834 comparison only.
- **Invalid-action rate** — rejected actions ÷ emitted actions (`rejectionsByCode` sum ÷ `emittedByActionType` sum), median across the cell's runs. This is **not** the same figure as `invalidOutputRate` (`TIMEOUT_NOOP` ticks ÷ total ticks): one counts actions, the other counts ticks, and they can disagree sharply on the same run. `n/a` when no run in the cell emitted any action.
- **No-op rate** — `noOpRate`: `LLM_NO_OP` ticks ÷ total ticks, median across the cell's runs.
- **Repair-success rate †** — `repairSuccessRate`: `LLM_REPAIRED` ticks ÷ total ticks. † As of SP2c.11, `LLM_REPAIRED` has no live producer on the async path, so this column is structurally `0` for every cell — read it as "not yet measurable", not as a finding.
- **p50 / p95 latency ms** — median / 95th percentile of the cell's per-run tick-latency percentiles. `—` means no run in the cell measured inference latency (rule-based arm, or every cycle failed before inference started) — *not measured*, never *measured as none*.
- **C7 clean** — whether every run in the cell was C7-clean (no RULE_FALLBACK/SAFETY_NET-authored action).
- **RULE_FALLBACK ticks** — total ticks across the cell's runs where the rule dispatcher ran as fallback, from `ticksByOutcome`. Per #847, a cell can show `C7 clean = yes` while this is nonzero: `c7Clean` counts attributed *actions*, not whether the rule dispatcher ran at all.
- **Journeys completed / Trains entered / Trains exited / Max concurrent / Block transitions / Conflicts / Failed reservations** — RailwayOutcome fields, summed across the cell's runs (max, for "max concurrent"), skipping any run where the figure was not measured. `n/a` when no run in the cell measured it — never rendered as `0`, which would misrepresent "not measured" as "measured as none".
- **Ranking (issue #906)** — both tables are ranked by **Trains exited**, descending, cells with no measured `trainsExited` sorted last. Not by `journeysCompleted`: that counter increments whenever a train's reservation count reaches zero, with no termination or movement predicate, so a stale swept route can credit a journey to a train that never moved, and the counter can fire more than once per train. `trainsExited` is termination-gated and is the authoritative per-cell outcome figure; `journeysCompleted` is still reported alongside it, for comparison only.

### Decision Hygiene

| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | Runs | Passing | Gate | LLM Success | Invalid-action rate | No-op rate | Repair-success rate † | validAt1 | correctAt1 | p50 latency ms | p95 latency ms | C7 clean | RULE_FALLBACK ticks |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | rule-based | 0.0 | 0 | 0 | 3 | unset | 30 |  | 10 | 10 | ✅ PASS | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | n/a | — | — | yes | 0 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 0 | 3 | unset | 30 | REVISED | 10 | 5 | ❌ FAIL | 0.795 | 0.239 | 0.373 | 0.000 | 0.000 | n/a | 1228 | 5299 | no | 4 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 0 | 3 | unset | 30 | REVISED | 10 | 3 | ❌ FAIL | 0.761 | 0.225 | 0.417 | 0.000 | 0.000 | n/a | 982 | 30005 | no | 8 |
| LLM_CONSTRAINED_JSON | rule-based | 0.0 | 0 | 0 | 0 | unset | 30 | unspecified | 0 | 0 | ❌ FAIL | 0.000 | n/a | 0.000 | 0.000 | 0.000 | n/a | — | — | yes | 0 |

### Railway Outcomes

| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | Runs | Journeys completed | Trains entered | Trains exited (authoritative) | Max concurrent | Block transitions | Conflicts | Failed reservations |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | rule-based | 0.0 | 0 | 0 | 3 | unset | 30 |  | 10 | 110 | 150 | 110 | 2 | 250 | 0 | 0 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 0 | 3 | unset | 30 | REVISED | 10 | 69 | 134 | 80 | 2 | 199 | 4 | 0 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 0 | 3 | unset | 30 | REVISED | 10 | 44 | 111 | 51 | 2 | 130 | 4 | 2 |
| LLM_CONSTRAINED_JSON | rule-based | 0.0 | 0 | 0 | 0 | unset | 30 | unspecified | 0 | n/a | n/a | n/a | n/a | n/a | n/a | n/a |

