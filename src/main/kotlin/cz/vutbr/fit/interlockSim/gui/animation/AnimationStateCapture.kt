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
import cz.vutbr.fit.interlockSim.sim.Train
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
 * ## Train State Capture (Issue #203)
 *
 * - Trains are collected from occupied track blocks (via TrackOccupant interface)
 * - Grid positions calculated via linear interpolation along track sections
 * - Uses new public Train API: getNumber(), getFrontSection(), getFrontPosition()
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
	 * - All active trains and their positions (via occupied track blocks)
	 * - All track blocks and their occupancy states
	 * - All semaphores and their signal indications
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
				trainStates = captureTrainStates(context),
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
	 * Capture state of all active trains in simulation.
	 *
	 * Collects trains by iterating over all track blocks and finding occupied tracks.
	 * Each occupied track has a train (TrackOccupant) that we capture state from.
	 *
	 * Uses [TrainPositionCalculator] to calculate grid positions for train rendering.
	 *
	 * @param context Simulation context to query
	 * @return Map of train number to [TrainState]
	 */
	private fun captureTrainStates(context: SimulationContext): Map<Int, TrainState> {
		val graph = context.getGraph()
		val trains = mutableSetOf<Train>()

		// Collect all trains from occupied track blocks
		// Graph edges are TrackBlock instances
		for (trackBlock in graph.values()) {
			val dynamicTrack = context.toDynamic(trackBlock as cz.vutbr.fit.interlockSim.objects.core.TrackFacility)
			val occupant = dynamicTrack.occupant

			// Check if occupant is a Train
			if (occupant is Train) {
				trains.add(occupant)
			}
		}

		logger.trace { "Capturing state for ${trains.size} active trains" }

		// Create position calculator for grid location interpolation
		val positionCalculator = TrainPositionCalculator(context)

		return trains.associate { train ->
			train.getNumber() to captureTrainState(train, positionCalculator)
		}
	}

	/**
	 * Capture state of a single train.
	 *
	 * Captures position, velocity, acceleration, and calculates grid location
	 * for rendering via linear interpolation along the current track section.
	 *
	 * @param train Train to capture state from
	 * @param positionCalculator Calculator for grid position interpolation
	 * @return Immutable train state snapshot
	 */
	private fun captureTrainState(
		train: Train,
		positionCalculator: TrainPositionCalculator
	): TrainState {
		val trainNumber = train.getNumber()
		val position = train.getTotalDistance()
		val velocity = train.getVelocity()
		val acceleration = train.getAcceleration()
		val length = train.getLength()

		// Calculate grid location for train front
		val currentSection = train.getFrontSection()
		val frontPosition = train.getFrontPosition()
		val frontGridLocation = positionCalculator.calculateTrainGridLocation(
			currentSection = currentSection,
			distanceAlongSection = frontPosition
		)

		return TrainState(
			trainNumber = trainNumber,
			position = position,
			velocity = velocity,
			acceleration = acceleration,
			frontGridLocation = frontGridLocation,
			length = length
		)
	}

	/**
	 * Capture state of all track blocks in simulation.
	 *
	 * Iterates over graph edges (TrackBlock instances) and captures their
	 * dynamic state via toDynamic() wrapper.
	 *
	 * @param context Simulation context to query
	 * @return Map of [TrackBlock] to [TrackState]
	 */
	private fun captureTrackStates(context: SimulationContext): Map<TrackBlock, TrackState> {
		val graph = context.getGraph()

		// Graph edges are TrackBlock instances
		val trackBlocks = graph.values()

		logger.trace { "Capturing state for ${trackBlocks.count()} track blocks" }

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
	 * Iterates over grid to find all RailSemaphore cells (static), converts them
	 * to dynamic wrappers using context.toDynamic(), and captures their signal state.
	 *
	 * Note: SimulationContext grid contains static cells. Dynamic wrappers are obtained
	 * via toDynamic() method which looks up the staticToDynamicMap created during
	 * context initialization.
	 *
	 * @param context Simulation context to query
	 * @return Map of [RailSemaphore] to [SignalState]
	 */
	private fun captureSignalStates(context: SimulationContext): Map<RailSemaphore, SignalState> {
		val grid = context.getRailWayNetGrid()
		val semaphores = mutableListOf<DynamicRailSemaphore>()

		// Iterate grid to find all RailSemaphore cells (static) and convert to dynamic
		for (x in 0 until grid.getCols()) {
			for (y in 0 until grid.getRows()) {
				val cell = grid.getCellAt(x, y)
				if (cell is RailSemaphore) {
					// Convert static RailSemaphore to dynamic wrapper
					val dynamicSemaphore = context.toDynamic(cell) as DynamicRailSemaphore
					semaphores.add(dynamicSemaphore)
				}
			}
		}

		logger.trace { "Capturing state for ${semaphores.size} semaphores" }

		return semaphores.associate { dynamicSemaphore ->
			dynamicSemaphore.staticRef to captureSignalState(dynamicSemaphore)
		}
	}

	/**
	 * Capture state of a single semaphore.
	 *
	 * Extracts current signal indication from dynamic wrapper.
	 *
	 * @param dynamicSemaphore Dynamic semaphore wrapper with current state
	 * @return Immutable signal state snapshot
	 */
	private fun captureSignalState(dynamicSemaphore: DynamicRailSemaphore): SignalState {
		val signal = dynamicSemaphore.signal

		return SignalState(
			semaphore = dynamicSemaphore.staticRef,
			signal = signal
		)
	}
}
