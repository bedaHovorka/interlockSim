/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks;

import cz.vutbr.fit.interlockSim.objects.paths.PathElement;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackFacility.State;
import cz.vutbr.fit.interlockSim.sim.TrackOperationException;

/**
 * "jakykoliv (z ruznych pohledu) usek koleji mezi pathseparatory (cast cesty)"
 * "muze se tvorit rekurzivne"
 */
public interface Track extends PathElement {
	/**
	 * model bound constants
	 */
	public static final double MIN_LENGTH = 5;//m
	/**
	 * model bound constants
	 */
	public static final double COMMON_TRACK_LENGTH = 100;//m

	/**
	 * @param sep must be from ends
	 * @return Is able to enter (set way) from direction
	 * @throws TrackOperationException
	 */
	public boolean isFreeFrom(PathSeparator sep) throws TrackOperationException;

	/**
	 * Reserve this track
	 * all or some (it depends on type) of {@link TrackFacility} in Track change state
	 * from {@link State#FREE} to {@link State#RESERVED}
	 * @param from must be from ends
	 * @throws TrackOperationException
	 */
	public void setUpPath(PathSeparator from) throws TrackOperationException; //direction, null cancel

	/**
	 * @param from from
	 * @return if way setted up
	 * @throws TrackOperationException
	 */
	public boolean isSetUpPath(PathSeparator from) throws TrackOperationException;

	/**
	 * Frees this track
	 * all or some (it depends on type) of {@link TrackFacility}s in Track change state
	 * from {@link State#RESERVED} to {@link State#FREE}
	 * @param from end of track
	 * @throws TrackOperationException
	 */
	public void cancelPathSetup(PathSeparator from) throws TrackOperationException;

	/**
	 * @param sep first end
	 * @return second end
	 */
	public PathSeparator getSecondEnd(PathSeparator sep);

	/**
	 * @return length of track in meters
	 */
	public double length();

	/**
	 * @param from
	 * @return allowed speed from one end
	 */
	public double maxSpeed(PathSeparator from);

	/**
	 * @return both separators on ends of track
	 */
	public PathSeparator[] ends();
}
