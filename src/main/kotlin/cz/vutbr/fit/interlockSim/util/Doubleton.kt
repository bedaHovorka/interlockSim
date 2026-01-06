/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import java.util.AbstractSet
import java.util.NoSuchElementException

/**
 * Pair of nodes for implementation ADT ExtendedUnorientedGraph
 * Represents combination of two elements
 * @param <T> type of elements
 * @param <V> type of additional information
 *
 * @deprecated should be replaced in kotlin with library thing like Pair
 */
@Deprecated(
	message = "Should be replaced in kotlin with library thing like Pair",
	replaceWith = ReplaceWith("Pair<T, V>")
)
class Doubleton<T, V> : AbstractSet<T> {
	enum class IteratorState {
		INIT,
		FIRST,
		SECOND
	}

	private inner class DoubletonIterator : MutableIterator<T> {
		private var state = IteratorState.INIT

		override fun hasNext(): Boolean {
			assert(state != null)
			return state != IteratorState.SECOND
		}

		override fun next(): T {
			assert(state != null)
			return when (state) {
				IteratorState.INIT -> {
					state = IteratorState.FIRST
					first
				}
				IteratorState.FIRST -> {
					state = IteratorState.SECOND
					second
				}
				IteratorState.SECOND -> throw NoSuchElementException()
			}
		}

		override fun remove(): Unit = throw UnsupportedOperationException()
	}

	private val first: T
	private val second: T
	private var firstValue: V? = null
	private var secondValue: V? = null

	/**
	 * Create with additional information
	 * @param first
	 * @param second
	 * @param firstValue
	 * @param secondValue
	 */
	constructor(first: T, second: T, firstValue: V, secondValue: V) : this(first, second) {
		this.firstValue = firstValue
		this.secondValue = secondValue
	}

	/**
	 * Create without additional information
	 * @param first
	 * @param second
	 */
	constructor(first: T, second: T) {
		if (first == second || (first != null && first == second)) throw IllegalArgumentException("arguments is equal")
		this.first = first
		this.second = second
	}

	override fun hashCode(): Int {
		var result = if (first == null) 0 else first.hashCode()
		result += if (second == null) 0 else second.hashCode() // kongruentni komutativita?
		return result
	}

	/**
	 * Compares this Doubleton with another object for equality.
	 * <p>
	 * This method explicitly delegates to {@link AbstractSet#equals(Object)},
	 * which compares sets by their content (order-independent). Two Doubletons
	 * are equal if they contain the same two elements, regardless of order.
	 * The firstValue and secondValue fields are not considered for equality.
	 * </p>
	 * <p>
	 * This explicit override satisfies the Java contract that requires overriding
	 * equals() when hashCode() is overridden, and documents the intended behavior.
	 * </p>
	 *
	 * @param obj the object to compare with
	 * @return true if obj is a Doubleton with the same elements
	 */
	override fun equals(obj: Any?): Boolean {
		// Delegate to AbstractSet's content-based equality implementation
		// which correctly handles order-independent comparison
		return super.equals(obj)
	}

	override fun iterator(): MutableIterator<T> = DoubletonIterator()

	override val size: Int = 2

	override fun isEmpty(): Boolean = false

	/**
	 * @param key
	 * @return value assigned to key
	 */
	fun getValue(key: T): V? {
		val secondEq = nullEq(key, second)
		if (nullEq(key, first)) {
			assert(!secondEq)
			return firstValue
		}
		if (secondEq) return secondValue
		throw IllegalArgumentException("Key $key is not contain in set")
	}

	private fun nullEq(
		a: Any?,
		b: Any?
	): Boolean {
		if (a == null) return b == null
		return a == b
	}

	/**
	 * @param first
	 * @param second
	 */
	fun setValues(
		first: V,
		second: V
	) {
		this.firstValue = first
		this.secondValue = second
	}
}
