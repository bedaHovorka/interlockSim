/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.ksimulantenbande.kdisco.Condition
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyNotifier
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Registry tracking train ownership of reserved track blocks.
 *
 * ## Purpose
 *
 * Maintains bidirectional mapping between trains and their reserved track blocks:
 * - Train ID → List of reserved blocks
 * - Block → Owning train ID
 *
 * This enables:
 * - Conflict detection (block already reserved by different train)
 * - Atomic rollback (release all blocks when reservation fails)
 * - Path release (free all blocks reserved by a train)
 *
 * ## Usage Pattern (New Atomic API)
 *
 * ```kotlin
 * val registry = PathReservationRegistry(context)
 *
 * // Attempt atomic registration
 * when (val result = registry.registerAtomic("train123", listOf(block1, block2, block3))) {
 *     is RegistrationResult.Success -> {
 *         // All blocks registered successfully
 *     }
 *     is RegistrationResult.Conflict -> {
 *         // Conflict detected: result.conflictingBlock, result.existingOwner
 *         // No blocks were registered (all-or-nothing)
 *     }
 * }
 *
 * // Check ownership
 * val owner = registry.getOwner(block1)  // returns "train123"
 *
 * // Release all blocks for a train
 * val released = registry.unregister("train123")
 * ```
 *
 * ## Thread Safety
 *
 * **NOT thread-safe.** All operations assume single-threaded access.
 *
 * @param context Simulation context (needed for PathInfo merging with ArrayPath)
 * @since Issue #294 (Phase 2 of Issue #292)
 * @since Issue #296 Phase 8 (PathInfo extension fix)
 */
class PathReservationRegistry(
	private val context: SimulationContext
) : BlockOccupancyNotifier {
	/**
	 * Registered external listeners for block occupancy/release events.
	 * Copy-on-write snapshot guarantees stable iteration even if a listener
	 * unsubscribes while an event is being dispatched.
	 */
	@kotlin.concurrent.Volatile
	private var occupancyListeners: List<BlockOccupancyListener> = emptyList()

	/**
	 * Result of an atomic registration attempt.
	 *
	 * This sealed class provides type-safe result handling for registration operations,
	 * replacing exception-based control flow with explicit result types.
	 */
	sealed class RegistrationResult {
		/**
		 * All blocks successfully registered.
		 *
		 * When this result is returned, all blocks in the registration request
		 * have been added to the registry and are now owned by the train.
		 */
		data object Success : RegistrationResult()

		/**
		 * Registration conflict detected.
		 *
		 * At least one block in the registration request was already owned by
		 * a different train. No blocks were registered (all-or-nothing semantics).
		 *
		 * @property conflictingBlock The first block that caused the conflict
		 * @property existingOwner The train that already owns the conflicting block
		 * @property reason Why the conflict occurred (reserved vs physically occupied)
		 */
		data class Conflict(
			val conflictingBlock: DynamicTrackBlock,
			val existingOwner: String,
			val reason: ConflictReason = ConflictReason.RESERVED_BY_OTHER_TRAIN
		) : RegistrationResult()
	}

	/**
	 * Reason for a registration conflict.
	 *
	 * Distinguishes between two distinct safety-critical failure modes:
	 * - **RESERVED_BY_OTHER_TRAIN**: The block is reserved (but not yet occupied) by another train.
	 * - **OCCUPIED_BY_OTHER_TRAIN**: The block is currently physically occupied by another train.
	 *
	 * Callers can use this code to decide whether to retry, wait, or escalate.
	 *
	 * @since Issue #581 (Goal 1 SP2)
	 */
	enum class ConflictReason {
		/** Block already reserved by a different train. */
		RESERVED_BY_OTHER_TRAIN,

		/** Block currently physically occupied by a different train. */
		OCCUPIED_BY_OTHER_TRAIN
	}

	/**
	 * Mapping: Train ID → List of reserved blocks
	 */
	private val trainToBlocks = mutableMapOf<String, MutableList<DynamicTrackBlock>>()

	/**
	 * Mapping: Block → Owning train ID
	 */
	private val blockToTrain = mutableMapOf<DynamicTrackBlock, String>()

	/**
	 * Mapping: Train ID → List of reserved switches (Tier 2)
	 *
	 * Tracks railway switches that are locked for specific trains.
	 * Switches are locked during path reservation and unlocked during release.
	 *
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock
	 */
	private val trainToSwitches = mutableMapOf<String, MutableList<DynamicRailSwitch>>()

	/**
	 * Mapping: Switch → Owning train ID (Tier 2)
	 *
	 * Reverse mapping to quickly determine which train owns a given switch.
	 * Used for conflict detection during path reservation.
	 *
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock
	 */
	private val switchToTrain = mutableMapOf<DynamicRailSwitch, String>()

	/**
	 * Mapping: Train ID → PathInfo metadata
	 *
	 * Stores complete path information including entry directions for each train.
	 * This enables TrainNavigationService to determine correct direction at switches.
	 *
	 * @since Issue #295/#296 Phase 3
	 */
	private val trainToPathInfo = mutableMapOf<String, PathInfo>()

	/**
	 * Subscribe an external agent to block occupancy/release events.
	 *
	 * The listener is invoked synchronously from the simulation event loop whenever
	 * a [DynamicTrackBlock] changes occupancy or reservation state.
	 */
	override fun addBlockOccupancyListener(listener: BlockOccupancyListener) {
		occupancyListeners = occupancyListeners + listener
	}

	/**
	 * Unsubscribe a previously registered external agent.
	 */
	override fun removeBlockOccupancyListener(listener: BlockOccupancyListener) {
		occupancyListeners = occupancyListeners - listener
	}

	/**
	 * Dispatch a block occupancy event to all registered external listeners.
	 */
	override fun emit(event: BlockOccupancyEvent) {
		val snapshot = occupancyListeners
		snapshot.forEach { it.onBlockOccupancyChanged(event) }
	}

	/**
	 * Atomically register blocks for a train.
	 *
	 * Performs an atomic check-and-reserve: it validates that every block is
	 * available, then registers all blocks. If any conflict is detected, no
	 * blocks are registered (all-or-nothing semantics). This makes the operation
	 * safe even when multiple trains attempt to reserve the same resource during
	 * the same simulation step: the first caller succeeds, subsequent callers see
	 * the updated ownership and receive a deterministic [RegistrationResult.Conflict].
	 *
	 * ## Atomicity Guarantee
	 *
	 * This operation is atomic with respect to the registry state:
	 * - Either all blocks are registered
	 * - Or no blocks are registered
	 *
	 * ## Conflict Detection
	 *
	 * A conflict occurs when any block is already owned by a **different** train.
	 * The check consults, in order:
	 * 1. The registry's `blockToTrain` mapping.
	 * 2. The block's own `trainName` reservation field (defence in depth).
	 * 3. The block's current physical `occupant` (defence in depth).
	 *
	 * If the same train attempts to register the same block again, it is allowed
	 * (idempotent operation).
	 *
	 * ## Conflict Reason
	 *
	 * The returned [RegistrationResult.Conflict] includes a [ConflictReason] that
	 * tells the caller whether the block is merely reserved by another train or is
	 * currently physically occupied by another train. Occupied conflicts are more
	 * severe and callers should typically wait longer before retrying.
	 *
	 * ## Usage Example
	 *
	 * ```kotlin
	 * when (val result = registry.registerAtomic("train1", blocks)) {
	 *     is RegistrationResult.Success -> {
	 *         logger.info { "All blocks registered" }
	 *     }
	 *     is RegistrationResult.Conflict -> {
	 *         logger.warn {
	 *             "Block ${result.conflictingBlock} owned by ${result.existingOwner} " +
	 *             "(reason=${result.reason})"
	 *         }
	 *         rollbackBlockReservations(blocks)
	 *     }
	 * }
	 * ```
	 *
	 * @param trainId The train identifier
	 * @param blocks List of blocks to register
	 * @return Success if all registered, Conflict with details if any conflict
	 */
	fun registerAtomic(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	): RegistrationResult {
		// Phase 1: Validate no conflicts
		for (block in blocks) {
			val registryOwner = blockToTrain[block]
			if (registryOwner != null && registryOwner != trainId) {
				return RegistrationResult.Conflict(block, registryOwner, classifyConflict(block))
			}

			// Defence in depth: also check the block's own reservation state in case
			// the registry and the dynamic block have diverged (e.g. manual cleanup).
			val blockOwner = block.trainName
			if (blockOwner != null && blockOwner != trainId) {
				return RegistrationResult.Conflict(block, blockOwner, classifyConflict(block))
			}

			// Defence in depth: a block may be occupied even if the registry has no
			// record of it. Reject the reservation to avoid entering an occupied block.
			val occupant = block.occupant
			if (occupant != null && occupant.name != trainId) {
				return RegistrationResult.Conflict(
					block,
					occupant.name,
					ConflictReason.OCCUPIED_BY_OTHER_TRAIN
				)
			}
		}

		// Phase 2: All checks passed - register all blocks
		val blockList = trainToBlocks.getOrPut(trainId) { mutableListOf() }
		blocks.forEach { block ->
			if (block !in blockList) {
				blockList.add(block)
			}
			blockToTrain[block] = trainId
		}

		return RegistrationResult.Success
	}

	/**
	 * Classify a conflict based on the block's physical state.
	 *
	 * @return [ConflictReason.OCCUPIED_BY_OTHER_TRAIN] if the block currently has
	 *         a physical occupant, [ConflictReason.RESERVED_BY_OTHER_TRAIN] otherwise.
	 */
	private fun classifyConflict(block: DynamicTrackBlock): ConflictReason =
		if (block.occupant != null) {
			ConflictReason.OCCUPIED_BY_OTHER_TRAIN
		} else {
			ConflictReason.RESERVED_BY_OTHER_TRAIN
		}

	/**
	 * Register blocks as reserved by a train.
	 *
	 * **DEPRECATED:** Use registerAtomic() for better error handling.
	 *
	 * This creates bidirectional mappings for all blocks in the list.
	 * If the train already has reserved blocks, the new blocks are added to the existing list.
	 *
	 * ## Preconditions
	 *
	 * - No block in the list should be already owned by a different train
	 * - Caller is responsible for validating blocks are FREE before registration
	 *
	 * ## State Changes
	 *
	 * For each block:
	 * - Adds block to trainToBlocks[trainId]
	 * - Sets blockToTrain[block] = trainId
	 *
	 * @param trainId The train identifier (typically Train.getClass().getSimpleName() + instance)
	 * @param blocks List of blocks to register as reserved
	 * @throws IllegalStateException if any block is already registered to a different train
	 * @deprecated Use registerAtomic() for explicit result handling instead of exceptions
	 */
	@Deprecated(
		message = "Use registerAtomic() for better error handling",
		replaceWith = ReplaceWith("registerAtomic(trainId, blocks)")
	)
	fun register(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	) {
		// Delegate to registerAtomic() and convert result to exception for backward compatibility
		when (val result = registerAtomic(trainId, blocks)) {
			is RegistrationResult.Success -> return
			is RegistrationResult.Conflict -> {
				throw IllegalStateException(
					"Block ${result.conflictingBlock} already reserved by ${result.existingOwner} " +
						"(attempted reservation by $trainId)"
				)
			}
		}
	}

	/**
	 * Unregister all blocks reserved by a train.
	 *
	 * Removes all bidirectional mappings for the given train, regardless of block state.
	 * This is used for simulation cleanup, test scenarios, and forced release.
	 *
	 * ## State Changes
	 *
	 * - Removes trainToBlocks[trainId]
	 * - Removes blockToTrain[block] for all blocks owned by this train
	 * - Removes trainToPathInfo[trainId] (Issue #295/#296)
	 *
	 * @param trainId The train identifier
	 * @return List of blocks that were released (empty if train had no reservations)
	 */
	fun unregister(trainId: String): List<DynamicTrackBlock> {
		val blocks = trainToBlocks[trainId] ?: return emptyList()

		// Remove all blocks from mappings (regardless of state)
		blocks.forEach { block ->
			blockToTrain.remove(block)
		}

		// Remove train entry and PathInfo
		trainToBlocks.remove(trainId)
		trainToPathInfo.remove(trainId)

		logger.debug {
			"unregister: Released ${blocks.size} blocks for '$trainId'"
		}

		return blocks.toList()
	}

	/**
	 * Unregister a single block for a train.
	 *
	 * Removes the block from registry mappings if it is FREE (no occupant).
	 * This is called automatically when a train's Tail leaves a block, ensuring
	 * blocks are cleaned up as soon as they become available for subsequent trains.
	 *
	 * ## Preconditions
	 *
	 * - Block must be FREE (no occupant, state == FREE)
	 * - Block must be registered to the given train
	 *
	 * ## State Changes
	 *
	 * If block is FREE and owned by trainId:
	 * - Removes block from trainToBlocks[trainId]
	 * - Removes blockToTrain[block]
	 * - If this was the last block, removes trainToBlocks[trainId] (but keeps trainToPathInfo[trainId])
	 *
	 * ## PathInfo Lifecycle (Issue #301 Fix)
	 *
	 * PathInfo represents the train's **intended path** (where it's going) and is semantically
	 * separate from the train's **current reservations** (which blocks it owns). A train needs
	 * PathInfo for navigation queries even after all blocks are unregistered (e.g., when the
	 * tail has cleared all blocks but the front is requesting the next path segment).
	 *
	 * PathInfo is only deleted in:
	 * - `releaseTrainReservations()` - when train completes its journey
	 * - `unregister()` - explicit full unregistration
	 * - `clear()` - clear all registrations
	 *
	 * ## Use Case
	 *
	 * Called by Train's Tail process after calling block.leave():
	 * ```kotlin
	 * current.leave(this@Train)
	 * env.unregisterBlock(trainId, current)
	 * ```
	 *
	 * @param trainId The train identifier
	 * @param block The block to unregister
	 * @return true if block was unregistered, false if block is still occupied or not owned
	 */
	fun unregisterBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean {
		// Validate block is owned by this train
		val owner = blockToTrain[block]
		if (owner != trainId) {
			logger.debug {
				"unregisterBlock: Block $block not owned by '$trainId' (owner='$owner')"
			}
			return false
		}

		// Only unregister if block is FREE
		if (block.occupant != null || block.getState() != TrackFacility.State.FREE) {
			logger.debug {
				"unregisterBlock: Block $block still occupied (occupant=${block.occupant}, " +
					"state=${block.getState()})"
			}
			return false
		}

		// Remove from mappings
		blockToTrain.remove(block)
		trainToBlocks[trainId]?.remove(block)

		val remainingBlocks = trainToBlocks[trainId]?.size ?: 0
		logger.debug {
			"unregisterBlock: Released block $block for '$trainId', $remainingBlocks blocks remaining"
		}

		// If no blocks remain, remove train entry from trainToBlocks
		// BUT keep PathInfo for navigation queries (Issue #301 - deadlock fix)
		if (trainToBlocks[trainId]?.isEmpty() == true) {
			trainToBlocks.remove(trainId)
			logger.debug {
				"unregisterBlock: All blocks released for '$trainId', " +
					"removed from trainToBlocks (PathInfo retained for navigation)"
			}
		}

		return true
	}

	/**
	 * Get the train ID that owns a block.
	 *
	 * @param block The block to query
	 * @return Train ID if block is registered, null otherwise
	 */
	fun getOwner(block: DynamicTrackBlock): String? = blockToTrain[block]

	/**
	 * Check whether a block is available for a new reservation.
	 *
	 * A block is considered available when:
	 * - Its dynamic state is [TrackFacility.State.FREE]
	 * - It has no registry owner
	 * - It has no physical occupant
	 *
	 * This predicate is used by [createBlockAvailableCondition] to build kDisco
	 * [Condition]s that wake a waiting process as soon as a block is released.
	 *
	 * @param block The block to check
	 * @return true if the block can be reserved by another train
	 * @since Issue #582 (Goal 1 SP3)
	 */
	fun isBlockAvailable(block: DynamicTrackBlock): Boolean =
		block.getState() == TrackFacility.State.FREE &&
			blockToTrain[block] == null &&
			block.occupant == null

	/**
	 * Create a kDisco [Condition] that becomes true when [block] is released.
	 *
	 * The condition is deterministic: kDisco re-evaluates all wait notices after
	 * every discrete event (including the block-release event that happens when
	 * [unregister] or [unregisterBlock] removes the owner). A process can suspend
	 * with [cz.ksimulantenbande.kdisco.Process.waitUntil] and resume without busy-polling.
	 *
	 * @param block The block to wait for
	 * @return A condition evaluating to true once the block is free
	 * @since Issue #582 (Goal 1 SP3)
	 */
	fun createBlockAvailableCondition(block: DynamicTrackBlock): Condition = Condition { isBlockAvailable(block) }

	/**
	 * Get the current physical occupant of a block.
	 *
	 * This reflects the actual train object currently on the block, which is
	 * independent from the reservation owner. A block may be reserved but not yet
	 * occupied, or (in exceptional cases) occupied without a registry record.
	 *
	 * @param block The block to query
	 * @return The train currently occupying the block, or null if it is unoccupied
	 * @since Issue #581 (Goal 1 SP2)
	 */
	fun getOccupant(block: DynamicTrackBlock): TrackOccupant? = block.occupant

	/**
	 * Get the train ID of the current physical occupant of a block.
	 *
	 * @param block The block to query
	 * @return Train ID if block is currently occupied, null otherwise
	 * @since Issue #581 (Goal 1 SP2)
	 */
	fun getOccupantName(block: DynamicTrackBlock): String? = block.occupant?.name

	/**
	 * Check whether a block is currently physically occupied.
	 *
	 * @param block The block to check
	 * @return true if a train is currently on the block, false otherwise
	 * @since Issue #581 (Goal 1 SP2)
	 */
	fun isOccupied(block: DynamicTrackBlock): Boolean = block.occupant != null

	/**
	 * Get all blocks currently physically occupied by a train.
	 *
	 * @param trainId The train identifier
	 * @return List of blocks currently occupied by this train (empty if none)
	 * @since Issue #581 (Goal 1 SP2)
	 */
	fun getOccupiedBlocks(trainId: String): List<DynamicTrackBlock> =
		trainToBlocks[trainId]?.filter { it.occupant?.name == trainId } ?: emptyList()

	/**
	 * Register switches as reserved by a train (Tier 2).
	 *
	 * This creates bidirectional mappings for all switches in the list and locks them.
	 * If the train already has reserved switches, the new switches are added to the existing list.
	 *
	 * ## Preconditions
	 *
	 * - No switch in the list should be already owned by a different train
	 * - Caller is responsible for validating switches are not locked by another train
	 *
	 * ## State Changes
	 *
	 * For each switch:
	 * - Adds switch to trainToSwitches[trainId]
	 * - Sets switchToTrain[switch] = trainId
	 * - Locks the switch
	 *
	 * @param trainId The train identifier
	 * @param switches List of switches to register as reserved
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Tier 2
	 */
	fun registerSwitches(
		trainId: String,
		switches: List<DynamicRailSwitch>
	) {
		val switchList = trainToSwitches.getOrPut(trainId) { mutableListOf() }
		switches.forEach { switch ->
			if (switch !in switchList) {
				switchList.add(switch)
				// Lock switch when first registered to this train
				switch.lock()
				logger.info {
					"registerSwitches: Locked switch ${switch.hashCode()} for '$trainId', locked=${switch.locked}"
				}
			}
			switchToTrain[switch] = trainId
		}

		logger.info {
			"registerSwitches: Registered ${switches.size} switches for '$trainId'"
		}
	}

	/**
	 * Unregister all switches reserved by a train (Tier 2).
	 *
	 * Removes all bidirectional mappings for the given train's switches and unlocks them.
	 * This is used for simulation cleanup and forced release.
	 *
	 * ## State Changes
	 *
	 * - Unlocks all switches
	 * - Removes trainToSwitches[trainId]
	 * - Removes switchToTrain[switch] for all switches owned by this train
	 *
	 * @param trainId The train identifier
	 * @return List of switches that were released (empty if train had no switches)
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Tier 2
	 */
	fun unregisterSwitches(trainId: String): List<DynamicRailSwitch> {
		val switches = trainToSwitches[trainId] ?: return emptyList()

		// Unlock and remove all switches from mappings
		switches.forEach { switch ->
			switch.unlock()
			switchToTrain.remove(switch)
			logger.info {
				"unregisterSwitches: Unlocked switch ${switch.hashCode()} for '$trainId', locked=${switch.locked}"
			}
		}

		// Remove train entry
		trainToSwitches.remove(trainId)

		logger.info {
			"unregisterSwitches: Released ${switches.size} switches for '$trainId'"
		}

		return switches.toList()
	}

	/**
	 * Unregister a SINGLE switch reserved by a train (Tier 2).
	 *
	 * Symmetric per-switch counterpart to [unregisterBlock], for scoped rollback of a
	 * rejected candidate path whose switches were already registered (e.g. a signal-config
	 * failure after [registerSwitches] succeeded, Issue #742 SP0.11 review follow-up).
	 * Unlike [unregisterSwitches] (which removes ALL of the train's switches), this only
	 * releases the one switch — leaving the train's earlier-hop switches intact.
	 *
	 * ## State Changes
	 *
	 * - Unlocks the switch (if owned by [trainId])
	 * - Removes `switchToTrain[switch]`
	 * - Removes the switch from `trainToSwitches[trainId]` (collapsing the list if empty)
	 *
	 * @param trainId The train identifier
	 * @param switch The switch to release
	 * @return `true` if the switch was owned by [trainId] and released, `false` otherwise
	 *   (not owned, or owned by a different train — both are safe no-ops for rollback)
	 * @since Issue #742 SP0.11 review follow-up
	 */
	fun unregisterSwitch(
		trainId: String,
		switch: DynamicRailSwitch
	): Boolean {
		if (switchToTrain[switch] != trainId) {
			logger.debug {
				"unregisterSwitch: Switch ${switch.hashCode()} not owned by '$trainId' " +
					"(owner='${switchToTrain[switch]}')"
			}
			return false
		}
		switch.unlock()
		switchToTrain.remove(switch)
		trainToSwitches[trainId]?.remove(switch)
		if (trainToSwitches[trainId]?.isEmpty() == true) {
			trainToSwitches.remove(trainId)
		}
		logger.debug {
			"unregisterSwitch: Released switch ${switch.hashCode()} for '$trainId'"
		}
		return true
	}

	/**
	 * Get all switches registered to a train (Tier 2).
	 *
	 * @param trainId The train identifier
	 * @return List of switches owned by the train (empty if none)
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Tier 2
	 */
	fun getSwitches(trainId: String): List<DynamicRailSwitch> = trainToSwitches[trainId]?.toList() ?: emptyList()

	/**
	 * Get the train ID that owns a switch (Tier 2).
	 *
	 * @param switch The switch to query
	 * @return Train ID if switch is registered, null otherwise
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Tier 2
	 */
	fun getSwitchOwner(switch: DynamicRailSwitch): String? = switchToTrain[switch]

	/**
	 * Get all blocks reserved by a train.
	 *
	 * @param trainId The train identifier
	 * @return List of blocks reserved by this train (empty if no reservations)
	 */
	fun getBlocks(trainId: String): List<DynamicTrackBlock> = trainToBlocks[trainId]?.toList() ?: emptyList()

	/**
	 * Check if a block is registered to any train.
	 *
	 * @param block The block to check
	 * @return true if block is registered, false otherwise
	 */
	fun isRegistered(block: DynamicTrackBlock): Boolean = blockToTrain.containsKey(block)

	/**
	 * Register PathInfo for a train.
	 *
	 * ## PathInfo Extension (Issue #296 Phase 8)
	 *
	 * Instead of overwriting the old PathInfo, this method **extends** it by merging:
	 * - Appends new path elements to old path (preserves Tail navigation)
	 * - Updates target to new target (allows Front to progress)
	 * - Merges entry directions (new overwrites old for conflicts)
	 *
	 * This fixes the "Train Tail Double-Leave Bug" where overwriting PathInfo caused
	 * the Tail to lose track of its position, leading to wrong-direction navigation.
	 *
	 * @param trainId The train identifier
	 * @param newPathInfo Complete path metadata to register or merge
	 * @since Issue #295/#296 Phase 3
	 * @since Issue #296 Phase 8 (PathInfo extension fix)
	 */
	fun registerPathInfo(
		trainId: String,
		newPathInfo: PathInfo
	) {
		val oldPathInfo = trainToPathInfo[trainId]

		if (oldPathInfo == null) {
			// First registration, just store it
			logger.debug {
				"registerPathInfo: first registration for '$trainId', storing PathInfo " +
					"(start=${newPathInfo.start}, target=${newPathInfo.target}, " +
					"path length=${newPathInfo.reservedPath.size})"
			}
			trainToPathInfo[trainId] = newPathInfo
			return
		}

		// Merge old and new PathInfo (pass trainId for switch cleanup)
		val mergedPathInfo = mergePathInfo(trainId, oldPathInfo, newPathInfo)
		trainToPathInfo[trainId] = mergedPathInfo

		logger.debug {
			"registerPathInfo: merged PathInfo for '$trainId' " +
				"(old: ${oldPathInfo.start}→${oldPathInfo.target}, " +
				"new: ${newPathInfo.start}→${newPathInfo.target}, " +
				"merged: ${mergedPathInfo.start}→${mergedPathInfo.target}, " +
				"path length: ${oldPathInfo.reservedPath.size} + ${newPathInfo.reservedPath.size} " +
				"= ${mergedPathInfo.reservedPath.size})"
		}

		// Log merged PathInfo (debug level for normal operation)
		logger.debug {
			"registerPathInfo: Created/merged PathInfo for '$trainId': " +
				"start=${mergedPathInfo.start}, target=${mergedPathInfo.target}, " +
				"path length=${mergedPathInfo.reservedPath.size}"
		}
	}

	private fun addElementWithCycleDetection(
		element: cz.vutbr.fit.interlockSim.objects.core.PathElement,
		mergedPath: ArrayPath,
		trainId: String,
		old: PathInfo,
		new: PathInfo
	): PathInfo? {
		if (element is cz.vutbr.fit.interlockSim.objects.core.PathSeparator &&
			mergedPath.any { it == element }
		) {
			val occurrenceCount = mergedPath.count { it == element }
			if (occurrenceCount >= 2) {
				// 3rd occurrence — infinite loop detected (Issue #316)
				logger.warn {
					"mergePathInfo: merge for train $trainId would create cycle (separator $element at 3+ occurrences). " +
						"Keeping existing valid PathInfo unchanged. " +
						"(old: ${old.start}→${old.target}, new: ${new.start}→${new.target})"
				}
				return old
			}
			// 2nd occurrence — legitimate circular route
			logger.info {
				"mergePathInfo: LEGITIMATE CIRCULAR ROUTE - separator $element appears ${occurrenceCount + 1}x " +
					"in path (train '$trainId' progressing through circular shunting loop). " +
					"Allowing (old: ${old.start}→${old.target}, new: ${new.start}→${new.target})"
			}
		}
		mergedPath.add(element)
		return null
	}

	/**
	 * Merge two PathInfo instances by extending old path with new path.
	 *
	 * ## This method NEVER throws (Issue #834)
	 *
	 * Every rejection is a **fail-safe abort**: log a WARN and `return old` unchanged. There are
	 * three of them — non-contiguous start, duplicated new start, and the 3rd-occurrence cycle
	 * guard — and they all behave identically from the caller's point of view.
	 *
	 * This is not stylistic. `registerPathInfo` is called from
	 * [DefaultPathReservationService.reservePath] Step 2i and from its already-owned early return,
	 * in both cases **after** blocks are reserved, switches locked and a START signal cleared, on
	 * the kDisco simulation thread, with no rollback and nothing catching a throw
	 * (`DispatchDecisionApplier.onControlStep` catches only `IllegalArgumentException`). An
	 * exception here therefore did not fail a run loudly — it killed the simulation thread while
	 * the run still wrote a well-formed result file, i.e. it fabricated a measurement.
	 *
	 * **Why "never throw" is safe on its own, without releasing anything at abort time.** An abort
	 * leaves an *orphaned RESERVED tail*: blocks this train owns that the stored PathInfo does not
	 * cover, some of them behind a cleared aspect. That state is not permanent, because it is
	 * exactly what `OrphanReservationSweeper` is for — it detects a route held un-travelled past
	 * its staleness threshold and hands it to
	 * `RegistryPartialRouteReleaser.releaseUntravelledTail`, which frees the tail's blocks *and*
	 * drives every semaphore governing them back to STOP (G1) before they become available to
	 * anyone else. So the worst an abort can produce is a temporarily over-reserved, temporarily
	 * over-permissive tail that the sweeper reclaims fail-safe; a thrown exception, by contrast, is
	 * unrecoverable for the whole run. Releasing the tail *here* instead would be the
	 * transactionally complete answer (the PR #901 standard applied to `reservePath`'s last
	 * un-rolled-back exit) but it changes the trade-off recorded under "Resource Safety" below and
	 * is deliberately filed as separate work.
	 *
	 * **Scope of that reclamation (review finding #8, Issue #834).** `OrphanReservationSweeper`
	 * lives in `dispatcher-agent` and is wired only in `desktop-ui`/`dispatcher-agent` (via
	 * `ExampleRegistry`). `:fast-sim` wires `wireSynchronousDispatcher(ctx, loop)` with
	 * `interlockingFacade = null` — the legacy `reservePath` → `mergePathInfo` branch — and does
	 * **not** wire the sweeper; neither does a bare-`:core` host that calls `reservePath` directly.
	 * In those hosts the orphaned tail persists for the rest of the run: the abort is still an
	 * improvement over the prior throw (no simulation thread dies), but the fail-safe reclamation
	 * the paragraph above describes is a `desktop-ui`/`dispatcher-agent` property, not a universal
	 * one. Releasing the tail in `mergePathInfo` itself — the transactionally-complete option the
	 * previous paragraph already files as "separate work" — is the only fix that would hold in
	 * `:fast-sim` and bare-`:core` too.
	 *
	 * ## Algorithm
	 *
	 * 0. Abort unless the new path continues the stored one (new.start == old.target)
	 * 1. Abort unless new.start appears exactly once in new.reservedPath
	 * 2. Create new ArrayPath and add all elements from old path
	 * 3. Find overlap point (old.target == new.start)
	 * 4. Add elements from new path, skipping first occurrence if it overlaps
	 * 5. Distinguish legitimate circular routes from infinite loop bugs
	 * 6. Merge entry directions (new overwrites old for same blocks)
	 *
	 * ## Example
	 *
	 * ```
	 * old: B → zB → vB → doB1  (Tail at B, Front at doB1)
	 * new: doB1 → k1 → A       (Front reserves forward to A)
	 * merged: B → zB → vB → doB1 → k1 → A  (complete path for both Front and Tail)
	 * ```
	 *
	 * ## Preconditions (each one aborts the merge rather than throwing)
	 *
	 * - `new.start == old.target` — the new path must CONTINUE the stored one. A path that starts
	 *   elsewhere is not merged: concatenating it yields two adjacent `PathSeparator`s, which every
	 *   navigation reader handles safely (`ArrayPath.getNext` returns `deque[i+2]` only when it
	 *   `is TrackSection`, so it returns `null`; `DefaultTrainNavigationService`'s
	 *   `determineNextFromPathInfo` hits its `is PathSeparator -> null` arm) — so there is no
	 *   teleport and no weakening of the `allowingSignal` gate, navigation simply truncates at
	 *   `old.target`. The damage is the orphaned RESERVED tail described above, not a safety
	 *   violation. Note this is a *merge* precondition only: it is unrelated to
	 *   `DefaultPathReservationService.rejectNonContiguousStart`, which tests the route origin
	 *   against the train's physical block footprint and must stay permissive enough for entry,
	 *   bidirectional reversal (PR #356) and post-partial-release re-reservation.
	 * - `new.start` appears exactly ONCE in `new.reservedPath` (at the beginning)
	 * - Circular routes are ALLOWED if train completes one full loop back to original start
	 * - Repeated cycles (>1 loop) are REJECTED to prevent infinite loops
	 *
	 * ## Circular Route Handling
	 *
	 * **Shunting Loop Scenario (LEGITIMATE):**
	 * - Train starts at A, travels A → B → C → A (one complete loop)
	 * - old.start = A, new path returns to A
	 * - This is ALLOWED: train completes circular shunting operation
	 *
	 * **Infinite Loop Bug (REJECTED):**
	 * - Train oscillates: A → B → A → B → A (repeated back-and-forth)
	 * - Separator would appear 3+ times in merged path
	 * - The entire merge is ABORTED and the original `old` PathInfo is returned unchanged
	 *   (Issue #316 fix: a truncated PathInfo ending mid-path is worse than keeping the valid original)
	 *
	 * ## Entry Direction Merging
	 *
	 * When old and new have the same block with different entry directions,
	 * the NEW direction overwrites the old (most recent direction is used).
	 *
	 * ## Resource Safety
	 *
	 * `mergePathInfo()` is a **pure data-structure operation** — it does not acquire or
	 * release track blocks or switches. All resource locking happens in
	 * `DefaultPathReservationService.reservePath()` via `registerAtomic()` and
	 * `registerSwitches()`, which use their own tracking maps (`trainToBlocks` /
	 * `trainToSwitches`). Those maps are independent of PathInfo, so `releasePath()` can
	 * still find and free all resources even when `return old` aborts the PathInfo merge.
	 *
	 * **PathInfo / block divergence (accepted trade-off):** When `return old` fires — for any of
	 * the three abort reasons — the train's `trainToBlocks` entry already contains the newly
	 * reserved blocks (step 2d in `reservePath()`), but `trainToPathInfo` still holds the pre-merge
	 * `old`. This means `TrainNavigationService` will not guide the train through those new blocks
	 * — the train effectively ignores the just-reserved segment. This is intentional: it is always
	 * better than storing a malformed PathInfo (#316), and it is recoverable, because
	 * `OrphanReservationSweeper` reclaims the divergent tail and resets its signals to STOP — in
	 * the hosts that wire it; see the "Scope of that reclamation" caveat above for `:fast-sim` and
	 * bare-`:core`, where the tail is left unreclaimed. The train will retry on its next dispatch
	 * tick.
	 *
	 * @param trainId Owner of the PathInfo being merged (used in the abort WARNs)
	 * @param old Previous PathInfo (Tail may still be navigating through this)
	 * @param new New PathInfo (Front just reserved this)
	 * @return Merged PathInfo covering both old and new paths, or [old] unchanged if the merge was
	 *         aborted: [new] does not start at `old.target`, `new.start` is duplicated inside
	 *         [new], or the merge would create a third occurrence of a separator (cycle guard).
	 *         Never throws.
	 * @since Issue #296 Phase 8; never-throw abort semantics from Issue #834 (SP2c.11)
	 */
	private fun mergePathInfo(
		trainId: String,
		old: PathInfo,
		new: PathInfo
	): PathInfo {
		logger.trace {
			"mergePathInfo: merging old path ${old.start}->${old.target} " +
				"with new ${new.start}->${new.target} for '$trainId'"
		}

		// Step 0a (Issue #834): the new path must CONTINUE the stored one. Concatenating a path
		// that starts somewhere else produces two adjacent separators; navigation truncates there
		// safely, so the train never teleports, but everything past the seam becomes an orphaned
		// RESERVED tail behind a cleared aspect the train will never reach. Abort fail-safe.
		if (new.start != old.target) {
			logger.warn {
				"mergePathInfo: aborting non-contiguous merge for train $trainId — new path starts at " +
					"${new.start} but the stored path ends at ${old.target}. " +
					"Keeping existing valid PathInfo unchanged. " +
					"(old: ${old.start}→${old.target}, new: ${new.start}→${new.target})"
			}
			return old
		}

		// Step 0b: new.start must occur exactly once in its own path (see "Preconditions" above).
		// Until Issue #834 this threw IllegalStateException — from reservePath Step 2i, i.e. after
		// blocks were reserved and a signal was already cleared, on the kDisco simulation thread,
		// with nothing catching it. Abort the same fail-safe way instead; never throw from here.
		val occurrences = new.reservedPath.count { it == new.start }
		if (occurrences != 1) {
			logger.warn {
				"mergePathInfo: aborting merge with duplicated new start for train $trainId — " +
					"${new.start} appears $occurrences times in the new path, expected exactly 1 at its " +
					"beginning. Keeping existing valid PathInfo unchanged. " +
					"(old: ${old.start}→${old.target}, new: ${new.start}→${new.target})"
			}
			return old
		}

		// Step 1: Create merged path starting from old path
		val mergedPath = ArrayPath(context)

		// Step 2: Add all elements from old path
		old.reservedPath.forEach { mergedPath.add(it) }

		// Step 3: Find overlap point (old.target == new.start). Always true past Step 0a; kept as an
		// explicit predicate so the skip below reads on its own terms rather than on the guard's.
		val skipFirst = (new.start == old.target)
		var skipped = false

		// Step 4: Add elements from new path (skip first separator if overlapping, detect cycles)
		for (element in new.reservedPath) {
			if (skipFirst && !skipped && element == new.start) {
				skipped = true // Skip this occurrence (already in old path)
				logger.trace {
					"mergePathInfo: skipping overlap element $element (old.target == new.start)"
				}
			} else {
				val cycleAbort = addElementWithCycleDetection(element, mergedPath, trainId, old, new)
				if (cycleAbort != null) return cycleAbort
			}
		}

		// Step 5: Merge entry directions (new overwrites old for conflicts)
		val mergedDirections = old.entryDirections.toMutableMap()
		mergedDirections.putAll(new.entryDirections)

		logger.trace {
			"mergePathInfo: merged ${old.reservedPath.size} + ${new.reservedPath.size} " +
				"elements into ${mergedPath.size} elements " +
				"(overlap: ${if (skipFirst) "yes" else "no"})"
		}

		return PathInfo(
			start = old.start, // Keep original start (where Tail might still be)
			target = new.target,
			reservedPath = mergedPath,
			entryDirections = mergedDirections
		)
	}

	/**
	 * Check if path extends beyond the given separator.
	 *
	 * Prevents redundant reservation attempts by dispatcher while allowing path extensions.
	 * This implements an idempotent check similar to the working tag's `isSetUpPath()` pattern.
	 *
	 * ## Usage Pattern
	 *
	 * ```kotlin
	 * // Before attempting path extension from a semaphore:
	 * if (registry.isPathExtendedBeyond(trainId, currentSemaphore)) {
	 *     // Path already extends beyond this semaphore, skip reservation
	 *     return true
	 * }
	 * // Otherwise, proceed with extension
	 * pathReservationService.reservePathToAnyNextSemaphore(trainId, currentSemaphore)
	 * ```
	 *
	 * ## Path Extension Check
	 *
	 * A path is considered extended beyond a separator if:
	 * 1. PathInfo exists for this train
	 * 2. The separator appears in the reserved path
	 * 3. There is at least one more element AFTER the separator in the path
	 *
	 * This allows the dispatcher to extend paths that END at a semaphore but prevents
	 * redundant reservations when the path already extends beyond it.
	 *
	 * ## Benefits
	 *
	 * - Prevents dispatcher from repeatedly calling reservePathToAnyNextSemaphore()
	 * - Allows path extensions when train is approaching a semaphore
	 * - Reduces cycle detection trigger rate
	 * - Matches working tag's idempotent behavior
	 * - Cheap O(n) check (linear scan of path elements)
	 *
	 * @param trainId Train identifier
	 * @param separator Semaphore/separator to check
	 * @return true if path already extends beyond this separator, false otherwise
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Dispatcher State Tracking
	 */
	fun isPathExtendedBeyond(
		trainId: String,
		separator: cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
	): Boolean {
		val pathInfo = trainToPathInfo[trainId] ?: return false

		// Find the separator in the path
		val separatorIndex = pathInfo.reservedPath.indexOfLast { it == separator }
		if (separatorIndex == -1) {
			// Separator not in path
			return false
		}

		// Path extends beyond if there are elements after the separator
		val extendsBeyond = separatorIndex < pathInfo.reservedPath.size - 1

		if (extendsBeyond) {
			logger.debug {
				"Path already extends beyond $separator for '$trainId' " +
					"(${pathInfo.reservedPath.size} elements, separator at index $separatorIndex)"
			}
		}

		return extendsBeyond
	}

	/**
	 * Get PathInfo for a train.
	 *
	 * Retrieves complete path metadata including entry directions.
	 * Used by TrainNavigationService to determine correct direction.
	 *
	 * @param trainId The train identifier
	 * @return PathInfo if registered, null otherwise
	 * @since Issue #295/#296 Phase 3
	 */
	fun getPathInfo(trainId: String): PathInfo? = trainToPathInfo[trainId]

	/**
	 * Restore a train's PathInfo to a previously snapshotted value, bypassing [registerPathInfo]'s
	 * merge logic.
	 *
	 * ## Purpose: transaction rollback only
	 *
	 * `registerPathInfo` is merge-only by design (Issue #296 Phase 8), and the merge is NOT
	 * invertible — it has cycle-guard abort semantics and entry-direction overwrites that make a
	 * "subtract the last registration" operation ill-defined. A caller that must undo a registration
	 * therefore snapshots the PathInfo via [getPathInfo] BEFORE attempting the mutation, and passes
	 * that snapshot here on rollback:
	 *
	 * - Non-null snapshot → replaces the current entry with the snapshot object (exact restore,
	 *   not a merge).
	 * - Null snapshot → removes the entry entirely (the train had no PathInfo before the attempt).
	 *
	 * The sole production caller is the bypass-rollback in
	 * `DefaultPathReservationService.reservePathToAnyNextSemaphore`: a candidate that committed its
	 * reservation and merged its PathInfo is rejected after the fact, and its contribution must be
	 * removed exactly — a merged-through-the-rejected-route PathInfo would steer the train onto
	 * track that was just released.
	 *
	 * Do NOT use this for general PathInfo editing: route extensions belong in [registerPathInfo]
	 * so the merge invariants (overlap handling, cycle guard) keep applying.
	 *
	 * @param trainId The train identifier
	 * @param snapshot The value previously read via [getPathInfo], or `null` when the train had
	 *   no PathInfo before the rolled-back attempt
	 * @since Issue #893 follow-up (PR #901 review — bypass-rollback transactional completion)
	 */
	fun restorePathInfo(
		trainId: String,
		snapshot: PathInfo?
	) {
		if (snapshot == null) {
			trainToPathInfo.remove(trainId)
			logger.debug { "restorePathInfo: removed PathInfo entry for '$trainId' (no pre-attempt PathInfo)" }
		} else {
			trainToPathInfo[trainId] = snapshot
			logger.debug {
				"restorePathInfo: restored PathInfo for '$trainId' to snapshot " +
					"(start=${snapshot.start}, target=${snapshot.target}, path length=${snapshot.reservedPath.size})"
			}
		}
	}

	/**
	 * Clear all registrations.
	 *
	 * Removes all train-to-block, block-to-train, train-to-switch, and switch-to-train mappings.
	 * Used for simulation reset or cleanup.
	 */
	fun clear() {
		trainToBlocks.clear()
		blockToTrain.clear()
		trainToSwitches.clear()
		switchToTrain.clear()
		trainToPathInfo.clear()
	}

	/**
	 * Get total number of registered trains.
	 *
	 * @return Count of trains with at least one reserved block
	 */
	fun trainCount(): Int = trainToBlocks.size

	/**
	 * Get total number of registered blocks across all trains.
	 *
	 * @return Count of blocks currently reserved
	 */
	fun blockCount(): Int = blockToTrain.size
}
