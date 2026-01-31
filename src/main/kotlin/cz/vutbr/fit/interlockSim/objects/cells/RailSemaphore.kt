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
open class RailSemaphore : OrientedNodeCell {
	/**
	 * Primary constructor accepting orientation and spatialType (for backward compatibility).
	 */
	constructor(
		orientation: Boolean,
		spatialType: Cell.SpatialType
	) : super(orientation, spatialType)

	/**
	 * Constructor accepting optional name, orientation, and spatialType.
	 * Used by XML parser when name attribute is present in vyhybna.xml.
	 *
	 * @param name Optional name for the semaphore (e.g., "zA", "doA1")
	 * @param orientation Semaphore orientation (true/false)
	 * @param spatialType Spatial type (HORIZONTAL/VERTICAL)
	 */
	constructor(
		name: String?,
		orientation: Boolean,
		spatialType: Cell.SpatialType
	) : super(orientation, spatialType) {
		if (name != null) {
			setName(name)
		}
	}

	override fun joins(): Set<Cell.Segment> = joinsOnLine()

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? = secondOnLine(from)

	/**
	 * Implementation of asRailSemaphore() for RailSemaphore.
	 * Returns self since this is already a RailSemaphore.
	 */
	override fun asRailSemaphore(): RailSemaphore = this
}
