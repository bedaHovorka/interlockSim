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
 * registered via [CollisionDetectionService.onCollisionWarning]. Warnings with
 * [Severity.CRITICAL] additionally trigger [PauseController.requestPause] when
 * [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService.autoPauseOnCritical]
 * is enabled (the default).
 *
 * All subclasses capture the simulation time of detection in [time].
 *
 * @since Issue #611 (Goal 3 SP1)
 */
sealed class CollisionWarning {
	/** Simulation time (in seconds) at which the warning was detected. */
	abstract val time: Double

	/**
	 * How urgent this warning is.
	 *
	 * Governs whether [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService]
	 * triggers an auto-pause when
	 * [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService.autoPauseOnCritical]
	 * is `true` (Goal 3 SP5).
	 *
	 * @since Issue #615 (Goal 3 SP5)
	 */
	enum class Severity {
		/**
		 * A safety violation that requires immediate operator attention.
		 * Triggers [PauseController.requestPause] when [DefaultCollisionDetectionService.autoPauseOnCritical]
		 * is `true`.
		 */
		CRITICAL,

		/**
		 * A pre-emptive advisory that does **not** by itself trigger an auto-pause.
		 * Delivered to listeners like any other warning but the simulation continues.
		 */
		WARNING
	}

	/** How urgent this warning is; governs auto-pause behaviour (Goal 3 SP5). */
	abstract val severity: Severity

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
	 * @property conflictingBlock The specific block that caused the conflict, if known.
	 * @property severity Always [Severity.CRITICAL]: a double-reservation means the
	 *   interlocking logic has failed and a physical collision is imminent.
	 */
	data class ReservationConflict(
		val trainId: String,
		val conflictingTrainId: String,
		override val time: Double,
		val conflictingBlock: DynamicTrackBlock? = null,
		override val severity: Severity = Severity.CRITICAL
	) : CollisionWarning()

	/**
	 * A train physically entered a block without a valid reservation.
	 *
	 * Emitted when a train occupies a block that is reserved for a **different** train
	 * (entry into an unreserved block does not fire — see #613), or when a train attempts
	 * to enter a block that is already physically occupied (double-occupancy; emitted by
	 * [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.enter] immediately before
	 * it throws).  Either case indicates that the interlocking logic allowed a train to
	 * proceed onto a block it does not own.
	 *
	 * @property trainId The train that entered the block without a matching reservation.
	 * @property block The track block that was entered illegally. **Live reference, not a
	 *   snapshot**: the block's reservation/occupancy state continues to mutate after the
	 *   warning is captured. Use [reservedForAtDetection] for the state at detection time.
	 * @property time Simulation time of detection.
	 * @property reservedForAtDetection Snapshot of the block's reserved train name
	 *   (`block.trainName`) at the moment of detection; `null` when the block held no
	 *   reservation at that moment.
	 * @property severity Always [Severity.CRITICAL]: the train has physically violated a
	 *   safety boundary; the interlocking has already failed.
	 */
	data class BlockEntryViolation(
		val trainId: String,
		val block: DynamicTrackBlock,
		override val time: Double,
		val reservedForAtDetection: String? = null,
		override val severity: Severity = Severity.CRITICAL
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
	 * @property severity Always [Severity.CRITICAL]: even a predictive collision is an
	 *   imminent safety hazard requiring immediate operator attention.
	 */
	data class PredictiveCollision(
		val trainId: String,
		val targetTrainId: String,
		override val time: Double,
		override val severity: Severity = Severity.CRITICAL
	) : CollisionWarning()
}
