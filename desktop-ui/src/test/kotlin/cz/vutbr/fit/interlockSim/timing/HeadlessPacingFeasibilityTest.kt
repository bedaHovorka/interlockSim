/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.timing

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.ActionValidator
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop
import cz.vutbr.fit.interlockSim.dispatcher.RuleBasedEmissionStrategy
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionCandidateEnumerator
import cz.vutbr.fit.interlockSim.dispatcher.agents.AffordanceAnnotator
import cz.vutbr.fit.interlockSim.dispatcher.agents.NoTimeoutBudget
import cz.vutbr.fit.interlockSim.dispatcher.agents.ObservationRenderer
import cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer
import cz.vutbr.fit.interlockSim.dispatcher.agents.TerminalFallbackGuard
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickRingBuffer
import cz.vutbr.fit.interlockSim.dispatcher.agents.WorkingMemory
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerCapabilities
import cz.vutbr.fit.interlockSim.dispatcher.planner.assertPlannerPacingCompatible
import cz.vutbr.fit.interlockSim.gui.SimulationRunner
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.IntegrationKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * SP2c.26 (Issue #849) evidence — **AC5 / risk R8: can a real pacing controller run headlessly?**
 *
 * ## The R8 problem, restated
 *
 * #822 R8 records the headless sweep as blocked: [assertPlannerPacingCompatible] rejects an async
 * planner bound to [NoOpSimulationController], and `shuntingLoopAI` is registered as a GUI-only
 * example — so A4's "N >= 10 runs" bar is unreachable. #849 asks whether (a) a real pacing
 * controller can be wired headlessly, or (b) F1 makes tick pacing intrinsic so the guard becomes
 * deliberately relaxable.
 *
 * ## What this test establishes
 *
 * Option (a) is **already achievable with the code that exists today**. [SimulationRunner] is a
 * complete [cz.vutbr.fit.interlockSim.context.SimulationController] with wall-clock throttling and
 * carries no `javax.swing` or `java.awt` dependency whatsoever — it uses only
 * [java.beans.PropertyChangeSupport]. It is GUI-*located*, not GUI-*coupled*. The tests below drive
 * a complete `vyhybna.xml` dispatcher run to completion under [SimulationRunner] pacing with no
 * Swing component, no `Frame`, and no EDT anywhere in the picture, and confirm that the pacing
 * guard accepts an async planner bound to it while still rejecting the no-pacing controller.
 *
 * R8 is therefore a **module-placement and example-registration problem, not a missing capability**
 * — which makes the fix far cheaper than "build a headless runner". See
 * `docs/GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md` for the recorded ruling and the follow-up issue.
 *
 * @since Issue #849 (SP2c.26 — Goal 10 F1 paused-clock spike)
 */
@Tag("integration-test")
@DisplayName("F1 paused-clock spike — headless pacing feasibility, risk R8 (#849)")
@Timeout(180, unit = TimeUnit.SECONDS)
class HeadlessPacingFeasibilityTest : IntegrationKoinTestBase() {
	/** Minimal async planner: only [PlannerCapabilities.isAsynchronous] matters to the guard. */
	private class AsyncProbePlanner : DispatcherPlanner {
		override val capabilities: PlannerCapabilities =
			PlannerCapabilities(
				name = "AsyncProbePlanner",
				isAsynchronous = true,
				maxSpeedMultiplier = PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER
			)

		override suspend fun plan(observation: DispatchObservation) = emptyList<Nothing>()
	}

	@Test
	@DisplayName("AC5: the pacing guard accepts SimulationRunner headlessly but still rejects NoOp")
	fun pacingGuardIsSatisfiableWithoutGui() {
		val context = loadShuntingContext()
		val runner = SimulationRunner(context)
		val planner = AsyncProbePlanner()

		// Satisfiable headlessly: no Frame, no EDT, no Swing — just the runner.
		assertPlannerPacingCompatible(planner, runner)

		// Control: the guard is genuinely still doing its job; it is not vacuously passing.
		val rejection = runCatching { assertPlannerPacingCompatible(planner, NoOpSimulationController) }.exceptionOrNull()
		assertThat(rejection).isNotNull()
		assertThat(rejection?.message ?: "").contains("NoOpSimulationController")
	}

	@Test
	@DisplayName("AC5: a paced headless run produces the same outcome as an unpaced one")
	fun pacedHeadlessRunMatchesUnpacedOutcome() {
		// Baseline: the wiring the P10 determinism gate uses — no pacing at all.
		val unpaced = executeHeadlessRun(pacing = null)
		// Same wiring, but paced by a real SimulationRunner with no GUI attached.
		val paced = executeHeadlessRun(pacing = SPEED_MULTIPLIER)

		logger.info {
			"Headless dispatcher run — unpaced: $unpaced, " +
				"paced by SimulationRunner at ${SPEED_MULTIPLIER}x: $paced"
		}

		// The loop was genuinely live in both runs: trains were admitted, so a null result would
		// not be mistaken for agreement.
		assertThat(unpaced.maxConcurrentTrains).isGreaterThan(0)
		assertThat(paced.maxConcurrentTrains).isGreaterThan(0)

		// Pacing changes wall-clock only, never event semantics — the property SimulationRunner
		// claims and the one R8 needs before a paced controller can carry the headless sweep.
		assertThat(paced).isEqualTo(unpaced)
	}

	/** Outcome of one headless dispatcher run; compared across pacing configurations. */
	private data class RunOutcome(
		val trainsExited: Int,
		val maxConcurrentTrains: Int
	)

	/**
	 * Runs the full `vyhybna.xml` dispatcher stack headlessly to [END_TIME].
	 *
	 * @param pacing speed multiplier for a real [SimulationRunner], or `null` to run unpaced under
	 *   [NoOpSimulationController] exactly as the P10 determinism gate does.
	 */
	private fun executeHeadlessRun(pacing: Double?): RunOutcome {
		val context = loadShuntingContext()
		context.getInOuts()
		val loop = ShuntingLoop(context, END_TIME)
		val controller =
			pacing?.let { SimulationRunner(context).apply { speedMultiplier = it } } ?: NoOpSimulationController

		val perceptionPort = DefaultNetworkPerceptionPort(env = context, activeTrains = loop::getApprovedTrains)
		val queue = ActuatorCommandQueue()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = DefaultNetworkActuatorPort(env = context),
				onApproveTrain = loop::approveQueuedTrain,
				onBlockTransition = loop::incrementBlockTransition,
				onFailedReservation = loop::incrementFailedReservation
			)
		val projector =
			DispatcherObservationProjector(
				perceptionPort = perceptionPort,
				dispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation),
				pathReservationRegistry = context.scope.get<PathReservationRegistry>(),
				environment = context
			)
		val topology = StationTopologySerializer.describe(context)
		val validator =
			ActionValidator(
				validEndpointNames = (topology.inOuts + topology.signals.map { it.name }).toSet(),
				blockIds = topology.blocks.map { it.name }.toSet()
			)
		val signal = DefaultSnapshotSignal()
		val tickLoop =
			DispatchTickLoop(
				observations = projector,
				annotator = AffordanceAnnotator(validator, ActionCandidateEnumerator()),
				renderer = ObservationRenderer { "" },
				emission = RuleBasedEmissionStrategy(RuleBasedDispatcher()),
				validator = validator,
				queue = queue,
				ring = TickRingBuffer(),
				workingMemory = WorkingMemory.EMPTY,
				budget = NoTimeoutBudget,
				fallbackGuard = TerminalFallbackGuard(),
				// The driver is paced through a DelegatingSimulationController, exactly as
				// ExampleRegistry.wireDispatcherAgent does: the driver must never call
				// SimulationRunner.awaitIfPaused directly, because that consumes single-step
				// requests owned by the simulation thread.
				controller = DelegatingSimulationController().apply { delegate = controller },
				snapshotSignal = signal
			)

		// Same hook ordering as production and as the P10 determinism gate: refresh perception and
		// project on the sim thread, but signal only from controlStepListener, after iteration()
		// has published the per-tick observation.
		val decisionsApplied = Semaphore(0)
		loop.snapshotCaptureHook = {
			perceptionPort.captureSnapshot()
			projector.captureOnSimThread()
		}
		loop.controlStepListener =
			ControlStepListener {
				signal.signal()
				decisionsApplied.acquireUninterruptibly()
				applier.onControlStep()
			}
		loop.agentDriverAction = {
			while (loop.isSimActive()) {
				if (tickLoop.runTick() != null) decisionsApplied.release()
			}
		}

		context.setMainProcess(loop)
		context.run(controller)

		return RunOutcome(
			trainsExited = loop.getTrainsExited(),
			maxConcurrentTrains = loop.getMaxConcurrentTrains()
		)
	}

	private fun loadShuntingContext(): DefaultSimulationContext =
		TestFixtures.newShuntingSimulationContext().tracked()

	companion object {
		private val logger = KotlinLogging.logger {}

		/** Same simulated horizon the P10 determinism gate uses, so the comparison is like-for-like. */
		private const val END_TIME: Long = 300L

		/**
		 * Compresses 300 simulated seconds into a few wall-clock seconds. Legitimate here because the
		 * emission strategy is rule-based and synchronous — [PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER]
		 * constrains *async* planners, which is exactly the distinction the guard test above covers.
		 */
		private const val SPEED_MULTIPLIER: Double = 50.0
	}
}
