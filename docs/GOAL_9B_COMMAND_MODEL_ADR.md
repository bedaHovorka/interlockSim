# Goal 9B command-model ADR

**Status:** Accepted 2026-11-27.

**Issues:** #1474 (part 1), #1475 (part 1b), #1486 (part 2). Parent: #1439. Goal: #1434.

**Reviewed by:**
- Part 1: kotlin-tech-lead, qa-engineer. Author: traffic-simulation-expert.
- Part 1b: railway-civil-engineer, agent-architect. Author: traffic-simulation-expert.
- Part 2: railway-civil-engineer, traffic-simulation-expert. Author: agent-architect.

The review record (the sign-off findings and how each was resolved) is on the issues above.

## Contents

- [Part 1 — threading, timing and replay (#1474)](#part-1--threading-timing-and-replay-1474)
  - [1. Fixed inputs (recorded, not reopened)](#1-fixed-inputs-recorded-not-reopened)
  - [2. Threads and the commit point](#2-threads-and-the-commit-point)
  - [3. Answers to the questions](#3-answers-to-the-questions)
  - [4. Timing, pacing, emergency and SEMI_AUTO](#4-timing-pacing-emergency-and-semi_auto)
  - [5. Paused-clock constraints restated (C1–C4)](#5-paused-clock-constraints-restated-c1c4)
  - [6. Replay](#6-replay)
  - [7. Stopping a run (kDisco engine rules)](#7-stopping-a-run-kdisco-engine-rules)
  - [8. Residual risks](#8-residual-risks)
  - [Named tests](#named-tests)
  - [Open points (each decided in a later leaf)](#open-points-each-decided-in-a-later-leaf)
- [Part 1b — pass-bar rule and counted automatics (#1475)](#part-1b--pass-bar-rule-and-counted-automatics-1475)
  - [1b.1 Fixed rules (word for word)](#1b1-fixed-rules-word-for-word)
  - [1b.2 Counted automatics (each one breaks a clean run)](#1b2-counted-automatics-each-one-breaks-a-clean-run)
  - [1b.3 Excluded as interlocking behaviour (not counted, but logged in SP6)](#1b3-excluded-as-interlocking-behaviour-not-counted-but-logged-in-sp6)
  - [1b.4 `cancel_route` on the leftover route of an exited train](#1b4-cancel_route-on-the-leftover-route-of-an-exited-train)
  - [1b.5 Run-ending verdicts (the run failed)](#1b5-run-ending-verdicts-the-run-failed)
  - [1b.6 Rule for the SP6 reachability proof](#1b6-rule-for-the-sp6-reachability-proof)
  - [Named tests](#named-tests-1)
  - [Open points (decided elsewhere)](#open-points-decided-elsewhere)
- [Part 2 — command vocabulary and LLM tools (#1486)](#part-2--command-vocabulary-and-llm-tools-1486)
  - [2.1 Fixed inputs (recorded, not reopened)](#21-fixed-inputs-recorded-not-reopened)
  - [2.2 The route id (unblocks #1469, #1249)](#22-the-route-id-unblocks-1469-1249)
  - [2.3 The tool table, judged row by row](#23-the-tool-table-judged-row-by-row)
  - [2.4 Low-level vocabulary (`:core` `commonMain`, package for #1478)](#24-low-level-vocabulary-core-commonmain-package-for-1478)
  - [2.5 The hold on `TrainLifecyclePort` (unblocks #1483, #1484)](#25-the-hold-on-trainlifecycleport-unblocks-1483-1484)
  - [2.6 Emergency channel (unblocks #1484)](#26-emergency-channel-unblocks-1484)
  - [2.7 `DispatchAction`, `KoogAgentPlanAdapter` and the modes](#27-dispatchaction-koogagentplanadapter-and-the-modes)
  - [Named tests](#named-tests-2)
  - [Open points](#open-points)

## Part 1 — threading, timing and replay (#1474)

Leaf #1474 (SP5.1, parent #1439). Author role: traffic-simulation-expert. Reviewers: kotlin-tech-lead,
qa-engineer. This part follows the signed-off specs #1456, #1463, #1467, #1475, #1486 and #1488.
Where a later leaf decides a detail, the text says "Decided in #NNNN".

**Summary.**
- Five threads, two queues, one commit point.
- The simulation thread stamps every command with O (the tick it observed), N (the tick of the snapshot it was
  translated against) and D (the tick of the drain). It never waits for anyone.
- The staleness bound is **K = 2 control steps** (4.0 simulated s). The replay ring buffer holds
  **K + 1 = 3 snapshots**.
- The R9 emergency channel is exempt from `STALE_SNAPSHOT` under three conditions. It is drained first at every
  drain, and its post tick P (`emergencyPostedTick`) gives D − P ≤ 1 (#1450 ruling, §4).
- The control tick stays the retry clock, and the routing thread has no retry loop.
- The scripted CI source implements **`HighLevelCommandSource`**. The run record names the source in the run-level
  field **`commandSource`**.
- **atomicfu purity: passed** (§1, measured 2026-11-27). The low-level queue stays in `commonMain`, with no
  expect/actual.

---

### 1. Fixed inputs (recorded, not reopened)

**R8 as amended (word for word).** "R8 stands; no lock-step. The output of the routing thread is committed at the
first control-step drain D after it arrives. Per command the record holds seq, cycle id, O, N and D. All three ticks
are stamped on the simulation thread (O and N are snapshot stamps, D is the drain). Replay re-simulates, keeps
snapshots N..D, re-runs the translator inline at D, and needs no dispatcher thread. Inline arms (rule-based control
arms, `*Sync`, `:fast-sim`, CI twins) keep today's `barrierControlStep` timing exactly. Their proof is #895
byte-equal." O is the tick the LLM observed; N is the snapshot the translator used.

**Numbered requirements (word for word; the amendment #1486 asks for is marked).**

1. "A command whose D−N exceeds a staleness bound K control steps is refused with the typed denial `STALE_SNAPSHOT`
   (add it to #1463). K bounds the replay ring buffer."
   *Amendment (#1486 §2.6, TSE 1):* "Restricting commands on the R9 emergency channel (#1486 §2.6) are excepted:
   they read no snapshot, they are resolved on the simulation thread at D from the #1469 route record, they are not
   part of a routing batch, and their D−N is recorded, with D−N > 1 counted as an R9 latency breach."
2. "A tool result inside a cycle is a pre-check against snapshot N, not the commit verdict. Commit-time refusals
   reach the LLM in the next cycle's observation."
3. "One LLM cycle is handed to the routing thread as one batch and translated against one snapshot."
4. "The run record holds the distributions of D−N and D−O and the count of commit-time refusals by code. An unpaced
   headless run, at about 2000× real time, is expected to show large D−N." (The 2000× is an estimate from the
   Goal 1B wall times.)
5. "Headless LLM runs must run under the R10 pacing controller. It sets a rate from rolling p95 latency, has a
   minimum speed above zero, and never waits for a specific command. A late command is refused as stale, never
   waited for. This is the line that keeps pacing from becoming lock-step."
6. "R7 watchdog budget = pace × endTime × margin." (This is the budget of the engine-liveness trigger; the idle-work
   trigger of #1485 uses a bound in simulated seconds.)
7. "SEMI_AUTO rows are subject to K." (SEMI_AUTO = operator-approval mode; a row is one command waiting for
   approval.)
8. "R9 emergency latency is up to one control step; no `Process.reactivate` from another thread."

**How requirement 2 is read (settled).** #1463 defines the stage `PRE_CHECK` as "the validator or tool check
against snapshot O". A pre-check can run at two points:
- A tool runs inside the cycle, before N exists, so it checks against the cycle's snapshot, tick O.
- The translator checks again against N. Example: `AMBIGUOUS_TARGET` is recorded as `PRE_CHECK` with `tick` = N
  (#1486 amendment to #1463).

The requirement's point holds in both cases: a pre-check reads a snapshot, never live state, and it is never the
commit verdict. The word "N" in requirement 2 stays as written. It means "the snapshot of that stage".

**Author of the scripted source (ruling Q2).** The scripted CI source (#1479) stands in for the dispatcher thread,
so its commands carry author `LLM` and the clean-run rule is computed exactly as for an LLM run. The author set gets
no new value. The run record names the command source at run level (scripted, with the script name, in place of a
model tag; the field is in the #1476 schema), so a scripted run can never be counted in an LLM campaign.

**Thread rule.** The routing thread never calls a reservation, navigator or route-finder method. Its inputs are
only these:
- the planning snapshot, which is **`DispatchLoopSnapshot`** (#1467 §1; `PlanningSnapshot` may exist as a
  `typealias` in `ports`, never as a class);
- the #1471 `RouteTable`;
- the commands.

The translator reads the route table and never the #1488 ranking. It never breaks a tie by cost
(#1488 §6, #1486 §2.3).

**Module placement.** Command types (`HighLevelCommand`, `LowLevelCommand`), the low-level queue, the emergency
queue, the translator, the held SEMI_AUTO row, the planning snapshot and the route table live in `:core` commonMain.
- `ActionAuthor` **moves** from `:dispatcher-agent` (`dispatcher.agents.ActionAuthor`) to `:core` commonMain
  (#1486 §2.4). Its package is decided in #1478, together with the queue types.
- Only two things live in `:dispatcher-agent`: the routing thread that runs the translator, and the **high-level
  hand-off**. The hand-off is the cross-thread delivery of one batch from the dispatcher thread to the routing
  thread. That is all it does.
- Inline arms (`:fast-sim`, `*Sync`, the rule-based arm, the CI twins) use no hand-off and no high-level queue.
  They call the translator directly on the simulation thread, so `:fast-sim` needs only `:core`.
- A SEMI_AUTO row waits **already translated**, in a `:core` holder next to `SemiAutoApprovalGateway`
  (`sim/`, commonMain). It does not wait in the high-level hand-off. Approval moves the held low-level batch into
  the low-level queue.

**One commit point: the `:core` low-level queue replaces `ActuatorCommandQueue`.**
- Today the cross-thread queue is `dispatcher.ActuatorCommandQueue` in `:dispatcher-agent`. #1478 adds the new
  queue beside it.
- Until the old queue is removed, every arm drains exactly one of the two queues, never both. So each run still has
  one commit point.
- #1483 deletes `ActuatorCommandQueue` once the rule-based arm, its last producer, has moved. The actuator list of
  `Goal9C7ArchitectureTest` follows the rename in the same PR.

**atomicfu purity result.** The low-level queue does **not** become expect/actual.
- The worktree was a throwaway on `goal-9B`, created 2026-11-27 and removed after the run. A probe in `:core`
  commonMain, `AtomicfuProbe`, posts and drains batches atomically. It uses only `kotlinx.atomicfu.atomic`,
  `kotlinx.atomicfu.locks.SynchronizedObject` and `kotlinx.atomicfu.locks.synchronized`.
- Command: `./gradlew :core:checkCoreCommonMainPurity :core:compileKotlinLinuxX64 :core:compileKotlinJvm
  --rerun-tasks`, under the gate lock.
- Result: `checkCoreCommonMainPurity` printed "commonMain purity check passed - no JVM-only code found.", both compiles passed, and the build ended BUILD SUCCESSFUL in 28 s. The probe compiled on JVM and linuxX64.
- `kotlinx.atomicfu` (`atomicfuVersion` 0.26.0) is already a `:core` commonMain dependency. Today
  `DefaultDispatcherPreferenceStore`, `SemiAutoApprovalGateway`, `ShuntingLoop` and five other commonMain files
  use it. #1478 needs no new dependency.

**Stop rule.** No `Simulation.stop()` from another thread (kDisco F4). A run stops by thread interrupt, by
run-until-time, or by cancelling the coroutine that runs `Simulation.run`. §7 says which stop the watchdog uses.

**Release.** Every release goes through `releasePathDetailed` (1B-SP12.2) or its per-route form. A release is
always approach-locked and always carried out on the simulation thread, never through `releasePath`.
- The V1 by-train `cancel_route` maps to `ReleaseTrainRoutes`, which is today's
  `RouteReleaser.releasePathDetailed`.
- `cancel_route` by route id, the cancel half of `reroute` and `EmergencyCancel` go through the new per-route,
  approach-locked release that #1469 owns (#1486 §2.1, §2.4).

**Paused-clock ruling.** C1–C4 of `docs/GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md` bind the routing thread and the
pacing controller (§5). The paused-clock regime itself is not used in Goal 9B LLM runs: R10 forbids a pause for one
decision, and the SP6 pacing controller replaces it.

---

### 2. Threads and the commit point

| Thread | Runs | May touch live state | Module |
|---|---|---|---|
| Simulation thread | kDisco `run`; `ShuntingLoop.iteration`; publication; inline translator; low-level and emergency drain; the SP3.10 re-check; the idle-work watchdog trigger | yes, and it is the only one | `:core` |
| Dispatcher thread | one LLM cycle per `SnapshotSignal` wake (`AgentLoopDriver.runCycle`, Koog); tool pre-checks at O | no | `:dispatcher-agent` |
| Routing thread | the translator over one batch and one snapshot; posts one low-level batch | no | `:dispatcher-agent` (the code itself is in `:core`) |
| Watchdog thread | the engine-liveness trigger (wall clock) | no; it can only end the run | decided in #1485 |
| GUI EDT | SEMI_AUTO rows, approval, MANUAL commands | no | `:desktop-ui` |

**Publication (#1467 P1–P5, adopted).** `ShuntingLoop.iteration` runs these steps in order:
1. prune;
2. `expireStaleReservations`;
3. `snapshotCaptureHook` (`captureSnapshot`);
4. one `@Volatile` write of `latestObservation` (`tick = ++publishedTickCount`);
5. shell-local bookkeeping;
6. `controlStepListener.onControlStep()`, which drains.

The rules that follow from this order:
- Nothing between steps 4 and 6 changes network state (P3). So live state at the start of the drain equals
  snapshot N = D.
- Off-thread readers use only `getLatestObservation()` or `latestDispatchLoopSnapshot()` (P2).
- The guarantees hold only under `runBlocking` (P5), which is also engine rule (c) in §4.
- The control step is 2.0 simulated s (`ShuntingLoopControlPeriodTest`).

**Stamps.**
- **O** is the `tick` of the `DispatchLoopSnapshot` the cycle read.
- **N** is the `tick` of the snapshot the translator read. The routing thread takes the latest published snapshot
  when it receives the batch, so N ≥ O.
- **D** is the tick of the drain that takes the batch.
- **seq** is assigned by the simulation thread at drain, in FIFO drain order.
- **P** (`emergencyPostedTick`, emergency commands only) is the tick the emergency queue stamps at `post`, read
  from `latestObservation` under the queue lock (§4).

O, N, D and seq are written on the simulation thread. P is the simulation thread's published tick, copied by the
emergency queue under its lock. The dispatcher cycle starts only after a `SnapshotSignal` wake,
so every batch has O ≥ 1. An arm that has the translator but no `snapshotCaptureHook` would plan on
`SimulationSnapshot.EMPTY`. That is a wiring error and fails at wiring time (#1467 §3).

**Commit.** At D the simulation thread first drains the emergency queue (§4), then takes each low-level command of
the main queue in seq order:
- If D−N > K, it refuses the command with `STALE_SNAPSHOT`: class REPLAN, stage COMMIT, payload `Stale(n, d, k)`.
  The run record gets **one denial event per low-level command of the stale batch** (this closes the #1463 open
  point).
- Otherwise it runs the SP3.10 re-check. That check is the four-argument
  `NetworkActuatorPort.requestRoute(..., precondition)` with its step at the top of
  `CandidateCommitter.registerAndFinalizeCandidate` (#1456 Q7). The command is then either carried out or refused
  with a #1463 `DenialCode`.
- A refused precondition is `PreconditionChanged`, which carries N and D.
- Under P4, a command in drain D sees live state after the earlier commands of the same drain.

There is no batch atomicity. An earlier command of a batch is not rolled back when a later one is refused, because
rolling it back would need a re-plan inside the drain, and that is lock-step.

**Batch rule (requirement 3, carried into the queues).** The translator output of one batch is posted to the
low-level queue in one call, and a drain never splits a batch. #1478 implements this with the atomicfu lock
measured above. The emergency queue is separate and holds no batches (§4).

**Inline arms.** These are the rule-based arm, `*Sync`, `:fast-sim` and the CI twins.
- The translator runs on the simulation thread inside `controlStepListener`, after step 4 and before the drain. So
  O = N = D and D−N = 0.
- The rule-based arm keeps `barrierControlStep = true` in `ExampleRegistry.wireDispatcherAgent`, with the
  `barrierSemaphore` acquire before `applier.onControlStep()`. Its high-level commands are translated inline after
  the acquire.
- The proof is the #895 bracket, byte-equal, plus `ControlArmGoldenOutcomeTest`.
- This lock-step is allowed only because these arms are not LLM arms (R8).

---

### 3. Answers to the questions

#### 3.1 K and the ring buffer

**K = 2 control steps (4.0 simulated s).** A command is refused when D−N > 2 (`StaleSnapshotTypeTest`: D−N = 2
commits, D−N = 3 refuses).

Why 2:
- D−N does not contain the LLM latency, which is in D−O. It holds only the translator time plus the wait for the
  next drain.
- The usual case is D−N = 1. D−N = 0 happens when a command is posted before step 6 of tick N (#1467 §4).
- K = 2 allows one more missed drain.
- Over 4 s a train's speed can change by up to 16 m/s (`MAXIMAL_TRAIN_ACCELERATION` 4 m/s² for 4 s; #1467 §5).
  At 20 m/s it moves about 80 m (estimate). The commit-time re-check covers that. Past 2 steps, the translation
  itself (route key, chain) is no longer trusted.
- A larger K would only hide an unpaced run, which requirement 5 forbids.
- D−O is not bounded by K. It is recorded (requirement 4), and the re-check and the R10 pacing controller deal
  with it.

**Ring buffer: K + 1 = 3 snapshots, in the replayer only.**
- Replay at tick D needs snapshot N, with D−N ≤ K, so it keeps the snapshots from D−K to D.
- A `STALE_SNAPSHOT` refusal needs no snapshot, because N and D decide it.
- The live run keeps no ring buffer.
- Memory: 3 × 15–25 KiB, about 45–75 KiB (estimate, the #1467 §6 Praha figure). It is at most 192 KiB under the
  #1467 budget of ≤ 64 KiB per snapshot. `PrahaPlanningSnapshotBudgetTest` (#1468) measures it.
- *Amendment (#1486 §2.6):* "Emergency commands need no kept snapshot."

#### 3.2 Partial cycle

**Definition.** A **partial cycle** is a cycle whose batch reached the drain, with at least one command carried
out and at least one refused at COMMIT stage. The refusal can be any #1463 code except `STALE_SNAPSHOT`. A stale
batch is refused whole, because it has one N and one D.

These cases are counted on their own:
- A **refused cycle**: every command was refused.
- A **stale cycle**: the whole batch was refused with `STALE_SNAPSHOT`.
- An **undrained cycle**: the run ended with the batch still queued. Its commands are recorded with no D.
- A **timed-out cycle**: the deadline hit before the cycle completed. Nothing of its routing batch is handed
  over, and the batch is dropped (#1486 §2.7). An emergency command the cycle already posted (§4, #1486 §2.6) is
  not withdrawn: it commits at its D like any other. The cycle is a silent cycle with cause timeout, author
  `TIMEOUT_NOOP`, only when it posted no emergency command. A timed-out cycle that posted one is not silent, and
  its emergency commands are `LLM` actions. This is the rule for a cut cycle that already emitted (§1b.1).

**What is recorded.**
- Per command: seq, cycle id, O, N, D, outcome and code. Decided in #1482 (stream fields) and #1476 (schema). An
  emergency command also carries P (`emergencyPostedTick`, required).
- Per denial: the #1463 `denials[]` event. It carries `n`, `d` and `o` for `STALE_SNAPSHOT` and
  `PRECONDITION_CHANGED`.
- Per run:
  - `commitRefusalsByCode`, which has COMMIT refusals without `STALE_SNAPSHOT` and `RELEASE_REFUSED`;
  - `staleRefusals`, a key of its own, so the LLM defect rates stay clean (#1463 Q6);
  - `staleRefusals` split by author, so `OPERATOR` SEMI_AUTO rows are not counted with LLM batches. The total
    stays the #1463 key; the split key is decided in #1476;
  - the D−N and D−O distributions. Undrained commands (no D) are left out of both distributions and counted only
    as undrained;
  - the counts of partial, refused, stale and undrained cycles;
  - for emergency commands, the D−N distribution, the R9 latency-breach count (D−N > 1) and the defect count
    D − P > 1 (keys decided in #1476);
  - the pacing controller's rate distribution and the time it spends at its minimum speed (QA 6; key decided in
    #1476).

  Together these meet requirement 4. The key spellings beyond #1463's are decided in #1476.

**How refusals reach the LLM.** Commit-time refusals reach the LLM in the next cycle's observation as typed lines:
`code`, `class`, `blocked_by`, `conflict_at`, `alternative`, `repeat` (#1463 Q5). `STALE_SNAPSHOT` is left out of
the `repeat` identity. N, D and K are never shown to the LLM.

#### 3.3 Retry trigger

**The control tick stays the retry clock. The routing thread has no retry loop.** A command refused at commit is
not retried by the pipeline. The LLM sees the refusal in the next cycle and re-issues or changes the command.

Why:
1. The 1B-SP7 wake events (`BlockReleased`, `ReservationExpired`, `OccupancyCleared`, `SwitchClaimsReleased`) are
   emitted inside kDisco event processing on the simulation thread. Forwarding them to another thread ties the
   retry instant to wall-clock time and breaks replay.
2. A retry is a reservation attempt, and the thread rule forbids that to the routing thread.
3. The simulation-thread wake queue in `MultiTrainLoop` stays unchanged for entry attempts below the low-level
   queue: FIFO waiters, coalesced wakes and the own-rollback filter. `RetryOrderEqualsPollingBaselineTest`,
   `BlockReleasePathWakeTest` and `SwitchReleasePathWakeTest` stay green (#1456).

Retries while `RouteRelease.deferredBlockIds` is non-empty keep the author of the original command (#1475).
`hold_train … until` is a condition on `TrainLifecyclePort` evaluated at the control step, not a translator retry
(#1486 §2.5).

#### 3.4 Scripted-source interface and command-source field

**Interface: `HighLevelCommandSource`** (confirmed). It lives in `:core` commonMain, in the package where
`ActionAuthor` moves (decided in #1478).
- Shape: `fun commandsAt(observation: DispatchLoopSnapshot): List<HighLevelCommand>`. `HighLevelCommand` is the
  sealed type of #1486 §2.7. #1479 may refine the parameter list but keeps the name.
- It is called once per control tick on the simulation thread, after step 4.
- Its runs are inline, so O = N = D and they are byte-equal. Its list goes directly to the translator, with no
  high-level hand-off, and the translator output goes through the low-level queue and its drain. The threaded
  hand-off is proven by #1481. Wherever #1439 and #1479 say "through both queues", the inline case means this path.
- It writes author `LLM`, never a default.

**Run-level field: `commandSource`**, an object with `kind` and `name`:
- `kind = LLM`, with `name` = the model tag;
- `kind = SCRIPTED`, with `name` = the script name;
- `kind = RULE_BASED` or `NONE` for runs without an LLM or a script, so every run JSON carries the key.

LLM campaign aggregation (`RunReportAggregator`, `ExitPassBars`) counts only `kind = LLM`. The key spelling and the
schema number are decided in #1476.

---

### 4. Timing, pacing, emergency and SEMI_AUTO

**Pacing (requirement 5; the controller is built in SP6).**
- The controller sets the simulation rate from the rolling p95 cycle latency, which the dispatcher thread
  publishes as one atomic value.
- It clamps the rate to a range from a minimum above zero to a maximum.
- It never waits for a command or a snapshot.
- The rate is applied in `SimulationController.throttle` on the simulation thread.

**Engine rule (c).** The pacing controller must keep `Simulation.run` under `runBlocking` or a single-thread
dispatcher.
- Under `Dispatchers.Unconfined` the engine continues on the thread that called `resume()`, with the kDisco
  ThreadLocal context unset.
- `delay()` in kDisco's `applyThrottle` has the same problem.
- This is also #1467 P5. Any other driver voids publication rule P1.

**Watchdog budget (requirement 6).**
- `pace` is wall seconds per simulated second at the controller's minimum speed. Using the minimum keeps the budget
  from firing on a run that is slow but legal.
- `endTime` is in simulated seconds.
- The `margin` value is decided in #1485 (proposal 1.5).

**Emergency channel (requirement 8 and the requirement 1 amendment; #1486 §2.6).**
- `cancel_route(route, emergency = true)` becomes `EmergencyCancel(routeId)`. `EMERGENCY_SYSTEM` may post
  `EmergencyHold`.
- These commands go to a separate low-level emergency queue as soon as the tool runs. They are not part of the
  routing batch.
- They are drained at **every** control-step drain, **first**, before the main low-level batch. This does not
  depend on what the main queue holds: an empty main queue or a stale main batch never delays or drops an
  emergency command (#1450 ruling; #1484 implements it). They are resolved from the #1469 route record at D, so
  they read no snapshot.
- **P, the post tick.** `post` reads `latestObservation.tick` inside the same atomicfu lock the drain takes
  (#1478). It stores the value in the command as `emergencyPostedTick` (P). A tick the poster reads before it
  enqueues is not enough, because the poster can be preempted between the read and the enqueue while the
  simulation thread publishes and drains twice. With the stamp under the lock, a post ordered before drain t has
  P ∈ {t − 1, t}. So the commit drain satisfies **D − P ≤ 1** exactly: D = P when the post lands between steps 4
  and 6 of step P (§2), else D = P + 1. D − P > 1 is a defect. `emergencyPostedTick` is therefore required in the
  record (key for #1476, stream field for #1482).
- **N, the cycle's tick.** D−N is recorded for every emergency command, and D−N > 1 counts as an R9 latency
  breach. D−N measures the whole chain from observation to commit, LLM thinking time included. A high count is
  therefore a pacing finding, like `staleRefusals`, not an engine defect. The key is decided in #1476.
- **Accepted latency (#1450 ruling): at most one control step, 2.0 simulated s, measured from the arrival of the
  command in the emergency queue to its commit at D.**
  - The LLM's thinking time is outside this bound.
  - In one control step a train runs 48 m at the 24 m/s line speed of both stations' tracks, and 80 m at 40 m/s
    on the Praha hl.n. north approach. These are computed from the station XML, not measured.
  - Wall time is not bounded: a GUI pause freezes simulated time, and the command commits at the first drain
    after resume.
- Nothing calls `Process.reactivate` or schedules a kDisco event from another thread. Goal 9B needs no kDisco API
  for cross-thread submission (#1450 ruling: not needed). Goal 9C (R15) revisits it, together with a shorter
  emergency step.
- #1484 adds `EmergencyStopNextDrainTest`, `EmergencyLatencyRecordedTest`,
  `EmergencyStopAppliedAtFirstDrainAfterPostTest`, `EmergencyChannelNoEngineCallArchitectureTest` and
  `EmergencyLatencyBudgetTripwireTest` (#1450 ruling).
- The channel skips the SEMI_AUTO row.

**SEMI_AUTO (requirement 7; settled).** K stays 2 in SEMI_AUTO; there is no separate bound.
- A row is translated when it is posted, which stamps N. It is held translated in `:core` (§1). It commits at the
  first drain after approval with author `OPERATOR` (#1486 §2.7, #1475 C12). D−N > K refuses it with
  `STALE_SNAPSHOT`.
- **The operator's real window is 1–2 drains.** The last drain that can commit is N + 2. A row is posted after
  drain N, so the window is 2–4 simulated s, depending on where in the control step the row was posted. At the
  Goal 7 speeds that is:

  | GUI speed | Wall window |
  |---|---|
  | 0.1× | 20–40 s |
  | 1× | 2–4 s |
  | 10× | 0.2–0.4 s |
  | 50× (preset) | 0.04–0.08 s |

  Above about 1×, a route cannot be read and judged without pausing.
- K counts control steps, so a GUI pause (`Space`, `SimulationRunner.isPaused`) freezes the row's age. The
  intended way to judge a row is to pause, approve, and resume.
- **This is a behaviour change, owned by #1440 (SP6).** Today `SemiAutoApprovalGateway.approve` blocks the
  simulation thread with the modal `SemiAutoApprovalDialog` (`invokeAndWait`), so the simulated clock stops while
  the dialog is open. #1440 rewrites the gateway and the dialog into the non-modal held row (its acceptance
  criterion on `SemiAutoApprovalGateway`).
- A stale row is REPLAN: the LLM's next cycle plans it again on fresh state. Committing an old plan would be worse.
- A refused row is never re-translated without the operator.
- A stale **approved** row must be visible to the operator: it gets a Warnings-tab entry (SP10, open point below).
- A rejected row is recorded as "operator rejected", which is not a denial. The key is decided in #1476.
- The residual risk is accepted with these measures in place: the tests `SemiAutoRowStaleAfterKTest` and
  `SemiAutoRowAgeFrozenWhilePausedTest`, the Warnings entry, the per-author stale split, and the manual QA script
  (#1443) run at 1× and at the 50× preset.

---

### 5. Paused-clock constraints restated (C1–C4)

- **C1.** No pipeline step is bracketed by a pause. The pacing controller changes only the rate. The only pause is
  an explicit GUI pause, and no pipeline step depends on it.
- **C2.** The routing thread translates only on the immutable snapshot it took (N). Validity at commit is the
  simulation-thread re-check at D (SP3.10), never a fresh capture.
- **C3.** Never wait for a simulation-thread capture while paused. Here the rule is stronger: the routing thread
  and the pacing controller never block on a capture at all, and read the latest published reference without
  waiting. The ruling's §2 deadlock applies to both: a pause in the before-event hook
  (`DefaultSimulationContext.advanceControlledStep` → `awaitIfPaused`) parks the only thread that could publish a
  snapshot.
- **C4.** Any rate change or pause the controller applies is undone in a `finally`. If the latency source dies, the
  rate stays at its last clamped value, never zero.

---

### 6. Replay

The record holds, per command: seq, cycle id, O, N, D, author, the high-level command and its outcome, and for an
emergency command P. The stream
fields and their schema number are decided in #1482.

How replay runs:
- It re-simulates the scenario with no dispatcher thread and no routing thread.
- On every tick it keeps the last K + 1 published snapshots.
- At tick D it takes each recorded routing command with that D, in seq order, re-runs the translator inline against
  the kept snapshot N, and then drains.
- Emergency commands are re-posted at their D with no snapshot, ahead of that D's routing commands (the drain
  order of §4). P is taken from the record, never re-stamped. Replay checks D − P ≤ 1.
- Stale refusals are reproduced from N and D.

This is sound only if the translator is a pure function of (snapshot, route table, command):
- no wall-clock read;
- no random value;
- no hash-order iteration (#1463 Q6);
- no live-state reference;
- no read of the ranking.

The architecture tests below enforce this. "Byte-equal" is defined in #1482. `DenialsReplayByteEqualTest` (#1463)
covers `denials[]`.

**A replay proof that reads the ring buffer (QA 2).** Inline CI twins have N = D, so on their own they never read
the buffer. A threaded CI run cannot choose N either. So #1480 adds a **test-only lagged inline translator**:
- It runs on the simulation thread at tick N and posts its output for drain D = N + m, with m fixed by the test.
- It is a test seam only. Production arms never use it.

#1482 uses it in `ReplayRailwayOutcomeTest`:
- It records runs with m ∈ {0, 1, 2, 3}. They include at least one command whose translation differs between
  snapshot N and live state at D. m = 3 gives `STALE_SNAPSHOT`.
- It replays each run and compares the #1479 railway outcome (entered, exited, block transitions, end cause) and
  `denials[]`. The comparison does not depend on #1482's byte-equal definition.
- Negative case: when snapshot N is missing from the buffer, replay fails with a typed error. It never falls back
  to live state.

---

### 7. Stopping a run (kDisco engine rules)

- **(a)** Before kdisco#87 ships, a cross-thread `Simulation.stop()` has no publication guarantee, so Job
  cancellation is used (`ensureActive` is checked in both engine loops).
  - After kdisco#87, `stop()` is read only at the top of the loop, after `beforeEvent`. A run paused in the pause
    channel then does not stop until `resume()`, so `stop()` must be followed by `resume()`, or the run job is
    cancelled.
  - Goal 9B keeps the F4 ban on cross-thread `stop()` in both cases.
- **(b) (settled)** Neither stop nor cancellation interrupts a process body that spins without suspending. Before
  kdisco#85, neither interrupts the `RESTART` retry loop either.
  - The issue text says the loop "checks only `ensureActive()`", which would make cancellation enough. The kDisco
    session review is the newer and stricter source, and this ADR follows it.
  - From kDisco 0.7.0 the `RESTART` loop ends by the kdisco#85 retry-limit exception.
  - A non-suspending spin in a process body still ends only by process exit.
- **(d)** kDisco F7: native and JS keep one global context. `:fast-sim` runs one simulation per process, and CI
  twins never run in parallel inside one native process.
- **kDisco 0.7.0** adds two run-ending exceptions: a process failure (`ProcessFailureException`) and the engine
  retry limit (the new type from kdisco#85). Each maps to its own end cause. The run JSON is written, and the run
  is never clean (#1475 §1b.5).

**Which stop the #1485 watchdog uses.**
- **Engine-liveness trigger** (wall time past the budget while simulated time is frozen):
  1. Write the run JSON with the watchdog end cause, from the last published record and the thread-safe counters.
  2. Cancel the run job.
  3. If simulated time still has not advanced after a grace period (proposal 10 s wall, estimate), end the process:
     `exitProcess` with a non-zero code on JVM, process exit on native.

  Step 3 is the only stop that works in case (b), so the watchdog must be able to fail a run without a cooperative
  stop. In `aiSweep`, the parent's existing child-JVM abort path stays the backstop.
- **Idle-work trigger:** checked on the simulation thread at the control step. It ends the run from that thread
  (a same-thread end, not a cross-thread `stop()`), so the verdict replays.
- The mechanism and the grace value are decided in #1485.

**Rule for #1485, so its tests are not flaky (QA 5).**
- The watchdog reads the wall clock only through an injected time source.
- `exitProcess` sits behind an injected exit action.
- Unit tests drive fake time: a legal slow run at minimum speed does not fire; frozen simulated time fires; the
  grace period calls the exit seam.
- The one real process-exit test (a non-suspending spin) runs in a forked child JVM, tagged `integration-test`. It
  asserts only the exit code and the run JSON end cause, never a duration.
- The idle-work trigger is tested in simulated seconds, with `ScriptedPlanner` silent mode and
  `FaultInjectionProcessFixture` (#1451).
- CI asserts no wall-clock value anywhere.

---

### 8. Residual risks

- **Stale plans and the refusal rate.** "A command planned on an observation that changed before the drain is
  refused at commit time. This is expected, not an error: the refusal is a typed denial, it reaches the LLM in the
  next cycle, and the run record counts it by code." A high `staleRefusals` count is a pacing finding, and a high
  `commitRefusalsByCode` count is a planning finding. Neither is a pipeline defect.
- **Partial cycles.** There is no batch atomicity, so a half-applied plan can leave a train approved with no route.
  Partial cycles are counted (§3.2).
- **Pacing collapsing into lock-step.** Requirement 5 guards against it: a minimum speed above zero, no wait for a
  command, and late commands refused. The risk is observable:
  - `PacingControllerNeverBlocksTest`: the latency source never publishes, and the rate stays at least at the
    minimum;
  - the run record holds the rate distribution and the time spent at minimum speed (§3.2).
- **Replay memory.** 3 snapshots, about 45–75 KiB (estimate). It grows only if K grows.
- **Emergency latency.** At most one control step (2.0 simulated s) from arrival in the emergency queue,
  guaranteed by D − P ≤ 1 (#1450 ruling). D−N > 1 is counted as an R9 latency breach, which is a pacing finding.
  D − P > 1 is a defect. A change to braking physics re-opens the question (the #1486 B1 tripwire). Goal 9C
  (R15) revisits cross-thread submission.
- **Non-suspending spin.** Only process exit ends it (§7 b).
- **SEMI_AUTO window.** The operator has 1–2 drains: 2–4 simulated s, which is 2–4 wall s at 1× and 0.2–0.4 s at
  10×, unless they pause the GUI. This is accepted together with the measures in §4.

---

### Named tests

**`TranslatorIsolationArchitectureTest` (#1480).** Headline, as the issue words it: "no `PathReservationService`,
`TopologyNavigator` or `RouteFinder` reference from the translator". A ban on three types alone misses the main
leak: a translator can reach all three through `SimulationContext` or `NetworkActuatorPort`, and
`PathCommandTranslator.translate` did exactly that. So the test has four parts:
- **(a) Allowlist**, a JVM reflection check in `:core` `jvmTest`. The constructor-parameter and field types of the
  translator classes may only be `DispatchLoopSnapshot`, `RouteTable`, `HighLevelCommand`, `LowLevelCommand`, the
  #1463 denial types, and Kotlin and stdlib value types.
- **(b) Source scan** of the translator package directory in `commonMain`, in the style of
  `DocLineAnchorTripwireTest`. It looks for the simple names `PathReservationService`, `TopologyNavigator`,
  `RouteFinder`, `SimulationContext`, `NetworkActuatorPort` and `DispatchLoopActuatorPort`.
- **(c) Package rule:** the translator package is not `context` or `context.navigation`, where those types need no
  import.
- **(d)** A `:dispatcher-agent` reflection check that the routing-thread class holds none of those types.

**Requirement → test → leaf → tag.** "unit" means untagged.

| Requirement or rule | Test | Leaf | Tag |
|---|---|---|---|
| R1 `STALE_SNAPSHOT` at D−N > K | `StaleSnapshotTypeTest` (D−N = 2 commits, 3 refuses; one event per command of the batch) | #1464 type, #1480 raise | unit |
| R1 amendment: emergency exempt, D−N recorded | `EmergencyLatencyRecordedTest` (D−N and P recorded; D−N > 1 counted) | #1484 | unit |
| R2 a pre-check is not the verdict; refusals in the next observation | `CommitRefusalInNextObservationTest` (a tool pre-check passes at O, the command is refused at D, and the refusal line appears in cycle O+1's observation) | #1481 | `integration-test` |
| R3 one batch, one snapshot, never split | `BatchNeverSplitAcrossDrainsTest` | #1478 / #1480 | unit |
| R4 distributions and counters in the run record | `RunRecordTimingDistributionsReadBackTest` (D−N, D−O, `staleRefusals` total and by author, `commitRefusalsByCode`, the cycle counters, undrained left out of D−N) | #1476 keys, #1482 writes | unit |
| R5 pacing never waits, minimum > 0 | `PacingControllerNeverBlocksTest` | #1440 (SP6) | unit |
| R6 watchdog budget | `WatchdogBudgetFakeTimeTest` (a slow legal run at minimum speed does not fire; frozen simulated time fires; the grace period calls the exit seam) | #1485 | unit |
| R6 real stop of a spin | `WatchdogProcessExitChildJvmTest` (exit code and end cause only) | #1485 | `integration-test` |
| R7 SEMI_AUTO subject to K | `SemiAutoRowStaleAfterKTest` (approval at D = N + 3 is refused `STALE_SNAPSHOT`, author `OPERATOR`) and `SemiAutoRowAgeFrozenWhilePausedTest` (pause, approve, resume, and the row commits). Both count control steps, never wall time | #1440 | unit |
| R8 emergency at the next drain | `EmergencyStopNextDrainTest` and `EmergencyStopAppliedAtFirstDrainAfterPostTest` (a two-latch `ControlStepListener` hook; `emergencyPostedTick` = P and D = P + 1; the `EmergencyHold` twin; a stale main batch and an emergency command at the same D: the batch is refused with `STALE_SNAPSHOT` and the emergency command commits first) | #1484 | unit |
| R8 no engine call from the emergency queue | `EmergencyChannelNoEngineCallArchitectureTest` (`:core` jvmTest source scan of the queue file) | #1484 | unit |
| R9 latency budget | `EmergencyLatencyBudgetTripwireTest` (tripwire: step 2.0 s, deceleration 3.0, max line speeds 24.0 and 40.0 read from the station XML) | #1484 | unit |
| R8 inline timing | O = N = D for every inline command, the #895 bracket byte-equal, `ControlArmGoldenOutcomeTest` | #1480 | `integration-test` |
| R8 threaded stamps | O ≤ N ≤ D for every command; nothing takes the old path | #1481 | `integration-test` |
| R8 replay | `ReplayRailwayOutcomeTest` (lagged translator, m ∈ {0..3}, plus the missing-snapshot case), `DenialsReplayByteEqualTest` | #1482 | `integration-test` |
| Thread rule | `TranslatorIsolationArchitectureTest`, `TranslatorNeverReadsRankingArchitectureTest` | #1480 | unit |
| §3.3 no retry loop on the routing thread | `RoutingThreadNoRetryTest` (a refused command is translated exactly once, and nothing is re-posted until the next LLM cycle) | #1481 | unit |
| C1 nothing bracketed by a pause | `PacingControllerNeverBlocksTest` (no pause call on the controller path) | #1440 | unit |
| C2 translate on N only | `TranslatorIsolationArchitectureTest` (a) and (b) | #1480 | unit |
| C3 never wait for a capture | `RoutingThreadNeverBlocksOnCaptureTest` (simulation paused; the routing thread finishes its batch on the held snapshot) | #1481 | unit |
| C4 restore in `finally` | `PacingRateRestoredInFinallyTest` (a throwing latency source leaves the rate at its last clamped value) | #1440 | unit |
| Engine rule (c) | the run stays on one thread across pause and resume | #1440 | unit |
| Memory | `PrahaPlanningSnapshotBudgetTest` | #1468 | `integration-test` |

No row is left as a review check only. The `heavyTest` runs of the code leaves follow the repo policy for `sim/`
changes and are not listed here.

### Open points (each decided in a later leaf)

- **#1476:**
  - the `commandSource` key;
  - the cycle counters;
  - the D−N and D−O distribution keys;
  - the per-author `staleRefusals` split;
  - the pacing rate distribution and the time at minimum speed;
  - the R9 latency-breach key;
  - the required `emergencyPostedTick` key;
  - the operator-rejected key;
  - the schema number.
- **#1478:** the package of the queue types and of `ActionAuthor`. The batch-atomic post and drain use atomicfu in
  commonMain. The emergency queue's lock uses the same atomicfu idiom, and `post` stamps P under it.
- **#1482:** the command-stream fields (seq, cycle id, O, N, D), the `emergencyPostedTick` stream field and the
  definition of byte-equal.
- **#1483:** the removal of `ActuatorCommandQueue` and the `Goal9C7ArchitectureTest` rename.
- **#1484:** the drain order (emergency queue first, at every drain) and the five emergency tests of §4.
- **#1485:** the margin, the grace period and the end-run mechanism, under the injected-seam rule (§7).
- **#1440 (SP6):** the non-modal rewrite of `SemiAutoApprovalGateway` and `SemiAutoApprovalDialog`.
- **SP10 (#1444), operator display (QA 3):**
  - `MetricsDashboardPanel` (`DashboardReading`) lines for p95 D−N, p95 D−O, the stale refusal count and the commit
    refusal count;
  - a Warnings-tab (`BottomPane`) entry for each R9 latency breach and for each stale SEMI_AUTO row, approved or
    not.


## Part 1b — pass-bar rule and counted automatics (#1475)

Leaf #1475 (SP5.2, parent #1439). Author role: traffic-simulation-expert. Reviewers: railway-civil-engineer,
agent-architect. Code read: develop
9b63f780 (`.worktrees/goal9b-base`). Inputs followed: the #1456 Q1 ruling (signed off), ADR part 1 (#1474:
`commandSource`, K = 2, the two watchdog triggers) and the #1342 ruling.

**Summary.** The section lists three groups by symbol:
- counted automatics, each with its `ActionAuthor`, its railway reason and the LLM command that can act first, today and after #1486;
- excluded interlocking behaviour, each with its own reason and the action that must come before it;
- run-ending verdicts.

The code check changed five starting rows:
- `ExpiredRouteQuarantine` exists only in test sources.
- `maybeForceAdmission` is deleted, so nothing emits `SAFETY_NET` today.
- `InOutWorker` never admits a train by itself (#1456 Q1).
- `DeadlockWatchdog` runs only in `MultiTrainLoop`.
- The #612 end-of-run flush gives only an advisory warning. The run-ending case is the live conflict auto-pause.

`cancel_route` on an exited train is refused today with `UNKNOWN_TRAIN` (`CancelRouteTool`), not with
`TRAIN_ALREADY_EXITED`. Silent cycles cost nothing only after #1413. Until then a silence runs the rule fallback
(C8).

---

### 1b.1 Fixed rules (word for word)

**Clean-run rule (rulings Q1 and Q2).** A run is clean when its end cause is natural completion, its run JSON has the
#1476 schema or later, every author other than `LLM` and `TIMEOUT_NOOP` has zero actions, and the unattributed-action
count of #1477 is zero. A silent cycle (`TIMEOUT_NOOP`, an LLM `no_op`, an empty plan) is not a counted action and
does not break a clean run; silent cycles are counted by cause. Whatever a silence lets happen is still counted (an
expiry is `EXPIRY`). An action with no author goes to the unattributed-action counter, never to a new `ActionAuthor`
value. Scripted test commands carry author `LLM`; the run-level command-source field of the #1476 schema tells a
scripted run from an LLM run.

**Watchdog.** The #1485 watchdog has two triggers. (1) Engine liveness: wall time passes beyond the budget while
simulated time does not advance (the kDisco F1 shape). (2) Idle work: at least one train has actionable work and no
command has been carried out for longer than a bound; the bound is a value of the model profile (#1445), counted in
simulated seconds and checked on the simulation thread at the control step, so the verdict replays. Either trigger
ends the run as failed with its own end cause; the run is never clean, and the run JSON is written. The simulation
does not wait in either case; ending a run is not waiting (R8).

**Silent-cycle causes.** These are counted from existing keys (`ticksByOutcome`, `timeoutNoOpByCause`). Each silent
cycle goes under exactly one cause.

| Cause | Key (source after #1413 / #1342) |
|---|---|
| timeout | `TickOutcome.TIMEOUT_NOOP` with `TimeoutNoOpCause.DEADLINE_MISS` |
| cut by the `num_predict` cap (`done_reason=length`) | `TickOutcome.TIMEOUT_NOOP` with `TimeoutNoOpCause.THINKING_TRUNCATED` (new value, #1342) |
| empty plan | `TimeoutNoOpCause.EMPTY_UNPARSEABLE` and `UNREPAIRABLE_OUTPUT`; `TickOutcome.LLM_SILENT_ALL_MOVING` and `LLM_SILENT_NONACTIONABLE` |
| `no_op` | `TickOutcome.LLM_NO_OP` |

`THINKING_TRUNCATED` is a sub-cause of `TIMEOUT_NOOP`, as #1342 rules, and is never counted twice. This keeps the
rule above correct as worded. A cut cycle that already emitted a command is not silent: those commands are `LLM`
actions. The same holds for a timed-out cycle that already posted an emergency command (ADR part 1 §3.2): the
emergency queue keeps it when the routing batch is dropped, and it commits at its D with author `LLM`. Today the `TimeoutNoOpCause` KDoc says that no producer reports `TIMEOUT_NOOP`. In today's
`KoogAgentPlanAdapter`, a timeout, an exception, an open breaker and a silent non-idle cycle all run `runFallback`.
So **until #1413 a silence is not free.** It breaks a clean run through C8. So do three consecutive failures: each
calls `LlmCircuitBreaker.recordFailure`, and the open breaker skips to `runFallback`. The next cycle after a silent
one names the skipped trains (#1413).

**Counting rules for #1476:**
- The predicate reads one map. #1476 writes the counts of C1–C6 and C13–C14 into `actionsByAuthor`. These acts do
  not pass through `DefaultDispatcherRunRecorder.onActionOutcome` today.
- The unattributed-action counter is the existing run JSON key `unattributedApplies` (`CommandCorrelationMap`,
  `DefaultDispatcherRunRecorder`). #1476 keeps that key or renames it in its schema history.
- An automatic is counted once per act: the first call of an episode that changes railway state (a signal reset, a
  freed block). Deferred calls of the same episode are not counted again. Today
  `DefaultReservationExpiry.expireHolding` and `expireTail` count only a completed release. So an expiry that
  deferred, and was then finished by an LLM cancel or by the run end, is never counted. #1476 changes this.
- C10 and C11 are counted once, under `SAFETY_NET`, and not also under `LLM`.

---

### 1b.2 Counted automatics (each one breaks a clean run)

"LLM first" names the command that, issued in time, keeps the automatic from acting. Today's tool set is
`approve_train`, `request_route`, `cancel_route` (by train) and `no_op` (`ToolGroupRegistry`). Commands marked
(#1486) do not exist yet. Three clocks of 60 simulated s run on a held route: the registration age (C1), the
`TailStallTracker` stall clock (C2) and the sweeper's `holdings` clock (C4/C5). `renew_route` must restart all three
(O1). A live `hold_train` stops all three clocks on the held train's routes, and they restart from zero when the
hold ends (#1486 §2.5).

| # | Automatic (symbol) | Author | Reason it is counted | LLM first |
|---|---|---|---|---|
| C1 | `DefaultReservationExpiry` phase 1, `ReservationExpired.Kind.WHOLE_HOLDING`. A holding where the train occupies nothing and its newest registration is a TTL old (`DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS`) is released whole. Callers: the loop tick `ReservationExpiry.expireStaleReservations` (`ShuntingLoop`, `MultiTrainLoop`) and the top of `reservePath` / `reservePathToAny` (requester exempt). | `EXPIRY` | No interlocking cancels a set route by a clock. A set route stays locked until the train passes or the dispatcher cancels it (R6, strict form). The one time element in practice does not start a cancel: approach locking (*přibližovací závěr*) delays a dispatcher's cancel, and the release then runs by time (*časové rušení závěru*). | **Today:** an extension that claims a new block (`PathReservationRegistry.expiredHoldings`: re-registering a held block keeps its first timestamp). For a queued train's route (the main producer) the only command is `approve_train` before the TTL, and E8 may refuse it. `cancel_route` by train refuses a queued train (`UNKNOWN_TRAIN`). **After #1486:** `renew_route`, or `cancel_route` by route id. |
| C2 | `DefaultReservationExpiry` phase 2, `Kind.OCCUPYING_TRAIN_TAIL` (`TailStallTracker`). The RESERVED tail of a train with no progress for the TTL is freed. The occupied and approach-locked blocks are kept. | `EXPIRY` | As C1. | `renew_route` (#1486) for the tail inside the entered route; `cancel_route` by route id is refused on an entered route. A whole unentered route ahead stays cancellable by route id. Today: none that keeps the tail. |
| C3 | Expiry triggered by **another** train's request (the `exceptTrainId` call at the top of `reservePath`). | `EXPIRY`, never the requester's author | The requester did not choose to release that route. | As C1/C2, for the expiring route. |
| C4 | `OrphanReservationSweeper` stale branch (`evaluateKnownTrain`): a known train's route, entirely un-travelled and unchanged for `staleAfterSimSeconds`. | `ORPHAN_SWEEP` | A dispatcher act, the ordinary route cancel (*rušení jízdní cesty*), done by the system. | **Today:** `cancel_route` by train, for active trains only. **After #1486:** `renew_route`, or `cancel_route` by route id. C1 usually acts first. |
| C5 | `OrphanReservationSweeper` partial branch (`evaluateOccupyingTrain`, `RegistryPartialRouteReleaser`): the un-travelled tail ahead of a stopped train. | `ORPHAN_SWEEP` | Like *nouzové rušení závěru*: locked sections ahead of a train that already entered the route, which no normal release frees. ESA-11 logs one count per act on the *počítadlo nouzových obsluh*. | `renew_route` (#1486) for the tail inside the entered route. The whole unentered routes ahead of the stopped train stay cancellable with `cancel_route` by route id. Releasing the tail inside the entered route is *nouzové rušení závěru* and is never an LLM command (#1486 P6). |
| C6 | `OrphanReservationSweeper` phantom branch (`releasePhantom`): the owner is neither in `trainPositions` nor queued; the route is released on sight. | `ORPHAN_SWEEP` | As C5: sections left locked with no train. | **None today** (§1b.4). After #1486: `cancel_route` by route id, posted a cycle earlier. `sweep()` runs after `applier.onControlStep()` in the same `controlStepListener`, so that cancel acts first. |
| C7 | Retries while `RouteRelease.deferredBlockIds` is not empty. | Author of the original command | A command that waits for its conditions is still that command. | — |
| C8 | `KoogAgentPlanAdapter` rule fallback (`runFallback`, `fallbackDispatcher`). Triggers: timeout, exception, agent-creation failure, open breaker (`circuitBreaker.shouldAttempt` false), and the silent non-idle cycle (oracle). `TickOutcome.RULE_FALLBACK` and `LLM_ABANDONED` map to it. | `RULE_FALLBACK` | A rule policy originates a dispatching action (#1413: `c7Clean` false in 20/20 Červený Újezd runs). | Any LLM command in the cycle. #1413 removes the fallback by construction, and an architecture tripwire guards it. |
| C9 | Admission safety net. `maybeForceAdmission` is deleted (`KoogAgentPlanAdapterTest` KDoc; SP2c.25 audit §3). No production code emits `ActionAuthor.SAFETY_NET`. `TerminalFallbackGuard` still treats it as an engage trigger. | `SAFETY_NET` | Kept as a detectable author. | `approve_train`. |
| C10 | Route-target choice made by the interlocking's own order when no target is named: `ReservationTargetPolicy.pick` (through `chosenTargetName`), `reservePathToAnyNextSemaphore`, `reservePathToAnyTargets`. | `SAFETY_NET` (confirmed) | The dispatcher always chooses the start and the end of a route (R5). An order the kernel picks is a choice the dispatcher did not make. | `request_route` with an explicit `toEndpointName`. The translator passes a named target through unchanged. |
| C11 | `InOutWorker` entry reservation **when an InOut has more than one entry target** (`ReservationTargetDiscovery.findNextSemaphoresVia` returns more than one; `EntryProbeSingleTargetTest` fails). | `SAFETY_NET` | #1456 Q1: the kernel's order then picks, as in C10. This holds until the translator names entry targets (SP7). | `approve_train` with a named entry target (SP7). |
| C12 | SEMI_AUTO operator approval. | `OPERATOR` | Legal, but the run is not autonomous (`ActionAuthor.OPERATOR` KDoc). | — (mode choice) |
| C13 | A restricting command the R9 emergency path issues by itself: a hold (`EmergencyHold`, #1486 §2.6). No producer today. | `EMERGENCY_SYSTEM` | The system, not the dispatcher, acted. | The LLM's own restricting command (author `LLM`). |
| C14 | Watchdog stop (§1b.5). | `WATCHDOG` | It ends the run; the run is never clean. | Any command carried out within the trigger-2 bound. |
| C15 | Rule-based control arm (`RuleBasedDispatcher`, `RuleBasedEmissionStrategy`). | `RULE_BASED` | Not an LLM run. It is judged by `ExitPassBars` and #895, not by the clean rule. | — |

Today C10 has no producer on the LLM path. `RequestRouteTool` requires `toEndpointName`. `NextHopResolver.resolveAll`
and `NextSectionTargets` use `chosenTargetName` only for the NEXT SECTION **offer**, which is part of the observation,
and a command the LLM writes from that offer is an `LLM` command. C10 becomes live as soon as a #1486 command lets
the translator pick. Its decision is recorded at the decision layer (#1456 Q2).

**Starting rows moved:**
- **`ExpiredRouteQuarantine` is not a row.** It lives only in `:dispatcher-agent` `src/test`
  (`dispatcher.testutil`, `ExpiredRouteRegrantCycleTest`). It only withholds a command, so it would be a gate in any
  case. Under #1249 (revised) R6 keeps it out of LLM runs. The #1413 tripwire names it.
- **"A train `InOutWorker` reserves for or admits itself" is excluded (E9)** (#1456 Q1). Admission happens only on
  this path: `ShuntingLoop.approveQueuedTrain` → `activate(train)` → `Train.actions` → `InOutWorker.enterTrain`.
  The entry probe calls `isPathToAnyNextSemaphoreAvailable`, then `reservePathToAnyNextSemaphore(trainId, inOut,
  next)`. Its retries keep author `LLM` (C7); this includes the `STALE_ORIGIN_RETRY_SECONDS` hold.

**C7 in today's code.** No path retries an LLM release automatically. `DispatchDecisionApplier.applyReleaseRoute`
reports `deferredBlockIds` in `AppliedOutcome.Released`, and `KoogDispatchAgentImpl` shows them to the LLM. The
sweeper and the expiry each retry only their own deferral. The routing thread has no retry loop (ADR part 1 §3.3).

**Stale KDoc.** `ShuntingLoop.approveQueuedTrain` still names the deleted `KoogAgentPlanAdapter` admission safety
net (O5).

---

### 1b.3 Excluded as interlocking behaviour (not counted, but logged in SP6)

| # | Behaviour (symbol) | Railway reason | Required predecessor (§1b.6) |
|---|---|---|---|
| E1 | Release on passage: `Train.Tail` leaving a block → `PathReservationRegistry.unregisterBlock` | Progressive release behind the train (*postupné rušení závěru*) | the route grant the train is travelling |
| E2 | Release at train exit: `SimulationEnvironment.releaseTrainReservations` → `pathReservationService.unregister` | Release of the route the train has completed | per block: the route the train travelled to its exit (§1b.6) |
| E3 | Signal drop to STOP: `Train` `semaphoreAction` (passage) and `ClearedSignalLedger.resetClearedSemaphores` (release) | A signal falls to STOP when its route is passed or released | the train passing that signal, or the release (E1, E2, LLM or counted) that freed its route |
| E4 | Flank claim released at teardown (`SwitchClaimsReleased`, `RouteReleaser`) | A flank lock goes with the route it protects | a release from E1, E2, or an LLM or counted release |
| E5 | Stale plain switch claims taken back: `SwitchOwnershipTable.isStaleSwitchOwnership` (not a flank claim; the owner holds no block bounded by the switch); `PathReservationRegistry.reclaimStaleSwitchOwnership`, `SwitchConfigurator.releaseOrphanSwitchLocks`, `SwitchConfigurator.reclaimStaleForeignSwitchOwnership`, `CandidateCommitter.reclaimCandidateSwitchOwnership` | No ESA-11 counterpart: a switch lock is freed with its section. A leftover claim is simulator bookkeeping that completes an earlier release. | that earlier release, and the reservation call that reclaims the claim |
| E6 | Rollbacks inside one call (`CandidateCommitter.rollbackRegistryConflict`, the bypass rollback of `reservePathToAnyNextSemaphore`) | Route setting is all or nothing: a route that cannot be fully set is not set | the call itself |
| E7 | A train moving again when its own hold ends | The train obeys the end of a command | the `hold_train` (#1486) that set the hold |
| E8 | `ShuntingLoop.approveQueuedTrain` capacity refusal (`stationCapacity`) | A gate, not an action | the `approve_train` it refuses |
| E9 | `InOutWorker` entry probe on a single-target InOut (#1456 Q1) | Carrying out the dispatcher's command; with one target, `approve_train` already names the whole route | `approve_train` |

---

### 1b.4 `cancel_route` on the leftover route of an exited train

**It is refused today, but not with `TRAIN_ALREADY_EXITED`.** In production `ToolGroupRegistry` wires
`CancelRouteTool(sinkHolder, perceptionPort)`. `CancelRouteTool.execute` checks the id against
`snapshot().trainPositions`, which holds active trains only. An exited or a queued train is therefore refused in-turn
with `RejectionCode.UNKNOWN_TRAIN`. `RejectionCode.TRAIN_ALREADY_EXITED` is returned by
`ActionValidator.validateCancelRoute`. Its only production caller is `DispatchTickLoop`, and nothing in production
constructs one. Neither refusal is reached first in practice: the sweeper's `knownTrainIds` misses the exited train
too, so `releasePhantom` (C6) frees the route in the same control step.

---

### 1b.5 Run-ending verdicts (the run failed)

| Verdict (symbol) | Where it runs today | End cause |
|---|---|---|
| `DeadlockWatchdog` → `SimulationStalled` → `errorStop` | **only `MultiTrainLoop`**. In `ShuntingLoop` (shuntingLoopAI, cervenyUjezdAI) the 180 s horizon (suspended while a hold is live on the train; #1486 §2.5) and #1485 trigger 2 are the stops | existing |
| `Train.OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS` (180 s) → `errorStop`, suspended while a hold is live on the train (#1486 §2.5) | every loop | existing |
| Other `errorStop`s: `MAX_ORIGIN_NO_PATH_RETRIES`, `MAX_MID_JOURNEY_NO_PATH_RETRIES`, the `InOutWorker` exception stop | every loop | existing |
| #1485 trigger 1 and trigger 2 (§1b.1) | new | `WATCHDOG_TIMEOUT`, `DISPATCHER_IDLE` (names to confirm in #1485); author `WATCHDOG` |
| #612 **live** conflict: `CandidateCommitter` emits `ReservationConflictDetected` with `atRunEnd` false → `DefaultCollisionDetectionService.handleReservationConflictDetected` → CRITICAL → `autoPauseOnCritical` | every context run | own end cause (O4) |

**Correction.** `PathReservationService.flushUnresolvedConflicts` runs once, after `run` returns
(`DefaultSimulationContext.flushUnresolvedReservationConflicts`). Since #1331 its events are `atRunEnd` and give only
an advisory WARNING. A queue left unresolved at the end is a railway-quality result (`ExitPassBars`,
`RunEndCause.STARVED`), not an authorship result.

---

### 1b.6 Rule for the SP6 reachability proof

The SP6 reachability proof runs the scripted perfect dispatcher (#1479) on `cervenyUjezd`. It logs every excluded
automatic E1–E9 and judges each event against its own reason in §1b.3. Each logged event names its required
predecessor: the command seq of an LLM or counted action, or the passage event that carries that grant forward.
E2 is judged **per block**: each block it frees must belong to the route the train travelled to its exit. A block
outside that route is a cancel, not a release at exit. **An excluded automatic that acts there without a preceding
counted or LLM action becomes counted.** It is counted under `SAFETY_NET` until an amendment to this section moves
its row to §1b.2 with its own author.

The proof passes only if all of these hold:
- zero counted automatics (C1–C14);
- every E-event is chained to its predecessor;
- `phantomReleaseCount == 0`;
- `EntryProbeSingleTargetTest` is green.

Extra SP6 check: a train that stands at a platform for at least the TTL with its departure route set loses its tail
to C2. This is an estimate, not measured. The proof either avoids that dwell or shows the LLM's `renew_route`.

---

### Named tests

All named tests belong to other leaves; this section adds no code.
- `EntryProbeSingleTargetTest` (`:core` jvmTest), plus the two guard tests against `ExampleRegistry` and
  `NativeExampleRegistry` (#1459).
- #1476: one predicate test per row C1–C14. C3 is tested apart from C1. C6 uses an exited train's leftover route.
  One test shows that a deferred expiry is counted once.
- #1476: a silent-cycle test over all four causes, including `THINKING_TRUNCATED` (the MockWebServer
  `done_reason=length` reply of #1342), where the run stays clean. One test shows that a cut cycle which already
  emitted counts its actions under `LLM`. One test shows that a timed-out cycle that already posted an
  `EmergencyCancel` is not silent and counts it under `LLM`.
- #1413: an architecture tripwire. The LLM runtime does not reference `RuleBasedDispatcher`, the fallback types or
  `ExpiredRouteQuarantine`.
- SP6 (#1440): the proof of §1b.6, including the per-block E2 check.

### Open points (decided elsewhere)

- **O1 — #1486:**
  - `cancel_route` by route id accepts a live route whatever its owner's phase: active, queued, exited or unknown.
    It goes through the new per-route, approach-locked release owned by #1469 (not today's train-wide
    `RouteReleaser.releasePathDetailed`, which stays the V1 `ReleaseTrainRoutes` path) and keeps approach locking.
  - `renew_route` restarts the expiry, tail-stall and sweeper clocks of that route.
- **O2 — two owners:**
  - #1459 validates the train id in `DefaultNetworkActuatorPort.requestRoute` (today it checks only for a blank
    name), so no phantom owner can exist.
  - #1440 (SP6 proof) checks "E2 leaves no residue" with the per-block check of §1b.6 and `phantomReleaseCount == 0`.
    A defect it finds is filed at that replan.
- **O3 — #1476:** whether `LLM_EXCEPTION` and the open-breaker skip get their own silent-cycle causes after #1413.
  Also the counter key names for C1–C15. #1413 removes the breaker → `runFallback` branch, so after it an open
  breaker gives only silent cycles. Also the cycle-outcome key of a timed-out cycle that posted an emergency
  command (not `TIMEOUT_NOOP` / `DEADLINE_MISS`).
- **O4 — #1476 / #1485:** the end cause of the live conflict auto-pause. A headless pause that never resumes would
  otherwise fire trigger 1. Also for #1485: the idle-work predicate counts a held train with no route as
  actionable work, so a hold that never clears still ends the run (#1486 §2.5).
- **O5 — #1413:** fix the stale `ShuntingLoop.approveQueuedTrain` comment.
- **O6 — #1249:** the YOUR ROUTES block shows the remaining time per route (`expires_in_s` does not occur in
  `:dispatcher-agent` main code today), including the routes of queued and exited owners.

## Part 2 — command vocabulary and LLM tools (#1486)

Leaf #1486 (SP5.13, parent #1439). Author role: agent-architect. Reviewers: railway-civil-engineer (RCE),
traffic-simulation-expert (TSE). Code read: develop 9b63f780
(`.worktrees/goal9b-base`). Inputs followed: #1463 (`DenialCode`), #1488 O-h, #1475 O1/O6, #1456 (single entry,
four-argument `requestRoute`), #1467 (snapshot, names), and ADR part 1 (#1474: threads, K = 2). Where the two
reviewers conflict, this text follows the TSE on simulation and replay questions and the RCE on railway
questions. Each such case is marked.

**Summary.**
- The LLM gets **8 tools in surface V2**, none with more than 3 arguments: `approve_train`, `set_route`,
  `reroute`, `cancel_route`, `renew_route`, `hold_train`, `release_hold` and `no_op`. The frozen prompt variants
  keep **surface V1**: today's 4 tools, with today's behaviour.
- A **route id** is `R<n>`. Each `PathReservationRegistry` instance assigns it on every successful commit.
- A **hold** is a hold at a signal. Its latch is initialised from live occupancy when the hold is installed and
  then set by the occupancy event, and its release is evaluated at the control step. While a hold is live, it stops the expiry clocks of the held train's routes.
- A **cancel** is a per-route, approach-locked release. Its chain of later routes is worked out at commit and is
  carried out all or nothing.
- The **emergency channel** accepts only restricting commands and reads no snapshot. An emergency on an entered
  route still puts a signal to STOP.
- Two denial codes are new: `AMBIGUOUS_TARGET` (an amendment to #1463) and the candidate
  `CLEAR_OF_NOT_AHEAD_OF_FOR_TRAIN` (owner ruling 2026-11-28, pending #1463 / #1464).

---

### 2.1 Fixed inputs (recorded, not reopened)

1. **Railway measures need no new tool.**
   - A change of station track is `reroute` while a route is set. Otherwise it is `set_route` to a station track
     that is not the planned one, priced by the #1488 deviation penalty.
   - Give-way is `hold_train` until another train clears a point.
   - Priority by category is a cost hint (the #1488 weight p), not a tool.
   - The cancel half of `reroute` obeys approach locking. Its legality reads "the train has not passed the
     route's start signal".
2. **Emergency path (R9).**
   - An emergency cancel puts the signal to STOP. It then releases through **a per-route, approach-locked
     release (new, #1469)**.
   - That release keeps approach-locked and occupied sections reserved, as `RouteReleaser.releasePathDetailed`
     does today with `approachLock = true`. Today's call releases the train's **whole holding**, so it cannot
     serve a per-route cancel.
   - A restricting command is allowed only under the ideal-stop invariant (B1). **Tripwire:** review this section
     if braking physics changes. This also covers holding a moving train whose signal ahead already shows
     proceed (§2.5).
   - An emergency cancel of a route whose train has passed the route's start signal is refused with a typed
     denial for its release half, unless a test pins that every section ahead of the train stays reserved. In
     reality such a route is released only by the passing train or by *nouzové rušení závěru*.
   - On the emergency path, `release_hold`, `renew_route`, every proceed command and every switch command are
     refused.
   - The simulator has no *časové rušení závěru* timer (B1). An unentered route in approach is protected only by
     the approach-lock deferral window.
3. **Modes.**
   - AUTO is the only mode that can give a clean run.
   - SEMI_AUTO: an LLM command is translated when it is posted (stamp N) and waits, already translated, as a non-modal row in a `:core` holder next to `SemiAutoApprovalGateway`, not in the high-level hand-off. Approval moves it to the low-level queue. It is subject to K (#1474 §4).
   - MANUAL: author OPERATOR; never a clean run.
4. **Author set:** `LLM, OPERATOR, RULE_BASED, EXPIRY, ORPHAN_SWEEP, EMERGENCY_SYSTEM, WATCHDOG, SAFETY_NET,
   RULE_FALLBACK, TIMEOUT_NOOP`.
   - `ActionAuthor` has six of these today. #1476 adds `EXPIRY`, `ORPHAN_SWEEP`, `EMERGENCY_SYSTEM` and
     `WATCHDOG`.
   - A low-level command inherits the author of its high-level parent.
   - Emergency is a channel, not an author: an emergency command the LLM writes keeps author `LLM`.
5. **Today's surface.**
   - `ActuatorToolSurface.ALLOWED_NAMES` has four tools, built by `ToolGroupRegistry.assembleAllTools` and
     checked by `ActuatorToolSurface.assertExactly` in `KoogAgentFactory`.
   - `CancelRouteTool` takes a train only and checks it against `trainPositions`, so a queued or exited train is
     refused with `UNKNOWN_TRAIN`. It releases the whole holding (`releaseRouteDetailed(trainName)`).
   - No route id is shown to the LLM anywhere.

### 2.2 The route id (unblocks #1469, #1249)

- **Form:** `R` + a decimal counter, for example `R17`, about 3 tokens (estimate).
- **Assignment:** inside the commit of a `PathReservationRegistry` instance, on the simulation thread, after a
  final registration outcome that is not `Aborted`.
  - **One id per successful commit.** `PathInfoStore` also returns `MergeOutcome.Merged` on a first
    registration, so "per merged extension" would be the wrong rule.
  - **The counter belongs to the registry instance**, never to the process. A process-wide counter repeats the
    `Train.countValue` defect that keeps #1458 from pinning train names.
  - **Every grant path gets an id:** `DefaultNetworkActuatorPort.requestRoute`, `InOutWorker` (through
    `reservePathToAnyNextSemaphore`), `MultiTrainLoop` and, until #1460 deletes it, `DefaultInterlockingFacade`.
  - **Attempts that fail use up no id:** the failed tries of `reservePathToAnyTargets` and the aborts in
    `CandidateCommitter`.
  - Ids therefore replay byte for byte (#1482).
- **Unit:** the blocks one commit adds. #1469 keeps a set of blocks per route beside the per-train holding and
  publishes `RouteReading(routeId, …)`.
- **Lifetime:** from the grant until the last block of the route is freed, by any path (E1, E2, a cancel,
  C1–C6). An id is never reused.
- **Shown** in YOUR ROUTES (#1249), including the routes of queued and exited owners (#1475 O6). Example line:
  `R17 Train 5 S3→k5 not entered, expires in 34 s` (about 18 tokens, estimate).
- **Taken by** `cancel_route` and `renew_route`.

### 2.3 The tool table, judged row by row

**Result rules for every tool:**
- A tool result is a pre-check against the cycle's snapshot.
- A success is one line of at most about 25 tokens.
- A refusal follows the #1463 Q5 line shape, at most about 35 tokens, with at most 3 alternatives. The token
  limits are estimates.
- A result never lists all valid names, as `RequestRouteTool` does today.
- A refusal at commit time reaches the LLM in the next observation.

Class letters: N = NEVER, R = REPLAN, W = WAIT.

| Proposed | Verdict | Final form | Legality line | Typed denials (#1463 code, class) |
|---|---|---|---|---|
| `approve_train(train)` | **Kept** | same | The train is queued, its queue turn allows it, and capacity is free. | Pre-check: `UNKNOWN_TRAIN` N, `TRAIN_ALREADY_ACTIVE` N, `TRAIN_ALREADY_EXITED` N, `TRAIN_NOT_QUEUED` W, `CAPACITY_FULL` W. Commit: `CAP_EXCEEDED_APPLY` W, `APPROVE_REJECTED` N. |
| `set_route(train, target)` | **Changed**: adds `via` (O-h) | `set_route(train, target, via?)` | The train holds no route to another target. `target` is a station track, signal or exit that a route-table chain from a legal origin reaches. `via`, when given, is the first route key of one such chain. Without `via`, exactly one chain reaches the target. | `UNKNOWN_TRAIN` N, `UNKNOWN_ENDPOINT` N, `ENDPOINT_IS_BLOCK_ID` N, `ROUTE_ALREADY_HELD_TO_SAME_TARGET` R, `ROUTE_HELD_TO_DIFFERENT_TARGET` R, `TARGET_NOT_TOWARD_OWN_EXIT` R, `HOLDING_FOR_OWN_EXIT` W, `TARGET_NOT_TRAIN_DESTINATION` N, **`AMBIGUOUS_TARGET` R**: up to 3 keys in key order, raised at O and also at N. Commit: `ALL_PATHS_BLOCKED`/`CONFLICT` W, `PRECONDITION_CHANGED` R, `STALE_SNAPSHOT` R, `ORIGIN_NOT_CONTIGUOUS` R, `GEOMETRICALLY_IMPOSSIBLE`/`NO_ROUTE_EXISTS`/`ROUTE_SEARCH_INCONCLUSIVE` N. |
| `reroute(train, target)` | **Changed**: adds `via` | `reroute(train, target, via?)` | The train holds a live route it has **not entered**, and the target differs from the held one. The cancel half is the §2.4 chain cancel. The result lists every id released. | `NO_ROUTE_HELD` R, `TRAIN_ON_RESERVED_BLOCK` R (entered), `ROUTE_ALREADY_HELD_TO_SAME_TARGET` R, every `set_route` denial. Commit: `RELEASE_REFUSED` W, on the whole chain. |
| `cancel_route(route id)` | **Changed**: adds `emergency` | `cancel_route(route, emergency?)` | The id is live. Any owner phase is accepted (#1475 O1). For an active owner, the route is not entered. With `emergency`, an entered route is accepted for the STOP half only (§2.6). | `NO_ROUTE_HELD` R (unknown or freed id), `TRAIN_ON_RESERVED_BLOCK` R (entered; with `emergency`, the release half only). Commit: `RELEASE_REFUSED` W on the whole chain, `PRECONDITION_CHANGED` R. |
| `renew_route(route id)` | **Kept** | `renew_route(route)` | The id is live, the owner is active or queued, and the command is not on the emergency channel. The description says: "keeps the route set against the simulator's expiry. Real routes do not expire." | `NO_ROUTE_HELD` R, `TRAIN_ALREADY_EXITED` N, `UNKNOWN_TRAIN` N (phantom owner). On the emergency channel: `DROPPED_INVALID` N. |
| `hold_train(train, until = after_train X clears P \| sim time T)` | **Changed**: the give-way form only, with arguments renamed | `hold_train(train, for_train, clear_of)` | `train` is active and not held. `for_train` ≠ `train`, and is queued or active. `clear_of` names a signal, switch or InOut. A route-table chain from the position of `for_train` (the block of its tail, or its entry InOut when queued) to its exit passes `clear_of` (owner ruling 2026-11-28); a `for_train` whose tail has already passed `clear_of` (far side, §2.5) is refused. No set route of `train` conflicts (#1487 row) with a live route of `for_train`, or with a route-table route of `for_train` that passes `clear_of`. | `UNKNOWN_TRAIN` N, `TRAIN_NOT_ADMITTED` R, `UNKNOWN_ENDPOINT` N, `BLANK_ARGUMENT` N (`for_train` = `train`), **`CLEAR_OF_NOT_AHEAD_OF_FOR_TRAIN` R** (*#1463 denial candidate*: no chain of `for_train` passes `clear_of`; raised at O and again at N, recorded as `PRE_CHECK` with `tick` = N, as `AMBIGUOUS_TARGET` is), **`CONFLICT` W** (`conflictAt` = the route; alternative = `cancel_route <id>`). Commit: `DROPPED_INVALID` N (no signal left ahead, or no chain of `for_train` from its live position passes `clear_of` at D). |
| `release_hold(train)` | **Kept** | same | The train holds a hold, and the command is not on the emergency channel. | `NO_ROUTE_HELD` R (`detail`: "no hold to release"). On the emergency channel: `DROPPED_INVALID` N. |
| `no_op(reason)` | **Changed**: `reason` optional, at most 8 words | `no_op(reason?)` | Always legal. It is counted as a silent cycle (`TickOutcome.LLM_NO_OP`). | — |

All tools also share the batch codes `ACTION_LIMIT_EXCEEDED` W and `DUPLICATE_ACTION_THIS_TICK` R.

**Why each change was made.**
- **`via`** closes O-h. The LLM names a candidate by the route key shown after "via". The translator reads the
  route table, never the ranking, and never breaks a tie by cost (R6). `via` stays optional: most targets have
  one chain, and leaving it out saves about 15 output tokens, about 6 s at 2.6 tok/s (estimate).
- **`emergency`** puts a cancel onto the R9 channel (§2.6).
- **`for_train` / `clear_of`** (RCE 7). In railway English "points" means a switch, so `point` would be
  ambiguous for a signal or an InOut. The call now reads "hold Train 5 for Train 3, clear of S12".
- **The `CONFLICT` refusal on `hold_train`** (RCE 1a). A held train whose route is already set can lock the
  switches the other train needs. That gives a mutual block that only #1485 would catch. Real *křižování* holds
  the train at a STOP signal **before** its route is set. The LLM therefore cancels first.
- **The time form of `hold_train` is dropped.** It would duplicate the timetable dwell, or ask a model that sees
  the state late to time to the second. The low-level hold keeps a time form for fixtures (§2.5), and P5 asks
  the owner about it.
- **Narrowing C5 (#1475).**
  - For the tail inside an entered route, the LLM's first command is `renew_route`. Releasing that tail is
    *nouzové rušení závěru*.
  - Whole unentered routes ahead of the stopped train stay cancellable by id.

**Design rules judged.**
- **Origin worked out by the routing side: accepted.** `RouteRequestAdmission.legalOriginsOf` (#1456 Q6) gives
  it. No V2 tool has a `from` argument, which removes the reversed-route and wrong-origin defects the
  `RequestRouteTool` KDoc records.
- **"Every name comes from a list the cycle message offers": changed.** Every name must come from a list in the
  **prompt**:
  - train names, route ids and `via` keys from the cycle message;
  - `clear_of` and static targets from the STATION TOPOLOGY block of the system prompt, so that the
    KV-cached prefix stays reusable.
- **At most 3 arguments: accepted.**
- **Small tool results: accepted**, with the caps above. A tripwire test checks the length of every result
  template.
- **Versioned surface: accepted.**
  - `ToolSurface { V1, V2 }` replaces `ALLOWED_NAMES`, and the check becomes `assertExactly(tools, version)`.
  - `BASELINE`, `REVISED` and `REVISED_STRUCTURAL` map to V1. A new SP11 variant maps to V2.
  - The run JSON records the surface version (#1476).
- **Frozen variants keep the 4 old tools: accepted.** They keep their schema text byte for byte, and their
  behaviour too (§2.7).

### 2.4 Low-level vocabulary (`:core` `commonMain`, package for #1478)

```kotlin
sealed interface LowLevelCommand { val author: ActionAuthor; val parentSeq: Long }
data class SetRoute(train: String, fromName: String, toName: String, routeKey: String,
                    precondition: RoutePrecondition?, countsBlockTransition: Boolean = false)
data class ReleaseRoute(routeId: String)          // the chain is worked out at commit (below)
data class ReleaseTrainRoutes(train: String)      // V1 cancel_route only: today's by-train release
data class RequestTrainRoute(train: String, fromName: String, toName: String) // V1 request_route only: today's endpoint request
data class RenewRoute(routeId: String)
data class AdmitTrain(train: String)
data class Hold(train: String, release: HoldRelease)
data class ReleaseHold(train: String)
data class SetSwitch(switchName: String, conf: RailSwitch.Conf)   // OPERATOR in MANUAL only
sealed interface Restricting : LowLevelCommand
data class EmergencyCancel(routeId: String) : Restricting         // §2.6
data class EmergencyHold(train: String) : Restricting             // EMERGENCY_SYSTEM (C13)
```

`ActionAuthor` moves to `:core`, next to `HighLevelCommandSource`.

**`RequestTrainRoute` (V1 only).** At D it calls today's endpoint request, `NetworkActuatorPort.requestRoute(train,
from, to)` with no precondition.
- The kernel keeps today's candidate selection between the two named endpoints.
- The applier keeps everything `DispatchDecisionApplier.applyRequestRoute` does today: the `appliedRequestRoutes`
  duplicate guard, the #1304 failed-reservation count and the route-request announcement.
- The translator passes the endpoints through unchanged, reads no route table and no ranking, and raises no
  `AMBIGUOUS_TARGET`.
- Like `ReservePath`'s `precondition = null`, no re-check (#1465) answers `PRECONDITION_CHANGED` where today's
  call answers `Conflict`.

**Cancel (`ReleaseRoute`) and its chain.** The cancel is the same for `cancel_route`, for the cancel half of
`reroute` and for the release half of `EmergencyCancel`.
- **Worked out at commit (TSE 5).** At D the simulation thread builds the chain from the live per-route sets:
  Rk, then each route of the same train that starts where the one before it ends, and so on. So a route granted
  between N and D is not left stranded.
- **Only chained routes go (RCE 5).** A later route the train could still reach some other way is not part of
  the chain and is kept.
- **One trim.** The chain is freed in one approach-locked trim of the train's holding, back to the blocks before
  Rk, with one preflight. The train's `PathInfo` must stay contiguous (`canTrimPathInfoTo`).
- **All or nothing (TSE 5).** An untrimmable block refuses the whole chain with `RELEASE_REFUSED` W.
- **No gap (RCE 5).** If any block of the chain is inside the approach-lock deferral window, the whole chain
  defers as a unit, and no route ahead of it is freed early. The retries keep the original author (C7).
- **Order of the act.** Each signal of the chain is put to STOP first, newest route first, the reverse of the
  setting order: the dispatcher's *rušení jízdní cesty*.
- **Blocks ahead of a train stay reserved.** The blocks of an entered route ahead of the train are never in the
  chain. `CancelRouteCascadeTest` pins that.
- **Owner:** the per-route release is new and belongs to #1469.

**Switch, signal and section commands.**
- **Switches** move only inside the reservation transaction (`CandidateCommitter`,
  `configureAndRegisterSwitches`). `SetRoute.precondition` carries the expected `switchSettings` of the route.
- **`SetSwitch`** is the single switch operation (*jednotlivé přestavení výhybky*), for OPERATOR in MANUAL mode
  only. No LLM command is ever translated into one. It is refused with `CONFLICT` W (`conflictAt` = SWITCH)
  when the switch is:
  1. locked in a route;
  2. occupied;
  3. held as flank protection.

  A test pins case 2.
- **Signals.** There is no proceed command: a signal clears only by setting a route. A calling-on aspect
  (*přivolávací návěst*) is out of scope on purpose.
- **Track sections** are reserved only as routes. A one-section hop is a `SetRoute` over a route of one section.

**`ReservePath` and its discriminant (#1456 Q2, audit §10).**
- `ReservePath` stays as it is in the inline rule-based arm until SP11. #1483 does not touch it.
- The discriminant moves into the low-level vocabulary as `SetRoute.countsBlockTransition`. Only the
  translation of a `ReservePath` sets it to true.
- When the flag is true, the low-level applier keeps all of today's behaviour (TSE 4):
  - the duplicate guard `DispatchDecisionApplier.appliedReservations` (a duplicate hop is skipped and not
    counted);
  - `incrementBlockTransition` on `Reserved`;
  - `incrementFailedReservation` only when `FailedReservationKind.of(result)` is not `null`.
- `announceRouteRequest` (`RouteGranted`/`ReservationRefused`) runs whatever the flag is.
- The inline translation passes `precondition = null`. So no re-check (#1465) can answer
  `PRECONDITION_CHANGED` where today's call answers `Conflict`, and `failedReservations` stays as it is.
- `RouteScope` stays a separate validator fact.
- **Pin:** the #895 bracket (the Block transitions column) and `ControlArmGoldenOutcomeTest`, unchanged, both
  equal on base and head.

**`SetSignalAspect`, `SetSwitchPosition` and `PathCommandTranslator`.**
- `PathCommandTranslator.translate` reads a live `SimulationContext`, which the thread rule forbids. Its output,
  switch moves followed by a FREE signal, is not the commit unit.
- **Delete it**, together with `PathCommandTranslatorTest`. This ADR is the ruling its #978 KDoc asks for.
- `SetSignalAspect` and `SetSwitchPosition` lose their only constructor. They are replaced by the STOP that
  `EmergencyCancel` gives and by `SetSwitch`. They are deleted when `DispatchDecision` retires (SP11).

### 2.5 The hold on `TrainLifecyclePort` (unblocks #1483, #1484)

**Railway meaning.** A held train does not pass its hold signal. The hold signal is the signal the train stands
at, or else its `signalAheadName`. While the hold lasts, that signal shows STOP for this train.
- For a train that is moving while its signal ahead shows proceed, the hold is legal only under the ideal stop
  (B1 tripwire).
- Because `hold_train` refuses a conflicting set route (§2.3), a give-way normally holds the train before its
  route is set. That is the real *křižování* / *předjíždění*.

```kotlin
sealed interface HoldRelease {
    data class AfterTrainClears(val trainId: String, val clearOfName: String) : HoldRelease
    data class AtSimTime(val simTime: Double) : HoldRelease   // fixtures
    data object OnRelease : HoldRelease                       // emergency: no automatic release
}
interface TrainLifecyclePort {                 // no longer a fun interface
    fun hold(trainId: String, release: HoldRelease): HoldOutcome     // Accepted | Refused(Denial)
    fun releaseHold(trainId: String): HoldOutcome
}
```

**How "until X clears P" is expressed.** It is a condition on the port, not a re-check by the translator. The
routing thread has no retry loop and reads no live state (#1474 §3.3). The rules (TSE 3):
- **Latch.** A per-hold latch "X has occupied a block bounded by P" is **initialised from live occupancy when the
  hold is installed** at drain D, on the simulation thread: it is set at once if X then occupies a block bounded
  by P. After that it is set from the occupancy event (`PathReservationRegistry.addBlockOccupancyListener`).
  Without the initial read, an X already standing on such a block when the hold arrives would never set the
  latch, because its occupancy event predates the hold, and the held train would wait until X exits. Sampling
  every 2 s would miss an X that crosses a 5 m block in well under 2 s (estimate).
- **X's position and the side of P.** X's position is the block of X's tail, its rearmost occupied block, or
  its entry InOut while X is queued. A train has cleared a point only when its tail has passed it, so the tail
  decides the side:
  - **Approach side:** X's tail has not passed P. This includes an X that straddles P, with its front beyond P
    and its tail still on the approach block. The hold is legal, and if X occupies a block bounded by P at D, the
    install-time latch rule above applies.
  - **Far side:** X's tail has passed P, so P is behind the whole train. The refusal rule below applies.
- **An X that will never pass P is refused (owner ruling 2026-11-28).** The hold is refused when no route-table
  chain from X's position to its exit passes P, which includes every far-side X: at pre-check (O and N) with
  `CLEAR_OF_NOT_AHEAD_OF_FOR_TRAIN` R (a #1463 denial candidate, §2.3) and again at commit at D with
  `DROPPED_INVALID` N. The commit rule catches an X whose tail passed P between N and D. Such a hold could
  otherwise end only when X exits or by the #1485 idle-work trigger.
- **Release test.** X clears P when the latch is set and X now neither occupies nor holds a block bounded by P,
  or when X has exited.
- **Where it is evaluated.** In `ShuntingLoop.iteration`, after `expireStaleReservations` and before the
  `TickObservation` is published, so the snapshot shows the released state. This is a `sim/` change, approved by
  the TSE.
- **What the hold may not add.** No new kDisco `Process`, no new `waitUntil` and no scheduled event. With an empty hold table
  the evaluation is a no-op, so #895 stays byte-equal.
- **Train side.** A held train whose route is still set waits level-triggered in
  `Front.holdShortOfRestrictiveSignal` or in `semaphoreAction` (`waitUntil(allowingSignal)`). The aspect change on
  the simulation thread wakes it.
  - A held train with **no** reserved continuation, which is the normal case after the cancel that §2.3
    prescribes, waits in `Train.Site.actions` → `waitForPathOrReportStall`, before any signal wait. While a hold
    is live on that train, the path-wait horizons (`OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS`, the 60 s WARN, and
    `OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS`, the 180 s `errorStop`) do not run. After the hold ends, both
    horizons are measured from the later of the wait start and the end of the hold, so a give-way is never ended
    by the stall stop.
  - The wait condition stays one level-triggered `waitUntil`, re-tested after every event, and the hold-table
    change at the control step is such an event. No kDisco `Process` or scheduled event is added. With no hold
    on the train the condition is today's, so unheld trains keep their bounds and #895 stays byte-equal. A
    held train is bounded by the #1485 idle-work trigger.
  - This hold-aware horizon was ruled by the repository owner on 2026-11-28 in the review of PR #1512; the
    `sim/` change is made in a later leaf with traffic-simulation-expert review, heavyTest and the #895
    bracket. It replaces the "no train-side change" statement of the signed-off #1486 review (TSE item 3).

**When the hold releases.** The port clears the signal again if the train's route is still set. This is E7, the
excluded automatic whose predecessor is the `hold_train`, with author `LLM`.
- **`OnRelease` holds never release by themselves (RCE 2).** These are the emergency holds. Only a `release_hold`
  sent outside the emergency channel ends one.
- **No clock releases a hold.** A hold that never clears is caught by the #1485 idle-work trigger.

**Clocks (RCE 1b).** While a hold is live, all three clocks (registration age, `TailStallTracker`, the sweeper's
`holdings`) stop for the held train's routes. They restart from zero when the hold ends, so a give-way is never
ended by a counted `EXPIRY`.

**The duration form.** `AtSimTime(t_D + d)` replaces `holdTrain(trainId, holdDurationSeconds)`, where t_D is the
simulated time in seconds at drain D, read on the simulation thread when the hold commits, never the tick number
D. The hold releases at the first control-step evaluation at or after that time, so its resolution is one control
step (2.0 simulated s). `holdTrain` is removed from the port.

**`release_hold` needs a port method**: `releaseHold`. It is not dropped.

**No signal ahead.** A train on its last section, with no signal left ahead, gets `Refused(DROPPED_INVALID)`, class
NEVER.

**Production class.** A new `DefaultTrainLifecyclePort(env)` in `:core` `ports`, built like
`DefaultNetworkActuatorPort`.
- It keeps the hold table and sets STOP through the actuator's `setSignalAspect` path.
- A held signal must not be cleared again. That needs a check in `ClearedSignalLedger`, which the TSE approves in
  #1484.
- `DispatchDecisionApplier` and `wireSynchronousDispatcher` receive the port. Today the production applier is
  built without one.

**What #1483 removes, keeps and converts.**
- **Removes only:** `DispatchDecision.HoldTrain`, `DispatchDecisionKind.HOLD_TRAIN` and `applyHoldTrain`.
  `ConflictResolution.HoldTrain` is a different type and stays.
- **Keeps:** `TrainLifecyclePort`, in its new shape.
- **Converts** the `HoldTrain` fixtures to `Hold(train, AtSimTime(…))`: `DispatchDecisionKindTest`,
  `DispatchDecisionListenerHubTest`, `DispatchDecisionSp2b1Test`, `DispatchDecisionSp2b5Test`,
  `SynchronousDispatcherWiringTest`, `ToolDrivenDecisionsOutcomeCallbackTest`, `DispatchDecisionApplierTest`,
  `DispatchDecisionApplierModeGatingTest`, `DispatchDecisionMetadataTest`, `RuleBasedEmissionStrategyTest` and
  `Sp2c21MetricsRecorderTest`.
- **Edits** the main-code mentions in `SynchronousDispatcherWiring`, `ToolDrivenDecisions` and
  `Sp2c21MetricsRecorder`, and the KDoc of `ReactiveTrainActuator` and `CandidatePathRuleEngine`.

### 2.6 Emergency channel (unblocks #1484)

**What enters it.**
- The channel accepts only `Restricting` commands. Anything else that reaches it, by replay or decoding, is
  refused with `DROPPED_INVALID` N.
- So do `release_hold`, `renew_route`, a proceed command, a switch command, and a lone release.
- A lone `ReleaseRoute` cannot be expressed on the channel. A release counts as restricting only inside
  `EmergencyCancel`, behind its own STOP (RCE 3).

**What the LLM posts.** `cancel_route(route, emergency = true)` becomes `EmergencyCancel(routeId)`.
- It is posted to the low-level emergency queue as soon as the tool runs. It is not part of the cycle's routing
  batch, so a stale batch cannot take it down (TSE 1). A cycle that later times out does not withdraw it.
- `EMERGENCY_SYSTEM` (C13) may also post `EmergencyHold`, which is `Hold(train, OnRelease)`.

**At commit, on the simulation thread.** The emergency queue is drained first, at every drain, before the main
batch (#1450 ruling). The command reads no snapshot (TSE 1). The signal is looked up from the #1469 route record
at D:
- **Route not entered:** its start signal goes to STOP first, then the §2.4 chain cancel runs.
- **Route entered (RCE 3):** the next signal ahead of the train goes to STOP. The release half is refused with
  `TRAIN_ON_RESERVED_BLOCK` R until a test pins that every block ahead of the train stays reserved. The STOP
  stands either way.
- **Freed id:** refused with `NO_ROUTE_HELD`.

**`STALE_SNAPSHOT` (Q-TSE1, ruled).** The channel is exempt from `STALE_SNAPSHOT` under three conditions:
1. the lowering reads no snapshot, so replay needs none;
2. D−N is recorded for every emergency command, with N = the cycle's tick, and D−N > 1 is counted as an R9
   latency breach (key for #1476). P (`emergencyPostedTick`) is stamped under the queue lock and recorded.
   D − P ≤ 1 is guaranteed, and D − P > 1 is a defect (#1450 ruling);
3. emergency commands are not part of the routing batch.

The channel also skips the SEMI_AUTO row.

### 2.7 `DispatchAction`, `KoogAgentPlanAdapter` and the modes

**`DispatchAction` is replaced by `HighLevelCommand`**, a sealed type in `:core` `commonMain`. Its subtypes mirror
the V2 tools. The V1 tools map onto it as follows, keeping today's behaviour:

| V1 `DispatchAction` | Command |
|---|---|
| `ApproveTrain(t)` | `ApproveTrain(t)` |
| `RequestRoute(t, from, to, scope)` | Low-level `RequestTrainRoute(t, fromName = from, toName = to)`: today's endpoint request with today's candidate selection (§2.4). V1 only. The caller's `from` is passed through. Its in-turn checks stay `RequestRouteTool`'s own (`ORIGIN_NOT_AT_TRAIN_POSITION`, `TARGET_NOT_TRAIN_DESTINATION`, `TARGET_NOT_NEXT_SECTION`, `ROUTE_SPANS_ENTRY_TO_EXIT`). No `via`, no `AMBIGUOUS_TARGET`. |
| `CancelRoute(t)` | Low-level `ReleaseTrainRoutes(t)`: today's by-train `releasePathDetailed`, which also frees the un-travelled tail of an entered route (TSE 6). V1 only. |
| `NoOp` | `NoOp` |

"Today's behaviour" means the same tool schema, the same in-turn checks and the same kernel call at commit. One
difference is inherent to R8 and applies to every threaded command: a V1 command commits at the first drain D after
it arrives, and is refused with `STALE_SNAPSHOT` when D−N > K (requirement 1).

**`KoogAgentPlanAdapter`.**
- Today `plan` returns `List<DispatchDecision>`. On the LLM path the list is always empty, because the tools emit
  through `SinkHolder`. After `runFallback` it holds the rule-based decisions.
- After #1413 and #1481 the adapter produces **one high-level batch per cycle**. Tools append to the batch, and
  the adapter posts it once at the end of the cycle. A timed-out cycle drops its batch. Emergency commands it
  already posted stay in the emergency queue and commit (§2.6, ADR part 1 §3.2).
- Emergency commands bypass the batch (§2.6).

**Modes.**
- **AUTO:** batches go through the high-level hand-off (`:dispatcher-agent`) to the routing thread; inline arms call the translator directly.
- **SEMI_AUTO:**
  - A row is translated when it is posted, which stamps N.
  - The row commits at the first drain after approval, and D−N > K refuses it with `STALE_SNAPSHOT`.
  - An approved row has author `OPERATOR` (#1475 C12).
  - A rejected row is recorded as "operator rejected" (key for #1476). That is not a denial.
- **MANUAL:** commands come from the GUI, with author `OPERATOR`. `SetSwitch` is legal only here.

---

### Named tests

- **#1483:**
  - the fixture conversions (§2.5);
  - `TrainLifecyclePortHoldReleaseTest`: `AfterTrainClears` with X passing, X exiting and X never arriving; X
    already on a block bounded by P when the hold is installed (the hold releases when X leaves P, not when X
    exits). The install-case fixtures put X on the **approach-side** block bounded by P, once wholly on it and
    once straddling P with its tail still on it (§2.5), so this case exercises only the install-time latch
    rule; `AtSimTime` (a hold of d = 10 s committed at a late tick, for example tick 50, releases at t_D + 10 s,
    not at once); `releaseHold`.
  - `HoldClearOfNotAheadRefusedTest`: a hold whose `for_train` has no route-table chain to its exit that passes
    `clear_of` is refused with `CLEAR_OF_NOT_AHEAD_OF_FOR_TRAIN` at O and at N (recorded with `tick` = N), and
    with `DROPPED_INVALID` at commit when X's tail passes `clear_of` between N and D. Its fixtures put X on the
    **far side** of `clear_of` (tail past it, §2.5), so this test exercises only the refusal rule; the
    approach-side and straddle fixtures belong to `TrainLifecyclePortHoldReleaseTest`.
- **#1484:**
  - `EmergencyChannelRestrictingOnlyTest`: every non-restricting command and a lone release give
    `DROPPED_INVALID`.
  - `EmergencyCancelEnteredRouteStopsTest`: the STOP is carried out and the release is refused with
    `TRAIN_ON_RESERVED_BLOCK`.
  - `EmergencyHoldNoAutoReleaseTest`.
  - `EmergencyLatencyRecordedTest` (D−N and P recorded; D−N > 1 counted).
  - `EmergencyStopNextDrainTest`.
  - `EmergencyStopAppliedAtFirstDrainAfterPostTest`, `EmergencyChannelNoEngineCallArchitectureTest` and
    `EmergencyLatencyBudgetTripwireTest` (#1450 ruling; Part 1 test table).
  - `EmergencyPostedBeforeTimeoutCommitsTest`: an `EmergencyCancel` posted by a cycle that then hits its deadline
    commits at its D with author `LLM`, and the cycle is not recorded as `TIMEOUT_NOOP`.
  - `HoldLatchShortBlockTest`: a 5 m block crossed between two control steps still releases the hold.
  - `HeldTrainNeverStallsTest`:
    - a held train whose route stays set never enters `waitForPathOrReportStall`;
    - **no-route case:** a train whose route was cancelled and that is then held (fixture `AtSimTime` hold
      longer than `OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS`) waits beyond 180 simulated s with no WARN and no
      `errorStop`;
    - after the hold ends, a train that still gets no route WARNs 60 s and stops 180 s after the hold end;
    - an unheld train in the same fixture still stops at 180 s.
  - `HoldStopsExpiryClocksTest`.
  - `HoldTableEmptyByteEqualTest` (#895).
- **#1469:** `RouteIdStabilityTest` (one id per successful commit, a counter per registry, no id for an aborted
  attempt, no id reused, the same on replay); `RouteReadingLiveEqualityTest`.
- **#1480:**
  - `SetRouteViaTest`: `AMBIGUOUS_TARGET` at O and at N, keys in key order.
  - `TranslatorNeverReadsRankingArchitectureTest`.
  - `CancelRouteCascadeTest`: chain only; all or nothing; deferral without a gap; the blocks of an entered route
    ahead of the train stay reserved.
  - `HoldTrainConflictRefusedTest`.
  - `ReservePathCountsBlockTransitionTest`, together with the #895 bracket and `ControlArmGoldenOutcomeTest`.
  - `V1CancelRouteByTrainTest`.
  - `V1RequestRouteEndpointTranslationTest`:
    - on a target with two legal chains from the given origin, V1 `request_route(t, from, to)` raises no
      `AMBIGUOUS_TARGET`;
    - the kernel receives the caller's `from` and `to` unchanged;
    - the granted blocks equal those of today's `DispatchDecision.RequestRoute` through `DispatchDecisionApplier`
      on the same state, pinned equal on base and head;
    - a `from` that differs from the V2 `legalOriginsOf` pick is passed through, not replaced.

    It sits next to `ToolSurfaceVersionTest` (V1 schema byte-equal), so both the schema bytes and the railway
    outcome are pinned.
  - `SetSwitchRefusalsTest`, with the occupied case.
- **SP11 / #1445:** `ToolSurfaceVersionTest` (V1 is byte-equal to today); `ToolResultSizeTripwireTest`.

### Open points

- **P1 → #1475:** the C5 "LLM first" cell, now in part 1b (§1b.2).
- **P2 → #1463 / #1464:** the `AMBIGUOUS_TARGET` row and the `NO_ROUTE_HELD` reuse for `release_hold` with no hold (§2.3); the candidate row `CLEAR_OF_NOT_AHEAD_OF_FOR_TRAIN` (PRE_CHECK, REPLAN, PLAIN; owner ruling 2026-11-28, §2.3, §2.5). Also check the pre-check use of `CONFLICT` in the `hold_train` row against the #1463 table, where `CONFLICT` is a commit code.
- **P3 → #1476:** the new author values; the surface-version key; the operator-rejected key; the R9
  latency-breach key; the `emergencyPostedTick` key.
- **P4 → #1469:** the per-route block sets, the per-registry id counter and the per-route approach-locked
  release.
- **P5 → owner:** whether the time form of `hold_train` returns for the LLM, as a V3 surface.
- **P6 → future leaf (OPERATOR, MANUAL only):** *nouzové rušení závěru* as a low-level command, done only after a
  person has checked that the track is free, with one count per act on a *počítadlo nouzových obsluh* counter.
  It is never an LLM tool.
- **P7 → #1478 / #1480:** `RequestTrainRoute` (V1 only) in the low-level vocabulary (§2.4, §2.7).
