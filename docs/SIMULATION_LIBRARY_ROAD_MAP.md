# Simulation Library Road Map: jDisco → kDisco → Kalasim

**Document:** Migration roadmap for the A2→A6→A5 phased migration path
**Created:** 2026-02-08
**Branch:** `feature/simulation-library-decision-round2`
**Decision Reference:** [Round 1](SIMULATION_LIBRARY_DECISION.md) (7-0 Kalasim), [Round 2](SIMULATION_LIBRARY_DECISION_ROUND2.md) (5-2 A2→A6→A5)
**Audit Reference:** [Decision Audit](DECISION_AUDIT_AND_EXPERTISE.md) (corrected estimates, risk analysis)
**kDisco Repository:** https://github.com/bedaHovorka/kdisco/

---

## Table of Contents

1. [Current State Assessment](#1-current-state-assessment)
2. [kDisco Implementation Tasks (Pre-Migration)](#2-kdisco-implementation-tasks-pre-migration)
3. [interlockSim Gradle + Import Switch](#3-interlocksim-gradle--import-switch)
4. [Step-by-Step Migration Roadmap](#4-step-by-step-migration-roadmap)
5. [Corrected Estimates from Audit](#5-corrected-estimates-from-audit)
6. [Risk Register](#6-risk-register)

---

## 1. Current State Assessment

### 1.1 kDisco — What Exists

The kDisco repository (https://github.com/bedaHovorka/kdisco/) contains 6 core classes with Kotlin Multiplatform `expect`/`actual` declarations. The JVM actuals delegate to jDisco 1.2.0.

**Implemented classes:**

| Class | Description | Module |
|---|---|---|
| `Link` | Queue message base class | `kdisco-core-api` |
| `Head` | Queue head (FIFO container) | `kdisco-core-api` |
| `Process` | Discrete-event process (`actions()`, `hold()`, `passivate()`, `activate()`, `time()`) | `kdisco-core-api` |
| `Continuous` | ODE integration base class (`derivatives()`) | `kdisco-core-api` |
| `Variable` | Continuous state variable (`.state`, `.rate`, `.start()`, `.stop()`, `.isActive()`) | `kdisco-core-api` |
| `Simulation` | Simulation runner | `kdisco-core-api` |

**Kotlin DSL extensions:** `runSimulation`, `activate`, `asSequence`
**Koin integration module:** `kdisco-koin` (KoinProcess, KoinContinuous, `koinSimulation` DSL)
**Package:** `cz.hovorka.kdisco`

### 1.2 interlockSim — Actual jDisco API Usage

Verified by `grep` against the codebase. **10 production files** and **2 test files** have direct `import jDisco.*` statements.

#### Production Files (10)

| File | jDisco Imports | Key APIs Used |
|---|---|---|
| `Train.kt` | Condition, Continuous, Process, Reporter, Variable | `waitUntil` (10 calls), Variable state/rate, Continuous ODE, Reporter subclass, `terminated` |
| `InOutWorker.kt` | Condition, Head, Link, Process | `waitUntil` (2 calls), `wait(Head)` (1 call), `activate()`, `time()` |
| `LoopProcess.kt` | Process | `passivate()`, `activate()`, `terminated()`, `actions()` |
| `Generator.kt` | Random | `random.normal()`, `random.shuffle()`, `terminated()` |
| `SimpleIntegration.kt` | Continuous, Variable | `Continuous.derivatives()`, `Variable.rate` |
| `ContinuousInvariantChecker.kt` | Continuous | `Continuous.derivatives()`, condition checking |
| `DefaultSimulationContext.kt` | DiscoException, Process, Random | `Process.activate()`, `Process.time()`, `DiscoException` catch |
| `SimulationException.kt` | Process | `Process.time()` for timestamp |
| `DynamicTrack.kt` | Process | `Process.time()` only |
| `DynamicTrackBlock.kt` | Process | `Process.time()` only |

#### Test Files (2)

| File | jDisco Imports |
|---|---|
| `SimpleIntegrationTest.kt` | Variable |
| `InOutWorkerPathHandlingTest.kt` | Head |

### 1.3 API Gap Analysis

| jDisco API | Used In | Call Count | kDisco Status |
|---|---|---|---|
| `Process` (extend, `actions()`) | Train, LoopProcess, InOutWorker, Generator | — | **EXISTS** |
| `Process.hold(double)` | Train, Generator | ~5 | **EXISTS** |
| `Process.passivate()` | LoopProcess | 1 | **EXISTS** |
| `Process.activate(Process)` | Train, InOutWorker, LoopProcess | ~8 | **EXISTS** |
| `Process.time()` | 7 files | ~15 | **EXISTS** |
| `Process.waitUntil(Condition)` | Train (10), InOutWorker (2) | **12** | **MISSING** |
| `Process.wait(Head)` | InOutWorker | 1 | **MISSING** |
| `Process.terminated()` | Generator, LoopProcess, Train, ShuntingLoop | ~6 | **MISSING** |
| `Condition` interface (`.test()`) | Train (5+ impls), InOutWorker (2 impls) | — | **MISSING** |
| `Continuous` (extend, `derivatives()`) | SimpleIntegration, ContinuousInvariantChecker, Train.Motor | — | **EXISTS** |
| `Variable` (`.state`, `.rate`, `.start()`, `.stop()`, `.isActive()`) | Train (25+ refs), SimpleIntegration | — | **EXISTS** |
| `Reporter` (extend, `.actions()`, `.start()`, `.stop()`, `.setFrequency()`) | Train (anonymous subclass) | — | **MISSING** |
| `Random` (`.normal()`, `.exp()`, `.shuffle()`) | Generator | 3 | **MISSING** |
| `Head` (`.empty()`, `.first()`) | InOutWorker | ~3 | **EXISTS** |
| `Link` (`.into(Head)`) | InOutWorker, Train | ~2 | **EXISTS** |
| `DiscoException` | DefaultSimulationContext, SimulationException | 2 | **MISSING** |

**Summary: 7 API gaps must be closed before interlockSim can switch to kDisco.**

---

## 2. kDisco Implementation Tasks (Pre-Migration)

All work in this section happens in the **kDisco repository** (https://github.com/bedaHovorka/kdisco/).

### P0 — Compile-Blocking (interlockSim will not build without these)

#### 2.1 `Condition` Interface

**Priority:** P0 (depended on by `waitUntil`, used directly in 7+ anonymous implementations)

```kotlin
// commonMain
expect fun interface Condition {
    fun test(): Boolean
}

// jvmMain
actual fun interface Condition {
    actual fun test(): Boolean
}
```

JVM actual must bridge to `jDisco.Condition` for `waitUntil` delegation. interlockSim uses Kotlin SAM conversion: `Condition { someBoolean }` — kDisco must support the same pattern.

**Used by:** Train.kt (5+ anonymous implementations including `AccelerationStopCondition`, `allowingSignal`), InOutWorker.kt (2 implementations: `pathFree`, queue change detection).

#### 2.2 `Process.waitUntil(Condition)`

**Priority:** P0 (12 call sites — most-used missing API)

```kotlin
// In expect abstract class Process
expect fun waitUntil(condition: Condition)

// jvmMain actual
actual fun waitUntil(condition: Condition) {
    jDiscoDelegate.waitUntil(jDisco.Condition { condition.test() })
}
```

This is the **state-event suspension** primitive: the process blocks until `condition.test()` returns `true`. The jDisco engine evaluates the condition after each state change.

Explicitly flagged as missing in kDisco README: *"Many things from chat (like waitUntil) was not implemented!"*

**Used by:** Train.kt (10 calls — physics boundaries, semaphore signals, process termination waits), InOutWorker.kt (2 calls — path availability, queue exit).

#### 2.3 `Process.wait(Head)`

**Priority:** P0 (1 call site, but blocks InOutWorker compilation)

```kotlin
// In expect abstract class Process
expect fun wait(head: Head)

// jvmMain actual
actual fun wait(head: Head) {
    jDiscoDelegate.wait(head.jDiscoDelegate)
}
```

Suspends the process until a `Link` message arrives in the `Head` queue. Used for train queueing at InOut entry points.

**Used by:** InOutWorker.kt line 163: `Process.wait(queqe)`.

#### 2.4 `Process.terminated()`

**Priority:** P0 (6 call sites across 4 files)

```kotlin
// In expect abstract class Process
expect fun terminated(): Boolean

// jvmMain actual
actual fun terminated(): Boolean = jDiscoDelegate.terminated()
```

Returns `true` when the process has finished its `actions()` method. Used for loop control and synchronization.

**Used by:**
- Train.kt line 99: `val terminated: Condition = Condition { terminated() }` (wrapped as Condition for `waitUntil`)
- Generator.kt line 74: `while (!train.terminated()) { ... }` (wait for train completion)
- LoopProcess.kt line 52: `if (!terminated()) Process.activate(this)` (self-reactivation)
- ShuntingLoop.kt line 213: `if (element.terminated()) iter.remove()` (cleanup finished elements)

#### 2.5 `Reporter` Class

**Priority:** P0 (Train.kt has anonymous Reporter subclass — won't compile without it)

```kotlin
// commonMain
expect abstract class Reporter : Link {
    abstract fun actions()
    open fun start(): Reporter
    open fun stop(): Reporter
    fun setFrequency(frequency: Double)
}

// jvmMain actual
actual abstract class Reporter : Link() {
    private val jDiscoDelegate = object : jDisco.Reporter() {
        override fun actions() = this@Reporter.actions()
    }
    actual abstract fun actions()
    actual open fun start(): Reporter { jDiscoDelegate.start(); return this }
    actual open fun stop(): Reporter { jDiscoDelegate.stop(); return this }
    actual fun setFrequency(frequency: Double) { jDiscoDelegate.setFrequency(frequency) }
}
```

Train.kt lines 57-78 define an anonymous `object : Reporter()` subclass that logs train state at regular intervals. The subclass overrides `actions()` and `start()`.

**Used by:** Train.kt (1 anonymous subclass with custom `actions()` and `start()` overrides).

#### 2.6 `Random` Class

**Priority:** P0 (Generator.kt won't compile without it)

```kotlin
// commonMain
expect class Random(seed: Long) {
    fun normal(mean: Double, stddev: Double): Double
    fun exp(mean: Double): Double
    fun uniform(a: Double, b: Double): Double
    fun shuffle(list: MutableList<*>)
}

// jvmMain actual
actual class Random actual constructor(seed: Long) {
    private val jDiscoDelegate = jDisco.Random(seed)
    actual fun normal(mean: Double, stddev: Double): Double = jDiscoDelegate.normal(mean, stddev)
    actual fun exp(mean: Double): Double = jDiscoDelegate.exp(mean)
    actual fun uniform(a: Double, b: Double): Double = jDiscoDelegate.uniform(a, b)
    actual fun shuffle(list: MutableList<*>) { /* delegate via java.util.Random inheritance */ }
}
```

**Used by:** Generator.kt (3 calls: `normal()` for inter-arrival times, `shuffle()` for randomized train ordering).

#### 2.7 `DiscoException`

**Priority:** P0 (DefaultSimulationContext.kt and SimulationException.kt reference it)

```kotlin
// commonMain
expect open class DiscoException : RuntimeException {
    constructor()
    constructor(message: String?)
}

// jvmMain actual
actual open class DiscoException : RuntimeException {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
}
```

**Used by:** DefaultSimulationContext.kt (caught in exception handler), SimulationException.kt (extends or references it).

### P1 — Quality (not compile-blocking, needed for robustness)

#### 2.8 `Process.cancel(Process)` — Force-terminate another process

Currently kDisco has `terminate()` which wraps `Process.cancel(jDiscoProcessDelegate)`, but the public static `cancel(Process)` method is not directly exposed. May be needed for simulation error handling and cleanup in DefaultSimulationContext.

#### 2.9 `Continuous.start()` / `Continuous.stop()` Return Types

Verify return types match jDisco (returns `Continuous` for method chaining). Train.Motor overrides `start()` with conditional logic — must remain subclassable with proper return type covariance.

---

## 3. interlockSim Gradle + Import Switch

Once kDisco implements all P0 APIs (Section 2.1–2.7), the interlockSim migration is **mechanical**.

### 3.1 Gradle Dependency Change

In `build.gradle.kts`:

```kotlin
// REMOVE:
implementation("dk.ruc.keld:jdisco:$jdiscoVersion") // jDisco 1.2.0

// ADD:
implementation("cz.hovorka.kdisco:kdisco-core-api:0.1.0") // from GitHub Packages
```

Repository configuration change:

```kotlin
// REMOVE:
maven {
    url = uri("https://maven.pkg.github.com/bedaHovorka/jdisco")
    // ...
}

// ADD:
maven {
    url = uri("https://maven.pkg.github.com/bedaHovorka/kdisco")
    // ...
}
```

### 3.2 Import Replacement

Across 12 files (10 production + 2 test), replace `jDisco` flat package with `cz.hovorka.kdisco`:

| Old Import | New Import | Files |
|---|---|---|
| `import jDisco.Process` | `import cz.hovorka.kdisco.Process` | 8 files |
| `import jDisco.Variable` | `import cz.hovorka.kdisco.Variable` | 2 files |
| `import jDisco.Continuous` | `import cz.hovorka.kdisco.Continuous` | 3 files |
| `import jDisco.Condition` | `import cz.hovorka.kdisco.Condition` | 2 files |
| `import jDisco.Reporter` | `import cz.hovorka.kdisco.Reporter` | 1 file |
| `import jDisco.Head` | `import cz.hovorka.kdisco.Head` | 2 files |
| `import jDisco.Link` | `import cz.hovorka.kdisco.Link` | 1 file |
| `import jDisco.Random` | `import cz.hovorka.kdisco.Random` | 2 files |
| `import jDisco.DiscoException` | `import cz.hovorka.kdisco.DiscoException` | 1 file |

### 3.3 Expected Code Changes: ZERO (Beyond Imports)

If kDisco API is truly 1:1, no logic changes should be needed. However, the following **friction points** may require minor adjustments:

1. **`Process.activate(process)` static call vs extension** — kDisco offers both `Process.activate(otherProcess)` (static) and `otherProcess.activate()` (extension). interlockSim uses the static form in LoopProcess.kt line 52: `Process.activate(this)`. Verify kDisco's companion object exposes this.

2. **`Head.first()` return type** — Returns `Link?` (nullable). interlockSim casts to specific types (e.g., `Train`). Kotlin smart casts should handle this, but verify.

3. **Condition SAM conversion** — interlockSim uses `Condition { booleanExpression }` (Kotlin SAM). kDisco's `Condition` must be a `fun interface` to support this pattern.

4. **Reporter subclassability** — Train.kt has `object : Reporter() { override fun actions() { ... } }`. kDisco's `Reporter` must be an `abstract class` (not `interface` or `sealed class`).

5. **`Random.shuffle()`** — jDisco's `Random` extends `java.util.Random`, providing `shuffle()` via `java.util.Collections.shuffle(list, this)`. kDisco must expose equivalent functionality.

---

## 4. Step-by-Step Migration Roadmap

### Step 0: kDisco API Completion

**Repo:** https://github.com/bedaHovorka/kdisco/
**Estimated duration:** 2–3 weeks
**Parallel with:** Nothing — this is the prerequisite

**Tasks:**
1. Implement all 7 P0 APIs (Section 2.1–2.7) with `expect`/`actual` declarations
2. JVM actuals delegate to jDisco 1.2.0 (same as existing kDisco classes)
3. Write tests for each new API in kDisco's own test suite
4. Address P1 items (Section 2.8–2.9) if time permits
5. Publish `0.1.0-SNAPSHOT` to GitHub Packages

**Gate criteria:**
- [ ] `./gradlew build` passes in kDisco
- [ ] All 7 P0 APIs have JVM actuals with tests
- [ ] API surface matches interlockSim's usage patterns (SAM conversion, subclassing, static methods)
- [ ] Published to GitHub Packages (snapshot or release)

### Step 1: kDisco Bridge Validation (A2)

**Repo:** interlockSim (`feature/kdisco-bridge` branch)
**Estimated duration:** 1–2 weeks
**Depends on:** Step 0 complete

**Tasks:**
1. Swap Gradle dependency: `dk.ruc.keld:jdisco:1.2.0` → `cz.hovorka.kdisco:kdisco-core-api:0.1.0`
2. Replace imports in 12 files (Section 3.2)
3. Fix any friction points (Section 3.3)
4. Run full test suite
5. Run golden output comparison against PR #367 baseline

**Gate criteria:**
- [ ] All 1840+ tests pass (zero new failures, zero new skips)
- [ ] Golden output comparison: position ±1e-6m, time ±1e-9s
- [ ] No changes to any file in `sim/` beyond import statements
- [ ] `./gradlew build detekt ktlintCheck test` passes
- [ ] CI green on GitHub Actions

**Binding condition (from Round 2 decision):** Dual-mode validation — kDisco-over-jDisco must produce **identical** output to direct jDisco.

### Step 2: Kalasim Backend Swap (A6)

**Repo:** kDisco
**Estimated duration:** 4–6 months
**Depends on:** Step 1 complete, Step 2 PoC (50-train scalability)

**Tasks:**
1. Replace jDisco delegation inside kDisco with Kalasim `Component`/`State`/coroutines
2. Implement analytical kinematics to replace ODE solver:
   - `v(t) = v₀ + at` and `s(t) = v₀t + ½at²` (exact for piecewise-constant acceleration)
   - Validated: `Train.kt:717` formula `a = (v_target² - v²) / (2s)` is constant between discrete state changes
3. Map kDisco API to Kalasim internals:
   - `kDisco.Process` → `org.kalasim.Component` (coroutine-based)
   - `kDisco.hold(t)` → `Component.hold(t)`
   - `kDisco.waitUntil(condition)` → `Component.wait(StateSignal)` or polling
   - `kDisco.Variable` → analytical state or `org.kalasim.State<Double>`
   - `kDisco.Head/Link` → `org.kalasim.ComponentQueue`
4. Run interlockSim's full test suite against new kDisco backend
5. Run 50-train scalability test (Issue #316 validation)

**interlockSim changes: NONE** — still uses kDisco API; the backend swap is invisible.

**Gate criteria:**
- [ ] All 1840+ interlockSim tests pass
- [ ] 50-train scalability test passes (Issue #316 fix validation)
- [ ] Golden output: position ±0.1m, velocity ±0.01m/s (relaxed tolerances for backend change)
- [ ] No jDisco dependency remains in kDisco's runtime classpath
- [ ] Performance: simulation wall-clock within 2x of jDisco baseline

**PoC gate (month 3):** If 50-train test fails and cannot be fixed within 4 weeks, trigger DSOL fallback evaluation (see Risk #2).

### Step 3: Bridge Removal (A5)

**Repo:** interlockSim (`feature/kalasim-direct` branch)
**Estimated duration:** 2–3 months
**Depends on:** Step 2 complete

**Tasks:**
1. Replace kDisco imports with direct Kalasim API:
   - `cz.hovorka.kdisco.Process` → `org.kalasim.Component`
   - `cz.hovorka.kdisco.Variable` → analytical kinematics or `org.kalasim.State<Double>`
   - `cz.hovorka.kdisco.Head/Link` → `org.kalasim.ComponentQueue`
   - `cz.hovorka.kdisco.Condition` → Kalasim native signals/predicates
2. Inline any remaining kDisco wrapper logic into interlockSim
3. Remove kDisco Gradle dependency entirely
4. Clean up SimulationEnvironment facade if needed
5. Full regression testing

**Gate criteria:**
- [ ] All tests pass, golden output validated
- [ ] kDisco module no longer referenced in `build.gradle.kts`
- [ ] No `cz.hovorka.kdisco` imports remain in codebase
- [ ] `./gradlew build detekt ktlintCheck test` passes
- [ ] CI green on GitHub Actions

**Binding condition (from Round 2 decision):** Bridge removal is **mandatory** by end of Step 3. kDisco is temporary scaffolding, not a permanent dependency.

### Timeline Summary

```
Month:  1    2    3    4    5    6    7    8    9   10   11   12   13   14   15   16   17   18
        ├────┤
Step 0  ▓▓▓▓▓  kDisco API completion (2-3 weeks)
             ├──┤
Step 1       ▓▓▓  Bridge validation (1-2 weeks)
                  ├──────────────────────────────┤
Step 2            ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  Kalasim backend (4-6 months)
                  ▲ PoC gate (month 3)
                                                   ├─────────────────┤
Step 3                                             ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  Bridge removal (2-3 months)
                                                                                              ▲
                                                                        Timeline ceiling: 18 months
```

---

## 5. Corrected Estimates from Audit

Per `docs/DECISION_AUDIT_AND_EXPERTISE.md`, several numbers from the decision documents were inaccurate:

| Metric | Documented | Actual | Impact |
|---|---|---|---|
| Files with jDisco imports | 13 | **10** (production), 12 with test files | Narrower scope — import swap is simpler |
| SimulationEnvironment methods | 11 | **18** (grew via Issue #292 navigation services) | Does NOT affect kDisco bridge — these are interlockSim-internal |
| Bridge code estimate | ~800 lines | ~800 lines (7 missing APIs + JVM actuals) | Estimate appears accurate for P0 scope |
| Continuous simulation required? | Assumed yes | **No** — piecewise-constant acceleration | Kalasim path is technically validated |

**Key clarification:** The 18 SimulationEnvironment methods do NOT need kDisco wrappers. They are interlockSim's own facade (Issue #94) that sits *above* the simulation library. The kDisco bridge wraps the 9 jDisco classes used directly in `import` statements.

**Recommendation from audit:** Freeze SimulationEnvironment interface before Step 1 — no new methods until migration is complete.

---

## 6. Risk Register

### Risk 1: jDisco Modality Issue (kDisco Issue #16)

**Severity:** Medium
**Likelihood:** High (known issue)

Non-final jDisco classes cause Kotlin Multiplatform `expect`/`actual` warnings for `expect class` declarations. Future Kotlin versions will promote these to errors.

**Mitigation:** Use `expect abstract class` or `expect open class` patterns. May require jDisco source modifications (add `open` modifiers) or kDisco wrapper indirection.

### Risk 2: Kalasim PoC Failure

**Severity:** Critical
**Likelihood:** ~20% (estimated)

If the 50-train scalability test fails at the Step 2 PoC gate (month 3), the DSOL fallback path adds 12–18 months.

**Expected timeline impact:**
- PoC succeeds: ~15 months total
- PoC fails → DSOL fallback: ~22 months total

**Mitigation:** Early PoC (month 3 gate). kDisco bridge architecture means interlockSim is insulated — only kDisco internals change for DSOL backend.

### Risk 3: kDisco Test Hangs

**Severity:** Medium
**Likelihood:** Medium (documented in kDisco TEST_HANG_INVESTIGATION.md)

jDisco's thread model can cause test hangs in kDisco's test suite. This is a known issue with jDisco's simulation loop blocking the test thread.

**Mitigation:** Timeouts on all kDisco tests. Test hang investigation documented in kDisco repo. Issue resolves itself when jDisco backend is replaced by Kalasim in Step 2.

### Risk 4: Package Naming Gap

**Severity:** Low
**Likelihood:** Certain (by design)

kDisco uses `cz.hovorka.kdisco` package while jDisco uses flat `jDisco` package. The import swap (Section 3.2) is clean but touches 12 files.

**Mitigation:** Automated search-and-replace. Single-commit, easily reviewable change.

### Risk 5: Kalasim Version Pinning

**Severity:** Medium
**Likelihood:** Medium

No Kalasim version target is specified. ~70 releases exist with no guaranteed API stability across major versions.

**Mitigation:** Pin to a specific Kalasim release in kDisco's `build.gradle.kts`. Evaluate latest stable version at Step 2 start. Consider forking if maintainer (single primary contributor, ~75 GitHub stars) becomes inactive.

### Risk 6: SimulationEnvironment Growth During Migration

**Severity:** Medium
**Likelihood:** Medium (interface grew from 11 → 18 methods during Issue #292)

If new methods are added to SimulationEnvironment during the migration window, each may increase migration complexity.

**Mitigation:** **Freeze SimulationEnvironment** before Step 1. No new methods until Step 3 is complete. Document freeze in the interface's KDoc.

---

## Appendix A: File-by-File Import Mapping

Complete list of files requiring import changes in Step 1:

```
src/main/kotlin/.../sim/Train.kt
  - jDisco.Condition    → cz.hovorka.kdisco.Condition
  - jDisco.Continuous   → cz.hovorka.kdisco.Continuous
  - jDisco.Process      → cz.hovorka.kdisco.Process
  - jDisco.Reporter     → cz.hovorka.kdisco.Reporter
  - jDisco.Variable     → cz.hovorka.kdisco.Variable

src/main/kotlin/.../sim/InOutWorker.kt
  - jDisco.Condition    → cz.hovorka.kdisco.Condition
  - jDisco.Head         → cz.hovorka.kdisco.Head
  - jDisco.Link         → cz.hovorka.kdisco.Link
  - jDisco.Process      → cz.hovorka.kdisco.Process

src/main/kotlin/.../sim/LoopProcess.kt
  - jDisco.Process      → cz.hovorka.kdisco.Process

src/main/kotlin/.../sim/Generator.kt
  - jDisco.Random       → cz.hovorka.kdisco.Random

src/main/kotlin/.../sim/SimpleIntegration.kt
  - jDisco.Continuous   → cz.hovorka.kdisco.Continuous
  - jDisco.Variable     → cz.hovorka.kdisco.Variable

src/main/kotlin/.../sim/ContinuousInvariantChecker.kt
  - jDisco.Continuous   → cz.hovorka.kdisco.Continuous

src/main/kotlin/.../context/DefaultSimulationContext.kt
  - jDisco.DiscoException → cz.hovorka.kdisco.DiscoException
  - jDisco.Process        → cz.hovorka.kdisco.Process
  - jDisco.Random         → cz.hovorka.kdisco.Random

src/main/kotlin/.../exceptions/SimulationException.kt
  - jDisco.Process      → cz.hovorka.kdisco.Process

src/main/kotlin/.../objects/tracks/DynamicTrack.kt
  - jDisco.Process      → cz.hovorka.kdisco.Process

src/main/kotlin/.../objects/tracks/DynamicTrackBlock.kt
  - jDisco.Process      → cz.hovorka.kdisco.Process

src/test/kotlin/.../sim/SimpleIntegrationTest.kt
  - jDisco.Variable     → cz.hovorka.kdisco.Variable

src/test/kotlin/.../sim/InOutWorkerPathHandlingTest.kt
  - jDisco.Head         → cz.hovorka.kdisco.Head
```

## Appendix B: Decision Traceability

| Decision | Document | Vote | Status |
|---|---|---|---|
| Simulation library: Kalasim | `SIMULATION_LIBRARY_DECISION.md` | 7-0 (unanimous) | Round 1 complete |
| Migration road: A2→A6→A5 | `SIMULATION_LIBRARY_DECISION_ROUND2.md` | 5-2 | Round 2 complete |
| Continuous sim not required | `DECISION_AUDIT_AND_EXPERTISE.md` | Independent verification | Audit complete |
| This roadmap | `SIMULATION_LIBRARY_ROAD_MAP.md` | — | Implementation plan |
