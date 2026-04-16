# commonTest Migration — Design

**Date:** 2026-04-16
**Status:** Draft (pending user review)
**Related memory:** `project_commontest_migration.md` (deferred)
**Unblocking PR:** #461 (Multiplatform Resources API, merged 2026-04-15)

## Problem

`:core/src/jvmTest/kotlin/` currently contains **107 test files** that only run on the JVM target. The KMP `:core` module also targets `linuxX64`, but native test coverage is only ~5 tests (`NativeSanityTest` + a handful of XML tests promoted in PR #461).

The original blocker recorded in the deferred memory was that XML parsing was JVM-only, so most domain tests could not be moved. **PR #461 made the `Resources` API multiplatform** and shipped multiplatform `XmlContextReader` + `XmlSchemaValidator`. The blocker is now stale — eligible tests can move.

## Goal

Migrate ~85 of 107 jvmTest files into `commonTest/` so they run on both JVM and `linuxX64`, in a **single PR**. JVM coverage is preserved (commonTest is shared with the JVM target). Native coverage on `:core:linuxX64Test` rises from ~5 tests to ~90.

## Non-goals

- No production code changes.
- No migration of `:desktop-ui` tests (the module is `kotlin("jvm")`; no native target).
- No conversion of MockK-using tests that **stay** in jvmTest. MockK and Mokkery will coexist temporarily.
- No replacement of `java.util.concurrent` primitives — those tests stay JVM-only.

## Decisions Captured From Brainstorm

| Decision | Choice | Rationale |
|---|---|---|
| Aggressiveness | **Maximal** (~85 files: Trivial + Moderate + de-File + MockK→Mokkery for moved tests) | User explicitly opted in. Maximizes native coverage in one cycle. |
| Staging | **Single mega-PR** | User explicitly opted in despite the recommended four-PR split. Mitigated by per-bucket commits. |
| MockK strategy | **Convert only the MockK in moved tests**; leave MockK in jvmTest survivors | Smaller blast radius; future cleanup PR can finish the migration. |

## Migration Buckets

### Bucket A — Trivial (~35 files)

Mechanical JUnit5 → `kotlin.test` swap:
- `org.junit.jupiter.api.Test` → `kotlin.test.Test`
- `@BeforeEach` → `@BeforeTest`
- `@AfterEach` → `@AfterTest`
- Drop `@DisplayName`, `@Tag`, `@Timeout` (no kotlin.test equivalent; `@Nested` may stay if nesting works).

Examples: `ContextTest.kt`, `BaseContextTest.kt`, `BresenhamJoinTest.kt`, `PathIteratorTest.kt`, `ArrayPathTest.kt`, `SimpleTrackStateTest.kt`, `GridTransformerTest.kt`, `ContextTypeParameterizationTest.kt`, `DefaultContextTest.kt`, plus ~26 more.

Move target: `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/<same subpackage>/`.

### Bucket B — Moderate, parameterized tests (~11 files)

Convert JUnit5 parameterization to kotlin.test patterns:

| JUnit5 | kotlin.test equivalent |
|---|---|
| `@ParameterizedTest @EnumSource(Enum::class)` | `for (value in Enum.values()) { … }` inside one `@Test`, or one `@Test` per case |
| `@ParameterizedTest @CsvSource("a,b", "c,d")` | List of data-class fixtures, iterated in a `@Test` |
| `@ParameterizedTest @MethodSource("companionMethod")` | Call companion fn directly, iterate result |
| `@ValueSource(ints = [...])` | `intArrayOf(...).forEach { … }` |

Diagnostic loss (no per-row IDE reporting) is mitigated with `assertk`'s `withClue { "row=$row" }`.

Files: `CellTest.kt`, `PointTest.kt`, `RailSemaphoreTest.kt`, `DynamicRailSwitchTest.kt`, `CellsPolishTest.kt`, `AutoNameGeneratorTest.kt`, `PathErrorRecoveryTest.kt`, `PathMaxSpeedCalculationTest.kt`, `TransitionAwarePathTest.kt`, `PathSetupTeardownTest.kt`, `PointFTest.kt`.

`CellTest.kt` additionally uses `::class.java` — replace with the `KClass` API per the existing codebase gotcha.

### Bucket C — De-File the fixture loaders (~24 candidates, expect ~20 actually movable)

Tests in this bucket load XML via `File("src/main/resources/.../foo.xml")`. The refactor pattern:

```kotlin
// Before (jvmTest)
val ctx = XMLContextFactory().createSimulationContext(
    File("src/main/resources/cz/vutbr/fit/interlockSim/xml/vyhybna.xml")
)

// After (commonTest)
val xml = Resources.read("xml/vyhybna.xml")
val ctx = XmlContextReader().read(xml).toSimulationContext()
```

**Critical constraint:** `XMLContextFactory` itself stays JVM-only (uses JAXP, reflection, `File`). Tests must be rewritten to use multiplatform `XmlContextReader` + `XmlSchemaValidator` directly. The existing `CommonTestFixtures.parseEditingContext(xml: String)` in `core-test/commonMain/` is the seam — extend it instead of duplicating logic per test.

Tests that additionally use `@TempDir` (filesystem write) stay in jvmTest — write-fs is not abstracted.

Per-file triage during execution may demote some candidates back to jvmTest if hidden JVM dependencies surface (e.g., `System.getProperty`, `ClassLoader`).

Confirmed-stays-in-jvmTest from this bucket: `ConcurrentSaveTest.kt` (concurrency + filesystem), `JvmParityReferenceTest.kt` (JVM-baseline by purpose), `GeneratorTest.kt` (writes files), `LoopProcessTest.kt` (concurrency).

### Bucket D — MockK → Mokkery for moved tests (~15 files)

Mokkery plugin is **declared** at root `build.gradle.kts:18` and `core/build.gradle.kts:41`, version `2.7.3` in `gradle.properties`. It is **not yet wired into source-set dependencies** — that's the one-time infra step.

Infra change (in `core/build.gradle.kts`):

```kotlin
kotlin {
    sourceSets {
        commonTest {
            dependencies {
                implementation("dev.mokkery:mokkery-runtime:$mokkeryVersion")
            }
        }
    }
}
```

Per-file conversion sketch (Mokkery DSL is similar to MockK but not identical):

| MockK | Mokkery |
|---|---|
| `mockk<Foo>()` | `mock<Foo>()` (compile-time generated by plugin) |
| `every { foo.bar() } returns x` | `every { foo.bar() } returns x` (same) |
| `verify { foo.bar() }` | `verify { foo.bar() }` (same) |
| `mockk<Foo>(relaxed = true)` | `mock<Foo>(MockMode.autofill)` (different mode constant) |
| `slot<T>()` / `capture(slot)` | `capture<T>` with similar shape |

Files: `TrainTest.kt`, `TimetableTest.kt`, `DeadlockDetectionTest.kt`, `TrainPathInteractionTest.kt`, `TrainPhysicsTest.kt`, `TrainStateTransitionTest.kt`, `TransitionAwarePathTest.kt`, `DynamicTrackTest.kt`, `DynamicTrackBlockTest.kt`, `TracksPolishTest.kt`, `RailSemaphoreTest.kt`, `PathTrackIntegrationTest.kt`, `TrainMovementIntegrationTest.kt`, `SimulationScenarioTest.kt`, `TrainPublicAPITest.kt`.

`TrackTestMocks.kt` (currently in `core-test/jvmMain` per memory) needs a Mokkery sibling in `core-test/commonMain` so commonTest tests share the mock factories.

### Bucket E — Native test promotion

`core/src/nativeTest/kotlin/.../xml/BuiltinNetworksTest.kt` should be **promoted to commonTest** so JVM also runs it (the user explicitly asked about jvm/native → common). Quick read indicates it only verifies builtin XML loads — promotion should be clean.

## Files Staying in `jvmTest/` (~22 files)

| Reason | Examples |
|---|---|
| `java.util.concurrent` primitives | `ContextConcurrencyTest.kt`, `ConcurrentSaveTest.kt`, `LoopProcessTest.kt`, `ShuntingLoopSmokeTest.kt`, `ShuntingLoopTest.kt` |
| Filesystem write (`@TempDir`, `FileWriter`) | `GeneratorTest.kt`, `ConcurrentSaveTest.kt` |
| JVM-baseline by purpose | `JvmParityReferenceTest.kt` |
| JVM-only test infra | `CoreTestModule.kt` (binds JVM-only `XMLContextFactory`) |

## Critical Files Touched

- `core/build.gradle.kts` — add Mokkery to `commonTest` dependencies.
- `core-test/build.gradle.kts` — likely add Mokkery as `commonMain` `api` dep so test fixtures expose mock factories.
- `core-test/src/commonMain/kotlin/.../testutil/CommonTestFixtures.kt` — extend with helpers needed by migrated tests.
- `core-test/src/commonMain/kotlin/.../testutil/TrackTestMocks.kt` — new file (Mokkery sibling of jvmMain `TrackTestMocks.kt`).
- ~85 individual test files moved from `core/src/jvmTest/kotlin/...` to `core/src/commonTest/kotlin/...`.
- `core/src/nativeTest/kotlin/.../BuiltinNetworksTest.kt` → moved to `core/src/commonTest/`.

## Verification

```bash
./gradlew :core:jvmTest                      # JVM still green
./gradlew :core:linuxX64Test                 # Native count rises
./gradlew :core:allTests                     # Aggregate
./gradlew clean build                        # Whole-project regression
./gradlew detekt ktlintCheck                 # Quality gates
./gradlew integrationTest                    # Pre-push gate per workflow rule
./gradlew :core:checkCoreCommonMainPurity    # KMP purity gate
```

Success criteria:
- JVM test count: zero loss (commonTest counts in jvm run).
- `linuxX64Test` count: rises from current baseline (~5) to roughly ~90.
- All gates green.

## Risks

1. **Mokkery is not 100% drop-in.** First conversion should be a spike on the simplest MockK file to surface API gaps before the rest.
2. **Bucket C may shrink** — hidden JVM deps in moved tests (`System.getProperty`, `ClassLoader`, etc.) may force per-file demotion back to jvmTest.
3. **Single mega-PR is hard to review.** User explicitly accepted; mitigation is per-bucket commits within the PR.
4. **`@Nested` classes** have no direct kotlin.test equivalent; some Bucket A files may need flattening.

## Open Items For User Review

- Confirm the spec directory and filename are right (this file is `docs/superpowers/specs/2026-04-16-commontest-migration-design.md`).
- Confirm that demoting a file mid-execution (when a hidden JVM dep is found) is acceptable rather than a forced workaround (e.g., a `expect`/`actual` shim).
- Confirm the deferred memory `project_commontest_migration.md` should be updated/removed once this lands.

## Next Step

After user reviews and approves this spec, invoke `superpowers:writing-plans` to produce the step-by-step implementation plan.
