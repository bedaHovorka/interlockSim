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

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.util.Util

/**
 * [Path] implementation backed by Kotlin's multiplatform [ArrayDeque].
 *
 * This implementation uses [kotlin.collections.ArrayDeque] for storage, providing:
 * - Efficient O(1) operations at both ends
 * - Multiplatform compatibility (JVM, JS, Native)
 * - Memory-efficient resizable circular buffer
 *
 * @param context the simulation context for this path
 */
class ArrayPath(
	context: SimulationContext
) : AbstractPath(context) {
	private val deque = ArrayDeque<PathElement>()

	// MutableCollection contract - delegate to underlying ArrayDeque
	override fun add(element: PathElement): Boolean = deque.add(element)

	override val size: Int get() = deque.size

	override fun clear() = deque.clear()

	override fun isEmpty(): Boolean = deque.isEmpty()

	override fun contains(element: PathElement): Boolean = deque.contains(element)

	override fun containsAll(elements: Collection<PathElement>): Boolean = deque.containsAll(elements)

	override fun iterator(): MutableIterator<PathElement> = deque.iterator()

	override fun remove(element: PathElement): Boolean = deque.remove(element)

	override fun removeAll(elements: Collection<PathElement>): Boolean = deque.removeAll(elements.toSet())

	override fun retainAll(elements: Collection<PathElement>): Boolean = deque.retainAll(elements.toSet())

	override fun addAll(elements: Collection<PathElement>): Boolean = deque.addAll(elements)

	// Path construction operations - delegate to ArrayDeque's efficient implementations
	override fun addFirst(element: PathElement) = deque.addFirst(element)

	override fun addLast(element: PathElement) = deque.addLast(element)

	override fun removeFirst(): PathElement = deque.removeFirst()

	// Path-specific typed accessors with runtime type validation
	override fun getFirst(): PathSeparator = Util.assertInstanceOf(PathSeparator::class.java, deque.first())

	override fun getLast(): OrientedPathSeparator = Util.assertInstanceOf(OrientedPathSeparator::class.java, deque.last())

	override fun getNext(current: TrackSection?): TrackSection? {
// 		var trackBlock: DynamicTrackBlock? = null
// 		if (current != null) {
// 			val block = current.getTrackBlock()
// 			requireSimulation(block != null) { "TrackBlock cannot be null for current track section" }
// 			val nextTrackSection = block.getNextTrackSection(separator, current)
// 			if (nextTrackSection != null) {
// 				DefaultSimulationContext.Companion.logger.trace {
// 					"getNextTrackSection: found next section within same block from $separator"
// 				}
// 				return nextTrackSection
// 			}
// 			// Look up DynamicTrackBlock wrapper from graph for use in getNextTrackBlock call below
// 			trackBlock = block as? DynamicTrackBlock ?: getDynamicWrapper(block)
// 		}
//
// 		// z dalsi TrackBlock
// 		// Extract static NodeCell for getNextTrackBlock (which needs NodeCell interface)
// 		// but preserve the separator (which could be Dynamic*) for proper type checking
// 		val staticNodeCell = CellUtilities.assertNodeCell(separator)
//
// 		// Pass separator as NodeCell to getNextTrackBlock
// 		// (NodeCell is a subtype of PathSeparator, so static objects work directly;
// 		// Dynamic* also implement PathSeparator, so they work too)
// 		val nodeCell = if (separator is NodeCell) separator else staticNodeCell
// 		val nextTrackBlock = getNextTrackBlock(nodeCell, trackBlock)
//
// 		@Suppress("UNCHECKED_CAST")
// 		val result = nextTrackBlock?.getNextTrackSection(separator, null)
// 		DefaultSimulationContext.Companion.logger.trace {
// 			"getNextTrackSection: navigating network from $separator, result: ${if (result != null) "found" else "not found"}"
// 		}
// 		return result

		if (current == null) {
			// Paths always start with a PathSeparator, then a TrackSection
			// When current is null, we want the first TrackSection (element at index 1)
			// not the first element (index 0, which is always a separator)
			if (deque.size < 2) return null
			val second = deque[1]
			return if (second is TrackSection) second else null
		}

		val index = deque.indexOf(current)
		if (index == -1) return null

		// In this codebase, paths are stored as [PathSeparator, TrackSection, PathSeparator, TrackSection, ...]
		// After a TrackSection there is always a PathSeparator, then the next TrackSection.
		// So the "next" TrackSection is at index + 2, not index + 1.
		val separatorIndex = index + 1
		val nextIndex = index + 2

		if (nextIndex >= deque.size) {
			// No further elements (we're at the end of the path)
			return null
		}

		val separator = deque[separatorIndex]
		val candidate = deque[nextIndex]

		return if (separator is PathSeparator && candidate is TrackSection) {
			candidate
		} else {
			// Path structure not as expected; fail gracefully
			null
		}
	}

	// Reverse iteration support
	override fun descendingIterator(): Iterator<PathElement> = deque.asReversed().iterator()

	override fun toString(): String = deque.toString()
}
