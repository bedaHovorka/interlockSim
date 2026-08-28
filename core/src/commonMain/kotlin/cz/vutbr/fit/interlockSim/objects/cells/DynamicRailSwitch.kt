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
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Dynamic wrapper for RailSwitch separating static and dynamic properties.
 *
 * **Static properties** (delegated from wrapped switch): type, speeds, topology, spatialType
 * **Dynamic properties** (in this class): current configuration (MAIN/BRANCH), locked state
 *
 * This wrapper uses the static RailSwitch object for:
 * - Stable identity (equals/hashCode based on static object)
 * - Immutable configuration (type, speeds, topology)
 * - Type compatibility with existing code
 *
 * Part of Phase 4: Static/Dynamic property separation (bedaHovorka/interlockSim#92)
 *
 * **Property change events:**
 * - "conf" property: Fired when configuration changes (MAIN ↔ BRANCH)
 *   - `changeConf()`: Always fires event (always toggles position)
 *   - `setUpPath()`: Fires event only if new configuration differs from current
 * - "locked" property: Fired when lock state changes
 *   - `lock()`/`unlock()`: Fire event only if state actually changes
 *
 * @property static The static switch object with immutable editing-time properties
 */
class DynamicRailSwitch(
	val staticRef: RailSwitch
) : PathSeparator by staticRef,
	DynamicPathSeparator {
	// Static properties delegated from wrapped object
	val type: RailSwitch.Type
		get() = staticRef.type
	val name: String
		get() = staticRef.getName()

	/**
	 * Dynamic property: Current configuration (MAIN or BRANCH)
	 *
	 * Mutable during simulation as trains set paths through the switch.
	 * Access via `conf` property. Use `changeConf()` to toggle position.
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
	 * Listeners for switch state changes.
	 * Copy-on-write list — @Volatile guarantees cross-thread visibility.
	 */
	@kotlin.concurrent.Volatile
	private var listeners: List<ContextPropertyChangeListener> = emptyList()

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
			"${Process.time()} Switch ${staticRef.hashCode()} position change: $oldConf -> $conf"
		}
		listeners.forEach { it.propertyChange(ContextChangeEvent("conf", oldConf, conf)) }
	}

	override fun cancelPathSetup(
		from: Cell.Segment?,
		to: Cell.Segment?
	) {
		val pathConf = getPathConfWithException(from, to)
		if (pathConf != conf) {
			throw PathSeparatorChangeException("cancelPathSetup: Switch is not configured (neccesarry except?)", this)
		}
		// Tier 1: Unlock switch after canceling path setup (Issue #291)
		unlock()
		logger.debug {
			"${Process.time()} Switch ${this.hashCode()} unlocked after cancelPathSetup"
		}
	}

	override fun allowedSpeed(): Double {
		val double1 = staticRef.speeds.get(conf)
		requireSimulationNotNull(double1) { "Speed for configuration must not be null: speeds=${staticRef.speeds}" }
		return double1
	}

	override fun setUpPath(
		from: Cell.Segment?,
		to: Cell.Segment?,
		allowedSpeed: Double,
		trackOccupant: TrackOccupant
	) {
		val oldConf = conf
		val newConf = getPathConfWithException(from, to)
		logger.info {
			"${Process.time()} Switch ${this.hashCode()} path setup: from=$from to=$to, " +
				"conf=$newConf, allowedSpeed=$allowedSpeed"
		}
		conf = newConf
		if (oldConf != newConf) {
			listeners.forEach { it.propertyChange(ContextChangeEvent("conf", oldConf, newConf)) }
		}
		// Tier 1: Lock switch after configuration (Issue #291)
		lock()
		logger.info {
			"${Process.time()} Switch ${this.hashCode()} LOCKED after setUpPath, locked=$locked"
		}
	}

	private fun getPathConfWithException(
		from: Cell.Segment?,
		to: Cell.Segment?
	): Conf {
		// Java would NPE here if from or to are null - we make it explicit
		if (from == null || to == null) {
			throw PathSeparatorChangeException("switch segments cannot be null", this)
		}
		return staticRef.confs.get(from, to) ?: throw PathSeparatorChangeException(
			"switch doesn't join this segments",
			this
		)
	}

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? {
		val map = staticRef.confs.getJoinedNodesAndEdges(from)

		for ((segment, configuration) in map.entries) {
			if (configuration == conf) return segment
		}
		return null
	}

	/**
	 * Locks the switch to prevent position changes during train movement.
	 *
	 * Safety property SI-5: Switch cannot toggle during train movement.
	 * Idempotent: Safe to call multiple times. Event only fired if state changes.
	 */
	fun lock() {
		if (locked) return // Already locked, no change needed

		val oldLocked = locked
		locked = true
		logger.debug {
			"${Process.time()} Switch ${staticRef.hashCode()} locked"
		}
		listeners.forEach { it.propertyChange(ContextChangeEvent("locked", oldLocked, locked)) }
	}

	/**
	 * Unlocks the switch to allow position changes.
	 *
	 * Safety property SI-5: Switch cannot toggle during train movement.
	 * Idempotent: Safe to call multiple times. Event only fired if state changes.
	 */
	fun unlock() {
		if (!locked) return // Already unlocked, no change needed

		val oldLocked = locked
		locked = false
		logger.debug {
			"${Process.time()} Switch ${staticRef.hashCode()} unlocked"
		}
		listeners.forEach { it.propertyChange(ContextChangeEvent("locked", oldLocked, locked)) }
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
	 * Registers a listener to be notified of switch state changes.
	 *
	 * **Thread Safety Note**: This method is synchronized to allow safe listener registration
	 * from multiple threads, even though the simulation context itself is not thread-safe.
	 *
	 * @param listener the listener to add
	 */
	fun addPropertyChangeListener(listener: ContextPropertyChangeListener) {
		listeners = listeners + listener
	}

	/**
	 * Unregisters a listener from receiving switch state change notifications.
	 */
	fun removePropertyChangeListener(listener: ContextPropertyChangeListener) {
		listeners = listeners - listener
	}

	/**
	 * Equality based on the static object (stable identity).
	 *
	 * Two DynamicRailSwitch instances are equal if they wrap the same
	 * static switch object, regardless of their current configuration or lock state.
	 * Also supports comparison with static RailSwitch objects directly.
	 *
	 * This ensures stable identity for use in collections (Set, Map) and
	 * compatibility with code that uses static objects (e.g., ShuntingLoop).
	 */
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other == null) return false

		// Compare with DynamicRailSwitch (compare staticRef references)
		if (other is DynamicRailSwitch) {
			return staticRef === other.staticRef
		}

		// Compare with static RailSwitch (compare this.staticRef with other)
		if (other is RailSwitch) {
			return staticRef === other
		}

		return false
	}

	/**
	 * Hash code based on the static object (stable hash code).
	 *
	 * Uses identity hash code of the static object to ensure:
	 * - Consistency with equals()
	 * - Stability across configuration changes
	 * - Proper behavior in hash-based collections
	 */
	override fun hashCode(): Int = staticRef.hashCode()

	/**
	 * Returns the segment pair that forms the current active path.
	 *
	 * Queries the switch topology to find which segments are connected
	 * based on the current configuration (MAIN or BRANCH).
	 *
	 * Performance: O(n) where n = number of segments in switch (typically 3-4).
	 * Currently adequate; if rendering performance becomes an issue (called from
	 * SimulationCellRenderer.draw() on every frame), consider caching the result
	 * and invalidating on configuration changes.
	 *
	 * @return Set of 2 segments forming the active path based on current conf
	 * @throws IllegalStateException if no segments found for current configuration
	 */
	fun getActiveSegments(): Set<Cell.Segment> {
		// Iterate through all segments that join in this switch
		for (segment in staticRef.joins()) {
			// Get all edges connected to this segment
			val joinedEdges = staticRef.confs.getJoinedNodesAndEdges(segment)

			// Search for the edge with value matching current conf
			for ((connectedSegment, configuration) in joinedEdges.entries) {
				if (configuration == conf) {
					return setOf(segment, connectedSegment)
				}
			}
		}

		// This should never happen if the switch is properly initialized
		throw IllegalStateException(
			"No segments found for configuration $conf in switch $name. " +
				"This indicates a corrupted switch topology."
		)
	}

	/**
	 * String representation for debugging
	 */
	override fun toString(): String = "Dynamic[$name, conf=$conf, locked=$locked]"
}
