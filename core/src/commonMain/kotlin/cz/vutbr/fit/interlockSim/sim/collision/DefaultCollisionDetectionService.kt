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

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of [CollisionDetectionService].
 *
 * **Goal 3 SP1 scope:** this is an intentionally thin backbone that provides only the
 * warning-listener registry, warning emission, and [PauseController] bridge. It does
 * **not** implement any collision-detection rules yet — those are deferred to later
 * sub-phases of Goal 3 so each rule ships with its own dedicated tests:
 * - [CollisionWarning.ReservationConflict] detection  → SP2 (#612)
 * - [CollisionWarning.BlockEntryViolation] detection  → SP3 (#613)
 * - [CollisionWarning.PredictiveCollision] detection   → SP4 (#614)
 *
 * SP2/SP3/SP4 will add the [cz.vutbr.fit.interlockSim.context.navigation.BlockEvent] /
 * [cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent] subscriptions and the
 * rule bodies that call [emit]. Until then, warnings are produced only via the
 * [emitWarning] test hook.
 *
 * When a warning is emitted, the service:
 * 1. Calls all registered [onCollisionWarning] listeners synchronously. Each listener
 *    is isolated — a throwing listener is logged and does **not** prevent the remaining
 *    listeners or the pause request.
 * 2. Calls [PauseController.requestPause] so the operator can inspect the state.
 *
 * @param pauseController Receives [PauseController.requestPause] on every emitted warning.
 * @since Issue #611 (Goal 3 SP1)
 */
class DefaultCollisionDetectionService(
	private val pauseController: PauseController
) : CollisionDetectionService {
	private val listeners: MutableList<(CollisionWarning) -> Unit> = mutableListOf()

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

	/**
	 * Emit a [CollisionWarning] directly, bypassing internal event detection.
	 *
	 * Provided for test scenarios that need to trigger warning delivery without
	 * constructing a full collision-inducing simulation scenario. Not intended for
	 * production call sites — SP2/SP3/SP4 will drive [emit] from real block events.
	 *
	 * @since Issue #611 (Goal 3 SP1)
	 */
	internal fun emitWarning(warning: CollisionWarning) = emit(warning)

	private companion object {
		private val logger = KotlinLogging.logger {}
	}
}
