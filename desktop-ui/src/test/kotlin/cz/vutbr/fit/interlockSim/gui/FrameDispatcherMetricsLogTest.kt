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
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
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
 * Also verifies (SP2c.22, Issue #845) that [Frame] calls
 * [DispatcherRunRecorder.finish] and [DispatcherRunRecorder.logFinalSummary] on STOPPED
 * when a [DispatcherRunRecorder] is present in scope.
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

	// ── SP2c.22 (#845) — DispatcherRunRecorder integration ───────────────────

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName(
		"SP2c.22: finish and logFinalSummary called on DispatcherRunRecorder when simulation stops (MANUAL_STOP path)"
	)
	fun runRecorderFinishAndLogCalledOnStop() {
		val started = CountDownLatch(1)
		val context = createMockSimulationContext(TestFixtures.loadShuntingXml())
		context.addPropertyChangeListener { _ -> started.countDown() }

		val runRecorder = mockk<DispatcherRunRecorder>(relaxed = true)
		context.scope.declare(runRecorder)

		SwingUtilities.invokeAndWait {
			frame.setContext(context)
			frame.startSimulation()
		}
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		SwingUtilities.invokeAndWait { frame.stopSimulation() }

		verify(exactly = 1) { runRecorder.finish(any()) }
		verify(exactly = 1) { runRecorder.logFinalSummary() }
		confirmVerified(runRecorder)
		context.close()
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("SP2c.22: stopping without a DispatcherRunRecorder in scope does not throw")
	fun noThrowWhenRunRecorderAbsent() {
		val started = CountDownLatch(1)
		val context = createMockSimulationContext(TestFixtures.loadShuntingXml())
		context.addPropertyChangeListener { _ -> started.countDown() }

		SwingUtilities.invokeAndWait {
			frame.setContext(context)
			frame.startSimulation()
		}
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		// Must not throw even though context.scope has no DispatcherRunRecorder registered.
		SwingUtilities.invokeAndWait { frame.stopSimulation() }

		context.close()
	}
}
