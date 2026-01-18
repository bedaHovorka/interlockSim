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

/**
 * "Oddelovac oddilu (i automaticky řízené prvky) uzel cesty z pohledu vlaku"
 */
interface PathSeparator :
	PathElement,
	Cell {

	/**
	 * @param from
	 * @return following segments - static
	 */
	fun possibleFollowers(from: Cell.Segment): Set<Cell.Segment>
}
