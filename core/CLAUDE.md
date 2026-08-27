# CLAUDE.md — :core

**Last Updated:** 2026-08-27

Guidance for Claude Code when working in the `:core` subproject. Repo-wide rules
(English-only output, heavy-test policy, Koin rules, code quality) live in the
[root CLAUDE.md](../CLAUDE.md) and are not repeated here.

`:core` is the Kotlin Multiplatform heart of the simulator: domain model,
simulation engine integration, and XML layer. Targets: `jvm` (primary) and
`linuxX64` (built only on a Linux host).

## Build and Test

```bash
./gradlew :core:jvmTest                     # Unit tests (excludes integration/heavy tags)
./gradlew :core:allTests                    # All targets, incl. linuxX64Test on Linux
./gradlew :core:integrationTest             # @Tag("integration-test"), single fork
./gradlew :core:heavyTest                   # Manual only — see root CLAUDE.md testing policy
./gradlew :core:checkCoreCommonMainPurity   # commonMain purity gate (part of check)
```

- **Purity gate:** `checkCoreCommonMainPurity` fails the build if
  `src/commonMain/kotlin` references `java.*`, `javax.*`, `android.*`, or
  `System.*`. Move such usages to `jvmMain` actuals.
- **`linuxX64Test` JUnit XML reports are disabled** in `core/build.gradle.kts` — a
  workaround for a Gradle × Kotlin/Native race
  ([gradle/gradle#33990](https://github.com/gradle/gradle/issues/33990), KT-69896)
  where concurrent stdout/stderr forwarding corrupts Gradle's binary test-result
  index (`Multiple entries with same key`) even when all tests pass. Do not
  re-enable without confirming the upstream fix.
- **`generateNativeResourceRoot`** generates `NATIVE_RESOURCE_ROOTS` with
  **absolute** paths to the resource directories of `:core` and `:core-test`.
  A relocated native binary fails at its first `Resources.read()` — a known
  limitation (see [fast-sim/CLAUDE.md](../fast-sim/CLAUDE.md)).

## Architecture

**Simulation engine:** kDisco (`cz.ksimulantenbande.kdisco:kdisco-core`;
version comes from `kdiscoVersion` in `gradle.properties`). See the root CLAUDE.md
"Simulation Engine" section for the engine policy and the kDisco repository link.

**Context system:**
- `Context<out C : Cell>` — base abstraction for a railway network configuration
- `EditingContext : Context<NodeCell>` and `SimulationContext : Context<Cell>, SimulationEnvironment`
- `BaseContext` — shared infrastructure; `DefaultEditingContext` /
  `DefaultSimulationContext` extend it independently
- `ContextTransformer` — factory for the editing→simulation transformation
- `XMLContextFactory` (jvmMain) — creates contexts from XML files
- History (Issues #98, #153, #94): [../docs/CONTEXT_REFACTORING_DESIGN.md](../docs/CONTEXT_REFACTORING_DESIGN.md),
  [../docs/ISSUE_153_RETROSPECTIVE.md](../docs/ISSUE_153_RETROSPECTIVE.md)

**Navigation services** (replaced the removed `pathToNextSemaphore()` API):
- `TopologyNavigator` — static topology navigation (pure graph traversal)
- `PathReservationService` — dispatcher logic: find FREE paths, reserve atomically
- `TrainNavigationService` — train-side navigation: follow RESERVED paths only
- `PathReservationRegistry` — O(1) train↔block ownership tracking, one per context

Details and Koin wiring: [../docs/KOTLIN_STYLE_GUIDE.md](../docs/KOTLIN_STYLE_GUIDE.md)
("Project Architecture Context"),
[../docs/PATH_DISCOVERY_ARCHITECTURE.md](../docs/PATH_DISCOVERY_ARCHITECTURE.md),
[../docs/PATH_RESERVATION_ARCHITECTURE.md](../docs/PATH_RESERVATION_ARCHITECTURE.md).

**Object model:** `objects/tracks/` (track facilities, blocks, occupants),
`objects/cells/` (grid-based spatial representation, `Array2DMap`),
`objects/paths/` (route management). Static/dynamic wrapper pattern:
[../docs/STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md](../docs/STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md).
Intentional interlocking simplifications:
[../docs/INTERLOCKING_SCOPE_LIMITATIONS.md](../docs/INTERLOCKING_SCOPE_LIMITATIONS.md).

## Package Structure

```
core/src/
├── commonMain/kotlin/cz/vutbr/fit/interlockSim/
│   ├── context/       - Context management and factories (+ context/navigation)
│   ├── di/            - Koin modules
│   ├── domain/        - Domain value types
│   ├── exceptions/    - Domain exceptions
│   ├── lang/          - Dispatcher vocabulary (lang/vocab)
│   ├── objects/       - Domain model (tracks, cells, paths)
│   ├── pathfinding/   - Route search
│   ├── ports/         - Ports for the dispatcher seam
│   ├── sim/           - Simulation engine and scenarios (+ collision, conflict, events, metrics)
│   ├── util/          - Utilities
│   └── xml/           - XML model (expect declarations)
├── commonTest/kotlin/ - Multiplatform tests
├── jvmMain/kotlin/    - JVM actuals: context/, util/, xml/ (parsing, factories)
├── jvmTest/kotlin/    - JVM-only tests, incl. testutil/
└── nativeMain/, nativeInterop/ - linuxX64 actuals and libxml2 interop
```

## XML Configuration

- Schema: `core/src/commonMain/resources/cz/vutbr/fit/interlockSim/resource/data.xsd`
- Bundled example: `vyhybna.xml` (shunting loop), in the same resource directory.
  Larger fixture networks (for example `praha-hlavni-nadrazi.xml`) live in
  [:core-test](../core-test/CLAUDE.md) under `src/commonMain/resources/.../xml/fixtures/`.
- Elements: RailSwitch, RailSemaphore, InOut (entry/exit points)
- **InOut minimum:** every network needs at least 1 InOut. With bidirectional
  operation (PR #356) one InOut can serve as both entry and exit.
  `XMLContextFactory` validates this during parse; the editor blocks saving
  contexts below the minimum (Issues #79, #80).

## sim/ Package Rules (Critical)

- **Minimal changes only** — be extremely conservative with simulation logic.
- **Refactoring needs approval** — do not restructure working simulation code
  on your own; only small refactorings are possible, and only after
  traffic-simulation-expert approval.
- **Tests required** — any change MUST have comprehensive test coverage first.
- **No unsolicited improvements** — only make explicitly requested changes.
- **Koin injection allowed** since 2026-03-20 (kDisco Phase 1 migration complete).
- **Never modify kDisco** — it is maintained as a separate project.

## Known Issues

- **SIM-004:** `ShuntingLoop` is the `vyhybna.xml` scenario by design, but it
  hardcodes that topology. The planned fix is to generalize `ShuntingLoop` into
  a `Station` abstraction (and clean up the hardcoded assumptions on the way) —
  Goal 1B scope, see `LONG_TERM_GOALS.md` and #591.
  Minor simulation issues SIM-001 to SIM-006 are documented in code comments.
- **DEFERRED-001:** XMLContextFactoryTest is missing exception type predicates.
- Train-physics passivation fix (Issue #291) is documented in
  [../docs/TRAIN_PASSIVATION_FIX.md](../docs/TRAIN_PASSIVATION_FIX.md) — a
  `sim/` change must not regress it.
