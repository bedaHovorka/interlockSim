/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.animation

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Utility for capturing immutable simulation state snapshots for animation.
 *
 * This object encapsulates the logic for querying [SimulationContext] and creating
 * thread-safe, immutable [AnimationState] snapshots. It handles the complexity of
 * accessing dynamic wrappers and extracting visual state from the simulation.
 *
 * ## Design Rationale
 *
 * Simulation state is spread across multiple mutable objects (DynamicTrack,
 * DynamicRailSemaphore) in the simulation thread. To safely render on the Swing EDT,
 * we create immutable snapshots that can be transferred between threads.
 *
 * ## MVP Limitations
 *
 * - Train state capture is NOT implemented (requires public Train API - future work)
 * - Focus on track and semaphore state only (sufficient for initial animation)
 *
 * ## Usage
 *
 * ```kotlin
 * val state = AnimationStateCapture.captureState(simulationContext)
 * // state is now an immutable snapshot, safe to use on EDT
 * ```
 *
 * @see AnimationState
 * @see AnimationController
 */
object AnimationStateCapture {

	/**
	 * Capture complete simulation state as immutable snapshot.
	 *
	 * Queries simulation context for:
	 * - All track blocks and their occupancy states
	 * - All semaphores and their signal indications
	 * - (Trains deferred to future iteration - requires public API)
	 *
	 * **Thread Safety:** This method accesses simulation objects. It should be
	 * called from a thread-safe context (typically after marshaling to EDT via
	 * SwingUtilities.invokeLater).
	 *
	 * @param context Simulation context to query
	 * @return Immutable animation state snapshot
	 * @throws Exception if state capture fails (logged and re-thrown)
	 */
	fun captureState(context: SimulationContext): AnimationState {
		return try {
			AnimationState(
				simulationTime = captureSimulationTime(),
				trainStates = emptyMap(), // TODO: Implement when Train API is public
				trackStates = captureTrackStates(context),
				signalStates = captureSignalStates(context)
			)
		} catch (e: Exception) {
			logger.error(e) { "Failed to capture animation state from simulation context" }
			throw e
		}
	}

	/**
	 * Capture current simulation time.
	 *
	 * Uses jDisco Process.time() to get current simulation time in seconds.
	 *
	 * @return Current simulation time in seconds
	 */
	private fun captureSimulationTime(): Double {
		return jDisco.Process.time()
	}

	/**
	 * Capture state of all track blocks in simulation.
	 *
	 * Iterates over graph to find all TrackBlock instances and captures their
	 * dynamic state via toDynamic() wrapper.
	 *
	 * @param context Simulation context to query
	 * @return Map of [TrackBlock] to [TrackState]
	 */
	private fun captureTrackStates(context: SimulationContext): Map<TrackBlock, TrackState> {
		val graph = context.getGraph()
		val trackBlocks = mutableSetOf<TrackBlock>()

		// Collect all TrackBlock instances from graph nodes
		for (node in graph.nodeSet()) {
			if (node is TrackBlock) {
				trackBlocks.add(node)
			}
		}

		logger.trace { "Capturing state for ${trackBlocks.size} track blocks" }

		return trackBlocks.associate { trackBlock ->
			trackBlock to captureTrackState(trackBlock, context)
		}
	}

	/**
	 * Capture state of a single track block.
	 *
	 * Uses dynamic wrapper to access current occupancy state.
	 *
	 * @param trackBlock Track block to capture state from
	 * @param context Simulation context (for dynamic wrapper access)
	 * @return Immutable track state snapshot
	 */
	private fun captureTrackState(trackBlock: TrackBlock, context: SimulationContext): TrackState {
		// Access dynamic wrapper to get current state
		// TrackBlock extends Track which extends TrackFacility
		val dynamicTrack = context.toDynamic(trackBlock as cz.vutbr.fit.interlockSim.objects.core.TrackFacility)

		return TrackState(
			trackBlock = trackBlock,
			state = dynamicTrack.state
		)
	}

	/**
	 * Capture state of all semaphores in simulation.
	 *
	 * Iterates over grid to find all RailSemaphore cells and captures their
	 * dynamic signal state.
	 *
	 * @param context Simulation context to query
	 * @return Map of [RailSemaphore] to [SignalState]
	 */
	private fun captureSignalStates(context: SimulationContext): Map<RailSemaphore, SignalState> {
		val grid = context.getRailWayNetGrid()
		val semaphores = mutableListOf<RailSemaphore>()

		// Iterate grid to find all RailSemaphore cells
		for (x in 0 until grid.getCols()) {
			for (y in 0 until grid.getRows()) {
				val cell = grid.getCellAt(x, y)
				if (cell is RailSemaphore) {
					semaphores.add(cell)
				}
			}
		}

		logger.trace { "Capturing state for ${semaphores.size} semaphores" }

		return semaphores.associate { semaphore ->
			semaphore to captureSignalState(semaphore, context)
		}
	}

	/**
	 * Capture state of a single semaphore.
	 *
	 * Uses dynamic wrapper to access current signal indication.
	 *
	 * @param semaphore Semaphore to capture state from
	 * @param context Simulation context (for dynamic wrapper access)
	 * @return Immutable signal state snapshot
	 */
	private fun captureSignalState(semaphore: RailSemaphore, context: SimulationContext): SignalState {
		// Access dynamic wrapper to get current signal
		// Note: Type cast is acceptable for MVP (per team meeting decision)
		val dynamicSemaphore = context.toDynamic(semaphore) as DynamicRailSemaphore
		val signal = dynamicSemaphore.signal

		return SignalState(
			semaphore = semaphore,
			signal = signal
		)
	}
}
