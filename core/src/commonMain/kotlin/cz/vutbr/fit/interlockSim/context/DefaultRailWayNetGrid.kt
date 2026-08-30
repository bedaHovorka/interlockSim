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
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.util.Point

/**
 * Grid representation for a railway network.
 *
 * **Thread safety:** This class is intentionally NOT thread-safe.
 * All mutations (put, remove) must occur on a single thread — either the editor thread
 * during context construction, or the simulation thread during execution.
 * The previously present `@Synchronized` annotations were JVM-only and have been removed
 * as part of the KMP migration; the single-threaded contract is enforced by the context
 * lifecycle (edit phase → freeze → simulation phase), not by synchronization primitives.
 *
 * @see BaseContext.freeze
 * @see BaseContext.checkNotFrozen
 */
class DefaultRailWayNetGrid(
	cols: Int,
	rows: Int
) : AbstractRailwayNetGrid<Cell>(cols, rows) {
	private inner class KeySet(
		private val set: Set<Map.Entry<Point, Cell>>
	) : AbstractMutableSet<Point>() {
		override fun add(element: Point): Boolean = throw UnsupportedOperationException("add")

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
						reverseTable.remove(cell)
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
				val cell = cells[point]
				if (cell != null) {
					cellsToRemove.add(cell)
					pointsToRemove.add(point)
				}
			}

			// Remove all points from cells map
			for (point in pointsToRemove) {
				cells.remove(point)
			}

			// Remove all cells from reverse table
			for (cell in cellsToRemove) {
				reverseTable.remove(cell)
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

	fun put(
		key: Point,
		cell: Cell
	): Cell? {
		if (cells.get(key) == cell) return cell
		cells.values.remove(cell)
		val prev: Cell? = cells.put(key, cell)
		reverseTable.remove(prev)
		reverseTable[cell] = key
		return prev
	}

	/**
	 * @param map of point to trackblock part
	 */

	fun putMap(map: Map<Point, TrackBlockPart>) {
		for ((key, value) in map) {
			put(key, value)
		}
	}

	/**
	 * @param newPoint
	 * @return true if point is present
	 */

	override fun containsKey(point: Point): Boolean {
		if (cells.containsKey(point)) {
			requireValidState(reverseTable.containsValue(point)) {
				"Inconsistent grid state: point $point in cells but not in reverse table"
			}
			return true
		}
		requireValidState(!reverseTable.containsValue(point)) {
			"Inconsistent grid state: point $point in reverse table but not in cells: $point"
		}
		return false
	}

	/**
	 * Remove cell from grid
	 * @param key
	 */

	fun remove(key: Point) {
		val removed: Cell? = cells.remove(key)
		val remove2: Point? = reverseTable.remove(removed)
		requireValidState(key == remove2) {
			"Inconsistent grid state: expected key $key but got $remove2 from reverse table"
		}
	}

	/**
	 * @return true if grid is empty
	 */
	fun isEmpty(): Boolean {
		requireValidState(reverseTable.isEmpty() == cells.isEmpty()) {
			"Inconsistent grid state: reverse table and cells have different empty states"
		}
		return cells.isEmpty()
	}

	/**
	 * All cell in grid
	 * @return set of cells
	 */
	fun keySet(): MutableSet<Point> {
		@Suppress("UNCHECKED_CAST")
		val entries = cells.entries as Set<Map.Entry<Point, Cell>>
		return KeySet(entries) as MutableSet<Point>
	}
}
