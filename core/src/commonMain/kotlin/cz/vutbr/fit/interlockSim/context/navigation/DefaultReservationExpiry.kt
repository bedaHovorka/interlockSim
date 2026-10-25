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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.round

/**
 * The kernel-side reservation expiry policy (Issue #1174, absorbs #975): every holding
 * [PathReservationRegistry.expiredHoldings] reports is released through
 * [RouteReleaser.releasePathDetailed] — never [PathReservationService.releasePath] or a raw registry
 * removal — so the release is the one `cancel_route` and the orphan sweeper use: signals reset to
 * STOP first, blocks cancelled, switches unlocked and unregistered, the PathInfo removed, one
 * `BlockReleased` per block. Only trains that occupy nothing qualify, which is the same whole-route
 * rule `OrphanReservationSweeper` applies before its `releaseRouteDetailed`. The registry's query
 * sees only the blocks it attributes to a train, so a train physically standing on any other block
 * ([RouteCandidateScreen.standsOnAnyBlock], the Issue #893 state) is dropped here too: a train on
 * the track is the occupying-train case, phase 2 (#1220).
 *
 * **Approach lock (Issue #1050).** A proceed aspect at the head of the holding may have a train
 * committed to it; the release then keeps that block, drops the signals to STOP and frees the rest.
 * The holding is reported again on the next call and freed once the booking window has closed in
 * simulation time — or, if the committed train booked the block meanwhile, it now occupies track and
 * never expires. The completion is logged and counted once, on the call that frees the last block.
 *
 * [expireStaleReservations] is the loop tick: the simulation loops call it once per control step,
 * and it judges every train. [DefaultPathReservationService.reservePath] and
 * [DefaultPathReservationService.reservePathToAny] call [expireStale] at their top instead, which
 * exempts the requesting train. It never runs per attempt inside
 * [PathReservationService.reservePathToAnyNextSemaphore], whose attempts call the internal
 * [DefaultPathReservationService.reservePath] overload so an expiry cannot move the before/after
 * snapshots its bypass rollback relies on, and
 * [PathReservationService.isPathToAnyNextSemaphoreAvailable] stays a pure query. A release that
 * throws is contained and logged, so it cannot fail the request that triggered it; the holding is
 * offered again on the next call.
 *
 * Extracted from [DefaultPathReservationService] in the SP12 decomposition (the service stays the
 * single facade; the owner thread on the PR body of #1226): the public class delegates the trigger
 * here and keeps no expiry state of its own.
 *
 * **Thread safety: none, like [DefaultPathReservationService]** — runs on the simulation thread,
 * like every other reservation call.
 *
 * @param ttlSimSeconds The configured reservation TTL, in simulated seconds: the service
 *   constructor's `reservationTtlSimSeconds`, which defaults to
 *   [DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS]
 * @param initialRelease The production release handoff, [RouteReleaser.releasePathDetailed]
 * @since Issue #1174
 */
internal class DefaultReservationExpiry(
	private val registry: PathReservationRegistry,
	private val candidateScreen: RouteCandidateScreen,
	private val ttlSimSeconds: Double,
	initialRelease: (String) -> PathRelease
) : ReservationExpiry {
	companion object {
		/** Rounding factor of the logged expiry age (one decimal). */
		private const val TENTHS: Double = 10.0
	}

	private val logger = KotlinLogging.logger {}

	/**
	 * The release [expireStaleReservations] hands a stale holding to: always
	 * [RouteReleaser.releasePathDetailed] in production. Replaced only by tests, to pin that a
	 * throwing release is contained (Issue #1174).
	 */
	internal var expiryRelease: (String) -> PathRelease = initialRelease

	/**
	 * Holdings [expireStaleReservations] has released completely since this expiry was built
	 * (Issue #1174). A holding approach locking deferred is counted once, when its release completes.
	 */
	internal var expiredHoldingCount: Int = 0
		private set

	/**
	 * Trains whose expiry release threw and was logged at WARN; later failures log at DEBUG
	 * (Issue #1174). An entry clears when the train leaves the candidate set (occupancy caught it,
	 * a new claim restarted its clock, or its release completed) or on an expiry that did not
	 * attempt its holding at all -- except the exempt requester of the current call, whose entry
	 * survives: its stale holding is still there, unchanged.
	 */
	private val failedExpiryTrains = mutableSetOf<String>()

	override fun expireStaleReservations(): Int = expireStale(exceptTrainId = null)

	/**
	 * [expireStaleReservations] at the top of a request by [exceptTrainId], which is exempt (Issue
	 * #1174 ruling): its new claim restarts its clock, as it restarts the #847 sweeper's. Expiring it
	 * first would empty its footprint, so `rejectNonContiguousStart` would pass vacuously (ruling D8)
	 * and a mid-station extension would be granted as an orphan route. If the request fails, the next
	 * loop tick expires the holding.
	 */
	internal fun expireStale(exceptTrainId: String?): Int {
		// Paid at every request top and loop tick: the common case (nothing stale, no failure
		// remembered) must stop right at the query, before any chain or set is built.
		val reported = registry.expiredHoldings(ttl = ttlSimSeconds)
		if (reported.isEmpty() && failedExpiryTrains.isEmpty()) return 0
		val stale =
			reported
				.filter { it.trainId != exceptTrainId }
				// The registry judges occupancy only over the blocks it attributes to the train; a train
				// standing on a block it holds no entry for (Issue #893) is on the track all the same.
				.filterNot { candidateScreen.standsOnAnyBlock(it.trainId) }
		// A train no longer in the candidate set gets a fresh WARN should its release throw again.
		// The exempt requester's holding is not attempted here, so its old marker must survive
		// the call: a failed self-request may not silence the next tick's WARN (Issue #1174).
		failedExpiryTrains.retainAll(stale.map { it.trainId }.toSet() + setOfNotNull(exceptTrainId))
		val completed = stale.count { expireHolding(it) }
		expiredHoldingCount += completed
		return completed
	}

	/** Release one stale [holding]; `true` when nothing of it is left. */
	private fun expireHolding(holding: ExpiredHolding): Boolean {
		val trainId = holding.trainId
		val age = roundedToTenth(holding.ageSimSeconds)
		val release =
			try {
				expiryRelease(trainId)
			} catch (e: Exception) {
				if (failedExpiryTrains.add(trainId)) {
					logger.warn(e) { "Reservation expiry of '$trainId' failed; the holding is offered again (Issue #1174)" }
				} else {
					logger.debug { "Reservation expiry of '$trainId' failed again: ${e.message} (Issue #1174)" }
				}
				return false
			}
		failedExpiryTrains.remove(trainId)
		if (release.deferred.isNotEmpty()) {
			logger.info {
				"Reservation expiry deferred for '$trainId': no new claim for ${age}s of simulated time " +
					"(TTL ${ttlSimSeconds}s), but approach locking kept ${release.deferred.size} " +
					"block(s) reserved with signals at STOP; retried on the next call (Issue #1174)"
			}
			return false
		}
		logger.info {
			"Reservation expired for '$trainId': no new claim for ${age}s of simulated time " +
				"(TTL ${ttlSimSeconds}s); released the remaining ${release.released.size} un-entered " +
				"block(s) through releasePathDetailed (Issue #1174)"
		}
		return true
	}

	/** [value] rounded to one decimal, so the expiry log lines stay stable to grep. */
	private fun roundedToTenth(value: Double): Double = round(value * TENTHS) / TENTHS
}
