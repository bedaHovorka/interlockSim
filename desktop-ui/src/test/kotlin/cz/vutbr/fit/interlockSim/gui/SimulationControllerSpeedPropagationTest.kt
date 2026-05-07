/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Tests for SimulationController -> SpeedControllable bridge (Goal 7 / Issue #187):
	verifies that speed-control-button clicks (which land in setSpeed) propagate
	into the running simulation's main process, not just SimulationRunner — the
	latter alone has no observable effect because SimulationRunner.throttle() is
	not wired into the simulation loop.
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.SpeedControllable
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies the missing link the rest of Goal 7's plumbing did not establish:
 * `SimulationController.setSpeed` must reach the running main process when it
 * implements [SpeedControllable], not just the bookkeeping field on
 * [SimulationRunner]. Without this, speed buttons are dead in `exampleGui` mode.
 *
 * ## Synchronisation design
 * Each test gets its own [startedLatch] and [blockSim] latches (instance fields,
 * not companion-object shared state). [blockSim] is counted down in [tearDown]
 * even if the test fails early, so the sim thread always unblocks and daemon threads
 * do not linger across tests.
 *
 * The 30-second timeouts accommodate busy CI runners where thread scheduling can be
 * delayed by several seconds (CI uses `maxParallelForks = availableProcessors()`
 * with multiple JVM forks competing for CPU).
 */
@DisplayName("SimulationController -> SpeedControllable propagation")
class SimulationControllerSpeedPropagationTest {
	/** A LoopProcess that exposes a mutable speed multiplier for assertion. */
	private class FakeSpeedyMainProcess : LoopProcess(), SpeedControllable {
		@Volatile
		override var speedMultiplier: Double = 1.0

		override suspend fun iteration() {
			// never actually scheduled in these tests — context.run() is stubbed
		}
	}

	/** A LoopProcess that does NOT implement SpeedControllable, to prove the cast is safe. */
	private class PlainMainProcess : LoopProcess() {
		override suspend fun iteration() = Unit
	}

	// ── Per-test latches (instance fields, NOT companion-object shared state) ───
	// JUnit 5 creates a fresh test instance per method, so these are naturally
	// isolated. tearDown() guarantees blockSim is released even on test failure,
	// preventing lingering sim threads from other tests.
	private lateinit var startedLatch: CountDownLatch
	private lateinit var blockSim: CountDownLatch

	@BeforeEach
	fun setUp() {
		startedLatch = CountDownLatch(1)
		blockSim = CountDownLatch(1)
	}

	@AfterEach
	fun tearDown() {
		// Guarantee the sim thread unblocks even if the test fails before releaseSim().
		blockSim.countDown()
		unmockkAll()
	}

	private fun newController(): SimulationController = SimulationController()

	private fun mockContext(mainProcess: LoopProcess?): DefaultSimulationContext =
		mockk<DefaultSimulationContext>(relaxed = true).also { ctx ->
			every { ctx.getMainProcess() } returns mainProcess
			every { ctx.run() } answers {
				startedLatch.countDown()
				blockSim.await(30, TimeUnit.SECONDS)
			}
		}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed propagates to a SpeedControllable main process while running")
	fun setSpeedReachesSpeedControllable() {
		val process = FakeSpeedyMainProcess()
		val ctx = mockContext(process)
		val controller = newController()

		controller.start(ctx)
		assertThat(startedLatch.await(30, TimeUnit.SECONDS)).isTrue()

		controller.setSpeed(2.5)

		assertThat(process.speedMultiplier).isEqualTo(2.5)
		assertThat(controller.runner!!.speedMultiplier).isEqualTo(2.5)

		blockSim.countDown()
		controller.stop()
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed before start applies on next start (desiredSpeed propagation)")
	fun preStartSpeedAppliedOnStart() {
		val process = FakeSpeedyMainProcess()
		val ctx = mockContext(process)
		val controller = newController()

		controller.setSpeed(0.5)
		controller.start(ctx)
		assertThat(startedLatch.await(30, TimeUnit.SECONDS)).isTrue()

		assertThat(process.speedMultiplier).isEqualTo(0.5)
		assertThat(controller.runner!!.speedMultiplier).isEqualTo(0.5)

		blockSim.countDown()
		controller.stop()
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed is a safe no-op when main process is not SpeedControllable")
	fun nonControllableMainProcessNoOp() {
		val ctx = mockContext(PlainMainProcess())
		val controller = newController()

		controller.start(ctx)
		assertThat(startedLatch.await(30, TimeUnit.SECONDS)).isTrue()

		controller.setSpeed(3.0) // must not throw, must not crash on the cast
		assertThat(controller.runner!!.speedMultiplier).isEqualTo(3.0)

		blockSim.countDown()
		controller.stop()
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed is a safe no-op when main process is null")
	fun nullMainProcessNoOp() {
		val ctx = mockContext(null)
		val controller = newController()

		controller.start(ctx)
		// Wait for the sim thread to actually start and block on blockSim.await()
		// before we call setSpeed() and assert on runner.speedMultiplier.
		// On a busy CI runner, thread scheduling can delay the sim thread by
		// several seconds — without this await the test is non-deterministic.
		assertThat(startedLatch.await(30, TimeUnit.SECONDS)).isTrue()

		controller.setSpeed(1.5)
		assertThat(controller.runner!!.speedMultiplier).isEqualTo(1.5)

		blockSim.countDown()
		controller.stop()
	}
}
