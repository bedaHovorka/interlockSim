/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.paths

import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException

/**
 * "Oddelovac oddilu (i automaticky řízené prvky) uzel cesty z pohledu vlaku"
 */
interface PathSeparator :
	PathElement,
	Cell {
	/**
	 * @param from
	 * @param to
	 * @throws PathSeparatorChangeException
	 */
	fun cancelPathSetup(
		from: Cell.Segment?,
		to: Cell.Segment?
	)

	/**
	 * @param from
	 * @param to
	 * @param allowedSpeed
	 * @throws PathSeparatorChangeException
	 */
	fun setUpPath(
		from: Cell.Segment?,
		to: Cell.Segment?,
		allowedSpeed: Double
	)

	/**
	 * @param from
	 * @return segment reprezents element configuration (dynamic)
	 */
	fun getFollowingSegment(from: Cell.Segment?): Cell.Segment?

	/**
	 * @param from
	 * @return following segments - static
	 */
	fun possibleFollowers(from: Cell.Segment): Set<Cell.Segment>

	/**
	 * @return allowed speed through separator in m/s
	 */
	fun allowedSpeed(): Double
}
