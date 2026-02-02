# Changelog

All notable changes to the Railway Interlocking Simulator project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

#### Path Discovery Restructuring (Issue #292)
- **TopologyNavigator** - Static topology navigation service for pure graph traversal
  - `EditingContext.getTopologyNavigator()` - Access topology navigator from editing context
  - `TopologyNavigator.findPath()` - Find path between separators using static topology
  - `TopologyNavigator.getNextTrackSection()` - Navigate topology without state checks
  - `TopologyNavigator.findPathToNextSemaphore()` - Find path to next semaphore (static)
- **PathReservationService** - Dispatcher logic for atomic path reservation
  - `SimulationEnvironment.getPathReservationService()` - Access reservation service
  - `PathReservationService.reservePath()` - Atomically reserve path for train
  - `PathReservationService.releasePath()` - Release all blocks owned by train
  - `PathReservationService.findReservablePaths()` - Find all FREE paths between separators
  - `ReservationResult` sealed class - Success/Failure result type with reason
- **TrainNavigationService** - Train-specific navigation with ownership validation
  - `SimulationEnvironment.getTrainNavigationService()` - Access train navigation service
  - `TrainNavigationService.findReservedPathForTrain()` - Find path through owned blocks only
  - `TrainNavigationService.isPathReservedForTrain()` - Check if path is reserved for train
  - `TrainNavigationService.getReservedBlocks()` - Get all blocks owned by train
- **PathReservationRegistry** - Bidirectional train↔block ownership tracking
  - Scoped lifecycle (one registry per SimulationContext)
  - O(1) ownership queries in both directions (train → blocks, block → train)
  - Shared by PathReservationService and TrainNavigationService within same context
- **Documentation**
  - `docs/PATH_DISCOVERY_ARCHITECTURE.md` - Architecture design, rationale, trade-offs
  - `docs/PATH_DISCOVERY_MIGRATION_GUIDE.md` - Migration guide with before/after examples

### Changed

#### ShuntingLoop Simplification (Issue #296)
- **Removed** ~100 lines of manual path construction logic (`constructPath()` method)
- **Integrated** TopologyNavigator for dynamic path finding on-demand
- **Simplified** path setup using PathReservationService architecture
- Maintains backward compatibility with existing tests (35 tests passing)
- Golden output validation confirms zero behavior changes

### Deprecated

#### Path Discovery APIs (Issue #292 Phase 5)
- `SimulationEnvironment.pathToNextSemaphore(separator, next)` → Use `getTrainNavigationService().findReservedPathForTrain()` for train navigation, or `getPathReservationService().reservePath()` for dispatcher logic, or `getTopologyNavigator().findPathToNextSemaphore()` for static topology
- `SimulationEnvironment.getNextTrackSection(separator, current)` → Use `getTrainNavigationService().findReservedPathForTrain()` for train navigation, or `getTopologyNavigator().getNextTrackSection()` for static topology

**Deprecation Level**: `WARNING` (code compiles with warnings)
**Rationale**: Original methods mixed three distinct concerns (static topology, dynamic reservation, train navigation), leading to fragility and race conditions. New specialized services provide clean separation of responsibilities.
**Migration Guide**: See `docs/PATH_DISCOVERY_MIGRATION_GUIDE.md` for detailed instructions

---

## [1.0.0] - 2026-01-27

### Added

#### Koin Dependency Injection (Issue #288, 2026-01-12)
- Koin 3.5.6 framework integration (Kotlin-native, lightweight DI)
- Module organization:
  - `utilModule` - Utility classes
  - `xmlModule` - XML parsing, XMLContextFactory
  - `editingModule` - Editing context factories
  - `simulationModule` - Simulation context factories, SimulationProcessFactory
  - `navigationModule` - Navigation services (TopologyNavigator, PathReservationService, TrainNavigationService)
  - `guiModule` - Swing components (conditionally loaded)
  - `objectsModule` - Domain model (minimal by design)
- Scope-per-context pattern for lifecycle management
- AutoCloseable pattern for resource cleanup (`Context.close()`)
- Full documentation in `KOTLIN-MIGRATION-STATUS.md` and `docs/KOTLIN_STYLE_GUIDE.md`

#### Context System Refactoring (Issues #98, #153, #94)
- **Issue #98 (2026-01-14)**: Split DefaultContext into DefaultEditingContext and DefaultSimulationContext
- **Issue #153 (2026-01-20)**: Composition over inheritance - BaseContext abstract base class
  - Network immutability enforcement via `freeze()` mechanism
  - Both EditingContext and SimulationContext extend BaseContext independently
  - Interface Segregation Principle (SimulationContext no longer extends EditingContext)
  - ContextTransformer factory for editing→simulation transformation
- **Issue #94 (2026-01-21)**: SimulationEnvironment facade interface
  - Decouples sim/ classes from full SimulationContext contract
  - 11 essential methods grouped by concern (network queries, state management, simulation control)
  - Enables future DSOL/Kalasim migration via adapter pattern

#### Static/Dynamic Separation (Issue #100)
- Wrapper pattern separates static configuration from dynamic simulation state
- `Context.toDynamic(track)` conversion API
- See `docs/STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md`

#### Grid Parameterization (Issue #131)
- Type-safe grid with parameterized cell types (`RailwayNetGrid<out T : Cell>`, `Context<out C : Cell>`)
- Covariant return types for EditingContext and SimulationContext
- See `docs/GRID_PARAMETERIZATION_*.md`

#### Kotlin Migration (2026-01)
- 100% of 94 files migrated from Java to Kotlin
- Conservative structure-preserving approach
- Full test parity (242 tests passing)
- Physics calculations validated against Java baseline (tolerance: 1e-9s, 1e-6m)
- Full jDisco interoperability maintained

#### Build System
- Gradle with Kotlin DSL (replaces Ant)
- Java 21 LTS minimum version (migrated from Java 11)
- Shadow JAR plugin for uber JAR creation
- GitHub Actions CI/CD integration
- Docker support for containerized builds

#### Code Quality
- SonarQube integration with JaCoCo coverage (51% coverage, 662 tests)
- Detekt (Kotlin static analysis) with dual-level configuration:
  - `detekt.yml` - Permissive rules for legacy Java→Kotlin converted code
  - `detekt-strict.yml` - Strict rules for new Kotlin code
- Ktlint (Kotlin formatting) respecting `.editorconfig` tab indentation
- See `docs/KOTLIN_STYLE_GUIDE.md`

#### Logging
- kotlin-logging (SLF4J wrapper) with Logback backend
- Lambda-based lazy evaluation for performance
- Runtime log level override support

### Changed

#### Build Configuration
- Build tool: Ant → Gradle with Kotlin DSL
- Java version: 11 → 21 LTS
- Dependency management: Manual JAR files → Gradle with Maven repositories
- jDisco extracted to separate repository: https://github.com/bedaHovorka/jdisco

#### API Changes
- `java.util.Observable/Observer` → `PropertyChangeSupport/PropertyChangeListener` (Issue #167)
- Logging: SLF4J → kotlin-logging (wrapper for SLF4J with Kotlin idioms)

#### Project Structure
- Main source: `src/main/java/` (Kotlin files, legacy structure preserved)
- Test source: `src/test/java/` → `src/test/kotlin/` (Kotlin tests)
- Documentation: `docs/` directory added with architecture guides

### Deprecated

- `DefaultContext` class - Use `DefaultEditingContext` or `DefaultSimulationContext` instead (Issue #98)

### Removed

- Ant build system and `build.xml` (replaced by Gradle)
- Java 11 compatibility (minimum is now Java 21 LTS)
- `java.util.Observable/Observer` API (replaced by PropertyChangeSupport)

### Fixed

#### Train Deadlock (Issue #280)
- Fixed train deadlock in path reservation cache
- Migrated from static cells to dynamic wrappers (DynamicInOut, DynamicRailSemaphore, DynamicRailSwitch)
- Updated grid coordinate lookups to retrieve dynamic wrappers
- All paths now use dynamic wrappers for consistent identity

#### Block Ownership Validation (Issue #282)
- Added block reservation validation before navigation
- Prevents trains from navigating into blocks not reserved for them
- Validation logic: blocks must be RESERVED from correct separator or OCCUPIED

### Security

- No known security vulnerabilities

---

## [0.1.0] - 2007-01-01

Initial BSc thesis release (Brno University of Technology, 2006/2007)

### Added
- Railway interlocking simulator with discrete event simulation
- Graphical editor for railway network construction
- XML-based network configuration (`data.xsd` schema)
- jDisco discrete event simulation framework integration
- Example scenarios (shunting loop - `vyhybna.xml`)
- Swing-based GUI with track layout editor
- Track facilities: switches, semaphores, entry/exit points (InOut)
- Train physics simulation (acceleration, deceleration, braking)
- Path reservation and block management
- Static/dynamic track block representation

---

## Version History

- **[Unreleased]**: Path Discovery Restructuring (Issue #292), Phase 5 complete
- **[1.0.0]** - 2026-01-27: Kotlin migration, Koin DI, context refactoring, build system modernization
- **[0.1.0]** - 2007-01-01: Initial BSc thesis release

---

## Migration Notes

### Upgrading from 0.1.0 to 1.0.0

**Breaking Changes**:
- Java 11 → Java 21 LTS required
- Ant → Gradle build system (use `./gradlew build` instead of `ant`)
- `java.util.Observable/Observer` removed (migrate to `PropertyChangeSupport`)
- `DefaultContext` deprecated (use `DefaultEditingContext` or `DefaultSimulationContext`)

**Recommended Steps**:
1. Update JDK to version 21 LTS or later
2. Replace Ant build commands with Gradle equivalents (see `CLAUDE.md` for commands)
3. Update code using `Observable/Observer` to `PropertyChangeSupport/PropertyChangeListener`
4. Replace `DefaultContext` usage with `DefaultEditingContext` or `DefaultSimulationContext`
5. Run full test suite: `./gradlew test` (662 tests should pass)

### Migrating from Deprecated Path Discovery APIs

**Deprecation Timeline**: Issue #292 Phase 5 (2026-01-27)

Old APIs (`pathToNextSemaphore`, `getNextTrackSection`) are deprecated with `WARNING` level. Code still compiles but shows deprecation warnings.

**Migration Scenarios**:
1. **Static topology navigation** → Use `context.getTopologyNavigator().findPathToNextSemaphore()`
2. **Dispatcher finding routes** → Use `env.getPathReservationService().reservePath()`
3. **Train navigation** → Use `env.getTrainNavigationService().findReservedPathForTrain()`

See `docs/PATH_DISCOVERY_MIGRATION_GUIDE.md` for detailed migration instructions with before/after examples.

---

## Contributing

See `TEAM.md` for agent roles and decision authority hierarchy when contributing to this project.

---

## License

This project is part of a BSc thesis from Brno University of Technology (2006/2007).
