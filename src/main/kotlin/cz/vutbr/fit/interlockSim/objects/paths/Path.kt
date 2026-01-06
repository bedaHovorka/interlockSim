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

import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import java.util.Deque

/**
 * Represents aPath
 * is a sequence of {@link PathElement} - {@link PathSeparator} and {@link Track}
 *
 */
interface Path :
	Deque<PathElement>,
	Track {
	/**
	 * @return last element must be semaphore
	 */
	fun getLastPathSemaphore(): RailSemaphore

	/**
	 * @return minimal value of {@link Track#maxSpeed(PathSeparator)} in sequence
	 */
	override fun maxSpeed(sep: PathSeparator?): Double

	/**
	 * @return copy of this aPath in reverse
	 */
	fun reversePath(): Path

	override fun getFirst(): PathSeparator

	override fun getLast(): OrientedPathSeparator

	/**
	 * Same as list equals, because {@link Deque#equals(Object)} should test identity
	 * @param path
	 * @return if path have same elements in same order
	 */
	fun equalsWithElements(path: Path): Boolean
// 	 z konvence pro deque neprekryvam equals a hashcode, cesta je "mutable" a to by delalo problemy v kolekcich
}
