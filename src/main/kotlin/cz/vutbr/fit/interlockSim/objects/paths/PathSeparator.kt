/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.paths;

import java.util.Set;

import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.sim.PathSeparatorChangeException;

/**
 * "Oddelovac oddilu (i automaticky řízené prvky) uzel cesty z pohledu vlaku"
 */
public interface PathSeparator extends PathElement, Cell {
	/**
	 * @param from
	 * @param to
	 * @throws PathSeparatorChangeException
	 */
	public void cancelPathSetup(Segment from, Segment to) throws PathSeparatorChangeException;

	/**
	 * @param from
	 * @param to
	 * @param allowedSpeed
	 * @throws PathSeparatorChangeException
	 */
	public void setUpPath(Segment from, Segment to, double allowedSpeed) throws PathSeparatorChangeException;

	/**
	 * @param from
	 * @return segment reprezents element configuration (dynamic)
	 */
	public Segment getFollowingSegment(Segment from);

	/**
	 * @param from
	 * @return following segments - static
	 */
	public Set<Segment> possibleFollowers(Segment from);

	/**
	 * @return allowed speed through separator in m/s
	 */
	public double allowedSpeed();
}
