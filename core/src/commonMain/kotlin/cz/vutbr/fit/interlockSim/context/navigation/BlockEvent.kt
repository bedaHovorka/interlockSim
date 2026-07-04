package cz.vutbr.fit.interlockSim.context.navigation

import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Domain events emitted when block reservation or occupancy state changes.
 *
 * Delivered via kdisco's event bus as [cz.hovorka.kdisco.SimulationEvent.Custom] payloads.
 * Subscribe via [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.onBlockEvent].
 *
 * All fields are captured at emission time (simulation-thread values).
 *
 * @since Issue #569 (Goal 10 prereq)
 */
sealed class BlockEvent {
	abstract val block: DynamicTrackBlock
	abstract val time: Double

	/**
	 * A block was atomically reserved for a train by [DefaultPathReservationService].
	 * State transition: FREE → RESERVED.
	 */
	data class BlockReserved(
		override val block: DynamicTrackBlock,
		val trainId: String,
		override val time: Double
	) : BlockEvent()

	/**
	 * A block was released from a train's reservation.
	 * State transition: RESERVED/OCCUPIED → FREE (registry entry removed).
	 */
	data class BlockReleased(
		override val block: DynamicTrackBlock,
		val trainId: String,
		override val time: Double
	) : BlockEvent()

	/**
	 * A train physically entered (occupied) a block.
	 * State transition: RESERVED → OCCUPIED.
	 */
	data class OccupancySet(
		override val block: DynamicTrackBlock,
		val occupant: TrackOccupant,
		override val time: Double
	) : BlockEvent()

	/**
	 * A train physically left a block.
	 * State transition: OCCUPIED → FREE.
	 */
	data class OccupancyCleared(
		override val block: DynamicTrackBlock,
		override val time: Double
	) : BlockEvent()

	/**
	 * A reservation conflict was detected: [trainId] attempted to use [block]
	 * that is already held by [conflictingTrainId].
	 *
	 * Emitted by [DefaultPathReservationService] when:
	 * - [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.Conflict]
	 *   is returned (registry-level race detected atomically at reservation time), or
	 * - the run has ended with blocked-path contention still unresolved
	 *   ([cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.flushUnresolvedConflicts]).
	 *   A routine [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.AllPathsBlocked]
	 *   outcome alone never emits this event mid-run (issue #612: timing-based
	 *   heuristics misfire on ordinary queueing at shared bottlenecks).
	 *
	 * Consumed by
	 * [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService]
	 * which re-emits the conflict as a [cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning.ReservationConflict]
	 * after applying a deduplication window.
	 *
	 * @since Issue #612 (Goal 3 SP2)
	 */
	data class ReservationConflictDetected(
		override val block: DynamicTrackBlock,
		val trainId: String,
		val conflictingTrainId: String,
		override val time: Double
	) : BlockEvent()
}
