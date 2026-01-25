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

import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.anti
import java.util.EnumSet

/**
 * predstavuje spojeni s externi zeleznicni siti
 *
 */
class InOut(
	name: String,
	orientation: Boolean,
	spatialType: Cell.SpatialType
) : OrientedNodeCell(orientation, spatialType) {
	private var name: String
	private val inSemaphore: RailSemaphore
	private val outSemaphore: RailSemaphore

	init {
		this.name = name
		this.inSemaphore = RailSemaphore(!orientation, spatialType)
		requireSimulation(inSemaphore.direction() == anti(direction())) {
			"In semaphore direction must be anti-parallel to InOut direction"
		}
		this.outSemaphore = RailSemaphore(orientation, spatialType)
		setName(name)
	}

	override fun joins(): Set<Cell.Segment> = EnumSet.of(direction()) as Set<Cell.Segment>

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? {
		if (from == null) return direction()
		requireSimulation(from === direction()) { "Invalid segment: $from, expected: ${direction()}" }
		return null
	}

	/**
	 * @return semaphore on input
	 */
	fun getInSemaphore(): RailSemaphore = inSemaphore

	/**
	 * @return name of place
	 */
	override fun getName(): String = name

	/**
	 * @return semaphore on output
	 */
	fun getOutSemaphore(): RailSemaphore = outSemaphore

	/**
	 * Implementation of asRailSemaphore() for InOut.
	 * Returns the output semaphore since InOut acts as an exit point.
	 */
	override fun asRailSemaphore(): RailSemaphore = outSemaphore
}
