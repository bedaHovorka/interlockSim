# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Graph Parameterization** (#277): Context graph parameterized with `T extends TrackBlock`
  - EditingContext: `ExtendedUnorientedGraph<Point, TrackBlock, Segment>` (static configuration)
  - SimulationContext: `ExtendedUnorientedGraph<Point, DynamicTrackBlock, Segment>` (dynamic state)
  - Type-safe single-step access to track state during simulation
  - DynamicTrackBlock wrapper with state machine (FREE/RESERVED/OCCUPIED)
  - Documentation: `docs/GRAPH_PARAMETERIZATION_ARCHITECTURE.md`
  - Test coverage: 28 new tests (15 unit, 8 transformation, 5 integration)

- **AnimatedSim Milestone** (#201-#207): Real-time GUI visualization for railway simulation
  - **Animation Infrastructure** (#201): Foundation for animated visualization
    - AnimationController: 30 FPS rendering with EDT thread safety
    - AnimationState: Immutable snapshot of simulation state
    - AnimationStateCapture: State extraction from simulation context
    - TrainPositionCalculator: Grid coordinate calculation for train rendering
  - **Enhanced Cell Rendering** (#202): Dynamic track state visualization
    - AnimatedSimulationCellRenderer: Color-coded track states (FREE/RESERVED/OCCUPIED)
    - Semaphore signal visualization (STOP/ALLOW)
    - Enhanced grid canvas with animation support
  - **Train Overlay Rendering** (#203): Real-time train visualization
    - Train position interpolation for smooth movement
    - Speed and direction indicators
    - Multi-train support architecture
  - **Event Timeline Panel** (#204): Simulation event logging
    - Real-time event capture and display
    - Filtering by event type (TRAIN_EVENTS, NODE_EVENTS, TRAIN_CONTINUOUS)
    - Timestamp synchronization with simulation clock
  - **Frame Integration** (#205): GUI component orchestration
    - AnimationController lifecycle management
    - EventTimelinePanel integration
    - ControlPanel for simulation control
    - Proper cleanup on context switching
  - **exampleGui Command** (#206): CLI entry point for animated examples
    - `./gradlew runExampleGui -PexampleName=shuntingLoop -PendTime=60`
    - ExampleRegistry integration for GUI example factories
    - Proper threading model: EDT for GUI, background for simulation
  - **Real-Time Synchronization** (#207, PR #274): Smooth animation timing
    - Conditional RealTimeSynch for GUI examples
    - 1:1 simulation time to wall-clock time mapping
    - Backward compatible: console examples run without sync (fast)
  - Documentation: `docs/ANIMATED_SIM_MILESTONE_PREP.md`, `docs/ANIMATED_SIM_SIMPLIFICATION_ANALYSIS.md`
  - Test coverage: 1,246 lines of animation tests across 7 test classes
  - Progress: 7/9 issues complete (77.8%), on track for 2026-01-29 deadline

### Changed
- Context interface: Added type parameter `T : TrackBlock` for graph parameterization
- BaseContext: Now parameterized as `abstract class BaseContext<T : TrackBlock>`
- DefaultSimulationContext: Wraps TrackBlock in DynamicTrackBlock during transformation
- ContextTransformer: Performs static-to-dynamic graph transformation

### Fixed
- Eliminates unchecked casts when accessing track blocks from simulation graph
- Provides compile-time type safety for graph operations
- Simplifies track state access for simulation processes and future visualization

## Technical Details
- **Related Issues**: #277 (this change), #131 (grid parameterization), #275 (unblocked)
- **Breaking Changes**: None (internal API only, XML compatibility maintained)
- **Test Coverage**: All 1438 tests passing (1410 existing + 28 new), zero regressions
- **Migration**: Existing code continues to work unchanged, new code can use type-safe graph access

## [1.0.0] - 2026-01

### Added
- Initial Kotlin migration (94 files, 100% complete)
- Gradle build system replacing Ant
- JUnit 5 test suite with AssertK assertions
- Docker containerization for build and runtime
- Dependency injection with Koin 3.5.6
- Comprehensive documentation and coding guidelines

### Changed
- Migrated from Java to Kotlin
- Updated from Java 11 to Java 21 LTS
- Replaced Observable with PropertyChangeSupport
- Migrated logging to kotlin-logging with SLF4J/Logback

### Removed
- Ant build system
- Java Observable/Observer pattern
- Legacy jDisco source code (now external dependency)
