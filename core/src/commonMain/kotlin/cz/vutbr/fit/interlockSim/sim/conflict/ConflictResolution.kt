/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import cz.vutbr.fit.interlockSim.objects.paths.Route

/**
 * Sealed class hierarchy describing a candidate resolution for a detected conflict.
 *
 * Each subclass represents a distinct mitigation strategy.  Resolutions are generated
 * by [ConflictResolver.generateResolutions] and are purely advisory — this layer does
 * **not** apply them automatically (auto-application is tracked in Issue #568).
 *
 * All subclasses carry:
 * - [strategy]        — which mitigation type was chosen
 * - [affectedTrains]  — the train IDs that would be impacted
 * - [estimatedImpact] — a human-readable consequence summary with an estimated delay
 *
 * @since Issue #585 (Goal 9 SP3)
 */
sealed class ConflictResolution {
	/**
	 * Mitigation strategy used by this candidate resolution.
	 */
	enum class Strategy {
		/**
		 * Instruct a train to wait in place until the contested resource is released.
		 * The [HoldTrain.holdDurationSeconds] field specifies how long the train should
		 * wait before retrying.
		 */
		HOLD_TRAIN,

		/**
		 * Direct a train onto an alternative path that avoids the contested block.
		 * The [Reroute.alternativeRoute] field carries the proposed substitute route
		 * produced by the Goal 2 pathfinding API ([cz.vutbr.fit.interlockSim.context.RouteFinder]).
		 */
		REROUTE,

		/**
		 * Reduce a train's speed to create additional separation from the train ahead.
		 * The [SpeedAdjust.speedReductionFactor] field is a fraction in (0.0, 1.0]:
		 * `1.0` means no reduction, `0.5` means 50 % of the current speed.
		 */
		SPEED_ADJUST
	}

	/**
	 * Estimated operational consequence of applying this resolution.
	 *
	 * @property delaySeconds Expected additional delay in simulation seconds (≥ 0,
	 *   enforced).  `0.0` when the resolution is expected to have no measurable delay
	 *   (e.g. reroute via an equally-cost path) — or when no numeric estimate is
	 *   available and `0.0` is a heuristic placeholder (e.g. [SpeedAdjust]); the
	 *   [description] states which.
	 * @property description Human-readable summary of the impact, suitable for logging
	 *   or operator display.
	 */
	data class EstimatedImpact(
		val delaySeconds: Double,
		val description: String
	) {
		init {
			require(delaySeconds >= 0.0) { "delaySeconds must be >= 0, was $delaySeconds" }
		}
	}

	/** Which mitigation strategy this candidate represents. */
	abstract val strategy: Strategy

	/** IDs of trains that would be directly affected by applying this resolution. */
	abstract val affectedTrains: List<String>

	/** Estimated operational consequence of applying this resolution. */
	abstract val estimatedImpact: EstimatedImpact

	// ── Concrete resolution types ────────────────────────────────────────────

	/**
	 * Ask [trainId] to remain stationary for [holdDurationSeconds] simulation seconds.
	 *
	 * This gives the conflicting train time to clear the contested track block before
	 * [trainId] retries its reservation.  The estimated delay is equal to
	 * [holdDurationSeconds].
	 *
	 * @property trainId  The train to hold.
	 * @property holdDurationSeconds  How long the train should wait (> 0, enforced).
	 */
	data class HoldTrain(
		val trainId: String,
		val holdDurationSeconds: Double,
		override val affectedTrains: List<String>,
		override val estimatedImpact: EstimatedImpact
	) : ConflictResolution() {
		init {
			require(holdDurationSeconds > 0.0) {
				"holdDurationSeconds must be > 0, was $holdDurationSeconds"
			}
		}

		override val strategy: Strategy = Strategy.HOLD_TRAIN
	}

	/**
	 * Direct [trainId] onto [alternativeRoute], bypassing the contested block.
	 *
	 * When produced by [DefaultConflictResolver], the route is discovered by the Goal 2
	 * pathfinding API ([cz.vutbr.fit.interlockSim.context.RouteFinder.findRoutes]) and its
	 * segments exclude the contested [ConflictDetectedEvent.block].  The data class itself
	 * does not enforce this — a manually constructed instance may carry any route.
	 *
	 * @property trainId          The train to reroute.
	 * @property alternativeRoute The substitute route that avoids the contested block.
	 */
	data class Reroute(
		val trainId: String,
		val alternativeRoute: Route,
		override val affectedTrains: List<String>,
		override val estimatedImpact: EstimatedImpact
	) : ConflictResolution() {
		override val strategy: Strategy = Strategy.REROUTE
	}

	/**
	 * Reduce [trainId]'s speed by [speedReductionFactor] to increase separation.
	 *
	 * A factor of `1.0` means no reduction; `0.5` means reduce to 50 % of the
	 * current speed.  Slowing down the blocked train creates additional time for
	 * the conflicting train to clear the contested block before [trainId] arrives.
	 *
	 * @property trainId             The train whose speed should be reduced.
	 * @property speedReductionFactor Target fraction of current speed, in (0.0, 1.0]
	 *   (enforced).
	 */
	data class SpeedAdjust(
		val trainId: String,
		val speedReductionFactor: Double,
		override val affectedTrains: List<String>,
		override val estimatedImpact: EstimatedImpact
	) : ConflictResolution() {
		init {
			require(speedReductionFactor > 0.0 && speedReductionFactor <= 1.0) {
				"speedReductionFactor must be in (0.0, 1.0], was $speedReductionFactor"
			}
		}

		override val strategy: Strategy = Strategy.SPEED_ADJUST
	}
}
