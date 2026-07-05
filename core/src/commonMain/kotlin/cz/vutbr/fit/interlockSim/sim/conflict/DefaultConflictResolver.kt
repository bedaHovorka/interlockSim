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
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.paths.Route
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default [ConflictResolver] implementation.
 *
 * For each [ConflictDetectedEvent] this resolver generates up to three kinds of
 * candidate resolutions:
 *
 * 1. **HoldTrain** — always generated.  Instructs [ConflictDetectedEvent.trainId] (the
 *    blocked train) to wait for [holdDurationSeconds] seconds so that the
 *    conflicting train can clear the contested block.
 *
 * 2. **Reroute** — generated only when the Goal 2 pathfinding API
 *    ([RouteFinder.findRoutes]) discovers at least one route across any pair of network
 *    [InOut] endpoints whose segments do not include the contested block.  Candidates
 *    are deduplicated (the same physical path discovered via multiple or reversed
 *    endpoint pairs is reported once), sorted by cost, and capped at
 *    [maxRerouteCandidates].  See [generateRerouteCandidates] for the position-agnostic
 *    limitation of these candidates.
 *
 * 3. **SpeedAdjust** — always generated.  Proposes reducing [ConflictDetectedEvent.trainId]'s
 *    speed to [speedReductionFactor] × its current speed.  This creates
 *    additional temporal separation before the blocked train reaches the contested block.
 *
 * ### Context coupling
 *
 * [routeFinder], [inOuts], and [networkState] **must all come from the same
 * [SimulationEnvironment] that emitted the [ConflictDetectedEvent]** — i.e.
 * `env.getRouteFinder()`, `env.getInOuts().map { it.staticRef }`, and `env` itself.
 * [ConflictDetectedEvent.block] is a dynamic wrapper whose equality is based on the
 * identity of its static counterpart; a [RouteFinder] scoped to an editing context (or
 * to a *different* simulation context) returns routes over static track sections that
 * never compare equal to the event's dynamic block, so the contested-block filter would
 * silently stop excluding anything and routes *through* the contested block would be
 * proposed as avoiding it.  Use [forEnvironment] as the safe construction path.
 *
 * @param routeFinder   Goal 2 pathfinding API used to discover alternative routes.
 *                      Must be scoped to the same simulation context as the events.
 * @param inOuts        All static [InOut] endpoints in the network.  Reroute candidates
 *                      are searched across every pair of these endpoints.
 * @param networkState  Network state snapshot forwarded to [RouteFinder.findRoutes].
 *                      In the current slice this is purely topological.
 * @param holdDurationSeconds  Hold duration proposed by HoldTrain candidates (> 0).
 * @param speedReductionFactor Speed factor proposed by SpeedAdjust candidates, in (0.0, 1.0].
 * @param maxRoutesPerPair     Maximum routes requested from [RouteFinder.findRoutes] per
 *                             InOut pair (≥ 1).  Keeps route enumeration bounded on large
 *                             networks.
 * @param maxRerouteCandidates Maximum Reroute candidates returned per conflict (≥ 1).
 *                             The cheapest candidates are kept.
 *
 * @see ConflictResolver
 * @see ConflictResolution
 * @since Issue #585 (Goal 9 SP3)
 */
class DefaultConflictResolver(
	private val routeFinder: RouteFinder,
	private val inOuts: Collection<InOut>,
	private val networkState: NetworkState,
	private val holdDurationSeconds: Double = DEFAULT_HOLD_DURATION_SECONDS,
	private val speedReductionFactor: Double = DEFAULT_SPEED_REDUCTION_FACTOR,
	private val maxRoutesPerPair: Int = DEFAULT_MAX_ROUTES_PER_PAIR,
	private val maxRerouteCandidates: Int = DEFAULT_MAX_REROUTE_CANDIDATES
) : ConflictResolver {
	init {
		require(holdDurationSeconds > 0.0) {
			"holdDurationSeconds must be > 0, was $holdDurationSeconds"
		}
		require(speedReductionFactor > 0.0 && speedReductionFactor <= 1.0) {
			"speedReductionFactor must be in (0.0, 1.0], was $speedReductionFactor"
		}
		require(maxRoutesPerPair >= 1) { "maxRoutesPerPair must be >= 1, was $maxRoutesPerPair" }
		require(maxRerouteCandidates >= 1) {
			"maxRerouteCandidates must be >= 1, was $maxRerouteCandidates"
		}
	}

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
			holdDurationSeconds = holdDurationSeconds,
			affectedTrains = listOf(conflict.trainId),
			estimatedImpact =
				ConflictResolution.EstimatedImpact(
					delaySeconds = holdDurationSeconds,
					description =
						"Hold train ${conflict.trainId} for ${holdDurationSeconds}s " +
							"to let ${conflict.conflictingTrainId} clear the contested block"
				)
		)

	/**
	 * Enumerate all [InOut] pairs and collect routes that bypass [ConflictDetectedEvent.block].
	 *
	 * Each call to [RouteFinder.findRoutes] may return multiple routes (bounded by
	 * [maxRoutesPerPair]); only those whose [Route.segments] contain no segment equal to
	 * the contested block are included.  Duplicates — the same physical path discovered
	 * via multiple InOut pairs or via the reversed pair (whose segment list is reversed) —
	 * are collapsed by comparing segment *sets*; a simple path is uniquely determined by
	 * its segment set.  The surviving candidates are sorted by cost and capped at
	 * [maxRerouteCandidates].
	 *
	 * **Position-agnostic limitation:** candidates are advisory suggestions enumerated
	 * across *all* InOut pairs.  This slice does not know where the blocked train
	 * currently is, where it is heading, or whether it can still reach a candidate
	 * route's start point — so not every candidate is actionable.  Actionability and
	 * position-aware filtering are deferred to Goal 9 SP4 (Issue #568).
	 */
	private fun generateRerouteCandidates(conflict: ConflictDetectedEvent): List<ConflictResolution.Reroute> {
		val bypassingRoutes = mutableListOf<RouteWithEndpoints>()
		val inOutList = inOuts.toList()

		for (from in inOutList) {
			for (to in inOutList) {
				if (from === to) continue
				val routes = routeFinder.findRoutes(from, to, networkState, maxRoutes = maxRoutesPerPair)
				for (route in routes) {
					if (route.segments.none { it == conflict.block }) {
						bypassingRoutes.add(RouteWithEndpoints(from, to, route))
					}
				}
			}
		}

		return bypassingRoutes
			.distinctBy { it.route.segments.toSet() }
			.sortedBy { it.route.cost }
			.take(maxRerouteCandidates)
			.map { (from, to, route) ->
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
			}
	}

	/**
	 * Always-generated speed-reduction candidate.
	 *
	 * No numeric delay estimate is possible for a speed adjustment in this slice (it
	 * would require the train's remaining distance), so the estimated impact carries
	 * `delaySeconds = 0.0` as a heuristic placeholder and says so in its description.
	 */
	private fun generateSpeedAdjust(conflict: ConflictDetectedEvent): ConflictResolution.SpeedAdjust =
		ConflictResolution.SpeedAdjust(
			trainId = conflict.trainId,
			speedReductionFactor = speedReductionFactor,
			affectedTrains = listOf(conflict.trainId),
			estimatedImpact =
				ConflictResolution.EstimatedImpact(
					delaySeconds = 0.0,
					description =
						"Reduce speed of ${conflict.trainId} to " +
							"${(speedReductionFactor * 100).toInt()}% to increase separation " +
							"from ${conflict.conflictingTrainId} " +
							"(delay impact not quantified in this slice)"
				)
		)

	/** Route paired with the directed InOut endpoints it was discovered between. */
	private data class RouteWithEndpoints(
		val from: InOut,
		val to: InOut,
		val route: Route
	)

	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Default hold duration in simulation seconds.
		 *
		 * Override via the `holdDurationSeconds` constructor parameter.
		 */
		const val DEFAULT_HOLD_DURATION_SECONDS: Double = 30.0

		/**
		 * Default speed-reduction factor applied to the blocked train.
		 *
		 * `0.5` means reduce to 50 % of the current speed.  A value of `1.0` would mean no
		 * change.  Override via the `speedReductionFactor` constructor parameter.
		 */
		const val DEFAULT_SPEED_REDUCTION_FACTOR: Double = 0.5

		/**
		 * Default maximum number of routes requested from [RouteFinder.findRoutes] per
		 * InOut pair.  Override via the `maxRoutesPerPair` constructor parameter.
		 */
		const val DEFAULT_MAX_ROUTES_PER_PAIR: Int = 3

		/**
		 * Default maximum number of Reroute candidates returned per conflict.
		 * Override via the `maxRerouteCandidates` constructor parameter.
		 */
		const val DEFAULT_MAX_REROUTE_CANDIDATES: Int = 10

		/**
		 * Create a resolver wired to [env], the same [SimulationEnvironment] that emits
		 * the [ConflictDetectedEvent]s this resolver will process.
		 *
		 * This is the recommended construction path: it guarantees the same-environment
		 * invariant described in the class documentation ("Context coupling") and hides
		 * the `DynamicInOut → InOut` unwrapping that the primary constructor requires.
		 */
		fun forEnvironment(
			env: SimulationEnvironment,
			holdDurationSeconds: Double = DEFAULT_HOLD_DURATION_SECONDS,
			speedReductionFactor: Double = DEFAULT_SPEED_REDUCTION_FACTOR,
			maxRoutesPerPair: Int = DEFAULT_MAX_ROUTES_PER_PAIR,
			maxRerouteCandidates: Int = DEFAULT_MAX_REROUTE_CANDIDATES
		): DefaultConflictResolver =
			DefaultConflictResolver(
				routeFinder = env.getRouteFinder(),
				inOuts = env.getInOuts().map { it.staticRef },
				networkState = env,
				holdDurationSeconds = holdDurationSeconds,
				speedReductionFactor = speedReductionFactor,
				maxRoutesPerPair = maxRoutesPerPair,
				maxRerouteCandidates = maxRerouteCandidates
			)
	}
}
