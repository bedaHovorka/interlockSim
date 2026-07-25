/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared fixture for the lifted dispatcher-agent stack + signal-based pacing.
 *
 * Both [cz.vutbr.fit.interlockSim.dispatcher.ShuntingLoopLiftedDriverIntegrationTest] (the
 * Goal 9 single-run correctness gate) and
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatcherCollisionValidationTest] (the Goal 3
 * safety-net gate) drive `vyhybna.xml` with the identical lifted stack —
 * [AgentLoopDriver] + [DispatchDecisionApplier] + [RuleBasedDispatcher] — under the same
 * pacing.  Centralising the wiring here means a change to the stack or the pacing has to
 * be made in exactly one place, so the two gates cannot silently drift apart in how they
 * drive the dispatcher.
 *
 * Each test is still responsible for its own pre-`run()` subscriptions (which differ —
 * Goal 3 subscribes to `CollisionWarning`s, Goal 9 only to `ConflictDetectedEvent`s) and
 * its own post-`run()` assertions.  This fixture owns only the parts that must match:
 * context loading, stack construction, and the pacing signal.
 *
 * ## Signal-based pacing rationale (Issue #746, SP0.11c)
 *
 * Without a pacing handshake, the driver thread and the kDisco sim thread race under OS
 * scheduling: the driver can read a stale observation in which a hop was not yet reserved
 * and issue a duplicate `ReservePath`, producing spurious conflicts. [DefaultSnapshotSignal]
 * pins exactly one driver cycle per simulation tick — the sim thread calls
 * [DefaultSnapshotSignal.signal] immediately after capturing the snapshot; the driver blocks
 * on [DefaultSnapshotSignal.await] at the top of each cycle and is woken exactly once per
 * tick, so it always reads a near-fresh snapshot (at most 1-tick lag, never a backlog of
 * stale cycles). See [cz.vutbr.fit.interlockSim.dispatcher.ShuntingLoopLiftedDriverIntegrationTest]
 * for the full race analysis.
 */
class LiftedStackFixture(
	private val xmlContextFactory: XMLContextFactory = XMLContextFactory(),
	private val processFactory: DefaultSimulationProcessFactory = DefaultSimulationProcessFactory()
) {
	/**
	 * Loads `vyhybna.xml` into a fresh [DefaultSimulationContext].
	 *
	 * Call [DefaultSimulationContext.getInOuts] on the result before constructing a
	 * [ShuntingLoop] — the dynamic wrapper map must be initialised first.
	 */
	fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	/**
	 * Wires the full lifted dispatcher-agent stack onto [loop], runs [context] to
	 * completion under signal-based pacing (Issue #746, SP0.11c), and returns a
	 * [RunResult] exposing the post-run observables each test logs or asserts on.
	 *
	 * Pre-`run()` subscriptions (collision warnings, conflict events, the headless
	 * `autoPauseOnCritical` flag) must be registered on [context] before calling this —
	 * they are buffered and flushed onto the live services at [DefaultSimulationContext.run]
	 * time, so registering them earlier in the test is the correct and only ordering that
	 * makes them live for the whole run.
	 */
	fun run(
		loop: ShuntingLoop,
		context: DefaultSimulationContext
	): RunResult {
		// Wire the full lifted stack — same components as production.
		val perceptionPort =
			DefaultNetworkPerceptionPort(
				env = context,
				activeTrains = loop::getApprovedTrains
			)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)
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
		// Signal-based pacing (Issue #746, SP0.11c): the sim thread calls
		// snapshotSignal.signal() after each captureSnapshot; the driver blocks on
		// snapshotSignal.await() and is woken exactly once per tick — at most 1-tick
		// observation lag. This matches the production wiring in
		// ExampleRegistry.wireDispatcherAgent.
		val snapshotSignal = DefaultSnapshotSignal()
		val driverCycleCount = AtomicInteger(0)
		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = planner,
				commandQueue = queue,
				controller = NoOpSimulationController,
				dispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation),
				snapshotSignal = snapshotSignal
			)

		loop.snapshotCaptureHook = {
			perceptionPort.captureSnapshot()
			snapshotSignal.signal()
		}
		loop.controlStepListener = applier
		loop.agentDriverAction = {
			while (loop.isSimActive()) {
				driver.runCycle()
				driverCycleCount.incrementAndGet()
			}
		}

		context.setMainProcess(loop)
		context.run()

		return RunResult(driverCycleCount = driverCycleCount, loop = loop)
	}

	/** Post-run observables shared by both integration gates. */
	class RunResult internal constructor(
		val driverCycleCount: AtomicInteger,
		private val loop: ShuntingLoop
	) {
		/** Number of generated trains that completed their journey. */
		fun trainsExited(): Int = loop.getTrainsExited()

		/** Peak concurrent approved trains during the run. */
		fun maxConcurrentTrains(): Int = loop.getMaxConcurrentTrains()
	}
}
