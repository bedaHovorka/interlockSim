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

import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.paths.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrack
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import java.util.EnumSet

/**
 * Interface to shared functions of inner data model, which is allowed by simulation
 *
 * ## Architecture
 *
 * SimulationContext extends [Context]<[Cell]> for architectural separation, following
 * the Interface Segregation Principle. The network structure is immutable once simulation
 * starts - editing operations are NOT supported during simulation.
 *
 * DefaultSimulationContext extends BaseContext directly and does NOT implement EditingContext.
 * Editing operations (putCell, removeCell, etc.) are only available through [EditingContext].
 *
 * Dynamic wrappers ([DynamicInOut], [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch],
 * [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore]) are created during
 * transformation from editing context. These wrappers maintain references to static
 * objects via `staticRef` property for identity preservation.
 *
 * The simulation context is created from an editing context using `GridTransformer` to convert
 * static NodeCell instances to their dynamic wrapper counterparts.
 *
 * ## Immutability Contract
 *
 * **Network structure is immutable after initialization.**
 *
 * Once a SimulationContext is created (via factory method or initialization in run()),
 * the railway network structure is frozen:
 * - No cells can be added or removed
 * - No track blocks can be created or destroyed
 * - No cells can be moved
 * - No network topology changes allowed
 *
 * This immutability is enforced at runtime:
 * - [BaseContext.freeze] is called after network initialization
 * - Attempts to modify frozen context throw [UnsupportedOperationException]
 * - Clear error messages guide users to use [EditingContext] for modifications
 *
 * ### Rationale
 *
 * Immutability ensures simulation correctness:
 * - Pre-computed paths remain valid throughout simulation
 * - Dynamic wrapper mappings stay consistent
 * - Simulation physics assumptions hold (fixed track lengths, etc.)
 * - No undefined behavior from runtime topology changes
 *
 * ### Usage
 *
 * - Build networks with [EditingContext] (putCell, joinCells, etc.)
 * - Convert to [SimulationContext] via factory (network freezes at this point)
 * - Run simulation with immutable network structure
 * - To modify network: create new EditingContext, make changes, convert to new SimulationContext
 *
 * ## Thread Safety
 *
 * **This interface is NOT thread-safe.** See [Context] for detailed thread safety
 * documentation and usage guidelines.
 *
 * All simulation operations must be performed within the jDisco simulation thread.
 * The jDisco discrete event simulation framework is single-threaded by design,
 * ensuring sequential execution of all simulation events.
 *
 * @see Context
 * @see EditingContext
 * @see DynamicPathSeparator
 * @see BaseContext.freeze
 * @see javax.annotation.concurrent.NotThreadSafe
 */
interface SimulationContext : Context<Cell> {
	/**
	 * simulation reporting types
	 */
	enum class ReportType {
		// higest to lowest priority
		/**
		 * control commands
		 */
		PATH_SETTING,

		/**
		 * inout, switch ...
		 */
		NODE_EVENTS,

		/**
		 * train discrete events: stop on signal, exiting system ...
		 */
		TRAIN_EVENTS,

		/**
		 * train position, velocity, acceleration
		 */
		TRAIN_CONTINUOUS,

		/**
		 * not standard
		 */
		_DEBUG;

		companion object {
			/**
			 * all standard reports (without debug)
			 */
			@JvmField
			val ALL: Array<ReportType> = EnumSet.complementOf(EnumSet.of(_DEBUG)).toArray(arrayOfNulls<ReportType>(0))
		}
	}

	/**
	 * Object come from <code>current</code> to <code>separator</code> and need know, how continue
	 * @param separator
	 * @param current
	 * @return next section in aPath
	 */
	fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection?

	/**
	 * Runs the simulation
	 * @throws EmptyContextException Context must not be empty
	 * @throws SimulationException if simulation failed
	 */
	fun run()

	/**
	 * stops the simulation with error
	 * @param error
	 */
	fun errorStop(error: Throwable)

	/**
	 * stops the simulation
	 * @throws SimulationException if the simulation didn't stop correctly
	 *
	 */
	fun stop()

	/**
	 *
	 * @param separator start of path
	 * @param next first section
	 * @return a path with all elements, or null if no path found
	 */
	fun pathToNextSemaphore(
		separator: PathSeparator,
		next: TrackSection
	): Path?

	/**
	 * Reporting - if report type is allowed
	 * @param report
	 * @param obj this object report message
	 * @param type
	 */
	fun report(
		report: CharSequence,
		obj: Any,
		type: ReportType
	)

	/**
	 *
	 * @param types
	 */
	fun addReportTypes(vararg types: ReportType)

	/**
	 * @param type
	 * @return true if is the type now reporting
	 */
	fun isReporting(type: ReportType): Boolean

	/**
	 *
	 * @param types
	 */
	fun removeReportTypes(vararg types: ReportType)

	/**
	 * @param nodeCell
	 * @param current from, for determine direction
	 * @return block, which follow depending on node configuration, or null if no following block exists
	 */
	fun getNextTrackBlock(
		nodeCell: NodeCell,
		current: TrackBlock?
	): TrackBlock?

	/**
	 * @param separator
	 * @param track
	 * @return topology join
	 */
	fun getSegment(
		separator: DynamicPathSeparator,
		track: Track
	): Segment?

	/**
	 * @param separator
	 * @param next track behind separator
	 * @param previous track front of separator (if next null previous must not be null)
	 * @return if separator direct to next!
	 */
	fun isSeparatorInDirection(
		separator: OrientedPathSeparator,
		next: Track?,
		previous: Track?
	): Boolean

	/**
	 * @param separator
	 * @param track
	 * @param secondEndTrack only if track is null is used (must be not null)
	 * @return segment for track, or null if no following segment exists
	 */
	fun getSegment(
		separator: DynamicPathSeparator,
		track: Track?,
		secondEndTrack: Track?
	): Segment?

	/**
	 * @param inOut
	 * @return worker
	 */
	fun getWorkerFor(inOut: DynamicInOut): InOutWorker

	/**
	 * @return all inouts in model
	 */
	fun getInOuts(): Collection<DynamicInOut>

	/**
	 * Convert a static PathSeparator to its Dynamic wrapper.
	 * Used by Train to ensure consistent use of Dynamic wrappers throughout simulation.
	 * @param separator The separator to convert (static or already Dynamic)
	 * @return The Dynamic wrapper (either found in map or the input if already dynamic)
	 * @throws IllegalStateException if the separator is static and not found in the dynamic map
	 */
	fun toDynamic(separator: PathSeparator): DynamicPathSeparator

	/**
	 * Convert a TrackFacility to its DynamicTrack wrapper.
	 * Used by Train to manage track state (enter/leave) via DynamicTrack wrappers.
	 * @param track The track facility to wrap
	 * @return The DynamicTrack wrapper for state operations
	 */
	fun toDynamic(track: TrackFacility): DynamicTrack
}
