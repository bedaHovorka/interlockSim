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

import java.awt.Point
import java.util.AbstractList
import java.util.AbstractMap
import java.util.AbstractSet
import java.util.Comparator
import java.util.Iterator
import java.util.List
import java.util.RandomAccess
import java.util.TreeSet

/**
 * ADT for grid
 * @param <V> type of values
 *
 */
class Array2DMap<V> : AbstractMap<Point, V>() /* EXTENSION implements NavigableMap<Point, V> */ {
	private inner class Entry(
		override val key: Point
	) : MutableMap.MutableEntry<Point, V> {
		/**
		 * Create entry
		 * @param key
		 */

		override val value: V
			get() = this@Array2DMap.get(key)!!

		override fun setValue(newValue: V): V = this@Array2DMap.put(key, newValue)!!

		override fun equals(other: Any?): Boolean {
			if (other !is MutableMap.MutableEntry<*, *>) return false
			val e = other
			val k1 = key
			val k2 = e.key
			if (k1 == k2 || (k1 != null && k1 == k2)) {
				val v1 = value
				val v2 = e.value
				if (v1 == v2 || (v1 != null && v1 == v2)) {
					return true
				}
			}
			return false
		}

		override fun hashCode(): Int {
			val k = key
			val v = value
			return (k?.hashCode() ?: 0) xor (v?.hashCode() ?: 0)
		}
	}

	private inner class Array2DEntrySet : AbstractSet<MutableMap.MutableEntry<Point, V>>() {
		private inner class Array2DIterator : MutableIterator<MutableMap.MutableEntry<Point, V>> {
			private val iterator: java.util.Iterator<Point> = _keys.iterator() as java.util.Iterator<Point>
			private var current: Point? = null

			override fun hasNext(): Boolean = iterator.hasNext()

			override fun next(): Entry {
				current = iterator.next()
				return Entry(current!!)
			}

			override fun remove() {
				if (current == null) throw IllegalStateException()
				this@Array2DMap.remove(current)
				current = null
			}
		}

		override fun iterator(): MutableIterator<MutableMap.MutableEntry<Point, V>> = Array2DIterator()

		override val size: Int
			get() = _keys.size
	}

	// seznam s dirama
	private class RelocableList<T> :
		AbstractList<T>(),
		RandomAccess {
		@Transient
		private var elements: Array<T?>? = null

		override fun get(index: Int): T? {
			if (elements == null || index >= elements!!.size || index < 0) return null
			return elements!![index]
		}

		override fun set(
			index: Int,
			element: T
		): T? {
			if (index < 0) throw IndexOutOfBoundsException()
			val resize = elements == null || index >= elements!!.size
			val prev = if (resize) null else elements!![index]
			if (resize) {
				@Suppress("UNCHECKED_CAST")
				val p = arrayOfNulls<Any>(index + 1) as Array<T?>
				if (elements != null) System.arraycopy(elements, 0, p, 0, elements!!.size)
				elements = p
			}
			elements!![index] = element
			return prev
		}

		override fun removeAt(index: Int): T? {
			if (elements == null || index >= elements!!.size || index < 0) return null
			val prev = elements!![index]
			elements!![index] = null
			return prev
		}

		override val size: Int
			get() = if (elements == null) 0 else elements!!.size
	}

	/**
	 * Compare points in order for grid
	 */
	companion object {
		@JvmField
		val POINT_COMPARATOR: Comparator<Point> =
			Comparator { o1, o2 ->
				val dy = o1.y - o2.y
				if (dy == 0) o1.x - o2.x else dy
			}
	}

	private val array = RelocableList<RelocableList<V>>()
	private val _keys: TreeSet<Point> = TreeSet(POINT_COMPARATOR)

	override val keys: MutableSet<Point>
		get() = _keys

	/* (non-Javadoc)
	 * @see java.util.AbstractMap#entrySet()
	 */
	override val entries: MutableSet<MutableMap.MutableEntry<Point, V>>
		get() = Array2DEntrySet()

	/**
	 *
	 * @param x column
	 * @param y row
	 * @return element in map or null if
	 */
	fun get(
		x: Int,
		y: Int
	): V? {
		val iArray = array.get(y)
		if (iArray == null) return null
		return iArray.get(x)
	}

	override fun get(key: Point): V? = get(key.x, key.y)

	override fun put(
		key: Point,
		value: V
	): V? {
		var iArray = array.get(key.y)
		if (iArray == null) {
			iArray = RelocableList()
			array.set(key.y, iArray)
		}
		_keys.add(key)
		return iArray.set(key.x, value)
	}

	override fun remove(key: Point): V? {
		_keys.remove(key)
		val iArray = array.get(key.y)
		if (iArray == null) return null
		return iArray.removeAt(key.x)
	}

	override fun containsKey(key: Point): Boolean = _keys.contains(key)

	/**
	 * @param y
	 * @return elements at row
	 */
	fun getRow(y: Int): List<V> {
		val list = array.get(y)

		// EXTENSION zatim unmodifieable
		@Suppress("UNCHECKED_CAST")
		val result: java.util.List<V> =
			if (list == null) {
				(java.util.ArrayList<V>() as java.util.List<V>)
			} else {
				(list as java.util.List<V>)
			}
		return result
	}

	override fun clear() {
		_keys.clear()
		array.clear()
	}
}
