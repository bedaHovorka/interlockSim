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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
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
	 * - All railway switches and their configurations
	 *
	 * **Performance:** Uses pre-built caches from AnimationController to avoid
	 * O(n²) grid scans on every PropertyChangeEvent (20-80× faster).
	 *
	 * **Thread Safety:** This method accesses simulation objects. It should be
	 * called from a thread-safe context (typically after marshaling to EDT via
	 * SwingUtilities.invokeLater).
	 *
	 * @param context Simulation context to query
	 * @param semaphoreCache Pre-built list of all semaphores in grid
	 * @param switchCache Pre-built list of all switches in grid
	 * @return Immutable animation state snapshot
	 * @throws Exception if state capture fails (logged and re-thrown)
	 */
	fun captureState(
		context: SimulationContext,
		semaphoreCache: List<DynamicRailSemaphore>,
		switchCache: List<DynamicRailSwitch>
	): AnimationState =
		try {
			AnimationState(
				simulationTime = captureSimulationTime(),
				trainStates = captureTrainStates(context),
				trackStates = captureTrackStates(context),
				signalStates = captureSignalStates(context, semaphoreCache),
				switchStates = captureSwitchStates(context, switchCache)
			)
		} catch (e: Exception) {
			logger.error(e) { "Failed to capture animation state from simulation context" }
			throw e
		}

	/**
	 * Capture current simulation time.
	 *
	 * Uses jDisco Process.time() to get current simulation time in seconds.
	 *
	 * @return Current simulation time in seconds
	 */
	private fun captureSimulationTime(): Double = jDisco.Process.time()

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
		// Train uses identity-based equals/hashCode (no overrides).
		// Each Train instance is unique, so Set deduplication works correctly
		// even when same train spans multiple track blocks.
		val trains = mutableSetOf<Train>()

		// Collect all trains from occupied track blocks
		// Graph edges are DynamicTrackBlock instances after Issue #277
		for (graphBlock in graph.values()) {
			// After Issue #277, graph already contains DynamicTrackBlock instances
			// with the occupant property - no need to unwrap/rewrap
			val occupant =
				when (graphBlock) {
					is cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock -> graphBlock.occupant
					is cz.vutbr.fit.interlockSim.objects.core.TrackFacility -> {
						// Fallback for legacy code paths (should not happen after Issue #277)
						context.toDynamic(graphBlock).occupant
					}
					else -> null
				}

			// Check if occupant is a Train
			if (occupant is Train) {
				trains.add(occupant)
				logger.trace { "Found train #${occupant.getNumber()} in block ${System.identityHashCode(graphBlock)}" }
			}
		}

		logger.trace { "Capturing state for ${trains.size} active trains" }

		// Create position calculator for grid location interpolation
		// Pass separator position cache for O(1) lookups (2,500× faster than grid scan)
		val positionCalculator =
			TrainPositionCalculator(
				context,
				(context as? cz.vutbr.fit.interlockSim.context.DefaultSimulationContext)?.getSeparatorPositionCache()
					?: emptyMap()
			)

		return trains.associate { train ->
			train.getNumber() to captureTrainState(train, positionCalculator, context)
		}
	}

	/**
	 * Capture state of a single train.
	 *
	 * Captures position, velocity, acceleration, and calculates grid location
	 * for rendering via linear interpolation along the current track section.
	 *
	 * Also determines train color based on origin InOut for directional color rendering.
	 *
	 * @param train Train to capture state from
	 * @param positionCalculator Calculator for grid position interpolation
	 * @param context Simulation context for accessing InOut information
	 * @return Immutable train state snapshot
	 */
	private fun captureTrainState(
		train: Train,
		positionCalculator: TrainPositionCalculator,
		context: SimulationContext
	): TrainState {
		val trainNumber = train.getNumber()
		val position = train.getTotalDistance()
		val velocity = train.getVelocity()
		val acceleration = train.getAcceleration()
		val length = train.getLength()

		// Calculate grid location for train front
		val currentSection = train.getFrontSection()
		val frontPosition = train.getFrontPosition()
		val frontGridLocation =
			positionCalculator.calculateTrainGridLocation(
				train = train,
				currentSection = currentSection,
				distanceAlongSection = frontPosition
			)

		// Determine train color based on origin InOut
		// Blue for InOut B (odd train numbers), Orange for InOut A (even train numbers)
		val isBlueColorVariant = determineOriginColorVariant(train, context)

		return TrainState(
			trainNumber = trainNumber,
			position = position,
			velocity = velocity,
			acceleration = acceleration,
			frontGridLocation = frontGridLocation,
			length = length,
			travelingRight = isBlueColorVariant
		)
	}

	/**
	 * Determine train color variant based on origin InOut.
	 *
	 * Trains are color-coded by their entry point for visual distinction:
	 * - **Blue (true):** Trains from InOut named "B"
	 * - **Orange (false):** Trains from InOut named "A" (or any other name)
	 *
	 * This implementation uses the train's actual origin InOut from its timetable,
	 * replacing the broken odd/even heuristic that failed when Generator shuffled
	 * train creation order.
	 *
	 * **Configuration:**
	 * InOut names are defined in XML configuration (e.g., vyhybna.xml):
	 * ```xml
	 * <InOut X="30" Y="8" name="B"/>
	 * <InOut X="11" Y="8" name="A"/>
	 * ```
	 *
	 * **Edge Cases:**
	 * - If InOut has no name or null name: defaults to orange (false)
	 * - Name comparison is case-sensitive ("B" ≠ "b")
	 * - Future configurations with different names: only "B" maps to blue
	 *
	 * @param train Train to determine color variant for
	 * @param context Simulation context (unused, kept for future extensibility)
	 * @return True for blue color variant (InOut "B"), false for orange (InOut "A" or other)
	 * @since 2026-02-04 (Fixed train color coding bug)
	 */
	private fun determineOriginColorVariant(
		train: Train,
		context: SimulationContext
	): Boolean {
		val originInOut = train.getOriginInOut()
		val inOutName = originInOut.name
		return inOutName == "B"
	}

	/**
	 * Capture state of all track blocks in simulation.
	 *
	 * Iterates over graph edges (TrackBlock instances) and captures their
	 * dynamic state via toDynamic() wrapper.
	 *
	 * **Map Key:** Uses STATIC TrackBlock (staticRef) as key for renderer lookup compatibility.
	 * After Issue #277, graph contains DynamicTrackBlock wrappers, but cells store static blocks.
	 *
	 * @param context Simulation context to query
	 * @return Map of [TrackBlock] (static) to [TrackState]
	 */
	private fun captureTrackStates(context: SimulationContext): Map<TrackBlock, TrackState> {
		val graph = context.getGraph()

		// Graph edges are DynamicTrackBlock instances after Issue #277
		val trackBlocks = graph.values()

		logger.trace { "Capturing state for ${trackBlocks.count()} track blocks" }

		return trackBlocks.associate { graphBlock ->
			// Extract static block from DynamicTrackBlock wrapper
			val staticBlock =
				if (graphBlock is cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock) {
					graphBlock.staticRef as TrackBlock
				} else {
					graphBlock as TrackBlock
				}
			// Use static block as both key and parameter
			staticBlock to captureTrackState(staticBlock, context)
		}
	}

	/**
	 * Capture state of a single track block.
	 *
	 * Uses dynamic wrapper to access current occupancy state.
	 *
	 * @param staticTrackBlock Static track block (extracted from graph's DynamicTrackBlock wrapper)
	 * @param context Simulation context (for dynamic wrapper access)
	 * @return Immutable track state snapshot
	 */
	private fun captureTrackState(
		staticTrackBlock: TrackBlock,
		context: SimulationContext
	): TrackState {
		// Access DynamicTrack wrapper via toDynamic() to get current state
		// This returns the canonical DynamicTrack instance with mutable state
		val dynamicTrack = context.toDynamic(staticTrackBlock as cz.vutbr.fit.interlockSim.objects.core.TrackFacility)

		val capturedState = dynamicTrack.state
		logger.trace {
			"Captured track state: block@${System.identityHashCode(staticTrackBlock)} " +
				"(${staticTrackBlock.toString().take(15)}), state=$capturedState"
		}

		return TrackState(
			trackBlock = staticTrackBlock, // Use static block as key for renderer lookup
			state = capturedState
		)
	}

	/**
	 * Capture state of all semaphores in simulation.
	 *
	 * Uses pre-built cache from AnimationController instead of O(n²) grid scan.
	 * This method is called on every PropertyChangeEvent, so performance is critical.
	 *
	 * Note: SimulationContext grid contains DYNAMIC cells after transformation.
	 * DynamicRailSemaphore instances are already in the grid - no toDynamic() conversion needed.
	 * The transformation from static to dynamic happens during ContextTransformer.createSimulationContext()
	 * via GridTransformer.transformGrid().
	 *
	 * @param context Simulation context to query
	 * @param semaphoreCache Pre-built list of all semaphores in grid (from AnimationController)
	 * @return Map of [RailSemaphore] (static reference) to [SignalState]
	 */
	private fun captureSignalStates(
		context: SimulationContext,
		semaphoreCache: List<DynamicRailSemaphore>
	): Map<RailSemaphore, SignalState> {
		logger.trace { "Capturing state for ${semaphoreCache.size} semaphores (using cache)" }

		return semaphoreCache.associate { dynamicSemaphore ->
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

	/**
	 * Capture state of all railway switches in simulation.
	 *
	 * Uses pre-built cache from AnimationController instead of O(n²) grid scan.
	 * This method is called on every PropertyChangeEvent, so performance is critical.
	 *
	 * Note: SimulationContext grid contains DYNAMIC cells after transformation.
	 * DynamicRailSwitch instances are already in the grid - no toDynamic() conversion needed.
	 * The transformation from static to dynamic happens during ContextTransformer.createSimulationContext()
	 * via GridTransformer.transformGrid().
	 *
	 * @param context Simulation context to query
	 * @param switchCache Pre-built list of all switches in grid (from AnimationController)
	 * @return Map of [RailSwitch] (static reference) to [SwitchState]
	 */
	private fun captureSwitchStates(
		context: SimulationContext,
		switchCache: List<DynamicRailSwitch>
	): Map<RailSwitch, SwitchState> {
		logger.trace { "Capturing state for ${switchCache.size} switches (using cache)" }

		return switchCache.associate { dynamicSwitch ->
			dynamicSwitch.staticRef to captureSwitchState(dynamicSwitch)
		}
	}

	/**
	 * Capture state of a single railway switch.
	 *
	 * Extracts current configuration (MAIN/BRANCH) from dynamic wrapper.
	 *
	 * @param dynamicSwitch Dynamic switch wrapper with current state
	 * @return Immutable switch state snapshot
	 */
	private fun captureSwitchState(dynamicSwitch: DynamicRailSwitch): SwitchState {
		val conf = dynamicSwitch.conf

		return SwitchState(
			railSwitch = dynamicSwitch.staticRef,
			conf = conf
		)
	}
}
