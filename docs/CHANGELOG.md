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
