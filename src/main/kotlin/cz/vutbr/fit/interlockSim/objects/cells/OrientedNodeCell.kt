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
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import java.util.EnumSet
import java.util.Set

/**
 * Base implementation of {@link OrientedPathSeparator}
 */
abstract class OrientedNodeCell protected constructor(
	private val orientation: Boolean,
	spatialType: Cell.SpatialType
) : NodeCell(spatialType),
	OrientedPathSeparator {
	override fun possibleFollowers(from: Cell.Segment): Set<Cell.Segment> =
		EnumSet.of(getFollowingSegment(from)) as Set<Cell.Segment>

	override fun getOrientation(): Boolean = orientation

	override fun direction(): Cell.Segment {
		val st = getSpatialType()
		requireSimulationNotNull(st) { "Spatial type must not be null for: $this" }
		return st.segments[if (getOrientation()) 1 else 0]
	}
}
