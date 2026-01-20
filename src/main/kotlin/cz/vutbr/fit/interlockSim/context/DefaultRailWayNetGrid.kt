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

import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.util.Point
import java.util.AbstractSet

/**
 * Grid represantation
 */
class DefaultRailWayNetGrid(
	cols: Int,
	rows: Int
) : AbstractRailwayNetGrid<Cell>(cols, rows) {
	private inner class KeySet(
		private val set: Set<Map.Entry<Point, Cell>>
	) : AbstractSet<Point>() {
		override fun iterator(): MutableIterator<Point> {
			val delegate: Iterator<Map.Entry<Point, Cell>> = set.iterator()
			return PointIterator(delegate)
		}

		private inner class PointIterator(
			private val delegate: Iterator<Map.Entry<Point, Cell>>
		) : MutableIterator<Point> {
			private var next: Map.Entry<Point, Cell>? = null

			override fun next(): Point {
				next = delegate.next()
				return next!!.key
			}

			override fun hasNext(): Boolean = delegate.hasNext()

			override fun remove() {
				if (delegate is MutableIterator<*>) {
					// Save the cell BEFORE removing from cells map
					// (Map.Entry.getValue() will fail after removal since it looks up the key)
					val cell = next?.value
					// Remove from cells map via delegate
					(delegate as MutableIterator<Map.Entry<Point, Cell>>).remove()
					// Also remove from reverse table to maintain invariant
					if (cell != null) {
						getReverseTable().remove(cell)
					}
				} else {
					throw UnsupportedOperationException("remove")
				}
			}
		}

		override fun removeAll(elements: Collection<Point>): Boolean {
			// Collect cells to remove before modifying the map
			// to avoid ConcurrentModificationException
			val cellsToRemove = mutableListOf<Cell>()
			val pointsToRemove = mutableSetOf<Point>()

			for (point in elements) {
				val cell = getCells()[point]
				if (cell != null) {
					cellsToRemove.add(cell)
					pointsToRemove.add(point)
				}
			}

			// Remove all points from cells map
			for (point in pointsToRemove) {
				getCells().remove(point)
			}

			// Remove all cells from reverse table
			for (cell in cellsToRemove) {
				getReverseTable().remove(cell)
			}

			return pointsToRemove.isNotEmpty()
		}

		override val size: Int
			get() = set.size
	}

	/**
	 * @param key
	 * @param cell
	 * @return previous cell in place
	 */
	@Synchronized
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
	@Synchronized
	fun putMap(map: Map<Point, TrackBlockPart>) {
		for ((key, value) in map) {
			put(key, value)
		}
	}

	/**
	 * @param newPoint
	 * @return true if point is present
	 */
	@Synchronized
	override fun containsKey(newPoint: Point): Boolean {
		if (getCells().containsKey(newPoint)) {
			requireValidState(getReverseTable().containsValue(newPoint)) {
				"Inconsistent grid state: point $newPoint in cells but not in reverse table"
			}
			return true
		}
		requireValidState(!getReverseTable().containsValue(newPoint)) {
			"Inconsistent grid state: point $newPoint in reverse table but not in cells: $newPoint"
		}
		return false
	}

	/**
	 * Remove cell from grid
	 * @param key
	 */
	@Synchronized
	fun remove(key: Point) {
		val removed: Cell? = getCells().remove(key)
		val remove2: Point? = getReverseTable().remove(removed)
		requireValidState(key == remove2) {
			"Inconsistent grid state: expected key $key but got $remove2 from reverse table"
		}
	}

	/**
	 * @return true if grid is empty
	 */
	fun isEmpty(): Boolean {
		requireValidState(getReverseTable().isEmpty() == getCells().isEmpty()) {
			"Inconsistent grid state: reverse table and cells have different empty states"
		}
		return getCells().isEmpty()
	}

	/**
	 * All cell in grid
	 * @return set of cells
	 */
	fun keySet(): MutableSet<Point> {
		@Suppress("UNCHECKED_CAST")
		val entries = getCells().entries as Set<Map.Entry<Point, Cell>>
		return KeySet(entries) as MutableSet<Point>
	}
}
