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

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.paths.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrack
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.objects.tracks.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.InOutWorker

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
 * - [getNextTrackSection] - Navigate track topology
 * - [pathToNextSemaphore] - Find path to next signal
 * - [isSeparatorInDirection] - Check signal orientation
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
 * When migrating from jDisco to DSOL:
 * 1. Create DSOLSimulationEnvironment implementing this interface
 * 2. Update [SimulationProcessFactory] to accept SimulationEnvironment
 * 3. Simulation classes (Train, etc.) work unchanged
 *
 * @see SimulationContext
 * @see DefaultSimulationContext
 * @see SimulationProcessFactory
 * @since 2026-01 (Issue #94)
 */
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
	 * Navigate to the next track section from a path separator.
	 *
	 * Object comes from `current` to `separator` and needs to know how to continue.
	 *
	 * @param separator Starting point (semaphore, switch, InOut)
	 * @param current Current track section (null = start of navigation)
	 * @return Next track section, or null if no path exists
	 */
	fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection?

	/**
	 * Find path from separator to next semaphore.
	 * Used for train navigation and path reservation.
	 *
	 * @param separator Start of path (must be in direction of travel)
	 * @param next First track section in path
	 * @return Path to next semaphore, or null if no path found
	 */
	fun pathToNextSemaphore(
		separator: PathSeparator,
		next: TrackSection
	): Path?

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
	 * Stop simulation due to error.
	 * @param error The error that caused termination
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
}
