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

import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore.Signal
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
class DynamicRailSemaphore(
	val static: RailSemaphore
) {
	// Static properties delegated from wrapped object using Kotlin delegation
	val orientation: Boolean by static::getOrientation
	val spatialType: Cell.SpatialType by static::getSpatialType
	val name: String by static::getName
	
	/**
	 * Dynamic property: Current signal state (mutable, changes during simulation)
	 * 
	 * Initial state is STOP for safety.
	 */
	var signal: Signal = Signal.STOP
		private set
	
	/**
	 * Gets the current signal state (dynamic property)
	 * 
	 * @return Current signal
	 */
	fun getSignal(): Signal = signal
	
	/**
	 * Sets the signal state (dynamic property - changes during simulation)
	 * 
	 * @param newSignal The new signal state
	 */
	fun setSignal(newSignal: Signal) {
		logger.debug {
			if (this.signal != newSignal) {
				"Semaphore ${static.getName()} " +
					"signal change: ${this.signal} -> $newSignal at t=${jDisco.Process.time()}"
			} else {
				""
			}
		}
		this.signal = newSignal
	}
	
	/**
	 * Equality based on the static object (stable identity).
	 * 
	 * Two DynamicRailSemaphore instances are equal if they wrap the same
	 * static semaphore object, regardless of their current signal state.
	 * 
	 * This ensures stable identity for use in collections (Set, Map).
	 */
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is DynamicRailSemaphore) return false
		// Identity comparison (===) for stable equals based on static object
		return static === other.static
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
