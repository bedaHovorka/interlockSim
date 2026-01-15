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
	val static: InOut,
	val inSemaphore: DynamicRailSemaphore,
	val outSemaphore: DynamicRailSemaphore
) : OrientedPathSeparator by static, DynamicPathSeparator {
	// Static properties delegated from wrapped object
	val name: String
		get() = static.getName()
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
			is DynamicInOut -> static === other.static
			is InOut -> static === other
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

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? {
		if (from == null) return static.direction()
		requireSimulation(from === static.direction()) { "Invalid segment: $from, expected: ${static.direction()}" }
		return null
	}

	/**
	 * Hash code based on the static object (stable hash code).
	 *
	 * Uses identity hash code of the static object to ensure:
	 * - Consistency with equals()
	 * - Stability across state changes
	 * - Proper behavior in hash-based collections
	 */
	override fun hashCode(): Int = System.identityHashCode(static)

	/**
	 * String representation for debugging
	 */
	override fun toString(): String = "Dynamic[$name]"

	private fun getSemaphoreFor(
		from: Cell.Segment?,
		to: Cell.Segment?
	): DynamicRailSemaphore? {
		if (from == null && to == static.direction()) return inSemaphore
		if (to == null && from == static.direction()) return outSemaphore
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
