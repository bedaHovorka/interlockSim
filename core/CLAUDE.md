# CLAUDE.md — :core

**Last Updated:** 2026-10-29

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
./gradlew :core:docLineAnchorCheck          # doc line-anchor tripwire (docs/**/*.md)
```

- **Purity gate:** `checkCoreCommonMainPurity` fails the build if
  `src/commonMain/kotlin` references `java.*`, `javax.*`, `android.*`, or
  `System.*`. Move such usages to `jvmMain` actuals.
- **Doc line anchors are forbidden:** `docLineAnchorCheck` runs `DocLineAnchorTripwireTest`
  (`jvmTest/.../docs/`), which scans `docs/**/*.md` and fails on any `Name.kt:123` anchor.
  Reference code by symbol instead.
- **`linuxX64Test` JUnit XML reports are disabled** in `core/build.gradle.kts` — a
  workaround for a Gradle × Kotlin/Native race
  ([gradle/gradle#33990](https://github.com/gradle/gradle/issues/33990), KT-69896)
  where concurrent stdout/stderr forwarding corrupts Gradle's binary test-result
  index (`Multiple entries with same key`) even when all tests pass. Do not
  re-enable without confirming the upstream fix.
  The same `Could not write XML test results` symptom has been seen on JVM test
  tasks: on `:desktop-ui`/`:dispatcher-agent` `integrationTest` with 0 real
  failures ([#1011](https://github.com/bedaHovorka/interlockSim/issues/1011)), and
  on 2026-10-01 under heavy machine load (load average ~90) on
  `:core:integrationTest` and `:desktop-ui:test` (that `:desktop-ui:test` run also
  had one real, load-induced timeout failure).
  **Test logging therefore stays on one console stream** (#1011): every
  `logback-test.xml` sends its console output to stdout only — the former
  `System.err` twin wrote each ERROR event twice, and Gradle drains a test JVM's
  two streams with two forwarding threads. That concurrency is the suspect
  mechanism behind the JVM XML failures — upstream only proves it for Kotlin test
  tasks, so for JVM `Test` tasks it is a probable cause and the single-stream
  policy is a precaution, not a confirmed fix. A per-module
  `TestLoggingSingleStreamTest` tripwire fails if a stderr appender reappears
  (desktop-ui's stdout `SPIKE_MEASUREMENTS` appender for the SP2c.26 F1 spike
  tests is unaffected). Gate evidence is still taken from a complete re-run's
  XML, never from a failed attempt.
- **`clean` is ordered before other build tasks** by the root build script (#1011);
  the aggregate `cleanAllTests` task is excluded to avoid a task-graph cycle.
  With `org.gradle.parallel=true`, Gradle gives no ordering between one project's
  `clean` and another project's work, so a `clean build …` gate could compile
  `:core-test`/`:dispatcher-agent` against `:core` jvm classes a `clean` was
  deleting (`Unresolved reference: SimulationContext`). The `mustRunAfter` block in
  the root `build.gradle.kts` supplies the ordering no dependency edge can express.
- **`generateNativeResourceRoot`** generates `NATIVE_RESOURCE_ROOTS` with
  **absolute** paths to the resource directories of `:core` and `:core-test`.
  A relocated native binary fails at its first `Resources.read()` — a known
  limitation (see [fast-sim/CLAUDE.md](../fast-sim/CLAUDE.md)).

## Architecture

**Simulation engine:** kDisco (`cz.ksimulantenbande.kdisco:kdisco-core`;
version comes from `kdiscoVersion` in `gradle.properties`). See the root CLAUDE.md
"Simulation Engine" section for the engine policy and the kDisco repository link.

**Context system:**
- `Context<out C : Cell, T : TrackBlock>` — base abstraction for a railway network configuration
- `EditingContext : Context<AbstractCell, TrackBlock>, NetworkState` and
  `SimulationContext : Context<Cell, DynamicTrackBlock>, SimulationEnvironment`
- `BaseContext` — shared infrastructure; `DefaultEditingContext` /
  `DefaultSimulationContext` extend it independently
- `ContextTransformer` — factory for the editing→simulation transformation
- `XMLContextFactory` (jvmMain) — creates contexts from XML files
- History (Issues #98, #153, #94): [../docs/CONTEXT_REFACTORING_DESIGN.md](../docs/CONTEXT_REFACTORING_DESIGN.md),
  [../docs/ISSUE_153_RETROSPECTIVE.md](../docs/ISSUE_153_RETROSPECTIVE.md)

**Navigation services** (`context/navigation`; replaced the removed `pathToNextSemaphore()` API),
reached through the `RoutingServices` facade (`SimulationEnvironment.getRoutingServices()`),
which has five getters:
- `TopologyNavigator` — static topology navigation (pure graph traversal)
- `PathReservationService` — dispatcher logic: find FREE paths, reserve atomically
- `ReservationTargetQuery` — read-only next-target candidate query
- `ReservationExpiry` — kernel-side reservation expiry trigger
- `TrainNavigationService` — train-side navigation: follow RESERVED paths only

`DefaultPathReservationService` is composed of internal SP12 units (`RouteReleaser`,
`SwitchConfigurator`, `RouteSignalConfigurator`, `ReservationTargetDiscovery`,
`RouteCandidateScreen`, `CandidateCommitter`, `DefaultReservationExpiry`, and others); the seam list
and sizes are in [../docs/goal9b-demands/SP12-routing-decomposition.md](../docs/goal9b-demands/SP12-routing-decomposition.md).
`PathReservationRegistry` (O(1) train↔block ownership, one per context) is a facade over
`BlockOwnershipTable`, `SwitchOwnershipTable`, `PathInfoStore` and `PathInfoMerger`.
Target selection policy lives in `sim/ReservationTargetPolicy`; the station abstraction in `sim/Station`.

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
│   ├── pathfinding/   - Automatic path finding (`DefaultRouteFinder`, path cost); `RouteSearch` is in context/navigation
│   ├── ports/         - Ports for the dispatcher seam
│   ├── sim/           - Simulation engine and scenarios (+ collision, conflict, events, metrics)
│   ├── util/          - Utilities
│   └── xml/           - XML model (expect declarations)
├── commonTest/kotlin/ - Multiplatform tests
├── jvmMain/kotlin/    - JVM actuals: context/, util/, xml/ (parsing, factories)
├── jvmTest/kotlin/    - JVM-only tests, incl. testutil/ and docs/ (line-anchor tripwire)
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

- **SIM-004:** ✅ resolved by SP2 (#1149): the `Station` abstraction replaced the hardcoded
  `vyhybna.xml` topology. `ShuntingLoop.compatibilityIssues` still limits the scenario to
  vyhybna-like stations until SP3 (#1150).
  Minor simulation issues SIM-001 to SIM-006 are documented in code comments.
- Train-physics passivation fix (Issue #291) is documented in
  [../docs/TRAIN_PASSIVATION_FIX.md](../docs/TRAIN_PASSIVATION_FIX.md) — a
  `sim/` change must not regress it.
