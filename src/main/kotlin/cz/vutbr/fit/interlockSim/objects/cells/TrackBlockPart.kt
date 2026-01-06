/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells // jinde?

import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import java.util.Set

/**
 * Cell as part of [TrackBlock] in grid
 *
 */
class TrackBlockPart(
	private val trackBlock: TrackBlock,
	private val segments: Array<Cell.Segment>
) : AbstractCell() {
	init {
		assert(segments.isNotEmpty())
	}

	/**
	 * @return whole block
	 */
	fun getTrackBlock(): TrackBlock = trackBlock

	/**
	 * @return topology joins
	 */
	fun getSegments(): Array<Cell.Segment> = segments

	override fun joins(): Set<Cell.Segment> = arr2set(getSegments())

	@Suppress("UNCHECKED_CAST")
	override fun getSpatialType(): Cell.SpatialType = null as Cell.SpatialType
}
