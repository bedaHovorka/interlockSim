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

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.core.anti
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
	 * Listeners for signal state changes.
	 * Copy-on-write list — @Volatile guarantees cross-thread visibility.
	 */
	@kotlin.concurrent.Volatile
	private var listeners: List<ContextPropertyChangeListener> = emptyList()

	/**
	 * The segment pair this semaphore's current proceed aspect is authorized for, as recorded by
	 * the last [setUpSpeed] / [setUpPath] call from the reservation flow. `null` while the signal
	 * is [Signal.STOP] (nothing authorized) and after [cancelPathSetup].
	 *
	 * Read by [authorizedDirection] / [isAllowingFor] so the canvas and the LLM dispatcher's
	 * `all_signal_aspects` can distinguish a proceed aspect authorized for the forward direction
	 * from one authorized for the opposite direction (Issue #812).
	 *
	 * Direct `signal =` writes that bypass [setUpSpeed] (interlocking-facade `clearSignal`,
	 * actuator `setSignalAspect`) leave these `null`; [authorizedDirection] then defaults to the
	 * canonical forward direction, which is correct for entry/facing signals.
	 */
	@kotlin.concurrent.Volatile
	private var authorizedFrom: Cell.Segment? = null

	@kotlin.concurrent.Volatile
	private var authorizedTo: Cell.Segment? = null

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
						"signal change: $oldSignal -> $newSignal at t=${Process.time()}"
				} else {
					""
				}
			}
			field = newSignal
			// A STOP signal authorizes nothing: drop the recorded reservation direction so the
			// next direct write (clearSignal/setSignalAspect without setUpSpeed) defaults cleanly
			// to the canonical forward direction via authorizedDirection(). setUpSpeed records a
			// fresh direction after setting a proceed aspect.
			if (newSignal == Signal.STOP) {
				authorizedFrom = null
				authorizedTo = null
			}
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
		checkPathSegments(from, to)
		signal = forSpeed(allowedSpeed)
		// Record the direction this proceed aspect was cleared for, so isAllowingFor /
		// authorizedDirection can report proceed only for that direction (Issue #812). Only
		// recorded for a proceed aspect — a STOP result (e.g. allowedSpeed == 0) authorizes
		// nothing, and the signal setter has already cleared any prior direction.
		if (signal.isAllowing()) {
			authorizedFrom = from
			authorizedTo = to
		}
		logger.debug {
			"SEMAPHORE_SIGNAL_UPDATED: ${staticRef.getName()} signal changed to $signal " +
				"(allowedSpeed=$allowedSpeed)"
		}
	}

	override fun allowedSpeed(): Double = signal.allowedSpeed()

	/**
	 * The (from, to) segment pair this semaphore's current proceed aspect is authorized for, or
	 * `null to null` when the signal is [Signal.STOP] (nothing is authorized).
	 *
	 * Backed by the [authorizedFrom]/[authorizedTo] pair recorded by [setUpSpeed] from the
	 * reservation flow. When the signal was set by a direct `signal =` write that bypassed
	 * [setUpSpeed] (interlocking-facade `clearSignal`, actuator `setSignalAspect`) and no
	 * direction was recorded, defaults to the canonical **forward** direction
	 * (`anti([direction()])` → [direction()]) — which is the correct authorization for an
	 * entry/facing signal cleared outside the reservation flow.
	 *
	 * [ConstantSemaphore] (InOut `outSemaphore`, predzvěst/narážník) never calls [setUpSpeed];
	 * its stored direction stays `null`, so it too reports the static forward — matching the
	 * always-proceed-for-facing-direction semantics of a constant semaphore.
	 *
	 * Consumers: [DefaultNetworkPerceptionPort.toReading] (so the LLM dispatcher's
	 * `all_signal_aspects` learns the actual reserved direction) and the canvas renderers via
	 * [isAllowingFor]. The train's own next-separator perception ([separatorAspect]) does NOT
	 * use it — the train is the reservation holder and always sees proceed-when-lit.
	 *
	 * @since Issue #812 (direction-aware signal display)
	 */
	open fun authorizedDirection(): Pair<Cell.Segment?, Cell.Segment?> {
		if (!signal.isAllowing()) return null to null
		val d = staticRef.direction()
		return if (authorizedFrom != null) authorizedFrom to authorizedTo else anti(d) to d
	}

	/**
	 * Returns true when this semaphore's current proceed aspect authorizes travel in the
	 * direction [from] → [to].
	 *
	 * Compares the query against the **stored reservation direction** ([authorizedDirection]),
	 * not the static orientation: a signal cleared for the reverse direction reports proceed for
	 * the reverse query and STOP for the forward query, and vice-versa. Returns false when the
	 * signal is [Signal.STOP]. When the direction is unknown (direct write, no [setUpSpeed]),
	 * [authorizedDirection] defaults to the canonical forward, so [isAllowingFor] authorizes the
	 * forward query only.
	 *
	 * Used by the canvas renderers (`AnimationStateCapture.captureSignalState`,
	 * `SimulationCellRenderer`) to paint a forward-facing semaphore STOP when its proceed aspect
	 * was cleared for the opposite direction — the Issue #812 perception-truthfulness fix. The
	 * train's own perception ([separatorAspect]) does not use this; it reads `signal` directly
	 * because the train is the reservation holder.
	 *
	 * @param from segment the approaching entity is travelling **from**
	 * @param to   segment the approaching entity is travelling **to**
	 * @return true if [signal] is currently allowing AND [from]/[to] match the direction the
	 *   aspect was actually cleared for (stored, defaulting to static forward)
	 * @since Issue #812 (direction-aware signal display)
	 */
	fun isAllowingFor(
		from: Cell.Segment?,
		to: Cell.Segment?
	): Boolean {
		if (!signal.isAllowing()) return false
		val (af, at) = authorizedDirection()
		return from == af && to == at
	}

	/**
	 * Validate that [from]/[to] are the two segments of this semaphore's block boundary,
	 * in either traversal order.
	 *
	 * ## Domain decision (Issue #566 / SP2b.9)
	 *
	 * A [DynamicRailSemaphore] marks a block boundary a train can legitimately cross from
	 * either side -- e.g. the vyhybna.xml shunting loop is explicitly designed for trains to
	 * traverse it in both directions (A→B and B→A), and [staticRef]'s `orientation` continues
	 * to drive path-finding preference (see `PathReservationService.reservePathToAny`'s
	 * same-side/opposite-side sorting) without restricting which direction the physical
	 * signal is allowed to clear for. Earlier revisions treated the "exit-side" pairing
	 * (`from == direction()`) as invalid and left the signal at STOP, which meant a fully
	 * granted, block/switch-reserved route through such a boundary could never actually be
	 * traversed -- `PathReservationService.reservePath` would report `Success` while the
	 * train stalled forever at that one semaphore (see
	 * `PathReservationServiceTest.SignalConfigurationTests`, "reservePath configures every
	 * semaphore along a full InOut-to-InOut path, not just START"). Both segment orderings
	 * are therefore accepted here; only a genuinely wrong (non-adjacent) segment pairing is
	 * rejected.
	 *
	 * @throws PathSeparatorChangeException if [from]/[to] are not this boundary's two segments
	 */
	private fun checkPathSegments(
		from: Cell.Segment?,
		to: Cell.Segment?
	) {
		val d = staticRef.direction()
		val antiD = anti(d)
		val validPairing = (to == d && from == antiD) || (from == d && to == antiD)
		if (!validPairing) {
			throw PathSeparatorChangeException("wrong aPath segments", this)
		}
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
