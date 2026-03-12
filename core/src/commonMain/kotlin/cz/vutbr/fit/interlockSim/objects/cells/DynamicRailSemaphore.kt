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
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.core.anti
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
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
	val staticRef: RailSemaphore
) : OrientedPathSeparator by staticRef,
	DynamicPathSeparator {
	/**
	 * Listeners for signal state changes. Copy-on-write via @Volatile list.
	 */
	@Volatile
	private var listeners: List<ContextPropertyChangeListener> = emptyList()

	// Static properties delegated from wrapped object
	// orientation and direction() are delegated from OrientedPathSeparator
	// spatialType is already available via PathSeparator delegation (getSpatialType())
	val name: String
		get() = staticRef.getName()

	/**
	 * Dynamic property: Current signal state (mutable, changes during simulation)
	 *
	 * Initial state is STOP for safety.
	 */
	open var signal: Signal = Signal.STOP
		set(newSignal) {
			val oldSignal = field
			logger.debug {
				if (oldSignal != newSignal) {
					"Semaphore ${staticRef.getName()} " +
						"signal change: $oldSignal -> $newSignal at t=${jDisco.Process.time()}"
				} else {
					""
				}
			}
			field = newSignal
			// Fire property change event only if signal actually changed
			if (oldSignal != newSignal) {
				val evt = ContextChangeEvent("signal", oldSignal, newSignal)
				listeners.forEach { it.propertyChange(evt) }
			}
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
		allowedSpeed: Double,
		trackOccupant: TrackOccupant
	) {
		setUpSpeed(from, to, allowedSpeed)
	}

	fun setUpSpeed(
		from: Cell.Segment?,
		to: Cell.Segment?,
		allowedSpeed: Double
	) {
		val isValidDirection = checkPathSegments(from, to)

		if (isValidDirection) {
			signal = forSpeed(allowedSpeed)
			logger.debug {
				"SEMAPHORE_SIGNAL_UPDATED: ${staticRef.getName()} signal changed to $signal " +
					"(allowedSpeed=$allowedSpeed)"
			}
		} else {
			logger.warn {
				"SEMAPHORE_SIGNAL_NOT_UPDATED: ${staticRef.getName()} signal remains $signal " +
					"due to reverse direction (from=$from, to=$to, semaphoreDirection=${staticRef.direction()})"
			}
		}
	}

	override fun allowedSpeed(): Double = signal.allowedSpeed()

	@Throws(PathSeparatorChangeException::class)
	private fun checkPathSegments(
		from: Cell.Segment?,
		to: Cell.Segment?
	): Boolean {
		val d = staticRef.direction()
		if (to == d && from == anti(d)) return true
		if (from == d && to == anti(d)) return false
		throw PathSeparatorChangeException("wrong aPath segments", this)
	}

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? = staticRef.getFollowingSegment(from)

	/**
	 * Implementation of asRailSemaphore() for DynamicRailSemaphore.
	 * Returns the static reference since the dynamic wrapper represents the same semaphore.
	 */
	override fun asRailSemaphore(): RailSemaphore = staticRef

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
		if (other == null) return false

		// Compare with DynamicRailSemaphore (compare staticRef references)
		if (other is DynamicRailSemaphore) {
			return staticRef === other.staticRef
		}

		// Compare with static RailSemaphore (compare this.staticRef with other)
		if (other is RailSemaphore) {
			return staticRef === other
		}

		return false
	}

	/**
	 * Hash code based on the static object (stable hash code).
	 *
	 * Uses identity hash code of the static object to ensure:
	 * - Consistency with equals()
	 * - Stability across signal state changes
	 * - Proper behavior in hash-based collections
	 */
	override fun hashCode(): Int = staticRef.hashCode()

	/**
	 * Adds a property change listener to this semaphore.
	 * The listener will be notified when the "signal" property changes.
	 */
	fun addPropertyChangeListener(listener: ContextPropertyChangeListener) {
		listeners = listeners + listener
	}

	/**
	 * Removes a property change listener from this semaphore.
	 */
	fun removePropertyChangeListener(listener: ContextPropertyChangeListener) {
		listeners = listeners - listener
	}

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
		set(_) {
			// Ignore changes: constant semaphore signal must not change
			// No-op setter to maintain compatibility with parent var
		}
}

private class DefaultDynamicSemaphore(
	static: RailSemaphore
) : DynamicRailSemaphore(static)

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
fun createDynamicInstance(static: RailSemaphore): DynamicRailSemaphore = DefaultDynamicSemaphore(static)
