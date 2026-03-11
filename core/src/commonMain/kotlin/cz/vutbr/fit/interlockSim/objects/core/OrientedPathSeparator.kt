/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.core

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

	/**
	 * Returns the semaphore for this path separator.
	 * Eliminates instanceof checks in AbstractPath.getLastPathSemaphore().
	 *
	 * For RailSemaphore: returns self
	 * For InOut: returns the output semaphore
	 * For Dynamic wrappers: delegates to static reference
	 *
	 * @return the RailSemaphore instance associated with this separator
	 */
	fun asRailSemaphore(): cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
}
