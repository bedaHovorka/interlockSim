# Dispatcher Final Metrics Log on Simulation Stop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guarantee a final `PlannerMetricsSnapshot` log line whenever the `shuntingLoopAI` GUI example's simulation stops (natural completion or manual Stop), closing the gap where a run ending between `MeasuringPlanAdapter`'s periodic checkpoints leaves stale stats.

**Architecture:** Add an unconditional `logFinalSummary()` method to `MeasuringPlanAdapter` (dispatcher-agent). Register the adapter instance into the `DefaultSimulationContext`'s Koin `scope` at construction time (`ExampleRegistry.createShuntingLoopAIGuiExample`) so it's retrievable later. Hook Frame's existing `SimulationController.SimulationStatus.STOPPED` branch — which already fires for both natural completion and manual stop — to resolve the adapter from `context.scope` and call `logFinalSummary()`.

**Tech Stack:** Kotlin, kotlin-logging (`KotlinLogging`), Koin 3.5.6 (`Scope.declare`/`Scope.getOrNull`), JUnit 5, AssertK, MockK.

## Global Constraints

- English only in all log messages, comments, commit messages (CLAUDE.md).
- No `[WIP]` in commit messages.
- Do not describe routine ktlint/detekt formatting fixes in commit messages — only substantive changes.
- Every modified file must pass `./gradlew detekt ktlintCheck` for its subproject.
- `dispatcher-agent` and `desktop-ui` are NOT the `sim/` package — Koin injection and modernization are unrestricted here (CLAUDE.md "Flexible Development" rules apply).
- Scope is exactly: the existing `shuntingLoopAI` GUI example. No new entry points, no GUI dialog, no `fast-sim` changes, no changes to `SimulationController` itself.

---

### Task 1: `MeasuringPlanAdapter.logFinalSummary()`

**Files:**
- Modify: `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/planner/MeasuringPlanAdapter.kt`
- Test: `dispatcher-agent/src/test/kotlin/cz/vutbr/fit/interlockSim/dispatcher/planner/MeasuringPlanAdapterTest.kt`

**Interfaces:**
- Produces: `MeasuringPlanAdapter.logFinalSummary(): Unit` — public, no parameters. Reads `getMetricsSnapshot()` (existing) and logs one INFO line unconditionally, regardless of `REPORT_EVERY_N_CYCLES` alignment or whether zero cycles have run. Task 3 calls this.

- [ ] **Step 1: Write the failing test — `logFinalSummary` logs at any cycle count**

Add a new nested test class at the end of `MeasuringPlanAdapterTest.kt`, right before the final closing `}` of the `MeasuringPlanAdapterTest` class (i.e. after the `SuccessRateBoundary` nested class, currently ending at line 395):

```kotlin
	// ── logFinalSummary ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("logFinalSummary logs unconditionally")
	inner class LogFinalSummary {
		@Test
		fun `does not throw with zero cycles recorded`() {
			val agent = mockk<KoogDispatchAgent>()
			val fallback = mockk<Dispatcher>()
			val adapter = measuring(agent, fallback)

			adapter.logFinalSummary() // must not throw

			// Calling it must not mutate the counters.
			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.totalCycles).isZero()
		}

		@Test
		fun `does not throw with a cycle count not aligned to REPORT_EVERY_N_CYCLES`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			// 3 cycles — not a multiple of MeasuringPlanAdapter.REPORT_EVERY_N_CYCLES (10).
			repeat(3) { runBlocking { adapter.plan(observation) } }

			adapter.logFinalSummary() // must not throw

			val snapshot = adapter.getMetricsSnapshot()
			assertThat(snapshot.totalCycles).isEqualTo(3L)
		}

		@Test
		fun `does not mutate counters when called multiple times`() {
			val agent = mockk<KoogDispatchAgent>()
			coEvery { agent.decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
			val adapter = measuring(agent, fallback)

			runBlocking { adapter.plan(observation) }
			adapter.logFinalSummary()
			adapter.logFinalSummary()
			adapter.logFinalSummary()

			assertThat(adapter.getMetricsSnapshot().totalCycles).isEqualTo(1L)
		}
	}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :dispatcher-agent:test --tests "cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapterTest"`

Expected: compilation FAILS with `Unresolved reference: logFinalSummary` (the method does not exist yet).

- [ ] **Step 3: Implement `logFinalSummary()`, sharing the log-line format with `logPeriodicSummary()`**

`logPeriodicSummary` (in the `// ── Internal helpers ──` section) already builds the same
`byReasonStr` + log-line shape this new method needs. Extract a shared private helper
instead of duplicating that logic, and have both methods use it.

In `MeasuringPlanAdapter.kt`, add the new public method directly after `getMetricsSnapshot()`
(which currently ends at line 131), before the `// ── Internal helpers ──` divider (line 133):

```kotlin
	/**
	 * Logs an unconditional INFO-level summary of the current [PlannerMetricsSnapshot].
	 *
	 * Unlike [logPeriodicSummary] (which only fires every [REPORT_EVERY_N_CYCLES] cycles),
	 * this always logs exactly once per call — intended for callers that detect the
	 * simulation has stopped (for any reason: natural completion or manual stop) and want
	 * a guaranteed final data point, even if the run ended between periodic checkpoints.
	 *
	 * Safe to call with zero cycles recorded — [PlannerMetricsSnapshot.ollamaSuccessRate]
	 * is `0.0` in that case. Read-only: does not mutate any counters, so it is safe to
	 * call more than once (e.g. defensively from multiple call sites).
	 */
	fun logFinalSummary() {
		logger.info { formatSummaryLine("final summary", getMetricsSnapshot()) }
	}
```

Then replace the existing `logPeriodicSummary` method (currently lines 135-150) — extracting
the line-building logic into the new private `formatSummaryLine` helper — with:

```kotlin
	private fun logPeriodicSummary(simTime: Double) {
		val snapshot = getMetricsSnapshot()
		if (snapshot.totalCycles > 0L && snapshot.totalCycles % REPORT_EVERY_N_CYCLES == 0L) {
			logger.info { formatSummaryLine("summary at simTime=${simTime}s", snapshot) }
		}
	}

	/** Builds the shared `[MeasuringPlanAdapter] <label> — totalCycles=... successRate=...` log line. */
	private fun formatSummaryLine(
		label: String,
		snapshot: PlannerMetricsSnapshot
	): String {
		val byReasonStr =
			FallbackReason.entries.joinToString(", ") { reason ->
				"${reason.name}=${snapshot.fallbacksByReason[reason] ?: 0}"
			}
		return "[MeasuringPlanAdapter] $label — " +
			"totalCycles=${snapshot.totalCycles} " +
			"ollamaSuccess=${snapshot.ollamaSuccessCount} " +
			"fallback=${snapshot.fallbackCount} ($byReasonStr) " +
			"successRate=${formatRate(snapshot.ollamaSuccessRate)}"
	}
```

This must NOT change `logPeriodicSummary`'s existing log output — `formatSummaryLine("summary at simTime=${simTime}s", snapshot)` reconstructs the exact same text it produced before ("[MeasuringPlanAdapter] summary at simTime=Xs — totalCycles=..."). No test in this file asserts on log text (verified: this file has no log-capture tests), so this refactor is safe.

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :dispatcher-agent:test --tests "cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapterTest"`

Expected: PASS, all tests in the class including the three new ones.

- [ ] **Step 5: Quality gates**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :dispatcher-agent:detekt :dispatcher-agent:ktlintCheck`

Expected: PASS. If ktlint fails on formatting only, run `:dispatcher-agent:ktlintFormat` and re-check the diff before re-running `ktlintCheck`.

- [ ] **Step 6: Commit**

```bash
git add dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/planner/MeasuringPlanAdapter.kt \
        dispatcher-agent/src/test/kotlin/cz/vutbr/fit/interlockSim/dispatcher/planner/MeasuringPlanAdapterTest.kt
git commit -m "$(cat <<'EOF'
feat(dispatcher-agent): add MeasuringPlanAdapter.logFinalSummary()

Guarantees one final PlannerMetricsSnapshot log line regardless of
REPORT_EVERY_N_CYCLES alignment, so a run stopping between periodic
checkpoints does not leave stale stats as the last visible entry.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Register `MeasuringPlanAdapter` into the context's Koin scope

**Files:**
- Modify: `desktop-ui/src/main/kotlin/cz/vutbr/fit/interlockSim/ExampleRegistry.kt:280-296` (`createShuntingLoopAIGuiExample`)
- Test: `desktop-ui/src/test/kotlin/cz/vutbr/fit/interlockSim/ExampleLoadingTest.kt`

**Interfaces:**
- Consumes: `MeasuringPlanAdapter` (Task 1, already constructed as local `aiPlanner` in this function — no signature change).
- Produces: after this task, `context.scope.getOrNull<MeasuringPlanAdapter>()` returns the same instance used as `plannerOverride` for any `SimulationContext` built via the `shuntingLoopAI` example. Task 3 consumes this.

- [ ] **Step 1: Write the failing test**

Add a new nested class in `desktop-ui/src/test/kotlin/cz/vutbr/fit/interlockSim/ExampleLoadingTest.kt`, inserted immediately after line 264 (the closing `}` of the existing `ShuntingLoopFactoryTests` inner class) and before line 266 (`@Nested`, the start of the next nested class):

```kotlin
	@Nested
	@DisplayName("ShuntingLoopAI Example Factory")
	inner class ShuntingLoopAIFactoryTests {
		/**
		 * Test that createShuntingLoopAIGuiExample registers its MeasuringPlanAdapter into
		 * the context's Koin scope, so callers outside ExampleRegistry (e.g. Frame's
		 * SimulationController.STOPPED handler) can retrieve it after the run ends.
		 */
		@Test
		fun `createShuntingLoopAIGuiExample registers MeasuringPlanAdapter in context scope`() {
			val registry = get<ExampleRegistry>()
			val createMethod =
				ExampleRegistry::class.java.getDeclaredMethod(
					"createShuntingLoopAIGuiExample",
					cz.vutbr.fit.interlockSim.context.SimulationContextFactory::class.java,
					Array<String>::class.java
				)
			createMethod.isAccessible = true
			val factory = get<cz.vutbr.fit.interlockSim.context.SimulationContextFactory>()
			val args = arrayOf("exampleGui", "shuntingLoopAI", "100")

			try {
				val result = createMethod.invoke(registry, factory, args)
				val context = result as cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
				assertThat(
					context.scope.getOrNull<cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter>()
				).isNotNull()
			} catch (e: java.lang.reflect.InvocationTargetException) {
				val cause = e.cause
				// ContextCreationException is acceptable if vyhybna.xml isn't found in the
				// test environment — mirrors the tolerance in ShuntingLoopFactoryTests above.
				if (cause is ContextCreationException) {
					assertThat((cause.message ?: "").contains("vyhybna.xml")).isTrue()
				} else {
					throw e
				}
			}
		}
	}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktop-ui:test --tests "cz.vutbr.fit.interlockSim.ExampleLoadingTest"`

Expected: the new test FAILS with an AssertK failure — `getOrNull<MeasuringPlanAdapter>()` returns `null` (nothing declares it into scope yet).

- [ ] **Step 3: Register the adapter into scope**

In `desktop-ui/src/main/kotlin/cz/vutbr/fit/interlockSim/ExampleRegistry.kt`, inside `createShuntingLoopAIGuiExample` (lines 280-293), add `context.scope.declare(aiPlanner)` immediately after constructing `aiPlanner`:

```kotlin
				val koogAdapter =
					KoogAgentPlanAdapter(
						agentFactory = context.scope.get<KoogAgentFactory>(),
						context = context,
						fallbackDispatcher = RuleBasedDispatcher(),
						commandQueue = context.scope.get<ActuatorCommandQueue>()
					)
				val aiPlanner = MeasuringPlanAdapter(koogAdapter)
				// Register in scope so callers outside this factory (e.g. Frame's
				// SimulationController.STOPPED handler) can retrieve it after the run ends
				// and log a final summary — see MeasuringPlanAdapter.logFinalSummary().
				context.scope.declare(aiPlanner)
				wireDispatcherAgent(
					context,
					loop,
					context.scope.get<DelegatingSimulationController>(),
					plannerOverride = aiPlanner
				)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktop-ui:test --tests "cz.vutbr.fit.interlockSim.ExampleLoadingTest"`

Expected: PASS, including all previously-passing tests in this file (no regressions).

- [ ] **Step 5: Quality gates**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktop-ui:detekt :desktop-ui:ktlintCheck`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add desktop-ui/src/main/kotlin/cz/vutbr/fit/interlockSim/ExampleRegistry.kt \
        desktop-ui/src/test/kotlin/cz/vutbr/fit/interlockSim/ExampleLoadingTest.kt
git commit -m "$(cat <<'EOF'
feat(desktop-ui): register MeasuringPlanAdapter into context scope

The shuntingLoopAI example built its MeasuringPlanAdapter as a local
that was discarded after wireDispatcherAgent, leaving nothing outside
ExampleRegistry able to reach it. Declaring it into context.scope makes
it retrievable via getOrNull() once the run stops.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Log final summary from Frame's `STOPPED` handler

**Files:**
- Modify: `desktop-ui/src/main/kotlin/cz/vutbr/fit/interlockSim/gui/Frame.kt:202-217`
- Test (new): `desktop-ui/src/test/kotlin/cz/vutbr/fit/interlockSim/gui/FrameDispatcherMetricsLogTest.kt`

**Interfaces:**
- Consumes: `context.scope.getOrNull<MeasuringPlanAdapter>()` (Task 2) and `MeasuringPlanAdapter.logFinalSummary()` (Task 1).
- Produces: nothing consumed by later tasks — this is the final integration point.

- [ ] **Step 1: Write the failing test**

Create `desktop-ui/src/test/kotlin/cz/vutbr/fit/interlockSim/gui/FrameDispatcherMetricsLogTest.kt`:

```kotlin
/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Integration test: Frame logs a final dispatcher metrics summary on simulation stop
	Tests require a non-headless display — skipped automatically in CI.
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Verifies that [Frame]'s `SimulationController.SimulationStatus.STOPPED` handler logs a
 * final dispatcher metrics summary when a [MeasuringPlanAdapter] is registered in the
 * active [cz.vutbr.fit.interlockSim.context.SimulationContext]'s Koin scope (the
 * shuntingLoopAI example wiring — see ExampleRegistry.createShuntingLoopAIGuiExample), and
 * that it does nothing for contexts without one (every other example).
 *
 * Both tests drive the STOPPED transition via [Frame.stopSimulation] (manual stop). This is
 * intentionally the only path exercised here: Frame's `onStateChanged` lambda has a single
 * `STOPPED` branch that [SimulationController] invokes identically whether the run stopped
 * manually (`SimulationController.stop()`) or finished naturally (the monitor thread in
 * `SimulationController.launchMonitorThread`) — see `SimulationController.kt:208-209` vs
 * `:232`, both call `onStateChanged(SimulationStatus.STOPPED)`. That the monitor thread
 * reaches the same STOPPED emission on natural completion is already covered by
 * `SimulationControllerTest.onCompletedInvokedOnNaturalFinish`; re-deriving it here would
 * only re-test `SimulationController`, not the new Frame-level lookup logic.
 *
 * Extends [AbstractFrameTestBase]:
 * - Tagged as `@Tag("integration-test")` — run via `./gradlew integrationTest`
 * - Skipped automatically in headless CI environments (no X11 display)
 *
 * @see FrameSimulationLifecycleTest for the broader Frame simulation lifecycle test suite
 * @see SimulationControllerTest.onCompletedInvokedOnNaturalFinish for proof that natural
 *   completion reaches the identical STOPPED emission exercised here via manual stop
 */
@DisplayName("Frame dispatcher final metrics log")
class FrameDispatcherMetricsLogTest : AbstractFrameTestBase() {
	private lateinit var frame: Frame

	@BeforeEach
	override fun setUp() {
		super.setUp() // checks for headless; skips test if no display
		SwingUtilities.invokeAndWait {
			frame = Frame()
			frames.add(frame) // registered for auto-disposal in tearDown()
		}
	}

	@AfterEach
	override fun tearDown() {
		if (this::frame.isInitialized) {
			SwingUtilities.invokeAndWait { frame.stopSimulation() }
		}
		super.tearDown()
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("logFinalSummary is called when a MeasuringPlanAdapter is in scope and the simulation stops")
	fun logsFinalSummaryWhenAdapterPresent() {
		val started = CountDownLatch(1)
		val context = createMockSimulationContext(TestFixtures.loadShuntingXml())
		context.addPropertyChangeListener { _ -> started.countDown() }

		val measuringAdapter = mockk<MeasuringPlanAdapter>(relaxed = true)
		context.scope.declare(measuringAdapter)

		SwingUtilities.invokeAndWait {
			frame.setContext(context)
			frame.startSimulation()
		}
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		SwingUtilities.invokeAndWait { frame.stopSimulation() }

		verify(exactly = 1) { measuringAdapter.logFinalSummary() }
		confirmVerified(measuringAdapter)
		context.close()
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("stopping a simulation without a MeasuringPlanAdapter in scope does not throw")
	fun noThrowWhenAdapterAbsent() {
		val started = CountDownLatch(1)
		val context = createMockSimulationContext(TestFixtures.loadShuntingXml())
		context.addPropertyChangeListener { _ -> started.countDown() }

		SwingUtilities.invokeAndWait {
			frame.setContext(context)
			frame.startSimulation()
		}
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		// Must not throw even though context.scope has no MeasuringPlanAdapter registered.
		SwingUtilities.invokeAndWait { frame.stopSimulation() }

		context.close()
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktop-ui:integrationTest --tests "cz.vutbr.fit.interlockSim.gui.FrameDispatcherMetricsLogTest"`

Expected: `logsFinalSummaryWhenAdapterPresent` FAILS — `verify(exactly = 1) { measuringAdapter.logFinalSummary() }` reports 0 invocations (Frame does not look up or call it yet). `noThrowWhenAdapterAbsent` passes already (nothing new happens on that path yet) — that's fine, it becomes a genuine regression guard once Step 3 lands.

- [ ] **Step 3: Wire the STOPPED handler**

In `desktop-ui/src/main/kotlin/cz/vutbr/fit/interlockSim/gui/Frame.kt`:

Add the import after the existing `cz.vutbr.fit.interlockSim.context.*` imports (alphabetically among the existing block, e.g. right after line 16's `SimulationContext` import):

```kotlin
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.gui.animation.ControlPanel
```

Then in the `STOPPED` branch (currently lines 202-217), add the lookup as the first statement:

```kotlin
						SimulationController.SimulationStatus.STOPPED -> {
							// Log the dispatcher's final PlannerMetricsSnapshot before any other
							// STOPPED cleanup runs. Null-tolerant: only the shuntingLoopAI example
							// registers a MeasuringPlanAdapter in scope; every other example is a
							// silent no-op here (see ExampleRegistry.createShuntingLoopAIGuiExample).
							currentSimulationContext?.scope?.getOrNull<MeasuringPlanAdapter>()?.logFinalSummary()
							toolBar.hideSimulationControls()
							simulationControlPanel.runner = null
							// Detach the decision sink first so the sim thread can no longer push
							// applied decisions into the panel, then clear panel state (Issue #561).
							wiredDecisionHub?.setSink(null)
							wiredDecisionHub = null
							// Detach the SEMI_AUTO approver so the sim thread cannot call a stale
							// dialog after the GUI is torn down (Issue #806, SP2b.6 follow-up).
							wiredSemiAutoGateway?.setApprover(null)
							wiredSemiAutoGateway = null
							dispatcherControlPanel.modeState = null
							dispatcherControlPanel.clearRationale()
							controlPanel.setStopEnabled(false)
							controlPanel.updateStatus(ControlPanel.SimulationStatus.STOPPED)
						}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktop-ui:integrationTest --tests "cz.vutbr.fit.interlockSim.gui.FrameDispatcherMetricsLogTest"`

Expected: PASS for both `logsFinalSummaryWhenAdapterPresent` and `noThrowWhenAdapterAbsent`.

- [ ] **Step 5: Run the full existing Frame simulation lifecycle suite for regressions**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktop-ui:integrationTest --tests "cz.vutbr.fit.interlockSim.gui.FrameSimulationLifecycleTest" --tests "cz.vutbr.fit.interlockSim.gui.SimulationControllerTest"`

Expected: PASS, no regressions from the new import or STOPPED-branch change.

- [ ] **Step 6: Quality gates**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktop-ui:detekt :desktop-ui:ktlintCheck`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add desktop-ui/src/main/kotlin/cz/vutbr/fit/interlockSim/gui/Frame.kt \
        desktop-ui/src/test/kotlin/cz/vutbr/fit/interlockSim/gui/FrameDispatcherMetricsLogTest.kt
git commit -m "$(cat <<'EOF'
feat(desktop-ui): log final dispatcher metrics when simulation stops

Frame's STOPPED handler already fires for both natural completion and
manual Stop. Resolve MeasuringPlanAdapter from the context's Koin scope
there and log its final summary — guarantees the shuntingLoopAI example
always ends with an up-to-date fallback/success-rate log line, even if
the run stopped between MeasuringPlanAdapter's periodic checkpoints.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Full verification sweep

**Files:** None (verification only).

**Interfaces:** None — this task runs the project-wide gate before considering the feature done.

- [ ] **Step 1: Run the full build and test suite**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew clean build detekt ktlintCheck test integrationTest`

Expected: BUILD SUCCESSFUL, all tests pass (no regressions in `:dispatcher-agent` or `:desktop-ui`, or elsewhere).

- [ ] **Step 2: Manual smoke check (optional but recommended given this touches Frame)**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew runExampleAIGui` (requires a display; requires Ollama running for the LLM path to actually produce success cycles — fallback-only cycles are fine too since the log line fires either way). Let it run briefly, then close the window or use the Stop button. Confirm a line matching `[MeasuringPlanAdapter] final summary — totalCycles=...` appears in the console/log output exactly once per run.

- [ ] **Step 3: Final commit (if Step 2 required any follow-up fixes)**

Only if Step 2 uncovered an issue requiring a code change — commit that fix separately with its own descriptive message. If no changes were needed, this task requires no commit.
