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
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.planner.assertPlannerPacingCompatible
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.ThreeTrainLoop
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.util.Resources
import cz.vutbr.fit.interlockSim.util.Util

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
	 */
	val examples: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext> =
		mapOf(
			"shuntingLoop" to ::createShuntingLoopExample,
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
				wireDispatcherAgent(context, loop, NoOpSimulationController)
				context.setMainProcess(loop)
				context
			}
	}

	/**
	 * Wires the SP0.11 dispatcher-agent stack onto [loop]:
	 * - creates [DefaultNetworkPerceptionPort] and [DefaultNetworkActuatorPort] backed by [context]
	 * - resolves [ActuatorCommandQueue] (scoped, one per context) and [DispatcherPlanner] (singleton)
	 *   from [DefaultSimulationContext.scope] via Koin — so swapping the [DispatcherPlanner] binding
	 *   in [dispatcherAgentModule][cz.vutbr.fit.interlockSim.dispatcher.di.dispatcherAgentModule]
	 *   (e.g. to an LLM-backed planner, SP3.6) takes effect here too (Goal 10 seam)
	 * - creates [DispatchDecisionApplier] (with ShuntingLoop counter callbacks) and registers
	 *   it as [ShuntingLoop.controlStepListener]
	 * - creates [AgentLoopDriver] (with ShuntingLoop observation providers) and registers its
	 *   run-loop as [ShuntingLoop.agentDriverAction]
	 * - registers [ShuntingLoop.snapshotCaptureHook] to keep the perception-port snapshot fresh
	 *
	 * [controller] is `[NoOpSimulationController]` for headless runs and the GUI's
	 * [SimulationRunner][cz.vutbr.fit.interlockSim.context.SimulationController] for GUI runs.
	 * For SP0.11, both use [NoOpSimulationController]; pacing via [SimulationRunner] is a
	 * follow-up task (SP1.4, #549).
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	private fun wireDispatcherAgent(
		context: DefaultSimulationContext,
		loop: ShuntingLoop,
		controller: SimulationController
	) {
		val perceptionPort =
			DefaultNetworkPerceptionPort(
				env = context,
				activeTrains = loop::getApprovedTrains
			)
		val actuatorPort =
			DefaultNetworkActuatorPort(
				env = context,
				// SP3.5 (Issue #573): wire InterlockingFacade as the single chokepoint so all
				// requestRoute calls (tool → queue → applier → port) pass through the safety kernel.
				interlockingFacade = context.scope.get<InterlockingFacade>()
			)

		val queue = context.scope.get<ActuatorCommandQueue>()
		val planner = context.scope.get<DispatcherPlanner>()
		// SP3.6 (#574 / #187): reject async/LLM planners until SimulationRunner pacing is wired
		// (SP1.4, #549). NoOpSimulationController provides no speed cap, so an async planner cannot
		// honour the 2× real-time limit. The rule-based planner is synchronous and exempt.
		assertPlannerPacingCompatible(planner, controller)

		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = actuatorPort,
				onApproveTrain = loop::approveQueuedTrain,
				onBlockTransition = loop::incrementBlockTransition,
				onFailedReservation = loop::incrementFailedReservation
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

		val driver =
			AgentLoopDriver(
				perceptionPort = perceptionPort,
				planner = planner,
				commandQueue = queue,
				controller = controller,
				observationProvider = loop::getLatestObservation
			)

		loop.snapshotCaptureHook = perceptionPort::captureSnapshot
		loop.controlStepListener = applier
		loop.agentDriverAction = {
			while (loop.isSimActive()) {
				driver.runCycle()
			}
		}
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
