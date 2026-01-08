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

import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.sim.SimulationException
import java.util.Collection
import java.util.EnumSet

/**
 * Interface to shared functions of inner data model, which is allowed by simulation
 *
 */
interface SimulationContext : Context {
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
		separator: PathSeparator,
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
		separator: PathSeparator,
		track: Track?,
		secondEndTrack: Track?
	): Segment?

	/**
	 * @param inOut
	 * @return worker
	 */
	fun getWorkerFor(inOut: InOut): InOutWorker

	/**
	 * @return all inouts in model
	 */
	fun getInOuts(): Collection<InOut>
}
