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

import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Sealed class hierarchy for collision warnings emitted by [CollisionDetectionService].
 *
 * Each subclass represents a distinct hazard category. Warnings are delivered to listeners
 * registered via [CollisionDetectionService.onCollisionWarning] and are always accompanied
 * by a [PauseController.requestPause] call so the operator can inspect the situation.
 *
 * All subclasses capture the simulation time of detection in [time].
 *
 * @since Issue #611 (Goal 3 SP1)
 */
sealed class CollisionWarning {
	/** Simulation time (in seconds) at which the warning was detected. */
	abstract val time: Double

	/**
	 * Two trains have conflicting path reservations for the same block.
	 *
	 * Emitted when a block that is already reserved for [conflictingTrainId] receives
	 * a second reservation attempt for [trainId].  In correct operation this should not
	 * happen; its presence indicates a dispatcher error or an interlocking bug.
	 *
	 * @property trainId The train that attempted the conflicting reservation.
	 * @property conflictingTrainId The train that already holds the reservation.
	 * @property time Simulation time of detection.
	 */
	data class ReservationConflict(
		val trainId: String,
		val conflictingTrainId: String,
		override val time: Double
	) : CollisionWarning()

	/**
	 * A train physically entered a block without a valid reservation.
	 *
	 * Emitted when a train occupies a block that is either unreserved or reserved for a
	 * different train.  This indicates that the interlocking logic allowed a train to proceed
	 * onto a block it does not own.
	 *
	 * @property trainId The train that entered the block without a matching reservation.
	 * @property block The track block that was entered illegally.
	 * @property time Simulation time of detection.
	 */
	data class BlockEntryViolation(
		val trainId: String,
		val block: DynamicTrackBlock,
		override val time: Double
	) : CollisionWarning()

	/**
	 * A predictive collision has been detected ahead on the shared path.
	 *
	 * Emitted when topology analysis shows that [trainId] and [targetTrainId] are heading
	 * toward the same track section with no safe separation.  Unlike [ReservationConflict]
	 * or [BlockEntryViolation], this warning is pre-emptive and does not require an actual
	 * reservation or entry violation to have occurred yet.
	 *
	 * @property trainId The train approaching the collision point.
	 * @property targetTrainId The opposing train on the same path.
	 * @property time Simulation time of detection.
	 */
	data class PredictiveCollision(
		val trainId: String,
		val targetTrainId: String,
		override val time: Double
	) : CollisionWarning()
}
