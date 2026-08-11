# Dispatcher Reliability Report

Generated: 2026-08-11 01:50:21 UTC

## Arm Comparison

| Arm | Runs | Passing | Gate | LLM Success (median) | NoOp (median) | validAt1 (median) | correctAt1 (median) | p95 latency ms | C7 clean |
|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | 10 | 10 | ✅ PASS | 0.000 | 0.000 | 0.000 | n/a | 0 | yes |
| LLM_TOOL_CALLING | 90 | 32 | ❌ FAIL | 0.659 | 0.138 | 0.000 | n/a | 4213 | no |
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
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r02 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 340.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r03 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 350.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r07 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 164.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r08 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 350.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r02 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 354.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r03 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 352.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r08 | 3 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r02 | 6 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r03 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r04 | 3 | SimulationException[FATAL]: Path separator must be an end of this track at time 10.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r05 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 66.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r06 | 1 | SimulationException[FATAL]: Path separator must be an end of this track at time 62.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r07 | 4 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r09 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r10 | 3 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r02 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 336.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r03 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r04 | 3 | SimulationException[FATAL]: Path separator must be an end of this track at time 10.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r05 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 340.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r07 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 10.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r08 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 354.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r03 | 1 | SimulationException[FATAL]: Path separator must be an end of this track at time 14.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r04 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 352.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r08 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 10.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r09 | 3 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r10 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r01 | 1 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r02 | 4 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r03 | 4 | SimulationException[FATAL]: Path separator must be an end of this track at time 64.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r04 | 4 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r05 | 5 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r07 | 1 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r09 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 60.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r02 | 2 | SimulationException[FATAL]: Path separator must be an end of this track at time 6.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r04 | 3 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r05 | 1 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r06 | 3 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r07 | 4 | SimulationException[FATAL]: Path separator must be an end of this track at time 10.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r08 | 1 | SimulationException[FATAL]: Path separator must be an end of this track at time 60.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r09 | 3 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r10 | 1 | SimulationException[FATAL]: Path separator must be an end of this track at time 8.0 |

## Per-Run Detail

| Arm | RunId | Ticks | LLM_ACTIONS | LLM_NO_OP | LLM_REPAIRED | TIMEOUT_NOOP | RULE_FALLBACK | End cause | C7 clean | Fallback tick | FATAL exceptions |
|---|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r01 | 0 | 0 | 0 | 0 | 0 | 0 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r02 | 0 | 0 | 0 | 0 | 0 | 0 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r03 | 0 | 0 | 0 | 0 | 0 | 0 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r04 | 0 | 0 | 0 | 0 | 0 | 0 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r05 | 0 | 0 | 0 | 0 | 0 | 0 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r06 | 0 | 0 | 0 | 0 | 0 | 0 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r07 | 0 | 0 | 0 | 0 | 0 | 0 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r08 | 0 | 0 | 0 | 0 | 0 | 0 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r09 | 0 | 0 | 0 | 0 | 0 | 0 | NATURAL_COMPLETION | yes | - | 0 |
| RULE_BASED | sweep-ex-shuntingLoop_m-default_t-default_p-0_h-3_a-3_it-default_pv-default-r10 | 0 | 0 | 0 | 0 | 0 | 0 | NATURAL_COMPLETION | yes | - | 0 |
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
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r01 | 20 | 16 | 2 | 0 | 0 | 2 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r02 | 27 | 18 | 4 | 0 | 0 | 5 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r03 | 25 | 17 | 4 | 0 | 0 | 4 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r04 | 28 | 19 | 2 | 0 | 0 | 7 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r05 | 23 | 20 | 1 | 0 | 0 | 2 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r06 | 23 | 20 | 1 | 0 | 0 | 2 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r07 | 26 | 19 | 2 | 0 | 0 | 5 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r08 | 27 | 17 | 4 | 0 | 0 | 6 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r09 | 24 | 18 | 3 | 0 | 0 | 3 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-30_pv-REVISED-r10 | 25 | 18 | 2 | 0 | 0 | 5 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r01 | 27 | 16 | 7 | 0 | 0 | 4 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r02 | 27 | 17 | 5 | 0 | 0 | 5 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r03 | 27 | 17 | 4 | 0 | 0 | 6 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r04 | 26 | 18 | 3 | 0 | 0 | 5 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r05 | 26 | 18 | 3 | 0 | 0 | 5 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r06 | 20 | 14 | 2 | 0 | 0 | 4 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r07 | 23 | 17 | 3 | 0 | 0 | 3 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r08 | 26 | 17 | 4 | 0 | 0 | 5 | NATURAL_COMPLETION | no | - | 3 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r09 | 25 | 18 | 2 | 0 | 0 | 5 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-0_a-3_it-90_pv-REVISED-r10 | 26 | 16 | 6 | 0 | 0 | 4 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r01 | 11 | 1 | 2 | 0 | 0 | 8 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r02 | 29 | 11 | 4 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 6 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r03 | 30 | 12 | 5 | 0 | 0 | 13 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r04 | 26 | 10 | 4 | 0 | 0 | 12 | NATURAL_COMPLETION | no | - | 3 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r05 | 25 | 9 | 5 | 0 | 0 | 11 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r06 | 26 | 11 | 5 | 0 | 0 | 10 | NATURAL_COMPLETION | no | - | 1 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r07 | 30 | 11 | 7 | 0 | 0 | 12 | NATURAL_COMPLETION | no | - | 4 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r08 | 25 | 10 | 6 | 0 | 0 | 9 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r09 | 29 | 9 | 6 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.28_p-0_h-3_a-3_it-30_pv-REVISED-r10 | 29 | 8 | 7 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 3 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r01 | 24 | 19 | 2 | 0 | 0 | 3 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r02 | 24 | 19 | 2 | 0 | 0 | 3 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r03 | 24 | 19 | 2 | 0 | 0 | 3 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r04 | 24 | 19 | 2 | 0 | 0 | 3 | NATURAL_COMPLETION | yes | - | 3 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r05 | 22 | 18 | 2 | 0 | 0 | 2 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r06 | 26 | 18 | 3 | 0 | 0 | 5 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r07 | 25 | 19 | 3 | 0 | 0 | 3 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r08 | 27 | 18 | 4 | 0 | 0 | 5 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r09 | 20 | 13 | 3 | 0 | 0 | 4 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-30_pv-REVISED-r10 | 25 | 18 | 4 | 0 | 0 | 3 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r01 | 26 | 19 | 4 | 0 | 0 | 3 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r02 | 19 | 13 | 3 | 0 | 0 | 3 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r03 | 23 | 19 | 2 | 0 | 0 | 2 | NATURAL_COMPLETION | yes | - | 1 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r04 | 28 | 16 | 5 | 0 | 0 | 7 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r05 | 23 | 18 | 3 | 0 | 0 | 2 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r06 | 27 | 16 | 7 | 0 | 0 | 4 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r07 | 23 | 19 | 2 | 0 | 0 | 2 | NATURAL_COMPLETION | yes | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r08 | 26 | 18 | 4 | 0 | 0 | 4 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r09 | 25 | 18 | 3 | 0 | 0 | 4 | NATURAL_COMPLETION | yes | - | 3 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-0_a-3_it-90_pv-REVISED-r10 | 23 | 17 | 2 | 0 | 0 | 4 | NATURAL_COMPLETION | yes | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r01 | 26 | 12 | 4 | 0 | 0 | 10 | NATURAL_COMPLETION | no | - | 1 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r02 | 28 | 11 | 3 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 4 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r03 | 26 | 12 | 3 | 0 | 0 | 11 | NATURAL_COMPLETION | no | - | 4 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r04 | 31 | 12 | 5 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 4 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r05 | 29 | 11 | 4 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 5 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r06 | 26 | 9 | 7 | 0 | 0 | 10 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r07 | 29 | 12 | 5 | 0 | 0 | 12 | NATURAL_COMPLETION | no | - | 1 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r08 | 28 | 14 | 2 | 0 | 0 | 12 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r09 | 25 | 10 | 8 | 0 | 0 | 7 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-30_pv-REVISED-r10 | 24 | 13 | 5 | 0 | 0 | 6 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r01 | 28 | 12 | 7 | 0 | 0 | 9 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r02 | 27 | 10 | 4 | 0 | 0 | 13 | NATURAL_COMPLETION | no | - | 2 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r03 | 26 | 12 | 6 | 0 | 0 | 8 | NATURAL_COMPLETION | no | - | 0 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r04 | 26 | 13 | 3 | 0 | 0 | 10 | NATURAL_COMPLETION | no | - | 3 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r05 | 28 | 11 | 3 | 0 | 0 | 14 | NATURAL_COMPLETION | no | - | 1 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r06 | 28 | 10 | 3 | 0 | 0 | 15 | NATURAL_COMPLETION | no | - | 3 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r07 | 25 | 8 | 4 | 0 | 0 | 13 | NATURAL_COMPLETION | no | - | 4 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r08 | 26 | 9 | 7 | 0 | 0 | 10 | NATURAL_COMPLETION | no | - | 1 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r09 | 28 | 12 | 3 | 0 | 0 | 13 | NATURAL_COMPLETION | no | - | 3 |
| LLM_TOOL_CALLING | sweep-ex-shuntingLoopAI_m-qwen2.5-7b-instruct_t-0.5_p-0_h-3_a-3_it-90_pv-REVISED-r10 | 27 | 14 | 3 | 0 | 0 | 10 | NATURAL_COMPLETION | no | - | 1 |

## Failure Modes (Rejection Codes)

> Read Failure Modes together with Apply Failures: a **high noOpRate with low ALL_PATHS_BLOCKED** is correct restraint; a **low noOpRate with high ALL_PATHS_BLOCKED** is thrashing.

| Rejection Code | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| UNKNOWN_TRAIN | 0 | 100 | 0 |
| BLANK_ARGUMENT | 0 | 6 | 0 |
| UNKNOWN_ENDPOINT | 0 | 0 | 0 |
| ENDPOINT_IS_BLOCK_ID | 0 | 31 | 0 |
| TRAIN_ALREADY_ACTIVE | 0 | 156 | 0 |
| TRAIN_NOT_QUEUED | 0 | 0 | 0 |
| CAPACITY_FULL | 0 | 0 | 0 |
| TRAIN_ALREADY_EXITED | 0 | 0 | 0 |
| ROUTE_ALREADY_HELD_TO_SAME_TARGET | 0 | 0 | 0 |
| ROUTE_HELD_TO_DIFFERENT_TARGET | 0 | 0 | 0 |
| TRAIN_NOT_ADMITTED | 0 | 0 | 0 |
| TARGET_NOT_TRAIN_DESTINATION | 0 | 72 | 0 |
| NO_FREE_PATH | 0 | 0 | 0 |
| ORIGIN_NOT_AT_TRAIN_POSITION | 0 | 393 | 0 |
| NO_ROUTE_HELD | 0 | 0 | 0 |
| TRAIN_ON_RESERVED_BLOCK | 0 | 0 | 0 |
| DUPLICATE_ACTION_THIS_TICK | 0 | 0 | 0 |
| ACTION_LIMIT_EXCEEDED | 0 | 119 | 0 |

## Apply Failures

| Apply Failure Code | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| ALL_PATHS_BLOCKED | 0 | 184 | 0 |
| CONFLICT | 0 | 0 | 0 |
| NO_ROUTE_EXISTS | 0 | 0 | 0 |
| APPROVE_REJECTED | 0 | 0 | 0 |
| CAP_EXCEEDED_APPLY | 91 | 187 | 0 |
| ORIGIN_NOT_CONTIGUOUS | 0 | 36 | 0 |
| DROPPED_INVALID | 0 | 0 | 0 |

## Author Attribution

> Must be `{LLM: n, everything else: 0}` for a passing LLM arm.

| Action Author | RULE_BASED | LLM_TOOL_CALLING | LLM_CONSTRAINED_JSON |
|---|---|---|---|
| LLM | 0 | 2910 | 0 |
| TIMEOUT_NOOP | 0 | 0 | 0 |
| RULE_BASED | 762 | 0 | 0 |
| RULE_FALLBACK | 0 | 495 | 0 |
| SAFETY_NET | 0 | 0 | 0 |
| OPERATOR | 0 | 0 | 0 |

## Latency

| Arm | Tick period ms | p50 latency ms | p95 latency ms | Max latency ms | Deadline misses |
|---|---|---|---|---|---|
| RULE_BASED | 0 | 0 | 0 | 0 | 0 |
| LLM_TOOL_CALLING | 0 | 895 | 4213 | 90004 | 0 |
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
| RULE_BASED | rule-based | 0.0 | 0 | 3 | 3 | unset | 30 |  | 10 | 10 | ✅ PASS | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | n/a | 0 | 0 | yes | 0 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 3 | 3 | unset | 90 | REVISED | 10 | 0 | ❌ FAIL | 0.576 | 0.250 | 0.132 | 0.000 | 0.000 | n/a | 580 | 3431 | no | 115 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 0 | 3 | unset | 30 | REVISED | 10 | 8 | ❌ FAIL | 0.827 | 0.303 | 0.090 | 0.000 | 0.000 | n/a | 1041 | 3920 | no | 41 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 90 | REVISED | 10 | 0 | ❌ FAIL | 0.534 | 0.178 | 0.163 | 0.000 | 0.000 | n/a | 566 | 3159 | no | 128 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 0 | 3 | unset | 30 | REVISED | 10 | 8 | ❌ FAIL | 0.875 | 0.279 | 0.103 | 0.000 | 0.000 | n/a | 1049 | 3277 | no | 34 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 0 | 3 | unset | 90 | REVISED | 10 | 8 | ❌ FAIL | 0.849 | 0.257 | 0.142 | 0.000 | 0.000 | n/a | 1064 | 3364 | no | 35 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 0 | 3 | unset | 90 | REVISED | 10 | 8 | ❌ FAIL | 0.808 | 0.293 | 0.139 | 0.000 | 0.000 | n/a | 1034 | 3911 | no | 46 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 3 | 3 | unset | 30 | REVISED | 10 | 0 | ❌ FAIL | 0.582 | 0.273 | 0.158 | 0.000 | 0.000 | n/a | 587 | 2843 | no | 110 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 30 | REVISED | 10 | 0 | ❌ FAIL | 0.549 | 0.233 | 0.196 | 0.000 | 0.000 | n/a | 635 | 3161 | no | 117 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 90 | BASELINE | 10 | 0 | ❌ FAIL | 0.543 | 0.253 | 0.099 | 0.000 | 0.000 | n/a | 587 | 3722 | no | 124 |
| LLM_CONSTRAINED_JSON | rule-based | 0.0 | 0 | 0 | 0 | unset | 30 | unspecified | 0 | 0 | ❌ FAIL | 0.000 | n/a | 0.000 | 0.000 | 0.000 | n/a | 0 | 0 | yes | 0 |

### Railway Outcomes

| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | Runs | Journeys completed | Trains entered | Trains exited (authoritative) | Max concurrent | Block transitions | Conflicts | Failed reservations |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RULE_BASED | rule-based | 0.0 | 0 | 3 | 3 | unset | 30 |  | 10 | 109 | 150 | 109 | 2 | 328 | 0 | 4 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 3 | 3 | unset | 90 | REVISED | 10 | 82 | 150 | 79 | 2 | 165 | 0 | 1 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 0 | 3 | unset | 30 | REVISED | 10 | 70 | 150 | 70 | 2 | 154 | 0 | 0 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 90 | REVISED | 10 | 75 | 150 | 70 | 2 | 150 | 4 | 4 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 0 | 3 | unset | 30 | REVISED | 10 | 69 | 150 | 68 | 2 | 148 | 0 | 0 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 0 | 3 | unset | 90 | REVISED | 10 | 70 | 150 | 68 | 2 | 144 | 1 | 0 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 0 | 3 | unset | 90 | REVISED | 10 | 67 | 150 | 67 | 2 | 141 | 0 | 0 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.5 | 0 | 3 | 3 | unset | 30 | REVISED | 10 | 68 | 150 | 64 | 2 | 137 | 3 | 1 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 30 | REVISED | 10 | 66 | 150 | 63 | 2 | 138 | 1 | 2 |
| LLM_TOOL_CALLING | qwen2.5:7b-instruct | 0.2800000011920929 | 0 | 3 | 3 | unset | 90 | BASELINE | 10 | 57 | 150 | 55 | 2 | 129 | 10 | 0 |
| LLM_CONSTRAINED_JSON | rule-based | 0.0 | 0 | 0 | 0 | unset | 30 | unspecified | 0 | n/a | n/a | n/a | n/a | n/a | n/a | n/a |

