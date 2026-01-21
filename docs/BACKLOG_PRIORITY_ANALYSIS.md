# Backlog Priority Analysis by Dependency Impact

**Generated:** 2026-01-21
**Analysis Type:** Dependency-based prioritization
**Scope:** Set B (Backlog milestone, 30 issues) → Set X (All open issues, 56 issues)

---

## Executive Summary

This analysis prioritizes the 30 Backlog issues based on how many other open issues each one helps/unblocks.

**Key Findings:**
- **Top issue (#94)** unblocks 18 issues (32% of all open issues)
- **Top 4 issues** (#94, #61, #210, #91) help 28 issues (50% of all open issues)
- **60% of backlog** (18 issues) are standalone improvements with no direct dependencies

**Strategic Recommendation:** Focus on **#94 (Decouple simulation)** first to unblock entire Goal 7 milestone, then tackle **#61 (XML save)** and **#210 (Context transformation)** for balanced progress.

---

## Priority Ranking Table

| Rank | Issue # | Title | Impact | Helped Issues | Effort | Tier |
|------|---------|-------|--------|---------------|--------|------|
| 1 | #94 | Decouple simulation classes from SimulationContext | 🔥🔥🔥 | **18 issues** | MEDIUM-HIGH | 1 |
| 2 | #61 | XMLContextFactory: Implement saveContext() | 🟠🟠 | **4 issues** | MEDIUM | 2 |
| 3 | #210 | Simplify Context Transformation Process | 🟠🟠 | **3 issues** | MEDIUM | 2 |
| 4 | #91 | Refactor circular dependency (cells/paths/tracks) | 🟠🟠 | **3 issues** | HIGH | 2 |
| 5 | #216 | Migrate performance tests to JMH benchmarks | 🟡 | **2 issues** | LOW | 3 |
| 6 | #247 | Fix disabled integration tests | 🟡 | **2 issues** | MEDIUM | 3 |
| 7 | #79 | Enhance test coverage for InOut validation | 🟡 | **2 issues** | LOW | 3 |
| 8 | #212 | Complete Phase 6 Documentation | 🟡 | **2 issues** | MEDIUM | 3 |
| 9 | #214 | Pre-Wrap All Tracks at Initialization | 🟡 | **2 issues** | LOW | 3 |
| 10 | #218 | Implement Koin golden output validation | 🟡 | **1 issue** | LOW | 3 |
| 11 | #62 | Support bidirectional train operation | 🟡 | **1 issue** | MEDIUM | 3 |
| 12-30 | Various | SonarQube, testing, docs, features | ⚪ | **0 issues** | LOW-MEDIUM | 4 |

---

## Tier 1: High Impact (10+ issues helped)

### #94: Decouple simulation classes from SimulationContext
**Impact: 18 issues helped (32% of X)**

**Direct dependencies:**
- **Goal 7 Milestone** (#187-200): All 14 issues require simulation decoupling
  - #187: Goal 7: Simulation Speed Control (root issue)
  - #188: Phase 1.1: Core SimulationRunner Implementation
  - #189: Phase 1.2: Main.kt and Frame.kt Integration
  - #190: Phase 1.3: Remove System.exit from DefaultSimulationContext
  - #191-200: Subsequent phases
- #215: Add Type-Safe Dynamic References in ShuntingLoop
- #214: Pre-Wrap All Tracks at Initialization

**Why critical:**
- Unblocks entire Goal 7 milestone (simulation speed control 0.1x-100x)
- Currently `sim/` classes tightly coupled to concrete SimulationContext
- Blocks testability, extensibility, and GUI integration
- Requires SimulationEnvironment interface abstraction

**Constraints:**
- `sim/` package has restricted modification rules (CLAUDE.md)
- Must coordinate with jDisco migration planning
- All 662 tests must pass after changes

**Effort:** MEDIUM-HIGH (2-3 days)

---

## Tier 2: Medium Impact (3-5 issues helped)

### #61: XMLContextFactory: Implement saveContext()
**Impact: 4 issues helped (7% of X)**

**Direct dependencies:**
- #248: XML serialization does not preserve context properties
- #250: XML serialization does not preserve NodeCell names
- #249: Property setters do not fire PropertyChangeEvents (needed for proper save)
- #79: Enhance test coverage for InOut validation (save/load cycle)

**Why important:**
- Enables GUI save functionality (critical user workflow gap)
- Currently users can only load/edit XML manually, cannot save from GUI
- Enables edit → save → reload roundtrip workflow

**Requirements:**
- Serialize Context to XML matching data.xsd schema
- Preserve all track elements, grid positions, connections
- Handle special characters in names/IDs
- Format XML with proper indentation

**Effort:** MEDIUM (2-3 days)

---

### #210: Simplify Context Transformation Process
**Impact: 3 issues helped (5% of X)**

**Direct dependencies:**
- #211: Add Transformation Validation (needs simplified code)
- #213: Eliminate Unused Grid from GridTransformer Result
- #214: Pre-Wrap All Tracks at Initialization (transformation related)

**Why important:**
- Current `fromEditingContext()` is 84-line god method
- 5 distinct phases mixed in single function
- Blocks maintainability and testing of transformation logic
- Foundation for other context improvements

**Solution:**
- Extract 6 helper methods (copyGridCells, copyGraphStructure, etc.)
- Add validation phase
- Improve graph copying performance
- Enable unit testing of individual phases

**Effort:** MEDIUM (3-4 days)

---

### #91: Refactor circular dependency (cells/paths/tracks)
**Impact: 3 issues helped (5% of X)**

**Direct dependencies:**
- #93: Eliminate instanceof checks in AbstractPath (same architectural issue)
- #214: Pre-Wrap All Tracks at Initialization (cleaner architecture enables)
- #215: Type-Safe Dynamic References (cleaner architecture enables)

**Why important:**
- High architectural debt: cells ↔ paths ↔ tracks cycle
- Impacts testability and maintainability
- AbstractPath imports concrete cell types (DIP violation)

**Solutions:**
- Extract common interfaces to `objects/core/` package
- Visitor pattern for path operations over cells
- Facade pattern for unified objects API

**Effort:** HIGH (complex domain refactoring, requires expert consultation)

---

## Tier 3: Low Impact (1-2 issues helped)

### Quick Summary Table

| Issue | Helped Issues | Effort | Notes |
|-------|---------------|--------|-------|
| #216 | 2 (#219, #196) | LOW | JMH benchmarks foundation |
| #247 | 2 (#232, #197) | MEDIUM | Fix disabled tests (jDisco decision needed) |
| #79 | 2 (#232, #197) | LOW | InOut test coverage |
| #212 | 2 (#199, #200) | MEDIUM | Complete Phase 6 documentation |
| #214 | 2 (#215, #213) | LOW | Pre-wrap tracks (consistency) |
| #218 | 1 (#195) | LOW | Koin golden output validation |
| #62 | 1 (AnimatedSim) | MEDIUM | Bidirectional trains |

---

## Tier 4: Standalone Issues (0 dependencies)

**60% of backlog (18 issues)** don't directly help other open issues. These are:

### SonarQube Quality (5 issues)
- #245: Doubleton equals() type test
- #244: DynamicRailSwitch equals() type test
- #243: DynamicRailSemaphore equals() type test
- #242: DynamicInOut equals() type test
- #241: Remove useless if in RailwayNetGridCanvas

### Architecture (blocked by dependencies, 4 issues)
- #211: Add Transformation Validation (needs #210 first)
- #213: Eliminate Unused Grid (needs #210, #214 first)
- #215: Type-Safe references (needs #214 first)
- #93: instanceof checks (needs #91 first)

### Testing (2 issues)
- #220: Koin scope lifecycle tests
- #219: Koin perf to JMH (needs #216 first)

### Documentation/Features (7 issues)
- #175: Translation Quality Verification
- #80: GUI validation for InOut
- #78: Document 2 InOut requirement
- #77: XMLContextFactory code quality
- #60: Validation for short tracks
- #59: HashMapGraph collection views
- #58: Array2DMap modifiable EntrySet
- #37: Praha XML improvements

---

## Recommended Implementation Strategies

### Option A: Maximum Impact (Aggressive)
**Goal:** Unblock maximum work as fast as possible

**Phase 1:** #94 (Decouple simulation) → 18 issues unblocked
**Phase 2:** #61 (XML save) + #210 (Context transformation) → +7 issues
**Phase 3:** Continue with dependent issues (#211, #213, #214, #215)

**Total Impact:** 25+ issues unblocked with 3 foundational issues

**Pros:**
- Fastest path to unblock Goal 7 milestone
- Maximum parallelization opportunity for future work

**Cons:**
- #94 is complex (sim/ restrictions, jDisco considerations)
- High risk if #94 encounters blockers

---

### Option B: Balanced Progress (Recommended)
**Goal:** Mix of high-impact and quick wins

**Phase 1 (Quick wins):**
- #216: JMH benchmarks (2-3 hours)
- #79: InOut test coverage (1-2 days)
- #212: Phase 6 docs (2 days)

**Phase 2 (Medium impact):**
- #61: XML save (2-3 days) → 4 issues
- #210: Context transformation (3-4 days) → 3 issues

**Phase 3 (High impact):**
- #94: Decouple simulation (2-3 days) → 18 issues

**Pros:**
- Build momentum with easier issues first
- De-risk #94 by completing it after team has built confidence
- Early completion of documentation (#212) helps onboarding

**Cons:**
- Slower to unblock Goal 7 milestone
- Some parallelization opportunities delayed

---

### Option C: Sequential Dependencies
**Goal:** Complete dependency chains methodically

**Chain 1: Context System**
1. #210: Simplify transformation (3 issues)
2. #214: Pre-wrap tracks (2 issues)
3. #215: Type-safe references (depends on #214)
4. #211: Transformation validation (depends on #210)
5. #213: Eliminate unused grid (depends on #210, #214)

**Chain 2: Testing Infrastructure**
1. #216: JMH benchmarks (2 issues)
2. #219: Koin perf to JMH (depends on #216)
3. #247: Fix disabled tests (2 issues)

**Chain 3: Simulation Decoupling**
1. #94: Decouple simulation (18 issues)
2. Goal 7 issues (#187-200)

**Pros:**
- Logical flow, each issue builds on previous
- Clear completion criteria for each chain

**Cons:**
- Longest time to unblock Goal 7
- Less parallelization opportunity

---

## Dependency Graph

```
High-Impact Issues:
  #94 ───┬──→ Goal 7 (#187-200) [14 issues]
         ├──→ #190 (Remove System.exit)
         ├──→ #188 (SimulationRunner)
         ├──→ #215 (Type-Safe references)
         └──→ #214 (Pre-wrap tracks)

Medium-Impact Issues:
  #61 ───┬──→ #248 (XML context properties)
         ├──→ #250 (XML NodeCell names)
         ├──→ #249 (PropertyChange events)
         └──→ #79 (InOut test coverage)

  #210 ──┬──→ #211 (Transformation validation)
         ├──→ #213 (Eliminate unused grid)
         └──→ #214 (Pre-wrap tracks)

  #91 ───┬──→ #93 (instanceof checks)
         ├──→ #214 (Pre-wrap tracks)
         └──→ #215 (Type-Safe references)

Low-Impact Issues:
  #216 ──┬──→ #219 (Koin perf to JMH)
         └──→ #196 (Phase 4.2: Benchmarks)

  #247 ──┬──→ #232 (Integration tests)
         └──→ #197 (Phase 4.3: Integration)

  #214 ──┬──→ #215 (Type-Safe references)
         └──→ #213 (Eliminate unused grid)
```

---

## Impact Distribution Analysis

### By Tier

| Tier | Count | Percentage | Description |
|------|-------|------------|-------------|
| Tier 1 | 1 | 3% | Helps 10+ issues |
| Tier 2 | 3 | 10% | Helps 3-5 issues |
| Tier 3 | 8 | 27% | Helps 1-2 issues |
| Tier 4 | 18 | 60% | Standalone (0 issues) |

### Pareto Analysis

**80/20 Rule:** Top 4 issues (13% of backlog) help 28 issues (50% of all open issues)

**Top 10 Issues:** Help 38 issues (68% of all open issues)

### Effort vs. Impact Matrix

```
HIGH IMPACT
    |
    |  #94 (TIER 1)
    |  Medium-High effort
    |  ↓ Prioritize first
----+------------------------
    |  #61, #210 (TIER 2)     #91 (TIER 2)
    |  Medium effort          High effort
    |                         ↓ Evaluate ROI
    |
LOW IMPACT
    |  #216, #79, #214, #218 (TIER 3)
    |  Low effort
    |  ↓ Quick wins
    |
    +------------------------
       LOW EFFORT    HIGH EFFORT
```

---

## Recommendations by Role

### For Project Manager
**Priority:** Option B (Balanced Progress)
- Start with quick wins (#216, #79, #212) to build momentum
- Then tackle medium impact (#61, #210) for steady progress
- Finally address #94 when team is ready for complex work

### For Tech Lead
**Priority:** Option A (Maximum Impact)
- Focus on #94 immediately to unblock Goal 7
- High risk but highest reward
- Assign experienced developer familiar with sim/ restrictions

### For Individual Contributor
**Priority:** Pick issues matching your expertise
- **Context/Architecture:** #210, #214, #215, #91
- **XML/Serialization:** #61, #248, #250
- **Testing:** #216, #247, #79, #218
- **Documentation:** #212, #78, #175

---

## Success Metrics

### Impact Tracking
Monitor progress using this formula:
```
Unblocked Issues = Σ(Completed Backlog Issues × Their Helped Count)
Progress % = (Unblocked Issues / 56 Total Open Issues) × 100
```

### Milestones
- **25% Progress:** Complete #94 (18 issues unblocked)
- **50% Progress:** Complete #94 + #61 + #210 (25 issues unblocked)
- **75% Progress:** Complete top 10 backlog issues (38 issues unblocked)

---

## Conclusion

**Key Insight:** Focus beats breadth. Implementing just **#94** unblocks 32% of all open work. Adding **#61** and **#210** reaches 44-50% with only 3 issues completed.

**Recommended Next Steps:**
1. Review this analysis with team
2. Choose implementation strategy (A, B, or C)
3. Assign #94 to experienced developer
4. Track progress weekly using success metrics
5. Re-evaluate priorities after completing top 4 issues

---

**Analysis Methodology:**
- Manual dependency analysis of all 56 open issues
- Cross-referenced issue descriptions, related issues, and CLAUDE.md constraints
- Validated against Goal 7 milestone structure (#187-200)
- Considered effort estimates from issue descriptions

**Limitations:**
- Indirect dependencies may exist beyond those identified
- Effort estimates are approximate (actual may vary)
- Assumes issues can be completed independently (some may require coordination)

**Last Updated:** 2026-01-21
