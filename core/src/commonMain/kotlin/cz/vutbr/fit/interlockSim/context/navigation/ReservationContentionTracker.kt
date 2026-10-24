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

import cz.ksimulantenbande.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.isFreeOrOwnedBy
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.util.currentSimulationTime

/**
 * Contention and blocked-request tracking of [DefaultPathReservationService]: which train is
 * blocked on which block, the one-shot mid-run [ConflictDetectedEvent], and the end-of-run
 * [BlockEvent.ReservationConflictDetected] flush. Extracted from the service unchanged (Issue #1167).
 *
 * ## Conflict-vs-routine-contention disambiguation (Issue #612 follow-up)
 *
 * reservePath() returning AllPathsBlocked is the ORDINARY outcome whenever a
 * candidate path is merely busy right now (e.g. another train is still using a
 * shared switch during routine ShuntingLoop choreography). That must not, on
 * its own, be treated as a [BlockEvent.ReservationConflictDetected] -- doing so
 * makes DefaultCollisionDetectionService auto-pause the simulation on completely
 * routine contention (regression: SimulationSpeedPerformanceTest hang).
 *
 * No timing signal derived from an AllPathsBlocked outcome can distinguish a
 * genuine race from ordinary queueing at a shared bottleneck (three heuristics
 * were tried and all misfired at high-traffic chokepoints -- see issue #612).
 * Genuine conflicts are therefore surfaced by exactly two mechanisms, neither
 * based on AllPathsBlocked timing:
 * 1. Live, mid-run: the atomic registry race ([PathReservationRegistry.RegistrationResult.Conflict]
 *    in [DefaultPathReservationService.reservePath]) -- a real double-booking detected at reservation time.
 * 2. End-of-run: [flushUnresolvedConflicts] -- blocked-path contention that never
 *    resolved before the run ended (deadlock/starvation indicator). Deliberately
 *    NOT a live/running-clock threshold; "unresolved when the run ends" is the
 *    actual signal, checked exactly once after the run has stopped.
 *
 * The map is cleared per-train on a reservation attempt that ends with Success, and by the
 * whole-route release ([DefaultPathReservationService.releaseAllBlocks]). NOT every
 * [DefaultPathReservationService.releasePath] call clears it: a release that holds no block
 * (or keeps blocks via approach locking) returns without clearing, so a contention can
 * outlive such a release -- pinned behavior (`PathReservationContentionTrackingTest`), not
 * an accident. Entries therefore stay bounded to trains that are or may still be contending,
 * not merely to currently-active trains.
 *
 * **NOT thread-safe**, like the service that owns it.
 *
 * @property registry Ownership registry, read to name the train that owns a contended block
 * @property emitConflict Delivers the one-shot mid-run [ConflictDetectedEvent]; the kDisco
 *   [emitCustom] by default (a no-op outside a running simulation). A seam for unit tests.
 */
internal class ReservationContentionTracker(
	private val registry: PathReservationRegistry,
	private val emitConflict: (ConflictDetectedEvent) -> Unit = { emitCustom(it) }
) {
	private val blockedSince: MutableMap<Pair<String, DynamicTrackBlock>, Double> = mutableMapOf()

	/**
	 * Record a blocked-path contention for [trainId] on [blockedBlock], and emit [ConflictDetectedEvent]
	 * only the FIRST time this contention is observed -- a routine polling retry of a still-blocked,
	 * already-known contention emits nothing new. [flushUnresolvedConflicts] reports whatever is
	 * still unresolved once the run has ended, and [clearBlockedTracking] forgets contention that
	 * resolved.
	 *
	 * reservePath() is called on every poll tick while a train is queued behind a busy
	 * shared block (ShuntingLoop/MultiTrainLoop retry once per control step — every 2.0
	 * simulated seconds, since `iteration()` holds 1.0 and `interLoopSleep()` holds another
	 * 1.0), so without this dedup guard the same still-blocked contention would re-emit the
	 * event once per tick for the entire wait instead of once per contention.
	 *
	 * Extracted from [DefaultPathReservationService.reservePath] to keep that method within the
	 * cyclomatic-complexity budget.
	 */
	fun recordContentionAndEmitIfNew(
		trainId: String,
		blockedBlock: DynamicTrackBlock,
		owningTrain: String
	) {
		val key = trainId to blockedBlock
		if (blockedSince.containsKey(key)) return
		blockedSince[key] = currentSimulationTime()
		val simTime = currentSimulationTime()
		emitConflict(
			ConflictDetectedEvent(
				block = blockedBlock,
				trainId = trainId,
				conflictingTrainId = owningTrain,
				time = simTime
			)
		)
	}

	/**
	 * Emit [BlockEvent.ReservationConflictDetected] for every (trainId, block) contention
	 * that is still tracked as blocked when the simulation ends.
	 *
	 * This is the "unresolved by end of run" signal: genuine, never-clearing contention
	 * (e.g. an actual deadlock) is still surfaced -- but routine "train waits its turn,
	 * then proceeds" contention, however long it takes mid-run, is never reported this
	 * way, because it resolves ([DefaultPathReservationService.reservePath] Success, or a
	 * whole-route release that actually cancels held blocks) before the run ends. A release
	 * call that holds no block does NOT resolve the contention that way -- it survives here
	 * by design-as-pinned (`PathReservationContentionTrackingTest`).
	 *
	 * Deliberately does **not** call [emitCustom] -- that top-level function is a no-op
	 * once the simulation's event loop has stopped (`Process.activeContext == null`),
	 * which is exactly the state the caller is in when the run has just ended. Instead,
	 * this returns the events for the caller ([cz.vutbr.fit.interlockSim.context.DefaultSimulationContext.run])
	 * to deliver directly to its buffered block-event listeners. Calling this mid-run
	 * would reintroduce the original false-positive-pause bug this design avoids, so it
	 * must only be called once, after the run has fully stopped.
	 *
	 * @param simulationEndTime Simulation clock value to stamp on the returned event(s).
	 * @since Issue #612 (Goal 3 SP2 follow-up)
	 */
	fun flushUnresolvedConflicts(simulationEndTime: Double): List<BlockEvent.ReservationConflictDetected> {
		if (blockedSince.isEmpty()) return emptyList()
		val unresolved = blockedSince.keys.toList()
		blockedSince.clear()
		return unresolved.mapNotNull { (trainId, block) ->
			val owner = ownerOf(block) ?: return@mapNotNull null
			BlockEvent.ReservationConflictDetected(
				block = block,
				trainId = trainId,
				conflictingTrainId = owner,
				time = simulationEndTime
			)
		}
	}

	/**
	 * Forget any in-progress blocked-path contention tracked for [trainId].
	 *
	 * Called when [trainId]'s reservation attempt resolves with Success, and by the
	 * whole-route release ([DefaultPathReservationService.releaseAllBlocks]). A release
	 * call that holds no block (or keeps blocks via approach locking) does NOT call this,
	 * so contention survives it -- pinned behavior (`PathReservationContentionTrackingTest`);
	 * the whole-route release is the only release-path clear. This keeps [blockedSince] from
	 * conflating a stale, already-resolved contention with a fresh one on the same block
	 * later, and keeps [flushUnresolvedConflicts] from reporting contention that actually
	 * resolved.
	 */
	fun clearBlockedTracking(trainId: String) {
		blockedSince.keys.removeAll { it.first == trainId }
	}

	/**
	 * Find the first block in [blocks] that is not free and not owned by [trainId],
	 * and return it paired with its owning train ID.
	 *
	 * Returns `null` when no such block exists (all blocks are free or owned by [trainId]).
	 *
	 * Extracted from [DefaultPathReservationService.reservePath] to keep that method within the
	 * cyclomatic-complexity budget.
	 *
	 * @since Issue #612 (Goal 3 SP2)
	 */
	fun findFirstBlockedConflict(
		blocks: List<DynamicTrackBlock>,
		trainId: String
	): Pair<DynamicTrackBlock, String>? {
		val blockedBlock = blocks.firstOrNull { block -> !block.isFreeOrOwnedBy(trainId) } ?: return null
		val owner = ownerOf(blockedBlock) ?: return null
		return Pair(blockedBlock, owner)
	}

	/** The train that owns [block]: the registry's record first, the block's own `trainName` as fallback. */
	private fun ownerOf(block: DynamicTrackBlock): String? = registry.getOwner(block) ?: block.trainName
}
