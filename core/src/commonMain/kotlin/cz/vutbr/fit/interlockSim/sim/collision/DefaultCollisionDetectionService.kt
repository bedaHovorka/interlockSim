/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.collision

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of [CollisionDetectionService].
 *
 * SP1 (#611) shipped this as an intentionally thin backbone: warning-listener registry,
 * warning emission, and the [PauseController] bridge. Detection rules are added per
 * Goal 3 sub-phase so each rule ships with its own dedicated tests:
 * - [CollisionWarning.ReservationConflict] detection  → SP2 (#612) — implemented here
 * - [CollisionWarning.BlockEntryViolation] detection  → SP3 (#613) — deferred
 * - [CollisionWarning.PredictiveCollision] detection   → SP4 (#614) — deferred
 *
 * **Detection rules (SP2, #612):**
 * - [CollisionWarning.ReservationConflict]: emitted when [BlockEvent.BlockReserved] fires
 *   for a block whose [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.trainName]
 *   already belongs to a different train — indicating a double-reservation.
 * - [CollisionWarning.ReservationConflict]: also emitted when
 *   [BlockEvent.ReservationConflictDetected] fires (path blocked by a different train
 *   during [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.reservePath]).
 *   Duplicate warnings for the same conflict pair are suppressed within a
 *   [DEDUP_WINDOW_SECONDS]-second window.
 *
 * The [BlockEvent] subscription is registered in the constructor so it is buffered by
 * [SimulationEnvironment.onBlockEvent] before
 * [cz.vutbr.fit.interlockSim.context.SimulationContext.run] is called.
 *
 * When a warning is emitted, the service:
 * 1. Calls all registered [onCollisionWarning] listeners synchronously. Each listener
 *    is isolated — a throwing listener is logged and does **not** prevent the remaining
 *    listeners or the pause request.
 * 2. Calls [PauseController.requestPause] so the operator can inspect the state.
 *
 * @param pauseController Receives [PauseController.requestPause] on every emitted warning.
 * @param env The simulation environment used to subscribe to block events. Production
 *   wiring (CoreModule) always provides it; `null` is permitted only for unit tests that
 *   exercise the emission machinery directly via [emitWarning].
 * @since Issue #611 (Goal 3 SP1)
 */
class DefaultCollisionDetectionService(
	private val pauseController: PauseController,
	env: SimulationEnvironment? = null
) : CollisionDetectionService {
	private val listeners: MutableList<(CollisionWarning) -> Unit> = mutableListOf()

	/**
	 * Deduplication state for [BlockEvent.ReservationConflictDetected].
	 *
	 * Key: ordered pair (trainId, conflictingTrainId).
	 * Value: simulation time of the last emitted warning for that pair.
	 *
	 * Entries are kept small because a typical simulation has at most a handful of
	 * concurrent trains. No eviction is needed beyond the time-window guard.
	 */
	private val lastConflictWarningTime: MutableMap<Pair<String, String>, Double> = mutableMapOf()

	init {
		// Subscribe to domain-level block events (reserve / occupancy changes).
		// The call happens before run(), so it is buffered by DefaultSimulationContext.
		env?.onBlockEvent { event -> handleBlockEvent(event) }
	}

	override fun onCollisionWarning(listener: (CollisionWarning) -> Unit) {
		listeners += listener
	}

	// ── Internal helpers ──────────────────────────────────────────────────────

	private fun emit(warning: CollisionWarning) {
		logger.warn { "Collision warning: $warning" }
		// Snapshot the listener list so a re-entrant onCollisionWarning during delivery
		// cannot trigger a ConcurrentModificationException, and isolate each listener so
		// one bad callback cannot prevent the pause or starve later listeners.
		listeners.toList().forEach { listener ->
			try {
				listener(warning)
			} catch (e: Throwable) {
				logger.error(e) {
					"Collision warning listener threw; continuing to remaining listeners and pause"
				}
			}
		}
		pauseController.requestPause()
	}

	private fun handleBlockEvent(event: BlockEvent) {
		when (event) {
			is BlockEvent.BlockReserved -> {
				// Check whether the block is already reserved for a different train.
				val existingOwner = event.block.trainName
				if (existingOwner != null && existingOwner != event.trainId) {
					emit(
						CollisionWarning.ReservationConflict(
							trainId = event.trainId,
							conflictingTrainId = existingOwner,
							time = event.time,
							conflictingBlock = event.block
						)
					)
				}
			}
			is BlockEvent.ReservationConflictDetected -> handleReservationConflictDetected(event)
			is BlockEvent.OccupancySet,
			is BlockEvent.BlockReleased,
			is BlockEvent.OccupancyCleared
			-> {
				// No SP2 rule: OccupancySet is handled by the SP3 BlockEntryViolation
				// rule (#613); release events do not indicate a hazard.
			}
		}
	}

	/**
	 * Re-emit [BlockEvent.ReservationConflictDetected] as a [CollisionWarning.ReservationConflict]
	 * after applying a [DEDUP_WINDOW_SECONDS]-second deduplication window per conflict pair.
	 *
	 * Two trains repeatedly competing for the same path would produce a
	 * [BlockEvent.ReservationConflictDetected] on every reservation attempt. Without
	 * deduplication this creates log spam and pauses the simulation too frequently.
	 * Warnings for the same (trainId, conflictingTrainId) pair are suppressed if the
	 * previous warning was emitted within [DEDUP_WINDOW_SECONDS] simulation seconds.
	 *
	 * @since Issue #612 (Goal 3 SP2)
	 */
	private fun handleReservationConflictDetected(event: BlockEvent.ReservationConflictDetected) {
		val key = Pair(event.trainId, event.conflictingTrainId)
		val lastTime = lastConflictWarningTime[key]
		if (lastTime != null && (event.time - lastTime) < DEDUP_WINDOW_SECONDS) {
			logger.debug {
				"handleReservationConflictDetected: suppressed duplicate for " +
					"(${event.trainId}, ${event.conflictingTrainId}) at t=${event.time} " +
					"(last at t=$lastTime)"
			}
			return
		}
		lastConflictWarningTime[key] = event.time
		emit(
			CollisionWarning.ReservationConflict(
				trainId = event.trainId,
				conflictingTrainId = event.conflictingTrainId,
				time = event.time,
				conflictingBlock = event.block
			)
		)
	}

	/**
	 * Emit a [CollisionWarning] directly, bypassing internal event detection.
	 *
	 * Provided for test scenarios that need to trigger warning delivery without
	 * constructing a full collision-inducing simulation scenario. Not intended for
	 * production call sites.
	 *
	 * @since Issue #611 (Goal 3 SP1)
	 */
	internal fun emitWarning(warning: CollisionWarning) = emit(warning)

	private companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Minimum gap (in simulation seconds) between two warnings for the same
		 * (trainId, conflictingTrainId) conflict pair. Prevents log spam when
		 * two trains continuously compete for the same path segment.
		 *
		 * @since Issue #612 (Goal 3 SP2)
		 */
		private const val DEDUP_WINDOW_SECONDS = 1.0
	}
}
