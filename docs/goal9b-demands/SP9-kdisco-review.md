# SP9 — kDisco deadlock and race review

**Issue:** bedaHovorka/interlockSim#1156 (Goal 1B, SP9, acceptance **B7**)
**Date:** 2026-10-10 · **Time-box:** 2 days (research note) · **Review:** kotlin-tech-lead
**Engine reviewed:** kDisco `main` @ `b0828aa`, `version=0.6.3-SNAPSHOT` (repo `bedaHovorka/kdisco`; it has no `develop` branch)
**Engine consumed by interlockSim:** `kdiscoVersion=0.6.2` ([gradle.properties](../../gradle.properties)) — **unchanged by this work**

> **Review only.** SP9 files issues; it changes no kDisco code and no `kdiscoVersion`. Section 7
> contains ready-to-file issue bodies, and section 4 ready-to-post classification comments. They are
> not yet filed or posted; see section 8.

---

## 1. Scope and method

Reviewed by reading the current kDisco sources (`Simulation.kt`, `Process.kt`, `EventQueue.kt`,
`SimulationContext.kt`, `SimulationContextHolder.kt` and its jvm/js/native actuals, `Monitor.kt`,
`Variable.kt`, `Resource.kt`, `Link.kt`, `SimulationController.kt`, the root build script and
`gradle.properties`) together with the interlockSim code that drives them
(`DefaultSimulationContext`, `MultiTrainLoop`, `ShuntingLoop`, `Train`, `SimulationRunner`,
`gui/Frame`, `MultiTrainLoopSnapshotRaceTest`).

The three prose leads named in the issue were read and **all three are history, not live defects**:

| Lead | Verdict |
| --- | --- |
| `TEST_HANG_INVESTIGATION.md` (kdisco repo root) | 2026-02-08, jDisco-wrapper era. It reports continuous-simulation tests that hang; the root cause was never found, and its thread-deadlock theory is unverified. The wrapper no longer exists, so it is history. "History" here is an inference, not a documented fix. **F1** (unbounded `RESTART` retry) is a plausible but unconfirmed candidate cause. Do not re-file. |
| `JDISCO_OBSERVED_ISSUES.md:50-56` | Same era (jDisco 1.2.0 wrapper). It records only that each process ran in its own thread and that `Head` and `Link` were not thread-safe. It says nothing about exceptions, so kdisco#83 is a newer, independent finding. |
| `KDOC_EXPERT_REVIEW_REPORT.md:149-165` and `:169-185` | Finding 7 (thread model undocumented) and finding 8 (`@Volatile` stop-flag race). Both describe the jDisco thread-per-process model. They are folded into **F4**/**F5** below rather than re-filed. |

Nothing in this review was reproduced under a race detector — see section 5 for why none is installed
today, and what to install.

## 2. Engine concurrency model (what kDisco actually is)

1. **Single-threaded by usage, not by construction.** `Simulation.run` launches every process body
   into `CoroutineScope(Dispatchers.Unconfined + SupervisorJob())`. `Unconfined` gives **no** thread
   confinement: a continuation runs on whichever thread resumes it. The engine is single-threaded
   only because every process resumption is issued by the event loop (`cont.resumeWith`) on the
   thread that called `run`. A process body that awaits something kDisco does not own (a foreign
   continuation, channel or flow fed from another thread) can continue on that other thread.
2. **No synchronisation primitives.** The engine contains no locks, no atomics and no `@Volatile`
   fields — except `SimulationController` (`paused`, `stepsRequested`, `throttleFactor`), which is
   explicitly designed for cross-thread control. `ContinuousMonitor`'s KDoc states that it is "not
   thread-safe".
3. **Determinism is structural, not enforced.** `EventQueue` breaks equal-time ties by insertion
   counter (FIFO for ordinary events, LIFO for priority events) and keeps a `mutations` counter so a
   step can notice that user code touched the queue while the step was running.
4. **Context lookup is thread-confined on JVM only.** `Process.activeContext` resolves through
   `SimulationContextHolder`; the `jvmMain` actual is a `ThreadLocal`, the `jsMain` and `nativeMain`
   actuals are a plain global `var`.
5. **Teardown is cooperative.** `run()` cancels `simScope`, joins it under `NonCancellable`, marks
   still-scheduled processes that never started `TERMINATED` and clears the queue. Processes that
   did start are ended by the cancellation itself, and no `ProcessTerminated` event is emitted for
   that teardown.
6. **Process exceptions are not propagated to the embedder.** Only `ProcessTerminatedException` is
   caught deliberately. Any other throwable fails the launched child coroutine. kDisco installs no
   `CoroutineExceptionHandler`, so the failure goes to the platform uncaught-exception path (a stack
   trace on stderr on the JVM). The `finally` block still marks the process `TERMINATED` and emits
   `ProcessTerminated`, and `run()` still returns `true`. The failure is visible in the log but
   cannot be observed through the API (this is kdisco#83).

## 3. Findings

Each finding states its **home** (which repository should carry the issue) and the classification
line required by the issue.

### F1 — Unbounded `RESTART` retry loop in `Simulation.run` can livelock at a fixed simulated time

`Simulation.run` drives `while (context.monitor.integrateUntil(...))` (`Simulation.kt`). When user
code queues or drops a turn during a step, the step returns `StepOutcome.RESTART` and the step is
retried. Two cases differ:

- **Mutation restarts** (`Monitor.kt:132-134`, and `locateCrossings` at `:251`) unwind the
  variables and the clock to the step start. Simulated time does not advance, so every guard that
  watches simulated time (including interlockSim's own run-until-time limits) is blind to the retry.
- **The notice-release restart** (`Monitor.kt:146-152`, after `checkWaitNotices` and
  `checkLevelCrossings` when user code also changed the queue) does **not** unwind: the accepted
  step-end state is kept.

In both cases the retry count is unbounded and nothing is logged. The loop calls only
`ensureActive()`, so wall-clock cancellation from outside can stop it. `beforeEvent` is deliberately
skipped on a retry, so a `SimulationController` pause cannot interrupt it either. A livelock needs
user code that changes the queue on every retry; termination is not guaranteed, but it is not the
normal case. The symptom is a process that burns wall-clock at a constant `simTime`. This shape is
consistent with the 20-train stall tracked in bedaHovorka/interlockSim#591, but the cause of that
stall has not been confirmed as this loop.

- **Home:** kdisco · **Severity:** high
- **prerequisite-of-1B: yes** — without a bound, a counter or a log line, a Goal 1B scale run that
  livelocks is indistinguishable from one that is merely slow.

### F2 — Continuous solver limits are silent

`ContinuousMonitor.probeStateAt` stops after `MAX_PROBE_SUB_STEPS = 1000` re-integration sub-steps
(`Monitor.kt:352`) and can leave the clock short of its target with no log, flag or exception. A
train whose threshold-crossing time (for example reaching a block boundary or a switch) was affected
may then trigger that event late or at the wrong simulated time, with no diagnostic. The other limit,
`MAX_BISECTION_ITERATIONS = 100` in `locateCrossingTime`, is effectively unreachable: the bracket
collapses to floating-point resolution (`Monitor.kt:316`) after about 60 iterations, and the cap
returns the upper bracket anyway. Only the probe limit needs a log line.

- **Home:** kdisco · **Severity:** medium
- **prerequisite-of-1B: waivable** — a log line at `warn` would be enough; the scale scenarios can
  run without it.

### F3 — No preemptive test timeout anywhere in kDisco

kDisco sets no `Test.timeout`, uses no JUnit `@Timeout`, and relies on a handful of
`runTest(timeout = …)` calls plus CI `timeout-minutes: 20`. As section 5 documents,
`runTest`'s timeout is **cooperative**: it is enforced by coroutine cancellation, so it cannot stop
a test body that never suspends, which is precisely the F1 shape. A hung kDisco test therefore burns
the whole CI job.

- **Home:** kdisco · **Severity:** medium
- **prerequisite-of-1B: waivable** — it costs CI time, not correctness, but it is the cheapest of all
  the fixes listed here.

### F4 — `Simulation.stop()` writes a non-volatile flag

`stop()` sets `SimulationContext.stopRequested`, a plain `Boolean`. A caller on another thread has no
guarantee that the engine thread ever observes the write. interlockSim does not currently depend on
it — `SimulationRunner.stop` interrupts the simulation thread — but any future "ask it to stop
politely" path would be relying on an unpublished write.

- **Home:** kdisco · **Severity:** medium
- **prerequisite-of-1B: no** — Goal 1B does not stop simulations from another thread. Recorded as a
  Goal 9B demand (section 6).

### F5 — No documented off-thread read contract

`Variable.state` is a plain `Double`; discrete fields are plain fields. Reading any of them from a UI
or metrics thread is a stale-or-torn read with no documented guarantees, and the KDoc says nothing
about it (the gap `KDOC_EXPERT_REVIEW_REPORT.md:149-165`, finding 7, noticed for the old
thread-per-process model).

- **Home:** kdisco (documentation, then possibly API) · **Severity:** medium
- **prerequisite-of-1B: no** — but every embedder, including Goal 9B, needs the answer written down.

### F6 — `SimulationController` resume depends on the dispatcher that runs `Simulation.run`

`SimulationController.beforeEvent` runs inline in `Simulation.run`, so `pauseChannel.receive()`
suspends the coroutine that is executing `run`, not a process coroutine. `resume()` and `step()` only
call `trySend`, which never runs the receiver inline. After the receive, `run` continues on the
dispatcher of the coroutine that called it:

- Under `runBlocking` — what `DefaultSimulationContext.run` uses — that is the simulation thread, so
  the engine is correct.
- If `run` were started on `Dispatchers.Unconfined`, the receive would resume inline on the thread
  that called `trySend`: the *control* thread, where the JVM `ThreadLocal` context of fact 4 is
  unset.
- On a pool dispatcher, the continuation goes to a pool worker. The original thread, and its
  `ThreadLocal`, are lost.

- **Home:** kdisco · **Severity:** medium
- **prerequisite-of-1B: no** — Goal 1B's runs are headless and never pause. Goal 9B demand.

### F7 — `SimulationContextHolder` is a global on native and JS

The `activeContext` KDoc describes thread-confined lookup, but only the JVM actual is a
`ThreadLocal`; the js and native actuals are a single global `var`. Two simulations in one process
(the `:fast-sim` linuxX64 target is the realistic case) would share it.

- **Home:** kdisco · **Severity:** low
- **prerequisite-of-1B: no** — Goal 1B runs one simulation per process.

### F8 — `Resource.reserve` re-entry grows the waiter chain

An external `reactivate` of a queued waiter re-enters `reserve`; `Link.into` unlinks the waiter first
and re-links it at the FIFO tail, so the waiter loses its place and the continuation chain deepens.
No interlockSim code does this today.

- **Home:** kdisco · **Severity:** low
- **prerequisite-of-1B: no**

### F9 — `MultiTrainLoop.getTrainSnapshot` composes a torn pair (the race named in the issue)

`getTrainSnapshot` (`MultiTrainLoop.kt:726-735`) reads `train.getVelocity()` (which is the live
`Variable.state`, continuously integrated by the engine) and `train.totalDistance` in two separate
reads. If a caller runs on a thread other than the simulation thread, the two halves can come from
different engine steps, producing a snapshot that was never a real state of the train.
`approvedTrains` itself is safe: it is copy-on-write with a documented contract
(`MultiTrainLoop.kt:236-253`).

**Who calls it today.** In production the only caller is
`DefaultCollisionDetectionService.evaluatePredictiveTtc`, through the provider that `ExampleRegistry`
registers. It runs in the collision-event path, which the KDoc at `MultiTrainLoop.kt:246` describes
as the simulation thread. We did not trace the full event-bus call chain, so the thread is not proved.
The GUI and the metrics path do **not** call it. The only deliberate off-thread caller is
`MultiTrainLoopSnapshotRaceTest`, and it passes an absent train id, so it never exercises the pair.
So the torn read is a **latent hazard of the public contract**, not an active defect: it appears if
anyone calls the method from another thread. The predictive-collision check is the one consumer that
uses the pair.

The engine side of this is only F5 (no documented contract); the tearing itself would be created by
interlockSim's composition of two independent reads, and interlockSim is also where it can be fixed
(snapshot under the engine thread, or publish an immutable sample), if an off-thread consumer is
ever added.

- **Home:** **interlockSim** (per the issue's "in kdisco when the cause is in the engine, otherwise
  in interlockSim") · **Severity:** low (latent)
- **prerequisite-of-1B: no** — the Goal 1B scale runs are headless and the only production caller is
  the collision-event path.

## 4. Classification of the linked issues

Ready-to-post comment text, one per issue.

### kdisco#83 — unhandled process exceptions are not propagated to the embedder

> **prerequisite-of-1B: yes**
>
> SP9 (bedaHovorka/interlockSim#1156) classification. A Goal 1B scale run launches many processes; if
> one dies of an unexpected throwable the failure goes only to the platform uncaught-exception path
> (a stack trace on stderr) and `run()` still returns `true`, so the scenario reports success while a
> train has stopped existing and no API call can tell. That makes every scale result unfalsifiable,
> which is why this is a prerequisite rather than a nice-to-have. PR kdisco#84
> (`onProcessFailure` / `ProcessFailed` / `stopOnProcessFailure`) is the accepted shape; Goal 1B needs
> it merged and released, not redesigned.

### bedaHovorka/interlockSim#1112 — consumer half of the absorbed-exception problem

> **prerequisite-of-1B: yes**
>
> SP9 (#1156) classification. This is the consumer half of kdisco#83: once the engine can report a
> failed process, interlockSim must fail the run instead of reporting a clean finish. Its priority
> label (P4) reflects the pre-Goal-1B view; for Goal 1B it is a prerequisite, because acceptance of
> the scale scenarios depends on a failed train being visible as a failure. It is cheap — wire
> `stopOnProcessFailure` once kdisco#84 ships.

### bedaHovorka/interlockSim#1128 — `DynamicRailSwitch.conf` / `locked` read on the EDT without `@Volatile`

> **prerequisite-of-1B: no** (waivable)
>
> SP9 (#1156) classification. The unsynchronised reads are real, but they are reads made by the Swing
> EDT for painting. Goal 1B's scale and routing scenarios are headless, so no Goal 1B result depends
> on them; a stale switch position can at worst paint one frame late. Recorded in SP9's Goal 9B
> demands as a guarantee Goal 9B may **not** rely on (off-thread reads of engine-owned state are
> unpublished), so if Goal 9B introduces an off-thread consumer that *acts* on switch state, this
> stops being waivable.

## 5. Research note: deadlock and race scanners

Surveyed against this engine's shape — **no locks, no `synchronized`, no threads spawned by the
library, coroutines on `Dispatchers.Unconfined`, Kotlin Multiplatform (jvm + js + linuxX64)**. All
status facts below were verified from the tools' own repositories in October 2026; non-GitHub hosts
were unreachable from the research sandbox, so vendor sites are not cited.

### Fits

| Tool | Licence / status | What it gives kDisco |
| --- | --- | --- |
| **Lincheck 3.7** (JetBrains) | MPL-2.0, released 2026-07-29, JVM-only | The most promising fit. It model-checks concurrent code (`runConcurrentTest`), supports `suspend` code, and targets the question kDisco has: is a field written by the engine and read elsewhere safely published? ⚠️ How it handles `Dispatchers.Unconfined` resumption is **unverified** for kDisco; run a small spike before relying on it. |
| **JUnit 5 `@Timeout(threadMode = SEPARATE_THREAD)`** | EPL-2.0, part of JUnit already | The only *preemptive* hang backstop (JUnit 5.9 or later). The default `SAME_THREAD` mode cannot fail a test that spins. `SEPARATE_THREAD` runs the test on its own thread and fails it when the time is up, so the build moves on. A body that ignores interrupts can keep spinning, so a Gradle `Test.timeout` stays the outer net. A timeout must exist first: set `junit.jupiter.execution.timeout.default` (this creates it) and `junit.jupiter.execution.timeout.thread.mode.default=SEPARATE_THREAD` (this only picks the mode), or annotate with `@Timeout`. This is the F3 fix. |
| **`kotlinx-coroutines-debug` / `DebugProbes`** | Apache-2.0, maintained, JVM-only | "jstack for coroutines": a coroutine dump naming the suspended process bodies, at single-digit-percent overhead. `install()` only starts the tracking. A dump needs an explicit `DebugProbes.dumpCoroutines()` call, for example from a JUnit extension that runs when a test fails by timeout. A spinning body may show no suspended coroutine at all, so also take a plain `jstack` thread dump. |
| **detekt `detekt-rules-coroutines`** | Apache-2.0, maintained, source-level, all KMP targets | Hygiene only (`SleepInsteadOfDelay`, `SuspendFunSwallowedCancellation`, …) — it finds no races, but it is the only surveyed tool that covers the js and linuxX64 sources at all. |
| **Fray 0.9.0** (CMU PASTA) | Apache-2.0, released 2026-07-17, JVM-only | Systematic schedule exploration (PCT/POS) with deterministic replay, a JUnit 5 `@FrayTest` and a Gradle plugin. Attractive for replaying an intermittent hang. ⚠️ Its documented control is `Thread`-level; whether it models coroutine dispatch is **unverified** — settle that before adopting. |

### Conditional

- **jcstress 0.16** (GPLv2+CPE, low activity): the rigorous JMM stress harness. Correct for the
  publication questions (F4, F5) but disproportionate; Lincheck answers the same questions with
  Kotlin-native ergonomics.
- **BlockHound** (Apache-2.0, active 2026-10-07): detects blocking calls in non-blocking threads, and
  integrates with coroutines through an SPI. Needs `-XX:+AllowRedefinitionToAddDeleteMethods` on
  JDK 13+. Of limited use while the engine runs under `runBlocking`; it becomes relevant if a
  caller drives `run` from a pool dispatcher (see F6).
- **vmlens 1.2.28** (Apache-2.0, last commit 2026-04-20, bus factor 1): exhaustive interleavings plus
  a race report, with a documented Gradle Kotlin-DSL setup — but **incompatible with JaCoCo**, which
  rules it out for a project that gates on coverage.

### Non-fits, and why (this is the useful half of the research)

The engine's own shape disqualifies a whole class of tools at once:

- **Infer RacerD** (MIT, actively developed) only analyses code reachable from `@ThreadSafe` or
  `synchronized`; kDisco has neither, so RacerD would analyse nothing. It also has no Kotlin frontend.
- **Error Prone `@GuardedBy`** verifies lock discipline — vacuous here, and Java-only.
- **SpotBugs `MT_CORRECTNESS`** / fb-contrib pattern-match lock and `Thread` misuse in bytecode —
  nothing to match.
- **`ThreadMXBean.findDeadlockedThreads()` / jstack** find monitor cycles — always empty, and they
  cannot see coroutines at all.
- **Java PathFinder** is still catching up on Java 11/17 class-library modelling in 2026; Kotlin 2.x
  plus coroutines bytecode is out of reach.
- **RV-Predict** is dead (last substantive release 2019). **jtsan** has no maintained home.
  **ThreadSafe** (Contemplate) could not be verified at all and was Java-only regardless.
- **SonarQube's Kotlin plugin** is now SSAL-licensed and overlaps detekt's coroutine rules.

### Recommendation (for the kdisco repo to act on, not this one)

1. Set `junit.jupiter.execution.timeout.default` together with
   `junit.jupiter.execution.timeout.thread.mode.default=SEPARATE_THREAD` (JUnit 5.9 or later), plus a
   Gradle `Test.timeout` as the outer net — the F3 fix, hours of work.
2. Install `DebugProbes` once per test JVM, and add a JUnit extension that calls
   `DebugProbes.dumpCoroutines()` when a test fails by timeout. Take a `jstack` thread dump too,
   because a spinning body may show no suspended coroutine.
3. Bound and log the F1 retry loop; log the F2 solver give-ups.
4. Only then consider Lincheck, and scope it to the publication questions (F4, F5) rather than to the
   engine as a whole.

**KMP caveat.** Every dynamic tool above is JVM-only — Lincheck is explicitly "a JVM-only project",
and JUnit `@Timeout`, BlockHound, Fray, vmlens, DebugProbes and jcstress are JVM agents, harnesses or
JVM test features. The js and
linuxX64 targets (including `:fast-sim`) get coverage from **detekt alone**. Any plan must say so.

## 6. Goal 9B demands

What Goal 9B may and may not rely on, given kDisco 0.6.2/0.6.3-SNAPSHOT as it stands today.

### Can be relied on

| Guarantee | Holds only if | Basis |
| --- | --- | --- |
| **Deterministic scheduling order.** Equal-time events run FIFO by insertion (priority events LIFO); identical inputs give identical event order. | Process bodies use only kDisco's own scheduling calls. | `EventQueue` tie-breaking by insertion counter. |
| **Single-threaded execution of process bodies.** Process bodies run one at a time, on the thread that called `run`. | Every suspension is resumed by the event loop or kDisco's own primitives, and `run` is driven by `runBlocking` (as `DefaultSimulationContext.run` does). A body that awaits a foreign continuation, channel or flow, or a controller resumed from another dispatcher (F6), can continue on another thread. | `Dispatchers.Unconfined` gives no confinement by itself; the event loop issues every resumption (fact 1, F6). |
| **Active context is correct on JVM.** `Process.activeContext` is right while the process runs on the thread that called `run`. | Same condition as the row above. After a resume on a foreign dispatcher the `ThreadLocal` is unset (F6). | `SimulationContextHolder` jvmMain actual is a `ThreadLocal` (fact 4). |
| **Cooperative teardown.** After `run()` returns, the scope is cancelled, processes that never started are `TERMINATED` and the queue is empty. | `run()` returns normally. It does not return if the F1 loop spins. | `run()` teardown block (fact 5). |
| **Pause/resume resumes on the simulation thread.** | `run` is driven by `DefaultSimulationContext.run` under `runBlocking`. Any other driver moves this to the list below. | F6. |

### Cannot be relied on

| Non-guarantee | Finding | Consequence for Goal 9B |
| --- | --- | --- |
| **Exception propagation.** A process that throws is reported only to the platform uncaught-exception path; `run()` still returns `true`. | kdisco#83, section 2 fact 6 | Goal 9B must not treat "run completed" as "nothing failed" until kdisco#84 ships and #1112 is wired. |
| **Safe publication of engine state to other threads.** `Variable.state` and the discrete fields are plain; off-thread reads may be stale or torn. | F5 (engine: no contract); F9 (interlockSim side, latent) | Any Goal 9B consumer outside the simulation thread must obtain its data from a snapshot taken *on* the engine thread, not by reading fields. |
| **Cross-thread `stop()`.** `stopRequested` is not volatile. | F4 | Goal 9B must stop runs the way interlockSim does today (thread interrupt / run-until-time), not via `stop()` from another thread. |
| **Liveness.** No bound on the `RESTART` retry loop, no preemptive timeout. | F1, F3 | Goal 9B needs its own wall-clock watchdog around every run; it cannot assume the engine will ever return. |
| **Thread confinement outside JVM.** Native and JS hold the active context in a global. | F7 | One simulation per process on `:fast-sim`. |
| **Solver convergence reporting.** The probe sub-step limit fails silently. | F2 | A located crossing time may be a best effort; Goal 9B must not assert on it to full precision. |
| **Pause/resume off `runBlocking`.** | F6 | If Goal 9B drives the controller from another dispatcher, it must re-verify which thread resumes the engine. |

## 7. Issues to file

Six issues, in the order they should be filed. Numbers must be posted on
bedaHovorka/interlockSim#1147 once they exist (acceptance criterion 5).

### 7.1 — interlockSim: `MultiTrainLoop.getTrainSnapshot` is unsafe to call off the simulation thread

> **Repository:** bedaHovorka/interlockSim · **prerequisite-of-1B: no**
>
> `MultiTrainLoop.getTrainSnapshot` (`MultiTrainLoop.kt:726-735`) reads `train.getVelocity()` (the
> live `Variable.state`) and `train.totalDistance` as two independent reads. If a caller runs on a
> thread other than the simulation thread, the two values can originate in different engine steps,
> so the snapshot describes a state the train never had. `approvedTrains` is not affected; it is
> copy-on-write with a documented contract (`MultiTrainLoop.kt:236-253`).
>
> This is a latent hazard, not an active defect. The only production caller is
> `DefaultCollisionDetectionService.evaluatePredictiveTtc`, which runs in the collision-event path on
> what the KDoc describes as the simulation thread. The GUI and the metrics path do not call it. The
> only off-thread caller is `MultiTrainLoopSnapshotRaceTest`.
>
> The engine offers no safe-publication guarantee for these fields and is not expected to (see
> SP9 F5 and the companion kdisco threading-contract issue). Asked for: document that
> `getTrainSnapshot` must be called on the simulation thread, and if an off-thread consumer is ever
> added, take the snapshot on the engine thread and publish one immutable value. Filed by SP9
> (#1156).

### 7.2 — kdisco: bound and log the `RESTART` retry loop in `Simulation.run`

> **Repository:** bedaHovorka/kdisco · **prerequisite-of-1B: yes**
>
> When user code queues or drops a turn during a step, the step returns `StepOutcome.RESTART` and is
> retried. Mutation restarts (`Monitor.kt:132-134`, `:251`) unwind the clock to the step start; the
> notice-release restart (`Monitor.kt:146-152`) keeps the accepted step-end state. In both cases the
> retry count is unbounded and nothing is logged, and `beforeEvent` is skipped on a retry. Where the
> clock unwinds, every simulated-time guard is blind, and the process can spin on wall-clock at a
> constant `simTime`. This shape is consistent with the stall in bedaHovorka/interlockSim#591; that
> cause is not yet confirmed.
>
> Asked for: a bound (configurable, generous), a `warn` log naming the mutating source when the
> retry count crosses a threshold, and a clear exception when the bound is exceeded. Filed by SP9
> (bedaHovorka/interlockSim#1156).

### 7.3 — kdisco: continuous-solver limits fail silently

> **Repository:** bedaHovorka/kdisco · **prerequisite-of-1B: waivable**
>
> `ContinuousMonitor.probeStateAt` stops after `MAX_PROBE_SUB_STEPS = 1000` (`Monitor.kt:352`) and can
> leave the clock short of its target with no log, flag or exception, so a non-converged probe is
> indistinguishable from a good one. The bisection cap in `locateCrossingTime` is effectively
> unreachable (the bracket collapses first, `Monitor.kt:316`) and needs no change. A `warn` log with
> the variable and the interval for the probe limit would be enough. Filed by SP9 (bedaHovorka/interlockSim#1156).

### 7.4 — kdisco: document (and then enforce) the threading contract

> **Repository:** bedaHovorka/kdisco · **prerequisite-of-1B: no**
>
> Four related gaps, best fixed as one documented contract:
> 1. `SimulationContext.stopRequested` is a plain `Boolean` written by `stop()` — a cross-thread
>    `stop()` has no publication guarantee.
> 2. `Variable.state` and the discrete fields are plain; the KDoc never says whether an embedder may
>    read them from another thread (it may not).
> 3. `SimulationController.beforeEvent` suspends the coroutine that runs `Simulation.run`, which
>    resumes on that caller's dispatcher. Under `runBlocking` this is the simulation thread. If `run`
>    is started on `Dispatchers.Unconfined`, the engine would continue on the control thread, where
>    the JVM `ThreadLocal` context is unset; on a pool dispatcher it moves to a pool worker.
> 4. `SimulationContextHolder` is a `ThreadLocal` on JVM but a plain global on js and native, which
>    contradicts the `activeContext` KDoc.
>
> Asked for: a "Threading" section in the engine KDoc stating what is thread-confined, what is
> publishable and how an embedder should sample state; then `@Volatile` on `stopRequested` and a
> documented resume-dispatcher requirement. Filed by SP9 (bedaHovorka/interlockSim#1156).

### 7.5 — kdisco: no preemptive test timeout

> **Repository:** bedaHovorka/kdisco · **prerequisite-of-1B: waivable**
>
> kDisco sets no `Test.timeout` and no JUnit `@Timeout`; it relies on a few `runTest(timeout = …)`
> calls and CI `timeout-minutes: 20`. `runTest`'s timeout is cooperative — it is enforced by coroutine
> cancellation, so it cannot stop a body that never suspends, which is exactly how an unbounded engine
> retry loop (see the `RESTART` issue) hangs. A hung test therefore consumes the whole CI job.
>
> Asked for: `junit.jupiter.execution.timeout.default` (this creates the timeout) together with
> `junit.jupiter.execution.timeout.thread.mode.default=SEPARATE_THREAD` (this picks the mode; JUnit
> 5.9 or later), a Gradle `Test.timeout` as the outer net, and `DebugProbes` installed once per test
> JVM with a JUnit extension that calls `DebugProbes.dumpCoroutines()` when a test fails by timeout.
> A `jstack` thread dump helps too, because a spinning body may show no suspended coroutine. Filed by SP9 (bedaHovorka/interlockSim#1156).

### 7.6 — kdisco: `Resource.reserve` re-entry re-queues the waiter at the tail

> **Repository:** bedaHovorka/kdisco · **prerequisite-of-1B: no**
>
> An external `reactivate` of a waiter already queued in `Resource.reserve` re-enters `reserve`;
> `Link.into` unlinks it and re-links it at the FIFO tail, so the waiter silently loses its place and
> the continuation chain deepens. No current consumer does this, so this is a documentation or
> defensive-check request rather than a bug report. Filed by SP9 (bedaHovorka/interlockSim#1156).

## 8. What this work deliberately did not do

- **No kDisco source change and no `kdiscoVersion` change.** `gradle.properties` still pins
  `kdiscoVersion=0.6.2`; `git diff` for that line is empty (acceptance criterion 4).
- **`Train.waitUntilCrossing` mid-block standstill (2026-08-22) is out of scope** by the owner's
  2026-08-22 ruling: not filed, not fixed, unless it returns.
- **The three historical reports were not re-filed.** `TEST_HANG_INVESTIGATION.md`,
  `JDISCO_OBSERVED_ISSUES.md` and `KDOC_EXPERT_REVIEW_REPORT.md` describe the jDisco-wrapper era.
  kdisco#83 has no precedent in them; the live residue is the documentation gap now covered by
  section 7.4. The hang in `TEST_HANG_INVESTIGATION.md` was never root-caused, and F1 is an
  unconfirmed candidate.
- **No scanner was installed.** SP9 is a review; section 5 records what to install and in what order,
  and that work belongs to the kdisco repository.
- **Filing is still open.** Acceptance criteria 3 and 5 of #1156 need the six issues, the three
  classification comments and the #1147 summary to be posted. They are public posts, so they are not
  done by this change. Sections 4 and 7 hold the exact texts; replace them with links and issue
  numbers once the issues exist, before this PR leaves draft.
