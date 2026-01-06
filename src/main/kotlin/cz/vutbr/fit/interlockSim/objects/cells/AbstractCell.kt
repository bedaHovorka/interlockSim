/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells

import java.util.EnumSet
import java.util.Set

/**
 * Base implementation of {@link Cell}
 *
 */
abstract class AbstractCell : Cell {
	protected fun joinsOnLine(): Set<Cell.Segment> {
		val st = getSpatialType()
		assert(st != null) { this }
		val arr2set = arr2set(st!!.segments)
		assert(arr2set.size == 2)
		return arr2set
	}

	protected fun secondOnLine(from: Cell.Segment?): Cell.Segment? {
		val joins = joinsOnLine()
		val removed = joins.remove(from)
		assert(removed && joins.size == 1) { "$from $joins" }
		val iterator = joins.iterator()
		assert(iterator.hasNext())
		return iterator.next()
	}

	companion object {
		@JvmStatic
		protected fun arr2set(segments: Array<Cell.Segment>): Set<Cell.Segment> {
			assert(segments.isNotEmpty())
			return EnumSet.of(segments[0], *segments) as Set<Cell.Segment>
		}
	}
}
