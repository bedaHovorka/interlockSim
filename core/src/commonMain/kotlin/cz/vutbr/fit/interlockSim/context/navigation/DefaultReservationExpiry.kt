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

import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.abs
import kotlin.math.round

/**
 * The kernel-side reservation expiry policy (Issue #1174, absorbs #975; phase 2 Issue #1220). Every
 * release goes through [RouteReleaser.releasePathDetailed] — never [PathReservationService.releasePath]
 * or a raw registry removal — so it is the one `cancel_route` and the orphan sweeper use: signals
 * reset to STOP first, blocks cancelled, switches unlocked and unregistered, the PathInfo trimmed or
 * removed, one `BlockReleased` per block. Blocks a train stands on are never released.
 *
 * **Phase 1: the un-entered route.** Every holding [PathReservationRegistry.expiredHoldings] reports
 * — the train occupies nothing, and its newest registration is a TTL old — is released whole: the
 * same rule `OrphanReservationSweeper` applies before its `releaseRouteDetailed`. The registry's
 * query sees only the blocks it attributes to a train, so a train physically standing on any other
 * block ([RouteCandidateScreen.standingTrainIds], the Issue #893 state) is dropped here too.
 *
 * **Phase 2: the stalled tail of a train on the track** (traffic-simulation-expert ruling D1–D9 on
 * #1220). For every train [PathReservationRegistry.occupyingHoldings] reports, this class keeps a
 * [ProgressSnapshot]: the blocks it stands on, its un-entered RESERVED tail and the occupant's
 * [cz.vutbr.fit.interlockSim.objects.core.TrackOccupant.distanceToSemaphore] reading. The reading is
 * a change detector, not a distance: it moves while the train moves inside a block, jumps at block
 * boundaries and stays constant while the train stands. The stall clock restarts whenever any of the
 * three changes, the reading by more than [PROGRESS_THRESHOLD_METERS] (an asymptotic approach,
 * Issue #797, creeps by less), so a new registration restarts it too. A train stalled for the TTL
 * hands its tail to the same release: it keeps the occupied and approach-locked blocks and frees
 * the rest, or frees nothing when the PathInfo cannot be trimmed to the kept blocks. A train with no
 * readable occupant of its own is not judged on that call; a train with no tail (the #943 waiter at
 * a STOP signal, which has consumed its leg) is skipped silently. This expiry's own release is not
 * progress: the snapshot is taken again afterwards and keeps its clock, so a deferred head is
 * retried on the next call. The snapshot of a train that leaves the set is dropped.
 *
 * **Approach lock (Issue #1050).** A proceed aspect at the head of the released blocks may have a
 * train committed to it; the release then keeps that block, drops the signals to STOP and frees
 * the rest. The holding is offered again on the next call and freed once the booking window has
 * closed in simulation time. A completion is logged and counted once, on the call that frees the
 * last un-entered block; a deferral is logged at INFO once per train, later ones at DEBUG (ruling
 * D5): a tail whose PathInfo cannot be trimmed is deferred on every call while the train stands.
 *
 * [expireStale] with no exempt train is the loop tick ([ReservationExpiry.expireStaleReservations]
 * on the service): the simulation loops call it once per control step, and it judges every train.
 * [DefaultPathReservationService.reservePath] and [DefaultPathReservationService.reservePathToAny]
 * call it at their top with the requesting train exempt. It never runs per attempt inside
 * [PathReservationService.reservePathToAnyNextSemaphore], whose attempts call the internal
 * [DefaultPathReservationService.reservePath] overload so an expiry cannot move the before/after
 * snapshots its bypass rollback relies on, and
 * [PathReservationService.isPathToAnyNextSemaphoreAvailable] stays a pure query. A release that
 * throws is contained and logged, so it cannot fail the request that triggered it; the holding is
 * offered again on the next call.
 *
 * Extracted from [DefaultPathReservationService] in the SP12 decomposition (the service stays the
 * single facade; the owner thread on the PR body of #1226): the public class delegates the trigger
 * here and keeps no expiry state of its own. The phase-2 progress state lives here too, never in
 * the registry (ruling D3).
 *
 * **Thread safety: none, like [DefaultPathReservationService]** — runs on the simulation thread,
 * like every other reservation call.
 *
 * @param ttlSimSeconds The configured reservation TTL, in simulated seconds: the service
 *   constructor's `reservationTtlSimSeconds`, which defaults to
 *   [DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS]. One TTL for both phases
 *   (ruling D8)
 * @param expiryRelease The release [expireStale] hands a stale holding or a stalled tail to: always
 *   [RouteReleaser.releasePathDetailed] in production. A constructor parameter rather than a writable
 *   hook, so a test that must pin that a throwing release is contained (Issue #1174) builds its own
 *   expiry over the same registry and candidate screen
 * @since Issue #1174
 */
internal class DefaultReservationExpiry(
	private val registry: PathReservationRegistry,
	private val candidateScreen: RouteCandidateScreen,
	private val ttlSimSeconds: Double,
	private val expiryRelease: (String) -> PathRelease
) {
	companion object {
		/** Rounding factor of the logged expiry age (one decimal). */
		private const val TENTHS: Double = 10.0

		/**
		 * A change of the progress reading above this many metres is progress (Issue #1220, ruling
		 * D2): a train that advances less than 1 cm within the TTL has stopped advancing.
		 */
		internal const val PROGRESS_THRESHOLD_METERS: Double = 0.01
	}

	/**
	 * What phase 2 last saw of one occupying train (Issue #1220, ruling D1): the blocks it stands on,
	 * its un-entered RESERVED tail and its progress reading, unchanged since [sinceSimTime].
	 */
	private data class ProgressSnapshot(
		val occupied: Set<DynamicTrackBlock>,
		val reserved: Set<DynamicTrackBlock>,
		val reading: Double,
		val sinceSimTime: Double
	) {
		/** Whether [current] shows progress over this snapshot: any set changed, or the reading moved. */
		fun isProgressIn(current: ProgressSnapshot): Boolean =
			occupied != current.occupied ||
				reserved != current.reserved ||
				abs(current.reading - reading) > PROGRESS_THRESHOLD_METERS
	}

	/** A train phase 2 has found stalled for at least the TTL. */
	private class StalledTail(
		val holding: OccupyingHolding,
		val snapshot: ProgressSnapshot,
		val stalledForSimSeconds: Double
	)

	private val logger = KotlinLogging.logger {}

	/**
	 * Holdings [expireStale] has released completely since this expiry was built
	 * (Issue #1174). A holding approach locking deferred is counted once, when its release completes.
	 */
	internal var expiredHoldingCount: Int = 0
		private set

	/**
	 * Stalled RESERVED tails of occupying trains [expireStale] has released completely (Issue
	 * #1220), counted once, on the call that frees the last un-entered block.
	 */
	internal var expiredTailCount: Int = 0
		private set

	/**
	 * Trains whose expiry release threw and was logged at WARN; later failures log at DEBUG
	 * (Issue #1174). An entry clears when the train leaves the candidate set (occupancy caught it,
	 * a new claim restarted its clock, or its release completed) or on an expiry that did not
	 * attempt its holding at all -- except the exempt requester of the current call, whose entry
	 * survives: its stale holding is still there, unchanged.
	 */
	private val failedExpiryTrains = mutableSetOf<String>()

	/**
	 * Trains whose deferred expiry was logged at INFO; later deferrals log at DEBUG (Issue #1220
	 * ruling D5, #1228). Cleared like [failedExpiryTrains].
	 */
	private val deferralLoggedTrains = mutableSetOf<String>()

	/** Phase 2's last snapshot per occupying train with a tail (Issue #1220, ruling D3). */
	private val progress = mutableMapOf<String, ProgressSnapshot>()

	/**
	 * Release every stale holding and every stalled tail, and return how many of them it released
	 * completely. [exceptTrainId] is `null` on the loop tick, which judges every train; at the top
	 * of a request it is the requesting train, which is exempt (Issue #1174 ruling): its new claim
	 * restarts its clock, as it restarts the #847 sweeper's. Expiring it first would empty its
	 * footprint, so `rejectNonContiguousStart` would pass vacuously (ruling D8) and a mid-station
	 * extension would be granted as an orphan route. If the request fails, the next loop tick
	 * expires the holding. The exempt train's phase-2 snapshot is neither judged nor taken.
	 */
	internal fun expireStale(exceptTrainId: String?): Int {
		val stale = staleHoldings(exceptTrainId)
		val stalled = stalledTails(exceptTrainId)
		if (stale.isEmpty() && stalled.isEmpty() && failedExpiryTrains.isEmpty() && deferralLoggedTrains.isEmpty()) {
			return 0
		}
		// A train no longer in the candidate set gets a fresh WARN / INFO should it fail or defer again.
		// The exempt requester's holding is not attempted here, so its old markers must survive
		// the call: a failed self-request may not silence the next tick's WARN (Issue #1174).
		val attempted = stale.map { it.trainId } + stalled.map { it.holding.trainId } + listOfNotNull(exceptTrainId)
		failedExpiryTrains.retainAll(attempted.toSet())
		deferralLoggedTrains.retainAll(attempted.toSet())
		val completedHoldings = stale.count { expireHolding(it) }
		val completedTails = stalled.count { expireTail(it) }
		expiredHoldingCount += completedHoldings
		expiredTailCount += completedTails
		return completedHoldings + completedTails
	}

	/** Phase 1's candidates: stale whole-route holdings of trains that stand on nothing. */
	private fun staleHoldings(exceptTrainId: String?): List<ExpiredHolding> {
		val reported = registry.expiredHoldings(ttl = ttlSimSeconds)
		if (reported.isEmpty()) return reported
		// One graph scan per call, not one per candidate (Issue #1229).
		val standing = candidateScreen.standingTrainIds()
		return reported
			.filter { it.trainId != exceptTrainId }
			// The registry judges occupancy only over the blocks it attributes to the train; a train
			// standing on a block it holds no entry for (Issue #893) is on the track all the same.
			.filterNot { it.trainId in standing }
	}

	/**
	 * Phase 2's candidates (Issue #1220): take every occupying train's snapshot, restart the clock
	 * of each that progressed, and return those stalled for at least the TTL.
	 */
	private fun stalledTails(exceptTrainId: String?): List<StalledTail> {
		val holdings = registry.occupyingHoldings()
		// Pruned to the trains still on the track with a holding: a train that left the registry, or
		// stopped standing on what it holds, starts a fresh clock should it ever be back.
		progress.keys.retainAll(holdings.mapTo(HashSet()) { it.trainId })
		if (holdings.isEmpty()) return emptyList()
		val now = currentSimulationTime()
		return holdings
			.filter { it.trainId != exceptTrainId }
			.mapNotNull { judge(it, now) }
	}

	/** One occupying train's stall verdict at [now]; updates its snapshot. */
	private fun judge(
		holding: OccupyingHolding,
		now: Double
	): StalledTail? {
		val trainId = holding.trainId
		if (holding.reserved.isEmpty()) {
			// Nothing to free (ruling D5): no clock either -- a new tail starts one of its own.
			progress.remove(trainId)
			return null
		}
		// Not judged on this call (ruling D2): an OCCUPIED booking, or somebody else on its block.
		val reading = readingOf(holding) ?: return null
		val current = ProgressSnapshot(holding.occupied.toSet(), holding.reserved.toSet(), reading, now)
		val last = progress[trainId]
		if (last == null || last.isProgressIn(current)) {
			progress[trainId] = current
			return null
		}
		val stalledFor = now - last.sinceSimTime
		return if (stalledFor >= ttlSimSeconds) StalledTail(holding, last, stalledFor) else null
	}

	/** The progress reading of [holding]'s own occupant, or `null` when none of its blocks has one. */
	private fun readingOf(holding: OccupyingHolding): Double? =
		holding.occupied
			.firstNotNullOfOrNull { block -> block.occupant?.takeIf { it.name == holding.trainId } }
			?.distanceToSemaphore()

	/** Release one stale [holding]; `true` when nothing of it is left. */
	private fun expireHolding(holding: ExpiredHolding): Boolean {
		val trainId = holding.trainId
		val age = roundedToTenth(holding.ageSimSeconds)
		val release = releaseContained(trainId) ?: return false
		if (release.deferred.isNotEmpty()) {
			logDeferral(trainId) {
				"Reservation expiry deferred for '$trainId': no new claim for ${age}s of simulated time " +
					"(TTL ${ttlSimSeconds}s), but approach locking kept ${release.deferred.size} " +
					"block(s) reserved with signals at STOP; retried on the next call (Issue #1174)"
			}
			return false
		}
		deferralLoggedTrains.remove(trainId)
		logger.info {
			"Reservation expired for '$trainId': no new claim for ${age}s of simulated time " +
				"(TTL ${ttlSimSeconds}s); released the remaining ${release.released.size} un-entered " +
				"block(s) through releasePathDetailed (Issue #1174)"
		}
		return true
	}

	/**
	 * Release the un-entered tail of one [stalled] train (Issue #1220); `true` when none of its
	 * tail is left. The blocks it stands on stay: the release keeps them.
	 */
	private fun expireTail(stalled: StalledTail): Boolean {
		val trainId = stalled.holding.trainId
		val age = roundedToTenth(stalled.stalledForSimSeconds)
		val release = releaseContained(trainId) ?: return false
		val remaining = registry.getBlocks(trainId).filter(::isUnenteredReservation)
		if (remaining.isNotEmpty()) {
			// This expiry's own release is not progress: keep the clock, so the rest is retried.
			progress[trainId] = stalled.snapshot.copy(reserved = remaining.toSet())
			logDeferral(trainId) {
				"Reservation tail expiry deferred for '$trainId': no progress for ${age}s of simulated time " +
					"(TTL ${ttlSimSeconds}s), but ${remaining.size} un-entered block(s) stay reserved -- " +
					"approach locking, or a PathInfo that cannot be trimmed to the blocks the train stands " +
					"on; retried on the next call (Issue #1220)"
			}
			return false
		}
		progress.remove(trainId)
		deferralLoggedTrains.remove(trainId)
		logger.info {
			"Reservation tail expired for '$trainId': no progress for ${age}s of simulated time " +
				"(TTL ${ttlSimSeconds}s); released ${release.released.size} un-entered block(s) through " +
				"releasePathDetailed and kept the ${stalled.holding.occupied.size} block(s) it stands on " +
				"(Issue #1220)"
		}
		return true
	}

	/**
	 * [expiryRelease] for [trainId], contained: a release that throws is logged at WARN once per
	 * train, then at DEBUG (Issue #1174), and yields `null`.
	 */
	private fun releaseContained(trainId: String): PathRelease? {
		val release =
			try {
				expiryRelease(trainId)
			} catch (e: Exception) {
				if (failedExpiryTrains.add(trainId)) {
					logger.warn(e) {
						"Reservation expiry of '$trainId' failed; the holding is offered again (Issue #1174)"
					}
				} else {
					logger.debug { "Reservation expiry of '$trainId' failed again: ${e.message} (Issue #1174)" }
				}
				return null
			}
		failedExpiryTrains.remove(trainId)
		return release
	}

	/** Log a deferral of [trainId] at INFO the first time, at DEBUG afterwards (Issue #1220 ruling D5, #1228). */
	private fun logDeferral(
		trainId: String,
		message: () -> String
	) {
		if (deferralLoggedTrains.add(trainId)) logger.info(message) else logger.debug(message)
	}

	/** [value] rounded to one decimal, so the expiry log lines stay stable to grep. */
	private fun roundedToTenth(value: Double): Double = round(value * TENTHS) / TENTHS
}
