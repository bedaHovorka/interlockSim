/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Bridge integration test (Goal 7 / Issue #187): with real DefaultSimulationContext
	and a real ShuntingLoop, SimulationController.setSpeed must reach the live
	ShuntingLoop instance through DefaultSimulationContext.getMainProcess() and the
	SpeedControllable cast — closing the loop the user observed broken in
	`exampleGui shuntingLoop`.
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
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
	@DisplayName("Real ShuntingLoop is recognised as SpeedControllable via getMainProcess()")
	fun realShuntingLoopIsRecognizedAsSpeedControllable() {
		val factory = get<SimulationContextFactory>()
		val ctx = TestFixtures.loadShuntingXml().use { factory.createContext(it) as DefaultSimulationContext }

		ctx.use { context ->
			context.getInOuts()
			val loop = ShuntingLoop(context, 60L, enableRealTimeSync = true)
			context.setMainProcess(loop)

			val mainProcess = context.getMainProcess()
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
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
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
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
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
}
