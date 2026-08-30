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

import cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant

/**
 * Dynamic wrapper for InOut separating static and dynamic properties.
 *
 * **Static properties** (delegated from wrapped InOut): name, orientation, spatialType
 * **Dynamic properties** (via semaphores): Signal states of inSemaphore (via DynamicRailSemaphore)
 *
 * This wrapper uses the static InOut object for:
 * - Stable identity (equals/hashCode based on static object)
 * - Immutable configuration (name, position, orientation)
 * - Type compatibility with existing code
 *
 * The dynamic state is primarily in the embedded semaphores, so this wrapper
 * mainly provides access to DynamicRailSemaphore wrappers for the in/out semaphores.
 *
 * Part of Phase 4: Static/Dynamic property separation (bedaHovorka/interlockSim#92)
 *
 * @property static The static InOut object with immutable editing-time properties
 * @property inSemaphore Dynamic wrapper for the input semaphore
 * @property outSemaphore Dynamic wrapper for the output semaphore
 */
class DynamicInOut(
	val staticRef: InOut,
	val inSemaphore: DynamicRailSemaphore,
	val outSemaphore: DynamicRailSemaphore
) : OrientedPathSeparator by staticRef,
	DynamicPathSeparator {
	// Static properties delegated from wrapped object
	val name: String
		get() = staticRef.getName()
	// orientation and direction() are delegated from OrientedPathSeparator

	/**
	 * Equality based on the static object (stable identity).
	 *
	 * Two DynamicInOut instances are equal if they wrap the same
	 * static InOut object, regardless of their semaphore signal states.
	 * Also supports comparison with static InOut objects directly.
	 *
	 * This ensures stable identity for use in collections (Set, Map) and
	 * compatibility with code that uses static objects (e.g., ShuntingLoop).
	 */
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other == null) return false

		// Compare with DynamicInOut (compare staticRef references)
		if (other is DynamicInOut) {
			return staticRef === other.staticRef
		}

		// Compare with static InOut (compare this.staticRef with other)
		if (other is InOut) {
			return staticRef === other
		}

		return false
	}

	override fun setUpPath(
		from: Cell.Segment?,
		to: Cell.Segment?,
		allowedSpeed: Double,
		trackOccupant: TrackOccupant
	) {
		val sem = getSemaphoreForWithException(from, to)
		sem.setUpPath(from, to, allowedSpeed, trackOccupant)
	}

	override fun cancelPathSetup(
		from: Cell.Segment?,
		to: Cell.Segment?
	) {
		val sem = getSemaphoreForWithException(from, to)
		sem.cancelPathSetup(from, to)
	}

	override fun allowedSpeed(): Double = ABSOLUTE_MAX_SPEED

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? {
		if (from == null) return staticRef.direction()
		requireSimulation(from === staticRef.direction()) { "Invalid segment: $from, expected: ${staticRef.direction()}" }
		return null
	}

	/**
	 * Implementation of asRailSemaphore() for DynamicInOut.
	 * Returns the output semaphore of the static InOut reference.
	 */
	override fun asRailSemaphore(): RailSemaphore = staticRef.outSemaphore

	/**
	 * Hash code based on the static object (stable hash code).
	 *
	 * Delegates to staticRef.hashCode(). Safe because InOut (and all NodeCell/TrackFacility
	 * subtypes used as staticRef) do NOT override hashCode() — they inherit Object.hashCode()
	 * which returns the identity hash code. This is equivalent to System.identityHashCode(staticRef).
	 */
	override fun hashCode(): Int = staticRef.hashCode()

	/**
	 * String representation for debugging
	 */
	override fun toString(): String = "Dynamic[$name]"

	/**
	 * Get the track connection direction for InOut.
	 *
	 * Delegates to the static InOut's getTrackConnectionDirection() method.
	 *
	 * @return Track connection direction
	 * @see InOut.getTrackConnectionDirection
	 */
	fun getTrackConnectionDirection(): Cell.Segment = staticRef.getTrackConnectionDirection()

	private fun getSemaphoreFor(
		from: Cell.Segment?,
		to: Cell.Segment?
	): DynamicRailSemaphore? {
		if (from == null && to == staticRef.direction()) return inSemaphore
		if (to == null && from == staticRef.direction()) return outSemaphore
		return null
	}

	private fun getSemaphoreForWithException(
		from: Cell.Segment?,
		to: Cell.Segment?
	): DynamicRailSemaphore = getSemaphoreFor(from, to) ?: throw PathSeparatorChangeException(this)
}
