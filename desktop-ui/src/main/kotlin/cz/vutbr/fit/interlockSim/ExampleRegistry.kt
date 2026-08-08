/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.context.ThrottlingSimulationController
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeChannel
import cz.vutbr.fit.interlockSim.dispatcher.CommandCorrelationMap
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.OrphanReservationSweeper
import cz.vutbr.fit.interlockSim.dispatcher.RegistryPartialRouteReleaser
import cz.vutbr.fit.interlockSim.dispatcher.agents.ConflictHintLatch
import cz.vutbr.fit.interlockSim.dispatcher.agents.CycleHistory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcomeAggregator
import cz.vutbr.fit.interlockSim.dispatcher.planner.CompositeActionOutcomeSink
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultDispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerCapabilities
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters
import cz.vutbr.fit.interlockSim.dispatcher.planner.assertPlannerPacingCompatible
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DispatchDecisionListenerHub
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.SemiAutoApprovalGateway
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.ThreeTrainLoop
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.util.Resources
import cz.vutbr.fit.interlockSim.util.Util
import java.time.Duration
import java.util.UUID

/**
 * Registry of available simulation examples.
 *
 * This class manages the registration and creation of built-in simulation examples.
 * It replaces the legacy reflection-based @Example annotation system with a type-safe
 * map registry approach.
 *
 * Example factory functions take a SimulationContextFactory and command line arguments,
 * and return a configured SimulationContext ready to run.
 *
 * **Example Types:**
 * - **console examples**: Run simulation in console mode with text output (no GUI)
 * - **guiExamples**: Run simulation with animated GUI visualization
 *
 * @since January 2026 (refactored from reflection-based system)
 */
class ExampleRegistry {
	/**
	 * Registry of console-based examples. Maps example name to factory function.
	 *
	 * **shuntingLoopAI** is registered here (in addition to [guiExamples]) since
	 * Issue #873 (SP2c.26 follow-up I2, R8 resolution): [createShuntingLoopAIExample]
	 * wires the async LLM planner to a [ThrottlingSimulationController] instead of
	 * [NoOpSimulationController], so [assertPlannerPacingCompatible] no longer blocks
	 * headless/console AI runs. The guard itself is unchanged — see the Issue #873 KDoc
	 * on [ThrottlingSimulationController] for the full rationale.
	 */
	val examples: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext> =
		mapOf(
			"shuntingLoop" to ::createShuntingLoopExample,
			"shuntingLoopAI" to ::createShuntingLoopAIExample,
			"shuntingLoopSync" to ::createShuntingLoopSyncExample,
			"multiTrainLoop" to ::createMultiTrainLoopExample,
			"threeTrainLoop" to ::createThreeTrainLoopExample
		)

	/**
	 * Registry of GUI-based examples. Maps example name to factory function.
	 *
	 * GUI examples create SimulationContext instances that are designed to be displayed
	 * in the animated Frame with AnimationController, EventTimelinePanel, and ControlPanel.
	 */
	val guiExamples: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext> =
		mapOf(
			"shuntingLoop" to ::createShuntingLoopGuiExample,
			"shuntingLoopAI" to ::createShuntingLoopAIGuiExample,
			"multiTrainLoop" to ::createMultiTrainLoopGuiExample,
			"threeTrainLoop" to ::createThreeTrainLoopGuiExample
		)

	/**
	 * Returns a sorted list of available console example names.
	 */
	fun getAvailableExamples(): List<String> = examples.keys.sorted()

	/**
	 * Returns a sorted list of available GUI example names.
	 */
	fun getAvailableGuiExamples(): List<String> = guiExamples.keys.sorted()

	/**
	 * Creates a shunting loop simulation example for console mode.
	 *
	 * This example demonstrates a train performing shunting operations on the
	 * shunting loop railway network configuration.
	 *
	 * @param factory The simulation context factory
	 * @param args Command line arguments (expects endTime as args[2])
	 * @return Configured simulation context ready to run
	 * @throws ContextCreationException if configuration fails or endTime is missing
	 */
	private fun createShuntingLoopExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		if (args.size < 3) {
			throw ContextCreationException("End time of simulation not specified")
		}
		val xml =
			try {
				Resources.read("cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			} catch (e: IllegalArgumentException) {
				throw ContextCreationException("Resource file vyhybna.xml not found", e)
			}
		return xml
			.byteInputStream()
			.use { stream ->
				val context = Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
				val time = args[2].toLong()
				// Initialize dynamic wrapper map by calling getInOuts()
				context.getInOuts()
				val loop = ShuntingLoop(context, time)
				wireDispatcherAgent(context, loop, NoOpSimulationController)
				context.setMainProcess(loop)
				context
			}
	}

	/**
	 * Creates a shunting loop example wired with the plain [wireSynchronousDispatcher],
	 * mirroring the native `fast-sim` CLI's `shuntingLoop` construction exactly.
	 *
	 * The default console `shuntingLoop` example runs the Goal 10 dispatcher-agent stack
	 * ([wireDispatcherAgent], Issue #733), which does not exist on the native target —
	 * so its output can never match `fast-sim.kexe` count-for-count. This `sync` variant
	 * exists for cross-platform determinism validation: the CI compare-outputs job and
	 * [cz.vutbr.fit.interlockSim.sim.CrossPlatformParityTest] compare its output against
	 * the native binary's and require **exact** equality (kDisco RNG is bit-identical
	 * across platforms since bedaHovorka/kdisco#69 was fixed).
	 *
	 * @param factory The simulation context factory
	 * @param args Command line arguments (expects endTime as args[2])
	 * @return Configured simulation context ready to run
	 * @throws ContextCreationException if configuration fails or endTime is missing
	 */
	private fun createShuntingLoopSyncExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		if (args.size < 3) {
			throw ContextCreationException("End time of simulation not specified")
		}
		val xml =
			try {
				Resources.read("cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			} catch (e: IllegalArgumentException) {
				throw ContextCreationException("Resource file vyhybna.xml not found", e)
			}
		return xml
			.byteInputStream()
			.use { stream ->
				val context = Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
				val time = args[2].toLong()
				// Initialize dynamic wrapper map by calling getInOuts()
				context.getInOuts()
				val loop = ShuntingLoop(context, time)
				wireSynchronousDispatcher(context, loop)
				context.setMainProcess(loop)
				context
			}
	}

	/**
	 * Builds the [RunParameters] recorded for an LLM-arm run from the settings that run actually
	 * uses (Issue #847 round 4, R4-5; completed in SP2c.24).
	 *
	 * Round 4 recorded `tickPeriodMs`, `historyN` and `maxActionsPerTick` as
	 * [PARAM_NOT_APPLICABLE] because nothing in production honoured them — they were
	 * `DispatchTickLoop` / `ActionValidator` settings and neither of those classes is ever
	 * constructed outside tests. SP2c.24 made all three live on the `AgentLoopDriver` path
	 * (a wall-clock cycle floor, a [cz.vutbr.fit.interlockSim.dispatcher.agents.CycleHistory],
	 * and a [cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder] cap respectively), so they
	 * are now recorded as what the run was given.
	 *
	 * `seed` stays `null`, and that is not an oversight: Koog 1.1.1 has no path from
	 * [OllamaExecutorConfig] to Ollama's `seed` option, so pinning one is impossible here and
	 * recording a value would claim a reproducibility guarantee the run does not have.
	 */
	private fun llmRunParameters(
		config: OllamaExecutorConfig,
		runConfig: DispatcherRunConfig
	): RunParameters =
		RunParameters(
			tickPeriodMs = runConfig.tickPeriodMs,
			historyN = runConfig.historyN,
			temperature = config.temperature.toDouble(),
			maxActionsPerTick = runConfig.maxActionsPerTick,
			model = config.modelName,
			seed = null
		)

	/**
	 * Creates a headless shunting loop AI simulation example for console mode (Issue #873).
	 *
	 * Identical to [createShuntingLoopAIGuiExample] in planner construction but wires the
	 * async [KoogAgentPlanAdapter] to a [ThrottlingSimulationController] capped at
	 * [PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER] instead of the GUI's
	 * [DelegatingSimulationController]. This satisfies [assertPlannerPacingCompatible]
	 * without `:desktop-ui` on the classpath.
	 *
	 * **Resolution of R8 (Issue #822):** the guard previously blocked headless AI runs
	 * because [NoOpSimulationController] has no pacing and was rejected up-front.
	 * [ThrottlingSimulationController] is a real pacing controller (wall-clock
	 * throttle via `platformSleep`) that does not require any GUI class.
	 *
	 * **Ollama prerequisite:** the local Ollama service must be reachable at the
	 * configured endpoint (default `http://localhost:11434`). Without Ollama, every
	 * LLM call times out and the rule-based fallback takes over.
	 *
	 * @param factory The simulation context factory
	 * @param args Command line arguments (expects endTime as args[2])
	 * @return Configured simulation context ready to run in console mode
	 * @throws ContextCreationException if configuration fails or endTime is missing
	 *
	 * @since Issue #873 (SP2c.26 follow-up I2 — headless pacing controller)
	 */

	private fun createShuntingLoopAIExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		if (args.size < 3) {
			throw ContextCreationException("End time of simulation not specified")
		}
		val xml =
			try {
				Resources.read("cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			} catch (e: IllegalArgumentException) {
				throw ContextCreationException("Resource file vyhybna.xml not found", e)
			}
		return xml
			.byteInputStream()
			.use { stream ->
				val context = Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
				val time = args[2].toLong()
				// Initialize dynamic wrapper map by calling getInOuts()
				context.getInOuts()
				// Headless: no real-time sync for the ShuntingLoop itself — the simulation
				// runs at full speed. Pacing only applies to the agent-driver loop below.
				val loop = ShuntingLoop(context, time)
				// Issue #873 (R8 resolution): build the LLM-backed planner with rule-based fallback.
				// KoogAgentPlanAdapter is async (isAsynchronous=true); ThrottlingSimulationController
				// provides the required pacing for assertPlannerPacingCompatible without `:desktop-ui`.
				// Capped at AGENT_MAX_SPEED_MULTIPLIER so the agent driver has sufficient wall-clock
				// time to produce decisions before the simulation advances past the relevant state.
				val koogAdapter =
					KoogAgentPlanAdapter(
						agentFactory = context.scope.get<KoogAgentFactory>(),
						context = context,
						fallbackDispatcher = RuleBasedDispatcher(),
						inferenceTimeout =
							Duration.ofSeconds(context.scope.get<DispatcherRunConfig>().inferenceTimeoutSeconds),
						commandQueue = context.scope.get<ActuatorCommandQueue>(),
						sinkHolder = context.scope.get<SinkHolder>(),
						// Issue #847 (SP2c.24): the same per-context CycleHistory the agent renders
						// from. Capacity comes from DispatcherRunConfig.historyN; 0 disables it.
						cycleHistory = context.scope.get<CycleHistory>()
					)
				val aiPlanner = MeasuringPlanAdapter(koogAdapter)
				// Register in scope so callers outside this factory can retrieve it after the run
				// ends and log a final summary — see MeasuringPlanAdapter.logFinalSummary().
				context.scope.declare(aiPlanner)
				// Issue #847 round 4 (R4-5): DispatcherAgentModule binds the recorder with
				// arm = RULE_BASED and model = "" for EVERY context, with a comment saying LLM arms
				// would override it — nothing ever did. Left as-is, every LLM run would be written to
				// dispatcher-runs/rule_based/ under an empty model name, silently merging the two arms
				// whose comparison is the whole point of A4. Override with what this example actually
				// runs, so the per-run JSON identifies its own arm and parameters.
				context.scope.declare<DispatcherRunRecorder>(
					DefaultDispatcherRunRecorder(
						// Issue #847 (SP2c.24): the sweep driver assigns a deterministic run id per grid
						// cell and passes it in, so an interrupted sweep can tell from the file names
						// which cells already have a result and resume instead of restarting.
						runId = context.scope.get<DispatcherRunConfig>().runId ?: UUID.randomUUID().toString(),
						arm = DispatcherArm.LLM_TOOL_CALLING,
						params =
							llmRunParameters(
								context.scope.get<OllamaExecutorConfig>(),
								context.scope.get<DispatcherRunConfig>()
							)
					)
				)
				val pacingController =
					ThrottlingSimulationController(
						initialSpeedMultiplier = PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER
					)
				wireDispatcherAgent(
					context,
					loop,
					pacingController,
					plannerOverride = aiPlanner,
					plannerTickSource = koogAdapter
				)
				// Issue #847 cleanup pass: declare under the SimulationController supertype so
				// Main.runExample() can retrieve and pass the SAME instance to context.run().
				// Without this, run() defaults to NoOpSimulationController, and pacingController
				// only ever paces the AgentLoopDriver thread — never the kDisco kernel loop it was
				// built to be paced against. assertPlannerPacingCompatible checks that the
				// controller TYPE can pace; nothing previously checked that the SAME INSTANCE
				// reaches the simulation kernel.
				context.scope.declare<SimulationController>(pacingController)
				context.setMainProcess(loop)
				context
			}
	}

	/**
	 * Creates a shunting loop simulation example for GUI mode (Issue #206, #207).
	 *
	 * This example is similar to [createShuntingLoopExample] but designed for display
	 * in the animated Frame with AnimationController, EventTimelinePanel, and ControlPanel.
	 *
	 * **Real-Time Synchronization (Issue #207):**
	 * This GUI example enables real-time synchronization (1x speed) for smooth animation.
	 * The console example runs at maximum speed without synchronization.
	 *
	 * **Threading Model:**
	 * The simulation runs on a background thread (kDisco simulation thread), while the
	 * GUI updates occur on the EDT. AnimationController handles the thread marshaling.
	 *
	 * @param factory The simulation context factory
	 * @param args Command line arguments (expects endTime as args[2])
	 * @return Configured simulation context ready to run in GUI
	 * @throws ContextCreationException if configuration fails or endTime is missing
	 */
	private fun createShuntingLoopGuiExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		if (args.size < 3) {
			throw ContextCreationException("End time of simulation not specified")
		}
		val xml =
			try {
				Resources.read("cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			} catch (e: IllegalArgumentException) {
				throw ContextCreationException("Resource file vyhybna.xml not found", e)
			}
		return xml
			.byteInputStream()
			.use { stream ->
				val context = Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
				val time = args[2].toLong()
				// Initialize dynamic wrapper map by calling getInOuts()
				context.getInOuts()
				// Enable real-time synchronization for GUI mode with 1x speed multiplier
				val loop = ShuntingLoop(context, time, enableRealTimeSync = true, initialSpeedMultiplier = 1.0)
				// SP4.2 (Issue #564): pace the agent loop with the GUI's real-time sync.
				// The scoped DelegatingSimulationController is handed to the driver here;
				// gui.SimulationController attaches the live SimulationRunner as its
				// delegate when the run starts (and detaches it on stop).
				wireDispatcherAgent(context, loop, context.scope.get<DelegatingSimulationController>())
				context.setMainProcess(loop)
				context
			}
	}

	/**
	 * Creates a shunting loop AI simulation example for GUI mode (SP2b.9, Issue #566).
	 *
	 * Identical to [createShuntingLoopGuiExample] but wires a [KoogAgentPlanAdapter] as the
	 * [DispatcherPlanner] instead of the default [cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter].
	 * The adapter runs the DISPATCHER agent against the configured local Ollama model and falls
	 * back to [RuleBasedDispatcher] when the LLM stalls, returns no decisions, or throws.
	 *
	 * **Requires pacing:** [KoogAgentPlanAdapter] declares `isAsynchronous = true` (LLM inference
	 * takes real wall-clock time). This GUI version uses [DelegatingSimulationController] for
	 * pause/step/speed-change integration. For headless/console runs, use
	 * [createShuntingLoopAIExample] which uses [ThrottlingSimulationController] instead
	 * (Issue #873, R8 resolution).
	 *
	 * **Ollama prerequisite:** the local Ollama service must be reachable at the configured
	 * endpoint (default `http://localhost:11434`). Without Ollama, every LLM call times out
	 * and the rule-based fallback takes over, so the simulation still completes correctly.
	 *
	 * @param factory The simulation context factory
	 * @param args Command line arguments (expects endTime as args[2])
	 * @return Configured simulation context ready to run in GUI
	 * @throws ContextCreationException if configuration fails or endTime is missing
	 *
	 * @since Issue #566 (SP2b.9 — Goal 10)
	 */
	private fun createShuntingLoopAIGuiExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		if (args.size < 3) {
			throw ContextCreationException("End time of simulation not specified")
		}
		val xml =
			try {
				Resources.read("cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			} catch (e: IllegalArgumentException) {
				throw ContextCreationException("Resource file vyhybna.xml not found", e)
			}
		return xml
			.byteInputStream()
			.use { stream ->
				val context = Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
				val time = args[2].toLong()
				// Initialize dynamic wrapper map by calling getInOuts()
				context.getInOuts()
				// Enable real-time synchronization for GUI mode with 1x speed multiplier
				val loop = ShuntingLoop(context, time, enableRealTimeSync = true, initialSpeedMultiplier = 1.0)
				// SP2b.9 (Issue #566): build the LLM-backed planner with rule-based fallback.
				// KoogAgentPlanAdapter is async (isAsynchronous=true); DelegatingSimulationController
				// provides the required pacing for assertPlannerPacingCompatible.
				// Issue #817: wrap with MeasuringPlanAdapter to log and measure fallback vs LLM success rate.
				val koogAdapter =
					KoogAgentPlanAdapter(
						agentFactory = context.scope.get<KoogAgentFactory>(),
						context = context,
						fallbackDispatcher = RuleBasedDispatcher(),
						inferenceTimeout =
							Duration.ofSeconds(context.scope.get<DispatcherRunConfig>().inferenceTimeoutSeconds),
						commandQueue = context.scope.get<ActuatorCommandQueue>(),
						sinkHolder = context.scope.get<SinkHolder>(),
						// Issue #847 (SP2c.24): the same per-context CycleHistory the agent renders
						// from. Capacity comes from DispatcherRunConfig.historyN; 0 disables it.
						cycleHistory = context.scope.get<CycleHistory>()
					)
				val aiPlanner = MeasuringPlanAdapter(koogAdapter)
				// Register in scope so callers outside this factory (e.g. Frame's
				// SimulationController.STOPPED handler) can retrieve it after the run ends
				// and log a final summary — see MeasuringPlanAdapter.logFinalSummary().
				context.scope.declare(aiPlanner)
				wireDispatcherAgent(
					context,
					loop,
					context.scope.get<DelegatingSimulationController>(),
					plannerOverride = aiPlanner,
					plannerTickSource = koogAdapter
				)
				context.setMainProcess(loop)
				context
			}
	}

	/**
	 * Wires the SP0.11 dispatcher-agent stack onto [loop]:
	 * - resolves the Koin-scoped [NetworkPerceptionPort] and [NetworkActuatorPort] for [context]
	 *   (Goal 10 dispatcher-cannot-approve-trains fix: must be the same instances
	 *   [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory] injects into its tools —
	 *   constructing separate ones here left the tools' perception port permanently unrefreshed)
	 * - resolves [ActuatorCommandQueue] (scoped, one per context) and [DispatcherPlanner] (singleton)
	 *   from [DefaultSimulationContext.scope] via Koin — so swapping the [DispatcherPlanner] binding
	 *   in [dispatcherAgentModule][cz.vutbr.fit.interlockSim.dispatcher.di.dispatcherAgentModule]
	 *   (e.g. to an LLM-backed planner, SP3.6) takes effect here too (Goal 10 seam)
	 * - creates [DispatchDecisionApplier] (with ShuntingLoop counter callbacks, the scoped
	 *   [DispatcherModeState] for applier-mode enforcement, and the scoped
	 *   [SemiAutoApprovalGateway] as the SEMI_AUTO approver) and registers it as
	 *   [ShuntingLoop.controlStepListener]
	 * - creates [AgentLoopDriver] (with ShuntingLoop observation providers) and registers its
	 *   run-loop as [ShuntingLoop.agentDriverAction]
	 * - registers [ShuntingLoop.snapshotCaptureHook] to keep the perception-port snapshot fresh
	 *
	 * [controller] is [NoOpSimulationController] for rule-based headless (console) runs,
	 * [ThrottlingSimulationController] for the AI console run ([createShuntingLoopAIExample],
	 * Issue #873 — a real pacing controller so [assertPlannerPacingCompatible] stays effective
	 * for async LLM planners headlessly), and the scoped [DelegatingSimulationController] for
	 * GUI runs (SP4.2, Issue #564):
	 * [gui.SimulationController][cz.vutbr.fit.interlockSim.gui.SimulationController] attaches
	 * the live [SimulationRunner][cz.vutbr.fit.interlockSim.gui.SimulationRunner] as its
	 * delegate when the run starts, so the agent loop is paced by the existing real-time
	 * sync (speed multiplier, pause).
	 *
	 * [plannerOverride] allows callers to supply a specific [DispatcherPlanner] instance
	 * instead of resolving the global singleton from the Koin scope.  Pass a
	 * [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter] here for the
	 * AI-dispatcher examples (SP2b.9, Issue #566); leave `null` for all rule-based examples
	 * (falls back to the Koin-scoped singleton).
	 *
	 * @since Issue #733 (SP0.11 — Goal 10); SimulationRunner pacing wired in SP4.2 (Issue #564);
	 *   [DispatcherModeState] + [SemiAutoApprovalGateway] passed to applier in Issue #806 (SP2b.6 follow-up);
	 *   [plannerOverride] added in Issue #566 (SP2b.9)
	 */
	private fun wireDispatcherAgent(
		context: DefaultSimulationContext,
		loop: ShuntingLoop,
		controller: SimulationController,
		plannerOverride: DispatcherPlanner? = null,
		// SP2c.20 follow-up (#843): the un-wrapped KoogAgentPlanAdapter behind plannerOverride
		// (when plannerOverride is a MeasuringPlanAdapter wrapping one), so AgentLoopDriver can
		// attribute each cycle correctly via its tickListener. null for rule-based examples.
		plannerTickSource: KoogAgentPlanAdapter? = null
	) {
		// Goal 10 dispatcher-cannot-approve-trains fix: resolve the SAME NetworkPerceptionPort /
		// NetworkActuatorPort instances the Koin-scoped KoogAgentFactory uses for its tools,
		// instead of constructing separate ones here. Previously this method built its own
		// DefaultNetworkPerceptionPort and refreshed it via snapshotCaptureHook below, while
		// KoogAgentFactory's perception tools read a DIFFERENT, never-refreshed Koin-scoped
		// instance — the LLM's perception tools therefore always saw
		// SimulationSnapshot.EMPTY regardless of actual simulation state.
		val perceptionPort = context.scope.get<NetworkPerceptionPort>()
		val actuatorPort = context.scope.get<NetworkActuatorPort>()

		val queue = context.scope.get<ActuatorCommandQueue>()
		// SP2b.9 (Issue #566): plannerOverride allows callers to supply a specific planner
		// (e.g. KoogAgentPlanAdapter for AI examples) instead of the global Koin singleton.
		val planner = plannerOverride ?: context.scope.get<DispatcherPlanner>()
		// SP2b.6 (Issue #561): scoped sink that forwards each applied decision to the GUI
		// DispatcherControlPanel (the "Why this route?" button). Null-tolerant: console runs
		// resolve the hub but never attach a sink, so onDecisionApplied is a no-op there.
		val decisionListener = context.scope.getOrNull<DispatchDecisionListenerHub>()
		// SP2b.4 (Issue #559): scoped mode state that gates decisions per operator selection.
		// Null-tolerant: if the dispatcher-agent module is not loaded the applier falls back to
		// its pre-SP2b.4 behaviour (apply everything without consulting the mode state).
		val modeState = context.scope.getOrNull<DispatcherModeState>()
		// SP2b.6 follow-up (Issue #806): scoped approval gateway for SEMI_AUTO decisions.
		// The gateway holds a mutable approver (null until Frame.wireDispatcherControlPanel
		// installs the SemiAutoApprovalDialog). Headless runs leave the approver null, so
		// gateway.approve() returns false and every SEMI_AUTO actuating decision is dropped
		// (DispatchDecisionApplier already logs a warning in that case). Null-tolerant here
		// too: if the scope does not contain the gateway the semiAutoApprover is null and the
		// applier's own "no approver" fallback applies.
		val semiAutoGateway = context.scope.getOrNull<SemiAutoApprovalGateway>()
		// SP2c.17 (#840): correlation map and outcome channel for pushed outcome attribution.
		// Null-tolerant: if the dispatcher-agent module is not loaded these stay null and the
		// applier falls back to fire-and-forget behaviour (pre-SP2c.17 compatibility).
		val correlationMap = context.scope.getOrNull<CommandCorrelationMap>()
		val outcomeSink = context.scope.getOrNull<AppliedOutcomeChannel>()
		// SP2c.20 follow-up (#843): scoped ActionOutcomeAggregator so per-action attribution
		// (author, phase, ApplyFailureCode) reaches a real consumer in production, not just tests.
		val actionOutcomeAggregator = context.scope.getOrNull<ActionOutcomeAggregator>()
		// Issue #847 round 4 (R4-5): the applier accepts exactly one ActionOutcomeSink and production
		// had already spent it on the aggregator, so DispatcherRunRecorder.onActionOutcome — which
		// fills emittedByActionType, rejectionsByCode, applyFailuresByCode and actionsByAuthor in the
		// per-run JSON — had no production caller at all, and every recorded snapshot was zeroes.
		// Fan out to both instead of choosing.
		val runRecorder = context.scope.getOrNull<DispatcherRunRecorder>()
		val actionOutcomeSink =
			CompositeActionOutcomeSink.of(
				actionOutcomeAggregator,
				runRecorder?.let { it::onActionOutcome }
			)
		// SP3.6 (#574 / #187): reject async/LLM planners when the controller provides no pacing.
		// NoOpSimulationController (rule-based console runs) has no speed cap, so an async planner
		// cannot honour the 2x real-time limit there. The AI console run passes
		// ThrottlingSimulationController (Issue #873) which provides real pacing headlessly; GUI
		// runs pass the DelegatingSimulationController paced by SimulationRunner (SP4.2, #564).
		// The rule-based planner is synchronous and exempt.
		assertPlannerPacingCompatible(planner, controller)

		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = actuatorPort,
				onApproveTrain = loop::approveQueuedTrain,
				// SP2c.18 (#841): live active-train count provider for apply-time cap enforcement.
				// loop.getApprovedTrains() returns the sim-thread-owned list as a snapshot; calling
				// .size on it on the sim thread (inside onControlStep) is safe and gives the live
				// count after any earlier approvals in the same drained batch.
				activeTrainCountProvider = { loop.getApprovedTrains().size },
				onBlockTransition = loop::incrementBlockTransition,
				onFailedReservation = loop::incrementFailedReservation,
				onDecisionApplied = decisionListener,
				modeState = modeState,
				semiAutoApprover = semiAutoGateway?.let { gw -> gw::approve },
				correlationMap = correlationMap,
				outcomeSink = outcomeSink,
				actionOutcomeSink = actionOutcomeSink
			)
		// Evict the duplicate-suppression guard's entries for a train once any of its blocks
		// releases — see DispatchDecisionApplier.evictReservationsFor for why this must not
		// stay permanent (bidirectional trains can legitimately re-reserve a hop).
		context.getRoutingServices().getPathReservationService().addBlockOccupancyListener(
			object : BlockOccupancyListener {
				override fun onBlockOccupancyChanged(event: BlockOccupancyEvent) {
					if (event.type == BlockOccupancyEventType.BLOCK_RELEASED) {
						event.trainId?.let(applier::evictReservationsFor)
					}
				}
			}
		)

		// SP2c.4 (Issue #827): Goal 9 C7 ruling option (a) — latch ConflictDetectedEvents into
		// affordance reasons. The latch must be registered BEFORE run() — listeners registered
		// after run() are silently dropped (see DefaultSimulationContext.onConflictDetectedEvent).
		// The latch is the perception-path bridge for AffordanceAnnotator; it holds no reference
		// to any actuator and never enacts any simulation effect.
		val conflictHintLatch = ConflictHintLatch()
		context.onConflictDetectedEvent(conflictHintLatch::onConflict)

		// SP0.11c (Issue #746): sim-to-driver pacing signal replacing the driver's old
		// Thread.sleep(1) wall-clock poll. AgentLoopDriver.runCycle() blocks on it instead
		// of polling, eliminating the pacing race that could stall admission entirely
		// under NoOpSimulationController (headless runs) — see SnapshotSignal's KDoc.
		// Declared in scope (Issue #847 round 4, R4-1) so the end-of-run summary can report how many
		// control ticks were coalesced away while the driver was busy inside an inference. That
		// number is the whole of round 3's unreconciled "301 control ticks vs 20-29 planner cycles".
		val driverSignal = DefaultSnapshotSignal()
		context.scope.declare(driverSignal)

		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = planner,
				commandQueue = queue,
				controller = controller,
				dispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation),
				snapshotSignal = driverSignal,
				plannerTickSource = plannerTickSource,
				// Issue #847 round 4 (R4-5): DispatcherRunRecorder.onTick fills totalTicks and
				// ticksByOutcome — every tick-shaped field of the per-run JSON, and the one the
				// snapshot's own `ticksByOutcome.values.sum() == totalTicks` invariant is written
				// against. It previously had no production caller, so a recorded run reported zero
				// ticks no matter how long it ran.
				onTickRecord = runRecorder?.let { it::onTick },
				// Issue #847 (SP2c.24): the grid's tickPeriodMs axis. Zero by default, so a run
				// that does not set the property paces exactly as it did before.
				tickPeriodMs = context.scope.get<DispatcherRunConfig>().tickPeriodMs
			)

		loop.snapshotCaptureHook = perceptionPort::captureSnapshot
		// The signal fires from controlStepListener — NOT from snapshotCaptureHook — because
		// ShuntingLoop.iteration() calls snapshotCaptureHook() BEFORE it publishes the
		// per-tick TickObservation (loop.getLatestObservation, read via dispatchLoopSensorPort
		// above) but calls controlStepListener AFTER. Signalling any earlier would let the
		// driver wake and read a stale TickObservation from the previous tick — confirmed via
		// a reproduced conflictEventCount regression during development of this fix.
		// Issue #847 round 3 (PR #891 defect B): reclaim routes nothing else can release — a
		// reservation owned by a train id the model invented, or one granted to a real train that
		// never travels it. Both are permanent otherwise: every release path in
		// PathReservationRegistry requires the train to consume the route, and the registry has no
		// timestamps, sweeper or TTL. MultiTrainLoop has releaseFreeResources() as its per-iteration
		// safety net; ShuntingLoop has no equivalent, which is why one round-2 run deadlocked with
		// Train #1 holding k1 and kA reserved and unoccupied for the rest of the run.
		// Declared in scope so the end-of-run summary can report its counters.
		val orphanSweeper =
			OrphanReservationSweeper(
				perceptionPort = perceptionPort,
				dispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation),
				actuatorPort = actuatorPort,
				// Issue #847 round 4 (R4-3): reclaim the case round 3 measured most often and could
				// never release — a train stopped ON a block while holding the next one RESERVED and
				// empty ahead of it. releaseRoute cannot do it (its finally drops the occupied block
				// too), so the tail is released block by block from the public reservation API.
				partialReleaser =
					RegistryPartialRouteReleaser(
						registry = context.scope.get<PathReservationRegistry>(),
						pathReservationService = context.getRoutingServices().getPathReservationService()
					)
			)
		context.scope.declare(orphanSweeper)

		loop.controlStepListener =
			ControlStepListener {
				driverSignal.signal()
				applier.onControlStep()
				// After the applier, so a route requested this tick is not judged stale before it
				// has had a single tick to be travelled.
				orphanSweeper.sweep()
			}
		// Issue #847 round 4 (R4-2): the driver action used to be a bare
		// `while (loop.isSimActive()) { driver.runCycle() }`. platformStartDaemonThread runs it as
		// `Thread { runBlocking { action() } }` with no uncaught-exception handler, so a single
		// escaped exception killed the dispatcher silently while the kernel — which knows nothing
		// about this thread — ticked on to the requested end time and the run still exited 0. Round
		// 3's run 1 went quiet after simTime=216s for exactly this reason. AgentDriverLoop counts and
		// logs each failure, survives transient ones, and gives up loudly on a persistent one.
		// Declared in scope so the end-of-run summary reports its counters.
		val driverLoop =
			AgentDriverLoop(
				isActive = loop::isSimActive,
				runCycle = driver::runCycle
			)
		context.scope.declare(driverLoop)
		loop.agentDriverAction = { driverLoop.run() }
	}

	/**
	 * Creates a console-based multi-train shunting loop example with three simultaneous trains.
	 */
	private fun createMultiTrainLoopExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		val xml =
			try {
				Resources.read("cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			} catch (e: IllegalArgumentException) {
				throw ContextCreationException("Resource file vyhybna.xml not found", e)
			}
		return xml
			.byteInputStream()
			.use { stream ->
				val context = Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
				val endTime = if (args.size >= 3) args[2].toLong() else 300L
				val specs =
					listOf(
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 40.0),
						MultiTrainLoop.TrainSpec(inName = "B", outName = "A", inTime = 1.0, length = 40.0),
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 2.0, length = 40.0)
					)
				// Initialize dynamic wrapper map by calling getInOuts()
				context.getInOuts()
				val process = MultiTrainLoop(context, endTime, specs, enableRealTimeSync = false)
				(context.getCollisionServices().getCollisionDetectionService() as? DefaultCollisionDetectionService)
					?.registerTrainSnapshotProvider(process::getTrainSnapshot)
				context.setMainProcess(process)
				context
			}
	}

	/**
	 * Creates a GUI-based multi-train shunting loop example with three simultaneous trains.
	 */
	private fun createMultiTrainLoopGuiExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		val xml =
			try {
				Resources.read("cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			} catch (e: IllegalArgumentException) {
				throw ContextCreationException("Resource file vyhybna.xml not found", e)
			}
		return xml
			.byteInputStream()
			.use { stream ->
				val context = Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
				val endTime = if (args.size >= 3) args[2].toLong() else 300L
				val specs =
					listOf(
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 40.0),
						MultiTrainLoop.TrainSpec(inName = "B", outName = "A", inTime = 1.0, length = 40.0),
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 2.0, length = 40.0)
					)
				// Initialize dynamic wrapper map by calling getInOuts()
				context.getInOuts()
				// Enable real-time synchronization for GUI mode with 1x speed multiplier
				val process = MultiTrainLoop(context, endTime, specs, enableRealTimeSync = true)
				(context.getCollisionServices().getCollisionDetectionService() as? DefaultCollisionDetectionService)
					?.registerTrainSnapshotProvider(process::getTrainSnapshot)
				context.setMainProcess(process)
				context
			}
	}

	/**
	 * Creates a console-based three-train shunting loop prototype (Issue #584).
	 */
	private fun createThreeTrainLoopExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		val xml =
			try {
				Resources.read("cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			} catch (e: IllegalArgumentException) {
				throw ContextCreationException("Resource file vyhybna.xml not found", e)
			}
		return xml
			.byteInputStream()
			.use { stream ->
				val context = Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
				val endTime = if (args.size >= 3) args[2].toLong() else 300L
				// Initialize dynamic wrapper map by calling getInOuts()
				context.getInOuts()
				val process = ThreeTrainLoop(context, endTime, enableRealTimeSync = false)
				(context.getCollisionServices().getCollisionDetectionService() as? DefaultCollisionDetectionService)
					?.registerTrainSnapshotProvider(process::getTrainSnapshot)
				context.setMainProcess(process)
				context
			}
	}

	/**
	 * Creates a GUI-based three-train shunting loop prototype (Issue #584).
	 */
	private fun createThreeTrainLoopGuiExample(
		factory: SimulationContextFactory,
		args: Array<String>
	): SimulationContext {
		val xml =
			try {
				Resources.read("cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			} catch (e: IllegalArgumentException) {
				throw ContextCreationException("Resource file vyhybna.xml not found", e)
			}
		return xml
			.byteInputStream()
			.use { stream ->
				val context = Util.assertInstanceOf<DefaultSimulationContext>(factory.createContext(stream))
				val endTime = if (args.size >= 3) args[2].toLong() else 300L
				// Initialize dynamic wrapper map by calling getInOuts()
				context.getInOuts()
				// Enable real-time synchronization for GUI mode with 1x speed multiplier
				val process = ThreeTrainLoop(context, endTime, enableRealTimeSync = true)
				(context.getCollisionServices().getCollisionDetectionService() as? DefaultCollisionDetectionService)
					?.registerTrainSnapshotProvider(process::getTrainSnapshot)
				context.setMainProcess(process)
				context
			}
	}
}
