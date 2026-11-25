/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import kotlin.test.Test

/**
 * Pins how both refusal types map onto the one [FailedReservationKind] breakdown (Issue #1304,
 * Goal 6 SP6): the dispatcher-level [RouteRequestResult] that `ShuntingLoop` counts and the
 * kernel-level [ReservationResult] that `MultiTrainLoop` sees. A success maps to `null`. The kernel
 * `Conflict` needs a live block and is covered by the exhaustive `when` of the mapping instead.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
class FailedReservationKindTest {
	@Test
	fun `every route request refusal has its kind and a reservation is no failure`() {
		val expected =
			mapOf(
				RouteRequestResult.NoRouteExists("A", "B") to FailedReservationKind.NO_ROUTE,
				RouteRequestResult.UnresolvedEndpoint("X") to FailedReservationKind.UNRESOLVED_ENDPOINT,
				RouteRequestResult.AllPathsBlocked(2) to FailedReservationKind.ALL_PATHS_BLOCKED,
				RouteRequestResult.Conflict("k1", "T2") to FailedReservationKind.CONFLICT,
				RouteRequestResult.OriginNotContiguous("S1", "gap") to FailedReservationKind.ORIGIN_NOT_CONTIGUOUS,
				RouteRequestResult.ConditionFailed("occupied", retryable = true) to
					FailedReservationKind.CONDITION_FAILED,
				RouteRequestResult.GeometricallyImpossible("reverse") to FailedReservationKind.GEOMETRICALLY_IMPOSSIBLE,
				RouteRequestResult.DivergesFromHeldRoute("S2", "other") to FailedReservationKind.DIVERGES_FROM_HELD_ROUTE
			)

		expected.forEach { (result, kind) ->
			assertThat(FailedReservationKind.of(result), name = result::class.simpleName).isEqualTo(kind)
		}
		assertThat(FailedReservationKind.of(RouteRequestResult.Reserved("T1", 3))).isNull()
	}

	@Test
	fun `every kernel refusal has its kind and a success is no failure`() {
		val expected =
			mapOf(
				ReservationResult.NoPathExists to FailedReservationKind.NO_ROUTE,
				ReservationResult.AllPathsBlocked(1) to FailedReservationKind.ALL_PATHS_BLOCKED,
				ReservationResult.NonContiguousStart("S1", "gap") to FailedReservationKind.ORIGIN_NOT_CONTIGUOUS,
				ReservationResult.GeometricallyImpossible("reverse") to FailedReservationKind.GEOMETRICALLY_IMPOSSIBLE,
				ReservationResult.DivergesFromHeldRoute("S2", "other") to FailedReservationKind.DIVERGES_FROM_HELD_ROUTE
			)

		expected.forEach { (result, kind) ->
			assertThat(FailedReservationKind.of(result), name = result::class.simpleName).isEqualTo(kind)
		}
		assertThat(FailedReservationKind.of(ReservationResult.Success(emptyList()))).isNull()
	}
}
