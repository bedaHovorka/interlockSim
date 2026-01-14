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

import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import io.github.oshai.kotlinlogging.KotlinLogging
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

private val logger = KotlinLogging.logger {}

/**
 * Dynamic wrapper for RailSwitch separating static and dynamic properties.
 * 
 * **Static properties** (from wrapped switch): type, speeds (MAIN/BRANCH), topology, spatialType
 * **Dynamic properties** (in this class): current configuration (MAIN/BRANCH), locked state
 * 
 * This wrapper uses the static RailSwitch object for:
 * - Stable identity (equals/hashCode based on static object)
 * - Immutable configuration (type, speeds, topology)
 * - Type compatibility with existing code
 * 
 * Part of Phase 4: Static/Dynamic property separation (bedaHovorka/interlockSim#92)
 * 
 * @property static The static switch object with immutable editing-time properties
 */
class DynamicRailSwitch(
	val static: RailSwitch
) {
	/**
	 * Dynamic property: Current configuration (MAIN or BRANCH)
	 * 
	 * Mutable during simulation as trains set paths through the switch.
	 */
	var conf: Conf = Conf.MAIN
		private set
	
	/**
	 * Dynamic property: Lock state
	 * 
	 * When locked, switch cannot change configuration (safety property SI-5:
	 * switch cannot toggle during train movement).
	 */
	var locked: Boolean = false
		private set
	
	/**
	 * Property change support for observing state changes
	 */
	private val propertyChangeSupport: PropertyChangeSupport = PropertyChangeSupport(this)
	
	/**
	 * Gets the current configuration (MAIN or BRANCH)
	 * 
	 * @return Current configuration
	 */
	fun getConf(): Conf = conf
	
	/**
	 * Changes the switch configuration to the opposite position.
	 * 
	 * @throws IllegalStateException if switch is locked (safety property SI-5)
	 */
	fun changeConf() {
		if (locked) {
			throw IllegalStateException(
				"Cannot change switch configuration while locked " +
					"(safety SI-5: switch cannot toggle during train movement)"
			)
		}
		val oldConf = conf
		conf = if (conf == Conf.MAIN) Conf.BRANCH else Conf.MAIN
		logger.info {
			"${jDisco.Process.time()} Switch ${static.hashCode()} position change: $oldConf -> $conf"
		}
	}
	
	/**
	 * Locks the switch to prevent position changes during train movement.
	 * 
	 * Safety property SI-5: Switch cannot toggle during train movement.
	 */
	fun lock() {
		val oldLocked = locked
		locked = true
		logger.debug {
			"${jDisco.Process.time()} Switch ${static.hashCode()} locked"
		}
		propertyChangeSupport.firePropertyChange("locked", oldLocked, locked)
	}
	
	/**
	 * Unlocks the switch to allow position changes.
	 * 
	 * Safety property SI-5: Switch cannot toggle during train movement.
	 */
	fun unlock() {
		val oldLocked = locked
		locked = false
		logger.debug {
			"${jDisco.Process.time()} Switch ${static.hashCode()} unlocked"
		}
		propertyChangeSupport.firePropertyChange("locked", oldLocked, locked)
	}
	
	/**
	 * Checks if switch is locked
	 * 
	 * @return true if switch is locked, false otherwise
	 */
	fun isLocked(): Boolean = locked
	
	/**
	 * Checks if switch is in MAIN (normal) configuration
	 * 
	 * @return true if configuration is MAIN, false otherwise
	 */
	fun isNormal(): Boolean = conf == Conf.MAIN
	
	/**
	 * Checks if switch is in BRANCH (reverse) configuration
	 * 
	 * @return true if configuration is BRANCH, false otherwise
	 */
	fun isReverse(): Boolean = conf == Conf.BRANCH
	
	/**
	 * Registers a PropertyChangeListener to be notified of switch state changes.
	 * 
	 * @param listener the PropertyChangeListener to add
	 */
	fun addPropertyChangeListener(listener: PropertyChangeListener) {
		propertyChangeSupport.addPropertyChangeListener(listener)
	}
	
	/**
	 * Unregisters a PropertyChangeListener from receiving switch state change notifications.
	 * 
	 * @param listener the PropertyChangeListener to remove
	 */
	fun removePropertyChangeListener(listener: PropertyChangeListener) {
		propertyChangeSupport.removePropertyChangeListener(listener)
	}
	
	/**
	 * Equality based on the static object (stable identity).
	 * 
	 * Two DynamicRailSwitch instances are equal if they wrap the same
	 * static switch object, regardless of their current configuration or lock state.
	 * 
	 * This ensures stable identity for use in collections (Set, Map).
	 */
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is DynamicRailSwitch) return false
		// Identity comparison (===) for stable equals based on static object
		return static === other.static
	}
	
	/**
	 * Hash code based on the static object (stable hash code).
	 * 
	 * Uses identity hash code of the static object to ensure:
	 * - Consistency with equals()
	 * - Stability across configuration changes
	 * - Proper behavior in hash-based collections
	 */
	override fun hashCode(): Int = System.identityHashCode(static)
	
	/**
	 * String representation for debugging
	 */
	override fun toString(): String = "Dynamic[${static.hashCode()}, conf=$conf, locked=$locked]"
}
