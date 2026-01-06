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
import cz.vutbr.fit.interlockSim.sim.PathSeparatorChangeException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Set

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

		companion object {
			private val values = Signal.values()

			/**
			 * @param speed
			 * @return signal with allowedSpeed less then speed
			 */
			@JvmStatic
			fun forSpeed(speed: Double): Signal {
				assert(speed >= S30.allowedSpeed() || speed == 0.0)
				for (s in values) {
					if (s.allowedSpeed() > speed) return if (s.ordinal == 0) STOP else values[s.ordinal - 1]
				}
				return FREE
			}
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
		if (logger.isDebugEnabled && this.signal != signal) {
			logger.debug(
				"Semaphore {} signal change: {} -> {} at t={}",
				if (getName() != null) getName() else this.hashCode(),
				this.signal,
				signal,
				jDisco.Process.time()
			)
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
			setSignal(Signal.forSpeed(allowedSpeed))
		}
	}

	override fun allowedSpeed(): Double = getSignal().allowedSpeed()

	@Throws(PathSeparatorChangeException::class)
	private fun checkPathSegments(
		from: Cell.Segment?,
		to: Cell.Segment?
	): Boolean {
		val d = direction()
		if (to == d && from == Cell.Segment.anti(d)) return true
		if (from == d && to == Cell.Segment.anti(d)) return false
		throw PathSeparatorChangeException("wrong aPath segments", this)
	}

	companion object {
		private val logger: Logger = LoggerFactory.getLogger(RailSemaphore::class.java)

		/**
		 * create semaphore, which don't change signal - like: "predzvest", impasse end "naraznik", "rychlostnik"
		 * @param orientation
		 * @param spatialType
		 * @param signal
		 * @return constant orientented aPath separator
		 */
		@JvmStatic
		fun getConstantInstance(
			orientation: Boolean,
			spatialType: Cell.SpatialType,
			signal: Signal
		): RailSemaphore = ConstantSemaphore(orientation, spatialType, signal)
	}
}
