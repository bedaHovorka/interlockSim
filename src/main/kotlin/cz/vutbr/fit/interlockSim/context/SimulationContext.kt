/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

import java.util.Collection;
import java.util.EnumSet;

import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator;
import cz.vutbr.fit.interlockSim.objects.paths.Path;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;
import cz.vutbr.fit.interlockSim.objects.tracks.Track;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection;
import cz.vutbr.fit.interlockSim.sim.InOutWorker;
import cz.vutbr.fit.interlockSim.sim.SimulationException;

/**
 * Interface to shared functions of inner data model, which is allowed by simulation
 *
 */
public interface SimulationContext extends Context {

	/**
	 * simulation reporting types
	 */
	public enum ReportType { //higest to lowest priority
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

		/**
		 * all standard reports (without debug)
		 */
		public static final ReportType[] ALL = EnumSet.complementOf(EnumSet.of(_DEBUG)).toArray(new ReportType[0]);
	}

	/**
	 * Object come from <code>current</code> to <code>separator</code> and need know, how continue
	 * @param separator
	 * @param current
	 * @return next section in aPath
	 */
	public TrackSection getNextTrackSection(PathSeparator separator, TrackSection current);

	/**
	 * Runs the simulation
	 * @throws EmptyContextException Context must not be empty
	 * @throws SimulationException if simulation failed
	 */
	public void run() throws EmptyContextException, SimulationException;

	/**
	 * stops the simulation with error
	 * @param error
	 */
	public void errorStop(Throwable error);

	/**
	 * stops the simulation
	 * @throws SimulationException if the simulation didn't stop correctly
	 *
	 */
	public void stop();

	/**
	 *
	 * @param separator start of aPath
	 * @param next first section
	 * @return aPath with all elements
	 */
	public Path pathToNextSemaphore(PathSeparator separator, TrackSection next);

	/**
	 * Reporting - if report type is allowed
	 * @param report
	 * @param obj this object report message
	 * @param type
	 */
	public void report(CharSequence report, Object obj, ReportType type);

	/**
	 *
	 * @param types
	 */
	public void addReportTypes(ReportType... types);

	/**
	 * @param type
	 * @return true if is the type now reporting
	 */
	public boolean isReporting(ReportType type);

	/**
	 *
	 * @param types
	 */
	public void removeReportTypes(ReportType... types);

	/**
	 * @param nodeCell
	 * @param current from, for determine direction
	 * @return block, which follow dependly on node configuration
	 */
	public TrackBlock getNextTrackBlock(NodeCell nodeCell, TrackBlock current);

	/**
	 * @param separator
	 * @param track
	 * @return topology join
	 */
	public Segment getSegment(PathSeparator separator, Track track);

	/**
	 * @param separator
	 * @param next track behind separator
	 * @param previous track front of separator (if next null previous must not be null)
	 * @return if separator direct to next!
	 */
	public boolean isSeparatorInDirection(OrientedPathSeparator separator, Track next, Track previous);

	/**
	 * @param separator
	 * @param track
	 * @param secondEndTrack only if track is null is used (must be not null)
	 * @return segment for track
	 */
	public Segment getSegment(PathSeparator separator, Track track, Track secondEndTrack);

	/**
	 * @param inOut
	 * @return worker
	 */
	public InOutWorker getWorkerFor(InOut inOut);

	/**
	 * @return all inouts in model
	 */
	public Collection<InOut> getInOuts();
}
