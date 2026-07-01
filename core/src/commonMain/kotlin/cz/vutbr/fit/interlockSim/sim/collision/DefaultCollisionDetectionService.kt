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
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of [CollisionDetectionService].
 *
 * Subscribes internally to both [BlockEvent] (via
 * [SimulationEnvironment.onBlockEvent]) and [BlockOccupancyEvent] (via
 * [SimulationEnvironment.addBlockOccupancyListener]) to monitor the simulation for
 * hazardous states.  These subscriptions are registered in the constructor so they are
 * buffered by [SimulationEnvironment.onBlockEvent] before
 * [cz.vutbr.fit.interlockSim.context.SimulationContext.run] is called.
 *
 * When a hazard is detected, the service:
 * 1. Calls all registered [onCollisionWarning] listeners synchronously.
 * 2. Calls [PauseController.requestPause] so the operator can inspect the state.
 *
 * **Detection rules (SP1 baseline):**
 * - [CollisionWarning.ReservationConflict]: emitted when [BlockEvent.BlockReserved] fires
 *   for a block whose [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.trainName]
 *   already belongs to a different train — indicating a double-reservation.
 * - [CollisionWarning.BlockEntryViolation]: emitted when [BlockEvent.OccupancySet] fires
 *   and the entering occupant's name does not match the block's reserved train name —
 *   indicating a train entered without a valid reservation.
 * - [CollisionWarning.PredictiveCollision]: reserved for future predictive analysis (SP2+).
 *
 * @param env The simulation environment used to subscribe to block events.
 * @param pauseController Receives [PauseController.requestPause] on every detected warning.
 * @since Issue #611 (Goal 3 SP1)
 */
class DefaultCollisionDetectionService(
	env: SimulationEnvironment,
	private val pauseController: PauseController
) : CollisionDetectionService {
	private val listeners: MutableList<(CollisionWarning) -> Unit> = mutableListOf()

	init {
		// Subscribe to domain-level block events (reserve / occupancy changes).
		// The call happens before run(), so it is buffered by DefaultSimulationContext.
		env.onBlockEvent { event -> handleBlockEvent(event) }

		// Subscribe to legacy BlockOccupancyEvent for supplementary detection.
		env.addBlockOccupancyListener(
			object : BlockOccupancyListener {
				override fun onBlockOccupancyChanged(event: BlockOccupancyEvent) {
					handleBlockOccupancyEvent(event)
				}
			}
		)
	}

	override fun onCollisionWarning(listener: (CollisionWarning) -> Unit) {
		listeners += listener
	}

	// ── Internal helpers ──────────────────────────────────────────────────────

	private fun emit(warning: CollisionWarning) {
		logger.warn { "Collision warning: $warning" }
		listeners.forEach { it(warning) }
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
							time = event.time
						)
					)
				}
			}
			is BlockEvent.OccupancySet -> {
				// Check whether the entering train matches the block's reservation.
				val reservedFor = event.block.trainName
				val occupantId = event.occupant.name
				if (reservedFor != null && reservedFor != occupantId) {
					emit(
						CollisionWarning.BlockEntryViolation(
							trainId = occupantId,
							block = event.block,
							time = event.time
						)
					)
				}
			}
			is BlockEvent.BlockReleased,
			is BlockEvent.OccupancyCleared
			-> {
				// Release events do not indicate a hazard.
			}
		}
	}

	private fun handleBlockOccupancyEvent(
		@Suppress("UNUSED_PARAMETER") event: BlockOccupancyEvent
	) {
		// Supplementary detection via the legacy BlockOccupancyEvent channel.
		// Primary detection is covered by handleBlockEvent (BlockEvent).
		// Reserved for future SP2+ predictive collision analysis.
	}

	/**
	 * Emit a [CollisionWarning] directly, bypassing internal event detection.
	 *
	 * Provided for test scenarios that need to trigger warning delivery
	 * without constructing a full collision-inducing simulation scenario.
	 * Not intended for production call sites.
	 *
	 * @since Issue #611 (Goal 3 SP1)
	 */
	internal fun emitWarning(warning: CollisionWarning) = emit(warning)

	private companion object {
		private val logger = KotlinLogging.logger {}
	}
}
