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
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared fixture for the lifted dispatcher-agent stack + lock-step handshake.
 *
 * Both [cz.vutbr.fit.interlockSim.dispatcher.ShuntingLoopLiftedDriverIntegrationTest] (the
 * Goal 9 single-run correctness gate) and
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatcherCollisionValidationTest] (the Goal 3
 * safety-net gate) drive `vyhybna.xml` with the identical lifted stack —
 * [AgentLoopDriver] + [DispatchDecisionApplier] + [RuleBasedDispatcher] — under the same
 * lock-step pacing.  Centralising the wiring here means a change to the stack or the
 * handshake has to be made in exactly one place, so the two gates cannot silently drift
 * apart in how they drive the dispatcher.
 *
 * Each test is still responsible for its own pre-`run()` subscriptions (which differ —
 * Goal 3 subscribes to `CollisionWarning`s, Goal 9 only to `ConflictDetectedEvent`s) and
 * its own post-`run()` assertions.  This fixture owns only the parts that must match:
 * context loading, stack construction, and the lock-step handshake.
 *
 * ## Lock-step rationale
 *
 * Without the lock-step handshake, the driver thread and the kDisco sim thread race
 * under OS scheduling: the driver can read a stale observation in which a hop was not yet
 * reserved and issue a duplicate `ReservePath`, producing spurious conflicts.  The
 * handshake pins exactly one driver cycle per simulation tick — the sim signals
 * `driverTurn` after capturing the snapshot, waits on `simTurn` for the driver to finish,
 * then applies decisions before the next tick advances state.  See
 * [cz.vutbr.fit.interlockSim.dispatcher.ShuntingLoopLiftedDriverIntegrationTest] for the
 * full race analysis.
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
		newShuntingLoopContext(xmlContextFactory, processFactory)

	/**
	 * Wires the full lifted dispatcher-agent stack onto [loop], runs [context] to
	 * completion under the lock-step handshake, and returns a [RunResult] exposing the
	 * post-run observables each test logs or asserts on.
	 *
	 * Pre-`run()` subscriptions (collision warnings, conflict events, the headless
	 * `autoPauseOnCritical` flag) must be registered on [context] before calling this —
	 * they are buffered and flushed onto the live services at [DefaultSimulationContext.run]
	 * time, so registering them earlier in the test is the correct and only ordering that
	 * makes them live for the whole run.
	 *
	 * @param plannerFactory Builds the [DispatcherPlanner] this run drives, given the shared
	 *   [ActuatorCommandQueue]. Defaults to the [RuleBasedPlanAdapter] both pre-existing gates
	 *   use, so their behaviour is unchanged. Issue #814's regression tests substitute scripted
	 *   planners here to reproduce specific LLM misbehaviours against a real simulation.
	 */
	fun run(
		loop: ShuntingLoop,
		context: DefaultSimulationContext,
		plannerFactory: (ActuatorCommandQueue) -> DispatcherPlanner = { RuleBasedPlanAdapter(RuleBasedDispatcher()) }
	): RunResult {
		// Wire the full lifted stack — same components as production.
		val perceptionPort =
			DefaultNetworkPerceptionPort(
				env = context,
				activeTrains = loop::getApprovedTrains
			)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)
		val queue = ActuatorCommandQueue()
		// Default: the rule-based planner both existing gates use. Issue #814's regression tests
		// pass a scripted planner instead so they can reproduce specific LLM misbehaviours
		// deterministically; the queue is handed in because a planner that models the SP2b.9
		// admission safety net posts to it directly, exactly as KoogAgentPlanAdapter does.
		val planner = plannerFactory(queue)
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
				dispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation)
			)

		// Lock-step handshake: the sim thread signals driverTurn after capturing the
		// snapshot, waits on simTurn for the driver to complete its cycle, then applies
		// decisions. This keeps the observation fresh each cycle and applies every decision
		// before the next tick advances state.
		val driverCycleCount = AtomicInteger(0)
		val driverTurn = Semaphore(0)
		val simTurn = Semaphore(0)

		loop.snapshotCaptureHook = perceptionPort::captureSnapshot
		loop.controlStepListener =
			ControlStepListener {
				driverTurn.release()
				simTurn.acquireUninterruptibly()
				applier.onControlStep()
			}
		loop.agentDriverAction = {
			while (loop.isSimActive()) {
				if (driverTurn.tryAcquire(100, TimeUnit.MILLISECONDS)) {
					try {
						driver.runCycle()
						driverCycleCount.incrementAndGet()
					} finally {
						simTurn.release()
					}
				}
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
