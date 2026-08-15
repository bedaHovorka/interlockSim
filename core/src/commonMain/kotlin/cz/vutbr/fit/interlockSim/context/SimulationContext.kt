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
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Interface to shared functions of inner data model, which is allowed by simulation
 *
 * ## Architecture
 *
 * SimulationContext extends `Context<Cell, DynamicTrackBlock>` for architectural separation,
 * following the Interface Segregation Principle. The network structure is immutable once
 * simulation starts - editing operations are NOT supported during simulation.
 *
 * DefaultSimulationContext extends `BaseContext<DynamicTrackBlock>` directly and does NOT
 * implement EditingContext. Editing operations (putCell, removeCell, etc.) are only available
 * through [EditingContext].
 *
 * Dynamic wrappers ([DynamicInOut], [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch],
 * [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore], [DynamicTrackBlock]) are
 * created during transformation from editing context. These wrappers maintain references to
 * static objects via `staticRef` property for identity preservation.
 *
 * The simulation context is created from an editing context using transformation methods to
 * convert static objects to their dynamic wrapper counterparts.
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
 * All simulation operations must be performed within the kDisco simulation thread.
 * The kDisco discrete event simulation framework is single-threaded by design,
 * ensuring sequential execution of all simulation events.
 *
 * @see Context
 * @see EditingContext
 * @see DynamicPathSeparator
 * @see BaseContext.freeze
 * @see javax.annotation.concurrent.NotThreadSafe
 */
interface SimulationContext :
	Context<Cell, DynamicTrackBlock>,
	SimulationEnvironment {
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
		 * train path approvals (reservation granted to enter network)
		 */
		TRAIN_APPROVED,

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
		@Suppress("ktlint:standard:enum-entry-name-case")
		_DEBUG;

		companion object {
			/**
			 * all standard reports (without debug)
			 */
			val ALL: Array<ReportType> = entries.filter { it != _DEBUG }.toTypedArray()
		}
	}

	/**
	 * Runs the simulation.
	 *
	 * @param controller the [SimulationController] governing pause, step, and throttle
	 *        behaviour. Defaults to [NoOpSimulationController] (no pause, no throttle).
	 * @throws EmptyContextException Context must not be empty
	 * @throws SimulationException if simulation failed
	 */
	fun run(controller: SimulationController = NoOpSimulationController)

	/**
	 * The kDisco kernel clock value ([cz.ksimulantenbande.kdisco.Simulation.time]) at the moment
	 * the most recently completed [run] returned, or `null` if [run] has never been called.
	 *
	 * This is the kernel's own notion of "how far simulated time actually advanced," independent
	 * of the report-event stream. It exists because the report-event-derived time signal (e.g.
	 * `SimulationTimeTracker.lastSimTime` in desktop-ui) can freeze below the requested end time
	 * even when the kernel itself reached it: queued-but-never-admitted trains are event-less by
	 * design, so once the last moving train finishes, no further TRAIN/NODE report events fire,
	 * even though the kernel clock keeps advancing silently.
	 *
	 * Callers that classify whether a run actually completed (vs. terminated early) should combine
	 * this value with the report-event-derived time signal, taking the maximum of the two, rather
	 * than relying on the report-event stream alone.
	 *
	 * @since Issue #929
	 */
	val lastRunEndTime: Double?

	/**
	 * Remove report types from logging.
	 * @param types Report types to disable
	 */
	fun removeReportTypes(vararg types: ReportType)

	/**
	 * Get the next track block following the given node cell.
	 *
	 * @param nodeCell The node cell to query
	 * @param current The current track block (for determining direction), or null
	 * @return The next DynamicTrackBlock, or null if no following block exists
	 */
	fun getNextTrackBlock(
		nodeCell: NodeCell,
		current: DynamicTrackBlock?
	): DynamicTrackBlock?

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
	 * @param track
	 * @param secondEndTrack only if track is null is used (must be not null)
	 * @return segment for track, or null if no following segment exists
	 */
	fun getSegment(
		separator: DynamicPathSeparator,
		track: Track?,
		secondEndTrack: Track?
	): Segment?
}
