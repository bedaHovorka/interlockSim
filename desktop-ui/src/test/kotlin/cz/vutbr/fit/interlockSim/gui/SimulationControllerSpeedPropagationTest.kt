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

	private fun newController(): SimulationController = SimulationController()

	private fun mockContext(mainProcess: LoopProcess?): DefaultSimulationContext {
		val started = CountDownLatch(1)
		val blockSim = CountDownLatch(1)
		return mockk<DefaultSimulationContext>(relaxed = true).also { ctx ->
			every { ctx.getMainProcess() } returns mainProcess
			every { ctx.run() } answers {
				started.countDown()
				blockSim.await(10, TimeUnit.SECONDS)
			}
			// Stash the latches on the mock for the test to access.
			ctx.attachLatches(started, blockSim)
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed propagates to a SpeedControllable main process while running")
	fun setSpeedReachesSpeedControllable() {
		val process = FakeSpeedyMainProcess()
		val ctx = mockContext(process)
		val controller = newController()

		controller.start(ctx)
		assertThat(ctx.startedLatch().await(5, TimeUnit.SECONDS)).isTrue()

		controller.setSpeed(2.5)

		assertThat(process.speedMultiplier).isEqualTo(2.5)
		assertThat(controller.runner!!.speedMultiplier).isEqualTo(2.5)

		ctx.releaseSim()
		controller.stop()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed before start applies on next start (desiredSpeed propagation)")
	fun preStartSpeedAppliedOnStart() {
		val process = FakeSpeedyMainProcess()
		val ctx = mockContext(process)
		val controller = newController()

		controller.setSpeed(0.5)
		controller.start(ctx)
		assertThat(ctx.startedLatch().await(5, TimeUnit.SECONDS)).isTrue()

		assertThat(process.speedMultiplier).isEqualTo(0.5)
		assertThat(controller.runner!!.speedMultiplier).isEqualTo(0.5)

		ctx.releaseSim()
		controller.stop()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed is a safe no-op when main process is not SpeedControllable")
	fun nonControllableMainProcessNoOp() {
		val ctx = mockContext(PlainMainProcess())
		val controller = newController()

		controller.start(ctx)
		assertThat(ctx.startedLatch().await(5, TimeUnit.SECONDS)).isTrue()

		controller.setSpeed(3.0) // must not throw, must not crash on the cast
		assertThat(controller.runner!!.speedMultiplier).isEqualTo(3.0)

		ctx.releaseSim()
		controller.stop()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed is a safe no-op when main process is null")
	fun nullMainProcessNoOp() {
		val ctx = mockContext(null)
		val controller = newController()

		controller.start(ctx)
		assertThat(ctx.startedLatch().await(5, TimeUnit.SECONDS)).isTrue()

		controller.setSpeed(1.5)
		assertThat(controller.runner!!.speedMultiplier).isEqualTo(1.5)

		ctx.releaseSim()
		controller.stop()
	}

	// ── latch wiring kept off the production type ────────────────────────────
	// Stores per-test latches via a side-table keyed on the mock instance, so
	// production DefaultSimulationContext stays unchanged.
	companion object {
		private val latches = java.util.IdentityHashMap<DefaultSimulationContext, Pair<CountDownLatch, CountDownLatch>>()
	}

	private fun DefaultSimulationContext.attachLatches(started: CountDownLatch, block: CountDownLatch) {
		latches[this] = started to block
	}

	private fun DefaultSimulationContext.startedLatch(): CountDownLatch = latches.getValue(this).first

	private fun DefaultSimulationContext.releaseSim() {
		latches.getValue(this).second.countDown()
	}
}
