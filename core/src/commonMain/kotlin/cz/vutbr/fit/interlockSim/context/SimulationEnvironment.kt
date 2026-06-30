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
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
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
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.sim.conflict.TemporalConflictDetector
import cz.vutbr.fit.interlockSim.sim.conflict.TemporalConflictEvent
import cz.vutbr.fit.interlockSim.sim.events.BlockEventListener
import cz.hovorka.kdisco.SimulationEvent as KDiscoSimulationEvent
import cz.vutbr.fit.interlockSim.sim.events.BlockEvent as AnimBlockEvent

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
 *
 * **Routing Services:**
 * - [getRoutingServices] - Access grouped routing/navigation services
 *   ([RoutingServices.getTopologyNavigator],
 *   [RoutingServices.getPathReservationService],
 *   [RoutingServices.getTrainNavigationService])
 *
 * **Collision Services:**
 * - [getCollisionServices] - Access grouped collision-detection services
 *   ([cz.vutbr.fit.interlockSim.sim.collision.CollisionServices.getCollisionDetectionService],
 *   [cz.vutbr.fit.interlockSim.sim.collision.CollisionServices.onCollisionWarning])
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
@Suppress("TooManyFunctions", "ComplexInterface")
interface SimulationEnvironment : NetworkState {
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
	 * Get the grouped routing/navigation services for this simulation environment.
	 *
	 * Routing accessors (topology navigation, path reservation, train navigation) are
	 * grouped behind the [RoutingServices] sub-interface rather than being flattened
	 * directly onto this facade. Callers reach a specific service via this single accessor:
	 *
	 * ```kotlin
	 * val navigator = env.getRoutingServices().getTopologyNavigator()
	 * val pathService = env.getRoutingServices().getPathReservationService()
	 * val trainNav = env.getRoutingServices().getTrainNavigationService()
	 * ```
	 *
	 * @return RoutingServices instance for this simulation context
	 * @see RoutingServices
	 * @since Issue #651 (routing accessor segregation)
	 */
	fun getRoutingServices(): RoutingServices

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
			getRoutingServices().getTrainNavigationService().findReservedPathForTrain(trainId, separator) is PathResult.Available
		}

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

	/**
	 * Get the route finder for automatic route planning between InOut elements.
	 *
	 * The returned [RouteFinder] is scoped to this simulation context. In this slice
	 * it performs purely topological search; future slices will consult the supplied
	 * [NetworkState] for dynamic constraints.
	 *
	 * @return RouteFinder instance for this simulation context
	 * @see RouteFinder
	 * @see NetworkState
	 */
	fun getRouteFinder(): cz.vutbr.fit.interlockSim.context.RouteFinder

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
	 * val result = env.getRoutingServices().getPathReservationService().reservePath(trainId, start, target)
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
	 * Subscribe to block occupancy events.
	 *
	 * @param listener Listener to add
	 */
	fun addBlockEventListener(listener: BlockEventListener)

	/**
	 * Unsubscribe from block occupancy events.
	 *
	 * @param listener Listener to remove
	 */
	fun removeBlockEventListener(listener: BlockEventListener)

	/**
	 * Fire a block event.
	 *
	 * @param event The event to fire
	 */
	fun fireBlockEvent(event: AnimBlockEvent)

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
		getRoutingServices().getPathReservationService().addBlockOccupancyListener(listener)
	}

	/**
	 * Unsubscribe an external agent from block occupancy/release events.
	 *
	 * @param listener The listener to remove
	 */
	fun removeBlockOccupancyListener(listener: BlockOccupancyListener) {
		getRoutingServices().getPathReservationService().removeBlockOccupancyListener(listener)
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

	/**
	 * Subscribe to [ConflictDetectedEvent]s emitted when two trains spatially conflict over
	 * the same track block during path reservation.
	 *
	 * Events are emitted **immediately** at reservation time — before the conflicting
	 * reservation is accepted or committed — so the Goal 9 conflict-resolution layer can
	 * react without waiting for the end-of-run flush. Unlike
	 * [cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning.ReservationConflict],
	 * receiving this event does **not** trigger an automatic simulation pause.
	 *
	 * Listener is called synchronously on the simulation thread in simulation-time order.
	 * Listeners registered after [run] has started are silently ignored.
	 *
	 * @param listener Callback invoked for each detected spatial conflict.
	 * @since Issue #580 (Goal 9 SP1)
	 */
	fun onConflictDetectedEvent(listener: (ConflictDetectedEvent) -> Unit)

	/**
	 * Subscribe to [TemporalConflictEvent]s emitted when the lookahead scan predicts that
	 * two trains will occupy the same track block simultaneously within the configured
	 * lookahead window.
	 *
	 * Unlike [onConflictDetectedEvent] (Goal 9 SP1), which fires only when a reservation
	 * actually conflicts, [TemporalConflictEvent] is **predictive**: it fires before the
	 * trains reach the contested block, giving the conflict-resolution layer lead time to
	 * re-route or delay a train.
	 *
	 * Listener is called synchronously on the simulation thread.
	 * Listeners registered after [run] has started are silently ignored.
	 *
	 * @param listener Callback invoked for each detected temporal conflict.
	 * @since Issue #583 (Goal 9 SP2)
	 */
	fun onTemporalConflictEvent(listener: (TemporalConflictEvent) -> Unit)

	/**
	 * Get the [TemporalConflictDetector] scoped to this simulation context.
	 *
	 * Provides access to the detector so that callers can configure the lookahead window
	 * or register a custom projection provider before the simulation starts:
	 *
	 * ```kotlin
	 * env.getTemporalConflictDetector().lookaheadWindowSeconds = 60.0
	 * env.getTemporalConflictDetector().registerProjectionProvider(loop::getProjectedOccupancies)
	 * ```
	 *
	 * **Note on listener registration:** calling [TemporalConflictDetector.onTemporalConflictEvent]
	 * directly on the returned instance does **not** share the "silently ignored after [run]
	 * has started" contract documented on [onTemporalConflictEvent] above — that guard only
	 * applies to the [SimulationEnvironment]-level wrapper. Prefer [onTemporalConflictEvent]
	 * for listener subscriptions unless you specifically need this divergent behavior.
	 * [TemporalConflictDetector.registerProjectionProvider] is unaffected by this note: it is
	 * intentionally always callable, at any point in the simulation lifecycle.
	 *
	 * @return [TemporalConflictDetector] instance scoped to this context.
	 * @since Issue #583 (Goal 9 SP2)
	 */
	fun getTemporalConflictDetector(): TemporalConflictDetector

	// ========================================
	// Collision Detection (Issue #611)
	// ========================================

	/**
	 * Get the grouped collision-detection services for this simulation environment.
	 *
	 * Collision accessors (warning subscription, collision detection service) are grouped
	 * behind the [cz.vutbr.fit.interlockSim.sim.collision.CollisionServices] sub-interface
	 * rather than being flattened directly onto this facade, mirroring the
	 * [getRoutingServices] / [RoutingServices] segregation pattern. Callers reach a
	 * specific accessor via this single entry point:
	 *
	 * ```kotlin
	 * val service = env.getCollisionServices().getCollisionDetectionService()
	 * env.getCollisionServices().onCollisionWarning { warning -> /* ... */ }
	 * ```
	 *
	 * @return [cz.vutbr.fit.interlockSim.sim.collision.CollisionServices] instance for this context
	 * @see cz.vutbr.fit.interlockSim.sim.collision.CollisionServices
	 * @since Issue #611 (Goal 3 SP1)
	 */
	fun getCollisionServices(): cz.vutbr.fit.interlockSim.sim.collision.CollisionServices
}
