# Dispatcher Reliability Report

Generated: 2026-08-23 07:53:57 UTC

## Arm Comparison

| Arm | Runs | Passing | Gate | LLM Success (median) | Actionable Rate (median) | NoOp (median) | validAt1 (median) | correctAt1 (median) | p95 latency ms | C7 clean |
|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | 10 | 10 | ✅ PASS | 0.000 | 0.000 | 0.000 | 0.000 | n/a | — | yes |
| LLM_TOOL_CALLING | 20 | 0 | ❌ FAIL | 0.779 | 0.932 | 0.424 | 0.000 | n/a | 30004 | no |
| LLM_CONSTRAINED_JSON | 0 | 0 | ❌ FAIL | 0.000 | 0.000 | 0.000 | 0.000 | n/a | — | yes |

## Logged FATAL Simulation Exceptions

No run recorded a FATAL `SimulationException` occurrence in its log. (A run whose log could not be scanned shows `n/a`, not a count, for `logged FATAL sim exceptions` in Per-Run Detail below — absence of a finding, not a clean bill.)

## Per-Run Detail

| Arm | RunId | Ticks | LLM_ACTIONS | LLM_NO_OP | LLM_REPAIRED | LLM_SILENT_NONACTIONABLE | TIMEOUT_NOOP | RULE_FALLBACK | Actionable Rate | End cause | C7 clean | Fallback tick | logged FATAL sim exceptions |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r01 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r02 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r03 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r04 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r05 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r06 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r07 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r08 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r09 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r10 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.000 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r01 | 84 | 30 | 32 | 0 | 19 | 0 | 3 | 0.954 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r02 | 96 | 32 | 43 | 0 | 15 | 0 | 6 | 0.926 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r03 | 56 | 13 | 25 | 0 | 9 | 0 | 9 | 0.809 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r04 | 27 | 13 | 9 | 0 | 4 | 0 | 1 | 0.957 | TERMINATED_EARLY | no | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r05 | 103 | 28 | 49 | 0 | 19 | 0 | 7 | 0.917 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r06 | 100 | 36 | 39 | 0 | 18 | 0 | 7 | 0.915 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r07 | 66 | 14 | 43 | 0 | 8 | 0 | 1 | 0.983 | TERMINATED_EARLY | no | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r08 | 89 | 27 | 51 | 0 | 9 | 0 | 2 | 0.975 | TERMINATED_EARLY | no | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r09 | 53 | 13 | 24 | 0 | 11 | 0 | 5 | 0.881 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-default_pv-default-r10 | 19 | 9 | 2 | 0 | 2 | 0 | 6 | 0.647 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r01 | 29 | 19 | 4 | 0 | 4 | 0 | 2 | 0.920 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r02 | 68 | 20 | 25 | 0 | 17 | 0 | 6 | 0.882 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r03 | 10 | 6 | 2 | 0 | 2 | 0 | 0 | 1.000 | TERMINATED_EARLY | yes | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r04 | 59 | 19 | 25 | 0 | 12 | 0 | 3 | 0.936 | TERMINATED_EARLY | no | - | n/a |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r05 | 85 | 30 | 36 | 0 | 14 | 0 | 5 | 0.930 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r06 | 88 | 22 | 47 | 0 | 16 | 0 | 3 | 0.958 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r07 | 34 | 20 | 7 | 0 | 2 | 0 | 5 | 0.844 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r08 | 90 | 32 | 40 | 0 | 13 | 0 | 5 | 0.935 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r09 | 105 | 40 | 49 | 0 | 13 | 0 | 3 | 0.967 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-default_pv-default-r10 | 75 | 35 | 19 | 0 | 19 | 0 | 2 | 0.964 | NATURAL_COMPLETION | no | - | 0 |

## Failure Modes (Rejection Codes)

> Read Failure Modes together with Apply Failures: a **high noOpRate with low ALL_PATHS_BLOCKED** is correct restraint; a **low noOpRate with high ALL_PATHS_BLOCKED** is thrashing.

| Rejection Code | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| UNKNOWN_TRAIN | 0 | 7 | 0 |
| BLANK_ARGUMENT | 0 | 0 | 0 |
| UNKNOWN_ENDPOINT | 0 | 1 | 0 |
| ENDPOINT_IS_BLOCK_ID | 0 | 2 | 0 |
| TRAIN_ALREADY_ACTIVE | 0 | 43 | 0 |
| TRAIN_NOT_QUEUED | 0 | 0 | 0 |
| CAPACITY_FULL | 0 | 0 | 0 |
| TRAIN_ALREADY_EXITED | 0 | 0 | 0 |
| ROUTE_ALREADY_HELD_TO_SAME_TARGET | 0 | 0 | 0 |
| ROUTE_HELD_TO_DIFFERENT_TARGET | 0 | 0 | 0 |
| TRAIN_NOT_ADMITTED | 0 | 0 | 0 |
| TARGET_NOT_TRAIN_DESTINATION | 0 | 17 | 0 |
| ROUTE_SPANS_ENTRY_TO_EXIT | 0 | 3 | 0 |
| NO_FREE_PATH | 0 | 0 | 0 |
| ORIGIN_NOT_AT_TRAIN_POSITION | 0 | 118 | 0 |
| NO_ROUTE_HELD | 0 | 0 | 0 |
| TRAIN_ON_RESERVED_BLOCK | 0 | 0 | 0 |
| DUPLICATE_ACTION_THIS_TICK | 0 | 0 | 0 |
| ACTION_LIMIT_EXCEEDED | 0 | 29 | 0 |

## Apply Failures

| Apply Failure Code | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| ALL_PATHS_BLOCKED | 0 | 56 | 0 |
| CONFLICT | 0 | 0 | 0 |
| NO_ROUTE_EXISTS | 0 | 0 | 0 |
| APPROVE_REJECTED | 0 | 0 | 0 |
| CAP_EXCEEDED_APPLY | 0 | 130 | 0 |
| ORIGIN_NOT_CONTIGUOUS | 0 | 25 | 0 |
| CONDITION_FAILED | 0 | 0 | 0 |
| DROPPED_INVALID | 0 | 0 | 0 |
| GEOMETRICALLY_IMPOSSIBLE | 0 | 7 | 0 |

## Author Attribution

> Must be `{LLM: n, everything else: 0}` for a passing LLM arm.

| Action Author | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| LLM | 0 | 927 | 0 |
| TIMEOUT_NOOP | 0 | 0 | 0 |
| RULE_BASED | 380 | 0 | 0 |
| RULE_FALLBACK | 0 | 87 | 0 |
| SAFETY_NET | 0 | 0 | 0 |
| OPERATOR | 0 | 0 | 0 |

## Latency

| Arm | Tick period ms | p50 latency ms | p95 latency ms | Max latency ms | Deadline misses |
|---|---|---|---|---|---|
| RULE_BASED | 0 | — | — | — | 0 |
| LLM_TOOL_CALLING | 0 | 1087 | 30004 | 30025 | 0 |
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
| RULE_BASED | rule-based | 0.0 | 0 | 3 | 3 | unset | 30 |  | 10 | 10 | ✅ PASS | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | n/a | — | — | yes | 0 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 3 | 3 | unset | 30 | REVISED | 10 | 0 | ❌ FAIL | 0.789 | 0.219 | 0.396 | 0.000 | 0.000 | n/a | 1120 | 7710 | no | 34 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 30 | REVISED | 10 | 0 | ❌ FAIL | 0.749 | 0.215 | 0.447 | 0.000 | 0.000 | n/a | 971 | 30004 | no | 47 |
| LLM_CONSTRAINED_JSON | rule-based | 0.0 | 0 | 0 | 0 | unset | 30 | unspecified | 0 | 0 | ❌ FAIL | 0.000 | n/a | 0.000 | 0.000 | 0.000 | n/a | — | — | yes | 0 |

### Railway Outcomes

| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | Runs | Journeys completed | Trains entered | Trains exited (authoritative) | Max concurrent | Block transitions | Conflicts | Failed reservations |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | rule-based | 0.0 | 0 | 3 | 3 | unset | 30 |  | 10 | 110 | 150 | 110 | 2 | 250 | 0 | 0 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 3 | 3 | unset | 30 | REVISED | 10 | 70 | 137 | 77 | 2 | 195 | 3 | 1 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 30 | REVISED | 10 | 51 | 137 | 65 | 2 | 168 | 5 | 2 |
| LLM_CONSTRAINED_JSON | rule-based | 0.0 | 0 | 0 | 0 | unset | 30 | unspecified | 0 | n/a | n/a | n/a | n/a | n/a | n/a | n/a |

