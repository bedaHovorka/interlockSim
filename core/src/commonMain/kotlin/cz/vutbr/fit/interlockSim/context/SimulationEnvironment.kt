/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import cz.hovorka.kdisco.Condition
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrack
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.hovorka.kdisco.SimulationEvent as KDiscoSimulationEvent

/**
 * Facade interface for simulation environment operations.
 *
 * Provides sim/ package with access to network queries, dynamic state management,
 * and simulation control without depending on full [SimulationContext] contract.
 *
 * ## Purpose
 *
 * Decouples simulation classes ([cz.vutbr.fit.interlockSim.sim.Train],
 * [InOutWorker], [cz.vutbr.fit.interlockSim.sim.Generator],
 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop]) from [SimulationContext]
 * implementation details. This enables:
 * - Simpler test doubles (mock only needed methods)
 * - Future migration to DSOL/Kalasim simulation engines
 * - Clear contract for what simulation processes can access
 *
 * ## Method Groups
 *
 * The interface is organized into logical groups for clarity:
 *
 * **Network Query Operations:**
 * - [getInOuts] - Get all entry/exit points
 * - [isSeparatorInDirection] - Check signal orientation
 * - [getTopologyNavigator] - Get static topology navigation service
 * - [getPathReservationService] - Get path reservation service
 * - [getTrainNavigationService] - Get service for train-specific path navigation
 *
 * **Dynamic State Management:**
 * - [toDynamic] (PathSeparator) - Convert to dynamic wrapper
 * - [toDynamic] (TrackFacility) - Convert track to dynamic wrapper
 * - [getWorkerFor] - Get InOut worker process
 *
 * **Simulation Control:**
 * - [report] - Log simulation events
 * - [stop] / [errorStop] - Terminate simulation
 * - [isReporting] / [addReportTypes] - Configure logging
 *
 * ## Implementation Note
 *
 * [DefaultSimulationContext] implements this interface (via [SimulationContext]
 * which extends this interface). No behavior changes required.
 *
 * ## DSOL Migration
 *
 * When migrating from kDisco to DSOL:
 * 1. Create DSOLSimulationEnvironment implementing this interface
 * 2. Update [SimulationProcessFactory] to accept SimulationEnvironment
 * 3. Simulation classes (Train, etc.) work unchanged
 *
 * @see SimulationContext
 * @see DefaultSimulationContext
 * @see SimulationProcessFactory
 * @since 2026-01 (Issue #94)
 */
@Suppress("TooManyFunctions", "ComplexInterface") // Facade for simulation subsystems; splitting would hurt usability
interface SimulationEnvironment {
	// ========================================
	// Network Query Operations
	// ========================================

	/**
	 * Get all InOut (entry/exit) points in the railway network.
	 * Returns dynamic wrappers for simulation state management.
	 *
	 * @return Collection of DynamicInOut wrappers
	 */
	fun getInOuts(): Collection<DynamicInOut>

	/**
	 * Get topology navigator for pure topology navigation (no state dependencies).
	 *
	 * The TopologyNavigator provides static graph traversal without any dependency on
	 * dynamic state (block reservations, occupancy, etc.). Use this for finding the next
	 * track section based purely on network topology.
	 *
	 * ## Use Cases
	 *
	 * - InOutWorker finding initial track section from InOut
	 * - Network validation and connectivity analysis
	 * - Editor features requiring topology queries
	 *
	 * @return TopologyNavigator instance for this simulation context
	 * @see TopologyNavigator
	 * @since Issue #296 Phase 5 (InOutWorker dependency)
	 */
	fun getTopologyNavigator(): TopologyNavigator

	/**
	 * Check if an oriented separator (semaphore) faces the specified direction.
	 * Used to verify trains are approaching signals from the correct direction.
	 *
	 * @param separator The oriented signal to check
	 * @param next Track after separator (direction train is going TO)
	 * @param previous Track before separator (direction train is coming FROM)
	 * @return true if separator is oriented toward the path from previous to next
	 */
	fun isSeparatorInDirection(
		separator: OrientedPathSeparator,
		next: Track?,
		previous: Track?
	): Boolean

	/**
	 * Get train navigation service for train-specific path following.
	 *
	 * The TrainNavigationService provides train-specific path navigation that validates
	 * block ownership. It only returns paths through blocks RESERVED for the specific train.
	 *
	 * ## Use Cases
	 *
	 * - Train requests path to next semaphore (only through owned blocks)
	 * - Train waits when blocks are reserved for different train
	 * - Train resumes when path becomes available
	 *
	 * ## Example Usage
	 *
	 * ```kotlin
	 * // In Train.Front.semaphoreAction():
	 * val trainNavService = env.getTrainNavigationService()
	 * val path = trainNavService.findReservedPathForTrain(
	 *     trainId = toString(),
	 *     separator = semaphore,
	 *     next = next
	 * )
	 *
	 * if (path == null) {
	 *     // Path not reserved for this train, halt and wait
	 *     fireStop()
	 *     waitUntil { trainNavService.isPathReservedForTrain(...) }
	 * } else {
	 *     // Path is reserved for us, continue
	 *     accelerateToSignal(semaphore, path)
	 * }
	 * ```
	 *
	 * @return TrainNavigationService instance for this simulation context
	 * @see TrainNavigationService
	 * @since Issue #295 (Phase 3 of Issue #292)
	 */
	fun getTrainNavigationService(): TrainNavigationService

	/**
	 * Create a kDisco [Condition] that becomes true when the path starting at
	 * [separator] is reserved for [trainId].
	 *
	 * This lets a train process suspend with [cz.hovorka.kdisco.Process.waitUntil]
	 * and resume deterministically as soon as the dispatcher reserves the path
	 * (or as soon as a conflicting train releases the required blocks).
	 *
	 * The condition is evaluated after every discrete event, so it integrates
	 * with kDisco event scheduling without busy-polling.
	 *
	 * @param trainId The train waiting for a path
	 * @param separator The separator where the train is waiting
	 * @return A condition that is true when [findReservedPathForTrain] returns [PathResult.Available]
	 * @since Issue #582 (Goal 1 SP3)
	 */
	fun createPathAvailableCondition(
		trainId: String,
		separator: PathSeparator
	): Condition =
		Condition {
			getTrainNavigationService().findReservedPathForTrain(trainId, separator) is PathResult.Available
		}

	/**
	 * Get path reservation service for dispatcher/interlocking path reservation.
	 *
	 * The PathReservationService provides atomic path reservation with train ownership
	 * tracking. Used by dispatchers and interlocking logic to reserve paths before
	 * trains enter the network.
	 *
	 * ## Use Cases
	 *
	 * - InOutWorker reserves path for incoming train
	 * - Interlocking reserves continuation path when train approaches semaphore
	 * - Dispatcher pre-reserves paths for scheduled trains
	 *
	 * ## Example Usage
	 *
	 * ```kotlin
	 * // In InOutWorker or Interlocking:
	 * val pathService = env.getPathReservationService()
	 * val result = pathService.reservePath(trainId, start, target)
	 *
	 * when (result) {
	 *     is Success -> {
	 *         // Path reserved, train can proceed
	 *         approveTrainEntry(train)
	 *     }
	 *     is AllPathsBlocked -> {
	 *         // Wait for path to become available
	 *         waitUntil { pathService.isPathAvailable(start, target) }
	 *     }
	 * }
	 * ```
	 *
	 * @return PathReservationService instance for this simulation context
	 * @see cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
	 * @since Issue #296 (ShuntingLoop refactoring)
	 */
	fun getPathReservationService(): cz.vutbr.fit.interlockSim.context.navigation.PathReservationService

	/**
	 * Get the automatic path finding service for static Dijkstra-based route search.
	 *
	 * The service computes shortest and all-topological paths from a start [PathSeparator]
	 * to a target [PathSeparator] without consulting dynamic reservation state. Useful for
	 * dispatcher logic that needs to know which routes exist before attempting reservation.
	 *
	 * @return AutomaticPathFindingService scoped to this simulation context
	 * @see cz.vutbr.fit.interlockSim.pathfinding.AutomaticPathFindingService
	 */
	fun getAutomaticPathFindingService(): cz.vutbr.fit.interlockSim.pathfinding.AutomaticPathFindingService

	// ========================================
	// Grid and Graph Access
	// ========================================

	/**
	 * Get the railway network grid for spatial cell access.
	 *
	 * Used by path reservation service for:
	 * - Grid location lookup (getRailWayNetGrid().getLocation(separator))
	 * - Cell scanning for semaphore discovery
	 * - Network topology analysis
	 *
	 * ## Navigation Service Requirements
	 *
	 * PathReservationService needs grid access to:
	 * 1. Find separator locations in the network
	 * 2. Query track connections at specific grid coordinates
	 * 3. Scan grid cells for semaphore discovery (reservePathToAny)
	 *
	 * ## Implementation Note
	 *
	 * This method is part of the Context<C, T> parent interface.
	 * DefaultSimulationContext already provides the implementation.
	 *
	 * @return RailwayNetGrid<Cell> containing all grid cells
	 * @see Context.getRailWayNetGrid
	 * @since Fix type safety violations (SimulationEnvironment extension)
	 */
	fun getRailWayNetGrid(): cz.vutbr.fit.interlockSim.context.RailwayNetGrid<cz.vutbr.fit.interlockSim.objects.core.Cell>

	/**
	 * Get the track block graph for topology queries.
	 *
	 * Used by path reservation service for:
	 * - Edge lookup by segment (graph.assignedEdges(location))
	 * - Track connectivity analysis
	 * - Path validation and routing
	 *
	 * ## Navigation Service Requirements
	 *
	 * PathReservationService needs graph access to:
	 * 1. Query outgoing edges from a separator location
	 * 2. Navigate track connections during path search
	 * 3. Calculate travel directions for multi-path discovery
	 *
	 * ## Implementation Note
	 *
	 * This method is part of the Context<C, T> parent interface.
	 * DefaultSimulationContext already provides the implementation.
	 *
	 * @return ExtendedUnorientedGraph with Point nodes and DynamicTrackBlock edges
	 * @see Context.getGraph
	 * @since Fix type safety violations (SimulationEnvironment extension)
	 */
	fun getGraph(): cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph<
		cz.vutbr.fit.interlockSim.util.Point,
		cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock,
		cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
	>

	/**
	 * Configure semaphore signal appearance after path reservation.
	 *
	 * This method separates signal configuration from block reservation logic.
	 * PathReservationService handles block ownership tracking, while this method
	 * updates semaphore visual signals (GO/SLOW/STOP) to match the reserved path.
	 *
	 * ## Separation of Concerns
	 *
	 * - **PathReservationService**: Owns block reservation and ownership tracking
	 * - **configureSemaphoreSignal**: Owns semaphore visual state updates
	 *
	 * This separation allows:
	 * - Clear API responsibilities
	 * - Non-fatal signal configuration failures (blocks already reserved)
	 * - Independent testing of reservation vs signal logic
	 *
	 * ## Example Usage
	 *
	 * ```kotlin
	 * val result = env.getPathReservationService().reservePath(trainId, start, target)
	 * when (result) {
	 *     is Success -> {
	 *         if (result.reservedBlocks.isNotEmpty()) {
	 *             env.configureSemaphoreSignal(
	 *                 semaphore = target as DynamicRailSemaphore,
	 *                 firstBlock = result.reservedBlocks.first(),
	 *                 allowedSpeed = 40.0
	 *             )
	 *         }
	 *     }
	 * }
	 * ```
	 *
	 * ## Error Handling
	 *
	 * Signal configuration failures are non-fatal - if semaphore update fails,
	 * blocks remain reserved and trains can proceed. Only logs warning.
	 *
	 * @param semaphore The semaphore to configure
	 * @param firstBlock First reserved block in the path
	 * @param allowedSpeed Speed limit for the path (null = auto-calculate from firstBlock)
	 */
	fun configureSemaphoreSignal(
		semaphore: DynamicRailSemaphore,
		firstBlock: DynamicTrackBlock,
		allowedSpeed: Double? = null
	)

	// ========================================
	// Dynamic State Management
	// ========================================

	/**
	 * Convert static PathSeparator to dynamic wrapper.
	 * Ensures consistent use of dynamic types throughout simulation.
	 *
	 * @param separator Static or dynamic separator
	 * @return Dynamic wrapper (existing or newly retrieved)
	 * @throws IllegalStateException if static separator not found in map
	 */
	fun toDynamic(separator: PathSeparator): DynamicPathSeparator

	/**
	 * Convert TrackFacility to DynamicTrack wrapper.
	 * Required for train state management (enter/leave operations).
	 *
	 * @param track Track facility to wrap
	 * @return DynamicTrack wrapper for state operations
	 */
	fun toDynamic(track: TrackFacility): DynamicTrack

	/**
	 * Get the InOut worker process for a specific entry/exit point.
	 *
	 * @param inOut The entry/exit point
	 * @return Worker process managing train queue for this InOut
	 * @throws IllegalStateException if no worker exists
	 */
	fun getWorkerFor(inOut: DynamicInOut): InOutWorker

	// ========================================
	// Simulation Control
	// ========================================

	/**
	 * Report simulation event for logging.
	 * Only outputs if the report type is enabled.
	 *
	 * @param report Message to log
	 * @param obj Object generating the report
	 * @param type Category of report (TRAIN_EVENTS, etc.)
	 */
	fun report(
		report: CharSequence,
		obj: Any,
		type: SimulationContext.ReportType
	)

	/**
	 * Stop simulation normally.
	 * @throws cz.vutbr.fit.interlockSim.exceptions.SimulationException if stop fails
	 */
	fun stop()

	/**
	 * Stop simulation due to error with diagnostic reporting.
	 *
	 * Called by simulation processes when fatal errors occur during execution.
	 * Performs graceful shutdown and reports the error for debugging.
	 *
	 * ## Behavior
	 * - Stops all simulation processes (equivalent to [stop])
	 * - Prints error stack trace to stderr
	 * - Does **NOT** exit JVM (allows continued execution)
	 *
	 * ## Post-Conditions
	 * - Simulation cannot be resumed (must create new context)
	 * - JVM continues running (can run new simulations, tests, etc.)
	 *
	 * @param error The error that caused termination
	 * @see stop Normal simulation termination
	 */
	fun errorStop(error: Throwable)

	/**
	 * Check if a report type is currently enabled.
	 *
	 * @param type Report type to check
	 * @return true if reports of this type are being logged
	 */
	fun isReporting(type: SimulationContext.ReportType): Boolean

	/**
	 * Enable report types for logging.
	 *
	 * @param types Report types to enable
	 */
	fun addReportTypes(vararg types: SimulationContext.ReportType)

	/**
	 * Release all path reservations for a train that has completed its journey.
	 *
	 * When a train reaches its destination and completes, it MUST release all
	 * blocks it has reserved from the PathReservationRegistry. This ensures
	 * subsequent trains can reserve those blocks without conflicts.
	 *
	 * ## Use Case
	 *
	 * Called by [cz.vutbr.fit.interlockSim.sim.Train] when it completes its
	 * journey and reaches the destination InOut from its timetable.
	 *
	 * ## Example Usage
	 *
	 * ```kotlin
	 * // In Train.actions() when journey complete:
	 * env.releaseTrainReservations(trainId = name)
	 * env.report("ends", this, ReportType.TRAIN_EVENTS)
	 * ```
	 *
	 * @param trainId The train identifier to release reservations for
	 */
	fun releaseTrainReservations(trainId: String)

	/**
	 * Unregister a single block for a train.
	 *
	 * Removes the block from the registry if it is FREE (no occupant).
	 * This is called automatically by the Train's Tail process after leaving a block,
	 * ensuring blocks are cleaned up as soon as they become available.
	 *
	 * ## Use Case
	 *
	 * Called by Train's Tail after leaving a block:
	 * ```kotlin
	 * if (current != null) {
	 *     current.leave(this@Train)
	 *     env.unregisterBlock(trainId = name, block = current)
	 * }
	 * ```
	 *
	 * @param trainId The train identifier
	 * @param block The block to unregister
	 */
	fun unregisterBlock(
		trainId: String,
		block: DynamicTrackBlock
	)

	// ========================================
	// External Observer API
	// ========================================

	/**
	 * Subscribe an external (non-train) agent to legacy block reservation/release events.
	 *
	 * The subscriber receives [cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent]
	 * instances when blocks are reserved or released via the path reservation service.
	 * For occupancy enter/leave events, prefer [onBlockEvent] (Issue #569).
	 *
	 * @param listener The listener to add
	 */
	fun addBlockOccupancyListener(listener: BlockOccupancyListener) {
		getPathReservationService().addBlockOccupancyListener(listener)
	}

	/**
	 * Unsubscribe an external agent from block occupancy/release events.
	 *
	 * @param listener The listener to remove
	 */
	fun removeBlockOccupancyListener(listener: BlockOccupancyListener) {
		getPathReservationService().removeBlockOccupancyListener(listener)
	}

	// ========================================
	// Event Subscription (Issue #569)
	// ========================================

	/**
	 * Subscribe to block-level domain events (reserve / release / occupancy changes).
	 *
	 * Listener is called synchronously on the simulation thread in simulation-time order.
	 * Listeners registered after [run] has started are silently ignored (this is based on simulation start,
	 * not on context freezing).
	 *
	 * @since Issue #569 (Goal 10 prereq)
	 */
	fun onBlockEvent(listener: (BlockEvent) -> Unit)

	/**
	 * Subscribe to raw kdisco simulation events (process lifecycle, resource changes, custom payloads).
	 *
	 * Listener is called synchronously on the simulation thread in simulation-time order.
	 * Listeners registered after [run] has started are silently ignored (this is based on simulation start,
	 * not on context freezing).
	 *
	 * @since Issue #569 (Goal 10 prereq)
	 */
	fun onSimulationEvent(listener: (KDiscoSimulationEvent) -> Unit)
}
