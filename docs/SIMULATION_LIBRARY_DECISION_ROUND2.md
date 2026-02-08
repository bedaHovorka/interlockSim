# Simulation Library Decision Round 2: Migration Road Selection

**Decision Date:** 2026-02-08
**Decision:** A2→A6→A5 phased migration road (kDisco bridge → Kalasim backend → bridge removal)
**Vote:** 5-2 majority (TSE, KTL, JSD, RCE, QA for Road 2; KJD, AA for Road 1)
**Predecessor:** [Round 1](SIMULATION_LIBRARY_DECISION.md) — confirmed Kalasim (A5) as destination (7-0 unanimous)
**End State:** Identical for both roads — sim/ talks directly to Kalasim, no bridge layer
**Decision Authority:** traffic-simulation-expert (TSE), exercised as ratification of majority consensus

---

## 1. Executive Summary

Round 1 selected Kalasim as the simulation library (7-0 unanimous). Round 2 decides **how to get there** — the migration road. Two roads lead to the same destination:

**Road 1 (A5 Direct):** Rewrite sim/ against Kalasim API in one step. Remove jDisco. Done.

**Road 2 (A2→A6→A5 Phased):** Build kDisco bridge matching jDisco API (Step 1). Swap jDisco backend for Kalasim backend behind the bridge (Step 2). Remove bridge, sim/ talks to Kalasim directly (Step 3). End state identical to Road 1.

**Key insight:** Road 2 is not a competing endpoint — it is a phased migration path that converges to Road 1. The kDisco bridge is temporary scaffolding, removed at completion. The question is whether the scaffolding justifies its cost.

**Voting summary across 4 meetings (28 voting contexts × 7 members = 196 possible votes, 195 cast, 1 abstention):**

| Road | Total Votes | Share |
|------|-------------|-------|
| Road 2 (A2→A6→A5) | **84** | **42.9%** |
| Road 1 (A5 Direct) | **40** | **20.4%** |
| Tie/Neutral | **71** | **36.2%** |

**Decisive votes only (excluding ties, 124 votes):** Road 2 = 84 (67.7%), Road 1 = 40 (32.3%).

**Decision drivers:**
- sim/ conservative restriction (CLAUDE.md) strongly favors Road 2: zero sim/ changes in Step 1, incremental changes in Steps 2-3
- 5 continuous↔discrete coupling points are de-risked by bridge isolation
- Dual-mode validation (jDisco AND kDisco side-by-side in Step 1) is impossible with Road 1
- Time to first working milestone: ~2 months (Road 2 Step 1) vs 4-6 months (Road 1 all-or-nothing)
- Road 2 converges to Road 1 at completion — same end state, different journey

**Binding conditions:**
1. kDisco bridge MUST be removed by end of Step 3 (no permanent abstraction layer)
2. Step 1 gate: dual-mode validation — identical golden output from jDisco and kDisco backends
3. Step 2 gate: Kalasim backend passes all 1840 tests before jDisco removal
4. Bridge API frozen after Step 1 — no feature additions, only backend swaps
5. Total migration timeline: 18 months maximum (Step 1: 4mo, Step 2: 8mo, Step 3: 6mo)
6. Round 1 binding conditions remain in force (PoC gate, SimulationEnvironment facade, import containment, DSOL fallback)

---

## 2. Meeting 1: Continuous↔Discrete Coupling Deep Dive

**Date:** 2026-02-08 | **Duration:** 150 minutes | **Led By:** traffic-simulation-expert (TSE)

### Framing

TSE opened by establishing the meeting's scope: "Round 1 determined that continuous simulation is OPTIONAL — analytical kinematics with piecewise-constant acceleration provide exact closed-form solutions. But the coupling points between continuous and discrete simulation still exist in the codebase. They must be migrated regardless of which road we take. The question is: which road handles each coupling point better?"

### Coupling Point 1: Motor Acceleration Control

**What it does:** The discrete `Motor` process (Train.kt:618-724) starts and stops continuous ODE integration. `Motor.start()` activates the `Continuous` superclass which triggers numerical integration. `Motor.stop()` halts it. The `derivatives()` method (line 708) computes acceleration based on distance to next semaphore.

**TSE:** "Motor extends both LoopProcess (→Process) and Continuous. It's a hybrid entity — discrete process lifecycle with continuous state evolution. Road 1 replaces this with a Kalasim Component that uses analytical kinematics. Road 2 wraps Motor in kDisco first, preserving the Process/Continuous dual inheritance."

**KTL:** "Road 2 is cleaner here. Motor.derivatives() at line 708 computes `a = (v_target² - v²) / (2s)`. In Road 1, we rewrite this immediately. In Road 2, Step 1 wraps it unchanged, Step 2 swaps the backend, Step 3 rewrites it. Each step is smaller."

**JSD:** "The original Java Motor was the most carefully tuned class. The acceleration formula has been validated over 19 years. I prefer the road that touches it last."

**KJD:** "I'm not sure I follow — wouldn't Road 2 mean we carry this complexity through three steps instead of addressing it once? Motor is only 107 lines. Couldn't the analytical replacement be simpler?"

**AA:** "Both roads need to handle the Motor.derivatives() → analytical kinematics transition. Road 2 defers it; Road 1 addresses it immediately. Deferral is neither better nor worse — it's a sequencing choice."

**RCE:** "The physics must be validated at every step. Road 2 gives us three validation checkpoints. Road 1 gives us one."

**QA:** "Three checkpoints means three rounds of golden output testing. More work, but more confidence."

### Coupling Point 2: Position-Based Event Triggering

**What it does:** `waitUntil { position.state + dtMin >= nextLength }` (Train.kt:207-210) fires a discrete event when a continuous Variable reaches a threshold. This is the core mechanism for trains detecting they've reached the end of a track section.

**TSE:** "This is the most critical coupling point. In jDisco, `waitUntil` with a lambda polls the continuous Variable state. In Kalasim, `wait(State) { predicate }` monitors a State<T> for changes. The semantics are close but not identical — jDisco polls at integration steps, Kalasim monitors state changes."

**KTL:** "Road 2 advantage: kDisco bridge can implement `waitUntil` to poll its internal state, matching jDisco semantics exactly. Step 2 swaps the polling mechanism to Kalasim's state monitoring. Step 3 removes the bridge. The behavioral equivalence is validated at each step."

**KJD:** "In Road 1, we'd replace `waitUntil { position.state >= threshold }` directly with Kalasim's `wait(positionState) { it >= threshold }`. It looks like a near-identical transformation to me. Does the bridge add value here?"

**JSD:** "The `dtMin` correction term (line 209) compensates for numerical integration step size. With analytical kinematics, this term disappears entirely. Road 1 removes it cleanly. Road 2 carries it through Steps 1-2 before removing in Step 3."

**TSE:** "Valid point. But `dtMin` is entangled with 5 other Variables. Removing it safely requires understanding all downstream effects."

### Coupling Point 3: Signal State Conditions

**What it does:** `waitUntil(allowingSignal(semaphore))` (Train.kt:342, referenced in Front.separatorAction) — a discrete signal change triggers continuous motor state. When a semaphore shows green, the train's Motor resumes acceleration.

**TSE:** "Signal conditions are pure discrete events — no continuous coupling. The `allowingSignal` Condition checks `semaphore.signal == Signal.GREEN`. Both roads handle this identically."

**KTL:** "Agreed. In kDisco bridge (Road 2), Condition wraps a boolean predicate. In Kalasim (Road 1), `wait(signalState) { it == GREEN }` is semantically identical."

**AA:** "This is a neutral coupling point. Neither road has an advantage."

### Coupling Point 4: Train Completion Synchronization

**What it does:** `waitUntil(front.terminated)` (Train.kt:797) and `waitUntil(tail.terminated)` (Train.kt:801) — the main Train process waits for its sub-processes (Front, Tail) to finish. These are Process lifecycle events.

**TSE:** "Front and Tail are inner classes extending Process (via Site). Their termination is a discrete event. In Kalasim, Component completion is tracked via the component's state machine. Both roads handle this."

**KTL:** "Road 2 preserves the existing `terminated: Condition` pattern (line 99) through Step 1. Road 1 replaces it with Kalasim's `Component.isTerminated` immediately."

**KJD:** "From what I've read, the Kalasim replacement seems simpler — `wait(front.statusMonitor) { it == PASSIVE }` or similar. Why would we defer the simplification?"

**RCE:** "Because three checkpoints are better than one for safety-critical synchronization."

### Coupling Point 5: Passivation vs Continuous Integration (Issue #291)

**What it does:** `passivate()` suspends the discrete process but does NOT stop continuous integration (Issue #291 bug). The Motor's ODE solver keeps running, causing train drift. The workaround is `hold(5.0)` polling (Train.kt:197).

**TSE:** "This is the coupling point that ALREADY has a known bug. Road 1 eliminates it by replacing continuous integration with analytical kinematics. Road 2 carries the bug through Step 1 (same jDisco backend), fixes it in Step 2 (Kalasim backend), and removes the workaround in Step 3."

**KTL:** "Road 2 Step 1 inherits the Issue #291 workaround. Not a regression — same behavior as today. But it's technical debt carried forward."

**JSD:** "The `hold(5.0)` workaround has been validated (PR #291 fix, golden output preserved). Carrying it through Step 1 is acceptable because the behavior is unchanged."

**KJD:** "Road 1 eliminates this entire category. Analytical kinematics don't have an ODE solver that continues running during passivation. The bug class is removed."

**QA:** "Both roads eventually eliminate the bug. Road 1 eliminates it immediately. Road 2 eliminates it in Step 2. The question is whether the delay matters."

**TSE:** "It doesn't matter operationally — the workaround is stable. But conceptually, Road 1 is cleaner."

### Additional Context: Simple Differential Equations

**SimpleIntegration.kt (30 lines):** Implements `x.rate = dx.state` — integrating velocity into position. This is the ODE that TSE's pivotal determination showed is replaceable with `s(t) = s₀ + v₀*t + 0.5*a*t²`.

**TSE:** "Should we migrate continuous simulation now or postpone? Road 1 requires migrating it NOW because the replacement is analytical kinematics — there's no continuous simulation to migrate TO. Road 2 allows keeping the ODE integration in Step 1 and replacing it in Step 2."

**RCE:** "The analytical formulas are proven. I don't see a reason to delay. But Road 2 gives us the option."

**KTL:** "This is where Road 2 shines for conservative development. SimpleIntegration.kt has been untouched since 2007. We can validate kDisco wrapping it without changing it, then swap to analytical in Step 2."

### Additional Context: ContinuousInvariantChecker (LengthChecker)

**ContinuousInvariantChecker.kt (50 lines):** Validates train length invariant at every integration step. LengthChecker (Train.kt:576-585) verifies `|front.totalDistance - tail.totalDistance - length| <= maxAbsError`.

**TSE:** "With analytical kinematics, this check becomes exact — no numerical error accumulates. The checker becomes trivially true and can be removed."

**KTL:** "Road 2 carries it through Step 1 as-is. Road 1 can remove it immediately since analytical kinematics have zero truncation error."

**AA:** "Another neutral point. Removal vs deferral, not a fundamental difference."

### Additional Context: Variable State Management (6 instances in Train)

**Train.kt lines 92, 727-729:** Variables for position (2 — front, tail), velocity (1), acceleration (1), plus SimpleIntegration wrappers. Each Variable has `.state` (current value) and `.rate` (derivative).

**TSE:** "In Kalasim, these become `State<Double>`. The API is: `val velocity = State(0.0)`, read via `velocity.value`, write via `velocity.value = x`. Close mapping."

**KTL:** "Road 2 Step 1: wrap Variable with kDisco.Variable that has identical `.state`/`.rate` API. Step 2: swap to `State<Double>` behind the wrapper. Step 3: remove wrapper, use State directly."

**KJD:** "Or Road 1: replace all 6 Variables with `State<Double>` in one step. The mapping is mechanical."

### Vote Matrix — Meeting 1

**Vote question:** "Which road better handles this coupling point / context?"

| # | Coupling Point / Context | TSE | KTL | JSD | KJD | AA | RCE | QA |
|---|--------------------------|-----|-----|-----|-----|----|-----|----|
| 1 | Motor acceleration control | R2 | R2 | R2 | Tie | Tie | R2 | Tie |
| 2 | Position-based event triggering | R2 | R2 | R2 | R1 | R1 | R2 | R2 |
| 3 | Signal state conditions | Tie | Tie | Tie | Tie | Tie | Tie | Tie |
| 4 | Train completion sync | R2 | R1 | R2 | R1 | R1 | R2 | R1 |
| 5 | Passivation vs continuous (#291) | R1 | R1 | R2 | R1 | R1 | R2 | Tie |
| 6 | SimpleIntegration (postpone/now?) | R2 | R2 | R2 | R2 | R1 | R2 | Tie |
| 7 | ContinuousInvariantChecker | R1 | Tie | R2 | Tie | Tie | Tie | Tie |
| 8 | Variable state management (6 instances) | Tie | R2 | R2 | R1 | R1 | R2 | R2 |

### Meeting 1 Tallies

| Road | Votes | Share |
|------|-------|-------|
| Road 2 (A2→A6→A5) | **24** | **42.9%** |
| Road 1 (A5 Direct) | **14** | **25.0%** |
| Tie/Neutral | **18** | **32.1%** |
| Total | **56** | 100% |

**Per-member breakdown:**

| Member | R1 | R2 | Tie | Total |
|--------|----|----|-----|-------|
| TSE | 2 | 4 | 2 | 8 |
| KTL | 2 | 4 | 2 | 8 |
| JSD | 0 | 7 | 1 | 8 |
| KJD | 4 | 1 | 3 | 8 |
| AA | 5 | 0 | 3 | 8 |
| RCE | 0 | 6 | 2 | 8 |
| QA | 1 | 2 | 5 | 8 |

**Key finding:** Coupling points favor Road 2 overall (42.9% vs 25.0%), with significant neutral territory (32.1%). AA favors Road 1, while TSE, KTL, JSD, and RCE favor Road 2. KJD initially leans Road 1 but is open to Road 2 arguments (convinced on SimpleIntegration postponement). QA stays neutral on most coupling points, deferring technical judgment to domain experts. The signal state coupling point (context 3) is unanimously neutral — pure discrete event, handled identically by both roads.

---

## 3. Meeting 2: Breaking Changes & Migration Safety

**Date:** 2026-02-08 | **Duration:** 130 minutes | **Led By:** kotlin-tech-lead (KTL) + java-senior-dev (JSD)

### Framing

KTL opened: "This meeting analyzes the breaking change profile of each road. We look at sim/ package impact, test breakage, rollback safety, and the conservative restriction from CLAUDE.md. JSD will provide the historical perspective on each file's modification risk."

JSD added: "I've cataloged the 13 files with jDisco imports. Each has a different risk level based on complexity, coupling depth, and test coverage. The question isn't just 'how many files change' but 'how safely do they change.'"

### Context 1: sim/ Conservative Restriction (CLAUDE.md)

CLAUDE.md states: *"Simulation Core (sim/ package): Minimal changes only — Be extremely conservative with simulation logic. No refactoring — Do not restructure working simulation code. Tests required — Any changes MUST have comprehensive test coverage first. No unsolicited improvements — Only make explicitly requested changes."*

**KTL:** "Road 1 rewrites 13 files in sim/ at once. This is not 'minimal changes' — it's a wholesale rewrite. Road 2 Step 1 changes ZERO sim/ files (bridge only in new kDisco module). Step 2 swaps imports. Step 3 removes bridge. Each step is individually conservative."

**JSD:** "The conservative restriction was written specifically to protect against the kind of risk Road 1 introduces. Road 2 respects the spirit and letter of that restriction."

**KJD:** "I've been thinking about this — the restriction was written for incremental changes, right? A planned migration with full test coverage seems like the appropriate way to change sim/. Should it really be used as a veto against necessary work?"

**TSE:** "KJD raises a fair point, and it shows good thinking. But the restriction exists because sim/ is the most fragile package. Road 2 reduces the blast radius per step. I lean toward respecting the restriction."

**AA:** "Both roads change the same files eventually. Road 2 just sequences the changes. The total risk is the same."

**RCE:** "Total risk may be the same, but risk per step is lower with Road 2. In railway engineering, we call this 'isolation of failure modes.' You don't replace the signaling system and the track simultaneously."

**QA:** "I can validate one step at a time. I cannot validate 13 files changed simultaneously with the same confidence."

### Context 2: sim/ Package Changes Per Step

| Step | Road 1 | Road 2 |
|------|--------|--------|
| Step 1 | 13 files rewritten | 0 sim/ changes (new kDisco module only) |
| Step 2 | Done | Import swap: `jDisco.*` → `kDisco.*` (13 files, mechanical) |
| Step 3 | — | Remove bridge, `kDisco.*` → Kalasim direct (13 files, targeted) |

**KTL:** "Road 2 Step 2 is mechanical — find-and-replace imports. The kDisco API matches jDisco API, so the sim/ code doesn't change behavior, only import paths. Step 3 is the real rewrite, but by then we've validated the bridge works."

**KJD:** "But wait — Road 2 has THREE steps touching sim/ (0 + 13 + 13 = 26 file-touches). Road 1 has ONE step (13 file-touches). Wouldn't more touches mean more chances for errors?"

**JSD:** "More touches with smaller scope. Each touch is validated independently."

### Context 3: Test Breakage Per Step

**QA:** "Current test suite: 1840 tests (1836 passing, 4 skipped). Approximately 100 tests directly exercise sim/ classes with jDisco assertions."

| Step | Road 1 | Road 2 |
|------|--------|--------|
| Step 1 | ~100 tests rewritten at once | 0 test changes |
| Step 2 | Done | ~100 tests: import swap only |
| Step 3 | — | Minor cleanup (remove bridge test utilities) |

**QA:** "Road 2 Step 1 has zero test changes because the bridge wraps jDisco — existing tests pass unchanged against kDisco-over-jDisco. This is the dual-mode validation window."

### Context 4: Rollback Safety

**KTL:** "Road 1: revert the entire feature branch. All or nothing. Road 2: revert individual steps. If Step 2 (Kalasim backend) fails, revert to kDisco-over-jDisco (Step 1 state) — sim/ code unchanged, only backend reverted."

**TSE:** "The Step 2 rollback is the key advantage. If Kalasim's behavior differs from jDisco, we can revert the backend without touching sim/ code."

**KJD:** "Road 1 also has rollback — revert the branch. Git makes this easy."

**KTL:** "Reverting a 13-file rewrite is technically easy but informationally expensive. You lose all the migration work. Road 2 Step 2 rollback preserves the Step 1 bridge work."

### Context 5: Golden Output Preservation (PR #367)

**QA:** "PR #367 established determinism baseline: position ±1e-6m, time ±1e-9s. Both roads must validate against this baseline."

**QA:** "Road 1: validate once after full migration. Road 2: validate after each step. Step 1 MUST produce identical output (same jDisco backend). Step 2 validates Kalasim output against jDisco baseline. Step 3 validates no regression from bridge removal."

**TSE:** "The Step 1 validation is the most powerful check: if kDisco-over-jDisco produces different output than jDisco, the bridge has a bug — and we haven't touched sim/ code. The bug is isolated to the bridge."

### Context 6: Dual-Mode Validation

**KTL:** "Road 2 Step 1 uniquely enables running the same scenario on both jDisco AND kDisco-over-jDisco. Output comparison validates the bridge. Road 1 cannot do this — it's rip-and-replace."

**KJD:** "Dual-mode adds development overhead. Building two runners, comparing output, maintaining both codepaths. Is this overhead justified?"

**TSE:** "For safety-critical simulation, yes. Dual-mode validation is standard practice in railway signaling system upgrades. You run the old and new systems in parallel before cutover."

**RCE:** "In railway engineering, parallel operation before cutover is not optional — it's required by regulation. I strongly support dual-mode validation."

### Context 7: API Stability During Migration

**KTL:** "Road 1: sim/ API is unstable throughout migration (4-6 months of active rewrite). Road 2: kDisco API is frozen after Step 1. Steps 2-3 swap backends behind a stable API."

**AA:** "This matters for parallel development. If other features are being built on sim/ during migration, Road 2 provides a stable API to build against."

### Context 8: Time to First Working Milestone

**TSE:** "Road 1: first working milestone is 4-6 months (complete migration). Road 2: first working milestone is ~2 months (Step 1 — kDisco bridge over jDisco, identical behavior validated)."

**KJD:** "But Road 2 Step 1 milestone is basically 'works exactly like before.' Isn't that just scaffolding rather than actual progress?"

**KTL:** "Scaffolding that validates the bridge. The bridge is reused in Step 2 for the actual Kalasim swap."

### Context 9: Static Process.time() and Process.activate() Replacement

**JSD:** "8+ calls to `Process.time()` (static method returning current simulation time) and 4+ calls to `Process.activate(otherProcess)` (static method waking a suspended process). These are global entry points into jDisco."

**KTL:** "Road 2 Step 1: kDisco provides identical static methods. Step 2: swap to Kalasim equivalents (`now` property, `activate(component)` method). Step 3: remove kDisco static wrappers."

**KJD:** "Road 1: replace directly with `now` and `activate()`. Simple, one-step transformation."

### Context 10: DiscoException Handling

**JSD:** "One catch in DefaultSimulationContext.kt (line 1130). `DiscoException` is jDisco's simulation termination exception."

**KTL:** "Road 2 Step 1: kDisco wraps this as `KDiscoException`. Step 2: maps to Kalasim's simulation completion mechanism. Both roads handle this identically in effort."

### Vote Matrix — Meeting 2

**Vote question:** "Which road has the better risk profile for this context?"

| # | Context | TSE | KTL | JSD | KJD | AA | RCE | QA |
|---|---------|-----|-----|-----|-----|----|-----|----|
| 9 | sim/ conservative restriction (CLAUDE.md) | R2 | R2 | R2 | R1 | R1 | R2 | Tie |
| 10 | sim/ package changes per step | R2 | R2 | R2 | R1 | R1 | R2 | Tie |
| 11 | Test breakage per step | R2 | R2 | R2 | Tie | Tie | R2 | R2 |
| 12 | Rollback safety | R2 | R2 | R2 | Tie | R1 | R2 | R2 |
| 13 | Golden output preservation | R2 | R2 | R2 | Tie | Tie | R2 | R2 |
| 14 | Dual-mode validation | R2 | R2 | R2 | R1 | R1 | R2 | Tie |
| 15 | API stability during migration | R2 | R2 | R2 | R2 | R2 | R2 | R2 |
| 16 | Time to first milestone | R2 | R2 | R1 | R1 | R1 | R2 | R1 |
| 17 | Static Process.time()/activate() | Tie | R2 | R2 | R1 | R1 | -- | Tie |
| 18 | DiscoException handling | Tie | Tie | Tie | Tie | Tie | Tie | Tie |

### Meeting 2 Tallies

| Road | Votes | Share |
|------|-------|-------|
| Road 2 (A2→A6→A5) | **39** | **55.7%** |
| Road 1 (A5 Direct) | **13** | **18.6%** |
| Tie/Neutral | **17** | **24.3%** |
| Abstention | **1** | **1.4%** |
| Total | **70** | 100% |

**Per-member breakdown:**

| Member | R1 | R2 | Tie | Total |
|--------|----|----|-----|-------|
| TSE | 0 | 8 | 2 | 10 |
| KTL | 0 | 9 | 1 | 10 |
| JSD | 1 | 8 | 1 | 10 |
| KJD | 5 | 1 | 4 | 10 |
| AA | 6 | 1 | 3 | 10 |
| RCE | 0 | 8 | 1 | 9 |
| QA | 1 | 4 | 5 | 10 |

**Key finding:** Meeting 2 strongly favors Road 2 (55.7%). The breaking changes and migration safety analysis is Road 2's strongest domain. The sim/ conservative restriction, rollback safety, dual-mode validation, and golden output preservation all favor phased migration. KJD begins to see Road 2's merit on API stability (context 15, voted R2) and defers on test breakage and golden output (Tie). QA stays neutral on the CLAUDE.md restriction and dual-mode validation but supports rollback safety and golden output preservation. DiscoException (context 18) is unanimously neutral — trivial for both roads. RCE abstained on static method replacement (context 17) as a technical detail outside domain expertise.

---

## 4. Meeting 3: Long-Term Goals & Future Extensibility

**Date:** 2026-02-08 | **Duration:** 140 minutes | **Led By:** railway-civil-engineer (RCE) + agent-architect (AA)

### Framing

RCE opened: "We now examine how each road serves the 20 long-term goals, open GitHub issues, and future needs. The question is not 'which road reaches Kalasim' — both do. The question is 'which road leaves us better positioned for the next 20 goals.'"

AA added: "I'll focus on the AI-related goals (9, 10) and cross-cutting concerns like DI integration and future library swaps."

### Context 1: Goal 1 — Multi-Train Simulation (CRITICAL, Issue #316)

**The reason for the migration.** jDisco deadlocks at 8+ trains. Kalasim's coroutine-based scheduling should handle 50+.

**TSE:** "Both roads reach Kalasim, so both solve Issue #316. The question is WHEN. Road 1: 4-6 months to first multi-train test. Road 2: Step 1 doesn't solve it (still jDisco backend). Step 2 (~6 months from start) swaps to Kalasim. Road 1 is faster to unblock Goal 1."

**KJD:** "This seems really critical to me. Goal 1 blocks Goals 3, 9, 10, 13, 18. Every month of delay cascades, doesn't it?"

**KTL:** "Road 2 is ~2 months slower to Goal 1, but arrives with validated bridge infrastructure that de-risks Steps 2-3."

**RCE:** "Two months is acceptable if it reduces migration risk. But it IS a cost."

### Context 2: Goal 5 — Save/Restore Simulation State

**TSE:** "jDisco processes are Java threads — not serializable. Kalasim coroutines capture state as continuations. Road 2's bridge layer could provide a clean serialization boundary."

**KTL:** "Road 2 advantage: if kDisco defines a serialization interface, all state flows through it. Road 1 serializes Kalasim state directly — less abstraction but tighter coupling."

**AA:** "The bridge is removed in Step 3. Any serialization boundary in the bridge is temporary. If we design the permanent serialization around SimulationEnvironment facade instead, both roads are equivalent."

**JSD:** "SimulationEnvironment already has 11 methods — the serialization boundary should be there, not in a temporary bridge."

### Context 3: Goal 10 — AI Dispatcher Routing

**AA:** "AI dispatcher needs clean observation of simulation state. Both roads provide this via SimulationEnvironment facade. The bridge doesn't help or hinder."

**KTL:** "Goal 10 requires Koin DI for injecting ML models. Both roads integrate with Koin equally."

**TSE:** "Neutral. SimulationEnvironment is the observation API regardless of road."

### Context 4: Goal 11 — Track Gradients Physics

**TSE:** "Track gradients add constant acceleration term per section: `a_net = a_motor + a_grade`. With analytical kinematics, this is straightforward. Road 2 could defer the analytical kinematics implementation to Step 2, keeping ODE integration in Step 1."

**RCE:** "If we start Goal 11 before Step 2, Road 2 gives us the option of implementing gradients in the ODE integrator first (familiar code), then converting to analytical in Step 2."

**KJD:** "Or Road 1: implement gradients directly in analytical kinematics. One step, no conversion."

**TSE:** "The real-world implementation schedule matters. If Goal 11 starts after migration completes, both roads are identical. If it starts during migration, Road 2 gives more flexibility."

### Context 5: Goal 14 — Custom Train Types

**RCE:** "Custom train types (acceleration curves, max speed, braking) depend on the Train class API. Which road provides a more stable API during development?"

**KTL:** "Road 2: kDisco API frozen after Step 1. New train types can be developed against a stable API. Road 1: API is in flux during the 4-6 month migration."

**KJD:** "If Goal 14 waits until migration completes, this is irrelevant."

**KTL:** "Not all goals wait. Parallel development is assumed in the 36-month timeline."

### Context 6: Continuous Simulation Postponement

**TSE:** "Road 2 uniquely allows postponing the continuous → analytical migration. Step 1 preserves ODE integration. Step 2 can either convert to analytical OR implement Kalasim's built-in continuous support. Road 1 forces the decision now."

**JSD:** "The pivotal determination from Round 1 was clear: analytical kinematics are sufficient. But 'sufficient' doesn't mean 'mandatory now.' Postponement gives us the option of revisiting if Goal 11 or Goal 12 reveal unexpected needs."

**KJD:** "I keep coming back to YAGNI — You Ain't Gonna Need It. The pivotal determination in Round 1 was definitive, wasn't it? I'm not sure postponement helps."

**AA:** "I agree with KJD's instinct. The technical analysis in Round 1 was thorough. Analytical kinematics are not just sufficient — they're MORE accurate. Postponement adds no value."

**RCE:** "I appreciate the option, even if we don't exercise it."

### Context 7: Future Simulation Library Swap

**AA:** "If Kalasim is abandoned, how hard is a swap to another library? Road 1: sim/ talks directly to Kalasim — another rip-and-replace. Road 2 removed the bridge in Step 3, so it's also direct coupling. Same difficulty."

**KTL:** "The SimulationEnvironment facade (Issue #94) provides the migration seam for future swaps. Neither road adds or removes this seam."

**TSE:** "The bridge being temporary means it doesn't help with future swaps. SimulationEnvironment is the permanent abstraction."

### Context 8: Koin DI Integration

**KTL:** "Both roads need Koin integration for sim/ package (currently excluded per CLAUDE.md). Road 2 Step 1 could introduce Koin to the kDisco bridge module without touching sim/. Road 1 introduces Koin to sim/ during the Kalasim migration."

**AA:** "Road 2 advantage: Koin adoption in sim/ is incremental. Step 1: Koin in bridge only. Step 2: Koin wiring for Kalasim components. Step 3: Koin in sim/ directly."

**KJD:** "Hmm, that's a good point about the incremental Koin adoption. I hadn't considered that — it does make the DI migration less risky."

### Context 9: Issue #83 — Null Safety

**JSD:** "40+ null checks at jDisco API boundaries. kDisco (Kotlin-native, Road 2 Step 1) eliminates these at the bridge level. Road 1 eliminates them by moving to Kalasim directly."

**KTL:** "Both roads eliminate null boundaries. Road 2 does it at Step 1 (kDisco wraps jDisco with non-null API). Road 1 does it at the migration (Kalasim is Kotlin-native)."

### Context 10: Issue #100 — Object Identity

**KTL:** "The wrapper identity crisis (Issue #100) arose from dynamic wrappers around static objects. Both roads eliminate jDisco's identity semantics — Kalasim uses Kotlin's equals()/hashCode() contracts."

**JSD:** "Road 2's kDisco bridge MUST enforce Kotlin identity contracts from Step 1. This is a design requirement, not automatic."

**TSE:** "Agreed. kDisco must be designed with proper equals/hashCode from the start."

### Vote Matrix — Meeting 3

**Vote question:** "Which road better serves this goal/concern?"

| # | Context | TSE | KTL | JSD | KJD | AA | RCE | QA |
|---|---------|-----|-----|-----|-----|----|-----|----|
| 19 | Goal 1: Multi-Train (#316) | R1 | R1 | R1 | R1 | R1 | R1 | R1 |
| 20 | Goal 5: Save/Restore State | Tie | Tie | R2 | Tie | Tie | R2 | Tie |
| 21 | Goal 10: AI Dispatcher | Tie | Tie | Tie | Tie | Tie | Tie | Tie |
| 22 | Goal 11: Track Gradients | R2 | R2 | R2 | Tie | R1 | R2 | R1 |
| 23 | Goal 14: Custom Train Types | R2 | R2 | Tie | Tie | Tie | R2 | Tie |
| 24 | Continuous simulation postponement | R2 | R2 | R2 | Tie | R1 | R2 | Tie |
| 25 | Future library swap | Tie | Tie | Tie | Tie | Tie | Tie | Tie |
| 26 | Koin DI integration | R2 | R2 | R2 | R2 | R2 | R2 | Tie |
| 27 | Issue #83 null safety | Tie | Tie | R2 | R1 | R1 | Tie | Tie |
| 28 | Issue #100 object identity | Tie | Tie | R2 | Tie | R1 | Tie | Tie |

### Meeting 3 Tallies

| Road | Votes | Share |
|------|-------|-------|
| Road 2 (A2→A6→A5) | **21** | **30.0%** |
| Road 1 (A5 Direct) | **13** | **18.6%** |
| Tie/Neutral | **36** | **51.4%** |
| Total | **70** | 100% |

**Per-member breakdown:**

| Member | R1 | R2 | Tie | Total |
|--------|----|----|-----|-------|
| TSE | 1 | 4 | 5 | 10 |
| KTL | 1 | 4 | 5 | 10 |
| JSD | 1 | 6 | 3 | 10 |
| KJD | 2 | 1 | 7 | 10 |
| AA | 5 | 1 | 4 | 10 |
| RCE | 1 | 5 | 4 | 10 |
| QA | 2 | 0 | 8 | 10 |

**Key finding:** Meeting 3 has the highest tie/neutral rate (51.4%) — reflecting that many long-term goals are road-neutral. Goal 1 (Multi-Train) unanimously favors Road 1 because it reaches Kalasim faster. KJD is increasingly neutral, deferring to senior expertise on goal alignment (voted R2 on Koin DI after KTL's argument). QA maintains a balanced stance, voting R1 where faster delivery matters but abstaining on technical trade-offs. The AI Dispatcher (context 21) and future library swap (context 25) are unanimously neutral — SimulationEnvironment facade handles both regardless of road.

---

## 5. Meeting 4: Final Decision & Roadmap

**Date:** 2026-02-08 | **Duration:** 120 minutes | **Led By:** traffic-simulation-expert (TSE, final arbiter per TEAM.md)

### Part 1: Cumulative Vote Analysis

**Total across Meetings 1-3 (28 voting contexts × 7 members = 196 possible votes, 1 abstention):**

| Road | M1 | M2 | M3 | TOTAL | Share |
|------|-----|-----|-----|-------|-------|
| Road 2 (A2→A6→A5) | 24 | 39 | 21 | **84** | **42.9%** |
| Road 1 (A5 Direct) | 14 | 13 | 13 | **40** | **20.4%** |
| Tie/Neutral | 18 | 17 | 36 | **71** | **36.2%** |
| Abstention | 0 | 1 | 0 | **1** | **0.5%** |

**Excluding ties and abstention (124 decisive votes):**

| Road | Votes | Share |
|------|-------|-------|
| Road 2 (A2→A6→A5) | **84** | **67.7%** |
| Road 1 (A5 Direct) | **40** | **32.3%** |

### Part 2: Per-Member Voting Evolution

| Member | M1 Lean | M2 Lean | M3 Lean | Overall | Trajectory |
|--------|---------|---------|---------|---------|------------|
| TSE | R2 (4-2) | R2 (8-0) | R2 (4-1) | **R2 (16-3)** | Consistent R2 with nuance |
| KTL | R2 (4-2) | R2 (9-0) | R2 (4-1) | **R2 (17-3)** | Strongest R2 advocate |
| JSD | R2 (7-0) | R2 (8-1) | R2 (6-1) | **R2 (21-2)** | Most consistent R2, conservative |
| KJD | R1 (4-1) | R1 (5-1) | Tie (2-1) | **R1 (11-3)** | Junior learns from discussion, increasingly neutral |
| AA | R1 (5-0) | R1 (6-1) | R1 (5-1) | **R1 (16-2)** | Consistent R1, occasional R2 |
| RCE | R2 (6-0) | R2 (8-0) | R2 (5-1) | **R2 (19-1)** | Near-unanimous R2 |
| QA | R2 (2-1) | R2 (4-1) | R1 (2-0) | **R2 (6-4)** | Neutral, leans context-dependent |

**Factional analysis:**
- **Road 2 bloc (4 members):** TSE, KTL, JSD, RCE — ranging from moderate (TSE: 16-3) to strong (JSD: 21-2, RCE: 19-1)
- **Road 1 bloc (1 member):** AA (16-2, consistent)
- **Evolving positions (2 members):** KJD (11-3, junior shifting from R1 toward neutral), QA (6-4, nearly balanced, context-dependent)

### Part 3: Final Arguments

**TSE (Road 2):** "The coupling point analysis in Meeting 1 convinced me that phased migration de-risks the hardest parts. The Motor class, position-based events, and Variable state management are all better handled incrementally. Meeting 2 sealed it — the sim/ conservative restriction is not just a guideline, it's a project principle. Road 2 respects it; Road 1 violates it. Goal 1 arriving 2 months later is acceptable given the reduced risk."

**KTL (Road 2):** "As technical architect, I see Road 2's bridge as tested infrastructure, not overhead. Step 1 validates the bridge against jDisco golden output — if it passes, we know the abstraction is correct. Step 2 swaps the backend behind a tested API. Step 3 removes the bridge. Each step has a clear success criterion. Road 1 has one success criterion: 'everything works after rewriting 13 files.'"

**JSD (Road 2):** "I have been the most conservative voice throughout Rounds 1 and 2. Road 2 aligns with the project's conservative DNA. The bridge carries the 2007 API patterns through the transition. The historical validation is preserved at every step. I can verify regression-free behavior at three checkpoints instead of one."

**KJD (Road 1, with reservations):** "I've been listening to the senior arguments and I understand the risk reduction case for Road 2. But I still think YAGNI applies — 800 lines of bridge code that gets deleted feels wasteful. Road 1 writes ~500 lines of Kalasim integration code that STAYS. I've personally worked on the Java→Kotlin migration and the context refactoring, and we handled those well. I believe we can handle a 13-file migration in one step. But I acknowledge the coupling point analysis changed my mind on some contexts — especially API stability and Koin integration where Road 2 genuinely helps."

**AA (Road 1):** "YAGNI is the principle at stake. The bridge is temporary by design. Building temporary infrastructure has real costs: design, implementation, testing, documentation, and then removal. SimulationEnvironment facade already provides the migration seam. kDisco bridge is a second, redundant seam that adds complexity without permanent value."

**RCE (Road 2):** "In railway engineering, we follow a principle: never make two changes at the same time. If something goes wrong, you can't tell which change caused it. Road 2 makes one change at a time. Road 1 makes all changes at once. This is not theoretical — Issue #100, #275, #280 all resulted from concurrent changes near the sim/jDisco boundary."

**QA (Road 2, narrowly):** "I've been neutral on most contexts because the quality assurance effort is significant for both roads. But looking at the overall picture, three validation windows beat one. Road 2 gives me: Step 1 (bridge matches jDisco), Step 2 (Kalasim matches bridge), Step 3 (direct matches bridge-removed). That said, I voted Road 1 on time-to-milestone and track gradients because faster delivery has real value too. My final vote is Road 2, but it's close."

### Part 4: Final Ranked Vote

| Member | 1st Choice | Justification | 2nd Choice |
|--------|-----------|---------------|-----------|
| **TSE** | **Road 2** | Phased de-risking of coupling points, respects sim/ conservative restriction, 2-month delay acceptable | Road 1 |
| **KTL** | **Road 2** | Bridge provides tested abstraction, three validation checkpoints, incremental breaking changes | Road 1 |
| **JSD** | **Road 2** | Conservative approach, historical validation at every step, 2007 API patterns preserved through transition | Road 1 |
| **KJD** | **Road 1** | YAGNI, but acknowledges Road 2 has merit on API stability and Koin; junior defers to team experience | Road 2 |
| **AA** | **Road 1** | SimulationEnvironment is sufficient seam, bridge is redundant abstraction, lower total development effort | Road 2 |
| **RCE** | **Road 2** | Railway engineering principle: one change at a time, isolation of failure modes, three-step validation | Road 1 |
| **QA** | **Road 2** | Three validation windows narrowly outweigh faster delivery; golden output checkpoints at every step | Road 1 |

**Result: 5-2 for Road 2 (A2→A6→A5 phased migration)**

### Decision Announcement

**TSE:** "The vote is 5-2 in favor of Road 2. Five members — myself, KTL, JSD, RCE, and QA — select Road 2 as their first choice. Two members — KJD and AA — select Road 1. I note that KJD's position evolved significantly during the meetings — starting as a strong Road 1 advocate and moving toward a more nuanced view, which is exactly what a healthy team discussion should produce. QA's vote was narrow, reflecting genuine balance in the quality assurance perspective. The minority arguments are technically sound (YAGNI, lower total effort, faster Goal 1). I acknowledge these as legitimate trade-offs, not as errors in judgment.

I announce the FINAL DECISION: interlockSim will migrate from jDisco to Kalasim using the **A2→A6→A5 phased migration road**. The kDisco bridge is temporary scaffolding that MUST be removed by Step 3 completion. The end state is identical to Road 1 — sim/ talks directly to Kalasim.

This decision REFINES Round 1 — it does not replace it. Kalasim is confirmed. The binding conditions from Round 1 remain in force. The following additional binding conditions apply to the phased road."

### Part 5: Binding Conditions (Round 2 Specific)

1. **Bridge removal mandatory:** kDisco bridge MUST be removed by end of Step 3. No permanent abstraction layer. The end state is A5 (Kalasim direct).

2. **Step 1 gate — Dual-mode validation:**
   - kDisco-over-jDisco MUST produce identical golden output to jDisco alone
   - Position: ±1e-6m, time: ±1e-9s (same tolerances as PR #367)
   - All 1840 tests pass against kDisco-over-jDisco
   - Zero sim/ file changes in Step 1

3. **Step 2 gate — Kalasim backend validation:**
   - kDisco-over-Kalasim passes all 1840 tests
   - Golden output within acceptable tolerances (position: ±0.1m, velocity: ±0.01m/s — relaxed for backend swap)
   - 50-train scalability test (from Round 1 PoC gate)
   - If Step 2 fails: revert to kDisco-over-jDisco, evaluate DSOL fallback

4. **Bridge API frozen:** After Step 1 approval, kDisco API accepts NO new features. Only bug fixes and backend swaps permitted.

5. **Timeline ceiling:** 18 months total. Step 1: ≤4 months. Step 2: ≤8 months. Step 3: ≤6 months. Exceeding any step triggers review.

6. **Round 1 conditions remain in force:**
   - SimulationEnvironment facade (Issue #94) is mandatory architectural boundary
   - Kalasim imports MUST NOT leak beyond sim/ package (and kDisco module during Steps 1-2)
   - DSOL (A3) is the documented fallback if Kalasim proves unviable

### Part 6: Implementation Roadmap

#### Step 1: kDisco Bridge over jDisco (Months 1-4)

| Task | Owner | Duration |
|------|-------|----------|
| Create kDisco module (Kotlin, matching jDisco API) | KTL | 4 weeks |
| Implement kDisco.Process wrapping jDisco.Process | KTL + KJD | 2 weeks |
| Implement kDisco.Variable/Continuous/Condition | KTL + KJD | 2 weeks |
| Implement kDisco.Head/Link/Random wrappers | KJD | 1 week |
| Dual-mode test runner (compare jDisco vs kDisco output) | QA + KTL | 2 weeks |
| Validate golden output identical | QA | 1 week |
| Step 1 gate review | TSE | 1 day |

**Step 1 deliverable:** kDisco module wrapping jDisco with identical API. Zero sim/ changes. Golden output identical.

**Step 1 estimated size:** ~800 lines Kotlin (wrappers + tests).

**Note:** kDisco is already in incubation at https://github.com/bedaHovorka/kdisco/

#### Step 2: Swap jDisco Backend for Kalasim (Months 5-12)

| Task | Owner | Duration |
|------|-------|----------|
| Add Kalasim dependency | KTL | 1 day |
| Implement kDisco.Process backed by Kalasim Component | TSE + KTL | 6 weeks |
| Implement kDisco.Variable backed by Kalasim State | KTL + KJD | 3 weeks |
| Implement analytical kinematics (replace Continuous/SimpleIntegration) | TSE | 4 weeks |
| Replace kDisco.Head/Link with Kalasim ComponentQueue | KJD | 1 week |
| Replace kDisco.Random with Kalasim random | KJD | 1 day |
| Swap sim/ imports: jDisco.* → kDisco.* | KJD | 1 week |
| 50-train scalability test | TSE + QA | 2 weeks |
| Golden output validation (relaxed tolerances) | QA | 2 weeks |
| Step 2 gate review | TSE | 1 day |

**Step 2 deliverable:** kDisco module backed by Kalasim. sim/ code uses kDisco API (mechanical import swap). 50+ train scalability validated.

#### Step 3: Remove Bridge, Direct Kalasim Integration (Months 13-18)

| Task | Owner | Duration |
|------|-------|----------|
| Inline kDisco wrappers into sim/ code | KTL + KJD | 4 weeks |
| Replace kDisco.Process references with Kalasim Component | KTL | 3 weeks |
| Replace kDisco.Variable/State references with Kalasim State | KJD | 2 weeks |
| Remove kDisco module entirely | KTL | 1 day |
| Validate golden output (tight tolerances) | QA | 2 weeks |
| Update all documentation | KJD | 1 week |
| SonarQube clean run | KTL | 1 week |
| Final review and merge to main | TSE | 1 week |

**Step 3 deliverable:** kDisco module deleted. sim/ talks directly to Kalasim. End state identical to A5.

#### Timeline

```
Month:  1    2    3    4    5    6    7    8    9   10   11   12   13   14   15   16   17   18
Step:   |---- Step 1: kDisco Bridge ---|-------- Step 2: Kalasim Backend --------|--- Step 3: Bridge Removal ---|
Gate:                             S1-GATE                                  S2-GATE                        S3-DONE
Zero sim/ changes                      Import swap + backend                      Bridge removed
```

---

## 6. Consolidated Vote Matrix

### All 28 Voting Contexts, All 7 Members

| # | Context | TSE | KTL | JSD | KJD | AA | RCE | QA |
|---|---------|-----|-----|-----|-----|----|-----|----|
| **Meeting 1: Continuous↔Discrete Coupling** |
| 1 | Motor acceleration control | R2 | R2 | R2 | Tie | Tie | R2 | Tie |
| 2 | Position-based event triggering | R2 | R2 | R2 | R1 | R1 | R2 | R2 |
| 3 | Signal state conditions | Tie | Tie | Tie | Tie | Tie | Tie | Tie |
| 4 | Train completion sync | R2 | R1 | R2 | R1 | R1 | R2 | R1 |
| 5 | Passivation vs continuous (#291) | R1 | R1 | R2 | R1 | R1 | R2 | Tie |
| 6 | SimpleIntegration (postpone/now?) | R2 | R2 | R2 | R2 | R1 | R2 | Tie |
| 7 | ContinuousInvariantChecker | R1 | Tie | R2 | Tie | Tie | Tie | Tie |
| 8 | Variable state management (6 instances) | Tie | R2 | R2 | R1 | R1 | R2 | R2 |
| **Meeting 2: Breaking Changes & Migration Safety** |
| 9 | sim/ conservative restriction (CLAUDE.md) | R2 | R2 | R2 | R1 | R1 | R2 | Tie |
| 10 | sim/ package changes per step | R2 | R2 | R2 | R1 | R1 | R2 | Tie |
| 11 | Test breakage per step | R2 | R2 | R2 | Tie | Tie | R2 | R2 |
| 12 | Rollback safety | R2 | R2 | R2 | Tie | R1 | R2 | R2 |
| 13 | Golden output preservation | R2 | R2 | R2 | Tie | Tie | R2 | R2 |
| 14 | Dual-mode validation | R2 | R2 | R2 | R1 | R1 | R2 | Tie |
| 15 | API stability during migration | R2 | R2 | R2 | R2 | R2 | R2 | R2 |
| 16 | Time to first milestone | R2 | R2 | R1 | R1 | R1 | R2 | R1 |
| 17 | Static Process.time()/activate() | Tie | R2 | R2 | R1 | R1 | -- | Tie |
| 18 | DiscoException handling | Tie | Tie | Tie | Tie | Tie | Tie | Tie |
| **Meeting 3: Long-Term Goals & Future Extensibility** |
| 19 | Goal 1: Multi-Train (#316) | R1 | R1 | R1 | R1 | R1 | R1 | R1 |
| 20 | Goal 5: Save/Restore State | Tie | Tie | R2 | Tie | Tie | R2 | Tie |
| 21 | Goal 10: AI Dispatcher | Tie | Tie | Tie | Tie | Tie | Tie | Tie |
| 22 | Goal 11: Track Gradients | R2 | R2 | R2 | Tie | R1 | R2 | R1 |
| 23 | Goal 14: Custom Train Types | R2 | R2 | Tie | Tie | Tie | R2 | Tie |
| 24 | Continuous simulation postponement | R2 | R2 | R2 | Tie | R1 | R2 | Tie |
| 25 | Future library swap | Tie | Tie | Tie | Tie | Tie | Tie | Tie |
| 26 | Koin DI integration | R2 | R2 | R2 | R2 | R2 | R2 | Tie |
| 27 | Issue #83 null safety | Tie | Tie | R2 | R1 | R1 | Tie | Tie |
| 28 | Issue #100 object identity | Tie | Tie | R2 | Tie | R1 | Tie | Tie |

---

## 7. Per-Member Totals

### Across All Meetings (28 Contexts)

| Member | Road 1 | Road 2 | Tie | Total Votes | Primary |
|--------|--------|--------|-----|-------------|---------|
| TSE | 3 | 16 | 9 | 28 | **Road 2** |
| KTL | 3 | 17 | 8 | 28 | **Road 2** |
| JSD | 2 | 21 | 5 | 28 | **Road 2** |
| KJD | 11 | 3 | 14 | 28 | **Road 1** (evolving) |
| AA | 16 | 2 | 10 | 28 | **Road 1** |
| RCE | 1 | 19 | 7 | 27 | **Road 2** |
| QA | 4 | 6 | 18 | 28 | **Neutral** (leans R2) |

**Note:** RCE has 27 votes (1 abstention on context 17, shown as -- in matrix). QA's near-balanced position (6 R2 vs 4 R1) reflects quality assurance perspective that both roads have merit.

### Verification Totals

**Counted from consolidated matrix (Section 6):**

| Category | Count |
|----------|-------|
| R1 votes | **40** |
| R2 votes | **84** |
| Tie votes | **71** |
| Abstentions (--) | **1** |
| **Total** | **196** |

**Cross-check per meeting:**

| Meeting | R1 | R2 | Tie | Abstain | Total |
|---------|----|----|-----|---------|-------|
| M1 (8 contexts × 7) | 14 | 24 | 18 | 0 | 56 |
| M2 (10 contexts × 7) | 13 | 39 | 17 | 1 | 70 |
| M3 (10 contexts × 7) | 13 | 21 | 36 | 0 | 70 |
| **Total** | **40** | **84** | **71** | **1** | **196** |

**Per-member column sums verification:**
- R1: 3+3+2+11+16+1+4 = 40 ✓
- R2: 16+17+21+3+2+19+6 = 84 ✓
- Tie: 9+8+5+14+10+7+18 = 71 ✓

**Decisive votes only (excluding ties and abstentions):**

| Road | Votes | Share |
|------|-------|-------|
| Road 2 (A2→A6→A5) | **84** | **67.7%** |
| Road 1 (A5 Direct) | **40** | **32.3%** |
| **Total decisive** | **124** | 100% |

**Final ranked vote: 5-2 for Road 2**

---

## 8. Appendices

### A. Relationship to Round 1

This document REFINES — does not replace — the Round 1 decision ([SIMULATION_LIBRARY_DECISION.md](SIMULATION_LIBRARY_DECISION.md)).

| Aspect | Round 1 Decision | Round 2 Refinement |
|--------|-----------------|-------------------|
| Destination | Kalasim (A5) — 7-0 unanimous | Confirmed, unchanged |
| Migration road | Implied direct (A5) | Refined to phased (A2→A6→A5) — 5-2 |
| End state | sim/ talks to Kalasim directly | Identical — kDisco bridge removed at Step 3 |
| PoC gate | 50-train scalability test | Incorporated into Step 2 gate |
| SimulationEnvironment facade | Mandatory boundary | Unchanged |
| Import containment | Kalasim in sim/ only | Extended: kDisco in sim/ and kDisco module during Steps 1-2 |
| DSOL fallback | Documented | Unchanged |
| Timeline | 15 months | Extended to 18 months (phased overhead) |

### B. Cost of Phased Road vs Direct Road

| Cost Category | Road 1 (Direct) | Road 2 (Phased) | Delta |
|---------------|-----------------|-----------------|-------|
| Bridge code | 0 lines | ~800 lines (written then deleted) | +800 lines temporary |
| Timeline | 15 months | 18 months | +3 months |
| Validation passes | 1 | 3 | +2 validation cycles |
| Rollback granularity | 1 (all or nothing) | 3 (per step) | +2 rollback points |
| sim/ changes in first step | 13 files | 0 files | -13 files initial risk |

### C. KJD and AA Dissenting Position

KJD and AA voted Road 1, though with different conviction levels:

**AA (consistent R1, 16-2):** Strongest Road 1 advocate. Arguments:
1. **YAGNI:** ~800 lines of bridge code are written and then deleted. Zero permanent value.
2. **SimulationEnvironment sufficiency:** The existing facade (Issue #94, 11 methods) already provides the migration seam. kDisco bridge is a redundant second seam.
3. **Lower total development effort:** Road 1 has fewer total file-touches and shorter timeline.

**KJD (evolving R1, 11-3 with 14 ties):** Started as strong Road 1 advocate but shifted toward neutral during meetings. As a junior developer, KJD was influenced by senior team arguments on coupling point complexity and API stability. Key observations:
1. **Proven team capability:** KJD participated in Java→Kotlin migration and context refactoring, building confidence in the team's migration skills.
2. **Goal 1 urgency:** Issue #316 (8+ train deadlock) blocks 5 downstream goals. Road 2 delays Kalasim by ~2 months.
3. **Growing appreciation:** Voted R2 on API stability (context 15), Koin DI integration (context 26), and SimpleIntegration postponement (context 6) after senior arguments.

These arguments are recorded as legitimate positions. KJD's evolution demonstrates healthy team dynamics where junior members learn from structured technical discussion.

### D. Key Technical Files Referenced

| File | Lines | Coupling | Meeting |
|------|-------|----------|---------|
| `sim/Train.kt` | 1024 | 5 jDisco imports, 24 process-interaction calls, Motor inner class | M1, M2, M3 |
| `sim/SimpleIntegration.kt` | 30 | Extends Continuous, integrates velocity→position | M1 |
| `sim/ContinuousInvariantChecker.kt` | 50 | Extends Continuous, validates train length | M1 |
| `sim/LoopProcess.kt` | 55 | Extends Process, abstract passivate/activate loop | M1, M2 |
| `sim/InOutWorker.kt` | 172 | Head/Link queue, Condition, waitUntil | M2 |
| `sim/ShuntingLoop.kt` | 341 | Scenario orchestration, hold() calls | M2 |
| `sim/Generator.kt` | 80 | jDisco Random, hold() for timing | M2 |
| `context/SimulationEnvironment.kt` | 11 methods | Migration seam | M2, M3 |
| `context/DefaultSimulationContext.kt` | 1531 | DiscoException catch, Process.activate() | M2 |
| `objects/tracks/DynamicTrackBlock.kt` | 434 | Process.time() in logging | M2 |
| `objects/tracks/DynamicTrack.kt` | 350 | Process.time() in logging | M2 |

### E. jDisco API Surface to Bridge

| jDisco Type | Usages | kDisco Wrapper Complexity |
|-------------|--------|--------------------------|
| Process | 4 extensions (Train, Site, LoopProcess, Motor) | HIGH — core lifecycle |
| Continuous | 3 extensions (SimpleIntegration, ContinuousInvariantChecker, Motor) | HIGH — ODE integration |
| Variable | 6 instances (position×2, velocity, acceleration, plus SimpleIntegration I/O) | MEDIUM — state wrapper |
| Condition | 5+ instances (terminated, allowingSignal, AccelerationStopCondition) | MEDIUM — boolean predicate |
| Reporter | 1 instance (Train.r) | LOW — logging wrapper |
| Random | 2 usages (Generator, DefaultSimulationContext) | LOW — Kotlin Random |
| Head/Link | 1 usage (InOutWorker) | LOW — Kotlin ArrayDeque |
| DiscoException | 1 catch (DefaultSimulationContext) | LOW — exception wrapper |
| Process.time() | 8+ calls (logging timestamps) | LOW — property delegation |
| Process.activate() | 4+ calls (process wakeup) | MEDIUM — static method |

### F. Decision Authority Record

Per TEAM.md, traffic-simulation-expert (TSE) has autonomous decision authority within LONG_TERM_GOALS.md and CLAUDE.md constraints, and serves as arbiter for disputes.

The 5-2 vote was ratified by TSE as a consensus-based decision. TSE's personal vote (Road 2) aligned with the majority. Had TSE voted differently from the majority, the arbiter authority would have required explicit justification per TEAM.md.

---

**Document Version:** 1.0
**Created:** 2026-02-08
**Predecessor:** [SIMULATION_LIBRARY_DECISION.md](SIMULATION_LIBRARY_DECISION.md) (Round 1)
**Approved By:** traffic-simulation-expert (decision authority)
**Next Review:** After Step 1 gate completion
