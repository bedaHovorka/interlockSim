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
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore.Signal
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import java.util.EnumSet
import java.util.Set

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
		requireSimulation(inSemaphore.direction() == Cell.Segment.anti(direction())) {
			"In semaphore direction must be anti-parallel to InOut direction"
		}
		this.outSemaphore = RailSemaphore.getConstantInstance(orientation, spatialType, Signal.FREE)
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

	private fun getSemaphoreFor(
		from: Cell.Segment?,
		to: Cell.Segment?
	): RailSemaphore? {
		if (from == null && to == direction()) return inSemaphore
		if (to == null && from == direction()) return outSemaphore
		return null
	}

	@Throws(PathSeparatorChangeException::class)
	private fun getSemaphoreForWithExeption(
		from: Cell.Segment?,
		to: Cell.Segment?
	): RailSemaphore {
		val sem = getSemaphoreFor(from, to)
		if (sem == null) throw PathSeparatorChangeException(this)
		return sem
	}

	override fun setUpPath(
		from: Cell.Segment?,
		to: Cell.Segment?,
		allowedSpeed: Double
	) {
		val sem = getSemaphoreForWithExeption(from, to)
		sem.setSignal(Signal.forSpeed(allowedSpeed))
	}

	override fun cancelPathSetup(
		from: Cell.Segment?,
		to: Cell.Segment?
	) {
		val sem = getSemaphoreForWithExeption(from, to)
		sem.setSignal(Signal.STOP)
	}

	override fun allowedSpeed(): Double = PathElement.ABSOLUTE_MAX_SPEED
}
