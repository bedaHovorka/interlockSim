/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator

/**
 * object in Track
 */
interface TrackOccupant {
	// EXTENSION position (obsazuje celou delku- nebo jen od urciteho konce a smer)...
	// pozor na vymenu koncu vlaku, obsazuje vice segmentu

	/**
	 * @return length of path to next Semaphore
	 */
	fun distanceToSemaphore(): Double

	/**
	 * @return next Semaphore in path
	 */
	fun nextSemaphore(): OrientedPathSeparator?
}
