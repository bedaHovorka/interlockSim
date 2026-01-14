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

/**
 * Represents a Path - a sequence of [PathElement] ([PathSeparator] and [Track]).
 *
 * A Path combines track-like behavior (length, speed limits) with ordered collection
 * semantics for managing path elements. In production usage, paths should alternate
 * between PathSeparators and Tracks, starting with a PathSeparator and ending with
 * an OrientedPathSeparator.
 *
 * This interface provides a minimal set of operations for path construction and traversal,
 * designed to be multiplatform-compatible using Kotlin collections.
 */
interface Path :
	Track,
	MutableCollection<PathElement> {
	/**
	 * Returns the last semaphore in this path.
	 * The last element must be a [RailSemaphore] or [InOut] (which contains an output semaphore).
	 */
	fun getLastPathSemaphore(): RailSemaphore

	/**
	 * Returns the minimal speed limit across all tracks in this path sequence.
	 * Considers the speed limits of all tracks when approached from the given separator.
	 */
	override fun maxSpeed(sep: PathSeparator?): Double

	/**
	 * Creates a reversed copy of this path with elements in opposite order.
	 */
	fun reversePath(): Path

	/**
	 * Returns the first element in this path, which must be a [PathSeparator].
	 */
	fun getFirst(): PathSeparator

	/**
	 * Returns the last element in this path, which must be an [OrientedPathSeparator].
	 */
	fun getLast(): OrientedPathSeparator

	/**
	 * Tests element-wise equality with another path.
	 *
	 * Unlike standard collection equality, this compares the actual path elements
	 * in sequence order, which is the appropriate equality test for paths.
	 *
	 * @param path the path to compare with
	 * @return true if both paths contain the same elements in the same order
	 */
	fun equalsWithElements(path: Path): Boolean

	// Path construction operations (multiplatform-compatible)

	/**
	 * Adds an element at the beginning of this path.
	 */
	fun addFirst(element: PathElement)

	/**
	 * Adds an element at the end of this path.
	 */
	fun addLast(element: PathElement)

	/**
	 * Removes and returns the first element from this path.
	 * @throws NoSuchElementException if the path is empty
	 */
	fun removeFirst(): PathElement

	/**
	 * Returns an iterator over the elements in this path in reverse order.
	 */
	fun descendingIterator(): Iterator<PathElement>

	/**
	 * Returns a mutable iterator over the elements in this path.
	 * Overridden to specify mutable iterator for MutableCollection contract.
	 */
	override fun iterator(): MutableIterator<PathElement>
}
