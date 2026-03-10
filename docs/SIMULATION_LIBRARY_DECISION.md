# Simulation Library Decision: jDisco to Kalasim Migration

**Decision Date:** 2026-02-08
**Decision:** Migrate from jDisco to Kalasim (Approach A5: direct integration)
**Vote:** 7-0 unanimous (all team members, 1st choice)
**Fallback:** A3 (DSOL direct) if Phase 1 PoC fails
**Timeline:** 15 months (3-month PoC + 6-month migration + 3-month testing + 3-month release)
**Decision Authority:** traffic-simulation-expert (TSE), exercised as ratification of consensus

---

## 1. Executive Summary

The interlockSim project will migrate its simulation engine from jDisco (a discrete-continuous simulation library from 2004, unmaintained for 22 years) to Kalasim (a Kotlin-native discrete event simulation library using coroutines).

**Key drivers:**
- jDisco deadlocks at 8+ concurrent trains (Issue #316), blocking Goal 1 (Multi-Train Simulation) and transitively Goals 3, 9, 10, 13, 18
- jDisco's research-only license, single-point distribution, and Java 6 codebase create unacceptable dependency risk
- The project owner explicitly states on Issue #316: "after rewrite to simulation library, deeply check the path finding algorithms"
- TSE's pivotal determination: continuous simulation is NOT required; analytical kinematics provide exact closed-form solutions

**Approach evaluated:** 6 approaches across 5 structured meetings with 7 specialized team roles, producing 177 context votes across 27 voting contexts plus 7 final ranked votes.

**Selected approach (A5 - Kalasim direct):**
- Native Kotlin with coroutine-based process-interaction (near-identical API to jDisco)
- 4-6 month migration effort (lowest of all migration options)
- ~100 of 1840 tests need modification; ~1740 unaffected
- Native Koin DI integration (matches existing architecture)
- MIT license (eliminates dependency risk)

**Binding conditions:**
1. Phase 1 PoC must pass go/no-go gate (50-train scalability test) before full migration
2. SimulationEnvironment facade (Issue #94) is mandatory architectural boundary
3. Kalasim imports MUST NOT leak beyond `sim/` package
4. DSOL (A3) is the documented fallback if PoC fails

---

## 2. Meeting 1 Record: Historical Analysis & Current State

**Date:** 2026-02-08 | **Duration:** 2 hours | **Led By:** traffic-simulation-expert (TSE)

### Part 1: jDisco Architecture Inventory

**java-senior-dev (JSD)** presented a file-by-file breakdown of jDisco coupling. The analysis identified **10 source files with direct jDisco imports (12 including indirect coupling via inheritance)**, approximately **50 distinct API references** in production code, and **40 calls to process-interaction primitives** (`hold`/`passivate`/`activate`/`waitUntil`) concentrated in 5 files.

**File-level coupling surface:**

| File | jDisco Imports | Key API Surface |
|------|---------------|----------------|
| `Train.kt` (1024 lines) | `Condition`, `Continuous`, `Process`, `Reporter`, `Variable` | 24 process-interaction calls. Inner `Site` extends `Process`. Inner `LengthChecker` extends `ContinuousInvariantChecker`. Uses `Variable` for position/velocity/acceleration. |
| `InOutWorker.kt` | `Condition`, `Head`, `Link`, `Process` | Extends `Process` via `LoopProcess`. Uses `Head`/`Link` for queue management. `waitUntil(Condition)` for synchronization. |
| `ShuntingLoop.kt` | (via `Interlocking`) | 7 process-interaction calls. Orchestrates simulation scenario. |
| `Generator.kt` | `Random` | jDisco `Random` for distributions. `hold()` for timing. |
| `LoopProcess.kt` | `Process` | Abstract base extending `Process`. |
| `Interlocking.kt` | (via `LoopProcess`) | 4-level inheritance chain. |
| `SimpleIntegration.kt` | `Continuous`, `Variable` | Extends `Continuous` for physics ODE integration. |
| `ContinuousInvariantChecker.kt` | `Continuous` | Runtime physics validation. |
| `DynamicTrackBlock.kt` | `Process` | Occupant tracking. |
| `DynamicTrack.kt` | `Process` | Occupant references. |
| `DefaultSimulationContext.kt` | `DiscoException`, `Process`, `Random` | Lifecycle management. Catches `DiscoException`. Seeded `Random(0)`. |
| `SimulationException.kt` | `Process` | Exception context. |
| Test files (2) | `Head`, `Variable` | Test-only imports. |

**Inheritance hierarchy:** `ShuntingLoop` -> `Interlocking` -> `LoopProcess` -> `jDisco.Process` (4 levels deep, rooted in dead library).

**TSE classified each jDisco feature:**

| Feature | Classification | Rationale |
|---------|---------------|-----------|
| `Process` (discrete process) | **MANDATORY** | Every simulation entity extends it. No simulation without it. |
| `hold(double)` | **MANDATORY** | Time advance primitive. 24 calls across sim/. PR #356 adds `hold(30.0)` for bidirectional operation. |
| `passivate()` / `activate()` | **MANDATORY** | Process suspension/resumption. PR #312 showed criticality: removing `env.stop()` relied on `passivate()`. |
| `waitUntil(Condition)` | **MANDATORY** | State-determined events. Train physics thresholds and InOutWorker coordination. |
| `Continuous` | **MANDATORY** | ODE integration for physics. `SimpleIntegration` and `ContinuousInvariantChecker` extend it. |
| `Variable` | **MANDATORY** | Piecewise continuous state. Train position, velocity, acceleration. |
| `Condition` | **MANDATORY** | Used with `waitUntil`. Coupled to Variable state. |
| `Reporter` | **OPTIONAL** | Replaceable by kotlin-logging. |
| `Random` | **OPTIONAL** | Replaceable by `kotlin.random.Random`. Issue #122 showed jDisco's Random was already problematic. |
| `Head` / `Link` | **OPTIONAL** | Replaceable by Kotlin `ArrayDeque`. |
| `DiscoException` | **OPTIONAL** | Only caught in DefaultSimulationContext. |

**Critical finding:** 7 of 11 features are MANDATORY. The `Continuous`/`Variable` combination for combined discrete-continuous simulation was identified as the KEY differentiator -- the main technical question for Meeting 2.

### Part 2: Pain Points & Challenges Deep Dive

#### Issue #42 (CLOSED 2026-01-14): Virtual Threads Investigation

jDisco uses single-threaded discrete event simulation with cooperative scheduling. Only 2 threading references in entire codebase: `Thread.sleep()` and `Thread.currentThread().stackTrace`. Virtual threads are a paradigm mismatch. The issue's conclusion: "Virtual threads are a solution looking for a problem in interlockSim's simulation engine. The real issue is jDisco's abandonment." Owner comment: "this conversation should be kept for future, but now is here nothing to do" -- explicitly deferring migration. Referenced jDisco research at github.com/bedaHovorka/jdisco/issues/3.

**AA:** The single-threaded event queue limits future AI dispatcher integration (Goal 10). Both DSOL and Kalasim offer more flexible observation patterns.

#### Issue #83 (CLOSED 2026-01-14): Null Handling Friction

40+ redundant null checks from Java-to-Kotlin migration because jDisco lacks nullability annotations. Every jDisco API boundary requires `!!` or `?.` in Kotlin. **KTL:** "A Kotlin-native library like Kalasim eliminates this category entirely. DSOL migration would still have this problem since DSOL is also Java." **KJD:** Confirmed friction during PR #342 (Kotlin property accessors for Train API) -- multiple null-safe operators around jDisco values that can never be null in practice.

#### Issue #100 (CLOSED 2026-01-18): Simulation Fails -- Dynamic Wrapper Identity Crisis

Railway switches created duplicate track segments sharing same name but different objects. Dynamic wrapper identity mismatch broke comparisons in Train.kt, Generator termination, ShuntingLoop semaphore/path matching. **KTL:** "The jDisco simulation model relies on object identity (`===`) for process coordination, but the wrapper pattern needed for proper architecture requires object equality (`==`)." Fixed by PR #285 and PR #286, but the underlying fragility remains: simulation model and architectural model pull in opposite directions. **RCE:** "Railway switches are the most safety-critical elements in interlocking. Silent identity failures are a domain correctness risk."

#### Issue #122 (CLOSED 2026-01-18): Non-Deterministic Simulation

Unseeded `Random()` in Generator caused non-reproducible results. Fixed with `Random(0)` -- hardcoded, not configurable. **TSE:** "A simulation that cannot be reproduced is not scientific. DSOL has StreamInterface with configurable seeds per replication. Kalasim uses Kotlin's Random with seed support." **QA:** "PR #367 (golden output validation) only became possible AFTER this fix. The framework should prevent this, not require manual discovery."

#### Issue #275 (CLOSED 2026-01-24): Path Reservation Conflict

Both trains reserved the same track simultaneously. Block 1687940142 reserved by Thread-7 and entered by Thread-8 in sequence. Regression from PR #95 (dependency inversion). **RCE:** "A track block can have exactly one reservation at a time -- the most fundamental safety invariant. Modern frameworks with proper resource management (Kalasim's `Resource`, DSOL's resource classes) enforce this at the framework level."

#### Issue #280 (CLOSED 2026-01-25): Second Train Deadlock

Train stuck at velocity=0.0 with near-zero acceleration on block "vB-around kB". Root cause: simulation grid used wrong wrapper objects. Fixed by PR #286. **KTL:** "Every architectural change near the sim/jDisco boundary creates wrapper identity mismatch risk. PR #285, #286, #287 were all emergency fixes for the same underlying issue."

#### Issue #291 (CLOSED 2026-02-04): K2 Track Never Used

Shunting loop has two tracks (k1, k2) but k2 was never utilized -- first-fit path selection always found k1 first. Required complete Path Discovery Restructuring (Issue #292) to separate TopologyNavigator, PathReservationService, and TrainNavigationService. **RCE:** "Real dispatchers actively balance traffic across available tracks. First-fit is not realistic."

#### Issue #316 (OPEN -- CRITICAL): Trains #8+ Deadlock

Trains 1-7 complete normally. Train #8 onwards deadlock due to PathInfo containing ~300-element cycles. Cycle detection in `buildPathWithDirection()` prevents OOM but trains freeze at velocity=0.0. **Owner's comment:** "after rewrite to simulation library, deeply check the path finding algorithms, revisit and cleanup code" -- explicit mandate for library migration. **QA:** "This is the scalability ceiling: 7 trains work, 8 fail. Unacceptable for a railway simulation."

#### Issue #94 / PR #254 (CLOSED 2026-01-21): SimulationEnvironment Facade

Introduced `SimulationEnvironment` facade interface with 18 essential methods. **Explicitly designed as migration seam** -- PR description included adapter pattern example for DSOL. Unblocked 18 issues (32% of all open work). **KTL:** "The hardest architectural preparation work has already been done. Switching the provider behind this facade is a contained task."

#### Issue #153 (CLOSED 2026-01-20): Context Inheritance Incompatibility

5-phase refactoring separated DefaultSimulationContext from DefaultEditingContext. Completed 70% faster than estimated (8 days vs 18), zero regressions across 927+ tests. **TSE:** "Evidence that our team can execute major refactoring safely. If we can do 5 phases of context refactoring with zero regressions, we can do a simulation library swap."

#### PR #257 (2026-01-21): Re-enabling 52 Disabled Integration Tests

52 tests disabled because `Process.activate()` hangs outside jDisco event loop. Hybrid approach: conversion to configuration tests plus 5 new full-simulation tests. **QA:** "jDisco's threading model creates test infrastructure challenges. A modern framework with explicit lifecycle management would make this a compile-time error, not a runtime hang."

#### PR #267 (2026-01-22): Animation Infrastructure Bridge

Bridge between jDisco simulation thread and Swing EDT for real-time animation. Required `@Volatile` state updates, `SwingUtilities.invokeLater`, Swing Timer at 30 FPS. **KTL:** "The complexity came directly from jDisco running on its own thread with no built-in animation infrastructure. Both DSOL and Kalasim have built-in real-time support."

#### PR #312 (2026-02-02): Remove env.stop() from Train Wait

Single line deletion preventing premature simulation termination when a train couldn't find its next path. **TSE:** "jDisco makes it easy to confuse 'stop this process' with 'stop the entire simulation.' Modern frameworks enforce this separation explicitly."

#### PR #352 (2026-02-07): PathResult Sealed Class

Replaced nullable `Path?` with sealed class distinguishing permanent vs temporary unavailability. **KTL:** "Highlights that jDisco has no concept of resource availability states. Kalasim's `Resource` and `State` classes provide this natively."

#### PR #356 (2026-02-06): Bidirectional Train Operation

Added `train.reverseDirection()` with `hold(30.0)` delay. Required making `Timetable.in`/`Timetable.out` mutable -- a compromise forced by jDisco's process model. In Kalasim, this would be a `Component` state transition with built-in monitoring.

#### PR #367 (2026-02-07): Koin Golden Output Validation

Determinism test proving simulation produces identical results across runs. Validates position within +/-1e-6m and time within +/-1e-9s. **QA:** "This is our regression safety net. Any migration MUST maintain these outputs or demonstrate that differences are improvements."

### Part 3: Conservative Approach Discussion

**Track record of successful migrations:**
- Ant to Gradle (complete)
- Java 11 to 21 (complete, 2 days vs 8 estimated)
- Java to Kotlin (100% of 94 files, ~30 days)
- Observable to PropertyChangeSupport (complete)
- SLF4J to kotlin-logging (complete)
- Koin dependency injection (complete)
- Context inheritance to composition (Issue #153, 5 phases, 8 days, zero regressions)
- SimulationEnvironment facade (Issue #94, 18 issues unblocked)
- Path Discovery Restructuring (Issue #292, 5 phases)

**Red lines established:**
1. Zero test regressions (all 1836 passing tests must continue to pass)
2. Golden output preservation (PR #367 determinism baseline)
3. Incremental deliverability (phased approach per Issue #153 template)
4. SimulationEnvironment facade as the migration seam
5. sim/ package conservative restriction remains during transition

**JSD (critical concern):** "The original 2007 code chose jDisco specifically for combined discrete-continuous simulation. Any replacement that does not support continuous simulation requires fundamentally rewriting the physics model. This is not negotiable." -- This set the key investigation item for Meeting 2.

### Voting Round 1: "Which approach best addresses our current pain points?"

| Pain Point / Concern | TSE | KTL | JSD | KJD | AA | RCE | QA |
|----------------------|-----|-----|-----|-----|----|-----|-----|
| Null safety (Issue #83) | A6 | A5 | A2 | A5 | A6 | -- | A5 |
| Object identity (Issues #100,#280) | A6 | A6 | A2 | A5 | A6 | A6 | A6 |
| Non-determinism (Issue #122) | A6 | A5 | A1 | A5 | A6 | -- | A6 |
| Multi-train scalability (Issue #316) | A4 | A6 | A2 | A5 | A6 | A4 | A6 |
| Test infrastructure (PR #257) | A6 | A6 | A2 | A6 | A6 | -- | A6 |
| Animation bridge (PR #267) | A3 | A6 | A1 | A5 | A6 | -- | A5 |
| Path reservation safety (Issue #275) | A6 | A6 | A2 | A5 | A6 | A6 | A6 |
| Continuous simulation | A4 | A4 | A1 | -- | A4 | A4 | -- |
| Migration risk | A6 | A6 | A1 | A5 | A6 | A2 | A2 |
| Koin DI compatibility | A6 | A6 | -- | A6 | A6 | -- | -- |

**Meeting 1 Tallies:** A6=32, A5=11, A2=7, A4=6, A1=4, A3=1

**Key finding:** A6 (kDisco+Kalasim) led because the continuous simulation question was unresolved, making the abstraction layer (with potential DSOL backend swap) seem necessary. A5 already had 11 votes (18%), showing early Kalasim support. TSE and RCE voted A4 specifically for the continuous simulation concern. This set up the pivotal determination in Meeting 2.

---

## 3. Meeting 2 Record: Long-Term Goals Alignment

**Date:** 2026-02-08 | **Duration:** 135 minutes | **Led By:** railway-civil-engineer (RCE)

### Pivotal Determination: Continuous Simulation is NOT Required

TSE delivered the most important technical finding of the entire meeting series:

> "InterlockSim's physics model uses piecewise-constant acceleration. Between state changes (throttle, brake, grade change), acceleration is CONSTANT. The ODE solver integrates v=integral(a*dt) and s=integral(v*dt), but because acceleration is constant between discrete events, these have exact closed-form solutions: v(t) = v0 + a*t, s(t) = v0*t + 0.5*a*t^2.
>
> The Motor's derivatives() computes a = (v_target^2 - v_current^2) / (2s) -- the constant-acceleration formula. We can compute the exact trajectory analytically and schedule events at exact times when trains reach semaphores.
>
> For track gradients (Goal 11): grade resistance adds a constant acceleration term within each section: a_grade = -g*sin(theta). This modifies net acceleration: a_net = a_motor + a_grade. The kinematic equations still apply.
>
> **Determination: Continuous simulation is OPTIONAL. Analytical kinematics with discrete state changes are sufficient for ALL 20 long-term goals.**"

**Impact:**
- Eliminated the main technical advantage of DSOL over Kalasim
- Made Kalasim fully viable for ALL 20 goals including track gradients and curves
- Removed the only unique capability jDisco provided
- Analytical solutions are actually MORE accurate than ODE numerical integration (no truncation error)

### Goals Analysis

**Goal 1 (Multi-Train, CRITICAL):** jDisco fails at 8+ trains (Issue #316). DSOL proven in OpenTrafficSim. Kalasim untested at scale but coroutine architecture is sound.

**Goal 5 (Save/Restore State):** jDisco processes are Java threads -- fundamentally not serializable. DSOL has experiment framework with serialization. Kalasim coroutine state is captured as continuations (serializable in Kotlin).

**Goal 10 (AI Dispatcher):** Kalasim + Koin + coroutines = natural AI integration. DSOL requires Java-to-Koin bridge layer.

**Goal 11 (Track Gradients):** TSE's pivotal determination makes Kalasim viable. Discrete grade resistance at segment boundaries matches real Czech railway engineering practice.

### Voting Round 2: "Which approach best enables each goal?"

| Goal Context | TSE | KTL | JSD | KJD | AA | RCE | QA |
|-------------|-----|-----|-----|-----|----|-----|----|
| Goal 1: Multi-Train | A5 | A5 | A3 | A5 | A6 | A3 | A5 |
| Goal 5: Save/Restore | A5 | A5 | A3 | A5 | A3 | A5 | A3 |
| Goal 7: Speed Control | A5 | A5 | A1 | A5 | A5 | A5 | A5 |
| Goal 10: AI Dispatcher | A5 | A5 | A3 | A5 | A6 | A5 | A5 |
| Goal 11: Track Gradients | A5 | A5 | A1 | A5 | A5 | A5 | A5 |
| Goal 14: Custom Train Types | A5 | A5 | A1 | A5 | A5 | A5 | A5 |

**Meeting 2 Tallies:** A5=31, A3=6, A1=3, A6=2, A2=0, A4=0

**Key shift:** After TSE's pivotal determination, A5 surged from 11 votes (Meeting 1) to 31 votes. A6 collapsed from 32 to 2. The abstraction layer was no longer justified once continuous simulation was proven unnecessary.

---

## 4. Meeting 3 Record: Technical Architecture Deep Dive

**Date:** 2026-02-08 | **Led By:** kotlin-tech-lead (KTL)

### API Mapping Analysis

| jDisco API | Kalasim Equivalent | Mapping Quality |
|-----------|-------------------|-----------------|
| `Process` | `Component` | **Direct** |
| `Process.actions()` | `Component.process() = sequence {}` | **Direct** |
| `Process.hold(t)` | `hold(t)` | **Identical** |
| `Process.waitUntil(cond)` | `wait(state, predicate)` | Close match |
| `Process.passivate()` | `passivate()` | **Identical** |
| `Process.activate()` | `activate()` | **Identical** |
| `Process.time()` | `now` property | Simpler |
| `Variable` | `State<T>` / `ComponentState` | More powerful |
| `Condition` | `State<T>` monitoring | More powerful |
| `Head` / `Link` | `ComponentQueue<T>` | Richer |
| `Random` | `Environment.random` | Better (seeded) |

Five API pairs are identical in name and semantics. Three are close matches with richer functionality.

### Migration Effort Matrix

| Criterion | A1 | A2 | A3 | A5 | A6 |
|-----------|-----|-----|-----|-----|-----|
| Migration effort | 0 | 3-5 mo | 12-18 mo | **4-6 mo** | 5-8 mo |
| Files changed | 0 | 10-15 | 20+ | **10-12** | 14-18 |
| Test rewrite | None | Low | High | **Medium (~100)** | Medium-high |
| Regression risk | None | Low | High | **Medium** | Medium |
| Rollback complexity | N/A | Medium | Very high | **Medium** | Medium |
| New code to maintain | 0 | ~800 lines | ~3000 lines | **~500 lines** | ~1000 lines |

**A4 (kDisco+DSOL) formally eliminated** -- unanimous. No technical scenario justifies building a process-interaction bridge over an event-scheduling engine when a native process-interaction library exists.

### Voting Round 3: "Which approach is best from technical perspective?"

| Technical Context | TSE | KTL | JSD | KJD | AA | RCE | QA |
|-------------------|-----|-----|-----|-----|----|-----|----|
| API mapping quality | A5 | A5 | A2 | A5 | A6 | A5 | A5 |
| Migration effort | A5 | A5 | A1 | A5 | A6 | A5 | A5 |
| Kotlin idiom alignment | A5 | A5 | A1 | A5 | A5 | -- | A5 |
| Test migration complexity | A5 | A5 | A2 | A5 | A6 | -- | A6 |
| Koin DI integration | A5 | A5 | A1 | A5 | A5 | A5 | A5 |
| Debugging/transparency | A5 | A5 | A1 | A5 | A6 | -- | A5 |

**Meeting 3 Tallies:** A5=28, A6=5, A1=4, A2=2, A3=0

---

## 5. Meeting 4 Record: Risk Assessment & Trade-Offs

**Date:** 2026-02-08 | **Led By:** traffic-simulation-expert (TSE)

### Risk Identification for A5 (Kalasim direct)

| Member | Top Risk | Probability | Impact |
|--------|----------|-------------|--------|
| TSE | Scalability unknown beyond ~20 agents | MEDIUM | HIGH |
| KTL | Single-maintainer risk (Holger Brandl) | MEDIUM | HIGH |
| JSD | Unproven in railway domain | HIGH | MEDIUM |
| KJD | Subtle behavioral differences from jDisco | LOW | MEDIUM |
| AA | Abandonment repeats jDisco history | LOW | HIGH |
| RCE | Unvalidated for safety-critical simulation | MEDIUM | MEDIUM |
| QA | Golden output regeneration masks regressions | MEDIUM | HIGH |

### Mitigation Strategies for Top 3 Risks

**Risk 1: Scalability (Owner: TSE)**
- Phase 1 PoC with 20/50/100-train stress tests
- GO: 20+ trains with <5x performance degradation
- NO-GO: 10x degradation or deadlock behavior triggers DSOL fallback

**Risk 2: Single-maintainer (Owner: KTL)**
- MIT license guarantees fork rights (unlike jDisco's research-only license)
- Kalasim is ~5,000 lines of Kotlin -- maintainable by our team
- Monitor GitHub activity quarterly; abandonment threshold: 12 months no commits

**Risk 3: Golden output regression (Owner: QA)**
- Physics validation suite built BEFORE migration using analytical (hand-computed) baselines
- Cross-validation: identical scenarios on jDisco AND Kalasim
- Tolerances: position +/-0.1m, velocity +/-0.01m/s, time +/-0.1s

### Key Trade-Off Resolution

**Simplicity (A5) vs Portability (A6):** YAGNI favors A5. The SimulationEnvironment facade (Issue #94) already provides sufficient abstraction. Building kDisco bridge adds ~500 lines for an 80%+ probability of never being exercised.

### Voting Round 4: "Which approach has the best risk profile?"

| Risk Context | TSE | KTL | JSD | KJD | AA | RCE | QA |
|-------------|-----|-----|-----|-----|----|-----|----|
| Lowest migration risk | A5 | A5 | A1 | A5 | A6 | A5 | A5 |
| Best long-term sustainability | A5 | A5 | A3 | A5 | A5 | A3 | A5 |
| Best upstream health | A5 | A3 | A3 | A5 | A3 | A3 | A5 |
| Best rollback safety | A5 | A5 | A1 | A5 | A6 | A5 | A5 |
| Best for team skills | A5 | A5 | A1 | A5 | A5 | A5 | A5 |

**Meeting 4 Tallies:** A5=24, A3=6, A1=3, A6=2

**Notable:** DSOL wins "best upstream health" (4 votes) -- TU Delft institutional backing is genuinely stronger. But MIT license + manageable codebase mitigates the single-maintainer risk adequately.

---

## 6. Meeting 5 Record: Final Decision & Roadmap

**Date:** 2026-02-08 | **Led By:** traffic-simulation-expert (TSE)

### Part 1: Consolidated Vote Analysis

**Cumulative totals across all 4 meetings (177 votes cast):**

| Approach | M1 | M2 | M3 | M4 | TOTAL | Share |
|----------|-----|-----|-----|-----|-------|-------|
| A1 (Keep jDisco) | 4 | 3 | 4 | 3 | **14** | 7.9% |
| A2 (kDisco+jDisco) | 7 | 0 | 2 | 0 | **9** | 5.1% |
| A3 (DSOL direct) | 1 | 6 | 0 | 6 | **13** | 7.3% |
| A4 (kDisco+DSOL) | 6 | 0 | 0 | 0 | **6** | 3.4% |
| A5 (Kalasim direct) | 11 | 31 | 28 | 24 | **94** | 53.1% |
| A6 (kDisco+Kalasim) | 32 | 2 | 5 | 2 | **41** | 23.2% |

**Post-Meeting-1 trajectory (after pivotal determination):**

| Approach | M2+M3+M4 | Share |
|----------|----------|-------|
| **A5** | **83** | **71.6%** |
| A3 | 12 | 10.3% |
| A1 | 10 | 8.6% |
| A6 | 9 | 7.8% |
| A2 | 2 | 1.7% |

**Per-member voting evolution:**

| Member | M1 Primary | M2-4 Primary | Shift Reason |
|--------|-----------|-------------|--------------|
| TSE | A6 | A5 | Own pivotal determination eliminated need for abstraction |
| KTL | A6 | A5 | YAGNI argument won after continuous sim resolved |
| JSD | A2/A1 | A1/A3 | Consistent conservative; accepted A5 in final vote |
| KJD | A5 | A5 | Most consistent A5 advocate from start |
| AA | A6 | A5/A6 | Realized SimulationEnvironment facade provides sufficient abstraction |
| RCE | A6/A4 | A5 | Domain correctness achievable with simpler path |
| QA | A6 | A5 | Manageable test migration confirmed |

### Part 2: Elimination Round

| Approach | Criterion Failed | Result |
|----------|------------------|--------|
| A1 (Keep jDisco) | Cannot enable Goal 1 (Issue #316 deadlock) | **ELIMINATED** |
| A2 (kDisco+jDisco) | Cannot enable Goal 1 (same jDisco backend) | **ELIMINATED** |
| A3 (DSOL direct) | High effort (12-18mo), low Kotlin alignment | **DEPRIORITIZED** (fallback) |
| A4 (kDisco+DSOL) | Eliminated unanimously in Meeting 3 | **ELIMINATED** |
| A5 (Kalasim direct) | All criteria pass | **FINALIST** |
| A6 (kDisco+Kalasim) | All criteria pass | **FINALIST** |

### Part 3: Final Ranked Vote

| Member | 1st Choice | Justification | 2nd Choice | 3rd Choice |
|--------|-----------|---------------|-----------|-----------|
| **TSE** | **A5** | Best technical fit, lowest risk-adjusted effort, team consensus | A3 | A6 |
| **KTL** | **A5** | Cleanest API mapping, lowest maintenance, highest team velocity | A6 | A3 |
| **JSD** | **A5** | Accepting consensus; concerns mitigated by PoC gate. Conditional on 50-train scalability. | A3 | A6 |
| **KJD** | **A5** | Native Kotlin, lowest effort, idiomatic API, Koin integration | A6 | A3 |
| **AA** | **A5** | SimulationEnvironment facade provides sufficient abstraction; kDisco layer unnecessary | A6 | A3 |
| **RCE** | **A5** | Shortest migration window preserves domain integrity | A3 | A6 |
| **QA** | **A5** | Lowest test disruption (~100 tests), golden output validation baseline exists | A6 | A3 |

**Result: 7-0 unanimous for A5 (Kalasim direct)**

### Decision Announcement

**TSE:** "The vote is unanimous. Seven of seven members select A5 as their first choice. I announce the FINAL DECISION: interlockSim will migrate from jDisco to Kalasim using direct integration."

### Part 4: Implementation Roadmap

#### Phase 0: Pre-Migration Preparation (Weeks 1-2)

| Task | Owner | Duration |
|------|-------|----------|
| Add Kalasim dependency to Gradle | KTL | 1 day |
| Create `feature/kalasim-migration` branch | KTL | 1 day |
| Snapshot current golden outputs | QA | 2 days |
| Document all jDisco API touchpoints | JSD | 3 days |
| Create Kalasim API compatibility matrix | KJD | 3 days |
| Define PoC success criteria | TSE + JSD | 2 days |

**PoC Success Criteria:**
- 50 concurrent trains without deadlock for 1000 simulated time units
- Deterministic output: identical results across 10 consecutive runs
- Event ordering: matches jDisco for ShuntingLoop scenario
- Performance: wall-clock time within 2x of jDisco
- Memory: heap usage within 3x of jDisco
- Coroutine count: stable (no unbounded growth)

#### Phase 1: Proof of Concept (Months 1-3)

**Month 1:** Core engine proof (KalasimRuntime, single-train ShuntingLoop, SimulationEnvironment wiring)
**Month 2:** Multi-train stress test (5/10/20/50 trains, determinism validation, performance benchmarking)
**Month 3:** PoC evaluation and **GO/NO-GO decision meeting**

**Gate criteria:** GO = all 6 criteria met. CONDITIONAL = 5/6 met with mitigation. NO-GO = 4 or fewer -> trigger A3 (DSOL) fallback.

#### Phase 2: Core Migration (Months 4-9)

**Months 4-5:** Migrate all sim/ process classes to Kalasim suspend functions
**Months 6-7:** Update DefaultSimulationContext, ContextTransformer, Koin scope integration
**Months 8-9:** Remove jDisco imports and dependencies entirely

**Architectural guardrail:** Kalasim imports MUST NOT leak beyond `sim/` package. KTL reviews every PR. Detekt custom rule if feasible.

#### Phase 3: Test Migration and Validation (Months 10-12)

- Migrate ~100 simulation tests
- Re-enable 52 previously disabled tests (PR #257)
- Golden output re-baselining with physics validation
- Integration test suite for 20+ train scenarios in CI
- JaCoCo coverage verification >= 51%

#### Phase 4: Documentation, Cleanup, and Rollout (Months 13-15)

- Update all documentation (CLAUDE.md, architecture docs)
- SonarQube clean run
- Release candidate testing
- Merge to `main`

#### Rollback Procedures

**Level 1 (Phase 2):** Revert individual simulation processes to jDisco (dual-mode maintained through Month 9)
**Level 2 (Phase 2-3):** Abandon feature branch; `develop` remains on jDisco
**Level 3 (Phase 1 NO-GO):** Pivot to DSOL (A3); Phase 0 artifacts transfer directly

#### Timeline

```
Month:  1    2    3    4    5    6    7    8    9   10   11   12   13   14   15
Phase:  |--- Phase 1: PoC ---|--- Phase 2: Core Migration ---|-- Phase 3 --|-- P4 --|
Gate:                    GO/NO-GO                        jDisco removed   Tests   Release
```

#### Success Metrics

| Metric | Target |
|--------|--------|
| Migration duration | <= 15 months |
| Test count | >= 1840 |
| Test pass rate | 100% |
| Code coverage | >= 51% |
| Multi-train support | >= 50 concurrent trains |
| Determinism | Bit-identical across 10 runs |
| Performance | <= 2x jDisco wall-clock |
| Kalasim import containment | 0 imports outside `sim/` |

---

## 7. Consolidated Vote Matrix

### All 27 Voting Contexts, All 7 Members

| # | Context | TSE | KTL | JSD | KJD | AA | RCE | QA |
|---|---------|-----|-----|-----|-----|----|-----|----|
| **Meeting 1** |
| 1 | Null safety (Issue #83) | A6 | A5 | A2 | A5 | A6 | -- | A5 |
| 2 | Object identity (Issues #100,#280) | A6 | A6 | A2 | A5 | A6 | A6 | A6 |
| 3 | Non-determinism (Issue #122) | A6 | A5 | A1 | A5 | A6 | -- | A6 |
| 4 | Multi-train scalability (Issue #316) | A4 | A6 | A2 | A5 | A6 | A4 | A6 |
| 5 | Test infrastructure (PR #257) | A6 | A6 | A2 | A6 | A6 | -- | A6 |
| 6 | Animation bridge (PR #267) | A3 | A6 | A1 | A5 | A6 | -- | A5 |
| 7 | Path reservation (Issue #275) | A6 | A6 | A2 | A5 | A6 | A6 | A6 |
| 8 | Continuous simulation | A4 | A4 | A1 | -- | A4 | A4 | -- |
| 9 | Migration risk | A6 | A6 | A1 | A5 | A6 | A2 | A2 |
| 10 | Koin DI compatibility | A6 | A6 | -- | A6 | A6 | -- | -- |
| **Meeting 2** |
| 11 | Goal 1: Multi-Train | A5 | A5 | A3 | A5 | A6 | A3 | A5 |
| 12 | Goal 5: Save/Restore | A5 | A5 | A3 | A5 | A3 | A5 | A3 |
| 13 | Goal 7: Speed Control | A5 | A5 | A1 | A5 | A5 | A5 | A5 |
| 14 | Goal 10: AI Dispatcher | A5 | A5 | A3 | A5 | A6 | A5 | A5 |
| 15 | Goal 11: Track Gradients | A5 | A5 | A1 | A5 | A5 | A5 | A5 |
| 16 | Goal 14: Custom Train Types | A5 | A5 | A1 | A5 | A5 | A5 | A5 |
| **Meeting 3** |
| 17 | API mapping quality | A5 | A5 | A2 | A5 | A6 | A5 | A5 |
| 18 | Migration effort | A5 | A5 | A1 | A5 | A6 | A5 | A5 |
| 19 | Kotlin idiom alignment | A5 | A5 | A1 | A5 | A5 | -- | A5 |
| 20 | Test migration complexity | A5 | A5 | A2 | A5 | A6 | -- | A6 |
| 21 | Koin DI integration | A5 | A5 | A1 | A5 | A5 | A5 | A5 |
| 22 | Debugging/transparency | A5 | A5 | A1 | A5 | A6 | -- | A5 |
| **Meeting 4** |
| 23 | Lowest migration risk | A5 | A5 | A1 | A5 | A6 | A5 | A5 |
| 24 | Best long-term sustainability | A5 | A5 | A3 | A5 | A5 | A3 | A5 |
| 25 | Best upstream health | A5 | A3 | A3 | A5 | A3 | A3 | A5 |
| 26 | Best rollback safety | A5 | A5 | A1 | A5 | A6 | A5 | A5 |
| 27 | Best for team skills | A5 | A5 | A1 | A5 | A5 | A5 | A5 |

### Per-Member Totals Across All Meetings

| Member | A1 | A2 | A3 | A4 | A5 | A6 | Abstains | Total Votes |
|--------|----|----|----|----|----|----|----------|-------------|
| TSE | 0 | 0 | 1 | 2 | 17 | 7 | 0 | 27 |
| KTL | 0 | 0 | 1 | 1 | 18 | 7 | 0 | 27 |
| JSD | 14 | 7 | 5 | 0 | 0 | 0 | 1 | 27 |
| KJD | 0 | 0 | 0 | 0 | 24 | 2 | 1 | 27 |
| AA | 0 | 0 | 2 | 1 | 7 | 17 | 0 | 27 |
| RCE | 0 | 1 | 3 | 2 | 11 | 2 | 8 | 27 |
| QA | 0 | 1 | 1 | 0 | 17 | 6 | 2 | 27 |

---

## 8. Appendices

### A. Reference Documents
- `docs/simulation-approach-analysis.md` -- 6 approaches comparison matrix
- `docs/jdisco-research.md` -- DSOL, Kalasim, SSJ analysis
- `LONG_TERM_GOALS.md` -- 20 goals, 57 months estimated
- `docs/KOTLIN_STYLE_GUIDE.md` -- Build environment and coding conventions

### B. Key GitHub Issues Referenced
- **Issue #42** -- Virtual threads investigation (CLOSED)
- **Issue #83** -- Null handling friction (CLOSED)
- **Issue #94** -- SimulationEnvironment facade (CLOSED)
- **Issue #100** -- Dynamic wrapper identity crisis (CLOSED)
- **Issue #122** -- Non-deterministic simulation (CLOSED)
- **Issue #153** -- Context inheritance incompatibility (CLOSED)
- **Issue #275** -- Path reservation conflict (CLOSED)
- **Issue #280** -- Second train deadlock (CLOSED)
- **Issue #291** -- K2 track never used (CLOSED)
- **Issue #292** -- Path Discovery Restructuring (CLOSED)
- **Issue #316** -- Trains #8+ deadlock (OPEN -- CRITICAL)

### C. Key Pull Requests Referenced
- **PR #95** -- Decouple DefaultContext via factory pattern
- **PR #238** -- Conservative simulation tests (30%->45%)
- **PR #254** -- SimulationEnvironment facade implementation
- **PR #257** -- Re-enable 52 disabled integration tests
- **PR #267** -- Animation infrastructure bridge
- **PR #285** -- Fix copyGridCells dynamic wrappers
- **PR #286** -- Fix train deadlock (#280)
- **PR #312** -- Remove env.stop() from train wait
- **PR #342** -- Kotlin property accessors for Train API
- **PR #352** -- PathResult sealed class
- **PR #356** -- Bidirectional train operation
- **PR #367** -- Koin golden output validation

### D. Team Roles (from TEAM.md)
| Role | Authority | Focus |
|------|-----------|-------|
| traffic-simulation-expert | Autonomous (arbiter) | Simulation, physics, railway domain |
| kotlin-tech-lead | Autonomous (non-sim) | GUI, context, API design |
| java-senior-dev | Read-only analysis | Historical code, regression analysis |
| kotlin-junior-dev | Proposal-only | Implementation, testing |
| agent-architect | Proposal-only | AI agent design, A2A protocols |
| railway-civil-engineer | Domain expert | Railway correctness, safety rules |
| qa-engineer | Coordinates w/ tech-lead | Testing, UX/UI |
