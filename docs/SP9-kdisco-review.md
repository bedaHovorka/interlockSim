# SP9 — kDisco deadlock and race review

**Issue:** bedaHovorka/interlockSim#1156 (Goal 1B, SP9, acceptance **B7**)
**Date:** 2026-10-10 · **Time-box:** 2 days (research note) · **Review:** kotlin-tech-lead
**Engine reviewed:** kDisco `develop` tip, `version=0.6.3-SNAPSHOT` (repo `bedaHovorka/kdisco`)
**Engine consumed by interlockSim:** `kdiscoVersion=0.6.2` ([gradle.properties](../gradle.properties)) — **unchanged by this work**

> **Intended path.** The issue asks for this file at `docs/goal9b-demands/SP9-kdisco-review.md`.
> The cloud agent that produced it cannot create directories: the repository `PreToolUse` hook in
> `.claude/settings.json` denies **every** Bash command (its `"if": "Bash(git push:*)"` key is not a
> supported hook field, so the pre-push gate runs — and fails — for all commands). Please
> `git mv docs/SP9-kdisco-review.md docs/goal9b-demands/SP9-kdisco-review.md` when merging, and
> update the link in [docs/README.md](README.md).

> **Review only.** SP9 files issues; it changes no kDisco code and no `kdiscoVersion`. Section 7
> contains ready-to-file issue bodies, and section 4 ready-to-post classification comments, because
> the agent has no tool that can create GitHub issues or issue comments.

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
| `TEST_HANG_INVESTIGATION.md` | 2026-02, jDisco-wrapper era; the wrapper it describes no longer exists. Keep as history; do not re-file. |
| `JDISCO_OBSERVED_ISSUES.md:54` | Same era; the observation (silently absorbed process exceptions) survives as kdisco#83, which is already filed. |
| `KDOC_EXPERT_REVIEW_REPORT.md:180` | Documentation-quality note about the threading contract; folded into **F5**/**F7** below rather than re-filed separately. |

Nothing in this review was reproduced under a race detector — see section 5 for why none is installed
today, and what to install.

## 2. Engine concurrency model (what kDisco actually is)

1. **Single-threaded by construction.** `Simulation.run` launches every process body into
   `CoroutineScope(Dispatchers.Unconfined + SupervisorJob())`. `Unconfined` means a resumed
   continuation runs on whichever thread resumed it; in practice all resumptions come from the event
   loop, so the whole simulation executes on the thread that called `run`.
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
   still-scheduled processes `TERMINATED` and clears the queue.
6. **Process exceptions are absorbed.** Only `ProcessTerminatedException` is caught deliberately;
   any other throwable from a process body is swallowed and `run()` still returns `true`
   (this is kdisco#83).

## 3. Findings

Each finding states its **home** (which repository should carry the issue) and the classification
line required by the issue.

### F1 — Unbounded `RESTART` retry loop in `Simulation.run` can livelock at a fixed simulated time

`Simulation.run` drives `while (context.monitor.integrateUntil(...))`. When user code mutates the
event queue during a step, the step returns `StepOutcome.RESTART`, the clock is unwound to the step's
start and the step is retried. The retry count is unbounded, nothing is logged, and because the
simulated clock does not advance, every outer guard that watches simulated time (including
interlockSim's own run-until-time limits) is blind to it. The symptom is a process that burns
wall-clock forever at a constant `simTime` — exactly the shape of the 20-train stall tracked in
bedaHovorka/interlockSim#591.

- **Home:** kdisco · **Severity:** high
- **prerequisite-of-1B: yes** — without a bound, a counter or a log line, a Goal 1B scale run that
  livelocks is indistinguishable from one that is merely slow.

### F2 — Continuous solver limits are silent

`ContinuousMonitor.probeStateAt` gives up after `MAX_PROBE_SUB_STEPS = 1000` and
`locateCrossingTime` after `MAX_BISECTION_ITERATIONS = 100`. Both return their best effort without a
log, a flag or an exception, so a train whose crossing time was never located simply behaves oddly.

- **Home:** kdisco · **Severity:** medium
- **prerequisite-of-1B: waivable** — a log line at `warn` would be enough; the scale scenarios can
  run without it.

### F3 — No preemptive test timeout anywhere in kDisco

kDisco sets no `Test.timeout`, uses no JUnit `@Timeout`, and relies on a handful of
`runTest(timeout = …)` calls plus CI `timeout-minutes: 20`. As section 5 documents,
`runTest`'s timeout is **cooperative**: it cannot fire if the test body never suspends, which is
precisely the F1 shape. A hung kDisco test therefore burns the whole CI job.

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
about it (the gap `KDOC_EXPERT_REVIEW_REPORT.md:180` noticed).

- **Home:** kdisco (documentation, then possibly API) · **Severity:** medium
- **prerequisite-of-1B: no** — but every embedder, including Goal 9B, needs the answer written down.

### F6 — `SimulationController` resume runs the engine on the resuming thread's dispatcher

`SimulationController.beforeEvent` suspends on `pauseChannel.receive()`. Which thread continues the
simulation after `resume()` is decided by the dispatcher of the coroutine that was suspended. Under
`runBlocking` — what `DefaultSimulationContext.run` uses — the engine correctly resumes on the
simulation thread. Under a plain `Dispatchers.Unconfined` or a pool dispatcher it would continue on
the *control* thread, where the JVM `ThreadLocal` context of fact 4 is unset.

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

`getTrainSnapshot` reads `train.getVelocity()` (which is the live `Variable.state`, continuously
integrated by the engine) and `train.totalDistance` (a discrete field plus a live component) in two
separate reads. Called from a non-simulation thread — which is what the GUI and the metrics path do —
the two halves can come from different engine steps, producing a snapshot that was never a real
state of the train. `approvedTrains` itself is safe: it is copy-on-write with a documented contract
(`MultiTrainLoop.kt:135-153`).

The engine side of this is only F5 (no documented contract); the tearing itself is created by
interlockSim's composition of two independent reads, and interlockSim is also where it can be fixed
(snapshot under the engine thread, or publish an immutable sample).

- **Home:** **interlockSim** (per the issue's "in kdisco when the cause is in the engine, otherwise
  in interlockSim") · **Severity:** medium
- **prerequisite-of-1B: no** — the Goal 1B scale runs are headless; the torn pair reaches only
  display and metrics, never a control decision.

## 4. Classification of the linked issues

Ready-to-post comment text, one per issue.

### kdisco#83 — unhandled process exceptions are absorbed

> **prerequisite-of-1B: yes**
>
> SP9 (bedaHovorka/interlockSim#1156) classification. A Goal 1B scale run launches many processes; if
> one dies of an unexpected throwable the engine swallows it and `run()` still returns `true`, so the
> scenario reports success while a train has silently stopped existing. That makes every scale result
> unfalsifiable, which is why this is a prerequisite rather than a nice-to-have. PR kdisco#84
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
| **Lincheck 3.7** (JetBrains) | MPL-2.0, released 2026-07-29, JVM-only | The best fit by a wide margin. Model-checks arbitrary concurrent code (`runConcurrentTest`), has first-class `suspend` support, and is built for exactly the question kDisco has: is a field written by the engine and read elsewhere safely published? |
| **JUnit 5 `@Timeout(threadMode = SEPARATE_THREAD)`** | EPL-2.0, part of JUnit already | The only *preemptive* hang backstop. The default `SAME_THREAD` mode cannot interrupt a spinning body; `SEPARATE_THREAD` can, and it can be made the default via `junit.jupiter.execution.timeout.thread.mode.default`. This is the F3 fix. |
| **`kotlinx-coroutines-debug` / `DebugProbes`** | Apache-2.0, maintained, JVM-only | "jstack for coroutines": a coroutine dump naming the suspended process bodies, at single-digit-percent overhead. Makes a timeout actionable instead of merely red. |
| **detekt `detekt-rules-coroutines`** | Apache-2.0, maintained, source-level, all KMP targets | Hygiene only (`SleepInsteadOfDelay`, `SuspendFunSwallowedCancellation`, …) — it finds no races, but it is the only surveyed tool that covers the js and linuxX64 sources at all. |
| **Fray 0.9.0** (CMU PASTA) | Apache-2.0, released 2026-07-17, JVM-only | Systematic schedule exploration (PCT/POS) with deterministic replay, a JUnit 5 `@FrayTest` and a Gradle plugin. Attractive for replaying an intermittent hang. ⚠️ Its documented control is `Thread`-level; whether it models coroutine dispatch is **unverified** — settle that before adopting. |

### Conditional

- **jcstress 0.16** (GPLv2+CPE, low activity): the rigorous JMM stress harness. Correct for the
  publication questions (F4, F5) but disproportionate; Lincheck answers the same questions with
  Kotlin-native ergonomics.
- **BlockHound** (Apache-2.0, active 2026-10-07): detects blocking calls in non-blocking threads, and
  integrates with coroutines through an SPI. Needs `-XX:+AllowRedefinitionToAddDeleteMethods` on
  JDK 13+. Useful only if kDisco ever moves off `Unconfined`.
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

1. Add `@Timeout(threadMode = SEPARATE_THREAD)` as the JUnit default, plus a Gradle `Test.timeout` as
   the outer net — the F3 fix, hours of work.
2. Install `DebugProbes` in the test source set so a timeout prints a coroutine dump.
3. Bound and log the F1 retry loop; log the F2 solver give-ups.
4. Only then consider Lincheck, and scope it to the publication questions (F4, F5) rather than to the
   engine as a whole.

**KMP caveat.** Every dynamic tool above is JVM-only — Lincheck is explicitly "a JVM-only project",
and BlockHound, Fray, vmlens, DebugProbes and jcstress are JVM agents or harnesses. The js and
linuxX64 targets (including `:fast-sim`) get coverage from **detekt alone**. Any plan must say so.

## 6. Goal 9B demands

What Goal 9B may and may not rely on, given kDisco 0.6.2/0.6.3-SNAPSHOT as it stands today.

### Can be relied on

| Guarantee | Basis |
| --- | --- |
| **Deterministic scheduling order.** Equal-time events run FIFO by insertion (priority events LIFO); identical inputs give identical event order. | `EventQueue` tie-breaking by insertion counter. |
| **Single-threaded execution of process bodies.** Everything a process does happens on the thread that called `run`; no process body runs concurrently with another. | `Dispatchers.Unconfined` + the event loop (fact 1). |
| **Thread confinement of the active context on JVM.** `Process.activeContext` is correct for JVM code running inside the simulation thread. | `SimulationContextHolder` jvmMain actual is a `ThreadLocal`. |
| **Cooperative teardown.** After `run()` returns, the scope is cancelled, still-scheduled processes are `TERMINATED` and the queue is empty. | `run()` teardown block. |
| **Pause/resume under `runBlocking`.** With `DefaultSimulationContext.run`'s `runBlocking` driver, resume continues on the simulation thread. | F6 analysis. |

### Cannot be relied on

| Non-guarantee | Finding | Consequence for Goal 9B |
| --- | --- | --- |
| **Exception propagation.** A process that throws is absorbed; `run()` still returns `true`. | kdisco#83, F-ref §2.6 | Goal 9B must not treat "run completed" as "nothing failed" until kdisco#84 ships and #1112 is wired. |
| **Safe publication of engine state to other threads.** `Variable.state` and the discrete fields are plain; off-thread reads may be stale or torn. | F5, F9 | Any Goal 9B consumer outside the simulation thread must obtain its data from a snapshot taken *on* the engine thread, not by reading fields. |
| **Cross-thread `stop()`.** `stopRequested` is not volatile. | F4 | Goal 9B must stop runs the way interlockSim does today (thread interrupt / run-until-time), not via `stop()` from another thread. |
| **Liveness.** No bound on the `RESTART` retry loop, no preemptive timeout. | F1, F3 | Goal 9B needs its own wall-clock watchdog around every run; it cannot assume the engine will ever return. |
| **Thread confinement outside JVM.** Native and JS hold the active context in a global. | F7 | One simulation per process on `:fast-sim`. |
| **Solver convergence reporting.** Probe and bisection limits fail silently. | F2 | A located crossing time may be a best effort; Goal 9B must not assert on it to full precision. |
| **Pause/resume off `runBlocking`.** | F6 | If Goal 9B drives the controller from another dispatcher, it must re-verify which thread resumes the engine. |

## 7. Issues to file

Six issues, in the order they should be filed. Numbers must be posted on
bedaHovorka/interlockSim#1147 once they exist (acceptance criterion 5).

### 7.1 — interlockSim: `MultiTrainLoop.getTrainSnapshot` composes a torn velocity/distance pair

> **Repository:** bedaHovorka/interlockSim · **prerequisite-of-1B: no**
>
> `MultiTrainLoop.getTrainSnapshot` reads `train.getVelocity()` (the live `Variable.state`) and
> `train.totalDistance` as two independent reads. Called from any thread other than the simulation
> thread — the GUI and metrics paths do exactly that — the two values can originate in different
> engine steps, so the snapshot describes a state the train never had. `approvedTrains` is not
> affected; it is copy-on-write with a documented contract (`MultiTrainLoop.kt:135-153`).
>
> The engine offers no safe-publication guarantee for these fields and is not expected to (see
> SP9 F5 and the companion kdisco threading-contract issue), so the fix belongs here: take the
> snapshot on the engine thread and publish one immutable value, rather than reading two fields
> across the thread boundary.
>
> Headless Goal 1B runs are unaffected, which is why this is not a 1B prerequisite. Filed by SP9
> (#1156).

### 7.2 — kdisco: bound and log the `RESTART` retry loop in `Simulation.run`

> **Repository:** bedaHovorka/kdisco · **prerequisite-of-1B: yes**
>
> When user code mutates the event queue during a step, the step returns `StepOutcome.RESTART`, the
> clock unwinds to the step start and the step is retried. The retry count is unbounded and nothing
> is logged. Because simulated time does not advance, every simulated-time guard is blind, and the
> process spins on wall-clock at a constant `simTime`. This is the shape of the stall in
> bedaHovorka/interlockSim#591.
>
> Asked for: a bound (configurable, generous), a `warn` log naming the mutating source when the
> retry count crosses a threshold, and a clear exception when the bound is exceeded. Filed by SP9
> (bedaHovorka/interlockSim#1156).

### 7.3 — kdisco: continuous-solver limits fail silently

> **Repository:** bedaHovorka/kdisco · **prerequisite-of-1B: waivable**
>
> `ContinuousMonitor.probeStateAt` (`MAX_PROBE_SUB_STEPS = 1000`) and `locateCrossingTime`
> (`MAX_BISECTION_ITERATIONS = 100`) return their best effort with no log, flag or exception, so a
> non-converged crossing time is indistinguishable from a located one. A `warn` log with the
> variable and the interval would be enough. Filed by SP9 (bedaHovorka/interlockSim#1156).

### 7.4 — kdisco: document (and then enforce) the threading contract

> **Repository:** bedaHovorka/kdisco · **prerequisite-of-1B: no**
>
> Four related gaps, best fixed as one documented contract:
> 1. `SimulationContext.stopRequested` is a plain `Boolean` written by `stop()` — a cross-thread
>    `stop()` has no publication guarantee.
> 2. `Variable.state` and the discrete fields are plain; the KDoc never says whether an embedder may
>    read them from another thread (it may not).
> 3. `SimulationController.beforeEvent` resumes on whatever dispatcher the caller supplies; off
>    `runBlocking` the engine would continue on the control thread, where the JVM `ThreadLocal`
>    context is unset.
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
> calls and CI `timeout-minutes: 20`. `runTest`'s timeout is cooperative — it runs on the test's own
> event loop and cannot fire if the body never suspends, which is exactly how an unbounded engine
> retry loop (see the `RESTART` issue) hangs. A hung test therefore consumes the whole CI job.
>
> Asked for: `junit.jupiter.execution.timeout.thread.mode.default = SEPARATE_THREAD` plus a default
> `@Timeout`, a Gradle `Test.timeout` as the outer net, and `DebugProbes.install()` in tests so a
> timeout prints a coroutine dump. Filed by SP9 (bedaHovorka/interlockSim#1156).

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
  `JDISCO_OBSERVED_ISSUES.md` and `KDOC_EXPERT_REVIEW_REPORT.md` describe the jDisco-wrapper era; the
  only live residue is kdisco#83 (already filed) and the documentation gap now covered by §7.4.
- **No scanner was installed.** SP9 is a review; section 5 records what to install and in what order,
  and that work belongs to the kdisco repository.
- **The issues themselves and the #1147 summary comment still need a human.** The agent that wrote
  this document has no tool that can create GitHub issues or issue comments; sections 4 and 7 are the
  exact texts to post.
