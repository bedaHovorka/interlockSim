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
import java.util.ArrayDeque
import java.util.Deque
import java.util.Iterator

/**
 * ArrayDeque delegated implemetation of {@link Path}
 * There is nothing special to see, please try {@link AbstractPath}
 */
@Suppress("ABSTRACT_MEMBER_NOT_IMPLEMENTED", "BASE_WITH_NULLABLE_UPPER_BOUND")
class ArrayPath(
	context: SimulationContext
) : AbstractPath(context) {
	private val deque: Deque<PathElement> = ArrayDeque<PathElement>()

	override fun add(e: PathElement): Boolean = deque.add(e)

	override fun addFirst(e: PathElement) {
		deque.addFirst(e)
	}

	override fun addLast(e: PathElement) {
		deque.addLast(e)
	}

	override fun clear() {
		deque.clear()
	}

	override fun contains(o: PathElement): Boolean = deque.contains(o)

	@Suppress("RETURN_TYPE_MISMATCH_ON_OVERRIDE")
	override fun descendingIterator(): Iterator<PathElement> = deque.descendingIterator() as Iterator<PathElement>

	override fun element(): PathElement = deque.element()

	override fun getFirst(): PathSeparator = Util.assertInstanceOf(PathSeparator::class.java, deque.first)

	override fun getLast(): OrientedPathSeparator = Util.assertInstanceOf(OrientedPathSeparator::class.java, deque.last)

	override fun isEmpty(): Boolean = deque.isEmpty()

	@Suppress("RETURN_TYPE_MISMATCH_ON_OVERRIDE")
	override fun iterator(): Iterator<PathElement> = deque.iterator() as Iterator<PathElement>

	override fun offer(e: PathElement): Boolean = deque.offer(e)

	override fun offerFirst(e: PathElement): Boolean = deque.offerFirst(e)

	override fun offerLast(e: PathElement): Boolean = deque.offerLast(e)

	override fun peek(): PathElement? = deque.peek()

	override fun peekFirst(): PathElement? = deque.peekFirst()

	override fun peekLast(): PathElement? = deque.peekLast()

	override fun poll(): PathElement? = deque.poll()

	override fun pollFirst(): PathElement? = deque.pollFirst()

	override fun pollLast(): PathElement? = deque.pollLast()

	override fun pop(): PathElement = deque.pop()

	override fun push(e: PathElement) {
		deque.push(e)
	}

	override fun remove(): PathElement = deque.remove()

	override fun remove(o: PathElement): Boolean = deque.remove(o)

	override fun removeFirst(): PathElement = deque.removeFirst()

	override fun removeFirstOccurrence(o: Any?): Boolean = deque.removeFirstOccurrence(o)

	override fun removeLast(): PathElement = deque.removeLast()

	override fun removeLastOccurrence(o: Any?): Boolean = deque.removeLastOccurrence(o)

	override val size: Int
		get() = deque.size

	override fun toString(): String = deque.toString()
}
