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
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import io.github.oshai.kotlinlogging.KotlinLogging
import cz.hovorka.kdisco.Process

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
 * - trainId (reservation ownership identifier)
 *
 * ## TrainId vs Occupant Design (Issue #294)
 *
 * This class tracks TWO separate but related concepts:
 *
 * 1. **trainId: String?** - Reservation ownership (WHO owns the block)
 *    - Available during RESERVED and OCCUPIED states
 *    - Set when path is reserved (before train physically enters)
 *    - Used for: conflict detection, path release, registry tracking
 *    - Benefits: Early reservation, registry operations without object references, clearer logging
 *
 * 2. **occupant: TrackOccupant?** - Physical presence (WHAT is on the block)
 *    - Only available during OCCUPIED state
 *    - Set when train physically enters the block
 *    - Used for: collision detection, train movement tracking, physics calculations
 *    - Benefits: Direct reference to train object for state queries
 *
 * **Why String-based trainId instead of TrackOccupant?**
 *
 * - **Early reservation**: Path can be reserved before train object exists
 * - **Decoupling**: Registry operations don't need full object graph
 * - **Atomic operations**: Conflict detection via simple string comparison
 * - **Path release**: Can release by ID without train object reference
 * - **Logging clarity**: String IDs more readable than object references in logs
 * - **Lifecycle independence**: Reservation and occupancy are separate concerns
 *
 * **State lifecycle example:**
 * ```
 * FREE:     trainId=null,     occupant=null
 *   ↓ setUpPathWithTrainId("train123", separator)
 * RESERVED: trainId="train123", occupant=null        // Reserved but not yet occupied
 *   ↓ enter(trainObject)
 * OCCUPIED: trainId="train123", occupant=trainObject // Both ownership and presence tracked
 *   ↓ leave(trainObject)
 * FREE:     trainId=null,     occupant=null
 * ```
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
	val staticRef: TrackBlock,
	private val end1: DynamicPathSeparator,
	private val end2: DynamicPathSeparator
) : TrackBlock by staticRef,
	TrackSection,
	TrackFacility {
	/**
	 * TrackSection interface implementation. Return this
	 */
	override fun getTrackBlock(): TrackBlock = this

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
	var reservedFrom: DynamicPathSeparator? = null
		private set

	/**
	 * Dynamic property: Train identifier (reservation ownership)
	 *
	 * String-based identifier indicating which train owns/reserved the block.
	 * Separate from `occupant` which tracks physical presence.
	 *
	 * ## When Available
	 *
	 * - **RESERVED**: trainId set, occupant null (path reserved, train not yet present)
	 * - **OCCUPIED**: trainId set, occupant non-null (train owns and occupies block)
	 * - **FREE**: trainId null, occupant null
	 *
	 * ## Why String, Not TrackOccupant?
	 *
	 * Reservation and occupancy are independent concerns:
	 * - Reservation happens BEFORE train enters (trainId set, occupant null)
	 * - String ID enables PathReservationRegistry operations without object references
	 * - Conflict detection via simple string comparison
	 * - Path release by ID without train object
	 *
	 * ## Lifecycle
	 *
	 * - FREE → RESERVED: set to train identifier
	 * - RESERVED → OCCUPIED: **remains set** (preserves ownership across state transition)
	 * - OCCUPIED → FREE: cleared to null
	 * - RESERVED → FREE: cleared to null (path cancelled)
	 *
	 * ## Contrast with Occupant
	 *
	 * | Property  | Type            | Available When        | Purpose                        |
	 * |-----------|-----------------|----------------------|--------------------------------|
	 * | trainId   | String?         | RESERVED or OCCUPIED | Ownership tracking, registry   |
	 * | occupant  | TrackOccupant?  | OCCUPIED only        | Physical presence, collision   |
	 *
	 * @see occupant for physical train presence tracking
	 * @see PathReservationRegistry which uses trainId for conflict detection
	 * @since Issue #294 (Phase 2 of Issue #292)
	 */
	var trainName: String? = null
		private set

	// ========== TrackFacility interface implementation (dynamic operations) ==========
	// Note: state property automatically provides getState() method required by TrackFacility interface

	/**
	 * Gets the current occupant (train)
	 *
	 * @return Current occupant, or throws if track is not occupied
	 * @throws IllegalStateException if track is not occupied
	 */
	override fun getTrackOccupant(): TrackOccupant? = occupant

	override fun ends(): Array<PathSeparator> = arrayOf(end1, end2)

	override fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection? {
		if (current == null) return this
		if (current == this || current == staticRef) return null
		throw IllegalArgumentException("dynamictrackblock: current must be only this or null")
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
			"${Process.time()} TrackBlock ${staticRef.hashCode()} ENTRY: " +
				"occupant=$newOccupant, state=${getState()}->OCCUPIED, trainId=$trainName"
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

		// IMPORTANT: trainId remains set from reservation phase (setUpPathWithTrainId).
		// This preserves ownership tracking across the RESERVED → OCCUPIED transition.
		// The train reserved this block earlier (trainId set, occupant null), and now
		// physically enters it (occupant set, trainId preserved).
		// trainId will be cleared only on exit (OCCUPIED → FREE).
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
			"${Process.time()} TrackBlock ${staticRef.hashCode()} EXIT: " +
				"occupant=$leavingOccupant, state=OCCUPIED->FREE, trainId=$trainName"
		}
		requireSimulation(occupant === leavingOccupant) {
			"TrackBlock occupant mismatch on leave"
		}
		assertGoodStateChange(TrackFacility.State.OCCUPIED, TrackFacility.State.FREE)
		occupant = null
		trainName = null
	}

	/**
	 * Checks if track block is free from given separator
	 *
	 * @param sep The separator to check from
	 * @return true if track is FREE, false otherwise
	 */
	override fun isFreeFrom(sep: DynamicPathSeparator): Boolean {
		val isFree = getState() == TrackFacility.State.FREE
		logger.debug {
			"TrackBlock ${staticRef.hashCode()} isFreeFrom check: from=$sep, state=${getState()}, result=$isFree"
		}
		return isFree
	}

	/**
	 * Reserves the track block for a path with optional train identifier.
	 *
	 * Transitions state from FREE to RESERVED.
	 *
	 * @param from The separator the path is being set up from
	 * @param reservingTrainId Train identifier for reservation tracking
	 * @throws TrackOperationException if track is not FREE
	 */
	override fun setUpPath(
		from: DynamicPathSeparator,
		reservingTrainId: String
	) {
		// Handle idempotent case: block already reserved from same separator
		// This is needed because paths can contain the same block multiple times
		// (e.g., switch "around" blocks appear twice in path definition)
		if (getState() == TrackFacility.State.RESERVED) {
			if (reservedFrom === from) {
				// Already reserved from this separator - idempotent operation, just return
				logger.debug {
					"${Process.time()} TrackBlock ${staticRef.hashCode()} already reserved from $from (idempotent), trainId=$trainName"
				}
				return
			} else {
				// Reserved from different separator - this is a conflict!
				logger.warn {
					"${Process.time()} CONFLICT: TrackBlock ${staticRef.hashCode()} reservation conflict - " +
						"already reserved from=$reservedFrom by trainId=$trainName, new request from=$from by trainId=$reservingTrainId"
				}
				throw TrackReservationException.AlreadyReservedConflict(this, reservedFrom!!, from)
			}
		}

		// Normal case: FREE → RESERVED
		logger.info {
			"${Process.time()} TrackBlock ${staticRef.hashCode()} RESERVE: " +
				"from=$from, state=FREE->RESERVED, trainId=$reservingTrainId"
		}
		exceptionStateChange(TrackFacility.State.FREE, TrackFacility.State.RESERVED, "setUpPath")
		reservedFrom = from
		trainName = reservingTrainId
	}

	/**
	 * Checks if path is set up from given separator
	 *
	 * @param from The separator to check
	 * @return true if track is RESERVED from this separator, false otherwise
	 */
	override fun isSetUpPath(from: DynamicPathSeparator): Boolean {
		requireSimulationNotNull(from) { "Path separator must not be null" }
		val isSetUp: Boolean
		if (getState() == TrackFacility.State.RESERVED) {
			isSetUp = from === reservedFrom
		} else {
			requireSimulation(reservedFrom == null) {
				"From separator must be null when state is not RESERVED"
			}
			isSetUp = false
		}
		logger.debug {
			"TrackBlock ${staticRef.hashCode()} isSetUpPath check: sep=$from, state=${getState()}, " +
				"from=$reservedFrom, result=$isSetUp"
		}
		return isSetUp
	}

	/**
	 * Cancels the path reservation
	 *
	 * Transitions state from RESERVED to FREE.
	 *
	 * @param from The separator the path was set up from
	 * @throws TrackOperationException if separator doesn't match or state is not RESERVED
	 */
	override fun cancelPathSetup(from: DynamicPathSeparator) {
		logger.info {
			"${Process.time()} TrackBlock ${staticRef.hashCode()} RELEASE: from=$from, state=RESERVED->FREE, trainId=$trainName"
		}
		exceptionStateChange(TrackFacility.State.RESERVED, TrackFacility.State.FREE, "cancelPathSetup")
		if (from !== reservedFrom) {
			throw TrackOperationException("wrong end on cancel", staticRef)
		}
		reservedFrom = null
		trainName = null
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

	@Throws(TrackReservationException::class)
	private fun exceptionStateChange(
		from: TrackFacility.State,
		to: TrackFacility.State,
		operation: String
	) {
		if (!stateChange(from, to)) {
			logger.error {
				"${Process.time()} CONFLICT: TrackBlock ${staticRef.hashCode()} state violation - " +
					"expected=$from, actual=${getState()}, attempted=$to"
			}
			throw TrackReservationException.InvalidStateTransition(this, getState(), operation)
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
	override fun hashCode(): Int = staticRef.hashCode()

	/**
	 * String representation for debugging
	 */
	override fun toString(): String =
		"DynamicTrackBlock[staticRef=$staticRef, state=${getState()}, " +
			"occupant=$occupant, from=$reservedFrom, trainId=$trainName]"
}
