/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import java.awt.Point
import java.util.AbstractSet
import java.util.Iterator
import java.util.Map
import java.util.Map.Entry
import java.util.Set

/**
 * Grid represantation
 */
class DefaultRailWayNetGrid(
	cols: Int,
	rows: Int
) : AbstractRailwayNetGrid(cols, rows) {
	private inner class KeySet(
		private val set: Set<Entry<Point, Cell>>
	) : AbstractSet<Point>() {
		override fun iterator(): MutableIterator<Point> {
			@Suppress("UNCHECKED_CAST")
			val delegate: Iterator<Entry<Point, Cell>> = (set.iterator() as Iterator<Entry<Point, Cell>>)
			return PointIterator(delegate)
		}

		private inner class PointIterator(
			private val delegate: Iterator<Entry<Point, Cell>>
		) : MutableIterator<Point> {
			private var next: Entry<Point, Cell>? = null

			override fun next(): Point {
				next = delegate.next()
				return next!!.key
			}

			override fun hasNext(): Boolean = delegate.hasNext()

			override fun remove() {
				if (delegate is MutableIterator<*>) {
					(delegate as MutableIterator<Entry<Point, Cell>>).remove()
				} else {
					throw UnsupportedOperationException("remove")
				}
			}
		}

		override val size: Int
			get() = set.size
	}

	/**
	 * @param key
	 * @param cell
	 * @return previous cell in place
	 */
	fun put(
		key: Point,
		cell: Cell
	): Cell? {
		if (getCells().get(key) == cell) return cell
		getCells().values.remove(cell)
		val prev: Cell? = getCells().put(key, cell)
		getReverseTable().remove(prev)
		getReverseTable()[cell] = key
		return prev
	}

	/**
	 * @param map of point to trackblock part
	 */
	fun putMap(map: Map<Point, TrackBlockPart>) {
		@Suppress("UNCHECKED_CAST")
		val javaMap = map as java.util.Map<Point, TrackBlockPart>
		val iter = javaMap.entrySet().iterator()
		while (iter.hasNext()) {
			val entry = iter.next()
			put(entry.key, entry.value)
		}
	}

	/**
	 * @param newPoint
	 * @return true if point is present
	 */
	fun containsKey(newPoint: Point): Boolean {
		if (getCells().containsKey(newPoint)) {
			assert(getReverseTable().containsValue(newPoint))
			return true
		}
		assert(!getReverseTable().containsValue(newPoint)) { newPoint }
		return false
	}

	/**
	 * Remove cell from grid
	 * @param key
	 */
	fun remove(key: Point) {
		assert(key != null)
		val removed: Cell? = getCells().remove(key)
		val remove2: Point? = getReverseTable().remove(removed)
		assert(key == remove2)
	}

	/**
	 * @return true if grid is empty
	 */
	fun isEmpty(): Boolean {
		assert(getReverseTable().isEmpty() == getCells().isEmpty())
		return getCells().isEmpty()
	}

	/**
	 * All cell in grid
	 * @return set of cells
	 */
	fun keySet(): Set<Point> {
		@Suppress("UNCHECKED_CAST")
		val entries = getCells().entries as java.util.Set<Entry<Point, Cell>>
		return KeySet(entries) as Set<Point>
	}
}
