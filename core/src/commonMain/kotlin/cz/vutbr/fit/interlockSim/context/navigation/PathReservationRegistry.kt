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
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyNotifier
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The registry's logger, shared by its units ([BlockOwnershipTable], [SwitchOwnershipTable],
 * [PathInfoStore], [PathInfoMerger]) so that every message keeps the logger name it had before the
 * split (Issue #1172) -- log configuration and log-capturing tests key on that name.
 */
internal val registryLogger: KLogger = KotlinLogging.logger {}

private val logger: KLogger = registryLogger

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
 * ## Registration timestamps (Issue #975)
 *
 * The registry records the simulation time at which each block was registered to its owner
 * ([getRegisteredAtSimTime]). It is a read-only fact, not a policy: nothing in the registry expires
 * a reservation. A block keeps its first timestamp while its owner re-registers it, loses it on
 * every removal ([unregisterBlock], [unregister], [clear] -- every release and rollback path goes
 * through one of them), and gets a new one if it is registered again later.
 *
 * Since Issue #1174 the timestamps have their reader: [expiredHoldings], the pure query that names
 * every train holding only un-entered blocks whose newest registration is at least a TTL old.
 * Releasing those holdings is the reservation kernel's policy
 * ([DefaultReservationExpiry.expireStale]), never the registry's; the registry
 * reads only simulation time ([simTimeSource]), never wall-clock time.
 *
 * ## Structure (Issue #1172)
 *
 * The registry is the single public owner of the train -> blocks / switches / PathInfo state and
 * keeps the whole public contract. Since Issue #1172 it delegates to three internal units, one per
 * ownership concern, and composes the operations that span them (whole-route teardown in
 * [unregister], the per-block release funnel in [unregisterBlock]):
 * - [BlockOwnershipTable] -- block ownership and registration timestamps
 * - [SwitchOwnershipTable] -- switch claims, their flank purpose and the stale-claim rule
 * - [PathInfoStore] -- the stored PathInfo and its trim bookkeeping, merging through [PathInfoMerger]
 *
 * The facade still exceeds detekt's `TooManyFunctions` limit: every public operation stays on it.
 *
 * @param context Simulation context (needed for PathInfo merging with ArrayPath)
 * @param simTimeSource Current simulation time, read when a block is registered and as the default
 *   `now` of [expiredHoldings]. The default is [currentSimulationTime]: the running kDisco clock, 0.0
 *   outside a simulation (unit tests).
 * @since Issue #294 (Phase 2 of Issue #292)
 * @since Issue #296 Phase 8 (PathInfo extension fix)
 */
@Suppress("TooManyFunctions")
class PathReservationRegistry(
	context: SimulationContext,
	private val simTimeSource: () -> Double = ::currentSimulationTime
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
	 * Outcome of [registerPathInfo] ([PathInfoMerger.merge]), distinguishing a genuine merge from an
	 * abort so the caller can react to the abort instead of assuming the stored [PathInfo] now
	 * reflects what it just registered.
	 *
	 * Precedent: [StartSignalResult][RouteSignalConfigurator] (Issue #903) is the same
	 * "a call that used to silently swallow its failure now needs to report why" shape.
	 *
	 * @since Issue #904
	 */
	sealed class MergeOutcome {
		/** The [PathInfo] now stored for the train — merged on [Merged], unchanged on [Aborted]. */
		abstract val pathInfo: PathInfo

		/** The stored [PathInfo] is [pathInfo] — either the first registration, or a genuine merge. */
		data class Merged(
			override val pathInfo: PathInfo
		) : MergeOutcome()

		/**
		 * The merge was aborted (Issue #834's fail-safe: non-contiguous new start, duplicated new
		 * start, or the 3rd-occurrence cycle guard). The stored [PathInfo] is unchanged and equals
		 * [pathInfo] — the pre-merge value — by reference.
		 *
		 * @property reason English explanation of which abort condition fired, forwarded from
		 *   [PathInfoMerger.merge]'s own WARN log.
		 */
		data class Aborted(
			override val pathInfo: PathInfo,
			val reason: String
		) : MergeOutcome()
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

	/** Block ownership: Train ID <-> blocks, and when each block was registered (Issue #975). */
	private val blockTable = BlockOwnershipTable(simTimeSource)

	/** Switch ownership (Tier 2): Train ID <-> switches, with each claim's purpose (Issue #1103). */
	private val switchTable = SwitchOwnershipTable(blockTable::getBlocks)

	/** PathInfo bookkeeping: Train ID -> the route it is driving (Issue #295/#296). */
	private val pathInfoStore = PathInfoStore(context, blockTable::getBlocks)

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
	 * ## Conflict Detection
	 *
	 * A conflict occurs when any block is already owned by a **different** train.
	 * The check consults, in order:
	 * 1. The registry's block -> train mapping.
	 * 2. The block's own `trainName` reservation field (defence in depth).
	 * 3. The block's current physical `occupant` (defence in depth).
	 *
	 * If the same train attempts to register the same block again, it is allowed
	 * (idempotent operation). The returned [RegistrationResult.Conflict] carries a [ConflictReason]
	 * telling whether the block is merely reserved or physically occupied by another train;
	 * occupied conflicts are more severe and callers should typically wait longer before retrying.
	 *
	 * @param trainId The train identifier
	 * @param blocks List of blocks to register
	 * @return Success if all registered, Conflict with details if any conflict
	 */
	fun registerAtomic(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	): RegistrationResult = blockTable.registerAtomic(trainId, blocks)

	/**
	 * Unregister all blocks reserved by a train (whole-route teardown).
	 *
	 * Removes all bidirectional mappings for the given train, regardless of block state.
	 * This is used for simulation cleanup, test scenarios, and forced release.
	 *
	 * ## State Changes
	 *
	 * - Removes the train's blocks, their owners and their registration timestamps
	 * - Removes the train's PathInfo (Issue #295/#296)
	 * - Releases the train's held switch claims too ([unregisterSwitches]): a whole-route teardown
	 *   unlocks every held switch unconditionally, so the bulk API cannot leave a stale claim behind
	 *   any more than the per-block funnel can (Issue #1103 review). Already-blockless trains release
	 *   their claims too -- a switch claim without a held block is exactly the stale shape; their
	 *   PathInfo stays. Callers that also call [unregisterSwitches] themselves get an idempotent no-op.
	 *
	 * @param trainId The train identifier
	 * @return List of blocks that were released (empty if train had no reservations)
	 */
	fun unregister(trainId: String): List<DynamicTrackBlock> {
		val blocks = blockTable.removeTrain(trainId)
		if (blocks != null) pathInfoStore.remove(trainId)

		// Issue #1103 review: whole-route teardown releases the switch claims too. A switch claim
		// can exist without a block entry -- exactly the stale shape -- so the wholesale release
		// runs unconditionally, and no train leaves this method with stale ownership anywhere in
		// the registry.
		switchTable.unregisterSwitches(trainId)

		if (blocks == null) return emptyList()
		logger.debug {
			"unregister: Released ${blocks.size} blocks for '$trainId'"
		}

		return blocks
	}

	/**
	 * Unregister a single block for a train.
	 *
	 * Removes the block from registry mappings if it is FREE (no occupant, state FREE) and owned by
	 * [trainId]. This is called automatically when a train's Tail leaves a block, ensuring blocks are
	 * cleaned up as soon as they become available for subsequent trains.
	 *
	 * ## State Changes
	 *
	 * If block is FREE and owned by trainId:
	 * - Removes the block, its owner and its registration timestamp; the train's entry goes with its
	 *   last block, but its PathInfo stays
	 * - Releases every switch claim at the block's [DynamicTrackBlock.ends] that is stale afterwards
	 *   ([isStaleSwitchOwnership]: a plain claim whose owner holds no block bounded by the switch any
	 *   more) through [unregisterSwitch]. This makes [unregisterBlock] the funnel for per-block
	 *   releases (whole-route teardown goes through [unregister], which releases the claims
	 *   wholesale itself); a reclaim failure is contained in [reclaimStaleSwitchOwnership] and the
	 *   claim stays healable there (Issue #1065, #1103). A flank claim, and a claim whose owner still
	 *   holds the block on the other side of the switch, stay.
	 *
	 * ## PathInfo Lifecycle (Issue #301 Fix)
	 *
	 * PathInfo represents the train's **intended path** and is separate from its **current
	 * reservations**: a train needs it for navigation even after all its blocks are unregistered.
	 * PathInfo is only deleted by `releaseTrainReservations()`, [unregister] and [clear].
	 *
	 * This method never shortens the PathInfo. A caller that releases the un-travelled tail of a
	 * route in front of a train must call [trimPathInfoToHeldBlocks] afterwards (Issue #1063,
	 * #1067); otherwise the PathInfo keeps describing the released track and [isPathExtendedBeyond]
	 * stays `true`.
	 *
	 * @param trainId The train identifier
	 * @param block The block to unregister
	 * @return true if block was unregistered, false if block is still occupied or not owned
	 */
	fun unregisterBlock(
		trainId: String,
		block: DynamicTrackBlock
	): Boolean {
		if (!blockTable.release(trainId, block)) return false

		// Issue #1103: the block is gone, so a plain claim at its ends that no held block protects
		// any more is stale now; release it here, where every per-block release passes, rather than
		// in each caller.
		block
			.ends()
			.filterIsInstance<DynamicRailSwitch>()
			.filter { it.locked }
			.forEach { reclaimStaleSwitchOwnership(it) }

		return true
	}

	/**
	 * Release [switch]'s ownership if [isStaleSwitchOwnership] says it protects no live route, through
	 * [unregisterSwitch], which keeps both ownership maps consistent and unlocks the switch.
	 *
	 * This is the mechanism that makes the SI-5 guard in `DynamicRailSwitch.setUpPath` livable
	 * (Issue #1065): without it, a train that has passed a switch keeps it locked until its FULL
	 * journey completes, and the next train needing the other position would be refused forever
	 * instead of merely waiting. [unregisterBlock] runs it for the freed block's ends;
	 * `DefaultPathReservationService` runs it for a candidate's switches before taking them
	 * (reservePath Step 2e.5, Issue #1076).
	 *
	 * Listener containment: `unregisterSwitch` -> `DynamicRailSwitch.unlock()` invokes property
	 * listeners synchronously. A throwing listener is contained by the cell itself; the try/catch in
	 * [SwitchOwnershipTable.reclaimStaleSwitchOwnership] is defense-in-depth, so the caller's own work
	 * also survives a throw. Both ownership maps stay consistent.
	 *
	 * @return the owner whose stale claim was released, or `null` when nothing was released or the
	 *   release failed and was contained
	 * @since Issue #1065 (moved here from the service, Issue #1103)
	 */
	fun reclaimStaleSwitchOwnership(switch: DynamicRailSwitch): String? = switchTable.reclaimStaleSwitchOwnership(switch)

	/** The train ID that owns [block], or `null` if no train has registered it. */
	fun getOwner(block: DynamicTrackBlock): String? = blockTable.getOwner(block)

	/**
	 * The simulation time at which [block] was registered to its current owner, or `null` when no
	 * train holds it (Issue #975). A read-only fact -- see the class KDoc; the registry never
	 * expires a reservation by it ([expiredHoldings] reads the same timestamps for the kernel expiry,
	 * Issue #1174). Only tests call this accessor.
	 */
	fun getRegisteredAtSimTime(block: DynamicTrackBlock): Double? = blockTable.getRegisteredAtSimTime(block)

	/**
	 * The trains whose holding has gone stale (Issue #1174, absorbs #975): pure query, changes
	 * nothing. A train is reported when
	 * - every block it holds is RESERVED and no train stands on it (the release's own
	 *   [isTrainOnBlock] notion, read live from the blocks) -- a route it has not entered; and
	 * - its NEWEST block registration is at least [ttl] older than [now]. Every new claim (a route
	 *   extension) restarts the clock; re-registering a block it already holds does not, since a
	 *   block keeps its first timestamp.
	 *
	 * A train that stands on any block of its holding is never reported, however old: entering a
	 * reserved block is not a registry write, so registration age says nothing about the progress
	 * of a train on the track (the reason `OrphanReservationSweeper` does not judge by it either).
	 * The stalled RESERVED tail of such a train is phase 2 (#1220). A held block in any other state
	 * (a FREE block the registry still attributes to the train, the Issue #1025 divergence) also
	 * keeps the train out: that is not an un-entered route.
	 *
	 * The policy -- the TTL and the release -- belongs to
	 * [DefaultReservationExpiry.expireStale].
	 *
	 * @param ttl Simulated seconds a holding may go without a new block registration
	 * @param now The current simulation time; defaults to this registry's own clock, the one every
	 *   registration timestamp was read from
	 */
	internal fun expiredHoldings(
		ttl: Double,
		now: Double = simTimeSource()
	): List<ExpiredHolding> = blockTable.expiredHoldings(ttl = ttl, now = now)

	/**
	 * Check whether a block is available for a new reservation: its dynamic state is
	 * [TrackFacility.State.FREE], it has no registry owner and no physical occupant.
	 *
	 * This predicate is used by [createBlockAvailableCondition] to build kDisco
	 * [Condition]s that wake a waiting process as soon as a block is released.
	 *
	 * @param block The block to check
	 * @return true if the block can be reserved by another train
	 * @since Issue #582 (Goal 1 SP3)
	 */
	fun isBlockAvailable(block: DynamicTrackBlock): Boolean = blockTable.isBlockAvailable(block)

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

	/** The train ID of [block]'s current physical occupant, or `null`. @since Issue #581 (Goal 1 SP2) */
	fun getOccupantName(block: DynamicTrackBlock): String? = block.occupant?.name

	/** Whether a train is currently physically on [block]. @since Issue #581 (Goal 1 SP2) */
	fun isOccupied(block: DynamicTrackBlock): Boolean = block.occupant != null

	/** The held blocks [trainId] currently physically occupies (empty if none). @since Issue #581 (Goal 1 SP2) */
	fun getOccupiedBlocks(trainId: String): List<DynamicTrackBlock> = blockTable.getOccupiedBlocks(trainId)

	/**
	 * Register switches as reserved by a train (Tier 2).
	 *
	 * Locks every switch newly registered to the train and records a plain [SwitchClaim] for it (a
	 * flank claim the train already holds stays a flank claim). If the train already has reserved
	 * switches, the new switches are added to the existing list.
	 *
	 * ## Foreign ownership is rejected atomically (Issue #1076)
	 *
	 * A plain overwrite used to silently steal a switch from its current owner while that owner's
	 * switch list still contained it -- the old owner's release path could later unlock a switch the
	 * new owner was actively using. All switches are therefore pre-checked BEFORE any mutation: if ANY
	 * switch is registered to a different train, an [IllegalStateException] is thrown and the
	 * registry is left completely untouched. Callers must release stale foreign ownership first (e.g.
	 * via [unregisterSwitch]) or refuse the route as transient contention.
	 *
	 * @param trainId The train identifier
	 * @param switches List of switches to register as reserved
	 * @throws IllegalStateException if any switch is already registered to a different
	 *   train; no registry state is mutated in that case
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Tier 2
	 */
	fun registerSwitches(
		trainId: String,
		switches: List<DynamicRailSwitch>
	) {
		switchTable.registerSwitches(trainId, switches)
	}

	/**
	 * Register switches as FLANK protection for a train (Issue #1076 review).
	 *
	 * [registerSwitches] (same atomic foreign-owner rejection), with flank claims -- see
	 * [SwitchOwnershipTable] for why. An empty list registers nothing.
	 *
	 * @throws IllegalStateException if any switch is already registered to a different
	 *   train; no registry state is mutated in that case
	 * @since Issue #1076
	 */
	fun registerFlankSwitches(
		trainId: String,
		switches: List<DynamicRailSwitch>
	) {
		switchTable.registerFlankSwitches(trainId, switches)
	}

	/**
	 * Undo a flank promotion: turn [trainId]'s flank claim on [switch] back into a plain claim, with
	 * the owner and the physical lock unchanged (Issue #1218). Only a caller that knows the claim was
	 * plain before its own failed registration may call it -- the facade's route rollback, for a
	 * switch a failed route listed as flank over the train's earlier plain claim. Once plain, the
	 * claim is subject to the [isStaleSwitchOwnership] rule again.
	 *
	 * @return `true` if demoted; `false` (and no change) when [switch] has no claim, another train
	 *   owns it, or the claim is already plain
	 * @since Issue #1218
	 */
	internal fun demoteFlankClaim(
		trainId: String,
		switch: DynamicRailSwitch
	): Boolean = switchTable.demoteFlankClaim(trainId, switch)

	/**
	 * Whether [switch] is currently registered as flank protection -- see [SwitchOwnershipTable].
	 *
	 * @since Issue #1076
	 */
	fun isFlankProtected(switch: DynamicRailSwitch): Boolean = switchTable.isFlankProtected(switch)

	/**
	 * Whether [switch] is registered to a train OTHER than [trainId] -- the ownership a
	 * registration for [trainId] must never overwrite (Issue #1076).
	 *
	 * @since Issue #1076
	 */
	fun isOwnedByOtherTrain(
		switch: DynamicRailSwitch,
		trainId: String
	): Boolean = switchTable.isOwnedByOtherTrain(switch, trainId)

	/**
	 * Whether [switch]'s ownership protects no live route any more, so it may be released.
	 *
	 * Stale iff the switch has an owner, its claim is not a flank claim ([SwitchClaim.isFlank]), and
	 * the owner holds no block bounded by it. A route never ends AT a switch
	 * ([isValidPathInfoEnd], Issue #938), so any switch on a live route has at least one held
	 * adjacent block, and a train straddling the switch holds both. "No held adjacent block"
	 * therefore means no train has authority over any track the switch connects, and
	 * releasing it cannot move a switch under a train or contradict a granted route.
	 *
	 * @since Issue #1076 (the #1065 reclamation predicate, moved here with its flank exception)
	 */
	fun isStaleSwitchOwnership(switch: DynamicRailSwitch): Boolean = switchTable.isStaleSwitchOwnership(switch)

	/**
	 * Whether a plain claim of [switch] by [trainId] would be stale the moment it is made: [trainId]
	 * holds no block bounded by it, the [isStaleSwitchOwnership] rule applied to the would-be claim.
	 * Independent of the switch's current owner. [registerSwitches] does not consult it (it accepts
	 * such a claim); the reservation path skips a switch it is `true` for instead of registering it.
	 *
	 * @since Issue #1173
	 */
	internal fun wouldBeStaleClaim(
		switch: DynamicRailSwitch,
		trainId: String
	): Boolean = switchTable.wouldBeStaleClaim(switch, trainId)

	/**
	 * Unregister all switches reserved by a train (Tier 2): unlocks them and removes both ownership
	 * mappings. Used for simulation cleanup and forced release.
	 *
	 * @param trainId The train identifier
	 * @return List of switches that were released (empty if train had no switches)
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Tier 2
	 */
	fun unregisterSwitches(trainId: String): List<DynamicRailSwitch> = switchTable.unregisterSwitches(trainId)

	/**
	 * Unregister a SINGLE switch reserved by a train (Tier 2).
	 *
	 * Symmetric per-switch counterpart to [unregisterBlock], for scoped rollback of a
	 * rejected candidate path whose switches were already registered (e.g. a signal-config
	 * failure after [registerSwitches] succeeded, Issue #742 SP0.11 review follow-up).
	 * Unlike [unregisterSwitches] (which removes ALL of the train's switches), this only
	 * releases the one switch — leaving the train's earlier-hop switches intact. It unlocks the
	 * switch first and then removes both ownership mappings.
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
	): Boolean = switchTable.unregisterSwitch(trainId, switch)

	/** All switches registered to [trainId] (empty if none). @since Issue #291 - Tier 2 */
	fun getSwitches(trainId: String): List<DynamicRailSwitch> = switchTable.getSwitches(trainId)

	/** The train ID that owns [switch], or `null`. @since Issue #291 - Tier 2 */
	fun getSwitchOwner(switch: DynamicRailSwitch): String? = switchTable.getSwitchOwner(switch)

	/**
	 * The [SwitchClaim] on [switch] -- its owner and purpose in one record -- or `null` when no
	 * train owns it (Issue #1103).
	 */
	internal fun getSwitchClaim(switch: DynamicRailSwitch): SwitchClaim? = switchTable.getSwitchClaim(switch)

	/** All blocks reserved by [trainId] (empty if no reservations). */
	fun getBlocks(trainId: String): List<DynamicTrackBlock> = blockTable.getBlocks(trainId)

	/** Whether [block] is registered to any train. */
	fun isRegistered(block: DynamicTrackBlock): Boolean = blockTable.isRegistered(block)

	/**
	 * Register PathInfo for a train.
	 *
	 * Instead of overwriting the old PathInfo, this method **extends** it (Issue #296 Phase 8):
	 * appends the new path elements to the old path (preserving Tail navigation), updates the target
	 * (letting the Front progress) and merges the entry directions (new overwrites old). The merge
	 * never throws; a rejected merge is a fail-safe abort that keeps the stored PathInfo -- see
	 * [PathInfoMerger.merge] for the four abort conditions and why they must not throw.
	 *
	 * @param trainId The train identifier
	 * @param newPathInfo Complete path metadata to register or merge
	 * @return [MergeOutcome.Merged] on a genuine merge (or first registration); [MergeOutcome.Aborted]
	 *   when the merge's fail-safe abort fired and the stored `PathInfo` is unchanged.
	 * @since Issue #295/#296 Phase 3
	 * @since Issue #296 Phase 8 (PathInfo extension fix)
	 * @since Issue #904 (return type widened from `Unit` to [MergeOutcome])
	 */
	fun registerPathInfo(
		trainId: String,
		newPathInfo: PathInfo
	): MergeOutcome = pathInfoStore.register(trainId, newPathInfo)

	/**
	 * Check if path extends beyond the given separator: the train has a PathInfo, the separator is on
	 * it, and at least one element follows the separator's last occurrence.
	 *
	 * Prevents redundant reservation attempts by the dispatcher while still allowing an extension of
	 * a path that ENDS at a semaphore (the idempotent check of the working tag's `isSetUpPath()`).
	 *
	 * @param trainId Train identifier
	 * @param separator Semaphore/separator to check
	 * @return true if path already extends beyond this separator, false otherwise
	 * @since Issue #291 Fix Trains 4 & 5 Deadlock - Dispatcher State Tracking
	 */
	fun isPathExtendedBeyond(
		trainId: String,
		separator: DynamicPathSeparator
	): Boolean = pathInfoStore.isPathExtendedBeyond(trainId, separator)

	/**
	 * The PathInfo (path metadata with entry directions) of [trainId], or `null`; read by
	 * TrainNavigationService to determine the correct direction. @since Issue #295/#296 Phase 3
	 */
	fun getPathInfo(trainId: String): PathInfo? = pathInfoStore.get(trainId)

	/**
	 * Read-only probe of the merge's Step 0a precondition (Issue #1066): would [candidate]
	 * CONTINUE the route [trainId] currently holds?
	 *
	 * Lets `DefaultPathReservationService.reservePath` refuse a divergent candidate BEFORE it
	 * reserves blocks, throws switches or clears signals. Changes no state.
	 *
	 * @return `null` when [candidate] continues the stored path, or when the train has no stored
	 *   PathInfo yet (a first registration is always accepted); otherwise an English reason naming
	 *   the stored target and the candidate's start.
	 * @since Issue #1066
	 */
	fun nonContinuingCandidateReason(
		trainId: String,
		candidate: PathInfo
	): String? = pathInfoStore.nonContinuingCandidateReason(trainId, candidate)

	/**
	 * Restore a train's PathInfo to a previously snapshotted value, bypassing [registerPathInfo]'s
	 * merge logic: a non-null snapshot replaces the entry (exact restore, not a merge), a `null`
	 * snapshot removes it.
	 *
	 * For transaction rollback only -- the merge is not invertible, so a caller that must undo a
	 * registration snapshots via [getPathInfo] first. Route extensions belong in [registerPathInfo]
	 * (details: [PathInfoStore.restore]).
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
		pathInfoStore.restore(trainId, snapshot)
	}

	/**
	 * Cut a train's stored PathInfo back so that it ends at [boundary], after the blocks beyond that
	 * separator were released (Issue #1063: an untrimmed PathInfo keeps [isPathExtendedBeyond] `true`
	 * and stalls the train).
	 *
	 * The trimmed PathInfo keeps [PathInfo.start], ends at the first occurrence of [boundary] after
	 * the last block the train still holds, and keeps only the entry directions of blocks that
	 * remain on it. Nothing is changed and `false` is returned when the train has no PathInfo, holds
	 * no block on it, [boundary] does not follow its last held block, or [boundary] is not a valid
	 * end ([isValidPathInfoEnd]: a switch, or a signal facing away from the train). Details:
	 * [PathInfoStore.trimPathInfoTo].
	 *
	 * @param trainId The train identifier
	 * @param boundary The separator where the train's held track now ends
	 * @return `true` if the stored PathInfo now ends at [boundary], `false` if it was left unchanged
	 * @since Issue #1063
	 */
	fun trimPathInfoTo(
		trainId: String,
		boundary: DynamicPathSeparator
	): Boolean = pathInfoStore.trimPathInfoTo(trainId, boundary)

	/**
	 * Whether [separator] may be the end of a stored PathInfo, the separator a train's next route
	 * request starts from: never a switch (Issue #938), never a semaphore facing away from
	 * [nextBlock] (Issue #1067 gap 2); without a [nextBlock] only the switch rule applies.
	 *
	 * @since Issue #1063
	 */
	fun isValidPathInfoEnd(
		separator: DynamicPathSeparator,
		nextBlock: DynamicTrackBlock? = null
	): Boolean = pathInfoStore.isValidPathInfoEnd(separator, nextBlock)

	/**
	 * Where the stored PathInfo of [trainId] would end if the train kept only [kept]: the separator
	 * right after the last element of the path whose block is in [kept], and the block after that
	 * separator (`null` when the separator is the path's target). Pure query.
	 *
	 * @return `null` when the train has no PathInfo, no block of [kept] lies on it, or no separator
	 *   follows the last kept block
	 * @since Issue #1067
	 */
	fun pathInfoEndAfter(
		trainId: String,
		kept: Collection<DynamicTrackBlock>
	): PathInfoEnd? = pathInfoStore.pathInfoEndAfter(trainId, kept)

	/** The end [pathInfoEndAfter] finds. @since Issue #1067 */
	data class PathInfoEnd(
		val boundary: DynamicPathSeparator,
		val nextBlock: DynamicTrackBlock?
	)

	/**
	 * [trimPathInfoTo], but finds the boundary itself: the separator immediately following the
	 * last block the train still holds in the stored PathInfo. Idempotent, so a tail released over
	 * several sweeps is trimmed by whichever sweep frees its last block (Issue #1067 gap 1).
	 *
	 * @param trainId The train identifier
	 * @return `true` if the stored PathInfo now ends at the boundary, `false` if there was nothing
	 *   to trim ([pathInfoEndAfter] found no end: no PathInfo, or no held block on it) or
	 *   [trimPathInfoTo] refused it (see [isValidPathInfoEnd])
	 * @since Issue #1067
	 */
	fun trimPathInfoToHeldBlocks(trainId: String): Boolean = pathInfoStore.trimPathInfoToHeldBlocks(trainId)

	/**
	 * Whether freeing every block of [trainId] except [kept] would leave its stored PathInfo
	 * consistent: pure query, changes nothing.
	 *
	 * `true` when no trim is needed (no PathInfo, or [kept] reaches the path's end) or when the trim
	 * [trimPathInfoTo] would perform will follow. `false` when [kept] holds no block on the path or
	 * the boundary is one [trimPathInfoTo] refuses. A caller that must not change anything unless the
	 * trim will follow checks this BEFORE freeing any block (Issue #1050 review round).
	 *
	 * @since Issue #1050
	 */
	fun canTrimPathInfoTo(
		trainId: String,
		kept: Collection<DynamicTrackBlock>
	): Boolean = pathInfoStore.canTrimPathInfoTo(trainId, kept)

	/**
	 * Clear all registrations.
	 *
	 * Removes all train-to-block, block-to-train, train-to-switch, and switch-to-claim mappings
	 * (a claim's flank purpose goes with it), every registration timestamp and every PathInfo.
	 * Used for simulation reset or cleanup.
	 */
	fun clear() {
		blockTable.clear()
		switchTable.clear()
		pathInfoStore.clear()
	}

	/** Count of trains with at least one reserved block. */
	fun trainCount(): Int = blockTable.trainCount()

	/** Count of blocks currently reserved across all trains. */
	fun blockCount(): Int = blockTable.blockCount()
}
