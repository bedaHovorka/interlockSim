package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.cellsOfType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests the SP1.7 tool-driven [DispatchDecision] branches ([DispatchDecision.SetSignalAspect],
 * [DispatchDecision.SetSwitchPosition], [DispatchDecision.ReleaseRoute],
 * [DispatchDecision.RequestRoute]) added to [applyDecision] to fix the exhaustiveness gap that
 * broke the `:core` build (Issue #774 follow-up).
 *
 * [RuleBasedDispatcher.decide] never emits these four subtypes today, so a full
 * [wireSynchronousDispatcher] run cannot reach them — each test instead drives a real
 * [ShuntingLoop.controlStepListener] that calls [applyDecision] directly with a fabricated
 * decision, keeping every actuator-port call on the kDisco simulation thread as required by
 * [DefaultNetworkActuatorPort]'s thread-safety contract.
 *
 * @since Issue #774 (SP1.7 — Goal 10 threading contract), build-fix follow-up
 */
@DisplayName("SynchronousDispatcherWiring SP1.7 tool-driven decisions (#774)")
@Tag("integration-test")
class SynchronousDispatcherWiringTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		}

	private fun switchConf(
		context: DefaultSimulationContext,
		switchName: String
	): RailSwitch.Conf =
		context
			.getRailWayNetGrid()
			.cellsOfType<DynamicRailSwitch>()
			.first { it.name == switchName }
			.conf

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `SetSignalAspect applies the requested aspect to a real semaphore`() {
		val context = loadVyhybnaContext()
		context.getInOuts()
		val loop = ShuntingLoop(context, endTime = 0L)
		context.setMainProcess(loop)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)
		val perceptionPort = DefaultNetworkPerceptionPort(env = context, activeTrains = { emptyList() })

		loop.controlStepListener =
			ControlStepListener {
				applyDecision(DispatchDecision.SetSignalAspect("zA", Signal.S40), loop, actuatorPort)
			}
		context.run()

		assertThat(perceptionPort.signalAspect("zA")?.signal).isEqualTo(Signal.S40)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `SetSignalAspect on an unknown semaphore does not throw`() {
		val context = loadVyhybnaContext()
		context.getInOuts()
		val loop = ShuntingLoop(context, endTime = 0L)
		context.setMainProcess(loop)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)

		loop.controlStepListener =
			ControlStepListener {
				applyDecision(DispatchDecision.SetSignalAspect("doesNotExist", Signal.FREE), loop, actuatorPort)
			}
		context.run()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `SetSwitchPosition applies the requested position to a real switch`() {
		val context = loadVyhybnaContext()
		context.getInOuts()
		val loop = ShuntingLoop(context, endTime = 0L)
		context.setMainProcess(loop)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)

		val target =
			if (switchConf(context, "vA") == RailSwitch.Conf.MAIN) RailSwitch.Conf.BRANCH else RailSwitch.Conf.MAIN
		loop.controlStepListener =
			ControlStepListener {
				applyDecision(DispatchDecision.SetSwitchPosition("vA", target), loop, actuatorPort)
			}
		context.run()

		assertThat(switchConf(context, "vA")).isEqualTo(target)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `SetSwitchPosition on an unknown switch does not throw`() {
		val context = loadVyhybnaContext()
		context.getInOuts()
		val loop = ShuntingLoop(context, endTime = 0L)
		context.setMainProcess(loop)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)

		loop.controlStepListener =
			ControlStepListener {
				applyDecision(
					DispatchDecision.SetSwitchPosition("doesNotExist", RailSwitch.Conf.BRANCH),
					loop,
					actuatorPort
				)
			}
		context.run()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `ReleaseRoute for a train holding no reservation is a no-op`() {
		val context = loadVyhybnaContext()
		context.getInOuts()
		val loop = ShuntingLoop(context, endTime = 0L)
		context.setMainProcess(loop)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)

		loop.controlStepListener =
			ControlStepListener {
				applyDecision(DispatchDecision.ReleaseRoute("ghostTrain"), loop, actuatorPort)
			}
		context.run()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `RequestRoute reserves a path, and a competing request logs a conflict without throwing`() {
		val context = loadVyhybnaContext()
		context.getInOuts()
		val loop = ShuntingLoop(context, endTime = 0L)
		context.setMainProcess(loop)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)

		val released = AtomicBoolean(false)
		loop.controlStepListener =
			ControlStepListener {
				applyDecision(DispatchDecision.RequestRoute("trainA", "A", "zA"), loop, actuatorPort)
				// trainB competes for the same route while trainA still holds it -> Conflict
				// branch must be logged, not thrown.
				applyDecision(DispatchDecision.RequestRoute("trainB", "A", "zA"), loop, actuatorPort)
				released.set(actuatorPort.releaseRoute("trainA"))
			}
		context.run()

		// releaseRoute only returns true if trainA's earlier RequestRoute actually reserved
		// something — this is the observable proof that the Reserved branch was applied.
		assertThat(released.get()).isTrue()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `HoldTrain is a no-op in the synchronous path and does not throw`() {
		// HoldTrain is not supported in the synchronous wiring (TrainLifecyclePort is not
		// available here); it should log a warning and drop the decision without throwing.
		val context = loadVyhybnaContext()
		context.getInOuts()
		val loop = ShuntingLoop(context, endTime = 0L)
		context.setMainProcess(loop)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)

		loop.controlStepListener =
			ControlStepListener {
				applyDecision(DispatchDecision.HoldTrain("ghostTrain", 30.0), loop, actuatorPort)
			}
		context.run() // must complete without throwing
	}
}
