# Issue #1338: `shuntingLoopAI 333` on `goal-1B` 988ea634 is not a regression

**Status:** ✅ **COMPLETE**: verdict recorded 2026-11-02. Issue #1338 (sub-issue of #1150, Goal 1B SP3).
Reviewed by the traffic-simulation-expert (read-only arbiter review), who upheld the verdict.

## Question

On 2026-11-01 the owner ran, on `goal-1B` at 988ea634:

```
java -jar desktop-ui/build/libs/interlockSim.jar exampleGui shuntingLoopAI 333 > run.log
```

The run showed six symptoms: route requests that end at a signal facing away from the train, two
stall warnings, a non-contiguous route origin, three 30 s LLM timeouts with an open circuit breaker,
`trainsEntered=7 trainsExited=5`, and SENSOR log lines with an empty semaphore name. The question:
did one of the last five commits cause them?

## Verdict

**No regression.** Every symptom also appears in runs made before all five commits, or follows from
one random event (a runaway Ollama generation) that also happened before them.

The five commits and why none of them changes this run:

| Commit | PR | Effect on vyhybna |
|---|---|---|
| 988ea634 | #1336 | Text of a compatibility reason and KDoc; the reason fires only on a station without a home signal. |
| eb06dbb8 | #1335 | `MultiTrainLoop.enableRealTimeSync` becomes public; `shuntingLoopAI` runs a `ShuntingLoop`. |
| 067b4abf | #1337 | Docs, a test and three KDoc lines in `GeneratedSemaphoreName` (unnamed-semaphore path only). |
| 1c53c97b | #1288 | SP8: station capacity now comes from the loop, still 2 on vyhybna. The vyhybna system prompt is pinned byte-identical by SHA-256 (`StationPromptGeneralisationTest`). The new queued-train refusal fires only on stations with more than two InOuts. |
| adac9c8a | #1283 | Names unnamed `RailSemaphore` elements on XML load; all six vyhybna semaphores already have names. |

Commit b2d18db1 (#1278, eight commits back) changes the reservation target policy; vyhybna has one
exit per side, so its picks are unchanged.

**No bisect was run.** The issue asked for one, with two reruns per step. It cannot help here: every
symptom is present in runs made before all five commits, and the runaway generation is a random
event (none of the seven baseline runs below had one) that two reruns per step cannot localise.

## Two facts the run log does not state

- **`333` is the end time, not a seed.** The GUI mode reads `exampleGui <name> <endTime>`. The
  example generates trains with seed 0, so the train sequence is the same in every run and only the
  LLM's answers differ. That is why older runs of the same command are a valid baseline.
- **`trainsEntered` counts generated trains, not admitted ones.** `ShuntingLoop.getTrainsEntered`
  counts in `ShuntingLoop.InnerGenerator.placeTrain`, when a train joins the entry queue. The run
  generated 7 trains, admitted 5 (one SENSOR entry line each) and saw 5 exit; trains #6 and #7 were
  still queued, because trains #4 and #5 held both capacity slots during the cascade described
  below. `Issue797StoppedAtAllowingSignalTest` pins the identity `exited + approved + queued =
  trainsEntered`. The KDoc of `RailwayOutcome.trainsEntered` said "admitted"; it now says what the counter counts.

## Baseline

Seven older run logs of the same command were on disk, all from before SP8 (1c53c97b) and #1283
(adac9c8a), checked with `git merge-base --is-ancestor`.

| Run (worktree, time) | Base on `goal-1B` | Geometrically impossible | Empty semaphore name | All paths blocked | Non-contiguous origin | LLM timeouts | Stall warnings | Ticks |
|---|---|---|---|---|---|---|---|---|
| `fix/issue-1272`, 10-31 14:27 | e9215f69 | 5 | 6 | 4 | 0 | 0 | 0 | 162 |
| `fix/issue-1280`, 10-31 17:34 | dd94bb4b | 3 | 7 | 4 | 0 | 0 | 0 | 166 |
| `feat/issue-1276`, 11-01 07:00 | 1b76a7b8 | 3 | 7 | 2 | 0 | 0 | 0 | 113 |
| `feat/issue-673`, 11-01 07:40 | 1b76a7b8 | 4 | 6 | 3 | 0 | 0 | 0 | 107 |
| `feat/issue-1278`, 11-01 08:27 | 167e2da0 | 2 | 6 | 4 | 1 | 0 | 0 | 108 |
| `test/issue-1275`, 11-01 09:12 | b2d18db1 | 4 | 6 | 2 | 0 | 0 | 0 | 106 |
| `fix/issue-1279`, 11-01 11:07 | eab8e5b7 | 1 | 6 | 2 | 0 | 0 | 0 | 117 |
| **The run under study, 11-01 21:03** | 988ea634 | 5 | 5 | 2 | 1 | 3 | 2 | 75 |

Every run, old and new, ends with `journeysCompleted=5 trainsEntered=7 trainsExited=5`. The 46 run
snapshots in `build/reports/dispatcher-runs/llm_tool_calling/` from 2026-10-01 to 2026-11-01 show 1 to
6 geometrically impossible requests and 1 to 4 all-paths-blocked denials in almost every run. Only
the run under study has a circuit breaker that opened (twice).

## Per symptom

1. **Route ends facing away from the train (`A → doA1`, `B → doB1`, `B → doB2`).** Known behaviour
   with a pinned cause. The REVISED system prompt's worked example picks its endpoints
   alphabetically (`StationTopologySerializer`: the first InOut and the first signal), which renders
   on vyhybna as `request_route(fromEndpointName="A", toEndpointName="doA1")`. That is exactly a
   route the kernel refuses: `RouteEndDirectionTest` pins `A → doA1`, `A → doA2`, `B → doB1` and
   `B → doB2` as ending at a signal that faces away. The example dates from f50fee1c (2026-08-28),
   the refusal rule from 2dc7b3c1 (2026-09-13). The model relapses because a refusal reason reaches
   only the next cycle's prompt and `historyN=0` keeps no history. #1301 owns the fix (worked
   examples taken from the station's structure, as a measured A/B prompt variant).
2. **`ReservePath: all paths blocked for Train #5 (zA → doB1)`.** Known behaviour. Train #4 held
   block kB, the platform block between `doA1` and `doB1`, so `zA → doB1` crosses an occupied block;
   `zA → doB2` takes the other platform track and was granted. The same pair is denied in the older
   runs. In this run the next attempt came 30 s later only because of symptom 4.
3. **Train #4's `zB → doA1` requests.** Not the LLM: both came from the rule-based fallback, at the
   timeouts of 21:02:07 and 21:02:37. The first was granted and moved Train #4 off block kB. The
   fallback decides on the observation taken when the cycle started, 30 s earlier; by the second
   timeout the train had moved, so that `zB → doA1` was refused as a non-contiguous origin. One
   denial, a consequence of symptom 4.
4. **One runaway Ollama generation, then a cascade.** See the next section.
5. **`trainsEntered=7 trainsExited=5` with `unresolvedAtRunEnd=0`.** The counter definition (see
   "Two facts" above). Identical in all 46 snapshots.
6. **`SENSOR: Train N detected at semaphore , signal=S80`.** The InOut's embedded entry semaphore
   has no name: it is no grid cell, so neither the XML reader nor #1273 names it. The line is in
   every older run log and in a log from 2026-02-02; the code dates from ba89a63c (#390). Fixed with
   #1339: the line now names the InOut, for example `detected at semaphore B (entry)`.

The simulation clock ran up to 22 s ahead of the wall clock late in the run. Three older runs show
the same (23 s, 10 s and 7 s), so eb06dbb8 did not change pacing. The GPU was not power-capped:
95 W limit, no software power cap, about 91 tokens per second during the runaway (unlike the
2026-10-24 case).

## The runaway generation

Ollama (`llama-server -c 16384 -np 1`, one slot) logged every dispatcher request of the run.

- Task 3913 started at 21:01:08 with a 2,575-token prompt. It generated 14,941 tokens at about 91
  tokens per second until a context shift ended it at 21:03:53 (`truncated = 1`).
- The client's 30 s cycle timeout fired at about 21:01:38, but Ollama logged no abort for task 3913.
  Its request stayed open and kept the only slot.
- The next three dispatcher requests queued behind it. Each timed out after 30 s
  (`aborting completion request due to client closing the connection`, `500 | 29.99s` at 21:02:07,
  21:02:37 and 21:03:37).

Cost: 5 rule-based fallback ticks, the circuit breaker opened twice (threshold 3), and Trains #4 and
#5 each stood 60 s at a STOP signal holding track they could not leave. The fallback decisions on
30-s-old observations produced symptoms 2 and 3.

It is not new. The Ollama journal since 2026-10-20 has six more generations ended by a context
shift: 17,643 tokens on 10-25; 44,240, 39,414, 24,269, 23,916 and 21,892 tokens on 10-31. On 10-31
at 13:31 one of them held the slot for 10 minutes: four requests aborted at about 30 s, and twenty
more completed in a burst afterwards, 1.5 to 9.5 minutes late. Client aborts per day: 3 (10-23),
4 (10-25), 7 (10-29), 73 (10-31), 3 (11-01). Normal replies are short: 99.8 % of the 46,010 completed
generations since 10-20 used at most 300 tokens.

Two facts in the code make one runaway this expensive:

- `KoogAgentPlanAdapter.attemptInference` bounds a cycle with `withTimeout`, and
  `KoogDispatchAgentImpl.decideAsync` runs the Koog agent in the same coroutine, yet the timeout
  did not close the `stream=false` request; the Koog client keeps its default 15-minute request and
  socket timeouts. Where the cancellation is lost is open (#1341).
- No request carries an output-token limit: Koog 1.1.1's `LLMParams.maxTokens` exists, but its
  Ollama client's options mapping drops it, so only `temperature` and `num_ctx` reach the wire
  (`GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md`, #1342).

## Follow-ups

- #1339: the empty semaphore name in the SENSOR line. Fixed together with this page.
- #1340 (sub-issue of #1147): one runaway Ollama generation stalls the control loop for minutes.
  - #1341: free the Ollama slot when the 30 s cycle timeout fires (primary).
  - #1342: cap the output tokens of every dispatcher request.
  - #1343: run the post-timeout fallback on the latest published snapshot, without awaiting a
    sim-thread capture (F1 ruling, constraint C3).
  - #1344: a rule-based batch dedupe by block. It changes a pinned invariant, so it needs golden
    re-validation and the #895 re-baseline, or a recorded drop.
- #1301: the evidence above for the worked-example cause of symptom 1.

## Evidence sources

- The run log posted on #1338 and its run snapshot
  (`20261101-210351-7de18c30-cde5-4377-8c5c-b85b03e5548e.json`, on the owner's machine).
- Seven older run logs of the same command in local worktrees, pinned to their `goal-1B` base
  commits.
- 46 run snapshots in `build/reports/dispatcher-runs/llm_tool_calling/` (2026-10-01 to 2026-11-01).
- The Ollama service journal (`journalctl -u ollama`) since 2026-10-20.
