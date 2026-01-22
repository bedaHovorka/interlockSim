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

import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.objects.core.Cell
import java.util.EnumSet

/**
 * Base implementation of {@link Cell}
 *
 */
abstract class AbstractCell : Cell {
	protected fun joinsOnLine(): MutableSet<Cell.Segment> {
		val st = getSpatialType()
		requireValidState(st != null) { "Spatial type is null for cell: $this" }
		val arr2set = arr2set(st!!.segments)
		requireValidState(arr2set.size == 2) { "Expected 2 segments but got ${arr2set.size}" }
		return arr2set.toMutableSet()
	}

	protected fun secondOnLine(from: Cell.Segment?): Cell.Segment? {
		val joins = joinsOnLine()
		val removed = joins.remove(from)
		requireValidState(removed && joins.size == 1) {
			"Failed to find unique second segment: from=$from, remaining joins=$joins"
		}
		val iterator = joins.iterator()
		requireValidState(iterator.hasNext()) { "Iterator should have next element" }
		return iterator.next()
	}
}

fun arr2set(segments: Array<Cell.Segment>): Set<Cell.Segment> {
	requireValidState(segments.isNotEmpty()) { "Segments array cannot be empty" }
	return EnumSet.of(segments[0], *segments) as Set<Cell.Segment>
}
