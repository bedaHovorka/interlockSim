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

import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement

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
) : OrientedPathSeparator by staticRef, DynamicPathSeparator {
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
		return when (other) {
			is DynamicInOut -> staticRef === other.staticRef
			is InOut -> staticRef === other
			else -> false
		}
	}

	override fun setUpPath(
		from: Cell.Segment?,
		to: Cell.Segment?,
		allowedSpeed: Double
	) {
		val sem = getSemaphoreForWithException(from, to)
		sem.signal = forSpeed(allowedSpeed)
	}

	override fun cancelPathSetup(
		from: Cell.Segment?,
		to: Cell.Segment?
	) {
		val sem = getSemaphoreForWithException(from, to)
		sem.signal = Signal.STOP
	}

	override fun allowedSpeed(): Double = PathElement.ABSOLUTE_MAX_SPEED

	/**
	 * Implementation of isSwitch() for DynamicInOut.
	 * Returns false since this is an InOut point, not a switch.
	 */
	override fun isSwitch(): Boolean = false

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? {
		if (from == null) return staticRef.direction()
		requireSimulation(from === staticRef.direction()) { "Invalid segment: $from, expected: ${staticRef.direction()}" }
		return null
	}

	/**
	 * Implementation of asRailSemaphore() for DynamicInOut.
	 * Returns the output semaphore of the static InOut reference.
	 */
	override fun asRailSemaphore(): RailSemaphore = staticRef.getOutSemaphore()

	/**
	 * Hash code based on the static object (stable hash code).
	 *
	 * Uses identity hash code of the static object to ensure:
	 * - Consistency with equals()
	 * - Stability across state changes
	 * - Proper behavior in hash-based collections
	 */
	override fun hashCode(): Int = System.identityHashCode(staticRef)

	/**
	 * String representation for debugging
	 */
	override fun toString(): String = "Dynamic[$name]"

	private fun getSemaphoreFor(
		from: Cell.Segment?,
		to: Cell.Segment?
	): DynamicRailSemaphore? {
		if (from == null && to == staticRef.direction()) return inSemaphore
		if (to == null && from == staticRef.direction()) return outSemaphore
		return null
	}

	@Throws(PathSeparatorChangeException::class)
	private fun getSemaphoreForWithException(
		from: Cell.Segment?,
		to: Cell.Segment?
	): DynamicRailSemaphore {
		return getSemaphoreFor(from, to) ?: throw PathSeparatorChangeException(this)
	}
}
