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

import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult

/**
 * Why a reservation attempt was refused, as one breakdown for every harness (Issue #1304, Goal 6 SP6).
 *
 * `ShuntingLoop` counts the dispatcher-level [RouteRequestResult] of each applied route request,
 * `MultiTrainLoop` the kernel-level [ReservationResult] of each entry attempt. Both map onto this
 * enum (ruling of the traffic-simulation-expert, 2026-10-31: one common enum rather than class
 * names), so a run record compares refusals across harnesses. Variants that exist on one side only
 * ([UNRESOLVED_ENDPOINT], [CONDITION_FAILED]) simply never occur on the other.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
enum class FailedReservationKind {
	/** No route connects the endpoints ([RouteRequestResult.NoRouteExists], [ReservationResult.NoPathExists]). */
	NO_ROUTE,

	/** An endpoint name resolved to nothing ([RouteRequestResult.UnresolvedEndpoint]). */
	UNRESOLVED_ENDPOINT,

	/** Every candidate route was blocked by other holdings ([RouteRequestResult.AllPathsBlocked]). */
	ALL_PATHS_BLOCKED,

	/** A block was already owned by another train ([RouteRequestResult.Conflict]). */
	CONFLICT,

	/**
	 * The origin is not contiguous with the train's holding ([RouteRequestResult.OriginNotContiguous],
	 * [ReservationResult.NonContiguousStart]).
	 */
	ORIGIN_NOT_CONTIGUOUS,

	/** The interlocking safety kernel refused one of its conditions ([RouteRequestResult.ConditionFailed]). */
	CONDITION_FAILED,

	/** The route cannot be driven at all ([RouteRequestResult.GeometricallyImpossible]). */
	GEOMETRICALLY_IMPOSSIBLE,

	/** The request leaves the route the train already holds ([RouteRequestResult.DivergesFromHeldRoute]). */
	DIVERGES_FROM_HELD_ROUTE;

	/** @since Issue #1304 (Goal 6 SP6) */
	companion object {
		/**
		 * The kind of a refused route request, or `null` for [RouteRequestResult.Reserved].
		 *
		 * @since Issue #1304 (Goal 6 SP6)
		 */
		fun of(result: RouteRequestResult): FailedReservationKind? =
			when (result) {
				is RouteRequestResult.Reserved -> null
				is RouteRequestResult.NoRouteExists -> NO_ROUTE
				is RouteRequestResult.UnresolvedEndpoint -> UNRESOLVED_ENDPOINT
				is RouteRequestResult.AllPathsBlocked -> ALL_PATHS_BLOCKED
				is RouteRequestResult.Conflict -> CONFLICT
				is RouteRequestResult.OriginNotContiguous -> ORIGIN_NOT_CONTIGUOUS
				is RouteRequestResult.ConditionFailed -> CONDITION_FAILED
				is RouteRequestResult.GeometricallyImpossible -> GEOMETRICALLY_IMPOSSIBLE
				is RouteRequestResult.DivergesFromHeldRoute -> DIVERGES_FROM_HELD_ROUTE
			}

		/**
		 * The kind of a refused kernel reservation, or `null` for [ReservationResult.Success].
		 *
		 * @since Issue #1304 (Goal 6 SP6)
		 */
		fun of(result: ReservationResult): FailedReservationKind? =
			when (result) {
				is ReservationResult.Success -> null
				is ReservationResult.NoPathExists -> NO_ROUTE
				is ReservationResult.AllPathsBlocked -> ALL_PATHS_BLOCKED
				is ReservationResult.Conflict -> CONFLICT
				is ReservationResult.NonContiguousStart -> ORIGIN_NOT_CONTIGUOUS
				is ReservationResult.GeometricallyImpossible -> GEOMETRICALLY_IMPOSSIBLE
				is ReservationResult.DivergesFromHeldRoute -> DIVERGES_FROM_HELD_ROUTE
			}
	}
}
