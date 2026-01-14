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
 * @property dynamicInSemaphore Dynamic wrapper for the input semaphore
 * @property dynamicOutSemaphore Dynamic wrapper for the output semaphore
 */
class DynamicInOut(
	val static: InOut,
	val dynamicInSemaphore: DynamicRailSemaphore,
	val dynamicOutSemaphore: DynamicRailSemaphore
) {
	// Static properties delegated from wrapped object
	val name: String
		get() = static.getName()
	val orientation: Boolean
		get() = static.getOrientation()
	val spatialType: Cell.SpatialType
		get() = static.getSpatialType()

	/**
	 * Gets the dynamic input semaphore
	 *
	 * @return Dynamic wrapper for the input semaphore
	 */
	fun getInSemaphore(): DynamicRailSemaphore = dynamicInSemaphore

	/**
	 * Gets the dynamic output semaphore
	 *
	 * @return Dynamic wrapper for the output semaphore
	 */
	fun getOutSemaphore(): DynamicRailSemaphore = dynamicOutSemaphore

	/**
	 * Equality based on the static object (stable identity).
	 *
	 * Two DynamicInOut instances are equal if they wrap the same
	 * static InOut object, regardless of their semaphore signal states.
	 *
	 * This ensures stable identity for use in collections (Set, Map).
	 */
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is DynamicInOut) return false
		// Identity comparison (===) for stable equals based on static object
		return static === other.static
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
}
