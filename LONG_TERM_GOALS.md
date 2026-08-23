# LONG_TERM_GOALS.md - InterlockSim Long-Term Development Goals

## Overview

This document defines 20 prioritized functional goals for extending the InterlockSim railway interlocking simulator. These goals were developed through a structured 5-meeting planning process involving domain experts in traffic simulation, AI/agent systems, Kotlin development, and railway engineering.

**Document Purpose:**
- Guide long-term development priorities
- Provide cost estimates for planning
- Define success criteria for each goal
- Map dependencies between goals
- Establish implementation phases

**Target Users:**
- Educators teaching railway interlocking concepts
- Students learning simulation and railway operations
- Researchers analyzing railway capacity and operations
- Railway engineers testing interlocking designs

**Planning Team:**
- traffic-simulation-expert (Meeting Leader)
- agent-architect (AI/Agent Specialist)
- kotlin-tech-lead (Technical Lead)
- railway-engineer (Domain Expert)

**Document Created:** 2026-01-09
**Planning Process:** 5 meetings, 170+ initial ideas, reduced to 20 final goals

---

## Goal Stages

A goal can be delivered in **stages**. A stage of one goal can depend on a stage of another goal.

- The **first stage carries no letter**. It is the simplest solution that does the job.
- **Later stages carry a letter, from B upwards.** Each one extends the stage before it.
- Later stages are **defined ad hoc**, once the real difficulty of the previous stage is known.
  This document therefore names a later stage only after somebody decided it is needed.

**Next planned after Goal 10 (stage A):**

| Order | Stage | Why now |
|---|---|---|
| 1 | **Goal 1B** — highest priority | Closes Goal 1's unverified scale bar; everything else needs it |
| 2 | **Goal 9B** | Specified *during* Goal 1B, built after it, from Goal 1B's findings |
| 3 | **Goal 12** | New goal — inter-station tracks and several dispatcher agents |
| 4 | **Goal 3B** | Collision detection on a separate thread, feeding the dispatcher |

## Goals Summary Table

**Re-scoped 2026-08-23:** Goal 10 stage A is **complete**, so the 2026-07-01 critical path is
closed. **Goal 1B is now the top-priority work**, followed by Goal 9B, the new Goal 12, and
Goal 3B. Goal 11 is redefined (the old Goal 12, curved track, is folded into it) and dropped to
**Very Low** priority. The number 12 is reused for a **new goal**: inter-station tracks and
several dispatcher agents. See "Goal Stages" above and "Implementation Strategy" below.

**Priority re-scoped 2026-07-01 (historical):** Goal 10 (AI Dispatcher Routing) was made the top-priority goal. Only Goal 10's transitive dependency chain — Goal 1 → Goal 2 → Goal 3 → Goal 9 → Goal 10 — remains **Critical**. Every other goal, including the previously-Critical Goals 4, 5, 6, 7, 8, is re-scoped to **High**. This also reconciles the doc with the dependency graph established in the GitHub issue tracker (#610, #531, #532), which added **Goal 3 as a direct prerequisite of both Goal 9 and Goal 10** — a link not present in the original 2026-01-09 planning. See "Implementation Strategy" and "Risk Assessment" below for the resulting re-plan.

| ID | Stage | Title | Category | Priority | Status | Estimate |
|----|-------|-------|----------|----------|----------|----------|
| 1 | — | Multi-Train Simulation | E: Advanced Simulation | Critical | ✅ Complete | 6 months |
| 1 | **B** | Scale and Routing Test Scenarios | E: Advanced Simulation | **Critical (next)** | 🆕 Open (#591) | 4 months |
| 2 | — | Automatic Path Finding | E: Advanced Simulation | Critical | ✅ Complete | 3 months |
| 3 | — | Collision Detection and Warning | J: Safety & Compliance | Critical | ✅ Complete | 2 months |
| 3 | **B** | Snapshot Collision Detection on a Separate Thread | J: Safety & Compliance | High | 🆕 Planned | 2 months |
| 4 | — | Interlocking Validation and Generation | J: Safety & Compliance | High | 🆕 Open (#660) | 4 months |
| 5 | — | Save and Restore Simulation State | I: System Operations | High | 🆕 Open (#666) | 3 months |
| 6 | — | Performance Metrics Collection | F: Research & Analysis | High | 🆕 Open (#659) | 2 months |
| 7 | — | Simulation Speed Control | I: System Operations | High | ✅ Complete | 1 month |
| 8 | — | Pause and Single-Step Simulation | I: System Operations | High | ✅ Complete | 1 month |
| 9 | — | Automatic Conflict Detection and Resolution | A: Intelligent Automation | Critical | ✅ Complete | 4 months |
| 9 | **B** | Realistic Conflict Resolution and Command Threading | A: Intelligent Automation | **High (next)** | 🆕 Planned | 4 months |
| 10 | **A** | AI Dispatcher Routing | A: Intelligent Automation | **Critical** | ✅ Complete (#532) | 6 months |
| 11 | — | Track Physics: Gradients and Curves *(redefined — old Goal 12 folded in)* | E: Advanced Simulation | **Very Low** | 🆕 Open (#664, #665) | 3 months |
| 12 | — | Inter-station Tracks and Multiple Dispatcher Agents *(new goal)* | A: Intelligent Automation | High | 🆕 Open (#821) | 5 months |
| 13 | — | Graphical Timetable Visualization | F: Research & Analysis | High | 🆕 Open (#661) | 3 months |
| 14 | — | Custom Train Types | E: Advanced Simulation | High | 🆕 Open (#667) | 2 months |
| 15 | — | Railway Interlocking Tutorials | C: Educational | High | 🆕 Open (#668) | 3 months |
| 16 | — | Signal Explanation Mode | C: Educational | High | 🆕 Open (#669) | 2 months |
| 17 | — | CSV/Excel Export | D: Data Integration | High | 🆕 Open (#662) | 1 month |
| 18 | — | Visual Train Timeline | F: Research & Analysis | High | 🆕 Open (#663) | 3 months |
| 19 | — | Czech Timetable Import | D: Data Integration | High | 🆕 Open (#670) | 4 months |
| 20 | — | Comprehensive Accessibility | B: User Experience | Medium | 🆕 Open (#671) | 3 months |

**Total Development Estimate: 71 months (23 months complete — Goals 1, 2, 3, 7, 8, 9, and Goal 10 stage A; 48 months remaining, including the newly named stages 1B, 9B, 3B and the new Goal 12)**

---

## Detailed Goal Descriptions

### Goal 1: Multi-Train Simulation

**Category:** E: Advanced Simulation
**Priority:** Critical
**Development Estimate:** 6 months
**Status:** ✅ COMPLETE

**User Value:**
Users can simulate realistic railway scenarios with multiple trains operating simultaneously on a shared track network. This enables analysis of complex traffic patterns, junction utilization, and conflict scenarios that are impossible with single-train simulation.

**Success Criteria:**
- 5+ trains running simultaneously without performance degradation
- Trains correctly share track infrastructure with proper block occupancy
- No race conditions or event ordering issues in simulation
- Performance remains acceptable up to 20 concurrent trains

**Dependencies:** None (foundation goal)

**Implementation Notes:**
- Requires kDisco scheduler modifications for concurrent entity management
- Consider prototype with 3-train scenario first
- High risk - may require DSOL migration if kDisco proves limiting
- Foundation for Goals 3, 9, and 10

---

### Goal 2: Automatic Path Finding

**Category:** E: Advanced Simulation
**Priority:** Critical
**Development Estimate:** 3 months
**Status:** ✅ COMPLETE

**User Value:**
Users can request automatic route calculation from any entry point to any exit point in the railway network. The system finds optimal paths considering track topology, switch positions, and current network state, eliminating manual route specification.

**Success Criteria:**
- Dijkstra-based routing algorithm finds shortest valid path
- Multiple alternative routes displayed when available
- Path calculation completes in under 1 second for networks up to 100 track elements
- Correctly handles switch positions and track directions

**Dependencies:** None (foundation goal)

**Implementation Notes:**
- Build on existing track topology graph structure
- Consider weighted paths (by distance, time, or preference)
- Foundation for Goals 4, 9, and 10

---

### Goal 3: Collision Detection and Warning

**Category:** J: Safety & Compliance
**Priority:** Critical
**Development Estimate:** 2 months
**Status:** ✅ COMPLETE

**User Value:**
Users receive immediate warnings when trains are on a collision course, enabling them to take corrective action before accidents occur. This provides a safety net during simulation and helps identify flaws in interlocking configurations.

**Success Criteria:**
- Immediate visual and optional audio warning when collision risk detected
- Detection occurs with sufficient advance time for intervention
- Option to automatically pause simulation on collision warning
- Collision prevention mode that automatically halts endangering train

**Dependencies:** Goal 1 (multi-train simulation)

**Implementation Notes:**
- Extend event system to detect track block conflicts
- Consider time-to-collision calculation for advance warning
- Foundation for Goal 4 (interlocking validation)

---

### Goal 4: Interlocking Validation and Generation

**Category:** J: Safety & Compliance
**Priority:** High *(re-scoped 2026-07-01 from Critical — not on Goal 10's dependency chain; tracked in #660)*
**Development Estimate:** 4 months

**User Value:**
Users can automatically validate that their interlocking configuration prevents all possible collisions, and optionally generate valid interlocking tables from track layouts. This ensures safety compliance and reduces manual design effort.

**Success Criteria:**
- Automatic generation of interlocking table from track layout
- Validation of user-provided interlocking tables against safety rules
- Clear reporting of any safety violations found
- Verification that no route conflicts are possible under any switch configuration

**Dependencies:** Goal 2 (pathfinding), Goal 3 (collision detection)

**Implementation Notes:**
- Requires formal railway safety rule knowledge
- Consider external railway signaling specialist consultation
- May benefit from formal verification approaches

---

### Goal 5: Save and Restore Simulation State

**Category:** I: System Operations
**Priority:** High *(re-scoped 2026-07-01 from Critical — not on Goal 10's dependency chain; tracked in #666)*
**Development Estimate:** 3 months

**User Value:**
Users can save the complete state of a running simulation at any point and restore it later to continue from that exact moment. This enables debugging, educational checkpoints, and analysis of specific scenarios without re-running entire simulations.

**Success Criteria:**
- Complete serialization of all simulation state (trains, signals, time, events)
- Exact restoration to saved state with identical future behavior
- Multiple save slots supported
- Save/load completes in under 5 seconds for typical scenarios

**Dependencies:** Goal 7 (speed control), Goal 8 (pause)

**Implementation Notes:**
- Requires serialization of kDisco process state
- Consider versioning for save file compatibility
- Foundation for Goals 15 and 18

---

### Goal 6: Performance Metrics Collection

**Category:** F: Research & Analysis
**Priority:** High *(re-scoped 2026-07-01 from Critical — not on Goal 10's dependency chain; tracked in #659)*
**Development Estimate:** 2 months

**User Value:**
Users can automatically collect key performance indicators during simulation including delays, conflicts, throughput, and utilization. This provides quantitative data for research, capacity analysis, and optimization studies.

**Success Criteria:**
- Real-time dashboard showing current KPIs
- Historical data collection throughout simulation run
- Configurable metrics selection
- Data export capability for external analysis

**Dependencies:** None (can implement early)

**Implementation Notes:**
- Build statistics collection framework
- Consider integration with existing kDisco statistics capabilities
- Foundation for Goals 13, 17, and 18

---

### Goal 7: Simulation Speed Control

**Category:** I: System Operations
**Priority:** High *(re-scoped 2026-07-01 from Critical — not on Goal 10's dependency chain; already complete)*
**Development Estimate:** 1 month
**Status:** ✅ COMPLETE

**User Value:**
Users can adjust simulation speed from slow motion (for detailed observation) to fast forward (for quick scenario completion). This provides flexibility for different use cases: slow for education, fast for research batch runs.

**Success Criteria:**
- Speed range from 0.1x (slow motion) to 100x (fast forward)
- Smooth speed adjustment during runtime without artifacts
- Visual indicator of current speed setting
- Keyboard shortcuts for common speed adjustments

**Dependencies:** None (quick win)

**Implementation Notes:**
- The simulation library interface used by the model (historically jDisco, now kDisco/KMP) runs in pure simulation time and has no native wall-clock speed control or synchronization.
- Speed control for `ShuntingLoop` is implemented via the `RealTimeSynch` inner process inside `sim/ShuntingLoop.kt` (enabled by `enableRealTimeSync`, paced by `speedMultiplier`). This resides in the `sim/` package.
- `SimulationRunner` provides a complementary external throttling API (`throttle()`, `awaitIfPaused()`) callable from the simulation thread; this is the designed extension point for future simulation processes that delegate pacing outside the `sim/` package.
- Quick win - implement early for immediate value

---

### Goal 8: Pause and Single-Step Simulation

**Category:** I: System Operations
**Priority:** High *(re-scoped 2026-07-01 from Critical — not on Goal 10's dependency chain; already complete)*
**Development Estimate:** 1 month
**Status:** ✅ COMPLETE

**User Value:**
Users can pause the simulation at any moment and advance it one event at a time for detailed analysis. This is essential for debugging simulation behavior, educational demonstrations, and understanding discrete-event dynamics.

**Success Criteria:**
- Pause at any time with immediate effect
- Single-step by event (advance to next scheduled event)
- Single-step by time increment (advance by configurable time delta)
- Clear indication of paused state and next pending event

**Dependencies:** Goal 7 (speed control)

**Implementation Notes:**
- kDisco supports pause; add single-step capability
- Quick win - implement early for immediate value

---

### Goal 9: Automatic Conflict Detection and Resolution

**Category:** A: Intelligent Automation
**Priority:** Critical *(re-scoped 2026-07-01 from High — direct dependency of Goal 10; superseded by Goal 9B, see "Planned Next Stages")*
**Development Estimate:** 4 months
**Status:** ✅ COMPLETE

**User Value:**
Users receive automatic detection of routing conflicts when multiple trains request the same track resources, with suggested resolution options. This assists manual dispatching and forms the foundation for automated dispatch.

**Success Criteria:**
- Detect conflicts before they cause operational issues
- Provide multiple resolution suggestions ranked by impact
- Allow user selection of preferred resolution
- Learn from user choices to improve future suggestions

**Dependencies:** Goal 1 (multi-train, ✅ complete), Goal 2 (pathfinding, ✅ complete), Goal 3 (collision detection — added 2026-07-01, per detailed design in #531: conflict resolution consumes `CollisionWarning` events from Goal 3 SP2/SP3)

**Implementation Notes:**
- Requires conflict detection logic beyond collision detection
- Consider temporal conflicts (future resource contention)
- Foundation for Goal 10 (AI dispatcher) — now the critical link on the path to the project's top-priority goal

**Completion notes (2026-07-06):**
- SP1–SP7 delivered: `ConflictDetectedEvent` (spatial, at reservation time),
  `TemporalConflictDetector` (predictive lookahead, projection-provider seam),
  `ConflictResolution` model + `DefaultConflictResolver` candidate generation,
  `ConflictResolutionRanker` (with preference-weighted overload),
  `AutoConflictResolutionService` (headless picker, Goal 10 wiring point),
  `StrategyPreferenceStore` + `DispatcherPreferenceStore` (preference learning),
  and the `ConflictResolutionPanel` operator-selection UI wired into `Frame`.
- SC3 + SC4 are wired end-to-end: the operator's **Apply** choice is recorded in the
  scoped `DispatcherPreferenceStore` (source = `OPERATOR`) and feeds
  `StrategyPreferenceStore.recordChoice`; the scoped `ConflictResolver` uses the
  preference-aware ranker overload so learned choices shift subsequent rankings.
- `AutoConflictResolutionService` (SP5) is intentionally inert in production — it is
  the headless wiring point for Goal 10's deliberative dispatcher and remains
  documented-deferred (see its KDoc "Usage (SP2b pattern)").
- **SP2c.4 ruling (#827, 2026-07-30):** `AutoConflictResolutionService` is **demoted to an
  information source and frozen there by test**. It has zero production call sites and is not
  reachable in `shuntingLoopAI`, so it does not violate Goal 10's "no deterministic policy
  component may originate a dispatching action" non-goal. Goal 9's `ConflictDetectedEvent` now
  feeds **affordance-line text** only (`ConflictHintLatch` → `AffordanceAnnotator`); the LLM
  dispatcher stays the only actor. This also delivers Goal 10's B2 amendment.

---

### Goal 10: AI Dispatcher Routing

**Category:** A: Intelligent Automation
**Priority:** Critical *(re-scoped 2026-07-01 — was the project's top-priority goal)*
**Development Estimate:** 6 months
**Status:** ✅ **COMPLETE (stage A, 2026-08-23)** — tracked in #532

**User Value:**
Users can enable an AI-powered dispatcher that automatically routes trains through complex junctions, managing switch positions and signal states without manual intervention. The dispatcher explains its decisions and allows human override at any time.

**Success Criteria (as amended 2026-07-30; #532 governs):**
- Autonomous operation handling all routine routing decisions
- Manual override capability at any point
- Explainable decisions — the user can ask "why this route?" **and** "what else was available?"
- Reliability at ShuntingLoop scale, measured as a success rate over N ≥ 10 runs
- *Dropped:* "performance matching or exceeding the average human dispatcher". No human-dispatcher
  baseline can be collected in this project, so the criterion was unfalsifiable.

**Dependencies:** Goal 2 (pathfinding, ✅ complete), Goal 9 (conflict resolution, ✅ complete), Goal 3 (collision detection — added 2026-07-01, per the detailed design in #532)

**Implementation Notes:**
- Rule-based dispatcher first, LLM dispatcher behind the same seam
- Modular design for incremental enhancement
- The `Dispatcher` seam and `RuleBasedDispatcher` live in `:core`; the Koog + Ollama agent stack
  lives in the `:dispatcher-agent` module

---

#### Goal 10 stage A — completion notes (2026-08-23)

**What shipped:**
- The `Dispatcher` seam plus the deterministic `RuleBasedDispatcher`
  (`core/src/commonMain/.../sim/`), refactored out of `ShuntingLoop`.
- An LLM dispatcher on Koog + a local Ollama model, in the `:dispatcher-agent` module:
  `DispatchTickLoop`, four actuator tools, `ActionValidator`, `AffordanceAnnotator`,
  `DispatchDecisionApplier`.
- Authority: `DispatcherMode` AUTO / SEMI_AUTO / MANUAL with a persistent override,
  `SemiAutoApprovalGateway`, and `DispatcherControlPanel` in `:desktop-ui`.
- Explainability: recorded rationale per decision, plus the affordance annotation shown at that
  tick, so "why this route?" also answers "what else was available?".
- A measurement layer: per-run JSON records, a cross-run aggregator, and a headless sweep driver
  (`aiSweep`, manual only — never in CI).

**Amended acceptance contract** (owner-decided 2026-07-30, recorded in #822 §7; #532's body is
the governing text):

| ID | What it says now |
|---|---|
| **A4** | A **measured** success rate: N ≥ 10 runs, gate at **≥ 8/10**, with zero `RULE_FALLBACK` and zero `SAFETY_NET` action attributions in any run. A per-run author breakdown is mandatory. One non-`c7Clean` run fails the whole arm. |
| **A5** | Demoted and reframed. At ShuntingLoop scale, report **reliability**, not optimality. The optimality comparison moves to Praha (#591) and must there meet an **OR/MILP** yardstick, not only `RuleBasedDispatcher`. |
| **A6** | Split. Real-time ratio ≥ 1× gates the `RuleBasedDispatcher` only. The LLM arm runs acceptance with a **paused clock** (ratio not applicable) and reports wall-clock separately, ungated. |
| **B2** | Explainability also reports the affordance annotation of that tick. |
| **Non-goal** | The LLM is not responsible for action legality and is **not inside the safety envelope**. The interlocking shields all actions. |
| **Non-goal** | **No deterministic policy component may originate a dispatching action during an LLM run.** |
| **Paramount example** | `vyhybna.xml` proves **reliability under autonomy**, not optimality. Praha is where a non-deterministic policy has something to win. |
| **Determinism (P8)** | What is delivered is **prompt determinism**: the same recorded snapshot sequence produces a byte-identical prompt sequence. A sampling **seed cannot reach Ollama through Koog 1.1.1**, so decode determinism is not available on the tool-calling path. It is reachable only on a future JSON-only decision mode. |

**Measured A4 outcome** (`docs/GOAL_10_SP2C14_RELIABILITY_REPORT.md`, 60 runs,
`qwen2.5:7b-instruct`, `vyhybna.xml`, 600 simulated seconds):

| Arm / cell | Runs | Passing | Gate (≥ 8/10) |
|---|---|---|---|
| `RULE_BASED` (two independent control sets) | 10 + 10 | 10 + 10 | ✅ PASS |
| `LLM_TOOL_CALLING`, temperature 0.28, history 3 | 10 | 0 | ❌ FAIL |
| `LLM_TOOL_CALLING`, temperature 0.5, history 3 | 10 | 0 | ❌ FAIL |
| `LLM_TOOL_CALLING`, temperature 0.28, history 0 *(shipped default)* | 10 | 5 | ❌ FAIL |
| `LLM_TOOL_CALLING`, temperature 0.5, history 0 | 10 | 3 | ❌ FAIL |
| `LLM_CONSTRAINED_JSON` | 0 | 0 | ❌ FAIL — not yet measured (#890) |

Stated plainly: **the rule-based arm meets A4; the LLM arm does not, in any measured
configuration.** The best cell reaches 5 of 10, three passing runs short of the bar.
`SAFETY_NET` attributions are **zero everywhere** — the deterministic admission net is gone and
stayed gone — while `RULE_FALLBACK` attributions (87 and 14) are the mechanical cause of every
failed autonomy check.

The dominant failure is **structural, not decision quality**: 13 of the 40 LLM runs ended
`TERMINATED_EARLY` on a route-extension stall — a train stands at a STOP signal holding track it
cannot leave because the model stops extending its route. This is a dispatcher decision failure,
not a `:core` defect. Whether it is an **interface** problem or a **model-capacity** problem is
still open: #838 prepared the larger-model diagnostic grid but no run has been made
(`docs/GOAL_10_SP2C15_FRONTIER_DIAGNOSTIC_SETUP.md`).

**Why stage A closes anyway.** The acceptance contract was deliberately rewritten so that an
honest LLM failure rate is publishable rather than hidden. It is now published. The rule-based
arm carries the reproducibility guarantee, and the **interlocking — not the LLM — carries
safety**, so an unreliable LLM arm cannot make the simulator unsafe. Raising the LLM success
rate is continuing work; it is not a reason to hold stage A open.

**Moved out of Goal 10.** The old "Goal 10b" work — the agent-to-agent operating language for
inter-dispatcher coordination (#821, GitHub milestone *AI2 Protocol*) — moves into the **new
Goal 12**.

---

### Goal 11: Track Physics — Gradients and Curves

**Category:** E: Advanced Simulation
**Priority:** **Very Low** *(redefined 2026-08-23; was High)*
**Development Estimate:** 3 months
**Status:** 🆕 OPEN (#664 gradients; #665 curves, already retitled "joined with 11")

**Redefined 2026-08-23:** the old Goal 12 (Curved Track Modeling) is **folded into this goal**.
Gradients and curves are one physics goal, because they share the same equations and the same
editor work. The number 12 is reused for a new goal.

**User Value:**
Users can model track gradients and curves that realistically affect train acceleration, braking, speed limits, and energy use. This enables accurate simulation of hilly and winding routes and validation of timetables.

**Scope, deliberately restricted:**
- Keep to what Czech railway conditions really demand, and to what is easy to model and cheap to
  compute. A hump yard ("seřazovací nádraží") uses terrain gradients and some crossing tracks.
- Linear models only in this stage. Non-linear terrain is a later-stage concern of Goal 12, not
  of this goal.
- **The differential-equation solver stays in the application.** The simulator is a combined
  discrete-event and continuous simulation, and dropping the continuous half would close the
  door on this goal and on every later terrain feature. This is the main reason the solver is
  kept even while this goal waits.

**Success Criteria:**
- Configurable grade percentage and curve radius per track section
- Correct effect on acceleration, braking, and speed limit
- Visual indication of gradients and curves in the track editor
- Energy consumption calculation for gradient sections
- Gradients and curves interact correctly in one physics pass

**Dependencies:** None (physics extension)

**Implementation Notes:**
- Extend the existing physics model in the `Train` class
- Consider superelevation (cant) only if a real scenario needs it
- Required for accurate Goal 19 (Czech timetable) validation

---

### Goal 12: Inter-station Tracks and Multiple Dispatcher Agents

**Category:** A: Intelligent Automation
**Priority:** High *(new goal, defined 2026-08-23 — fourth in the next-stage order)*
**Development Estimate:** 5 months
**Status:** 🆕 OPEN — absorbs #821 (milestone *AI2 Protocol*: #575, #577, #578)

**New goal 2026-08-23.** The number 12 previously held "Curved Track Modeling", which is now
part of Goal 11.

**User Value:**
Users can connect two stations with a real inter-station track and give each station its own dispatcher agent. The two dispatchers must agree on the direction of travel before a train may enter the track between them, exactly as Czech railway rules require. This is also the proof that agent-to-agent coordination works.

**Success Criteria:**
- Two shunting loops, each driven by its own dispatcher agent, in **one** simulation context
- The two dispatchers negotiate the direction of the track between them, and no train enters
  against the agreed direction
- All trains complete their journeys with no conflict events and no operator action

**Scope (first stage):**
- **A2A proof:** two shunting loops with their own agents. Join the east end of loop 1 to the
  west end of loop 2.
- **`InOut` refactor:** today's behaviour becomes `RandomInOut`; `JoinedInOut` is two InOuts
  linked by an `InterstationTrack`.
- **`SimpleInterstationTrack`:** several kilometres long, 3 km by default. Only **one train at a
  time**, even in the agreed direction. At the InOut position it is FREE in the direction leaving
  the station, and has an entry signal ("vjezdové návěstidlo") in the direction toward the
  station, with a distant signal ("předvěst") ahead of it at braking distance ("zábrzdná
  vzdálenost").
- **Negotiation protocol:** reuse the merged inter-agent message protocol — sealed `Message` and
  8 speech acts in `dispatcher-agent/.../lang/proto/Message.kt` (PR #765) — and the operating
  language of the Czech railway rules ("dopravní předpisy"). Dispatchers renegotiate direction
  often.
- **Programmatic wiring only.** Editor support for inter-station tracks is a later stage.
- Study the sibling project `../OpenCybele1` for its inter-station and inter-agent communication
  (see its `docs/message-ontology.md`).

**Prepared for, but not built in, the first stage:**
- `InterstationTrackWithBlockPost` — the Czech "hradlo": up to 2 trains in one direction, but at
  most 1 before and 1 after the block post
- Automatic block signalling, and two independent single-direction tracks between stations, where
  the direction is normally fixed for a long period — better throughput and a simpler decision
  between the two stations

**Later stages:**
- A chain of several shunting loops — Jindřichův Hradec → Nová Bystřice.
- A `MainDispatcher` above the station dispatchers. It enforces safety and the timetable and
  judges the negotiation between dispatchers. It talks **only** to subordinate dispatchers and
  never uses the tools that control a station directly. Trains can complain to it, for example
  when one waits too long in a station, and it can resolve collision detections (Goal 9B).
  No micromanagement. It needs a different model from the station dispatcher — bigger context,
  smaller memory, faster; try gemma4. Its context is a simple chain or graph of stations and
  inter-station tracks with current and maximum capacity, plus the number of trains inside and
  the trains outside the system at the InOuts of the leaf stations (see `../OpenCybele1`).
- Non-linear terrain modelling on inter-station tracks (needs Goal 11).

**Dependencies:** Goal 10 stage A (✅ complete — the dispatcher seam and agent stack), Goal 1B
(scale and routing scenarios)

---

## Planned Next Stages

These three sections describe the stages planned after Goal 10 stage A. They sit together, in the
order they will be worked, rather than beside their first stages. See "Goal Stages" above.

### Goal 1B: Scale and Routing Test Scenarios

**Category:** E: Advanced Simulation
**Priority:** **Critical — the top-priority work as of 2026-08-23**
**Development Estimate:** 4 months
**Status:** 🆕 OPEN — GitHub milestone *Goal 1B*, currently holding #591

**Paramount example:** the **Praha hlavní nádraží 5-train scenario** (owner decision
2026-07-11). Goal 10 keeps `vyhybna.xml` as its paramount example; the Praha scale scenario moves
here.

**User Value:**
The scale bar that Goal 1 left unverified is finally closed, with routing tested on real station layouts instead of a two-switch loop.

**Scope.** Build a fresh task plan out of #591's test expectations, its `.md` documents, its code
comments, and the current state of the code. Note that **#591 is an issue, not a pull request**;
its work lives on the branch `feat/issue-591-scale-validation`, where the 5-train case passes and
the 20-train stress case livelocks. The plan must cover:

- What must be implemented as new, and what must be revisited
- Extension of the routing tests
- Generalising the shunting loop into a station
- More complex models, in order of difficulty: Červený Újezd first, Praha last (Praha is hard)
- The refactors this needs
- Tuning of prompts and tool descriptions
- Routing cleanup: smaller classes as simpler testable units, duplicate checks, facades (Goal 10
  already ships `DefaultInterlockingFacade`), and other design patterns
- A large review of kDisco for deadlocks, race conditions, and weak points, including research
  into existing scanners — this produces **new kDisco issues that become prerequisites of this
  goal**
- At least the kDisco package upgrade (kdisco#53)
- During the work, write the realistic demands on Goal 9B into an extra `.md` file as part of each
  task — at least a revision, as a retrospective

**Exit condition:** #591's failing tests are green at the end of the whole plan. That issue is
therefore created **last** in the plan. It runs with low traffic — long lambda times in the
parameters, and a low maximum of concurrent trains.

**Open questions to answer when the plan is written:**
- Is each item already part of some goal or issue?
- Does it depend on an existing open issue?
- Is the multi-loop example only usable with an agent?

**Dependencies:** Goal 1 (✅ complete), Goal 2 (✅ complete), Goal 10 stage A (✅ complete)

---

### Goal 9B: Realistic Conflict Resolution and Command Threading

**Category:** A: Intelligent Automation
**Priority:** High — second in the next-stage order
**Development Estimate:** 4 months
**Status:** 🆕 PLANNED — **specified during Goal 1B, built after it**

**Why.** Goal 9 as shipped is judged **not realistic** (owner decision 2026-07-11). Goal 9B
rebuilds conflict resolution so that the suggestions are realistic and are actually carried out,
using Goal 10's dispatcher, the Czech railway rules, and the findings written down during Goal 1B.

**Scope:**
- The **dispatcher runs on its own thread**, because a non-deterministic decision needs its own
  compute time. This means refactoring Goal 10's SB0.
- A **queue of high-level commands** — routes, and Goal 9B conflict-resolution commands.
- A **routing and command-resolving thread**. It also reads snapshots of the simulation thread.
  It makes **deterministic decisions only**, turning high-level commands into low-level ones.
  This needs Goal 1B's routing cleanup first.
- A **queue of low-level commands**. In an emergency the dispatcher may write into it directly.
- The **simulation thread**, with coroutines inside it, because their overhead is small.
- Open question to settle during the work: can the design also optimise unusual and emergency
  situations?

**Target scenario:** Praha hlavní nádraží at full traffic, with the maximum number of concurrent
trains.

**Dependencies:** Goal 9 (✅ complete), Goal 10 stage A (✅ complete), Goal 1B

---

### Goal 3B: Snapshot Collision Detection on a Separate Thread

**Category:** J: Safety & Compliance
**Priority:** High — fourth in the next-stage order
**Development Estimate:** 2 months
**Status:** 🆕 PLANNED

**Scope:**
- Detect collisions from a **simulation snapshot**, on a separate thread
- The result is also an **input for the dispatcher**, not only a warning for the user
- Extends Goal 9B's resolution suggestions

**Dependencies:** Goal 3 (✅ complete), Goal 9B

---

## Detailed Goal Descriptions (continued)

### Goal 13: Graphical Timetable Visualization

**Category:** F: Research & Analysis
**Priority:** High
**Development Estimate:** 3 months

**User Value:**
Users can view simulation results as graphical timetables (time-distance diagrams), the standard visualization tool in railway planning. This enables analysis of train conflicts, capacity utilization, and schedule optimization.

**Success Criteria:**
- Standard time-distance diagram format
- Interactive zoom and pan
- Train path highlighting and selection
- Export to PDF and image formats

**Dependencies:** Goal 6 (metrics collection)

**Implementation Notes:**
- New Swing visualization component
- Consider integration with existing timeline concepts

---

### Goal 14: Custom Train Types

**Category:** E: Advanced Simulation
**Priority:** High
**Development Estimate:** 2 months

**User Value:**
Users can define custom train types with specific performance characteristics (acceleration, maximum speed, braking curves, length). This enables simulation of realistic mixed traffic with different train categories.

**Success Criteria:**
- Configurable acceleration curves, max speed, length, braking performance
- Train type library with save/load capability
- Pre-built types for common categories (passenger, freight, high-speed)
- Easy assignment of types to simulated trains

**Dependencies:** None (parameter configuration)

**Implementation Notes:**
- Extend Train class with configurable parameters
- Consider XML schema extension for train type definitions

---

### Goal 15: Railway Interlocking Tutorials

**Category:** C: Educational
**Priority:** High
**Development Estimate:** 3 months

**User Value:**
Users new to railway interlocking can learn fundamentals through interactive step-by-step tutorials. This preserves InterlockSim's educational heritage and enables use in academic settings.

**Success Criteria:**
- 5+ tutorial scenarios covering basic to intermediate concepts
- Progress tracking across tutorial completion
- Checkpoint system using save/restore functionality
- Optional assessment/quiz mode

**Dependencies:** Goal 5 (save/restore for checkpoints)

**Implementation Notes:**
- Content development alongside technical implementation
- Consider collaboration with railway education institutions

---

### Goal 16: Signal Explanation Mode

**Category:** C: Educational
**Priority:** High
**Development Estimate:** 2 months

**User Value:**
Users can click on any signal to see a detailed explanation of why it shows its current aspect (red, green, etc.). This teaches the logic of interlocking systems and helps debug configuration issues.

**Success Criteria:**
- Click any signal to see state reasoning
- Animated state transitions showing cause and effect
- Clear explanation of blocking conditions
- Integration with tutorial scenarios

**Dependencies:** None (UI enhancement)

**Implementation Notes:**
- Requires introspection of signal state logic
- Build explanation templates for common states

---

### Goal 17: CSV/Excel Export

**Category:** D: Data Integration
**Priority:** High
**Development Estimate:** 1 month

**User Value:**
Users can export simulation results to CSV or Excel format for analysis in external tools (spreadsheets, statistical packages, databases). This enables research workflows and integration with other analysis tools.

**Success Criteria:**
- Export events, metrics, and train positions
- Configurable column selection
- Support for both CSV and Excel formats
- Scheduled automatic export during batch runs

**Dependencies:** Goal 6 (metrics - data source)

**Implementation Notes:**
- Quick win - straightforward file I/O implementation
- Consider Apache POI for Excel format

---

### Goal 18: Visual Train Timeline

**Category:** F: Research & Analysis
**Priority:** High
**Development Estimate:** 3 months

**User Value:**
Users can view a visual timeline of all train movements for post-simulation review. Clicking on timeline events jumps to that simulation state for detailed analysis.

**Success Criteria:**
- Interactive timeline with zoom capability
- Click to jump to specific simulation point (requires Goal 5)
- Visual indication of conflicts and delays
- Export timeline as image

**Dependencies:** Goal 6 (metrics), Goal 5 (save/restore for jumping)

**Implementation Notes:**
- New timeline UI component
- Integration with save/restore for state jumping

---

### Goal 19: Czech Timetable Import

**Category:** D: Data Integration
**Priority:** High
**Development Estimate:** 4 months

**User Value:**
Users can import Czech railway timetables in GVD/KADR format to simulate real-world schedules and validate simulation accuracy against actual operations. This enables research validation and practical railway applications.

**Success Criteria:**
- Parse standard GVD/KADR timetable format
- Map timetable stations to network elements
- Generate train schedules from imported data
- Validate simulation results against timetable

**Dependencies:** Goal 11 (gradient and curve physics, for accurate timing)

**Implementation Notes:**
- Requires GVD/KADR format research and documentation
- Contact Czech railway authorities for format specifications
- Consider fallback to simplified publicly available format

---

### Goal 20: Comprehensive Accessibility

**Category:** B: User Experience
**Priority:** Medium
**Development Estimate:** 3 months

**User Value:**
Users with disabilities can fully use InterlockSim through comprehensive accessibility support including themes, keyboard navigation, and screen reader compatibility. This ensures inclusive design and expands the potential user base.

**Success Criteria:**
- WCAG 2.1 AA compliance
- Dark mode and colorblind-friendly themes
- Full keyboard navigation (no mouse required)
- Screen reader compatibility for core workflows

**Dependencies:** None (can implement incrementally)

**Implementation Notes:**
- Accessibility specialist consultation recommended
- Implement incrementally across releases

---

## Implementation Strategy

**Re-planned 2026-08-23.** Phase 0 is **finished**: Goal 3, Goal 9, and Goal 10 stage A are all
complete. The new highest-priority block is **Phase 0B** below — Goal 1B, then Goal 9B, then
Goal 12, then Goal 3B. Phases 1-2 hold the remaining High/Medium work and can proceed in parallel
with, but never ahead of, Phase 0B if a second developer is available.

*Historical:* the 2026-07-01 plan put Goal 10 at the top and made Goal 3 → Goal 9 → Goal 10 the
only critical path. That path is now closed.

### ✅ Already complete (pre-dates this re-plan)

| Goal | Title | Months | Notes |
|------|-------|--------|-------|
| 1 | Multi-Train Simulation | 6 | Complete — foundation of Goal 10's dependency chain |
| 2 | Automatic Path Finding | 3 | Complete — direct dependency of Goal 9 and Goal 10 |
| 7 | Simulation Speed Control | 1 | Complete — no longer Critical (not on Goal 10's path) |
| 8 | Pause and Single-Step | 1 | Complete — no longer Critical (not on Goal 10's path) |

**Complete total: 11 months** (already delivered; excluded from the remaining-effort totals below).

### ✅ Phase 0: Critical Path to Goal 10 — COMPLETE (2026-08-23)

| Goal | Title | Months | Status | Tracking |
|------|-------|--------|--------|----------|
| 3 | Collision Detection and Warning | 2 | ✅ Complete | #610 |
| 9 | Automatic Conflict Detection and Resolution | 4 | ✅ Complete | #531 |
| 10 | AI Dispatcher Routing (stage A) | 6 | ✅ Complete | #532 |

**Phase 0 Total: 12 months, delivered.**

**Delivered:**
- Collision warnings + auto-pause/halt (Goal 3)
- Ranked, learnable conflict resolution (Goal 9)
- Autonomous, explainable AI dispatcher with human override (Goal 10 stage A). The LLM arm's
  measured success rate is published and is **below** the A4 bar — see Goal 10's completion
  notes.

### Phase 0B: Next Stages (highest priority — strictly sequential)

**Objective:** close Goal 1's unverified scale bar, then rebuild conflict resolution on realistic
demands, then connect stations to each other. Nothing in Phases 1-2 should take developer time
away from this phase.

| Order | Goal | Title | Months | Rationale | Tracking |
|-------|------|-------|--------|-----------|----------|
| 1 | 1B | Scale and Routing Test Scenarios | 4 | **Top-priority work.** Closes #591; produces the routing cleanup and the kDisco review everything else needs | #591, milestone *Goal 1B* |
| 2 | 9B | Realistic Conflict Resolution and Command Threading | 4 | Specified during Goal 1B, built from its findings | — |
| 3 | 12 | Inter-station Tracks and Multiple Dispatcher Agents | 5 | Needs the Goal 10 dispatcher seam and Goal 1B's routing cleanup | #821 |
| 4 | 3B | Snapshot Collision Detection on a Separate Thread | 2 | Feeds Goal 9B's resolution suggestions | — |

**Phase 0B Total: 15 months sequential** (each step depends on the one before it).

### Phase 1: High-Priority Enhancement (parallel to Phase 0, non-blocking)

**Objective:** Everything re-scoped from Critical to High, plus the pre-existing High-priority goals — none of these block Goal 10, so they only consume developer capacity beyond what Phase 0 needs.

| Goal | Title | Months | Rationale | Tracking |
|------|-------|--------|-----------|----------|
| 6 | Performance Metrics | 2 | No dependencies; foundation for Goals 13, 17, 18 | #659 |
| 5 | Save/Restore State | 3 | Dependencies (Goals 7, 8) already complete; foundation for Goals 15, 18 | #666 |
| 4 | Interlocking Validation | 4 | Depends on Goal 2 (done) + Goal 3 (Phase 0) | #660 |
| 11 | Track Physics: Gradients and Curves | 3 | No dependencies. **Very Low priority** — kept because the differential-equation solver must stay in the application | #664, #665 |
| 14 | Custom Train Types | 2 | No dependencies | #667 |
| 16 | Signal Explanation | 2 | No dependencies | #669 |
| 13 | Graphical Timetable | 3 | Depends on Goal 6 | #661 |
| 17 | CSV/Excel Export | 1 | Depends on Goal 6 | #662 |
| 18 | Visual Timeline | 3 | Depends on Goal 6 + Goal 5 | #663 |
| 15 | Tutorials | 3 | Depends on Goal 5 | #668 |
| 19 | Czech Timetable Import | 4 | Depends on Goal 11 | #670 |

**Phase 1 Total: 30 months sequential, ~16-18 months with parallelization** (most items are independent leaves or single-hop dependents — high parallelization potential, unlike Phase 0).

### Phase 2: Medium-Priority Polish

| Goal | Title | Months | Rationale | Tracking |
|------|-------|--------|-----------|----------|
| 20 | Accessibility | 3 | No dependencies; can be implemented incrementally alongside any other phase | #671 |

**Phase 2 Total: 3 months**, absorbable into Phase 0/1 developer downtime rather than scheduled as a separate block.

---

## Total Development Effort

| Phase | Sequential | Parallel (2 devs) |
|-------|------------|-------------------|
| ✅ Complete (Goals 1, 2, 7, 8) | 11 months | — (done) |
| ✅ Phase 0: Critical Path to Goal 10 (Goals 3, 9, 10A) | 12 months | — (done) |
| Phase 0B: Next Stages (1B → 9B → 12 → 3B) | 15 months | 15 months *(no parallelization — strictly sequential)* |
| Phase 1: High-Priority Enhancement | 30 months | ~16-18 months |
| Phase 2: Medium-Priority Polish | 3 months | ~2 months (absorbed) |
| **Total (all goals and named stages)** | **71 months** | **~46 months** |
| **Remaining (excl. complete)** | **48 months** | **~33-35 months** |

**Resource Options:**
- 1 developer: Phase 0B alone is a ~1.3-year commitment; full remaining scope ~4 years.
- 2 developers: 1 dedicated to Phase 0B (1B → 9B → 12 → 3B, cannot be parallelized further), 1 covering Phase 1/2 in the background.
- Phased funding: fund Phase 0B to completion first (Goal 1B unblocks everything after it); revisit Phase 1/2 scope afterward.

---

## Risk Assessment

**Re-scoped 2026-08-23:** Goal 10's critical path is closed, so risk priority now follows
**Phase 0B**: Goal 1B → Goal 9B → Goal 12 → Goal 3B. Goal 1B inherits the highest schedule risk,
because it owns the kDisco deadlock and race-condition review that everything after it depends on.
Goal 11 is Very Low priority and carries no schedule risk.

**Re-scoped 2026-07-01 (historical):** risk priority followed Goal 10's critical path (Goal 3 → Goal 9 → Goal 10). Goal 1's risk is resolved (complete). Goal 9 is promoted out of Medium-Risk into High-Risk, since it now sits directly on the only path to the top-priority goal. Goal 4's demotion to High priority does not reduce its underlying technical risk (still formal-verification-heavy) — it stays Medium-Risk, just no longer schedule-critical.

### High-Risk Goals

**✅ Resolved — Goal 1: Multi-Train Simulation**
- **Risk (historical):** kDisco scheduler may require fundamental architectural changes; concurrent entity management introduces race conditions and event ordering complexity.
- **Outcome:** Complete. No DSOL migration was needed; kDisco's atomic block reservation approach (see #601/#603) held up. Kept here for historical record only — no longer an active risk.

**Goal 9: Automatic Conflict Detection and Resolution** *(promoted from Medium-Risk 2026-07-01 — now on Goal 10's critical path)*
- **Risk:** Algorithm complexity for temporal conflicts (future resource contention, configurable lookahead); ranking-and-learning loop (`DispatcherPreferenceStore`) is new design surface with no prior art in this codebase.
- **Impact (historical):** Critical — it directly blocked Goal 10. Both are now complete; the open risk moved to Goal 9B, which rebuilds this machinery on realistic demands.
- **Mitigation:**
  - Start with simple spatial-conflict detection (#580) before temporal (#583), enhance iteratively — sequencing already reflected in #531's sub-task order
  - Keep preference learning (#592) as the last sub-task, behind a working rule-based ranking engine (#588)

**✅ Resolved (stage A) — Goal 10: AI Dispatcher Routing**
- **Risk (historical):** planning algorithm complexity; integration with pathfinding; explainability requirements limiting algorithm choices.
- **Outcome:** stage A complete 2026-08-23. The mitigations held: the rule-based dispatcher came
  first and passes A4 at 10/10, the seam kept the LLM swappable, and SEMI_AUTO exists as a
  fallback. **One risk was realised:** the LLM arm's measured success rate is below the ≥ 8/10
  gate in every configuration, on a route-extension stall. That is now a Goal 12 and Goal 1B
  concern, not a Goal 10 blocker, because the interlocking — not the LLM — carries safety.

**Goal 1B: Scale and Routing Test Scenarios** *(new highest schedule risk 2026-08-23)*
- **Risk:** the 20-train Praha stress case livelocks today; the kDisco deadlock and race-condition
  review may produce prerequisite kDisco issues of unknown size; routing cleanup touches code that
  Goal 10 depends on.
- **Impact:** Critical — Goal 9B, Goal 12, and Goal 3B all wait behind it.
- **Mitigation:**
  - Order the models by difficulty: Červený Újezd first, Praha last
  - Run with low traffic first — long lambda times, low maximum of concurrent trains
  - Create the #591 closing task **last** in the plan, so the failing tests are the exit condition
  - Research existing scanners before hand-auditing kDisco

**Goal 19: Czech Timetable Import**
- **Risk:** GVD/KADR format may be undocumented, proprietary, or vary between sources; data quality inconsistencies
- **Impact:** Medium - primarily affects validation use case; unaffected by the 2026-07-01 re-plan since it was never on Goal 10's path
- **Mitigation:**
  - Contact Czech railway authorities early for documentation
  - Start with simplified publicly available format
  - Build robust parser with comprehensive error handling
  - Consider alternative data sources (OpenRailwayMap, GTFS)

### Medium-Risk Goals

| Goal | Risk | Mitigation |
|------|------|------------|
| 4: Interlocking Validation | Requires formal safety rule knowledge | External specialist consultation |
| 5: Save/Restore State | kDisco process serialization complexity | Incremental implementation, test extensively |

### Dependency Risks

```
Critical Path 0 (CLOSED 2026-08-23): Foundation -> Safety -> AI
  Goal 1 (done) -> Goal 2 (done) -> Goal 3 (done) -> Goal 9 (done) -> Goal 10 stage A (done)
  Kept for historical record only.

Critical Path 0B (the ONLY schedule-critical path as of 2026-08-23): Scale -> Realism -> Network
  Goal 1B (scale + routing cleanup + kDisco review)
    -> Goal 9B (realistic conflict resolution, specified during 1B)
    -> Goal 12 (inter-station tracks, several dispatcher agents)
    -> Goal 3B (snapshot collision detection)
  Risk: a delay in Goal 1B delays all three stages behind it, with no other goal absorbing the
  slack. Goal 1B also owns an unbounded piece of work — the kDisco deadlock and race-condition
  review — whose findings become its own prerequisites.

Critical Path 1: Safety Chain (re-scoped to High priority, no longer schedule-critical)
  Goal 1 (multi-train, done) -> Goal 3 (collision, shared with Path 0) -> Goal 4 (interlocking)
  Risk: Goal 4 now only risks its own schedule, not Goal 10's - demoted from Critical to High.

Critical Path 2: Analytics Chain (re-scoped to High priority, no longer schedule-critical)
  Goal 6 (metrics) -> Goal 13 (timetable viz) -> Goal 18 (timeline)
  Risk: Entirely independent of Goal 10 - can proceed in parallel with Phase 0 without
  any risk of delaying the top-priority goal.
```

---

## Resource Requirements

### Team Composition

**Minimum Team (1 developer):**
- Full-stack Kotlin/Java developer with Swing experience
- Timeline: ~4-5 years for all 20 goals
- Suitable for: Hobby project, academic research, individual contributor

**Recommended Team (2 developers):**
- 1 backend developer (simulation logic, AI, data)
- 1 frontend developer (UI, visualization, tutorials)
- Timeline: ~3 years for all 20 goals
- Suitable for: Funded project, commercial development

### Required Skills

**Core Skills (Required):**
- Kotlin/Java development (primary language)
- Swing GUI development (existing framework)
- Discrete-event simulation concepts
- Test-driven development
- Git version control

**Domain Skills (Can Learn):**
- Basic railway operations and terminology
- Interlocking system concepts
- Railway signaling principles

**Specialized Skills (For Specific Goals):**
- Goal 4: Formal verification concepts
- Goal 10: AI/ML, planning algorithms
- Goal 19: Czech language (for documentation)
- Goal 20: WCAG accessibility standards

### External Expertise (Optional)

| Expert | Goals Supported | Engagement |
|--------|-----------------|------------|
| Railway signaling specialist | Goal 4 | 2 weeks consultation |
| Czech railway timetable expert | Goal 19 | 1 week + format documentation |
| Accessibility specialist | Goal 20 | 1 week WCAG compliance review |

---

## Appendix: Decision Process

### Five-Meeting Planning Process

**Meeting 1: Railway System Vision (90 min)**
- Generated 86 initial goal ideas (target: 50)
- Reviewed thesis documentation and source code TODOs
- Identified target users and pain points
- Established functional (not technical) focus

**Meeting 2: Technology & AI Exploration (90 min)**
- Generated 89 additional goal ideas
- Total: 170+ goals across 10 categories
- Research presentations on AI, architecture, modern tools
- Added Safety & Compliance category (J)

**Meeting 3: Goal Prioritization & Filtering (120 min)**
- Individual rankings by all 4 team members
- Consensus analysis identified 16 high-agreement goals
- Applied 6 systematic filters
- Reduced to 45 prioritized candidates

**Meeting 4: Final Selection & Refinement (120 min)**
- Feasibility review by kotlin-tech-lead
- Domain validation by railway-engineer
- Final selection of 20 goals from 45 candidates
- Detailed specifications written

**Meeting 5: Cost Estimation & Strategy (120 min)**
- Effort estimates for all 20 goals
- Three-phase implementation strategy
- Risk assessment and mitigation
- Resource requirements defined
- Document approved and published

### Selection Criteria Applied

1. **User Value:** Does this enable something users couldn't do before?
2. **Technical Feasibility:** Can this be implemented with reasonable effort?
3. **Alignment:** Does this fit InterlockSim's educational heritage?
4. **Success Criteria:** Can we define clear, measurable outcomes?
5. **Scope:** Is the implementation scope reasonable?
6. **Dependencies:** Are prerequisites achievable first?

### Goals Deferred

The following categories of goals were deferred for future consideration:

- **Platform Goals (Web/Mobile):** 12+ month architectural changes required
- **ETCS Signaling:** Defer until Czech signaling complete
- **Multi-User Collaboration:** Requires server infrastructure
- **Advanced AI Learning:** Requires mature AI dispatcher foundation

---

**Document Version:** 1.2
**Created:** 2026-01-09
**Last Revised:** 2026-08-23 (#839, SP2c.16) — added the **Goal Stages** convention; marked **Goal 10 stage A complete** and recorded the amended acceptance contract (#822 §7) together with SP2c.14's measured A4 result, including the LLM arm's failure to reach the ≥ 8/10 gate; corrected the P8 determinism claim (a sampling seed cannot reach Ollama through Koog 1.1.1 — prompt determinism only); **redefined Goal 11** to cover gradients *and* curves at Very Low priority, folding in the old Goal 12; **created a new Goal 12** for inter-station tracks and multiple dispatcher agents, absorbing #821; added brief **Goal 1B, 9B, 3B** sections and the new Phase 0B (1B → 9B → 12 → 3B); restructured Implementation Strategy, Total Development Effort, and Risk Assessment accordingly
**Previously Revised:** 2026-07-01 — re-prioritized around Goal 10 as the top-priority goal; only its dependency chain (Goal 1 → 2 → 3 → 9 → 10) remained Critical; Goal 9/Goal 10 dependency lines reconciled with the GitHub issue tracker (#531, #532) to include Goal 3
**Approved By:** traffic-simulation-expert, agent-architect, kotlin-tech-lead, railway-engineer
**Next Review:** After Goal 1B completion
