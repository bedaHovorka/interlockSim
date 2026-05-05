/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for SimulationController (Issue #189)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.gui.animation.ControlPanel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * Unit tests for [SimulationController].
 *
 * [SimulationController] encapsulates all simulation lifecycle logic extracted from
 * [Frame], enabling it to be tested headlessly (no X11 display required). [ControlPanel]
 * is a [javax.swing.JPanel] that can be created in headless mode.
 *
 * Covers:
 * - [SimulationController.start] creates and starts a [SimulationRunner]
 * - [SimulationController.start] enables the Stop button and updates status to "Running"
 * - [SimulationController.start] is idempotent while runner is alive
 * - [SimulationController.stop] interrupts the runner and disables the Stop button
 * - [SimulationController.stop] is a no-op when nothing is running
 * - Monitor thread invokes [onCompleted] callback when simulation finishes naturally
 * - Monitor thread updates ControlPanel to "Stopped" after natural completion
 * - [SimulationController.isRunning] reflects the runner state correctly
 * - [SimulationController.runner] field is null before start and after stop
 * - Race condition fix: [SimulationRunner.start] is called before the monitor thread
 * - Stale-monitor fix: stop+start does not let old monitor clobber new run's panel state
 */
@DisplayName("SimulationController")
class SimulationControllerTest {
	private lateinit var controlPanel: ControlPanel
	private lateinit var context: SimulationContext
	private lateinit var context2: SimulationContext

	@BeforeEach
	fun setUp() {
		// ControlPanel is a JPanel subclass; creating it requires the EDT in strict Swing
		// environments. mockk does not require EDT.
		SwingUtilities.invokeAndWait {
			controlPanel = ControlPanel()
		}
		context = mockk(relaxed = true)
		context2 = mockk(relaxed = true)
	}

	// ── start: Stop button ────────────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("start enables stop button while simulation is running")
	fun startEnablesStopButton() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)

		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		SwingUtilities.invokeAndWait {
			assertThat(findStopButton()!!.isEnabled).isTrue()
		}

		blockSim.countDown()
		controller.stop()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("stop disables stop button")
	fun stopDisablesStopButton() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		controller.stop()
		blockSim.countDown()

		SwingUtilities.invokeAndWait {
			assertThat(findStopButton()!!.isEnabled).isFalse()
		}
	}

	// ── start: status label ───────────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("start sets status label to Running")
	fun startSetsStatusRunning() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		SwingUtilities.invokeAndWait {
			assertThat(findStatusLabel()!!.text).isEqualTo("Status: Running")
		}

		blockSim.countDown()
		controller.stop()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("stop sets status label to Stopped")
	fun stopSetsStatusStopped() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		controller.stop()
		blockSim.countDown()

		SwingUtilities.invokeAndWait {
			assertThat(findStatusLabel()!!.text).isEqualTo("Status: Stopped")
		}
	}

	// ── isRunning ─────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("isRunning returns true while simulation thread is alive")
	fun isRunningTrueWhileSimRunning() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		assertThat(controller.isRunning()).isTrue()

		blockSim.countDown()
		controller.stop()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("isRunning returns false before start is called")
	fun isRunningFalseBeforeStart() {
		val controller = SimulationController(controlPanel)
		assertThat(controller.isRunning()).isFalse()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("isRunning returns false after stop")
	fun isRunningFalseAfterStop() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
		controller.stop()
		blockSim.countDown()

		assertThat(controller.isRunning()).isFalse()
	}

	// ── stop no-op ────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("stop is a no-op when nothing is running")
	fun stopNoOpWhenNotRunning() {
		val controller = SimulationController(controlPanel)
		controller.stop() // must not throw
		controller.stop()
		assertThat(controller.isRunning()).isFalse()
	}

	// ── idempotent start ──────────────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("start is idempotent — second call while running is a no-op")
	fun startIdempotent() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		var runCount = 0
		every { context.run() } answers {
			runCount++
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		controller.start(context)
		controller.start(context)

		blockSim.countDown()
		controller.stop()

		assertThat(runCount).isEqualTo(1)
	}

	// ── runner field ──────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("runner is null before start")
	fun runnerNullBeforeStart() {
		val controller = SimulationController(controlPanel)
		assertThat(controller.runner).isNull()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("runner is non-null while running")
	fun runnerNonNullWhileRunning() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
		assertThat(controller.runner).isNotNull()

		blockSim.countDown()
		controller.stop()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("runner is null after stop")
	fun runnerNullAfterStop() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
		controller.stop()
		blockSim.countDown()

		assertThat(controller.runner).isNull()
	}

	// ── onCompleted callback ──────────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("onCompleted is invoked when simulation finishes naturally")
	fun onCompletedInvokedOnNaturalFinish() {
		val completedLatch = CountDownLatch(1)
		every { context.run() } answers { /* returns immediately */ }

		val controller = SimulationController(controlPanel) { completedLatch.countDown() }
		controller.start(context)

		assertThat(completedLatch.await(5, TimeUnit.SECONDS)).isTrue()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("onCompleted resets ControlPanel status to Stopped after natural finish")
	fun onCompletedResetsPanelOnNaturalFinish() {
		val completedLatch = CountDownLatch(1)
		every { context.run() } answers { /* returns immediately */ }

		val controller = SimulationController(controlPanel) { completedLatch.countDown() }
		controller.start(context)

		assertThat(completedLatch.await(5, TimeUnit.SECONDS)).isTrue()
		SwingUtilities.invokeAndWait { /* flush EDT */ }

		SwingUtilities.invokeAndWait {
			assertThat(findStopButton()!!.isEnabled).isFalse()
			assertThat(findStatusLabel()!!.text).isEqualTo("Status: Stopped")
		}
	}

	// ── context.run() is called ───────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("start invokes context.run()")
	fun startInvokesContextRun() {
		every { context.run() } answers { /* returns immediately */ }

		val completedLatch = CountDownLatch(1)
		val controller = SimulationController(controlPanel) { completedLatch.countDown() }
		controller.start(context)

		assertThat(completedLatch.await(5, TimeUnit.SECONDS)).isTrue()
		verify { context.run() }
	}

	// ── race condition fix: runner.start() called before monitor thread ────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("stop after start always interrupts the simulation (no race condition)")
	fun stopAlwaysInterruptsAfterStart() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)

		// Wait for simulation to actually start running.
		// Race condition being tested: in the old design, runner.start() was called
		// inside the monitor thread. If stop() was invoked before the monitor thread
		// ran, stop() would interrupt nothing and the simulation would still start
		// afterwards. The fix: runner.start() is called synchronously in start()
		// before the monitor thread is launched, so stop() always finds a live thread.
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		// stop() must always see a running thread (runner.start() was called synchronously)
		controller.stop()
		blockSim.countDown()

		assertThat(controller.isRunning()).isFalse()
	}

	// ── stale-monitor fix: stop+start doesn't let old monitor clobber new run ──

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("start-stop-start: old monitor does not clobber new run panel state")
	fun startStopStartStalemonitorSafe() {
		// First run: blocks until explicitly released
		val run1Started = CountDownLatch(1)
		val run1Block = CountDownLatch(1)
		every { context.run() } answers {
			run1Started.countDown()
			run1Block.await(10, TimeUnit.SECONDS)
		}

		// Second run: also blocks so we can assert on panel state while it is still running
		val run2Started = CountDownLatch(1)
		val run2Block = CountDownLatch(1)
		every { context2.run() } answers {
			run2Started.countDown()
			run2Block.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)

		// Phase 1: start first simulation
		controller.start(context)
		assertThat(run1Started.await(5, TimeUnit.SECONDS)).isTrue()
		SwingUtilities.invokeAndWait {
			assertThat(findStatusLabel()!!.text).isEqualTo("Status: Running")
		}

		// Phase 2: stop first simulation, then immediately start second simulation.
		// The old monitor is still alive (run1Block not yet released).
		controller.stop()
		controller.start(context2)
		assertThat(run2Started.await(5, TimeUnit.SECONDS)).isTrue()

		// Phase 3: release first run's block — old monitor wakes up and fires invokeLater
		run1Block.countDown()

		// Give old monitor's invokeLater time to land on EDT (if it fires at all)
		SwingUtilities.invokeAndWait { /* flush EDT */ }
		Thread.sleep(100) // extra buffer for late-arriving invokeLater
		SwingUtilities.invokeAndWait { /* flush EDT again */ }

		// Panel must still show Running for the new run — old monitor must not clobber it
		SwingUtilities.invokeAndWait {
			assertThat(findStatusLabel()!!.text).isEqualTo("Status: Running")
			assertThat(findStopButton()!!.isEnabled).isTrue()
		}

		// Cleanup
		run2Block.countDown()
		controller.stop()
	}

	// ── setSpeed ──────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed rejects value below MIN_SPEED")
	fun setSpeedBelowMinThrows() {
		val controller = SimulationController(controlPanel)
		org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
			controller.setSpeed(SimulationRunner.MIN_SPEED - 0.001)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed rejects value above MAX_SPEED")
	fun setSpeedAboveMaxThrows() {
		val controller = SimulationController(controlPanel)
		org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
			controller.setSpeed(SimulationRunner.MAX_SPEED + 0.001)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed accepts boundary values MIN_SPEED and MAX_SPEED")
	fun setSpeedAcceptsBoundaryValues() {
		val controller = SimulationController(controlPanel)
		controller.setSpeed(SimulationRunner.MIN_SPEED)
		controller.setSpeed(SimulationRunner.MAX_SPEED)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed applies immediately to an active runner")
	fun setSpeedAppliedImmediatelyToActiveRunner() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		controller.setSpeed(2.0)
		assertThat(controller.runner!!.speedMultiplier).isEqualTo(2.0)

		blockSim.countDown()
		controller.stop()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed before start is carried into the next start()")
	fun setSpeedCarriedIntoNextStart() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val controller = SimulationController(controlPanel)
		controller.setSpeed(0.5) // pre-select before start
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		assertThat(controller.runner!!.speedMultiplier).isEqualTo(0.5)

		blockSim.countDown()
		controller.stop()
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	// ── toolBar / statusBar wiring ────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("speed change on runner propagates to StatusBar indicator")
	fun speedChangePropagatestoStatusBar() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val statusBar = StatusBar()

		val controller = SimulationController(controlPanel, statusBar = statusBar)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		// Change speed via runner — this fires PROP_SPEED_MULTIPLIER
		controller.runner!!.speedMultiplier = 2.0

		// Flush EDT twice (listener calls invokeLater, so two flushes are needed)
		SwingUtilities.invokeAndWait { /* flush 1 */ }
		SwingUtilities.invokeAndWait { /* flush 2 */ }

		SwingUtilities.invokeAndWait {
			assertThat(findSpeedText(statusBar)).isEqualTo("Speed: 2.0x")
			assertThat(statusBar.isVisible).isTrue()
		}

		blockSim.countDown()
		controller.stop()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("stop resets StatusBar speed indicator to hidden")
	fun stopResetsStatusBarSpeedIndicator() {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			blockSim.await(10, TimeUnit.SECONDS)
		}

		val statusBar = StatusBar()

		val controller = SimulationController(controlPanel, statusBar = statusBar)
		controller.start(context)
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()

		// Set non-default speed, then stop
		controller.runner!!.speedMultiplier = 3.0
		SwingUtilities.invokeAndWait { /* flush */ }
		SwingUtilities.invokeAndWait { /* flush invokeLater */ }

		controller.stop()
		blockSim.countDown()

		// Stop calls updateSpeedIndicator(DEFAULT_SPEED) via invokeLater
		SwingUtilities.invokeAndWait { /* flush stop's invokeLater */ }
		SwingUtilities.invokeAndWait { /* flush nested */ }

		SwingUtilities.invokeAndWait {
			assertThat(statusBar.isVisible).isFalse()
		}
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private fun findSpeedText(statusBar: StatusBar): String? =
		statusBar.text.takeIf { it.startsWith("Speed:") }

	private fun findStopButton(): JButton? =
		(0 until controlPanel.componentCount)
			.mapNotNull { controlPanel.getComponent(it) as? JButton }
			.firstOrNull { it.text == "Stop" }

	private fun findStatusLabel(): JLabel? =
		(0 until controlPanel.componentCount)
			.mapNotNull { controlPanel.getComponent(it) as? JLabel }
			.firstOrNull { it.text.startsWith("Status:") }
}
