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

import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Deterministic rule-based dispatcher — the baseline implementation of the
 * [Dispatcher] seam introduced by SP0.1 (Issue #540).
 *
 * ## Dispatch policy
 *
 * Each iteration, the shell calls [approve] (before the polling `hold()`) and then
 * [advancePaths] (after it):
 * 1. **[approve]** — admits trains from the head of
 *    [DispatcherTickContext.unapprovedTrains] until [maxConcurrentTrains]
 *    simultaneous trains are active.
 * 2. **[advancePaths]** — advances paths on inner blocks: for each
 *    [DispatcherTickContext.innerBlocks] entry (RailSemaphore–RailSemaphore),
 *    checks both semaphore endpoints and reserves the next forward path for any
 *    approaching or reserved train.
 * 3. **[advancePaths]** — advances paths on outer blocks: for each
 *    [DispatcherTickContext.outerBlocks] entry (InOut–RailSemaphore), checks the
 *    semaphore endpoint and reserves the next forward path.
 *
 * ## Determinism (Goal 10 Stage A3)
 * Train admission is strictly FIFO over [DispatcherTickContext.unapprovedTrains].
 * Path selection delegates to
 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.reservePathToAnyNextSemaphore],
 * which produces deterministic results for a fixed network topology.  Given the
 * same XML network and train generation sequence, this dispatcher produces
 * identical outcomes across consecutive runs.
 *
 * ## Goal 9 integration hook
 * This class is the designated place for wiring Goal 9's
 * [cz.vutbr.fit.interlockSim.sim.conflict.AutoConflictResolutionService] and
 * [cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolutionRanker]: when a path
 * reservation returns a [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.Conflict],
 * conflict resolution can be applied here in a future iteration without touching
 * [ShuntingLoop].  The hook is intentionally left as a comment for SP2b.2 (#557).
 *
 * @param maxConcurrentTrains Maximum number of simultaneously approved trains.
 *   Defaults to [DEFAULT_MAX_CONCURRENT_TRAINS] which matches the physical capacity
 *   of the `vyhybna.xml` shunting loop (2 parallel tracks).
 *
 * @see Dispatcher
 * @see ShuntingLoop
 * @since Issue #540 (SP0.1 — Goal 10)
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

	/**
	 * Admits queued trains (FIFO) up to [maxConcurrentTrains] concurrent trains.
	 * Called by the shell before the per-iteration polling `hold()`.
	 */
	override fun approve(context: DispatcherTickContext) {
		approveTrains(context)
	}

	/**
	 * Advances forward paths on all monitored track blocks.  Called by the shell
	 * after the per-iteration polling `hold()` so block-state checks observe state
	 * after newly admitted trains have moved.
	 */
	override fun advancePaths(context: DispatcherTickContext) {
		for (block in context.innerBlocks) {
			checkBothEnds(block, context)
		}
		for ((block, sem) in context.outerBlocks) {
			checkOneEnd(block, sem, context)
		}
	}

	// ── Train admission ─────────────────────────────────────────────────────

	private fun approveTrains(context: DispatcherTickContext) {
		while (context.approvedTrainCount < maxConcurrentTrains) {
			val train = context.unapprovedTrains.firstOrNull() ?: break
			logger.debug { "RuleBasedDispatcher: approving ${train.name}" }
			context.approveTrain(train)
		}
	}

	// ── Path advancement ────────────────────────────────────────────────────

	/**
	 * Inner blocks have [DynamicRailSemaphore] ends only; checks both endpoints.
	 */
	private fun checkBothEnds(
		block: DynamicTrackBlock,
		context: DispatcherTickContext
	) {
		for (sep in block.ends()) {
			if (checkOneEnd(block, Util.assertInstanceOf<DynamicRailSemaphore>(sep), context)) {
				return
			}
		}
	}

	/**
	 * Attempts to reserve a forward path from [to] if the [block] state warrants it.
	 *
	 * @return `true` when a reservation was made or already exists, `false` when
	 *   the block state requires no action (FREE, or occupied/reserved but train not
	 *   yet approaching [to]).
	 */
	private fun checkOneEnd(
		block: DynamicTrackBlock,
		to: DynamicRailSemaphore,
		context: DispatcherTickContext
	): Boolean {
		if (block.getState() == TrackFacility.State.FREE) {
			return false
		}

		if (block.getState() == TrackFacility.State.OCCUPIED) {
			val occupant = requireSimulationNotNull(block.getTrackOccupant())
			if (occupant.nextSemaphore() != to) {
				return false
			}

			// Idempotent check — skip if path already extends beyond this semaphore.
			if (context.isPathExtendedBeyond(occupant.name, to)) {
				logger.debug {
					"Path already extends beyond ${to.name} for ${occupant.name}, skipping redundant reservation"
				}
				return true
			}

			logger.debug { "Train ${occupant.name} approaching ${to.name}, reserving forward path" }
			return context.reservePath(to, occupant.name)
		}

		if (block.getState() == TrackFacility.State.RESERVED) {
			if (context.isPathSetUp(block, to)) {
				val trainName = requireSimulationNotNull(block.trainName)

				// Idempotent check for reserved blocks.
				if (context.isPathExtendedBeyond(trainName, to)) {
					logger.debug {
						"Path already extends beyond ${to.name} for $trainName (reserved block), skipping"
					}
					return true
				}

				logger.debug { "Path already set up through ${to.name}, attempting extension" }
				return context.reservePath(to, trainName)
			}
		}

		return false
	}
}
