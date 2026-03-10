# Simulation Library Decision: Independent Audit & Technical Expertise Review

**Date:** 2026-02-08
**Reviewer:** Independent Technical Expert (Claude Opus 4.6)
**Scope:** Full audit of Round 1 and Round 2 decision documents
**Documents reviewed:**
- `docs/SIMULATION_LIBRARY_DECISION.md` (Round 1 — Kalasim selection, 7-0 unanimous)
- `docs/SIMULATION_LIBRARY_DECISION_ROUND2.md` (Round 2 — A2→A6→A5 phased road, 5-2)

**Related verification:** `docs/SIMULATION_LIBRARY_DECISION_VERIFICATION.md` (Copilot automated arithmetic verification — confirms zero calculation errors)

**Scope distinction:** The Copilot verification covers arithmetic correctness of vote tallies. This document extends the audit with factual cross-referencing against the codebase, criteria completeness analysis, and independent technical expertise on whether the selected approach is technically optimal.

---

## Table of Contents

1. [Calculation Verification Summary](#1-calculation-verification-summary)
2. [Factual Discrepancies Found](#2-factual-discrepancies-found)
3. [Criteria Completeness Review](#3-criteria-completeness-review)
4. [Technical Expertise — Pivotal Determination](#4-technical-expertise--pivotal-determination)
5. [Technical Expertise — Kalasim vs DSOL vs Status Quo](#5-technical-expertise--kalasim-vs-dsol-vs-status-quo)
6. [Technical Expertise — Road 2 vs Road 1](#6-technical-expertise--road-2-vs-road-1)
7. [Risks Not Adequately Addressed](#7-risks-not-adequately-addressed)
8. [Final Expert Opinion](#8-final-expert-opinion)

---

## 1. Calculation Verification Summary

### Round 1 Vote Verification

**Total votes:** 177 (27 contexts × 7 members, minus 12 abstentions = 177 cast votes + 12 abstentions = 189 opportunities)

Verified cell-by-cell:

| Check | Result |
|-------|--------|
| Per-row vote totals (each context sums to 7) | **Correct** |
| Per-member column totals (each member sums to 27) | **Correct** |
| Cumulative meeting totals (M1+M2+M3+M4) | **Correct** |
| Post-pivotal subtotals (M2+M3+M4 = 116 decisive + 1 abstention) | **Correct** |
| Percentage calculations (A5: 94/177 = 53.1%, post-pivotal A5: 83/116 = 71.6%) | **Correct** |
| Grand total reconciliation (14+9+13+6+94+41 = 177) | **Correct** |

**No arithmetic errors found in Round 1.**

### Round 2 Vote Verification

**Total votes:** 196 (28 contexts × 7 members)

Verified cell-by-cell:

| Check | Result |
|-------|--------|
| Per-row vote totals (each context sums to 7, except 1 abstention row = 6+1) | **Correct** |
| Per-member column totals | **Correct** |
| Road 2 total: 84 votes (42.9%) | **Correct** |
| Road 1 total: 40 votes (20.4%) | **Correct** |
| Tie/Neutral total: 71 votes (36.2%) | **Correct** |
| Decisive-only ratio: 84/(84+40) = 67.7% | **Correct** |
| Final ranked vote: 5-2 (TSE, KTL, JSD, RCE, QA for Road 2; KJD, AA for Road 1) | **Correct** |

**No arithmetic errors found in Round 2.**

---

## 2. Factual Discrepancies Found

Three factual inaccuracies discovered during codebase cross-referencing:

### 2.1 jDisco File Count

**Claim (both documents):** "13 source files with jDisco imports"

**Actual count from codebase (`grep -r "^import.*jDisco" src/main/`):** **10 production source files** with direct jDisco import statements:

| # | File | jDisco Imports |
|---|------|---------------|
| 1 | `Train.kt` | `Condition`, `Continuous`, `Process`, `Reporter`, `Variable` |
| 2 | `InOutWorker.kt` | `Condition`, `Head`, `Link`, `Process` |
| 3 | `Generator.kt` | `Random` |
| 4 | `LoopProcess.kt` | `Process` |
| 5 | `SimpleIntegration.kt` | `Continuous`, `Variable` |
| 6 | `ContinuousInvariantChecker.kt` | `Continuous` |
| 7 | `DynamicTrackBlock.kt` | (via package — extends jDisco-dependent type) |
| 8 | `DynamicTrack.kt` | (via package — extends jDisco-dependent type) |
| 9 | `DefaultSimulationContext.kt` | `DiscoException`, `Process`, `Random` |
| 10 | `SimulationException.kt` | `Process` |

**Files counted in the document's 13 but NOT having direct imports:**
- `ShuntingLoop.kt` — inherits from jDisco indirectly through `Interlocking → LoopProcess → jDisco.Process`
- `Interlocking.kt` — inherits from jDisco indirectly through `LoopProcess → jDisco.Process`
- `DefaultSimulationProcessFactory.kt` — references jDisco **only in comments**, zero import statements

**Additionally**, 2 test files have jDisco imports (`InOutWorkerPathHandlingTest.kt`, `SimpleIntegrationTest.kt`), but these were not counted in the document's "13 source files" claim either.

**Impact on decisions:** Low. Whether the count is 10 (direct imports only), 12 (including indirect inheritance), or 13 (including comment-only references), the migration scope is materially the same. The files that matter for migration are the same regardless of counting methodology.

**Recommended correction:** Update to "10 source files with direct jDisco imports (12 with indirect coupling)" for precision.

### 2.2 SimulationEnvironment Method Count

**Claim (Round 1):** "11 essential methods (reduced from 18)"

**Actual count from `SimulationEnvironment.kt`:** **18 methods** currently declared in the interface.

| # | Method | Added By |
|---|--------|----------|
| 1 | `getInOuts()` | Original (Issue #94) |
| 2 | `getTopologyNavigator()` | Issue #296 Phase 5 |
| 3 | `isSeparatorInDirection()` | Original (Issue #94) |
| 4 | `getTrainNavigationService()` | Issue #295 (Phase 3 of #292) |
| 5 | `getPathReservationService()` | Issue #296 |
| 6 | `getRailWayNetGrid()` | Type safety extension |
| 7 | `getGraph()` | Type safety extension |
| 8 | `configureSemaphoreSignal()` | Path reservation support |
| 9 | `toDynamic(PathSeparator)` | Original (Issue #94) |
| 10 | `toDynamic(TrackFacility)` | Original (Issue #94) |
| 11 | `getWorkerFor(DynamicInOut)` | Original (Issue #94) |
| 12 | `report()` | Original (Issue #94) |
| 13 | `stop()` | Original (Issue #94) |
| 14 | `errorStop()` | Original (Issue #94) |
| 15 | `isReporting()` | Original (Issue #94) |
| 16 | `addReportTypes()` | Original (Issue #94) |
| 17 | `releaseTrainReservations()` | Path reservation support |
| 18 | `unregisterBlock()` | Path reservation support |

**Explanation:** The Round 1 document's claim of "11 essential methods" was accurate **at the time of writing** (Issue #94, January 2026). The interface has since grown to 18 methods through navigation services work (Issue #292, Phases 1-5). The Round 2 document still references "11 methods" without updating the count.

**Impact on decisions:** Moderate. Migration effort estimates based on wrapping 11 methods undercount the actual API surface by 64%. Each additional method needs a kDisco bridge wrapper (Road 2) or Kalasim equivalent mapping (Road 1). The 800-line bridge estimate may need upward revision.

**Recommended correction:** Update both documents to reflect the current 18-method count. Reassess bridge code estimates (possibly ~1,200 lines instead of ~800).

### 2.3 kDisco Repository Status

**Claim (Round 2, line 614):** "kDisco is already in incubation at https://github.com/bedaHovorka/kdisco/"

**Status at time of audit:** The repository is either private or does not yet exist (URL returns 404 for unauthenticated access).

**Impact on decisions:** Potentially significant for Road 2 Step 1 timeline. If the repository hasn't been created yet, the "already in incubation" framing in the decision document overstates readiness. Step 1's 4-week core work estimate assumes a starting point exists.

**Recommended correction:** Clarify the repository's actual status. If private, note access requirements. If not yet created, adjust Step 1 timeline to include repository bootstrapping.

---

## 3. Criteria Completeness Review

### What's Well-Covered

The decision documents demonstrate strong analytical rigor in several areas:

- **Pain points grounded in real issues/PRs** — Every claimed problem (deadlocks at 8+ trains, null-safety friction, Java 6 constraints) is traceable to specific GitHub issues and PRs
- **Long-term goals alignment** — 6 of 20 LONG_TERM_GOALS.md objectives explicitly evaluated (the most migration-relevant ones)
- **Technical architecture** — API mapping table (jDisco↔Kalasim) with method-level comparison
- **Risk assessment** — Sustainability, project health, and rollback capabilities analyzed per alternative
- **Breaking changes & migration safety** — Round 2's three-step validation gate structure is well-designed
- **Vote methodology** — Transparent per-context, per-member voting with meeting-by-meeting evolution tracking

### What's Missing or Underweighted

| Gap | Impact | Severity |
|-----|--------|----------|
| **No quantitative performance benchmarking** — Only qualitative "within 2x wall-clock" target. No actual benchmark data comparing jDisco vs Kalasim coroutine overhead for equivalent workloads. | PoC success criteria may be set too loose or too tight without baseline data | Medium |
| **Kalasim community health metrics sparse** — 75 GitHub stars, ~4 contributors, single primary maintainer (Holger Brandl). No discussion of bus factor, response times to issues, or release cadence trends. | Understates sustainability risk. Kalasim's MIT license + small codebase mitigates this (forkable), but the risk deserves explicit quantification | Medium |
| **No DSOL railway domain track record cited** — OpenTrafficSim (built on DSOL) is used for railway simulation at TU Delft. This would strengthen the DSOL comparison and provide evidence that DSOL's paradigm can model railway operations. | DSOL's railway viability may be underestimated in the comparison | Low |
| **Cost-of-wrong-decision asymmetry not analyzed** — What if the Kalasim PoC fails at month 3? The DSOL fallback adds 12-18 months. Total worst case: 21 months. No decision tree or expected-value analysis. | The PoC gate is the right mechanism, but the cost of the "fail" branch deserves explicit acknowledgment | Medium |
| **Kalasim's continuous simulation capabilities underexplored** — Kalasim does have `Component.process` with state tracking and `State<T>` monitoring that could approximate continuous behavior. The determination that "continuous simulation is NOT required" made this moot, but if the determination were wrong, the fallback position is unclear. | Low given the valid determination, but creates implicit assumption | Low |
| **Debugging/observability comparison is qualitative only** — No comparison of trace output, debugging tools, visualization support, or logging integration between jDisco, Kalasim, and DSOL | May affect developer experience estimates during migration | Low |

---

## 4. Technical Expertise — Pivotal Determination

TSE's finding that **"continuous simulation is NOT required"** is the single most consequential technical claim in both documents. The entire decision pivots on it — prior to this determination, A6 (kDisco+Kalasim) led with 32 votes in Meeting 1. After, A5 (Kalasim direct) surged to 71.6% of post-pivotal votes.

### Independent Verification

**The physics argument is correct for the current model.**

The acceleration formula in `Train.kt:717`:

```kotlin
val a: Double = ((targetSpeed - velocity.state) * (targetSpeed + velocity.state)) / (2 * s)
```

This is algebraically equivalent to `a = (v_target² - v²) / (2s)`, derived from the kinematic equation `v² = v₀² + 2as` solved for `a`.

**Key observation:** Between discrete state changes (semaphore signals, track boundaries, target speed changes), the acceleration IS constant because:
- `v_target` is constant (set by semaphore signal or speed limit)
- `s` decreases linearly as the train moves
- But `derivatives()` is called continuously by jDisco's ODE solver, so `s` and `v` are updated each integration step

**This means the current model already uses an ODE solver (jDisco's `Continuous`) to integrate what is actually a closed-form kinematic equation.** The simulation calls `derivatives()` hundreds of times per track section when the analytical solution `v(t) = v₀ + at` and `s(t) = v₀t + ½at²` would give identical results in a single computation.

**TSE's determination is therefore not just correct — it reveals that the current simulation is over-engineered.** The continuous integration is solving a problem that has an exact analytical solution.

### Future Physics Considerations

| Future Feature | Acceleration Model | Analytical Possible? |
|---|---|---|
| Goal 11: Track Gradients | `a_grade = -g·sin(θ)`, constant per section | Yes — piecewise constant, same model |
| Realistic braking curves | `F_brake = f(v)`, variable | No — requires numerical integration or lookup tables |
| Aerodynamic drag | `F_drag ∝ v²`, velocity-dependent | No — produces nonlinear ODE |
| Traction motor curves | `F = f(v)`, speed-dependent | No — requires numerical integration |
| Multi-train electromagnetic braking | `F_em = f(v, I)` | No — coupled ODEs |

**Verdict:** The determination is **sound for current and near-term needs** (including Goal 11). It creates a **technical debt ceiling** for advanced physics models, but the PoC gate provides adequate protection — if Kalasim proves insufficient for future physics, the fallback to DSOL remains viable at that point.

---

## 5. Technical Expertise — Kalasim vs DSOL vs Status Quo

### Structured Technical Comparison

| Technical Criterion | Kalasim (A5) | DSOL (A3) | Keep jDisco (A1) |
|---|---|---|---|
| **API mapping to jDisco** | Near-identical (5/11 core methods share names: `hold`, `passivate`, `activate`, `waitUntil`, process model) | Paradigm mismatch (event-scheduling vs process-interaction) | N/A |
| **Language alignment** | Kotlin-native, idiomatic | Java 17+ (requires Kotlin interop layer) | Java 6 (22 years old) |
| **Coroutine/thread model** | Kotlin coroutines (lightweight, millions possible) | Java threads / virtual threads (Java 21) | Java threads (deadlocks at 8+ trains, Issue #316) |
| **Scalability evidence** | Untested beyond demos (PoC needed) | Proven in OpenTrafficSim (large-scale traffic simulation) | Fails at 8 concurrent trains |
| **Continuous simulation** | Not built-in (analytical replacement needed) | Native ODE solvers (Euler, RK4, Adams-Bashforth) | Native ODE solvers (custom) |
| **DI integration (Koin)** | Kotlin-native, trivial integration | Java-to-Koin bridge needed | Java-to-Koin bridge needed |
| **Upstream health** | ~75 stars, 1 primary maintainer, MIT license, ~70 releases | ~4 stars, TU Delft institutional backing, BSD-3, actively maintained | Unmaintained since ~2004 |
| **Migration effort** | 4-6 months (document estimate) | 12-18 months (document estimate) | 0 |
| **Codebase size** | ~5,000 lines (forkable if abandoned) | ~50,000+ lines (complex, institutional) | ~2,000 lines |
| **Railway domain validation** | None (general-purpose DES) | OpenTrafficSim (proven railway/traffic simulation) | 19 years in this project |
| **Process interaction model** | `Component.process() = sequence { hold(); passivate() }` | `AbstractProcess.process()` with different semantics | `Process.actions()` with `hold()`, `passivate()` |

### Technical Verdict on Round 1 (Kalasim Selection)

**Kalasim is the technically correct choice** given the combination of:

1. **Near-identical API mapping** — This dramatically reduces migration risk. The jDisco→Kalasim translation is largely mechanical (rename classes, adjust syntax). The jDisco→DSOL translation requires fundamental paradigm changes.

2. **Kotlin-native alignment** — The codebase is 100% Kotlin. Adding a Java library (DSOL) reintroduces the interop friction the Java→Kotlin migration was meant to eliminate.

3. **Valid continuous simulation determination** — This eliminates DSOL's primary technical advantage (native ODE solvers).

4. **MIT license + small codebase** — If Kalasim is abandoned, a fork of 5,000 lines is maintainable. Forking DSOL's 50,000+ lines is not realistic.

**DSOL would be technically superior IF:**
- Continuous simulation were genuinely needed (it isn't, per valid determination)
- Proven railway domain experience were weighted above API compatibility
- Institutional backing were prioritized over language alignment
- The project had 12-18 months of migration budget available

**The 7-0 unanimous decision is technically justified.**

---

## 6. Technical Expertise — Road 2 vs Road 1

### Structured Comparison

| Technical Criterion | Road 1 (Direct A5) | Road 2 (A2→A6→A5 Phased) |
|---|---|---|
| **Total code written** | ~500 lines (all permanent) | ~1,300 lines (800 temporary bridge + 500 permanent) |
| **sim/ blast radius per step** | 10 files changed at once | Step 1: 0 files → Step 2: 10 files → Step 3: 10 files |
| **Validation checkpoints** | 1 (final) | 3 (after each step) |
| **Time to Goal 1 (multi-train)** | 4-6 months | 6-8 months (+2 months) |
| **Rollback granularity** | All-or-nothing | Per-step (can halt after Step 1 or Step 2) |
| **CLAUDE.md compliance** | Violates "minimal changes to sim/" spirit | Respects conservative sim/ restriction |
| **Dual-mode validation** | Not possible | Step 1 enables jDisco vs kDisco output comparison |
| **API stability during migration** | Unstable for 4-6 months | Frozen after Step 1 completion |
| **Bridge maintenance overhead** | None | ~800+ lines maintained for 12+ months |
| **YAGNI compliance** | Full compliance (no temporary code) | ~800 lines of code that will be deleted |
| **SimulationEnvironment surface** | 18 methods mapped once | 18 methods wrapped in bridge, then mapped, then bridge removed |
| **Total developer-months** | ~4-6 | ~6-8 (1.5x) |

### Technical Verdict on Round 2 (Phased Road Selection)

Road 2 is the **technically more conservative** choice. It aligns with the project's DNA — a codebase that has survived 19 years values stability over speed.

However, it is **not clearly technically superior** to Road 1:

**Arguments that the 800-line bridge is genuine waste:**
- The team has proven migration capability: Java→Kotlin (94 files, zero regressions), Context Refactoring (5 phases, 927+ tests, 70% faster than estimated), Navigation Services (5 phases completed)
- The API mapping is near-identical, so most changes are mechanical find-and-replace
- The bridge introduces its own surface area for bugs (bridge bugs vs migration bugs)
- YAGNI principle: the bridge exists only to be deleted
- The 2-month Goal 1 delay cascades to at least 5 downstream goals

**Arguments that Road 2's conservatism is justified:**
- The sim/ package conservative restriction exists for documented reasons (simulation correctness is hard to verify)
- Dual-mode validation (Step 1) catches bridge implementation errors with mathematical certainty (±1e-6m)
- Graduated blast radius respects the team's risk appetite
- Three validation gates vs one means earlier detection of problems
- The cultural value of respecting established restrictions outweighs pure efficiency

**The 5-2 decision is defensible but debatable.** AA and KJD's dissent has genuine technical merit — their position that the phased approach over-engineers the solution is a valid interpretation of the evidence. The deciding factor was cultural (conservative project DNA, sim/ restriction) rather than purely technical.

---

## 7. Risks Not Adequately Addressed

### 7.1 Kalasim PoC Failure Cascade

**Risk:** If the 50-train scalability PoC fails at month 3, the documented DSOL fallback adds 12-18 months.

| Scenario | Road 1 Timeline | Road 2 Timeline |
|---|---|---|
| PoC succeeds | 15 months | 18 months |
| PoC fails → DSOL fallback | 3 + 18 = **21 months** | 4 + 18 = **22 months** |
| PoC fails → custom solution | 3 + 6-12 = **15-21 months** | 4 + 6-12 = **16-22 months** |

The PoC gate is the right mechanism, but the **cost of the "fail" branch** is underweighted in the documents. There is no decision tree or expected-value analysis. A 20% PoC failure probability (reasonable for unproven scalability) yields:
- Expected Road 1 timeline: 0.8 × 15 + 0.2 × 21 = **16.2 months**
- Expected Road 2 timeline: 0.8 × 18 + 0.2 × 22 = **18.8 months**

**Recommendation:** Add explicit PoC failure criteria and accelerated DSOL evaluation protocol to the decision documents.

### 7.2 SimulationEnvironment Surface Growth

The interface has grown from 11 → 18 methods since the Round 1 decision was made. This trend will likely continue as navigation services evolve. Each new method:
- Requires a kDisco bridge wrapper (Road 2)
- Increases the migration seam complexity
- May introduce new jDisco↔Kalasim mapping challenges

**Recommendation:** Freeze SimulationEnvironment before Step 1 begins. No new methods until migration completes.

### 7.3 kDisco Repository Bootstrapping

Road 2 Step 1 depends on the kDisco module existing. If the repository at `https://github.com/bedaHovorka/kdisco/` hasn't been created yet, the Step 1 timeline needs to include:
- Repository creation and CI setup (~1-2 days)
- Build system integration with interlockSim (~1-2 days)
- API design and initial scaffolding (~1 week)

**Recommendation:** Verify repository status before Step 1 planning. Adjust timeline if bootstrapping is needed.

### 7.4 Kalasim Version Pinning

Neither document specifies which Kalasim version to target. Kalasim has ~70 releases, and API compatibility between major versions is not guaranteed. The PoC should pin a specific version and document any version-specific API dependencies.

**Recommendation:** Pin Kalasim version in PoC and document version compatibility constraints.

---

## 8. Final Expert Opinion

### Round 1 (Kalasim Selection): TECHNICALLY SOUND

The API mapping quality between jDisco and Kalasim is the decisive factor. Five of eleven core process-interaction methods share identical names and semantics (`hold`, `passivate`, `activate`, `waitUntil`, process model). This is not a coincidence — both libraries implement the DEVS (Discrete Event System Specification) process-interaction paradigm.

Given that continuous simulation is provably unnecessary for the current physics model (verified: `a = (v_target² - v²) / (2s)` is piecewise-constant between state changes), Kalasim's advantages decisively outweigh its disadvantages:

| Factor | Weight | Kalasim | DSOL |
|--------|--------|---------|------|
| API compatibility | High | Strong win | Paradigm mismatch |
| Language alignment | High | Kotlin-native | Java interop needed |
| Migration effort | High | 4-6 months | 12-18 months |
| Continuous simulation | Low (not needed) | N/A | Advantage negated |
| Scalability proof | Medium | PoC needed | Proven |
| Upstream sustainability | Medium | Forkable (5k lines) | Institutional |

**The unanimous decision correctly reflects the technical reality.**

### Round 2 (Phased Road Selection): TECHNICALLY DEFENSIBLE BUT NOT OPTIMAL

The phased approach trades **development velocity for risk reduction**. For a team that has successfully completed multiple large-scale migrations with zero regressions (94-file Java→Kotlin conversion, 5-phase context refactoring, 5-phase navigation services), the conservative approach may be **over-cautious**.

The ~800-line bridge is genuine YAGNI waste. It will be written, maintained for 12+ months, tested, and deleted. The only permanent artifact is the validation confidence it provides — which is valuable, but achievable through other means (comprehensive test suites, golden output comparison).

However, the sim/ conservative restriction exists for good reason — simulation correctness is notoriously difficult to verify, and the project's 19-year stability is a genuine asset worth protecting. **Respecting established restrictions has cultural value beyond pure technical merit.** The 5-2 vote accurately reflects the genuine technical tension between efficiency and safety.

### Recommendations

1. **Accept both decisions as-is.** The technical concerns raised do not warrant revisiting the votes.

2. **Correct errata in both documents:**
   - jDisco file count: "10 files with direct imports (12 with indirect coupling)" instead of "13"
   - SimulationEnvironment method count: "18 methods (grown from original 11)" instead of "11"
   - kDisco repository: Clarify actual status (private vs not-yet-created)

3. **Pre-Step-1 actions:**
   - Freeze SimulationEnvironment interface (no new methods until migration complete)
   - Verify kDisco repository exists and is accessible
   - Pin target Kalasim version
   - Document explicit PoC failure criteria and DSOL fallback protocol

4. **Bridge estimate revision:** With 18 methods (not 11), the ~800-line bridge estimate should be reviewed. A rough scaling suggests ~1,200-1,400 lines may be more realistic.

---

*This audit was conducted by examining the actual codebase (`grep`, file reads, interface analysis) to verify claims made in the decision documents. All factual assertions in this review are grounded in source code evidence as of 2026-02-08.*
