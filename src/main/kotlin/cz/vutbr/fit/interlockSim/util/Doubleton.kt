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

import cz.vutbr.fit.interlockSim.exceptions.requireValidState

/**
 * Unordered pair of nodes for implementation ADT ExtendedUnorientedGraph.
 *
 * Represents an unordered combination of two distinct elements with associated values.
 * Unlike Kotlin's [Pair], this class provides **order-independent equality**:
 * `Doubleton(A, B) == Doubleton(B, A)`.
 *
 * **Key Features:**
 * - Extends [AbstractSet] providing set semantics
 * - Enforces element distinctness (first != second)
 * - Order-independent equality and commutative hashCode
 * - Associated values for each element accessible via [getValue]
 * - Mutable values via [setValues]
 * - Suitable as HashMap key with proper hash-based collection behavior
 *
 * **Why not use Kotlin's Pair?**
 * - [Pair] is ordered: `Pair(A, B) != Pair(B, A)`
 * - [Pair] has no associated values mechanism
 * - [Pair] is not a Set and doesn't enforce distinctness
 *
 * This class is essential for representing unordered edges in graph structures where
 * the relationship between nodes is bidirectional and order-independent.
 *
 * @param T type of elements (the two nodes)
 * @param V type of additional information associated with each element
 */
class Doubleton<T, V>(first: T, second: T) : AbstractMutableSet<T>() {
	enum class IteratorState {
		INIT,
		FIRST,
		SECOND
	}

	private inner class DoubletonIterator : MutableIterator<T> {
		private var state = IteratorState.INIT

		override fun hasNext(): Boolean = state != IteratorState.SECOND

		override fun next(): T =
			when (state) {
				IteratorState.INIT -> {
					state = IteratorState.FIRST
					first
				}
				IteratorState.FIRST -> {
					state = IteratorState.SECOND
					second
				}
				IteratorState.SECOND -> throw kotlin.NoSuchElementException()
			}

		override fun remove(): Unit = throw UnsupportedOperationException()
	}

	private val first: T
	private val second: T
	private var firstValue: V? = null
	private var secondValue: V? = null

	init {
		if (first == second) throw IllegalArgumentException("arguments is equal")
		this.first = first
		this.second = second
	}

	/**
	 * Creates a Doubleton with associated values for each element.
	 *
	 * @param first the first element
	 * @param second the second element (must be distinct from first)
	 * @param firstValue the value associated with the first element
	 * @param secondValue the value associated with the second element
	 * @throws IllegalArgumentException if first equals second
	 */
	constructor(first: T, second: T, firstValue: V, secondValue: V) : this(first, second) {
		this.firstValue = firstValue
		this.secondValue = secondValue
	}

	override fun hashCode(): Int {
		var result = first.hashCode()
		result += second.hashCode() // kongruentni komutativita?
		return result
	}

	/**
	 * Compares this Doubleton with another object for equality.
	 * <p>
	 * This method explicitly delegates to {@link AbstractSet#equals(Object)},
	 * which compares sets by their content (order-independent). A Doubleton
	 * is equal to any {@link Set} containing exactly the same two elements,
	 * regardless of order. The firstValue and secondValue fields are not
	 * considered for equality.
	 * </p>
	 * <p>
	 * This explicit override satisfies the Java contract that requires overriding
	 * equals() when hashCode() is overridden, and documents the intended behavior.
	 * </p>
	 *
	 * @param obj the object to compare with
	 * @return true if {@code obj} is a {@link Set} containing exactly the same two elements
	 * (regardless of order); associated values (firstValue, secondValue) are ignored
	 */
	override fun equals(obj: Any?): Boolean {
		// Early reference check
		if (this === obj) return true
		if (obj == null) return false

		// Type check: Set can only equal another Set
		if (obj !is Set<*>) return false

		// Delegate to AbstractSet's content-based equality implementation
		// which correctly handles order-independent comparison.
		// This allows equality with any Set (not just Doubleton) that has
		// the same elements, which is the correct Set semantics.
		return super.equals(obj)
	}

	override fun add(element: T): Boolean = throw UnsupportedOperationException()

	override fun iterator(): MutableIterator<T> = DoubletonIterator()

	override val size: Int = 2

	override fun isEmpty(): Boolean = false

	/**
	 * Retrieves the value associated with the specified element.
	 *
	 * @param key the element whose associated value is to be returned
	 * @return the value associated with the key, or null if no value has been set
	 * @throws IllegalArgumentException if the key is not contained in this Doubleton
	 */
	fun getValue(key: T): V? {
		val secondEq = nullEq(key, second)
		if (nullEq(key, first)) {
			requireValidState(!secondEq) { "Key matches both first and second elements: $key" }
			return firstValue
		}
		if (secondEq) return secondValue
		throw IllegalArgumentException("Key $key is not contained in set")
	}

	private fun nullEq(
		a: Any?,
		b: Any?
	): Boolean {
		if (a == null) return b == null
		return a == b
	}

	/**
	 * Sets the associated values for both elements.
	 *
	 * @param first the value to associate with the first element
	 * @param second the value to associate with the second element
	 */
	fun setValues(
		first: V,
		second: V
	) {
		this.firstValue = first
		this.secondValue = second
	}
}
