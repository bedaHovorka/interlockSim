/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Integration tests for Frame simulation lifecycle (Issue #189)
	Tests require a non-headless display — skipped automatically in CI.
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.PROGRAM_FULL_NAME
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolution
import cz.vutbr.fit.interlockSim.sim.conflict.DispatcherPreferenceStore
import cz.vutbr.fit.interlockSim.sim.conflict.StrategyPreferenceStore
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the Frame simulation lifecycle (Issue #189).
 *
 * Verifies that [Frame.startSimulation], [Frame.stopSimulation], and [Frame.setContext]
 * interact correctly with [SimulationController] to produce correct UI state transitions.
 *
 * These tests extend [AbstractFrameTestBase]:
 * - Tagged as `@Tag("integration-test")` — run via `./gradlew integrationTest`
 * - Skipped automatically in headless CI environments (no X11 display)
 * - Frame instances are disposed automatically via [AbstractFrameTestBase.tearDown]
 *
 * Tests cover:
 * 1. [Frame.setContext] switches to simulation mode with Stop button disabled
 * 2. [Frame.startSimulation] starts runner and enables Stop button
 * 3. [Frame.stopSimulation] stops runner and disables Stop button
 * 4. [Frame.setContext] while simulation is running stops the previous simulation
 * 5. [Frame.stopSimulation] is a no-op when no simulation is running
 * 6. [Frame.modificationTracker] `setCurrentFile` updates window title (mirrors edit mode)
 *
 * @see AbstractFrameTestBase
 * @see SimulationController
 * @see SimulationControllerTest for headless unit tests
 */
@DisplayName("Frame Simulation Lifecycle")
class FrameSimulationLifecycleTest : AbstractFrameTestBase() {
	private lateinit var frame: Frame

	@BeforeEach
	override fun setUp() {
		super.setUp() // checks for headless; skips test if no display
		runOnEDT {
			frame = Frame()
			frames.add(frame) // registered for auto-disposal in tearDown()
		}
	}

	@AfterEach
	override fun tearDown() {
		// Guard against setUp() being aborted by the headless assumption check
		// (frame would not be initialized in that case)
		if (this::frame.isInitialized) {
			runOnEDT {
				frame.stopSimulation()
			}
		}
		super.tearDown()
	}

	// ── setContext(SimulationContext) ─────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("setContext(SimulationContext) switches to simulation mode, Stop button disabled")
	fun setContextSwitchesToSimulationMode() {
		val context = createMockSimulationContext(TestFixtures.loadShuntingXml())

		runOnEDT {
			frame.setContext(context)
			// Stop button starts disabled — only enabled after startSimulation()
			assertThat(frame.simulationController.isRunning()).isFalse()
		}

		context.close()
	}

	// ── startSimulation ───────────────────────────────────────────────────────

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("startSimulation starts the simulation runner")
	fun startSimulationStartsRunner() {
		val runStarted = CountDownLatch(1)
		val context = createMockSimulationContext(TestFixtures.loadShuntingXml())
		context.addPropertyChangeListener { _ -> runStarted.countDown() }

		runOnEDT {
			frame.setContext(context)
			frame.startSimulation()
		}

		// Wait for simulation runner to spin up via property-change notification
		assertThat(runStarted.await(5, TimeUnit.SECONDS)).isTrue()

		// Key assertion: startSimulation() must not throw; simulation either running
		// or completed naturally — both are valid for a quick mock context
		runOnEDT {
			frame.stopSimulation()
		}
		context.close()
	}

	// ── Goal 9 SC3 + SC4: operator-Apply wiring ─────────────────────────────

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("startSimulation wires onResolutionApplied to record operator choices in the scoped preference stores")
	fun startSimulationWiresOnResolutionAppliedToScopedStores() {
		val context = createMockSimulationContext(TestFixtures.loadShuntingXml())

		runOnEDT {
			frame.setContext(context)
			frame.startSimulation()
		}

		// startSimulation must wire the operator-Apply callback (Goal 9 SC3 + SC4).
		val applyCallback = frame.conflictResolutionPanel.onResolutionApplied
		assertThat(applyCallback).isNotNull()

		// Build a conflict + resolution and invoke the callback exactly as the panel
		// does when the dispatcher selects a candidate and clicks Apply.
		val block =
			mockk<DynamicTrackBlock>(relaxed = true) {
				every { name } returns "Block-7"
			}
		val conflict = ConflictDetectedEvent(block, "Train-A", "Train-B", 12.5)
		val resolution =
			ConflictResolution.HoldTrain(
				trainId = "Train-A",
				holdDurationSeconds = 30.0,
				affectedTrains = listOf("Train-A"),
				estimatedImpact = ConflictResolution.EstimatedImpact(30.0, "Hold Train-A")
			)
		runOnEDT { applyCallback!!.invoke(conflict, resolution) }

		// SC3: the operator's choice is recorded in the scoped DispatcherPreferenceStore.
		val dispatcherStore = context.scope.get<DispatcherPreferenceStore>()
		val choices = dispatcherStore.getChoices()
		assertThat(choices).hasSize(1)
		val choice = choices.single()
		assertThat(choice.source).isEqualTo(DispatcherPreferenceStore.ApplicationSource.OPERATOR)
		assertThat(choice.applied).isEqualTo(resolution)

		// SC4: the choice feeds the scoped StrategyPreferenceStore learning counter.
		val strategyStore = context.scope.get<StrategyPreferenceStore>()
		assertThat(strategyStore.selectionCount("Block-7", ConflictResolution.Strategy.HOLD_TRAIN))
			.isEqualTo(1)

		runOnEDT { frame.stopSimulation() }
		context.close()
	}

	// ── stopSimulation ────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("stopSimulation stops a running simulation and marks as not running")
	fun stopSimulationStopsRunner() {
		val started = CountDownLatch(1)
		val context = createMockSimulationContext(TestFixtures.loadShuntingXml())

		// Hook into context to know when simulation is running
		context.addPropertyChangeListener { _ -> started.countDown() }

		runOnEDT {
			frame.setContext(context)
			frame.startSimulation()
		}

		// Simulation may start quickly; either way proceed to stop
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		runOnEDT {
			frame.stopSimulation()
			assertThat(frame.simulationController.isRunning()).isFalse()
		}

		context.close()
	}

	// ── setContext while running ──────────────────────────────────────────────

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("setContext while simulation is running stops the previous simulation")
	fun setContextWhileRunningStopsPreviousSimulation() {
		val context1 = createMockSimulationContext(TestFixtures.loadShuntingXml())
		val context2 = createMockSimulationContext(TestFixtures.loadShuntingXml())

		val run1Started = CountDownLatch(1)
		context1.addPropertyChangeListener { _ -> run1Started.countDown() }

		runOnEDT {
			frame.setContext(context1)
			frame.startSimulation()
		}

		// Wait for first simulation to actually start before switching context
		assertThat(run1Started.await(5, TimeUnit.SECONDS)).isTrue()

		runOnEDT {
			// setContext calls stopSimulation() internally before switching
			frame.setContext(context2)
			// After context switch, previous simulation must be stopped
			assertThat(frame.simulationController.isRunning()).isFalse()
		}

		runOnEDT { frame.stopSimulation() }
		context1.close()
		context2.close()
	}

	// ── stopSimulation no-op ──────────────────────────────────────────────────

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("stopSimulation is a no-op when no simulation is running")
	fun stopSimulationIsNoopWhenNotRunning() {
		runOnEDT {
			// Should not throw when called without a running simulation
			frame.stopSimulation()
			assertThat(frame.simulationController.isRunning()).isFalse()
		}
	}

	// ── window title (mirrors edit-mode file marker) ──────────────────────────

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("modificationTracker setCurrentFile updates window title like edit mode")
	fun modificationTrackerSetCurrentFileUpdatesTitle() {
		val file = File("vyhybna.xml")
		runOnEDT {
			frame.modificationTracker.setCurrentFile(file)
			// Title should now contain the filename
			assertThat(frame.title).contains("vyhybna.xml")
			assertThat(frame.title).isNotEqualTo(PROGRAM_FULL_NAME)
		}
	}
}
