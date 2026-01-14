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

	// Reverse iteration support
	override fun descendingIterator(): Iterator<PathElement> = deque.asReversed().iterator()

	override fun toString(): String = deque.toString()
}
