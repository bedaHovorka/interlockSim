/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.TimetableReading
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.collision.CollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.InstanceCreationException
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SP1.4 (#549) — verifies the per-[DefaultSimulationContext] Koin scoped bindings added to the
 * production [dispatcherAgentModule] actually resolve from a real context scope.
 *
 * `DispatcherAgentModuleTest` only exercises the singleton bindings (it starts Koin with
 * `dispatcherAgentModule` alone and never creates a [DefaultSimulationContext]), so the
 * `scope<DefaultSimulationContext> { scoped<NetworkPerceptionPort> / scoped<NetworkActuatorPort>
 * / scoped<KoogAgentFactory> }` bindings — including the `getSource<DefaultSimulationContext>()
 * ?: throw IllegalStateException(...)` path — went unverified. This class closes that gap by
 * building a real simulation context from `vyhybna.xml` and resolving each scoped binding from
 * the context's own Koin scope.
 *
 * Loads [dispatcherAgentModule] (the production SP1.4 bindings) together with
 * [commonCoreTestModule] (which supplies [cz.vutbr.fit.interlockSim.context.SimulationProcessFactory]
 * for context construction but, unlike [cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule],
 * does not re-declare the port bindings, so there is no duplicate-definition conflict).
 *
 * @since Issue #549 (SP1.4 — Goal 10)
 */
@DisplayName("SP1.4 port bindings resolve from a DefaultSimulationContext Koin scope (#549)")
class DispatcherAgentPortBindingTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	/**
	 * Supplies only [CollisionDetectionService], which [dispatcherAgentModule] does not bind
	 * (that binding lives in [cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule])
	 * but [DefaultSimulationContext.run] requires. Kept separate from
	 * [cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule] itself because that
	 * module re-declares [NetworkPerceptionPort]/[NetworkActuatorPort], which would conflict
	 * with [dispatcherAgentModule] — the module actually under test here.
	 */
	private val collisionDetectionOnlyTestModule: Module =
		module {
			scope<DefaultSimulationContext> {
				scoped<CollisionDetectionService> {
					val context =
						getSource<DefaultSimulationContext>()
							?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
					DefaultCollisionDetectionService(context, context)
				}
			}
		}

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentModule, commonCoreTestModule, collisionDetectionOnlyTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	@Test
	@DisplayName("scope resolves DefaultNetworkPerceptionPort, DefaultNetworkActuatorPort and KoogAgentFactory")
	fun scopeResolvesSp14PortBindings() {
		loadShuntingLoopContext().use { simCtx ->
			val perceptionPort = simCtx.scope.get<NetworkPerceptionPort>()
			val actuatorPort = simCtx.scope.get<NetworkActuatorPort>()
			val agentFactory = simCtx.scope.get<KoogAgentFactory>()

			assertThat(perceptionPort).isInstanceOf<DefaultNetworkPerceptionPort>()
			assertThat(actuatorPort).isInstanceOf<DefaultNetworkActuatorPort>()
			assertThat(agentFactory).isNotNull()
		}
	}

	@Test
	@DisplayName("scoped port binding throws when no DefaultSimulationContext source is present in the scope")
	fun scopedPortBindingThrowsWithoutContextSource() {
		// A scope created without a source mirrors the failure path the scoped bindings guard
		// against: getSource<DefaultSimulationContext>() returns null → IllegalStateException.
		// Koin wraps the factory's IllegalStateException in an InstanceCreationException, so the
		// assertion walks the cause chain to verify the documented guard fired.
		val scope =
			KoinPlatformTools
				.defaultContext()
				.get()
				.createScope(scopeId = "no-source-test", qualifier = named<DefaultSimulationContext>())
		try {
			val ex = assertThrows<InstanceCreationException> { scope.get<NetworkPerceptionPort>() }
			val cause = assertInstanceOf(IllegalStateException::class.java, ex.cause)
			assertEquals("DefaultSimulationContext source not found in scope", cause.message)
		} finally {
			scope.close()
		}
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName(
		"scoped NetworkPerceptionPort resolved via Koin reports approved trains during a real " +
			"ShuntingLoop run (#769 review finding 1)"
	)
	fun scopedPerceptionPortReportsApprovedTrainsDuringRealRun() {
		loadShuntingLoopContext().use { context ->
			// Required before ShuntingLoop construction (mirrors RuleBasedDispatcherDeterminismTest).
			context.getInOuts()

			val loop = ShuntingLoop(context, endTime = 300L)

			// Resolve the port under test via the production Koin binding — NOT constructed
			// directly — this is the gap the #769 review flagged: no test previously exercised
			// the Koin-resolved port's activeTrains supplier against a real main process.
			val perceptionPort = context.scope.get<NetworkPerceptionPort>()
			val actuatorPort = context.scope.get<NetworkActuatorPort>()

			val queue = ActuatorCommandQueue()
			val dispatcher = RuleBasedDispatcher()
			val planner = RuleBasedPlanAdapter(dispatcher)
			val applier =
				DispatchDecisionApplier(
					queue = queue,
					networkActuator = actuatorPort,
					onApproveTrain = loop::approveQueuedTrain,
					onBlockTransition = loop::incrementBlockTransition,
					onFailedReservation = loop::incrementFailedReservation
				)
			val driver =
				AgentLoopDriver(
					perceptionPort = perceptionPort,
					planner = planner,
					commandQueue = queue,
					controller = NoOpSimulationController,
					observationProvider = loop::getLatestObservation
				)

			// Lock-step handshake (same pattern as RuleBasedDispatcherDeterminismTest) so the
			// driver has definitely run at least one cycle — and therefore an ApproveTrain
			// decision has definitely been applied — before we sample the port each tick.
			val driverTurn = Semaphore(0)
			val simTurn = Semaphore(0)

			val capturedPositions = AtomicReference<List<TrainPositionReading>>()
			val capturedTimetables = AtomicReference<List<TimetableReading>>()

			loop.snapshotCaptureHook = perceptionPort::captureSnapshot
			loop.controlStepListener =
				ControlStepListener {
					driverTurn.release()
					simTurn.acquireUninterruptibly()
					applier.onControlStep()
					// Sample the Koin-resolved port on the sim thread, right after decisions
					// (including approvals) have been applied for this tick.
					if (capturedPositions.get() == null) {
						val positions = perceptionPort.allTrainPositions()
						if (positions.isNotEmpty()) {
							capturedPositions.set(positions)
							capturedTimetables.set(perceptionPort.allTrainTimetables())
						}
					}
				}
			loop.agentDriverAction = {
				while (loop.isSimActive()) {
					if (driverTurn.tryAcquire(100, TimeUnit.MILLISECONDS)) {
						try {
							driver.runCycle()
						} finally {
							simTurn.release()
						}
					}
				}
			}

			context.setMainProcess(loop)
			context.run()

			assertThat(capturedPositions.get()).isNotNull()
			assertThat(requireNotNull(capturedPositions.get())).isNotEmpty()
			assertThat(capturedTimetables.get()).isNotNull()
			assertThat(requireNotNull(capturedTimetables.get())).isNotEmpty()
		}
	}

	@Test
	@DisplayName(
		"scoped NetworkPerceptionPort returns empty train lists when no main process is set " +
			"(#769 review finding 1)"
	)
	fun scopedPerceptionPortReturnsEmptyTrainsWhenNoMainProcessSet() {
		loadShuntingLoopContext().use { context ->
			val perceptionPort = context.scope.get<NetworkPerceptionPort>()

			// context.getMainProcess() is null until setMainProcess() is called — never
			// called in this test. No sim thread is running, so calling the query methods
			// synchronously from the test thread is safe here (unlike during a live run).
			assertThat(perceptionPort.allTrainPositions()).isEmpty()
			assertThat(perceptionPort.allTrainTimetables()).isEmpty()
		}
	}

	/**
	 * Minimal [LoopProcess] that intentionally does NOT implement
	 * [cz.vutbr.fit.interlockSim.sim.ApprovesTrains] — used only to exercise the
	 * "main process present but not an approver" branch of `mainProcessActiveTrains`.
	 * Never activated/run; it only needs to exist as an instance assignable to
	 * [DefaultSimulationContext.setMainProcess].
	 */
	private class NonApprovingLoopProcess : LoopProcess() {
		override suspend fun iteration() {
			// Never invoked — this process is never activated in the test.
		}
	}

	@Test
	@DisplayName(
		"scoped NetworkPerceptionPort returns empty train lists when the main process does " +
			"not implement ApprovesTrains (#769 review finding 1)"
	)
	fun scopedPerceptionPortReturnsEmptyTrainsWhenMainProcessDoesNotApprove() {
		loadShuntingLoopContext().use { context ->
			context.setMainProcess(NonApprovingLoopProcess())
			val perceptionPort = context.scope.get<NetworkPerceptionPort>()

			assertThat(perceptionPort.allTrainPositions()).isEmpty()
			assertThat(perceptionPort.allTrainTimetables()).isEmpty()
		}
	}
}
