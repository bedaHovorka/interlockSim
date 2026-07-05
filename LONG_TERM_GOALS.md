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

## Goals Summary Table

**Priority re-scoped 2026-07-01:** Goal 10 (AI Dispatcher Routing) is now the top-priority goal. Only Goal 10's transitive dependency chain — Goal 1 → Goal 2 → Goal 3 → Goal 9 → Goal 10 — remains **Critical**. Every other goal, including the previously-Critical Goals 4, 5, 6, 7, 8, is re-scoped to **High**. This also reconciles the doc with the dependency graph established in the GitHub issue tracker (#610, #531, #532), which added **Goal 3 as a direct prerequisite of both Goal 9 and Goal 10** — a link not present in the original 2026-01-09 planning. See "Implementation Strategy" and "Risk Assessment" below for the resulting re-plan.

| ID | Title | Category | Priority | Status | Estimate |
|----|-------|----------|----------|----------|----------|
| 1 | Multi-Train Simulation | E: Advanced Simulation | Critical | ✅ Complete | 6 months |
| 2 | Automatic Path Finding | E: Advanced Simulation | Critical | ✅ Complete | 3 months |
| 3 | Collision Detection and Warning | J: Safety & Compliance | Critical | ✅ Complete | 2 months |
| 4 | Interlocking Validation and Generation | J: Safety & Compliance | High | 🆕 Open (#660) | 4 months |
| 5 | Save and Restore Simulation State | I: System Operations | High | 🆕 Open (#666) | 3 months |
| 6 | Performance Metrics Collection | F: Research & Analysis | High | 🆕 Open (#659) | 2 months |
| 7 | Simulation Speed Control | I: System Operations | High | ✅ Complete | 1 month |
| 8 | Pause and Single-Step Simulation | I: System Operations | High | ✅ Complete | 1 month |
| 9 | Automatic Conflict Detection and Resolution | A: Intelligent Automation | **Critical** | 🆕 Open (#531) | 4 months |
| 10 | AI Dispatcher Routing | A: Intelligent Automation | **Critical** | 🆕 Open (#532) | 6 months |
| 11 | Track Gradients Physics | E: Advanced Simulation | High | 🆕 Open (#664) | 2 months |
| 12 | Curved Track Modeling | E: Advanced Simulation | High | 🆕 Open (#665) | 2 months |
| 13 | Graphical Timetable Visualization | F: Research & Analysis | High | 🆕 Open (#661) | 3 months |
| 14 | Custom Train Types | E: Advanced Simulation | High | 🆕 Open (#667) | 2 months |
| 15 | Railway Interlocking Tutorials | C: Educational | High | 🆕 Open (#668) | 3 months |
| 16 | Signal Explanation Mode | C: Educational | High | 🆕 Open (#669) | 2 months |
| 17 | CSV/Excel Export | D: Data Integration | High | 🆕 Open (#662) | 1 month |
| 18 | Visual Train Timeline | F: Research & Analysis | High | 🆕 Open (#663) | 3 months |
| 19 | Czech Timetable Import | D: Data Integration | High | 🆕 Open (#670) | 4 months |
| 20 | Comprehensive Accessibility | B: User Experience | Medium | 🆕 Open (#671) | 3 months |

**Total Development Estimate: 57 months (13 months complete — Goals 1, 2, 3, 7, 8; 44 months remaining)**

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
**Priority:** Critical *(re-scoped 2026-07-01 from High — direct dependency of Goal 10, now the top-priority goal)*
**Development Estimate:** 4 months
**Status:** 🆕 OPEN (#531)

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

---

### Goal 10: AI Dispatcher Routing

**Category:** A: Intelligent Automation
**Priority:** Critical *(re-scoped 2026-07-01 from High — this is now the project's top-priority goal)*
**Development Estimate:** 6 months
**Status:** 🆕 OPEN (#532)

**User Value:**
Users can enable an AI-powered dispatcher that automatically routes trains through complex junctions, managing switch positions and signal states without manual intervention. The AI explains its decisions and allows human override at any time.

**Success Criteria:**
- Autonomous operation handling all routine routing decisions
- Manual override capability at any point
- Explainable decisions (user can query "why this route?")
- Performance matching or exceeding average human dispatcher

**Dependencies:** Goal 2 (pathfinding, ✅ complete), Goal 9 (conflict resolution), Goal 3 (collision detection — added 2026-07-01, per detailed design in #532: the deliberative dispatcher routes around predictive TTC warnings and uses the headless auto-pause/halt API from Goal 3 SP4/SP5)

**Implementation Notes:**
- Start with rule-based dispatcher; add ML/optimization later
- High complexity - modular design for incremental enhancement
- Consider integration with agent-architect's AI research
- **Top priority as of 2026-07-01:** all other goals are scoped around not blocking this one; only its transitive dependency chain (Goal 1 → 2 → 3 → 9 → 10) is Critical

---

### Goal 11: Track Gradients Physics

**Category:** E: Advanced Simulation
**Priority:** High
**Development Estimate:** 2 months

**User Value:**
Users can model track gradients (uphill and downhill sections) that realistically affect train acceleration, braking, and energy consumption. This enables accurate simulation of mountainous routes and timetable validation.

**Success Criteria:**
- Configurable grade percentage for each track section
- Accurate physics impact on acceleration and braking
- Visual indication of gradients in track editor
- Energy consumption calculation for gradient sections

**Dependencies:** None (physics extension)

**Implementation Notes:**
- Extend existing physics model in Train class
- Foundation for Goal 12 (curved tracks)
- Required for accurate Goal 19 (Czech timetable) validation

---

### Goal 12: Curved Track Modeling

**Category:** E: Advanced Simulation
**Priority:** High
**Development Estimate:** 2 months

**User Value:**
Users can model curved track sections with automatic speed restrictions based on curve radius. This enables realistic simulation of speed-restricted curves and accurate journey time calculation.

**Success Criteria:**
- Configurable curve radius for track sections
- Automatic speed limit calculation based on radius
- Visual representation of curves in editor
- Correct physics interaction with gradients (Goal 11)

**Dependencies:** Goal 11 (gradients - physics foundation)

**Implementation Notes:**
- Extend physics model for centrifugal effects
- Consider superelevation (cant) modeling
- Required for accurate Goal 19 validation

---

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

**Dependencies:** Goal 11, Goal 12 (physics for accurate timing)

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

**Re-planned 2026-07-01** around Goal 10 as the top-priority goal. Phase 0 below is Goal 10's critical path and takes precedence over everything else; Phases 1-2 hold the re-scoped High/Medium work and can proceed in parallel with (but never ahead of) Phase 0 if a second developer is available.

### ✅ Already complete (pre-dates this re-plan)

| Goal | Title | Months | Notes |
|------|-------|--------|-------|
| 1 | Multi-Train Simulation | 6 | Complete — foundation of Goal 10's dependency chain |
| 2 | Automatic Path Finding | 3 | Complete — direct dependency of Goal 9 and Goal 10 |
| 7 | Simulation Speed Control | 1 | Complete — no longer Critical (not on Goal 10's path) |
| 8 | Pause and Single-Step | 1 | Complete — no longer Critical (not on Goal 10's path) |

**Complete total: 11 months** (already delivered; excluded from the remaining-effort totals below).

### Phase 0: Critical Path to Goal 10 (highest priority — strictly sequential)

**Objective:** Close out the only remaining Critical-priority chain: Goal 3 → Goal 9 → Goal 10. Nothing in Phases 1-2 should take developer time away from this phase.

| Goal | Title | Months | Rationale | Tracking |
|------|-------|--------|-----------|----------|
| 3 | Collision Detection and Warning | 2 | Direct prerequisite of Goal 9 and Goal 10 (added to the dependency graph 2026-07-01) | #610, in progress |
| 9 | Automatic Conflict Detection and Resolution | 4 | Direct prerequisite of Goal 10 | #531 |
| 10 | AI Dispatcher Routing | 6 | **Top-priority goal** | #532 |

**Phase 0 Total: 12 months sequential** (no parallelization benefit — each step strictly depends on the last).

**Deliverables:**
- Collision warnings + auto-pause/halt (Goal 3)
- Ranked, learnable conflict resolution (Goal 9)
- Autonomous, explainable AI dispatcher with human override (Goal 10)

### Phase 1: High-Priority Enhancement (parallel to Phase 0, non-blocking)

**Objective:** Everything re-scoped from Critical to High, plus the pre-existing High-priority goals — none of these block Goal 10, so they only consume developer capacity beyond what Phase 0 needs.

| Goal | Title | Months | Rationale | Tracking |
|------|-------|--------|-----------|----------|
| 6 | Performance Metrics | 2 | No dependencies; foundation for Goals 13, 17, 18 | #659 |
| 5 | Save/Restore State | 3 | Dependencies (Goals 7, 8) already complete; foundation for Goals 15, 18 | #666 |
| 4 | Interlocking Validation | 4 | Depends on Goal 2 (done) + Goal 3 (Phase 0) | #660 |
| 11 | Track Gradients | 2 | No dependencies; foundation for Goal 12 | #664 |
| 12 | Curved Tracks | 2 | Depends on Goal 11 | #665 |
| 14 | Custom Train Types | 2 | No dependencies | #667 |
| 16 | Signal Explanation | 2 | No dependencies | #669 |
| 13 | Graphical Timetable | 3 | Depends on Goal 6 | #661 |
| 17 | CSV/Excel Export | 1 | Depends on Goal 6 | #662 |
| 18 | Visual Timeline | 3 | Depends on Goal 6 + Goal 5 | #663 |
| 15 | Tutorials | 3 | Depends on Goal 5 | #668 |
| 19 | Czech Timetable Import | 4 | Depends on Goal 11 + Goal 12 | #670 |

**Phase 1 Total: 31 months sequential, ~16-18 months with parallelization** (most items are independent leaves or single-hop dependents — high parallelization potential, unlike Phase 0).

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
| Phase 0: Critical Path to Goal 10 | 12 months | 12 months *(no parallelization — strictly sequential)* |
| Phase 1: High-Priority Enhancement | 31 months | ~16-18 months |
| Phase 2: Medium-Priority Polish | 3 months | ~2 months (absorbed) |
| **Total (all 20 goals)** | **57 months** | **~36 months** |
| **Remaining (excl. complete)** | **46 months** | **~30-32 months** |

**Resource Options:**
- 1 developer: Phase 0 alone is a ~1-year commitment before Goal 10 ships; full remaining scope ~3.5-4 years.
- 2 developers: 1 dedicated to Phase 0 (Goal 3 → Goal 9 → Goal 10, cannot be parallelized further), 1 covering Phase 1/2 in the background — Goal 10 ships in ~1 year instead of waiting behind Phase 1/2 work.
- Phased funding: fund Phase 0 to completion first (it's the only path to the top-priority goal); revisit Phase 1/2 scope afterward.

---

## Risk Assessment

**Re-scoped 2026-07-01:** risk priority now follows Goal 10's critical path (Goal 3 → Goal 9 → Goal 10). Goal 1's risk is resolved (complete). Goal 9 is promoted out of Medium-Risk into High-Risk, since it now sits directly on the only path to the top-priority goal. Goal 4's demotion to High priority does not reduce its underlying technical risk (still formal-verification-heavy) — it stays Medium-Risk, just no longer schedule-critical.

### High-Risk Goals

**✅ Resolved — Goal 1: Multi-Train Simulation**
- **Risk (historical):** kDisco scheduler may require fundamental architectural changes; concurrent entity management introduces race conditions and event ordering complexity.
- **Outcome:** Complete. No DSOL migration was needed; kDisco's atomic block reservation approach (see #601/#603) held up. Kept here for historical record only — no longer an active risk.

**Goal 9: Automatic Conflict Detection and Resolution** *(promoted from Medium-Risk 2026-07-01 — now on Goal 10's critical path)*
- **Risk:** Algorithm complexity for temporal conflicts (future resource contention, configurable lookahead); ranking-and-learning loop (`DispatcherPreferenceStore`) is new design surface with no prior art in this codebase.
- **Impact:** Critical - directly blocks Goal 10, the top-priority goal. Unlike before the re-plan, there is no other goal to fall back to while this is being worked.
- **Mitigation:**
  - Start with simple spatial-conflict detection (#580) before temporal (#583), enhance iteratively — sequencing already reflected in #531's sub-task order
  - Keep preference learning (#592) as the last sub-task, behind a working rule-based ranking engine (#588)

**Goal 10: AI Dispatcher Routing** *(re-scoped 2026-07-01 — now the top-priority goal, not a non-blocking capstone)*
- **Risk:** Planning algorithm complexity; integration challenges with pathfinding; explainability requirements may limit algorithm choices.
- **Impact:** Critical - this is now the project's top priority. Previously assessed as "not blocking other goals"; that framing no longer applies since every other goal is explicitly scoped to not compete with this one for developer time.
- **Mitigation:**
  - Start with rule-based dispatcher before ML approaches
  - Modular design for incremental enhancement
  - Define clear interface between planning and execution
  - Accept semi-autonomous mode as fallback

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
Critical Path 0 (was "Path 1", renumbered as top priority 2026-07-01): Foundation -> Safety -> AI
  Goal 1 (multi-train, done) -> Goal 2 (pathfinding, done) -> Goal 3 (collision, in progress)
    -> Goal 9 (conflict) -> Goal 10 (AI dispatcher)
  Risk: This is now the ONLY schedule-critical path. A delay anywhere in Goal 3, 9, or 10
  delays the top-priority goal directly, with no other goal absorbing the slack.
  Note: Goal 3 was not originally on this path in the 2026-01-09 plan — added 2026-07-01
  per #531/#532's detailed dependency design.

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

**Document Version:** 1.1
**Created:** 2026-01-09
**Last Revised:** 2026-07-01 — re-prioritized around Goal 10 as the top-priority goal; only its dependency chain (Goal 1 → 2 → 3 → 9 → 10) remains Critical; Implementation Strategy, Total Development Effort, and Risk Assessment restructured accordingly; Goal 9/Goal 10 dependency lines reconciled with the GitHub issue tracker (#531, #532) to include Goal 3
**Approved By:** traffic-simulation-expert, agent-architect, kotlin-tech-lead, railway-engineer
**Next Review:** After Phase 0 (Goal 10) completion
