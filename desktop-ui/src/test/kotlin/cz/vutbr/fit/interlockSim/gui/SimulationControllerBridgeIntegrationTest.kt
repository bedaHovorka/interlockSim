/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Bridge integration test (Goal 7 / Issue #187): with real DefaultSimulationContext
	and a real ShuntingLoop, SimulationController.setSpeed must reach the live
	ShuntingLoop instance through DefaultSimulationContext.mainProcess and the
	SpeedControllable cast — closing the loop the user observed broken in
	`exampleGui shuntingLoop`.
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.SpeedControllable
import cz.vutbr.fit.interlockSim.testutil.IntegrationKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.get
import java.util.concurrent.TimeUnit

@Tag("integration-test")
@DisplayName("SimulationController -> ShuntingLoop bridge (real types)")
class SimulationControllerBridgeIntegrationTest : IntegrationKoinTestBase() {
	@Test
	@DisplayName("Real ShuntingLoop is recognised as SpeedControllable via mainProcess")
	fun realShuntingLoopIsRecognizedAsSpeedControllable() {
		val factory = get<SimulationContextFactory>()
		val ctx = TestFixtures.loadShuntingXml().use { factory.createContext(it) as DefaultSimulationContext }

		ctx.use { context ->
			context.getInOuts()
			val loop = ShuntingLoop(context, 60L, enableRealTimeSync = true)
			context.setMainProcess(loop)

			val mainProcess = context.mainProcess
			assertThat(mainProcess).isSameInstanceAs(loop)
			assertThat(mainProcess is SpeedControllable).isTrue()

			val controllable = mainProcess as SpeedControllable
			controllable.speedMultiplier = 2.5
			// Same instance — RealTimeSynch on the simulation thread reads through
			// the same @Volatile-backed field on its next iteration.
			assertThat(loop.speedMultiplier).isEqualTo(2.5)
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("controller.setSpeed propagates to live ShuntingLoop while simulation runs")
	fun controllerSetSpeedPropagatesToLiveShuntingLoop() {
		val factory = get<SimulationContextFactory>()
		val ctx = TestFixtures.loadShuntingXml().use { factory.createContext(it) as DefaultSimulationContext }

		ctx.use { context ->
			context.getInOuts()
			// Long simulated end time + real-time sync so the simulation thread is
			// active when we change speed. We stop() it explicitly in finally.
			val loop =
				ShuntingLoop(
					context,
					endTime = 600L,
					enableRealTimeSync = true,
					initialSpeedMultiplier = 1.0
				)
			context.setMainProcess(loop)

			val controller = SimulationController()
			try {
				controller.start(context)

				// SimulationController wires its `speedControllable` reference
				// synchronously inside start(), before launching the simulation thread,
				// so the bridge is live by the time we get here.
				controller.setSpeed(2.0)
				assertThat(loop.speedMultiplier).isEqualTo(2.0)

				controller.setSpeed(0.5)
				assertThat(loop.speedMultiplier).isEqualTo(0.5)

				controller.setSpeed(10.0)
				assertThat(loop.speedMultiplier).isEqualTo(10.0)
			} finally {
				controller.stop()
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("setSpeed before start applies on start() to the real ShuntingLoop")
	fun preStartSpeedAppliedToRealShuntingLoopOnStart() {
		val factory = get<SimulationContextFactory>()
		val ctx = TestFixtures.loadShuntingXml().use { factory.createContext(it) as DefaultSimulationContext }

		ctx.use { context ->
			context.getInOuts()
			val loop =
				ShuntingLoop(
					context,
					endTime = 600L,
					enableRealTimeSync = true,
					initialSpeedMultiplier = 1.0
				)
			context.setMainProcess(loop)

			val controller = SimulationController()
			try {
				controller.setSpeed(3.0)
				controller.start(context)

				assertThat(loop.speedMultiplier).isEqualTo(3.0)
			} finally {
				controller.stop()
			}
		}
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName(
		"a throttled run publishes paced gaps from the simulation thread and the clock ends at the end time"
	)
	fun pacedSimulationTimeFollowsRealThrottledRun() {
		val factory = get<SimulationContextFactory>()
		val ctx = TestFixtures.loadShuntingXml().use { factory.createContext(it) as DefaultSimulationContext }

		ctx.use { context ->
			context.getInOuts()
			val loop = ShuntingLoop(context, endTime = 60L, enableRealTimeSync = false)
			context.setMainProcess(loop)

			// 60 simulated seconds at 10x pace ~6 wall seconds. `setSpeed` before `start`
			// applies the multiplier on the runner (see the pre-start test above).
			val controller = SimulationController()
			controller.setSpeed(10.0)
			controller.start(context)
			val runner = requireNotNull(controller.runner) { "start() must keep the runner" }
			try {
				// Mid-run the simulation thread publishes its own authoritative gaps, so two
				// reads a wall-second apart must advance (PR #1430 review: the unit tests only
				// drove the throttle off the run's thread).
				val midRunFirst = runner.pacedSimulationTime()
				assertMidRunAdvances(runner)

				// Natural completion: the last gap the simulation thread published ends at the
				// run's end time, and the runner object keeps it after the controller has
				// dropped its own reference.
				val deadline = System.nanoTime() + TimeUnit.NANOSECONDS.convert(15, TimeUnit.SECONDS)
				while (controller.isRunning() && System.nanoTime() < deadline) {
					Thread.sleep(100)
				}
				assertThat(controller.isRunning()).isFalse()
				assertThat(runner.pacedSimulationTime()).isCloseTo(60.0, 1.0)
			} finally {
				controller.stop()
			}
		}
	}

	/** Advances the injected runner's paced time by polling until it moved, bounded to 2 s. */
	private fun assertMidRunAdvances(runner: SimulationRunner) {
		val first = runner.pacedSimulationTime()
		val deadline = System.nanoTime() + TimeUnit.NANOSECONDS.convert(2, TimeUnit.SECONDS)
		var next = first
		while (next <= first && System.nanoTime() < deadline) {
			Thread.sleep(100)
			next = runner.pacedSimulationTime()
		}
		assertThat(next).isCloseTo(first, 1.0)
		assertThat(next > first).isTrue()
	}
}
