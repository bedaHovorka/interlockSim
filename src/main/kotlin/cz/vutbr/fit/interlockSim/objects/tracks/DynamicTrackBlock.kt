/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import io.github.oshai.kotlinlogging.KotlinLogging
import jDisco.Process

private val logger = KotlinLogging.logger {}

/**
 * Dynamic wrapper for TrackBlock separating static and dynamic properties.
 *
 * **Static properties** (delegated from wrapped track block):
 * - length, maxSpeed, ends (via TrackFacility/Track interface)
 * - Block structure: sections, inner elements, joins (via TrackBlock interface)
 *
 * **Dynamic properties** (in this class):
 * - state (FREE/RESERVED/OCCUPIED)
 * - occupant (current train)
 * - reservation direction (which end reserved from)
 *
 * This wrapper uses the static TrackBlock object for:
 * - Stable identity (equals/hashCode based on static object)
 * - Immutable configuration (length, speed limits, topology, sections)
 * - Type compatibility with existing code
 *
 * ## Usage Example (Issue #277)
 *
 * ```kotlin
 * val graph = simulationContext.getGraph()  // Type-safe!
 * val dynamicBlock: DynamicTrackBlock = graph.get(point1, point2)
 *
 * // Access dynamic state directly
 * when (dynamicBlock.getState()) {
 *     State.FREE -> logger.info { "Track available" }
 *     State.RESERVED -> logger.info { "Path set up from ${dynamicBlock.reservedFrom}" }
 *     State.OCCUPIED -> logger.info { "Train present: ${dynamicBlock.occupant}" }
 * }
 *
 * // Access static configuration via delegation
 * val length = dynamicBlock.length()
 * val maxSpeed = dynamicBlock.maxSpeed(fromSeparator)
 *
 * // Unwrap to access underlying static block if needed
 * val staticBlock: TrackBlock = dynamicBlock.staticRef
 * ```
 *
 * Part of Issue #277: Graph parameterization with dynamic wrappers
 *
 * @property staticRef The static track block object with immutable editing-time properties
 */
class DynamicTrackBlock(
	val staticRef: TrackBlock
) : TrackBlock by staticRef,
	TrackSection,
	TrackFacility {
	/**
	 * TrackSection interface implementation.
	 * DynamicTrackBlock wraps a static TrackBlock, so it returns the static reference.
	 * This allows DynamicTrackBlock to act as both a TrackBlock and a TrackSection.
	 */
	override fun getTrackBlock(): TrackBlock = staticRef

	// ========== Dynamic properties ==========

	/**
	 * Dynamic property: Current state (FREE, RESERVED, or OCCUPIED)
	 *
	 * State transitions during simulation:
	 * - FREE -> RESERVED (when path is set up)
	 * - RESERVED -> OCCUPIED (when train enters)
	 * - OCCUPIED -> FREE (when train leaves)
	 */
	private var _state: TrackFacility.State = TrackFacility.State.FREE

	override fun getState(): TrackFacility.State = _state

	/**
	 * Dynamic property: Current occupant (train)
	 *
	 * Non-null when state is OCCUPIED, null otherwise.
	 */
	var occupant: TrackOccupant? = null
		private set

	/**
	 * Dynamic property: Reservation direction
	 *
	 * When state is RESERVED, indicates which end the path is reserved from.
	 * Null when state is FREE or OCCUPIED.
	 */
	var reservedFrom: PathSeparator? = null
		private set

	// ========== TrackFacility interface implementation (dynamic operations) ==========
	// Note: state property automatically provides getState() method required by TrackFacility interface

	/**
	 * Gets the current occupant (train)
	 *
	 * @return Current occupant, or throws if track is not occupied
	 * @throws IllegalStateException if track is not occupied
	 */
	override fun getTrackOccupant(): TrackOccupant {
		requireSimulationNotNull(occupant) {
			"TrackBlock occupant should not be null - must call when track is OCCUPIED"
		}
		return occupant!!
	}

	/**
	 * Train entering the track block
	 *
	 * Transitions state from RESERVED to OCCUPIED.
	 *
	 * @param newOccupant The train entering
	 * @throws IllegalStateException if track is already occupied (collision detection)
	 */
	override fun enter(newOccupant: TrackOccupant) {
		logger.info {
			"${Process.time()} TrackBlock ${staticRef.hashCode()} ENTRY: occupant=$newOccupant, state=${getState()}->OCCUPIED"
		}
		if (occupant != null) {
			logger.error {
				"${Process.time()} CONFLICT: TrackBlock ${staticRef.hashCode()} collision! " +
					"Existing occupant=$occupant, newOccupant=$newOccupant"
			}
		}
		requireSimulation(occupant == null) {
			"TrackBlock occupant collision - must be null on entry (shunting not implemented)"
		}
		assertGoodStateChange(TrackFacility.State.RESERVED, TrackFacility.State.OCCUPIED)
		occupant = newOccupant
		reservedFrom = null
	}

	/**
	 * Train leaving the track block
	 *
	 * Transitions state from OCCUPIED to FREE.
	 *
	 * @param leavingOccupant The train leaving (must match current occupant)
	 * @throws IllegalStateException if occupant doesn't match
	 */
	override fun leave(leavingOccupant: TrackOccupant) {
		logger.info {
			"${Process.time()} TrackBlock ${staticRef.hashCode()} EXIT: occupant=$leavingOccupant, state=OCCUPIED->FREE"
		}
		requireSimulation(occupant === leavingOccupant) {
			"TrackBlock occupant mismatch on leave"
		}
		assertGoodStateChange(TrackFacility.State.OCCUPIED, TrackFacility.State.FREE)
		occupant = null
	}

	/**
	 * Checks if track block is free from given separator
	 *
	 * @param sep The separator to check from
	 * @return true if track is FREE, false otherwise
	 */
	override fun isFreeFrom(sep: PathSeparator): Boolean {
		val isFree = getState() == TrackFacility.State.FREE
		logger.debug {
			"TrackBlock ${staticRef.hashCode()} isFreeFrom check: from=$sep, state=${getState()}, result=$isFree"
		}
		return isFree
	}

	/**
	 * Reserves the track block for a path
	 *
	 * Transitions state from FREE to RESERVED.
	 *
	 * @param sep The separator the path is being set up from
	 * @throws TrackOperationException if track is not FREE
	 */
	override fun setUpPath(sep: PathSeparator) {
		logger.info {
			"${Process.time()} TrackBlock ${staticRef.hashCode()} RESERVE: from=$sep, state=FREE->RESERVED"
		}
		if (getState() != TrackFacility.State.FREE) {
			logger.warn {
				"${Process.time()} CONFLICT: TrackBlock ${staticRef.hashCode()} reservation rejected - " +
					"state=${getState()}, occupant=$occupant, requested by=$sep"
			}
		}
		exceptionStateChange(TrackFacility.State.FREE, TrackFacility.State.RESERVED)
		reservedFrom = sep
	}

	/**
	 * Checks if path is set up from given separator
	 *
	 * @param sep The separator to check
	 * @return true if track is RESERVED from this separator, false otherwise
	 */
	override fun isSetUpPath(sep: PathSeparator): Boolean {
		requireSimulationNotNull(sep) { "Path separator must not be null" }
		val isSetUp: Boolean
		if (getState() == TrackFacility.State.RESERVED) {
			isSetUp = sep === reservedFrom
		} else {
			requireSimulation(reservedFrom == null) {
				"From separator must be null when state is not RESERVED"
			}
			isSetUp = false
		}
		logger.debug {
			"TrackBlock ${staticRef.hashCode()} isSetUpPath check: sep=$sep, state=${getState()}, " +
				"from=$reservedFrom, result=$isSetUp"
		}
		return isSetUp
	}

	/**
	 * Cancels the path reservation
	 *
	 * Transitions state from RESERVED to FREE.
	 *
	 * @param sep The separator the path was set up from
	 * @throws TrackOperationException if separator doesn't match or state is not RESERVED
	 */
	override fun cancelPathSetup(sep: PathSeparator) {
		logger.info {
			"${Process.time()} TrackBlock ${staticRef.hashCode()} RELEASE: from=$sep, state=RESERVED->FREE"
		}
		exceptionStateChange(TrackFacility.State.RESERVED, TrackFacility.State.FREE)
		if (sep !== reservedFrom) {
			throw TrackOperationException("wrong end on cancel", staticRef)
		}
		reservedFrom = null
	}

	// ========== Private helper methods for state transitions ==========

	private fun stateChange(
		from: TrackFacility.State,
		to: TrackFacility.State
	): Boolean {
		val ok = _state == from
		if (ok) _state = to
		return ok
	}

	@Throws(TrackOperationException::class)
	private fun exceptionStateChange(
		from: TrackFacility.State,
		to: TrackFacility.State
	) {
		if (!stateChange(from, to)) {
			logger.error {
				"${Process.time()} CONFLICT: TrackBlock ${staticRef.hashCode()} state violation - " +
					"expected=$from, actual=${getState()}, attempted=$to"
			}
			throw TrackOperationException(errorStateMessage(from), staticRef)
		}
	}

	private fun errorStateMessage(from: TrackFacility.State): String = "Wrong state: $_state , expected : $from"

	private fun assertGoodStateChange(
		from: TrackFacility.State,
		to: TrackFacility.State
	) {
		val stateChange = stateChange(from, to)
		requireSimulation(stateChange) { errorStateMessage(from) }
	}

	// ========== Identity and string representation ==========

	/**
	 * Equality based on the static object (stable identity).
	 *
	 * Two DynamicTrackBlock instances are equal if they wrap the same
	 * static track block object, regardless of their current state or occupant.
	 *
	 * This ensures stable identity for use in collections (Set, Map).
	 */
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is DynamicTrackBlock) return false
		// Identity comparison (===) for stable equals based on static object
		return staticRef === other.staticRef
	}

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
	override fun toString(): String =
		"DynamicTrackBlock[staticRef=$staticRef, state=${getState()}, occupant=$occupant, from=$reservedFrom]"
}
