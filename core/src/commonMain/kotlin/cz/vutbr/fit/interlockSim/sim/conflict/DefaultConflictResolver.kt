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

import cz.vutbr.fit.interlockSim.context.NetworkState
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default [ConflictResolver] implementation.
 *
 * For each [ConflictDetectedEvent] this resolver generates up to three kinds of
 * candidate resolutions:
 *
 * 1. **HoldTrain** — always generated.  Instructs [ConflictDetectedEvent.trainId] (the
 *    blocked train) to wait for [DEFAULT_HOLD_DURATION_SECONDS] seconds so that the
 *    conflicting train can clear the contested block.
 *
 * 2. **Reroute** — generated only when the Goal 2 pathfinding API
 *    ([RouteFinder.findRoutes]) discovers at least one route across any pair of network
 *    [InOut] endpoints whose segments do not include the contested block.  All such
 *    alternative routes produce separate [ConflictResolution.Reroute] candidates.
 *
 * 3. **SpeedAdjust** — always generated.  Proposes reducing [ConflictDetectedEvent.trainId]'s
 *    speed to [DEFAULT_SPEED_REDUCTION_FACTOR] × its current speed.  This creates
 *    additional temporal separation before the blocked train reaches the contested block.
 *
 * @param routeFinder   Goal 2 pathfinding API used to discover alternative routes.
 * @param inOuts        All static [InOut] endpoints in the network.  Reroute candidates
 *                      are searched across every pair of these endpoints.
 * @param networkState  Network state snapshot forwarded to [RouteFinder.findRoutes].
 *                      In the current slice this is purely topological.
 *
 * @see ConflictResolver
 * @see ConflictResolution
 * @since Issue #585 (Goal 9 SP3)
 */
class DefaultConflictResolver(
	private val routeFinder: RouteFinder,
	private val inOuts: Collection<InOut>,
	private val networkState: NetworkState
) : ConflictResolver {
	override fun generateResolutions(conflict: ConflictDetectedEvent): List<ConflictResolution> {
		val result = mutableListOf<ConflictResolution>()

		result.add(generateHoldTrain(conflict))
		result.addAll(generateRerouteCandidates(conflict))
		result.add(generateSpeedAdjust(conflict))

		logger.debug {
			"generateResolutions: conflict=(${conflict.trainId} vs ${conflict.conflictingTrainId}) " +
				"block=${conflict.block} → ${result.size} candidates"
		}
		return result
	}

	// ── Private helpers ──────────────────────────────────────────────────────

	private fun generateHoldTrain(conflict: ConflictDetectedEvent): ConflictResolution.HoldTrain =
		ConflictResolution.HoldTrain(
			trainId = conflict.trainId,
			holdDurationSeconds = DEFAULT_HOLD_DURATION_SECONDS,
			affectedTrains = listOf(conflict.trainId),
			estimatedImpact =
				ConflictResolution.EstimatedImpact(
					delaySeconds = DEFAULT_HOLD_DURATION_SECONDS,
					description =
						"Hold train ${conflict.trainId} for ${DEFAULT_HOLD_DURATION_SECONDS}s " +
							"to let ${conflict.conflictingTrainId} clear the contested block"
				)
		)

	/**
	 * Enumerate all [InOut] pairs and collect routes that bypass [ConflictDetectedEvent.block].
	 *
	 * Each call to [RouteFinder.findRoutes] may return multiple routes; only those whose
	 * [cz.vutbr.fit.interlockSim.objects.paths.Route.segments] contain no segment equal to
	 * the contested block are included.
	 */
	private fun generateRerouteCandidates(conflict: ConflictDetectedEvent): List<ConflictResolution.Reroute> {
		val candidates = mutableListOf<ConflictResolution.Reroute>()
		val inOutList = inOuts.toList()

		for (from in inOutList) {
			for (to in inOutList) {
				if (from === to) continue
				val routes = routeFinder.findRoutes(from, to, networkState)
				for (route in routes) {
					if (route.segments.none { it == conflict.block }) {
						candidates.add(
							ConflictResolution.Reroute(
								trainId = conflict.trainId,
								alternativeRoute = route,
								affectedTrains = listOf(conflict.trainId),
								estimatedImpact =
									ConflictResolution.EstimatedImpact(
										delaySeconds = 0.0,
										description =
											"Reroute ${conflict.trainId} via " +
												"${route.elementCount}-segment alternative " +
												"from ${from.getName()} to ${to.getName()}"
									)
							)
						)
					}
				}
			}
		}

		return candidates
	}

	private fun generateSpeedAdjust(conflict: ConflictDetectedEvent): ConflictResolution.SpeedAdjust =
		ConflictResolution.SpeedAdjust(
			trainId = conflict.trainId,
			speedReductionFactor = DEFAULT_SPEED_REDUCTION_FACTOR,
			affectedTrains = listOf(conflict.trainId),
			estimatedImpact =
				ConflictResolution.EstimatedImpact(
					delaySeconds = 0.0,
					description =
						"Reduce speed of ${conflict.trainId} to " +
							"${(DEFAULT_SPEED_REDUCTION_FACTOR * 100).toInt()}% to increase separation " +
							"from ${conflict.conflictingTrainId}"
				)
		)

	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Default hold duration in simulation seconds.
		 *
		 * Callers can override by instantiating [DefaultConflictResolver] with a different
		 * implementation or by subclassing and overriding [generateResolutions].
		 */
		const val DEFAULT_HOLD_DURATION_SECONDS: Double = 30.0

		/**
		 * Default speed-reduction factor applied to the blocked train.
		 *
		 * `0.5` means reduce to 50 % of the current speed.  A value of `1.0` would mean no
		 * change.
		 */
		const val DEFAULT_SPEED_REDUCTION_FACTOR: Double = 0.5
	}
}
