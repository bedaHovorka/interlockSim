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

import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import io.github.oshai.kotlinlogging.KotlinLogging
import jDisco.Process

private val logger = KotlinLogging.logger {}

/**
 * Dynamic wrapper for Track (specifically TrackFacility) separating static and dynamic properties.
 * 
 * **Static properties** (delegated from wrapped track): length, maxSpeed, ends
 * **Dynamic properties** (in this class): state (FREE/RESERVED/OCCUPIED), occupant, reservation direction
 * 
 * This wrapper uses the static Track object for:
 * - Stable identity (equals/hashCode based on static object)
 * - Immutable configuration (length, speed limits, topology)
 * - Type compatibility with existing code
 * 
 * Part of Phase 4: Static/Dynamic property separation (bedaHovorka/interlockSim#92)
 * 
 * @property static The static track object with immutable editing-time properties
 */
class DynamicTrack(
	val static: TrackFacility
) {
	// Static properties delegated from wrapped object using Kotlin delegation
	val length: Double by static::length
	val ends: Array<PathSeparator> by static::ends
	
	/**
	 * Dynamic property: Current state (FREE, RESERVED, or OCCUPIED)
	 * 
	 * State transitions during simulation:
	 * - FREE -> RESERVED (when path is set up)
	 * - RESERVED -> OCCUPIED (when train enters)
	 * - OCCUPIED -> FREE (when train leaves)
	 */
	var state: TrackFacility.State = TrackFacility.State.FREE
		private set
	
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
	
	/**
	 * Gets the current state
	 * 
	 * @return Current state (FREE, RESERVED, or OCCUPIED)
	 */
	fun getState(): TrackFacility.State = state
	
	/**
	 * Gets the current occupant (train)
	 * 
	 * @return Current occupant, or throws if track is not occupied
	 * @throws IllegalStateException if track is not occupied
	 */
	fun getTrackOccupant(): TrackOccupant {
		requireSimulationNotNull(occupant) { 
			"Track occupant should not be null - must call when track is OCCUPIED" 
		}
		return occupant!!
	}
	
	/**
	 * Train entering the track
	 * 
	 * Transitions state from RESERVED to OCCUPIED.
	 * 
	 * @param newOccupant The train entering
	 * @throws IllegalStateException if track is already occupied (collision detection)
	 */
	fun enter(newOccupant: TrackOccupant) {
		logger.info {
			"${Process.time()} Block ${static.hashCode()} ENTRY: occupant=$newOccupant, state=$state->OCCUPIED"
		}
		if (occupant != null) {
			logger.error {
				"${Process.time()} CONFLICT: Block ${static.hashCode()} collision! " +
					"Existing occupant=$occupant, newOccupant=$newOccupant"
			}
		}
		requireSimulation(occupant == null) { 
			"Track occupant collision - must be null on entry (shunting not implemented)" 
		}
		assertGoodStateChange(TrackFacility.State.RESERVED, TrackFacility.State.OCCUPIED)
		occupant = newOccupant
		reservedFrom = null
	}
	
	/**
	 * Train leaving the track
	 * 
	 * Transitions state from OCCUPIED to FREE.
	 * 
	 * @param leavingOccupant The train leaving (must match current occupant)
	 * @throws IllegalStateException if occupant doesn't match
	 */
	fun leave(leavingOccupant: TrackOccupant) {
		logger.info {
			"${Process.time()} Block ${static.hashCode()} EXIT: occupant=$leavingOccupant, state=OCCUPIED->FREE"
		}
		requireSimulation(occupant === leavingOccupant) { 
			"Track occupant mismatch on leave" 
		}
		assertGoodStateChange(TrackFacility.State.OCCUPIED, TrackFacility.State.FREE)
		occupant = null
	}
	
	/**
	 * Checks if track is free from given separator
	 * 
	 * @param sep The separator to check from
	 * @return true if track is FREE, false otherwise
	 */
	fun isFreeFrom(sep: PathSeparator): Boolean {
		val isFree = state == TrackFacility.State.FREE
		logger.debug { 
			"Track ${static.hashCode()} isFreeFrom check: from=$sep, state=$state, result=$isFree" 
		}
		return isFree
	}
	
	/**
	 * Reserves the track for a path
	 * 
	 * Transitions state from FREE to RESERVED.
	 * 
	 * @param sep The separator the path is being set up from
	 * @throws TrackOperationException if track is not FREE
	 */
	fun setUpPath(sep: PathSeparator) {
		logger.info {
			"${Process.time()} Block ${static.hashCode()} RESERVE: from=$sep, state=FREE->RESERVED"
		}
		if (state != TrackFacility.State.FREE) {
			logger.warn {
				"${Process.time()} CONFLICT: Block ${static.hashCode()} reservation rejected - " +
					"state=$state, occupant=$occupant, requested by=$sep"
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
	fun isSetUpPath(sep: PathSeparator): Boolean {
		requireSimulationNotNull(sep) { "Path separator must not be null" }
		val isSetUp: Boolean
		if (state == TrackFacility.State.RESERVED) {
			isSetUp = sep === reservedFrom
		} else {
			requireSimulation(reservedFrom == null) { 
				"From separator must be null when state is not RESERVED" 
			}
			isSetUp = false
		}
		logger.debug { 
			"Track ${static.hashCode()} isSetUpPath check: sep=$sep, state=$state, " +
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
	fun cancelPathSetup(sep: PathSeparator) {
		logger.info {
			"${Process.time()} Block ${static.hashCode()} RELEASE: from=$sep, state=RESERVED->FREE"
		}
		exceptionStateChange(TrackFacility.State.RESERVED, TrackFacility.State.FREE)
		if (sep !== reservedFrom) {
			throw TrackOperationException("wrong end on cancel", static)
		}
		reservedFrom = null
	}
	
	// Private helper methods for state transitions
	
	private fun stateChange(
		from: TrackFacility.State,
		to: TrackFacility.State
	): Boolean {
		val ok = state == from
		if (ok) state = to
		return ok
	}
	
	@Throws(TrackOperationException::class)
	private fun exceptionStateChange(
		from: TrackFacility.State,
		to: TrackFacility.State
	) {
		if (!stateChange(from, to)) {
			logger.error {
				"${Process.time()} CONFLICT: Block ${static.hashCode()} state violation - " +
					"expected=$from, actual=$state, attempted=$to"
			}
			throw TrackOperationException(errorStateMessage(from), static)
		}
	}
	
	private fun errorStateMessage(from: TrackFacility.State): String = 
		"Wrong state: $state , expected : $from"
	
	private fun assertGoodStateChange(
		from: TrackFacility.State,
		to: TrackFacility.State
	) {
		val stateChange = stateChange(from, to)
		requireSimulation(stateChange) { errorStateMessage(from) }
	}
	
	/**
	 * Equality based on the static object (stable identity).
	 * 
	 * Two DynamicTrack instances are equal if they wrap the same
	 * static track object, regardless of their current state or occupant.
	 * 
	 * This ensures stable identity for use in collections (Set, Map).
	 */
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is DynamicTrack) return false
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
	override fun toString(): String = 
		"Dynamic[length=$length, state=$state, occupant=$occupant, from=$reservedFrom]"
}
