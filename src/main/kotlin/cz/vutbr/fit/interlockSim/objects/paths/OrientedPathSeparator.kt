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
 * orientovany prvek
 * "ma nejaky vyznam a ten plati jen v jednom smeru jizdy"
 * muze byt zacatkem a koncem cesty
 */
interface OrientedPathSeparator : PathSeparator {
	/**
	 * @return orientation atribute
	 */
	fun getOrientation(): Boolean

	/**
	 * @return direction segment
	 */
	fun direction(): Cell.Segment
}
