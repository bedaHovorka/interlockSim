# commonTest Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate ~85 of the 107 `:core/jvmTest/` test files to `:core/commonTest/` so they additionally run on `linuxX64`, in a single mega-PR with per-bucket commits.

**Architecture:** Five sequential migration buckets (A: Trivial JUnit5→kotlin.test, B: parameterized rewrite, C: de-File fixture loaders, D: MockK→Mokkery for moved tests, E: nativeTest promotion), preceded by Mokkery wiring validation. Each bucket = one commit. Files that hit hard JVM-only blockers (`java.util.concurrent`, `@TempDir`, JVM-baseline tests) stay in `jvmTest/`.

**Tech Stack:** Kotlin Multiplatform (`:core` targets jvm + linuxX64), Gradle Kotlin DSL, kotlin.test, AssertK 0.28, Koin 3.5.6, Mokkery 2.7.3 (replaces MockK in moved tests), xmlutil (multiplatform XML), kotlinx-io (native filesystem).

**Spec:** `docs/superpowers/specs/2026-04-16-commontest-migration-design.md` (commit `4fff07e`).

**Baseline (verified 2026-04-16):**
- `:core:jvmTest` = 1861 tests passing.
- `:core:linuxX64Test` = 545 tests passing.
- Build green in 55s (clean run was not done; baseline was incremental).

**Branch:** `feature/commontest-migration` in worktree `.worktrees/commontest-migration/`.

---

## Glossary of conventions used in this plan

- **commonTest path** for a moved file: `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/<same subpackage>/<SameClassName>.kt`. Subpackage and filename are preserved.
- **JUnit5 → kotlin.test mapping:**
  | JUnit5 | kotlin.test |
  |---|---|
  | `import org.junit.jupiter.api.Test` | `import kotlin.test.Test` |
  | `@BeforeEach` | `@BeforeTest` (`import kotlin.test.BeforeTest`) |
  | `@AfterEach` | `@AfterTest` (`import kotlin.test.AfterTest`) |
  | `assertEquals(a, b)` (junit) | `assertEquals(a, b)` (`import kotlin.test.assertEquals`) — same name, different package |
  | `@DisplayName("…")` | **drop** (no equivalent) |
  | `@Tag("…")` | **drop** (no equivalent — see Bucket A guard) |
  | `@Timeout(…)` | **drop** for pure-logic tests; for time-bounded tests, leave file in jvmTest |
  | `@Nested` inner classes | **drop** the annotation; if class structure breaks, flatten into top-level test functions |
  | `@ParameterizedTest` etc. | rewrite — see Bucket B |
- **AssertK is multiplatform**, no changes needed for assertion calls.
- **Koin is multiplatform**, no changes needed for `inject()` / `KoinTest` usage. (`KoinTestBase` exists in core-test/commonMain — verify during Bucket A.)
- **Commit one bucket at a time.** Per-bucket commit messages prefix `test(commonTest):` and are listed in each bucket's final step.
- **Run after every bucket:** `./gradlew :core:jvmTest :core:linuxX64Test`. Both must stay green. JVM count must be ≥ baseline (1861); native count must rise.

---

## Files Touched (high level)

- **Modify:** `core/build.gradle.kts` — possibly add explicit Mokkery commonTest dep if plugin auto-wiring isn't sufficient; update stale comment at lines 24-29.
- **Modify:** `core-test/build.gradle.kts` — add `id("dev.mokkery")` plugin if `core-test/commonMain` needs to expose Mokkery-based mock helpers.
- **Possibly create:** `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/CommonTrackTestMocks.kt` — only if any moved test references the existing JVM-only `TrackTestMocks` helpers.
- **Possibly extend:** `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/CommonTestFixtures.kt` — add helpers needed by Bucket C tests.
- **Move + rewrite:** ~85 individual test files from `core/src/jvmTest/kotlin/...` to `core/src/commonTest/kotlin/...` (see per-bucket discovery commands).
- **Move:** `core/src/nativeTest/kotlin/cz/vutbr/fit/interlockSim/xml/BuiltinNetworksTest.kt` → `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/xml/`.

---

## Task 1: Validate Mokkery wiring in commonTest

Mokkery's plugin (`dev.mokkery` 2.7.3) is declared in `core/build.gradle.kts:41` but no explicit `mokkery-runtime` dependency appears in commonTest. Per Mokkery 2.x docs, the plugin auto-configures test source-set dependencies. Bucket D will fail catastrophically if this auto-wiring doesn't reach commonTest, so we validate up front with a TDD smoke test before mass migration.

**Files:**
- Create: `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/testutil/MokkerySmokeTest.kt`
- Possibly modify: `core/build.gradle.kts` (lines 130-138 commonTest dependencies block) if smoke test fails to compile.

- [ ] **Step 1.1: Create the smoke test**

```kotlin
// core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/testutil/MokkerySmokeTest.kt
package cz.vutbr.fit.interlockSim.testutil

import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import dev.mokkery.verify
import kotlin.test.Test
import kotlin.test.assertEquals

interface SmokeTarget {
    fun greet(name: String): String
}

class MokkerySmokeTest {
    @Test
    fun `mokkery mocks an interface and verifies the call`() {
        val target = mock<SmokeTarget>()
        every { target.greet("world") } returns "hello world"

        val result = target.greet("world")

        assertEquals("hello world", result)
        verify { target.greet("world") }
    }
}
```

- [ ] **Step 1.2: Run on JVM**

Run: `./gradlew :core:jvmTest --tests "cz.vutbr.fit.interlockSim.testutil.MokkerySmokeTest"`
Expected: 1 test passed.

If compilation fails with `Unresolved reference: dev.mokkery`, add to `core/build.gradle.kts` inside the `commonTest by getting { dependencies { … } }` block (lines 130-138):
```kotlin
implementation("dev.mokkery:mokkery-runtime:$mokkeryVersion")
```
…and add `val mokkeryVersion: String by project` near the other version declarations (around line 58). Re-run.

- [ ] **Step 1.3: Run on linuxX64**

Run: `./gradlew :core:linuxX64Test --tests "cz.vutbr.fit.interlockSim.testutil.MokkerySmokeTest"`
Expected: 1 test passed.

If native test fails with a missing-symbol or ABI error, that's a hard blocker for Bucket D. Stop and report — Bucket D may need to be dropped from the PR.

- [ ] **Step 1.4: Commit**

```bash
git add core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/testutil/MokkerySmokeTest.kt
# Plus core/build.gradle.kts if modified
git commit -m "test(commonTest): validate Mokkery wiring in :core commonTest

Mokkery 2.7.3 plugin auto-configures runtime deps for test source sets;
this smoke test confirms the wiring reaches both jvm and linuxX64 targets
before Bucket D performs MockK->Mokkery rewrites."
```

---

## Task 2: Promote `nativeTest/BuiltinNetworksTest.kt` to commonTest (Bucket E)

Smallest, lowest-risk move. Validates the move-and-rerun cycle and gives the JVM target additional coverage of builtin XML networks.

**Files:**
- Move: `core/src/nativeTest/kotlin/cz/vutbr/fit/interlockSim/xml/BuiltinNetworksTest.kt` → `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/xml/BuiltinNetworksTest.kt`

- [ ] **Step 2.1: Read the file to confirm it's portable**

Run: `cat core/src/nativeTest/kotlin/cz/vutbr/fit/interlockSim/xml/BuiltinNetworksTest.kt`
Verify imports contain only `kotlin.test`, `assertk.*`, project `cz.vutbr.fit.interlockSim.*`, and (possibly) `cz.vutbr.fit.interlockSim.util.Resources`. If it imports anything platform-specific (`platform.posix.*`, `kotlinx.cinterop.*`), STOP — file is native-only by design and should not move.

- [ ] **Step 2.2: Move the file with git mv**

```bash
mkdir -p core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/xml
git mv core/src/nativeTest/kotlin/cz/vutbr/fit/interlockSim/xml/BuiltinNetworksTest.kt \
       core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/xml/BuiltinNetworksTest.kt
```

- [ ] **Step 2.3: Run on both targets**

```bash
./gradlew :core:jvmTest --tests "cz.vutbr.fit.interlockSim.xml.BuiltinNetworksTest"
./gradlew :core:linuxX64Test --tests "cz.vutbr.fit.interlockSim.xml.BuiltinNetworksTest"
```

Both must pass. JVM was previously not running this test; this confirms it works there too.

- [ ] **Step 2.4: Verify no orphan native-only directory**

Run: `ls core/src/nativeTest/kotlin/cz/vutbr/fit/interlockSim/xml/ 2>/dev/null`
If empty, remove: `rmdir core/src/nativeTest/kotlin/cz/vutbr/fit/interlockSim/xml/` (and parent dirs as they empty). Use `git status` to confirm no stray files left behind.

- [ ] **Step 2.5: Commit**

```bash
git add -A core/src/nativeTest core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/xml/
git commit -m "test(commonTest): promote BuiltinNetworksTest from nativeTest

Verifies builtin XML networks load successfully. Was native-only;
moving to commonTest gives JVM the same coverage."
```

---

## Task 3: Bucket A — Trivial JUnit5→kotlin.test migration (~35 files)

Files in this bucket use JUnit5 with no parameterized tests, no MockK, no File I/O, and no `java.util.concurrent`. The change is mechanical.

**Files (discovery in Step 3.1; representative examples):**
- Move: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/ContextTest.kt` → `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/context/ContextTest.kt`
- Move: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/BaseContextTest.kt` → `core/src/commonTest/kotlin/cz/vutbr/fit/interlockSim/context/BaseContextTest.kt`
- (~33 more discovered in Step 3.1)

- [ ] **Step 3.1: Enumerate Bucket A candidates**

Run this enumeration command from the worktree root:

```bash
# Files that DON'T use any of the JVM-only blockers
cd /home/beda/work/interlockSim/.worktrees/commontest-migration
find core/src/jvmTest/kotlin -name "*.kt" -type f | while read f; do
  # Skip if uses any blocker
  if grep -qE "io\.mockk|java\.io\.File|java\.util\.concurrent|@ParameterizedTest|@TempDir|@MethodSource|@EnumSource|@CsvSource|@ValueSource|@ArgumentsSource|System\.getProperty|ClassLoader" "$f"; then continue; fi
  echo "$f"
done > /tmp/bucket_a_candidates.txt
wc -l /tmp/bucket_a_candidates.txt
cat /tmp/bucket_a_candidates.txt
```

Expected: roughly 30-40 file paths. The exact list is whatever the grep produces — record it and proceed.

If the count is wildly off (e.g., 5 or 60), pause and re-read the spec's Bucket A description to triangulate.

- [ ] **Step 3.2: Worked example — convert and move ONE file first**

Pick the simplest file from the candidate list (e.g. `BresenhamJoinTest.kt` or `PathIteratorTest.kt`). Apply the procedure end-to-end so any unforeseen issue surfaces before the batch runs.

Example transformation (conceptual; actual file content varies):

**Before** (`core/src/jvmTest/kotlin/.../BresenhamJoinTest.kt`):
```kotlin
package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Bresenham join algorithm")
class BresenhamJoinTest {
    private lateinit var joiner: BresenhamJoin

    @BeforeEach fun setUp() { joiner = BresenhamJoin() }

    @Test fun `joins two collinear segments`() {
        assertThat(joiner.join(/* … */)).isEqualTo(/* … */)
    }
}
```

**After** (`core/src/commonTest/kotlin/.../BresenhamJoinTest.kt`):
```kotlin
package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.BeforeTest
import kotlin.test.Test

class BresenhamJoinTest {
    private lateinit var joiner: BresenhamJoin

    @BeforeTest fun setUp() { joiner = BresenhamJoin() }

    @Test fun `joins two collinear segments`() {
        assertThat(joiner.join(/* … */)).isEqualTo(/* … */)
    }
}
```

Procedure:
1. `git mv core/src/jvmTest/kotlin/<path>/<File>.kt core/src/commonTest/kotlin/<path>/<File>.kt` (mkdir -p the target dir first if needed).
2. Apply the import/annotation swaps from the Glossary table above.
3. If file uses `@Nested`, drop the annotation. If the test class structure depended on it (e.g., shared state in outer class), flatten the nested class into top-level test functions.
4. If file uses `@Tag("integration-test")`, **revert the move** — that file does NOT belong in Bucket A; demote it to "stays in jvmTest" and remove from the candidate list.

After conversion:
```bash
./gradlew :core:jvmTest --tests "cz.vutbr.fit.interlockSim.util.BresenhamJoinTest"
./gradlew :core:linuxX64Test --tests "cz.vutbr.fit.interlockSim.util.BresenhamJoinTest"
```
Both must pass. If native fails with an assertion-only message (e.g., "expected X but was Y"), that's a real test failure — the test is not commonTest-compatible (likely depends on JVM-specific behavior). Demote the file back to jvmTest and remove from the candidate list.

- [ ] **Step 3.3: Process the remaining candidates**

For each file in `/tmp/bucket_a_candidates.txt` (excluding the worked example):

1. Apply the same git-mv + import/annotation swap procedure from Step 3.2.
2. Track per-file outcome (moved / demoted-to-jvmTest with reason) in a scratch list — these will go into the commit message.
3. Do NOT run tests after each individual file — wait for the bucket sweep below.

After all files processed:

```bash
./gradlew :core:jvmTest 2>&1 | tail -20
./gradlew :core:linuxX64Test 2>&1 | tail -20
```

Expected:
- `:core:jvmTest`: ≥ 1861 tests, all passing (count unchanged because commonTest runs on JVM too).
- `:core:linuxX64Test`: > 545 tests (rises by however many test methods lived in the migrated files), all passing.

Compile failures? Read the error, fix the offending file (likely a missed `@Tag` or `@Nested`), re-run. Do not push fixes that disable tests.

- [ ] **Step 3.4: Commit Bucket A**

Stage everything migrated:
```bash
git add -A core/src/jvmTest core/src/commonTest
git status --short  # verify only test moves, no production code touched
```

Commit with the actual count substituted in:
```bash
git commit -m "test(commonTest): migrate Bucket A trivial tests to commonTest

Mechanical JUnit5 -> kotlin.test conversion for N test files with no
JVM-only dependencies. Native coverage rises from ~545 to ~M tests.

JUnit5 imports replaced with kotlin.test equivalents:
- @Test, @BeforeEach->@BeforeTest, @AfterEach->@AfterTest
- @DisplayName, @Tag, @Timeout dropped (no kotlin.test analog)

No production code changes."
```
(Replace N with the actual moved count and M with the post-migration linuxX64Test count from Step 3.3.)

---

## Task 4: Bucket B — Parameterized test conversion (~11 files)

These files use `@ParameterizedTest`/`@EnumSource`/`@MethodSource`/`@CsvSource`/`@ValueSource`. JUnit5's parameterized test runner has no kotlin.test equivalent, so each parameterized test becomes either:
- A single `@Test` function with a `for (…)` loop iterating over the parameter list, or
- One `@Test` per parameter value (when there are 2-3 cases and clarity matters).

Either form must use `withClue { "row=$row" }` (from `assertk.assertions.support.show`) inside the loop so failure messages still identify the failing row.

**Files (Bucket B candidates from spec):**
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/cells/CellTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/util/PointTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/RailSemaphoreTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/DynamicRailSwitchTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/cells/CellsPolishTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/util/AutoNameGeneratorTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/paths/PathErrorRecoveryTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/paths/PathMaxSpeedCalculationTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/paths/TransitionAwarePathTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/paths/PathSetupTeardownTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/util/PointFTest.kt`

(If discovery in Step 4.1 surfaces a different set, use that. Path prefixes are best-guesses — the discovery command is canonical.)

- [ ] **Step 4.1: Confirm Bucket B candidates exist**

```bash
cd /home/beda/work/interlockSim/.worktrees/commontest-migration
grep -lrE "@ParameterizedTest|@EnumSource|@MethodSource|@CsvSource|@ValueSource|@ArgumentsSource" core/src/jvmTest/kotlin > /tmp/bucket_b_candidates.txt
wc -l /tmp/bucket_b_candidates.txt
cat /tmp/bucket_b_candidates.txt
```

Expected: ~11 files. Exclude any that ALSO use MockK or File I/O — those are blocked by Bucket C/D dependencies and will be handled there if eligible at all.

```bash
# Filter out files also blocked by File I/O or MockK
while read f; do
  if grep -qE "io\.mockk|java\.io\.File|@TempDir|java\.util\.concurrent" "$f"; then
    echo "DEFERRED: $f"
  else
    echo "$f"
  fi
done < /tmp/bucket_b_candidates.txt
```

Use the non-deferred list for the rest of Task 4.

- [ ] **Step 4.2: Worked example — convert ONE parameterized file first**

Pick the simplest (likely `PointTest.kt` or `PointFTest.kt` — short, value-based).

Conversion patterns (apply whichever matches the source):

**Pattern 1: `@EnumSource`**

Before:
```kotlin
@ParameterizedTest
@EnumSource(Direction::class)
fun `direction has non-null vector`(d: Direction) {
    assertThat(d.vector).isNotNull()
}
```

After:
```kotlin
@Test
fun `every direction has non-null vector`() {
    Direction.values().forEach { d ->
        assertThat(d.vector, name = "direction=$d").isNotNull()
    }
}
```

**Pattern 2: `@CsvSource`**

Before:
```kotlin
@ParameterizedTest
@CsvSource("1, 1, 2", "2, 3, 5", "0, 0, 0")
fun adds(a: Int, b: Int, expected: Int) {
    assertThat(add(a, b)).isEqualTo(expected)
}
```

After:
```kotlin
@Test
fun adds() {
    listOf(
        Triple(1, 1, 2),
        Triple(2, 3, 5),
        Triple(0, 0, 0),
    ).forEach { (a, b, expected) ->
        assertThat(add(a, b), name = "a=$a,b=$b").isEqualTo(expected)
    }
}
```

**Pattern 3: `@MethodSource("companionMethod")`**

Before:
```kotlin
@ParameterizedTest
@MethodSource("rowsForCornerCases")
fun handles(row: Row) { … }

companion object {
    @JvmStatic
    fun rowsForCornerCases(): List<Row> = listOf(…)
}
```

After:
```kotlin
@Test
fun handlesAllCornerCaseRows() {
    rowsForCornerCases().forEach { row ->
        assertThat(handle(row), name = row.toString()).isEqualTo(row.expected)
    }
}

private fun rowsForCornerCases(): List<Row> = listOf(…)
// Drop @JvmStatic and companion-object wrapper if no other JVM-only consumer
```

**Pattern 4: `@ValueSource`**

Before:
```kotlin
@ParameterizedTest
@ValueSource(ints = [0, 1, 5, 100])
fun fitsInRange(n: Int) { assertThat(fits(n)).isTrue() }
```

After:
```kotlin
@Test
fun fitsInRangeForKnownValues() {
    intArrayOf(0, 1, 5, 100).forEach { n ->
        assertThat(fits(n), name = "n=$n").isTrue()
    }
}
```

**Pattern 5: `@ArgumentsSource(SwitchActiveSegmentsProvider::class)` (project-specific provider)**

Look at what the provider returns. If it's a list of arguments objects, convert it to a Kotlin function returning `List<…>` and iterate. Provider class can be deleted.

After conversion of the worked example, run:
```bash
./gradlew :core:jvmTest --tests "<FQN of file>"
./gradlew :core:linuxX64Test --tests "<FQN of file>"
```

Both pass. Failure modes to expect:
- Test ordering: previously each parameterized row was its own JUnit5 test; now they share state. If `@BeforeEach`/`@BeforeTest` setup is not idempotent across rows, restructure to one `@Test` per row.
- `KClass.java` usage: replace with the `KClass` API per the existing project gotcha (see CLAUDE.md memory note about `AutoNameGenerator.generateName`).

- [ ] **Step 4.3: Convert remaining Bucket B files**

Apply Step 4.2's patterns to each file. After all done, full sweep:
```bash
./gradlew :core:jvmTest 2>&1 | tail -10
./gradlew :core:linuxX64Test 2>&1 | tail -10
```

Both green. JVM count unchanged (commonTest counts there too); native count rises by the test-method count of the converted files.

- [ ] **Step 4.4: Commit Bucket B**

```bash
git add -A core/src/jvmTest core/src/commonTest
git status --short
git commit -m "test(commonTest): migrate Bucket B parameterized tests to commonTest

JUnit5 @ParameterizedTest / @EnumSource / @MethodSource / @CsvSource
have no kotlin.test equivalent; rewrote N files as data-driven loops
inside single @Test functions, using assertk's name= clue argument to
preserve diagnostic context on failure.

CellTest also converted from ::class.java to KClass API."
```
(N = actual converted count.)

---

## Task 5: Bucket C — De-File the fixture loaders (~24 candidates, expect ~20 actual moves)

These tests load XML via `File("src/main/resources/.../foo.xml")` — JVM-only. Refactor to use `Resources.read("xml/foo.xml")` + `XmlContextReader` (both already multiplatform).

**Strategy:** First check if `CommonTestFixtures` in `core-test/commonMain` already exposes the helper each test needs. If not, extend it. Then convert files one at a time.

**Files (Bucket C candidates from spec):**
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/ContextInitializationTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/SimulationGridDynamicCellsTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/ShuntingLoopRegressionTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/SimpleLinearTrackTestProcessTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/Issue316RegressionTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/InOutWorkerTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/paths/PathReservationServiceTest.kt`
- Discover the rest in Step 5.1.

**Confirmed-stays-in-jvmTest** (do NOT touch in Bucket C):
- `ConcurrentSaveTest.kt` (concurrency + filesystem)
- `JvmParityReferenceTest.kt` (JVM-baseline by design)
- `GeneratorTest.kt` (writes files)
- `LoopProcessTest.kt` (concurrency)
- Anything additionally using MockK (those move in Bucket D, not C)
- Anything using `@TempDir`

- [ ] **Step 5.1: Enumerate Bucket C candidates**

```bash
cd /home/beda/work/interlockSim/.worktrees/commontest-migration
grep -lrE "java\.io\.File|XMLContextFactory" core/src/jvmTest/kotlin > /tmp/bucket_c_raw.txt
# Filter out files with hard JVM blockers
while read f; do
  if grep -qE "io\.mockk|@TempDir|java\.util\.concurrent|System\.getProperty|FileWriter|FileOutputStream|MessageDigest" "$f"; then
    echo "EXCLUDED: $f"
  else
    echo "$f"
  fi
done < /tmp/bucket_c_raw.txt > /tmp/bucket_c_candidates.txt
grep -v EXCLUDED /tmp/bucket_c_candidates.txt | tee /tmp/bucket_c_eligible.txt | wc -l
```

Expected eligible: roughly 18-22 files. Use `/tmp/bucket_c_eligible.txt` as the working list.

- [ ] **Step 5.2: Inspect CommonTestFixtures and decide on extensions**

Read: `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/CommonTestFixtures.kt`

The spec says it already has `parseEditingContext(xml: String)`. Identify which Bucket C tests need:
- `parseEditingContext(xml)` — returns EditingContext from XML string
- `parseSimulationContext(xml)` — returns SimulationContext from XML string
- A loader by builtin network name (e.g., `loadShuntingLoopContext()`)

If a needed helper doesn't exist, add it. Example sketch:
```kotlin
// Add to CommonTestFixtures (only if not already present)
fun parseSimulationContext(xml: String): SimulationContext {
    val editing = parseEditingContext(xml)
    return ContextTransformer.toSimulation(editing)  // verify actual API
}

fun loadShuntingLoopContext(): SimulationContext =
    parseSimulationContext(Resources.read("xml/vyhybna.xml"))
```

If you add helpers, run `./gradlew :core-test:compileKotlinMetadata` to confirm they compile in commonMain, then continue.

- [ ] **Step 5.3: Worked example — convert ONE Bucket C file**

Pick the simplest (likely `Issue316RegressionTest.kt` or whichever has fewest fixture references).

Refactor pattern:

Before:
```kotlin
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import java.io.File

class Issue316RegressionTest {
    @Test fun `regression for issue 316`() {
        val ctx = XMLContextFactory().createSimulationContext(
            File("src/main/resources/cz/vutbr/fit/interlockSim/xml/vyhybna.xml")
        )
        // … assertions on ctx
    }
}
```

After:
```kotlin
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import kotlin.test.Test

class Issue316RegressionTest {
    @Test fun `regression for issue 316`() {
        val ctx = CommonTestFixtures.loadShuntingLoopContext()
        // … same assertions on ctx
    }
}
```

After conversion:
```bash
git mv core/src/jvmTest/kotlin/.../Issue316RegressionTest.kt \
       core/src/commonTest/kotlin/.../Issue316RegressionTest.kt
./gradlew :core:jvmTest --tests "*Issue316RegressionTest"
./gradlew :core:linuxX64Test --tests "*Issue316RegressionTest"
```

Both pass. If linuxX64 fails because the network XML file isn't reachable, the resource path is wrong — debug with `Resources.read("xml/vyhybna.xml")` printed in a sanity test. The `NATIVE_RESOURCE_ROOTS` list in `core/build.gradle.kts:179-223` shows what dirs native looks in.

If the test additionally calls `XMLContextFactory.save(...)` or any write API, demote the file to "stays in jvmTest" — write paths aren't multiplatform.

- [ ] **Step 5.4: Convert remaining Bucket C files**

Per file:
1. Read it. Identify every File-based load. Rewrite to `Resources.read(…)` or a `CommonTestFixtures` helper.
2. If you find a hidden JVM dep mid-conversion (`System.getProperty`, `ClassLoader.getResourceAsStream`, `@Tag("integration-test")`), demote the file: `git checkout core/src/jvmTest/kotlin/.../<File>.kt` to undo the move and leave it in jvmTest. Note in your scratch list.
3. `git mv` to commonTest target.
4. Apply JUnit5 → kotlin.test imports per Bucket A glossary.

After all done:
```bash
./gradlew :core:jvmTest 2>&1 | tail -10
./gradlew :core:linuxX64Test 2>&1 | tail -10
```

Both green.

- [ ] **Step 5.5: Commit Bucket C**

```bash
git add -A core/src/jvmTest core/src/commonTest core-test/src/commonMain
git status --short
git commit -m "test(commonTest): migrate Bucket C fixture-loader tests to commonTest

Replaced File-based XML loading with multiplatform Resources.read +
XmlContextReader (already commonMain) so N tests now run on linuxX64.

XMLContextFactory itself stays JVM-only (uses JAXP, reflection, File);
CommonTestFixtures was extended where needed with shared loaders.

K files demoted back to jvmTest after triage surfaced hidden JVM deps
(@Tag('integration-test'), filesystem writes, etc.)."
```
(N, K = actual counts.)

---

## Task 6: Bucket D — MockK→Mokkery for moved tests (~15 files)

Mokkery wiring is validated by Task 1. This bucket converts the MockK API calls in tests being moved.

**Files (Bucket D candidates from spec):**
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/TrainTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/TimetableTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/DeadlockDetectionTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/paths/TrainPathInteractionTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/TrainPhysicsTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/TrainStateTransitionTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/paths/TransitionAwarePathTest.kt` (if not already in B)
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/DynamicTrackTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/DynamicTrackBlockTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/TracksPolishTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/RailSemaphoreTest.kt` (if not already in B)
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/paths/PathTrackIntegrationTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/TrainMovementIntegrationTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/sim/SimulationScenarioTest.kt`
- Move + rewrite: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/api/TrainPublicAPITest.kt`

(Discovery in Step 6.1 is canonical.)

- [ ] **Step 6.1: Enumerate Bucket D candidates**

```bash
cd /home/beda/work/interlockSim/.worktrees/commontest-migration
grep -lrE "io\.mockk" core/src/jvmTest/kotlin > /tmp/bucket_d_raw.txt
# Files that ALSO have hard blockers stay in jvmTest with MockK
while read f; do
  if grep -qE "java\.util\.concurrent|@TempDir|java\.io\.File|System\.getProperty" "$f"; then
    echo "STAYS IN JVMTEST: $f"
  else
    echo "$f"
  fi
done < /tmp/bucket_d_raw.txt > /tmp/bucket_d_candidates.txt
grep -v "STAYS" /tmp/bucket_d_candidates.txt | tee /tmp/bucket_d_eligible.txt | wc -l
```

Expected: roughly 12-16 eligible files. Files marked STAYS keep MockK in jvmTest.

- [ ] **Step 6.2: Check whether `TrackTestMocks` is referenced by any eligible file**

```bash
grep -l "TrackTestMocks" $(cat /tmp/bucket_d_eligible.txt) || echo "NONE"
```

If NONE, skip Step 6.3 and proceed to Step 6.4.

If some files reference `TrackTestMocks`, proceed to Step 6.3.

- [ ] **Step 6.3 (conditional): Port TrackTestMocks helpers to commonMain**

Read: `core-test/src/jvmMain/kotlin/cz/vutbr/fit/interlockSim/testutil/TrackTestMocks.kt`

For each helper function the eligible Bucket D files use, port it to a new file `core-test/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/testutil/CommonTrackTestMocks.kt`, replacing MockK calls with Mokkery equivalents (see API table in Step 6.4).

Add the Mokkery plugin to `core-test/build.gradle.kts` (currently lines 12-15 of plugins block):
```kotlin
plugins {
    kotlin("multiplatform")
    id("io.gitlab.arturbosch.detekt")
    id("dev.mokkery")  // ADD THIS LINE
}
```

The plugin will auto-configure the Mokkery runtime in commonMain test scope and (because core-test is consumed by core's commonTest) Mokkery will be transitively available.

If Mokkery is in core-test commonMain (rather than test sourceSet), the `mokkery-runtime` dep needs explicit declaration since the plugin's auto-wiring targets test source sets:
```kotlin
val commonMain by getting {
    dependencies {
        // … existing deps
        api("dev.mokkery:mokkery-runtime:$mokkeryVersion")  // 'api' so consumers see it
    }
}
val mokkeryVersion: String by project  // add near top with other versions
```

Verify:
```bash
./gradlew :core-test:compileKotlinMetadata
./gradlew :core-test:compileKotlinJvm
./gradlew :core-test:compileKotlinLinuxX64
```
All compile.

- [ ] **Step 6.4: Worked example — convert ONE Bucket D file**

Pick the simplest with the fewest MockK calls (likely `TrainPhysicsTest.kt` if it just mocks one Track).

API mapping (already in spec, repeated here for engineer convenience):

| MockK | Mokkery |
|---|---|
| `import io.mockk.mockk` | `import dev.mokkery.mock` |
| `import io.mockk.every` | `import dev.mokkery.every` |
| `import io.mockk.verify` | `import dev.mokkery.verify` |
| `import io.mockk.slot` | `import dev.mokkery.matcher.capture.slot` |
| `mockk<Foo>()` | `mock<Foo>()` |
| `mockk<Foo>(relaxed = true)` | `mock<Foo>(MockMode.autofill)` (`import dev.mokkery.MockMode`) |
| `every { foo.bar() } returns x` | `every { foo.bar() } returns x` (DSL identical) |
| `every { foo.bar() } answers { … }` | `everySuspend { … } returns …` for suspend, otherwise `every { … } calls { … }` |
| `verify { foo.bar() }` | `verify { foo.bar() }` (identical) |
| `verify(exactly = 1)` | `verify(VerifyMode.exactly(1))` (`import dev.mokkery.verify.VerifyMode`) |
| `slot<T>()` + `capture(s)` | `dev.mokkery.matcher.capture.slot<T>()` + `capture(s)` |

Procedure for ONE file:
1. Replace import lines first.
2. Replace `mockk<…>` with `mock<…>` everywhere.
3. Adjust `relaxed = true` → `MockMode.autofill`.
4. Adjust `verify(exactly = N)` → `verify(VerifyMode.exactly(N))`.
5. `git mv` to commonTest target path.
6. Run on both targets:
```bash
./gradlew :core:jvmTest --tests "*<ClassName>"
./gradlew :core:linuxX64Test --tests "*<ClassName>"
```

Failure modes to expect:
- Mokkery requires the mocked type to be open or an interface. If the file mocks a `final class` (most Kotlin classes), Mokkery's compiler plugin handles it — but the `dev.mokkery` plugin must be applied to the module containing the test (which `core/build.gradle.kts:41` already does). If a `final class` mock fails on linuxX64, the class may need `open` or an interface extracted — flag as a blocker and demote the file back to jvmTest with MockK.
- Suspend functions require `everySuspend { }` instead of `every { }`. If you see "expected suspend function", apply that fix.

- [ ] **Step 6.5: Convert remaining Bucket D files**

Apply Step 6.4's pattern to each eligible file. Track demotions. After all done:
```bash
./gradlew :core:jvmTest 2>&1 | tail -10
./gradlew :core:linuxX64Test 2>&1 | tail -10
```

Both green.

- [ ] **Step 6.6: Commit Bucket D**

```bash
git add -A core core-test
git status --short
git commit -m "test(commonTest): migrate Bucket D MockK tests to commonTest with Mokkery

Replaced MockK with Mokkery 2.7.3 (multiplatform mocking) in N tests
that were previously JVM-only because of MockK. Mokkery DSL is largely
drop-in for MockK; conversions: mockk -> mock, relaxed=true -> MockMode.autofill,
verify(exactly=N) -> verify(VerifyMode.exactly(N)).

K MockK-using files stay in jvmTest because of additional JVM-only
dependencies (concurrency, filesystem) that block them from commonTest
regardless of mock framework. MockK and Mokkery temporarily coexist in
the project; full MockK->Mokkery conversion deferred to a future PR.

(If TrackTestMocks was ported:) Added CommonTrackTestMocks in
core-test/commonMain so shared mock factories work on both targets."
```
(N, K = actual counts.)

---

## Task 7: Verification & PR readiness

- [ ] **Step 7.1: Run the full quality gate**

```bash
./gradlew clean build 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL. If failures appear in modules other than `:core` (e.g., `:desktop-ui`), they are NOT caused by this PR (which only touches `:core` and `:core-test` test code) — but stop and investigate before considering the PR done.

- [ ] **Step 7.2: Run the integration test gate (per workflow rule)**

```bash
./gradlew integrationTest 2>&1 | tail -15
```

Expected: green. Per memory `feedback_push_integration_test.md`, this is required before pushing.

- [ ] **Step 7.3: Run detekt**

```bash
./gradlew :core:detekt :core-test:detekt 2>&1 | tail -10
```

Expected: green. Detekt source set already includes commonTest (line 367 of `core/build.gradle.kts`), so any new commonTest files are checked.

- [ ] **Step 7.4: Run the commonMain purity gate**

```bash
./gradlew :core:checkCoreCommonMainPurity
```

Expected: "commonMain purity check passed - no JVM-only code found." We never touched `commonMain` production code, but this confirms no accidental import leaked through.

- [ ] **Step 7.5: Capture final test counts**

```bash
./gradlew :core:jvmTest :core:linuxX64Test 2>&1 | grep -E "Tests run|Test Results" | tail -10
```

Record:
- JVM count (must be ≥ 1861 — commonTest tests still count toward JVM run).
- linuxX64 count (must be > 545 — that's the gain).

If JVM count dropped, something was lost in a move (a `@Tag("integration-test")` filtered out, or a test silently became unreferenced). Investigate before declaring done.

- [ ] **Step 7.6: Update the stale comment in core/build.gradle.kts**

The comment at lines 24-29 (in the JVM-target architecture explanation) says:

> "Remaining blockers for fully portable common code are test infrastructure (MockK/JUnit5 are JVM-only; commonTest is restricted to kotlin.test) and a Koin/xmlutil native audit on all intended targets."

This is now stale — Mokkery and xmlutil work on linuxX64. Update to reflect current state. Suggested replacement:

```
 *     below). Remaining blockers for fully portable common code are
 *     production-side concerns (xml/, context factories using JAXP);
 *     test infrastructure now supports both targets via Mokkery 2.7.3
 *     (multiplatform mocking) and xmlutil (multiplatform XML).
```

Commit:
```bash
git add core/build.gradle.kts
git commit -m "docs(core): update commonMain comment for Mokkery + xmlutil multiplatform

The commonMain note claimed MockK/JUnit5 were the only test-side options.
Mokkery 2.7.3 now provides multiplatform mocking and xmlutil already
supports linuxX64; commonTest is no longer restricted to kotlin.test
without mocks."
```

- [ ] **Step 7.7: Push and open PR**

```bash
git push -u origin feature/commontest-migration
gh pr create --base develop --title "test: migrate eligible :core jvmTest files to commonTest" --body "$(cat <<'EOF'
## Summary

Migrates ~85 of 107 :core jvmTest files into commonTest so they additionally run on the linuxX64 target. JVM coverage is preserved; native coverage rises substantially.

Per-bucket commits inside this PR:
- Task 1: Mokkery wiring smoke test
- Task 2: nativeTest BuiltinNetworksTest -> commonTest (Bucket E)
- Task 3: Bucket A trivial JUnit5 -> kotlin.test (~N files)
- Task 4: Bucket B parameterized rewrites (~N files)
- Task 5: Bucket C de-File fixture loaders (~N files; K demoted on triage)
- Task 6: Bucket D MockK -> Mokkery for moved tests (~N files; K stay in jvmTest)
- Task 7: stale-comment update

Files staying in jvmTest:
- Concurrency (CountDownLatch / AtomicInteger / TimeUnit)
- @TempDir / filesystem-write
- JvmParityReferenceTest (JVM-baseline by purpose)
- MockK-using tests with additional JVM blockers

Spec: `docs/superpowers/specs/2026-04-16-commontest-migration-design.md` (commit 4fff07e)
Plan: `docs/superpowers/plans/2026-04-16-commontest-migration.md`

## Test plan

- [ ] :core:jvmTest green and count >= 1861
- [ ] :core:linuxX64Test green and count > 545 (capture in description after final run)
- [ ] :core:integrationTest green
- [ ] :core:checkCoreCommonMainPurity green
- [ ] :core:detekt green
- [ ] clean build green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

(Substitute the actual N and K counts in the PR body before pushing.)

Per memory `feedback_no_auto_merge.md`, do NOT auto-merge. Wait for external review.

- [ ] **Step 7.8: Mark TODO for memory update**

After the PR is reviewed and merged on `develop`, the deferred memory `project_commontest_migration.md` should be updated/removed. This is tracked in TaskList task #4.

---

## Self-review notes

Spec coverage check:
- Bucket A: ✅ Task 3
- Bucket B: ✅ Task 4
- Bucket C: ✅ Task 5 (with extension to CommonTestFixtures sub-step)
- Bucket D: ✅ Task 6 (with conditional TrackTestMocks porting)
- Bucket E: ✅ Task 2
- Mokkery wiring infra: ✅ Task 1 (validation-first per "spike on simplest first" risk mitigation in spec)
- Stale-comment cleanup: ✅ Step 7.6
- Verification gates from spec: ✅ Task 7

Open items from the spec (user already decided 2 of 3):
- "OK to demote Bucket C files mid-execution" → captured as Step 5.4 procedure.
- "Update memory after merge" → captured as Task 7.8 / TaskList item #4.
- Spec filename — user did not explicitly answer, but the file is committed and not flagged for change.

Type/method consistency: helper names referenced in plan (`Resources.read`, `XmlContextReader`, `CommonTestFixtures.parseEditingContext`, `ContextTransformer`, `Mokkery: mock/every/verify/MockMode/VerifyMode`) match the spec and verified `core/build.gradle.kts` references. The TrackTestMocks port is conditional on actual usage (Step 6.2 gate).

Placeholder scan: no TBD/TODO. Two parameterized counts are intentionally written as "(N)" / "(K)" — the implementer fills them in from real discovery output, since pre-counting would be brittle (the discovery commands are canonical).
