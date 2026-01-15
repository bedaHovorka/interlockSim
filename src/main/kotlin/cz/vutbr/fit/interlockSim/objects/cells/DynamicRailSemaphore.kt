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

import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.objects.paths.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Dynamic wrapper for RailSemaphore separating static and dynamic properties.
 *
 * **Static properties** (delegated from wrapped semaphore): orientation, spatialType, position
 * **Dynamic properties** (in this class): signal state (changes during simulation)
 *
 * This wrapper uses the static RailSemaphore object for:
 * - Stable identity (equals/hashCode based on static object)
 * - Immutable configuration (orientation, position)
 * - Type compatibility with existing code
 *
 * Part of Phase 4: Static/Dynamic property separation (bedaHovorka/interlockSim#92)
 *
 * @property static The static semaphore object with immutable editing-time properties
 */
sealed class DynamicRailSemaphore(
	val static: RailSemaphore
) : OrientedPathSeparator by static, DynamicPathSeparator {
	// Static properties delegated from wrapped object
	// orientation and direction() are delegated from OrientedPathSeparator
	// spatialType is already available via PathSeparator delegation (getSpatialType())
	val name: String
		get() = static.getName()

	/**
	 * Dynamic property: Current signal state (mutable, changes during simulation)
	 *
	 * Initial state is STOP for safety.
	 */
	open var signal: Signal = Signal.STOP
		set(newSignal) {
			logger.debug {
				if (field != newSignal) {
					"Semaphore ${static.getName()} " +
						"signal change: $field -> $newSignal at t=${jDisco.Process.time()}"
				} else {
					""
				}
			}
			field = newSignal
		}

	override fun cancelPathSetup(
		from: Cell.Segment?,
		to: Cell.Segment?
	) {
		checkPathSegments(from, to)
		signal = Signal.STOP
	}

	override fun setUpPath(
		from: Cell.Segment?,
		to: Cell.Segment?,
		allowedSpeed: Double
	) {
		if (checkPathSegments(from, to)) {
			signal = forSpeed(allowedSpeed)
		}
	}

	override fun allowedSpeed(): Double = signal.allowedSpeed()

	@Throws(PathSeparatorChangeException::class)
	private fun checkPathSegments(
		from: Cell.Segment?,
		to: Cell.Segment?
	): Boolean {
		val d = static.direction()
		if (to == d && from == anti(d)) return true
		if (from == d && to == anti(d)) return false
		throw PathSeparatorChangeException("wrong aPath segments", this)
	}

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? = static.getFollowingSegment(from)

	/**
	 * Equality based on the static object (stable identity).
	 *
	 * Two DynamicRailSemaphore instances are equal if they wrap the same
	 * static semaphore object, regardless of their current signal state.
	 * Also supports comparison with static RailSemaphore objects directly.
	 *
	 * This ensures stable identity for use in collections (Set, Map) and
	 * compatibility with code that uses static objects (e.g., ShuntingLoop).
	 */
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		return when (other) {
			is DynamicRailSemaphore -> static === other.static
			is RailSemaphore -> static === other
			else -> false
		}
	}

	/**
	 * Hash code based on the static object (stable hash code).
	 *
	 * Uses identity hash code of the static object to ensure:
	 * - Consistency with equals()
	 * - Stability across signal state changes
	 * - Proper behavior in hash-based collections
	 */
	override fun hashCode(): Int = System.identityHashCode(static)

	/**
	 * String representation for debugging
	 */
	override fun toString(): String = "Dynamic[$name, signal=$signal]"
}

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

private class ConstantSemaphore(
	static: RailSemaphore,
	signal: Signal
) : DynamicRailSemaphore(static) {

	override var signal: Signal = signal
		set(newSignal) {
			// ignore changes
			field = field
		}
}

private class DefaultDynamicSemaphore(static: RailSemaphore) : DynamicRailSemaphore(static)

/**
 * @param speed
 * @return signal with allowedSpeed less than speed
 */
fun forSpeed(speed: Double): Signal {
	requireSimulation(speed >= Signal.S30.allowedSpeed() || speed == 0.0) {
		"Speed must be at least S30 allowed speed or 0.0: $speed"
	}
	val entries = Signal.entries
	for (s in entries) {
		if (s.allowedSpeed() > speed) return if (s.ordinal == 0) Signal.STOP else entries[s.ordinal - 1]
	}
	return Signal.FREE
}

fun createConstantInstance(
	static: RailSemaphore,
	signal: Signal
): DynamicRailSemaphore = ConstantSemaphore(static, signal)

/**
 * create semaphore, which don't change signal - like: "predzvest", impasse end "naraznik", "rychlostnik"
 * @param orientation
 * @param spatialType
 * @param signal
 * @return constant orientented aPath separator
 */
fun createConstantInstance(
	orientation: Boolean,
	spatialType: Cell.SpatialType,
	signal: Signal
): DynamicRailSemaphore = ConstantSemaphore(RailSemaphore(orientation, spatialType), signal)

/**
 * create dynamic rail semaphore
 * @param static static rail semaphore
 * @return dynamic rail semaphore
 */
fun createDynamicInstance(
	static: RailSemaphore
): DynamicRailSemaphore = DefaultDynamicSemaphore(static)
