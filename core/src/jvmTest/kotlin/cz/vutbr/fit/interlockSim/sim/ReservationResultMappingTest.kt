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

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade.RouteResponse
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade.RouteResponse.DenialCause
import cz.vutbr.fit.interlockSim.testutil.coversEverySealedSubclassOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The one mapping across the kernel → facade boundary (Issue #968, owner ruling D7):
 * [ReservationResult.toRouteResponse] turns every [ReservationResult] subtype into exactly one
 * [RouteResponse], with an exhaustive `when` and no `else`.
 *
 * [providerCoversEverySubtype] is the "a new variant cannot pass unnoticed" promise: adding a
 * [ReservationResult] subtype fails this class until [samples] names its expected mapping.
 */
@DisplayName("ReservationResult.toRouteResponse — one exhaustive kernel → facade mapping (#968)")
class ReservationResultMappingTest {
	@ParameterizedTest(name = "{0}")
	@MethodSource("cz.vutbr.fit.interlockSim.sim.ReservationResultMappingTest#samples")
	@DisplayName("each ReservationResult subtype maps to its RouteResponse")
	fun mapsEachSubtype(
		result: () -> ReservationResult,
		expected: RouteResponse
	) {
		assertThat(result().toRouteResponse("T1", "A", "B")).isEqualTo(expected)
	}

	@Test
	@DisplayName("the sample table covers every ReservationResult subtype")
	fun providerCoversEverySubtype() {
		assertThat(rows.map { it.result() }).coversEverySealedSubclassOf(ReservationResult::class)
	}

	/** One table row: the result is built only when the row runs (see [block]). */
	private class Row(
		val label: String,
		val result: () -> ReservationResult,
		val expected: RouteResponse
	)

	companion object {
		/**
		 * Mock stubs do not survive from the argument provider into a later parameter row (they
		 * are cleared between test invocations), so each row builds its result — and the block
		 * mocks inside it — only when it runs.
		 */
		private fun block(name: String?): DynamicTrackBlock =
			mockk<DynamicTrackBlock>().also {
				every { it.name } returns name
			}

		private val rows =
			listOf(
				Row(
					"Success",
					{ ReservationResult.Success(listOf(block("U7"), block(null))) },
					RouteResponse.Granted(
						Aspect.Volno,
						TrainRoute(
							from = SignalId("A"),
							to = SignalId("B"),
							running = emptyList(),
							blocks = listOf(BlockId("U7"), BlockId("unnamed-1"))
						)
					)
				),
				Row(
					"NoPathExists",
					{ ReservationResult.NoPathExists },
					RouteResponse.Denied("No path exists: A → B", DenialCause.NoPath)
				),
				Row(
					"AllPathsBlocked",
					{ ReservationResult.AllPathsBlocked(attemptedPaths = 4) },
					RouteResponse.Denied("All paths blocked (A → B, attempts: 4)", DenialCause.AllPathsBlocked(4))
				),
				Row(
					"Conflict (unnamed block)",
					{ ReservationResult.Conflict(block(null), "T2") },
					RouteResponse.Denied("Block ? occupied by train T2", DenialCause.Conflict(null, "T2"))
				),
				Row(
					"NonContiguousStart",
					{ ReservationResult.NonContiguousStart("A", "T1 holds no block bounded by 'A'") },
					RouteResponse.Denied("T1 holds no block bounded by 'A'", DenialCause.NonContiguousStart)
				),
				Row(
					"GeometricallyImpossible",
					{ ReservationResult.GeometricallyImpossible("START semaphore faces away") },
					RouteResponse.Denied(
						"START semaphore faces away",
						DenialCause.GeometricallyImpossible("START semaphore faces away")
					)
				),
				Row(
					"DivergesFromHeldRoute",
					{ ReservationResult.DivergesFromHeldRoute("doB2", "new path starts at zA") },
					RouteResponse.Denied(
						"Route already continues toward doB2; extend from it or cancel the route first",
						DenialCause.DivergesFromHeldRoute("doB2", "new path starts at zA")
					)
				)
			)

		@JvmStatic
		fun samples(): List<Arguments> = rows.map { Arguments.of(Named.of(it.label, it.result), it.expected) }
	}
}
