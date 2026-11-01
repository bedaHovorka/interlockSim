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

import cz.ksimulantenbande.kdisco.Process
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
 * 1. Live, mid-run: the atomic registry race ([PathReservationRegistry.RegistrationResult.Conflict],
 *    handled by `CandidateCommitter.rollbackRegistryConflict`) -- a real double-booking detected at
 *    reservation time.
 * 2. End-of-run: [flushUnresolvedConflicts] -- blocked-path contention that never
 *    resolved before the run ended (deadlock/starvation indicator). Deliberately
 *    NOT a live/running-clock threshold; "unresolved when the run ends" is the
 *    actual signal, checked exactly once after the run has stopped.
 *
 * The map is cleared per-train on a reservation attempt that ends with Success, and by the
 * whole-route release ([RouteReleaser.releasePath]'s `releaseAllBlocks`); [clearBlockedTracking]
 * says which releases do NOT clear it. Entries therefore stay bounded to trains that are or may
 * still be contending, not merely to currently-active trains.
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
	/**
	 * The open contentions, keyed by (train, blocked block), valued by the simulation time the contention was first
	 * observed: the "since" column of [openContentions] (Issue #1305; the Goal 9B demand D1, section 2.3 of
	 * `docs/goal9b-demands/SP12-routing-decomposition.md`, Issue #1221). The same value stamps the one-shot
	 * [ConflictDetectedEvent] in [recordContentionAndEmitIfNew].
	 */
	private val blockedSince: MutableMap<Pair<String, DynamicTrackBlock>, Double> = mutableMapOf()

	/**
	 * Simulated seconds of the blocked episodes that have closed, per train (Issue #1305): recorded, never read
	 * back by the tracking itself. One entry per train ever blocked, so bounded by the trains of the run.
	 */
	private val closedBlockedSeconds: MutableMap<String, Double> = mutableMapOf()

	/**
	 * Episodes cleared while no kernel was running, so with no clock to close them at, per train: the
	 * earliest start (Issue #1305). [flushUnresolvedConflicts] closes them at the run's end time; until
	 * then [blockedSeconds] counts them up to its `now`. Without this a clear outside a run would close
	 * the episode at the clock's 0.0 fallback and lose it.
	 */
	private val unclosedSince: MutableMap<String, Double> = mutableMapOf()

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
	 * The simulation clock is read once: the value stored in [blockedSince] and the event's `time` are the same.
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
		val simTime = currentSimulationTime()
		blockedSince[key] = simTime
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
	 * whole-route release that actually cancels held blocks) before the run ends.
	 *
	 * Deliberately does **not** call [emitCustom] -- that top-level function is a no-op
	 * once the simulation's event loop has stopped (`Process.activeContext == null`),
	 * which is exactly the state the caller is in when the run has just ended. Instead,
	 * this returns the events for the caller ([cz.vutbr.fit.interlockSim.context.DefaultSimulationContext.run])
	 * to deliver directly to its buffered block-event listeners. Calling this mid-run
	 * would reintroduce the original false-positive-pause bug this design avoids, so it
	 * must only be called once, after the run has fully stopped.
	 *
	 * Every returned event is marked [BlockEvent.ReservationConflictDetected.atRunEnd], so the metrics
	 * count it apart from a mid-run conflict (Issue #1280): a contention open at an end-time cutoff
	 * is a train still queued, not a double-booking.
	 *
	 * @param simulationEndTime Simulation clock value to stamp on the returned event(s).
	 * @since Issue #612 (Goal 3 SP2 follow-up)
	 */
	fun flushUnresolvedConflicts(simulationEndTime: Double): List<BlockEvent.ReservationConflictDetected> {
		// Issue #1305: episodes cleared with no running kernel end with the run.
		unclosedSince.forEach { (trainId, since) -> addClosed(trainId, simulationEndTime - since) }
		unclosedSince.clear()
		if (blockedSince.isEmpty()) return emptyList()
		val unresolved = blockedSince.keys.toList()
		// Issue #1305: every train still blocked stood blocked to the end, ownerless block or not.
		unresolved.map { it.first }.distinct().forEach { closeEpisode(it, simulationEndTime) }
		blockedSince.clear()
		return unresolved.mapNotNull { (trainId, block) ->
			val owner = ownerOf(block) ?: return@mapNotNull null
			BlockEvent.ReservationConflictDetected(
				block = block,
				trainId = trainId,
				conflictingTrainId = owner,
				time = simulationEndTime,
				atRunEnd = true
			)
		}
	}

	/**
	 * Forget any in-progress blocked-path contention tracked for [trainId].
	 *
	 * Called when [trainId]'s reservation attempt resolves with Success, and by the
	 * whole-route release ([RouteReleaser.releasePath]'s `releaseAllBlocks`). NOT every release
	 * clears it: a release that holds no block (or keeps blocks via approach locking) returns
	 * without calling this, so contention survives it -- pinned behavior
	 * (`PathReservationContentionTrackingTest`), not an accident; the whole-route release is the
	 * only release-path clear. This keeps [blockedSince] from
	 * conflating a stale, already-resolved contention with a fresh one on the same block
	 * later, and keeps [flushUnresolvedConflicts] from reporting contention that actually
	 * resolved.
	 *
	 * Records the closed blocked episode for [blockedSeconds] (Issue #1305) at the simulation clock. With no
	 * running kernel there is no clock: the episode is then kept apart, still forgotten as a contention, and
	 * the end-of-run [flushUnresolvedConflicts] closes it at the run's end time.
	 */
	fun clearBlockedTracking(trainId: String) {
		// Issue #1305: record the closed episode; the clock is read only when the train was blocked. With no
		// running kernel there is no close time, so the flush closes the episode at the end time instead.
		val since = earliestOpenSince()[trainId] ?: return
		val now = runCatching { Process.time() }.getOrNull()
		if (now != null) {
			addClosed(trainId, now - since)
		} else {
			unclosedSince[trainId] = minOf(unclosedSince[trainId] ?: since, since)
		}
		blockedSince.keys.removeAll { it.first == trainId }
	}

	/**
	 * The open contentions, in the order first observed, with the owner [ownerOf] names now; a copy
	 * (Issue #1305). Read-only: see [ContentionQuery.openContentions].
	 */
	fun openContentions(ownerOf: (DynamicTrackBlock) -> String?): List<OpenContention> =
		blockedSince.map { (key, since) -> OpenContention(key.first, key.second, ownerOf(key.second), since) }

	/**
	 * Blocked seconds per train: the closed episodes plus the open one up to [now] (Issue #1305). An episode runs from
	 * the train's earliest open contention to the clear, so contentions on several blocks count once. Read-only: see
	 * [ContentionQuery.blockedSeconds].
	 */
	fun blockedSeconds(now: Double): Map<String, Double> {
		val total = closedBlockedSeconds.toMutableMap()
		for (episodes in listOf(earliestOpenSince(), unclosedSince)) {
			for ((trainId, since) in episodes) {
				total[trainId] = (total[trainId] ?: 0.0) + (now - since).coerceAtLeast(0.0)
			}
		}
		return total
	}

	/** Add the open episode of [trainId], from its earliest open contention to [until], to [closedBlockedSeconds]. */
	private fun closeEpisode(
		trainId: String,
		until: Double
	) {
		val since = earliestOpenSince()[trainId] ?: return
		addClosed(trainId, until - since)
	}

	private fun addClosed(
		trainId: String,
		seconds: Double
	) {
		closedBlockedSeconds[trainId] = (closedBlockedSeconds[trainId] ?: 0.0) + seconds.coerceAtLeast(0.0)
	}

	/** The earliest open-contention start of every blocked train. */
	private fun earliestOpenSince(): Map<String, Double> {
		val earliest = mutableMapOf<String, Double>()
		for ((key, since) in blockedSince) {
			val trainId = key.first
			earliest[trainId] = minOf(earliest[trainId] ?: since, since)
		}
		return earliest
	}

	/**
	 * The ids of the trains that currently have at least one blocked-path contention tracked.
	 *
	 * Read-only snapshot that exists so tests can observe the real tracker without the destructive
	 * [flushUnresolvedConflicts], which empties the map and also drops any contention whose block
	 * no longer has an owner -- so it cannot tell a cleared entry from a dropped ownerless one.
	 */
	fun blockedTrainIds(): Set<String> = blockedSince.keys.map { it.first }.toSet()

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
