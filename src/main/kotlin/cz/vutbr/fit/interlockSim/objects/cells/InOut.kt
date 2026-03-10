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

	override fun joins(): Set<Cell.Segment> = setOf(direction())

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
	 * Set the name of this InOut.
	 * Overrides parent to update the local name field (which shadows parent's name).
	 *
	 * @param name The new name for this InOut
	 */
	override fun setName(name: String) {
		this.name = name
		super.setName(name) // Also update parent's name for consistency
	}

	/**
	 * @return semaphore on output
	 */
	fun getOutSemaphore(): RailSemaphore = outSemaphore

	/**
	 * Implementation of asRailSemaphore() for InOut.
	 * Returns the output semaphore since InOut acts as an exit point.
	 */
	override fun asRailSemaphore(): RailSemaphore = outSemaphore

	/**
	 * Get the track connection direction for InOut.
	 *
	 * InOut connects to the railway network at `direction()` regardless of whether
	 * trains are entering or exiting. The track connection is bidirectional.
	 *
	 * ## Bidirectional Semantics
	 *
	 * InOut has two semaphores:
	 * - **inSemaphore**: faces `anti(direction())` - controls trains entering FROM external network
	 * - **outSemaphore**: faces `direction()` - controls trains exiting TO external network
	 *
	 * The track connection is ALWAYS at `direction()`:
	 * - **Entry path**: FROM null (outside at anti-direction) TO direction() (track) → uses inSemaphore
	 * - **Exit path**: FROM direction() (track) TO null (outside at anti-direction) → uses outSemaphore
	 *
	 * See [DynamicInOut.getSemaphoreFor] for the logic that determines which semaphore
	 * controls a path based on from/to segments.
	 *
	 * @return Track connection direction (same as output direction)
	 * @see DynamicInOut.getSemaphoreFor
	 * @see getInSemaphore
	 * @see getOutSemaphore
	 */
	fun getTrackConnectionDirection(): Cell.Segment = direction()
}
