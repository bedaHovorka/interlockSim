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

import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Set

private val logger = KotlinLogging.logger {}

/**
 * "Navestidlo"
 */
open class RailSemaphore(
	orientation: Boolean,
	spatialType: Cell.SpatialType
) : OrientedNodeCell(orientation, spatialType) {
	/**
	 * Represents ligths (command) on semaphore
	 */
	enum class Signal(
		speed: Int?
	) {
		/**
		 * red signal, stop
		 */
		STOP(0),

		/**
		 * allow speed max 30 km/h
		 */
		S30(30),

		/**
		 * allow speed max 40 km/h
		 */
		S40(40),

		/**
		 * allow speed max 60 km/h
		 */
		S60(60),

		/**
		 * allow speed max 80 km/h
		 */
		S80(80),

		/**
		 * allow speed max 100 km/h
		 */
		S100(100),

		/**
		 * allow speed maximal in track section
		 */
		FREE(null);

		private val allowedSpeed: Double

		init {
			this.allowedSpeed = if (speed != null) speed / 3.6 else PathElement.ABSOLUTE_MAX_SPEED
		}

		/**
		 * @return if the signal allow next move
		 */
		fun isAllowing(): Boolean = allowedSpeed > 0

		/**
		 * @return speed in in m/s !!!
		 */
		fun allowedSpeed(): Double = allowedSpeed
	}

	private var signal: Signal = Signal.STOP

	override fun joins(): Set<Cell.Segment> = joinsOnLine()

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? = secondOnLine(from)

	/**
	 * @return atribute getter {@link Signal}
	 */
	fun getSignal(): Signal = signal

	/**
	 * atribute setter {@link Signal}
	 * @param signal
	 */
	open fun setSignal(signal: Signal) {
		logger.debug {
			if (this.signal != signal) {
				"Semaphore ${if (getName() != null) getName() else this.hashCode()} " +
					"signal change: ${this.signal} -> $signal at t=${jDisco.Process.time()}"
			} else {
				""
			}
		}
		this.signal = signal
	}

	override fun cancelPathSetup(
		from: Cell.Segment?,
		to: Cell.Segment?
	) {
		checkPathSegments(from, to)
		setSignal(Signal.STOP)
	}

	override fun setUpPath(
		from: Cell.Segment?,
		to: Cell.Segment?,
		allowedSpeed: Double
	) {
		if (checkPathSegments(from, to)) {
			setSignal(forSpeed(allowedSpeed))
		}
	}

	override fun allowedSpeed(): Double = getSignal().allowedSpeed()

	@Throws(PathSeparatorChangeException::class)
	private fun checkPathSegments(
		from: Cell.Segment?,
		to: Cell.Segment?
	): Boolean {
		val d = direction()
		if (to == d && from == anti(d)) return true
		if (from == d && to == anti(d)) return false
		throw PathSeparatorChangeException("wrong aPath segments", this)
	}
}

private class ConstantSemaphore(
	orientation: Boolean,
	spatialType: Cell.SpatialType,
	signal: Signal
) : RailSemaphore(orientation, spatialType) {
	init {
		super.setSignal(signal)
	}

	override fun setSignal(signal: Signal) {
		// EMPTY
	}
}

/**
 * @param speed
 * @return signal with allowedSpeed less than speed
 */
fun forSpeed(speed: Double): RailSemaphore.Signal {
	requireSimulation(speed >= RailSemaphore.Signal.S30.allowedSpeed() || speed == 0.0) {
		"Speed must be at least S30 allowed speed or 0.0: $speed"
	}
	val entries = RailSemaphore.Signal.entries
	for (s in entries) {
		if (s.allowedSpeed() > speed) return if (s.ordinal == 0) RailSemaphore.Signal.STOP else entries[s.ordinal - 1]
	}
	return RailSemaphore.Signal.FREE
}

/**
 * create semaphore, which don't change signal - like: "predzvest", impasse end "naraznik", "rychlostnik"
 * @param orientation
 * @param spatialType
 * @param signal
 * @return constant orientented aPath separator
 */
fun getConstantInstance(
	orientation: Boolean,
	spatialType: Cell.SpatialType,
	signal: RailSemaphore.Signal
): RailSemaphore = ConstantSemaphore(orientation, spatialType, signal)
