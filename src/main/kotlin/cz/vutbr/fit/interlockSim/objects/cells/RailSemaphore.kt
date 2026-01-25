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

import cz.vutbr.fit.interlockSim.objects.core.Cell
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * "Navestidlo"
 */
open class RailSemaphore(
	orientation: Boolean,
	spatialType: Cell.SpatialType
) : OrientedNodeCell(orientation, spatialType) {
	override fun joins(): Set<Cell.Segment> = joinsOnLine()

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? = secondOnLine(from)

	/**
	 * Implementation of asRailSemaphore() for RailSemaphore.
	 * Returns self since this is already a RailSemaphore.
	 */
	override fun asRailSemaphore(): RailSemaphore = this
}
