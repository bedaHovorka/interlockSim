/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Deterministic rule-based dispatcher — the baseline implementation of the
 * [Dispatcher] seam introduced by SP0.1 (Issue #540), reshaped to a pure
 * decision function by SP0.7 (Issue #729).
 *
 * ## Dispatch policy
 *
 * Each call to [decide]:
 * 1. **Admission** — admits trains from the head of
 *    [DispatchObservation.unapprovedTrains] until [maxConcurrentTrains]
 *    simultaneous trains are active.
 * 2. **Path advancement** — for every [DispatchObservation.innerBlockEnds] and
 *    [DispatchObservation.outerBlockEnds] entry, reserves the next forward path
 *    for any approaching or reserved train.
 *
 * The shell ([ShuntingLoop]) calls [decide] once per phase (see [Dispatcher]
 * KDoc), so a single call only ever sees a non-empty [DispatchObservation.unapprovedTrains]
 * *or* non-empty block-end lists, never both.
 *
 * ## Determinism (Goal 10 Stage A3)
 * Train admission is strictly FIFO over [DispatchObservation.unapprovedTrains].
 * Path selection is applied by the shell via
 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.reservePathToAnyNextSemaphore],
 * which produces deterministic results for a fixed network topology. Given the
 * same XML network and train generation sequence, this dispatcher produces
 * identical decision sequences across consecutive runs.
 *
 * ## Both-ends evaluation
 * Each end of an inner block is evaluated independently (no short-circuit). In
 * the real domain model a block's occupant can only be approaching one end
 * ([cz.vutbr.fit.interlockSim.objects.core.TrackOccupant.nextSemaphore] is
 * single-valued) and a reserved block's path is only ever set up toward one end
 * ([cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.isSetUpPath] is
 * directional), so at most one end of a real block is ever eligible for a
 * decision per tick — independent evaluation is behaviorally identical to the
 * pre-#729 short-circuiting version for real data. The short-circuit itself is
 * no longer possible under a pure [decide]: it depended on the *live return
 * value* of a reservation attempt, which is unavailable before the shell applies
 * the returned [DispatchDecision]s.
 *
 * ## Goal 9 integration hook
 * This class is the designated place for wiring Goal 9's
 * [cz.vutbr.fit.interlockSim.sim.conflict.AutoConflictResolutionService] and
 * [cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolutionRanker]: when the
 * shell's path reservation attempt returns a
 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.Conflict],
 * conflict resolution can be applied in a future iteration without touching
 * [ShuntingLoop]. The hook is intentionally left as a comment for SP2b.2 (#557).
 *
 * @param maxConcurrentTrains Maximum number of simultaneously approved trains.
 *   Defaults to [DEFAULT_MAX_CONCURRENT_TRAINS] which matches the physical capacity
 *   of the `vyhybna.xml` shunting loop (2 parallel tracks).
 *
 * @see Dispatcher
 * @see ShuntingLoop
 * @since Issue #540 (SP0.1 — Goal 10); reshaped to a pure seam in Issue #729
 *   (SP0.7 — Goal 10)
 */
class RuleBasedDispatcher(
	val maxConcurrentTrains: Int = DEFAULT_MAX_CONCURRENT_TRAINS
) : Dispatcher {
	companion object {
		/** Physical capacity of the vyhybna shunting loop: k1 and k2. */
		const val DEFAULT_MAX_CONCURRENT_TRAINS: Int = 2
		private val logger = KotlinLogging.logger {}
	}

	init {
		require(maxConcurrentTrains > 0) {
			"maxConcurrentTrains must be positive, got: $maxConcurrentTrains"
		}
	}

	override fun decide(observed: DispatchObservation): List<DispatchDecision> {
		val decisions = decideAdmissions(observed) + decidePathAdvancements(observed)
		return decisions.ifEmpty { listOf(DispatchDecision.NoAction) }
	}

	// ── Train admission ─────────────────────────────────────────────────────

	private fun decideAdmissions(observed: DispatchObservation): List<DispatchDecision.ApproveTrain> {
		val freeSlots = maxConcurrentTrains - observed.approvedTrainCount
		if (freeSlots <= 0) {
			return emptyList()
		}
		return observed.unapprovedTrains.take(freeSlots).map { queued ->
			logger.debug { "RuleBasedDispatcher: approving ${queued.trainId}" }
			DispatchDecision.ApproveTrain(queued.trainId)
		}
	}

	// ── Path advancement ────────────────────────────────────────────────────

	private fun decidePathAdvancements(observed: DispatchObservation): List<DispatchDecision.ReservePath> =
		(observed.innerBlockEnds + observed.outerBlockEnds).mapNotNull(::decideForEnd)

	/**
	 * Decides whether a forward path reservation is warranted from [end].
	 *
	 * @return A [DispatchDecision.ReservePath] when the block state warrants one,
	 *   `null` when the state requires no action (FREE, or occupied/reserved but
	 *   not eligible toward this end, or already extended).
	 */
	private fun decideForEnd(end: BlockEndObservation): DispatchDecision.ReservePath? =
		when (end.state) {
			TrackFacility.State.FREE -> null

			TrackFacility.State.OCCUPIED -> {
				if (!end.isApproachingThisEnd) {
					null
				} else if (end.pathAlreadyExtendedBeyond) {
					logger.debug {
						"Path already extends beyond ${end.towardSemaphoreName} for ${end.ownerTrainId}, " +
							"skipping redundant reservation"
					}
					null
				} else {
					logger.debug {
						"Train ${end.ownerTrainId} approaching ${end.towardSemaphoreName}, reserving forward path"
					}
					DispatchDecision.ReservePath(requireNotNull(end.ownerTrainId), end.towardSemaphoreName)
				}
			}

			TrackFacility.State.RESERVED -> {
				if (!end.pathSetUpTowardThisEnd) {
					null
				} else if (end.pathAlreadyExtendedBeyond) {
					logger.debug {
						"Path already extends beyond ${end.towardSemaphoreName} for ${end.ownerTrainId} " +
							"(reserved block), skipping"
					}
					null
				} else {
					logger.debug {
						"Path already set up through ${end.towardSemaphoreName}, attempting extension"
					}
					DispatchDecision.ReservePath(requireNotNull(end.ownerTrainId), end.towardSemaphoreName)
				}
			}
		}
}
