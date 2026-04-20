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

import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator

/**
 * Base implementation of {@link OrientedPathSeparator}
 */
abstract class OrientedNodeCell protected constructor(
	private val orientation: Boolean,
	spatialType: Cell.SpatialType,
	name: String = ""
) : NodeCell(spatialType, name),
	OrientedPathSeparator {
	/**
	 * Get the following segment from a given segment (static configuration).
	 * This is used by possibleFollowers and also needed by Dynamic wrappers.
	 */
	abstract fun getFollowingSegment(from: Cell.Segment?): Cell.Segment?

	override fun possibleFollowers(from: Cell.Segment): Set<Cell.Segment> = setOfNotNull(getFollowingSegment(from))

	override fun getOrientation(): Boolean = orientation

	override fun direction(): Cell.Segment {
		val st = getSpatialType()
		requireSimulationNotNull(st) { "Spatial type must not be null for: $this" }
		return st.segments[if (getOrientation()) 1 else 0]
	}
}
