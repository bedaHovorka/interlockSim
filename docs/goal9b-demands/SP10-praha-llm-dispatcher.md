# SP10 — LLM dispatcher on Praha hlavní nádraží: measurement and demands on Goal 9B

**Issues:** bedaHovorka/interlockSim#1157 (Goal 1B SP10) · the Praha pass-rate row of #1300 · the
stale-origin count of #1355 · **Date:** 2026-11-17

This is the SP10 campaign of the "Replan — waves 8+" plan on #1147 (Order 10). It runs the Praha
`Station` with the LLM dispatcher (`prahaHlavniNadraziAI`) against the rule-based control arm
(`prahaHlavniNadrazi`), 10 + 10 runs of 600 s on 10 validated seeds. The measurement is manual and
non-gating: nothing here runs in `build`, `test`, `integrationTest` or CI.

## Machine state

- **Code:** PR #1403 (#1385) head, file tree identical to `9808badf`; stack `goal-1B` b986e4f2 →
  #1399 (merged as 0f957df8 during the campaign, tree identical to the tested 687a136c) → #1403.
  **PR #1403 was not merged at measurement time.** The plan's precondition "#1390 and #1387 merged"
  was waived by owner ruling on 2026-11-17 (run the campaign on top of #1399 + #1385).
- **Jar:** one frozen `:desktop-ui:shadowJar` for every run, sha256
  `bc266fc87205b6d439cc7cd6d03efdcd5f53c589311e9d7977a7950ac1a2e829`.
- **Model:** `qwen2.5:7b-instruct` (Q4_K_M, digest `845dbda0ea48`), Ollama 100 % GPU, `num_ctx`
  16 384, NVIDIA RTX PRO 5000 Blackwell Laptop GPU, enforced power limit 95 W, AC power, generation
  speed median 85 tok/s.
- **Gate on the code:** the #1403 navigation gate (full gate, `:core:allTests`, both parity tests,
  `:core:heavyTest` 2005/0, `:dispatcher-agent:heavyTest` 3020/0) and a flat #895 bracket.

## Method

`aiSweep` has no seed axis, so every run is a direct CLI run, wrapped in `timeout 900`:

```
java -ea -Dlogback.configurationFile=logback-sweep.xml \
  -Dinterlocksim.dispatcher.runsRoot=<root> -Dinterlocksim.dispatcher.runId=praha-<arm>-s<seed> \
  -Dinterlocksim.dispatcher.model=qwen2.5:7b-instruct -Dinterlocksim.dispatcher.temperature=0.28 \
  -Dinterlocksim.dispatcher.tickPeriodMs=0 -Dinterlocksim.dispatcher.historyN=3 \
  -Dinterlocksim.dispatcher.maxActionsPerTick=3 \
  -jar interlockSim.jar example prahaHlavniNadraziAI 600 <seed>
```

The control arm runs the same command with `example prahaHlavniNadrazi`. The axes are the SP10.1
values. Traffic is the SP10.2 low-traffic generator (at most 4 concurrent trains of capacity 9,
8 trains per run). Each run writes one `DispatcherRunSnapshot` JSON; without a sweep there is no
`report.md`, so the numbers below are summed from the JSONs and the run logs.
`RunReportAggregator.runPassed` is the pass rule of the #1300 row.

**Seed rule (narrowed; traffic-simulation-expert, arbiter, 2026-11-17; supersedes the "class A = 0"
wording of the #1147 replan; the owner may overrule).** The replan's rule accepts a seed when the control arm has 8 entered,
8 exited and class A = 0. #1387 split class A into *blind* (the own exit was never offered and no
platform toward it was available) and *fallback* (every platform toward the own exit was busy at one
of the train's picks — the owner's fallback clause). The traffic-simulation-expert ruled that only
**blind class A = 0** rejects a seed; fallback A and class B are recorded per seed and per arm. The
classes are read from the `WRONG_OUT: … class A (… blind …)` / `(… fallback …)` / `class B` log lines
of `ShuntingLoop`.

**Seed validation:** seeds 1366, 0, 1, … in that order until 10 were accepted. **10 accepted,
0 rejected:** 1366, 0–8. Blind A = 0 on all ten; fallback A = 8 trains over the ten (seed 1366: 1, 5: 3, 6: 1, 7: 2, 8: 1).
The campaign's control runs reproduced the validation runs exactly.

**Smoke:** 1 + 1 on seed 1366 before the campaign: both arms 8/8, natural completion, no new
engine fault.

## Deviation: a measurement proxy kept one runaway generation alive

To record the real `prompt_eval_count` (the app does not log it), the LLM runs went through a
pass-through HTTP proxy in front of Ollama. At 21:11:23, during the seed-3 LLM run, one request
**ran away** (the #1340 pattern): 45 009 generated tokens with context shifts. Normally the
dispatcher's 30 s inference deadline closes the connection and Ollama stops generating. The proxy did
not pass the disconnect upstream, so the generation ran for 10 minutes (until the proxy's own 600 s
upstream timeout) and blocked Ollama's only slot. That spoiled the end of the seed-3 run, all of
seed 4 and the start of seed 5 (every cycle hit the 30 s deadline). **The LLM runs of seeds 3, 4
and 5 were run again without the proxy on the same jar; the tables use the reruns.** The rule
was fixed before the outcomes were looked at: every run touched by the blocked Ollama slot is
rerun. The discarded originals: seed 3 exited 6 trains, seed 4 exited 5 (actionable-tick rate 0.0),
seed 5 exited 8 (actionable-tick rate 0.417). The seed-5 rerun is the weakest LLM run of the final
set, so the substitution did not favour the LLM arm. A full rerun is not needed: the jar is the
same, the proxy only carries the traffic, the other seven LLM runs never reached the deadline, and
their p50 latencies (417–463 ms) match the reruns (435–545 ms). The Ollama server log shows exactly
one truncated (runaway) generation between 20:30 and 22:00, which covers the campaign and the
reruns.

**Rule for later campaigns:** do not put a measurement layer between the dispatcher and Ollama
unless it passes the client's disconnect upstream. Better, record `prompt_eval_count` in the
dispatcher itself (in the run JSON), so no proxy is needed.

## Results

### Per arm (10 runs each, 600 s, seeds 1366 and 0–8)

| | Control (`prahaHlavniNadrazi`) | LLM (`prahaHlavniNadraziAI`) |
|---|---|---|
| **Pass rate (`runPassed`)** | **10/10** | **0/10** |
| Natural completion | 10 | 10 |
| C7 clean (no rule-fallback-authored action) | 10 | 0 |
| Trains entered / exited (sum) | 80 / 80 | 80 / 79 |
| Exited at the timetable exit (sum) | 72 | 69 |
| Class A blind / fallback / class B (sum) | 0 / 8 / 0 | 2 / 8 / 0 |
| Block transitions, median | 16 | 18.5 |
| Max concurrent trains | 4 | 4 |
| Failed reservations / conflicts / unresolved at end | 0 / 0 / 0 | 1 / 0 / 0 |
| `SimulationStalled`, FATAL sim exceptions, SI-5 WARN lines | 0, 0, 0 | 0, 0, 0 |
| `reservePath: refused` summary lines | 0 | 7 |

The one LLM train that did not exit (seed 3, Train #8) held its exit route when the run ended at
600 s: an end-of-run effect, not a stall. **No stall and no `SimulationStalled` in either arm, so
there is no tool-call sequence to record.**

### LLM arm detail (10 runs)

| Measure | Value |
|---|---|
| Ticks | 930: `LLM_ACTIONS` 207, `LLM_NO_OP` 549, `LLM_SILENT_ALL_MOVING` 100, `LLM_SILENT_NONACTIONABLE` 7, `RULE_FALLBACK` 67 |
| LLM success rate, median | 0.916 (actionable-tick rate median 0.919; one run below the 0.8 of `runPassed`: seed 7, 0.795) |
| Inference latency | p50 median 438 ms, p95 median 1 795 ms, max 30 053 ms. The two cycles at the 30 s deadline (30 053 and 30 051 ms) are in the no-proxy reruns of seeds 3 and 4; they were not runaways (no truncated generation), and their cause is not recorded |
| Prompt size (`prompt_eval_count`, 1 470 calls of the 7 kept proxied runs) | min 6 656, median 6 828, p95 7 377, max 8 651 tokens, of the 16 384 window. The three reruns have no `prompt_eval_count` |
| Wall time per run | LLM 303–316 s; control 1–11 s |
| Emitted actions | route requests 335 (`RequestRoute` 170 + `request_route` 165: the run JSON counts the two spellings separately), train approvals 137 (`ApproveTrain` 108 + `approve_train` 29), fallback `ReservePath` 73. By author: LLM 450, rule fallback 95 of 545 (17 %) |
| Rejections by code | `TARGET_NOT_TRAIN_DESTINATION` 86, `TARGET_NOT_NEXT_SECTION` 51, `TRAIN_ALREADY_ACTIVE` 28, `TRAIN_NOT_ADMITTED` 13, `ENDPOINT_IS_BLOCK_ID` 7, `ROUTE_SPANS_ENTRY_TO_EXIT` 5, `ACTION_LIMIT_EXCEEDED` 2, `ORIGIN_NOT_AT_TRAIN_POSITION` 1, `UNKNOWN_TRAIN` 1 |
| Apply failures by code | `ORIGIN_NOT_CONTIGUOUS` 12, `NO_ROUTE_EXISTS` 5, `ALL_PATHS_BLOCKED` 2, `CAP_EXCEEDED_APPLY` 2, `DIVERGES_FROM_HELD_ROUTE` 1, `GEOMETRICALLY_IMPOSSIBLE` 1 |
| `staleOriginRefusalsAfterSilentCycle` (#1355) | 0. The cycles that can produce one are the 74 indexed silent non-idle cycles (67 `RULE_FALLBACK` + 7 `LLM_SILENT_NONACTIONABLE`; `LLM_SILENT_ALL_MOVING` never consults the fallback). They posted 95 fallback actions; all 12 `ORIGIN_NOT_CONTIGUOUS` were on other decisions |
| Koog `AIAgentMaxNumberOfIterationsReachedException` | 7 |
| Runaway generations | 1 (see the deviation above) |

### Per seed

| Seed | Control exited (at timetable exit) | Control fallback A | LLM exited (at timetable exit) | LLM blind A / fallback A | LLM `RULE_FALLBACK` ticks |
|---|---|---:|---|---|---:|
| 1366 | 8 (7) | 1 | 8 (6) | 0 / 2 | 4 |
| 0 | 8 (8) | 0 | 8 (8) | 0 / 0 | 11 |
| 1 | 8 (8) | 0 | 8 (7) | 1 / 0 | 9 |
| 2 | 8 (8) | 0 | 8 (8) | 0 / 0 | 5 |
| 3 | 8 (8) | 0 | 7 (7) | 0 / 0 | 7 |
| 4 | 8 (8) | 0 | 8 (8) | 0 / 0 | 4 |
| 5 | 8 (5) | 3 | 8 (5) | 1 / 2 | 7 |
| 6 | 8 (7) | 1 | 8 (7) | 0 / 1 | 7 |
| 7 | 8 (6) | 2 | 8 (6) | 0 / 2 | 8 |
| 8 | 8 (7) | 1 | 8 (7) | 0 / 1 | 5 |

### #895 bracket before and after the campaign code

Rule-based `aiSweep`, 10 × 600 s per example, identical before (687a136c) and after (9808badf):
shuntingLoop 150 / 150 / 150 journeys / entered / exited, 300 block transitions, 0 failed
reservations; cervenyUjezd 190 / 190 / 190, 380 transitions, 10 failed reservations
(`ALL_PATHS_BLOCKED`).

## What the numbers say

1. **The LLM arm no longer stalls on Praha** — at this traffic (at most 4 concurrent trains,
   8 per run), and with the rule fallback authoring 95 of the 545 actions (17 %): the run that does
   not stall is the LLM and the fallback together, not the LLM alone. The #1378 acceptance run
   (2026-11-08) ended `TERMINATED_EARLY` at 285 s on #1390. Here all 10 LLM runs complete naturally
   and move 79 of 80 trains. Destination quality is close to the control arm: 69 vs 72 trains leave by their timetable
   exit.
2. **The pass rate is 0/10 for one reason: C7.** Every LLM run has rule-fallback-authored actions
   (67 `RULE_FALLBACK` ticks, 4–11 per run). This is the same blocker as on Červený Újezd (#1155:
   0/10 in all three campaigns, C7 never clean). The other `runPassed` terms hold in 8 of 10 runs
   (9 of 10 runs; seed 7 falls just under the 0.8 actionable-tick rate with 0.795).
3. **Two class-A trains that the log labels "blind" in the LLM arm (seeds 1 and 5), none in the
   control arm.** The LLM does see the #1387 pick: the NEXT SECTION line comes from
   `chosenTargetName()`, that is `ReservationTargetPolicy.pick` with the exit-reachability
   preference. But the next-section guard (`NextSectionTargets.refusal`) accepts **any** candidate
   one section ahead, so nothing enforces that pick. The recorder is shared by both arms, so in the
   LLM arm the label means "the policy's pick was toward the exit (or the steer never applied), and
   the train still went elsewhere" — not "no platform was available". Example, seed 1, Train #8: it
   held a route to S3; at 398 s the rule fallback's `ReservePath` to S7 (the platform toward its exit
   N-Lib-2) was refused as divergent from the held route.
4. **No #944 fallback asymmetry was needed.** Class B = 0 in both arms, so no train waited past the
   #1299 bound in either arm; the known asymmetry (the control arm falls back to another exit after
   the wait bound, the LLM arm cannot) did not show in this campaign.
5. **Result classes moved with #1385.** `NO_ROUTE_EXISTS` (5) now covers the pairs whose candidates
   were all switch-illegal; before #1385 they were `GEOMETRICALLY_IMPOSSIBLE` (or `ALL_PATHS_BLOCKED`
   when a phantom was blocked). `OracleVerdict.DIVERGES_UNSAFE` counts `NO_ROUTE_EXISTS` but not GI,
   so the apply-failure split is **not comparable** with the #1378 acceptance run.

## Demands on Goal 9B

No stall was observed. The four categories #1157 names (deadlock avoidance, destination awareness,
capacity, prompt size) are mapped below, with the other findings.

- **C7 / fallback ownership.** A run cannot pass while silent or failed LLM cycles on a non-idle
  station hand the tick to the rule-based fallback. Goal 9B must decide whether the fallback is part
  of the dispatcher (and C7 measures something else) or the LLM must own every action.
- **Destination awareness.** `TARGET_NOT_TRAIN_DESTINATION` (86) and `TARGET_NOT_NEXT_SECTION` (51)
  are the two largest rejection codes; "blind" class A appears only in the LLM arm. The pick is
  already rendered, so the gap is in the guard and the per-candidate facts. Goal 9B needs one of:
  the next-section guard flags or refuses a candidate that is not toward the train's exit while one
  toward it is available, or the prompt shows exit-reachability for each candidate.
- **Recovery hints.** A `NO_ROUTE_EXISTS` denial carries no facing-signal hint (#1402).
- **Runaway generation (#1340).** One runaway in about 2 000 calls; with a client that does not
  cancel upstream it blocks the model for minutes. Any proxy, gateway or batching layer in front of
  Ollama must propagate the client's cancel, or set `num_predict`.
- **Prompt size is not the limit on Praha.** 6.7–8.7 k tokens of the 16 k window after the SP8 cap.
- **Capacity is not the limit at this traffic.** At most 4 concurrent trains of 9; 2
  `CAP_EXCEEDED_APPLY` in 930 ticks.
- **Deadlock avoidance:** not exercised at this traffic level (no stall, no conflict in either arm).
  A denser timetable is the next measurement, not part of SP10.
