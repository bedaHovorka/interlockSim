# Documentation Index

Index of everything under `docs/`. Entries marked "(history, YYYY-MM)" (the month comes from the file's own date or from git
history; otherwise just "(history)") are records of finished work: they are kept for context and are not maintained. Entries
under "Goal 10 reports (dated)" record a measurement or ruling at a point in time and are kept verbatim. All other entries are
living documents. Project-wide rules live in the root [CLAUDE.md](../CLAUDE.md) and [TEAM.md](../TEAM.md).

Directories covered as a single history entry (their files are not listed one by one):
`superpowers/plans/`, `superpowers/specs/`, `election/`, `issues/`. The `diagrams/` and
`images/` directories are covered by their owner entries below.

## Guides and conventions

- [KOTLIN_STYLE_GUIDE.md](KOTLIN_STYLE_GUIDE.md) - coding conventions, DI with Koin, test fixtures, build environment.
- [FEDORA_DOCKER_X11_SETUP.md](FEDORA_DOCKER_X11_SETUP.md) - running GUI containers on Fedora with SELinux and X11.
- [SP1_5_OLLAMA_EXECUTOR_SETUP.md](SP1_5_OLLAMA_EXECUTOR_SETUP.md) - configuring the local Ollama executor for the Goal 10 dispatcher.
- [CZECH_RAILWAY_TERMINOLOGY.md](CZECH_RAILWAY_TERMINOLOGY.md) - Czech-to-English railway term reference and translation guide.
- [TRAIN_PUBLIC_API_USAGE.md](TRAIN_PUBLIC_API_USAGE.md) - how to use the public `Train` API from animation and observers.
- [TRAIN_PUBLIC_API_QUICK_REF.md](TRAIN_PUBLIC_API_QUICK_REF.md) - quick reference of the property-style `Train` accessors.
- [PATH_DISCOVERY_MIGRATION_GUIDE.md](PATH_DISCOVERY_MIGRATION_GUIDE.md) - migrating from the deprecated mixed-concern path APIs to the path discovery services.
- [MANUAL_TEST_PLAN_GOAL_3.md](MANUAL_TEST_PLAN_GOAL_3.md) - manual test plan for Goal 3 (collision detection).
- [KOIN_SCOPE_LIFECYCLE_TESTS.md](KOIN_SCOPE_LIFECYCLE_TESTS.md) - what the Koin scope lifecycle tests cover and how they work.

## Architecture

- [PATH_RESERVATION_ARCHITECTURE.md](PATH_RESERVATION_ARCHITECTURE.md) - path reservation, signal clearing invariants, route release.
- [PATH_DISCOVERY_ARCHITECTURE.md](PATH_DISCOVERY_ARCHITECTURE.md) - separated static, reservation, and navigation path APIs.
- [STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md](STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md) - separation of the static track model from dynamic simulation state.
- [GRAPH_PARAMETERIZATION_ARCHITECTURE.md](GRAPH_PARAMETERIZATION_ARCHITECTURE.md) - graph parameterization architecture (issue #277).
- [ANIMATION_ARCHITECTURE.md](ANIMATION_ARCHITECTURE.md) - animated simulation GUI architecture.
- [CONTEXT_REFACTORING_DESIGN.md](CONTEXT_REFACTORING_DESIGN.md) - editing/simulation context split design and its history.
- [INTERLOCKING_SCOPE_LIMITATIONS.md](INTERLOCKING_SCOPE_LIMITATIONS.md) - deliberate interlocking simplifications.
- [ENGINE_CONTINUOUS_RATIONALE.md](ENGINE_CONTINUOUS_RATIONALE.md) - why `Engine` extends `Continuous`.
- [SIMULATION_SPEED_CONTROL.md](SIMULATION_SPEED_CONTROL.md) - live wall-clock speed control for the animated simulation.
- [FAST_SIM_BENCHMARK.md](FAST_SIM_BENCHMARK.md) - native versus JVM performance benchmark of the fast-sim binary (history, 2026-03; a measurement at that date, #418).
- [diagrams/README.md](diagrams/README.md) - PlantUML sources (`diagrams/*.puml`) and rendered images of the context architecture.
- [grid-parameterization-architecture.puml](grid-parameterization-architecture.puml) - PlantUML class diagram of the grid parameterization design.
- [grid-transformation-flow.puml](grid-transformation-flow.puml) - PlantUML flow of the editing-to-simulation grid transformation.

## Grid parameterization (2026-01 design set)

Start with [GRID_PARAMETERIZATION_README.md](GRID_PARAMETERIZATION_README.md), the navigation guide for this set (history, 2026-01).

- [GRID_TRANSFORMER_VS_INIT_MAPPING.md](GRID_TRANSFORMER_VS_INIT_MAPPING.md) - relationship between `GridTransformer.transformGrid()` and `initializeDynamicMapping()`.
- [GRID_PARAMETERIZATION_INDEX.md](GRID_PARAMETERIZATION_INDEX.md) - earlier index of the design documents (history, 2026-01).
- [GRID_PARAMETERIZATION_SUMMARY.md](GRID_PARAMETERIZATION_SUMMARY.md) - executive summary of the design (history, 2026-01).
- [GRID_PARAMETERIZATION_DESIGN.md](GRID_PARAMETERIZATION_DESIGN.md) - full design for issue #139 (history, 2026-01).
- [GRID_TRANSFORMATION_DESIGN.md](GRID_TRANSFORMATION_DESIGN.md) - transformation algorithm design (history, 2026-01).
- [GRID_PARAMETERIZATION_IMPLEMENTATION.md](GRID_PARAMETERIZATION_IMPLEMENTATION.md) - implementation summary for issue #131 (history, 2026-01).
- [GRID_TRANSFORMER_TEST_FIXES.md](GRID_TRANSFORMER_TEST_FIXES.md) - list of `GridTransformerTest` compilation fixes (history, 2026-01).

## Goal 10 reports (dated)

- [GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md](GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md) - LLM model evaluation for the dispatcher agent role (SP3.1, #534).
- [GOAL_10_SP2C14_RELIABILITY_REPORT.md](GOAL_10_SP2C14_RELIABILITY_REPORT.md) - dispatcher reliability report (SP2c.14, #837).
- [GOAL_10_SP2C15_FRONTIER_DIAGNOSTIC_SETUP.md](GOAL_10_SP2C15_FRONTIER_DIAGNOSTIC_SETUP.md) - setup for the frontier-model diagnostic run (SP2c.15, #838, 2026-08).
- [GOAL_10_SP2C25_DECISION_VOCABULARY_AUDIT.md](GOAL_10_SP2C25_DECISION_VOCABULARY_AUDIT.md) - `RuleBasedDispatcher` decision-vocabulary audit (SP2c.25, #848).
- [GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md](GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md) - paused-clock feasibility and headless-pacing ruling (SP2c.26, #849, 2026-08).
- [GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md](GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md) - Ollama capability audit: seed, format and tools, `num_ctx`, `maxIterations` (SP2c.27, #850).

## Goal 9B demand list

- [goal9b-demands/SP1-fail-fast-harness.md](goal9b-demands/SP1-fail-fast-harness.md) - reservation cost per entry attempt and attempt counts on Praha (Goal 1B SP1, #1148).
- [goal9b-demands/SP5-target-selection-path.md](goal9b-demands/SP5-target-selection-path.md) - what a dispatcher still cannot express through `CandidateTarget` (cost, destination, unavailability reason, horizon, time), recorded as Goal 9B demands (Goal 1B SP5, #1152).
- [goal9b-demands/SP9-kdisco-review.md](goal9b-demands/SP9-kdisco-review.md) - kDisco deadlock and race review, scanner survey, and the Goal 9B engine-guarantee demands (Goal 1B SP9, #1156, 2026-10).

## History

Dated summaries, retrospectives, decisions, and reports. Kept for context; do not treat as current.

### AnimatedSim milestone

- [ANIMATED_SIM_MILESTONE_PREP.md](ANIMATED_SIM_MILESTONE_PREP.md) - milestone preparation analysis (history, 2026-01).
- [ANIMATED_SIM_SIMPLIFICATION_ANALYSIS.md](ANIMATED_SIM_SIMPLIFICATION_ANALYSIS.md) - which backlog issues simplify the milestone (history, 2026-01).
- [ANIMATED_SIM_STATUS_MEETING_2026_02_04.md](ANIMATED_SIM_STATUS_MEETING_2026_02_04.md) - team status meeting (history, 2026-02).
- [ANIMATED_SIM_MILESTONE_COMPLETE.md](ANIMATED_SIM_MILESTONE_COMPLETE.md) - milestone completion report (history, 2026-02).
- [ISSUE_273_TEST_EXECUTION_REPORT.md](ISSUE_273_TEST_EXECUTION_REPORT.md) - manual testing and quality verification report, issue #273 (history, 2026-02).
- [IMPLEMENTATION_SUMMARY_ISSUE_205.md](IMPLEMENTATION_SUMMARY_ISSUE_205.md) - Frame integration of animation components, issue #205 (history, 2026-02).
- [MANUAL_TEST_PLAN_ISSUE_205.md](MANUAL_TEST_PLAN_ISSUE_205.md) - manual test plan for issue #205 (history, 2026-02).

### Context and factory refactoring

- [CONTEXT_INHERITANCE_INCOMPATIBILITY.md](CONTEXT_INHERITANCE_INCOMPATIBILITY.md) - why `SimulationContext` cannot extend `EditingContext`, issue #153 (history, 2026-01).
- [ISSUE_153_RETROSPECTIVE.md](ISSUE_153_RETROSPECTIVE.md) - retrospective of issue #153 (history, 2026-01).
- [CONTEXT_REFACTORING_PHASE6_SUMMARY.md](CONTEXT_REFACTORING_PHASE6_SUMMARY.md) - phase 6 summary of the context refactoring (history, 2026-02).
- [FACTORY_PATTERN_IMPLEMENTATION.md](FACTORY_PATTERN_IMPLEMENTATION.md) - factory pattern implementation summary (history, 2026-01).
- [ISSUE_214_IMPLEMENTATION_SUMMARY.md](ISSUE_214_IMPLEMENTATION_SUMMARY.md) - pre-wrapping all tracks at initialization, issue #214 (history, 2026-02).
- [ISSUE_214_VISUAL_EXPLANATION.md](ISSUE_214_VISUAL_EXPLANATION.md) - before/after diagram of the track wrapping strategy (history, 2026-02).
- [IMPLEMENTATION_SUMMARY_ISSUE_220.md](IMPLEMENTATION_SUMMARY_ISSUE_220.md) - Koin scope lifecycle tests, issue #220 (history, 2026-02).
- [ISSUE_219_IMPLEMENTATION.md](ISSUE_219_IMPLEMENTATION.md) - migrating the Koin performance test to JMH, issue #219 (history).
- [MOCKK_MIGRATION_PHASE4_RETROSPECTIVE.md](MOCKK_MIGRATION_PHASE4_RETROSPECTIVE.md) - MockK migration phase 4 retrospective, issue #332 (history, 2026-02).
- [JAVA21-MIGRATION-SUMMARY.md](JAVA21-MIGRATION-SUMMARY.md) - Java 11 to Java 21 migration summary (history, 2026-01).

### Fixes, tests, and implementation summaries

- [CHANGELOG.md](CHANGELOG.md) - early changelog fork, last updated 2026-02; superseded by the root [CHANGELOG.md](../CHANGELOG.md), which is the maintained one (history, 2026-02).
- [TRAIN_PUBLIC_API_IMPLEMENTATION.md](TRAIN_PUBLIC_API_IMPLEMENTATION.md) - technical summary of the public `Train` API (history, 2026-02).
- [TRAIN_PASSIVATION_FIX.md](TRAIN_PASSIVATION_FIX.md) - train stops completely when no path is available (history).
- [SIGNAL_CONFIG_ROLLBACK_FIX.md](SIGNAL_CONFIG_ROLLBACK_FIX.md) - signal configuration rollback bug fix (history, 2026-03).
- [RESOURCE_LEAK_IMPROVEMENTS.md](RESOURCE_LEAK_IMPROVEMENTS.md) - exception safety and resource leak improvements (history, 2026-01).
- [PRAHA_SWITCH_IMPROVEMENTS.md](PRAHA_SWITCH_IMPROVEMENTS.md) - switch layout improvements for the Praha hlavní nádraží XML (history, 2026-02).
- [PR_306_IMPLEMENTATION_SUMMARY.md](PR_306_IMPLEMENTATION_SUMMARY.md) - review points implemented for PR #306 (history).
- [INTEGRATION_TESTS_SUMMARY.md](INTEGRATION_TESTS_SUMMARY.md) - end-to-end integration test implementation summary (history).
- [TEST_COVERAGE_SUMMARY.md](TEST_COVERAGE_SUMMARY.md) - context package coverage improvement summary (history).
- [TEST_COVERAGE_POLISH.md](TEST_COVERAGE_POLISH.md) - coverage polish for cells, tracks, and xml packages (history).
- [ISSUE_280_ANALYSIS_PLAN.md](ISSUE_280_ANALYSIS_PLAN.md) - analysis plan for the zero-acceleration train deadlock, issue #280 (history, 2026-01).

### Planning and backlog analysis

- [BACKLOG_PRIORITY_ANALYSIS.md](BACKLOG_PRIORITY_ANALYSIS.md) - backlog prioritization by dependency impact (history, 2026-01).
- [backlog-dependency-graph.md](backlog-dependency-graph.md) - dependency graph of backlog issues (history, 2026-01).

### Simulation library decision (2026-02)

Superseded: the Kalasim migration plan was dropped on 2026-08-24; kDisco is the engine.

- [SIMULATION_LIBRARY_DECISION.md](SIMULATION_LIBRARY_DECISION.md) - jDisco to Kalasim decision, round 1 (history, 2026-02).
- [SIMULATION_LIBRARY_DECISION_ROUND2.md](SIMULATION_LIBRARY_DECISION_ROUND2.md) - migration road selection, round 2 (history, 2026-02).
- [SIMULATION_LIBRARY_DECISION_VERIFICATION.md](SIMULATION_LIBRARY_DECISION_VERIFICATION.md) - calculation verification of the decision documents (history, 2026-02).
- [VERIFICATION_SUMMARY.md](VERIFICATION_SUMMARY.md) - summary of that verification (history, 2026-02).
- [DECISION_AUDIT_AND_EXPERTISE.md](DECISION_AUDIT_AND_EXPERTISE.md) - independent audit of both decision rounds (history, 2026-02).
- [SIMULATION_LIBRARY_ROAD_MAP.md](SIMULATION_LIBRARY_ROAD_MAP.md) - jDisco to kDisco to Kalasim roadmap (history, 2026-02).
- [simulation-approach-analysis.md](simulation-approach-analysis.md) - simulation backend approach analysis (history).
- [jdisco-research.md](jdisco-research.md) - research report on the jDisco library (history).

### Directories (history, indexed as one entry each)

- `superpowers/plans/` - dated implementation plans, 2026-03 to 2026-06 (history).
- `superpowers/specs/` - dated design specs that accompany those plans, 2026-03 to 2026-06 (history).
- `election/` - the 2026-04-14 backlog election record (history, 2026-04).
- `issues/` - write-ups of individual resolved or investigated issues (#80, #291, #311), with its own README (history, 2026-02 to 2026-03).
