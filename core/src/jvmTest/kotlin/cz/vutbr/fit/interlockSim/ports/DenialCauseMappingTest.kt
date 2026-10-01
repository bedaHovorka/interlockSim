/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade.RouteResponse.DenialCause
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The one mapping across the facade → port boundary (Issue #968, owner ruling D7):
 * [DefaultNetworkActuatorPort]'s `classifyDenial` turns every [DenialCause] into exactly one
 * [RouteRequestResult], with an exhaustive `when` and no `else` — and no residual `Other` cause
 * left to collapse onto [RouteRequestResult.NoRouteExists].
 *
 * `classifyDenial` is private, so the table drives it through the public
 * [DefaultNetworkActuatorPort.requestRoute] with a mocked facade. [providerCoversEverySubtype] is
 * the "a new variant cannot pass unnoticed" promise.
 */
@DisplayName("DenialCause → RouteRequestResult — one exhaustive facade → port mapping (#968)")
class DenialCauseMappingTest {
	private fun inOut(name: String): DynamicInOut {
		val staticRef = mockk<InOut>(relaxed = true)
		every { staticRef.getName() } returns name
		return mockk<DynamicInOut>(relaxed = true).also {
			every { it.name } returns name
			every { it.staticRef } returns staticRef
		}
	}

	private fun portWithFacade(facade: InterlockingFacade): DefaultNetworkActuatorPort {
		val grid = mockk<RailwayNetGrid<Cell>>(relaxed = true)
		every { grid.cols } returns 0
		every { grid.rows } returns 0
		val env = mockk<SimulationEnvironment>(relaxed = true)
		every { env.getInOuts() } returns listOf(inOut("A"), inOut("B"))
		every { env.getRailWayNetGrid() } returns grid
		every { env.getRoutingServices() } returns mockk<RoutingServices>(relaxed = true)
		return DefaultNetworkActuatorPort(env = env, interlockingFacade = facade)
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cz.vutbr.fit.interlockSim.ports.DenialCauseMappingTest#samples")
	@DisplayName("each DenialCause maps to its RouteRequestResult")
	fun mapsEachCause(
		cause: DenialCause,
		expected: RouteRequestResult
	) {
		val facade = mockk<InterlockingFacade>()
		every { facade.requestRouteByEndpoints("T1", "A", "B") } returns
			InterlockingFacade.RouteResponse.Denied(REASON, cause)

		assertThat(portWithFacade(facade).requestRoute("T1", "A", "B")).isEqualTo(expected)
	}

	/**
	 * `sealedSubclasses` reports **direct** subtypes only; the hierarchy is flat today, so a
	 * deeper subtype would need this guard to recurse.
	 */
	@Test
	@DisplayName("the sample table covers every DenialCause subtype")
	fun providerCoversEverySubtype() {
		val causes = samples().map { it.get()[0] as DenialCause }

		assertThat(DenialCause::class.sealedSubclasses).hasSize(causes.size)
		assertThat(causes.map { it::class }.toSet()).isEqualTo(DenialCause::class.sealedSubclasses.toSet())
	}

	companion object {
		private const val REASON = "kernel reason"

		@JvmStatic
		fun samples(): List<Arguments> =
			listOf(
				Arguments.of(DenialCause.NoPath, RouteRequestResult.NoRouteExists("A", "B")),
				Arguments.of(DenialCause.AllPathsBlocked(3), RouteRequestResult.AllPathsBlocked(3)),
				Arguments.of(DenialCause.Conflict("U7", "T2"), RouteRequestResult.Conflict("U7", "T2")),
				Arguments.of(DenialCause.NonContiguousStart, RouteRequestResult.OriginNotContiguous("A", REASON)),
				Arguments.of(DenialCause.UnresolvedEndpoint("A"), RouteRequestResult.UnresolvedEndpoint("A")),
				Arguments.of(DenialCause.ConditionFailed(true), RouteRequestResult.ConditionFailed(REASON, true)),
				Arguments.of(
					DenialCause.GeometricallyImpossible("faces away"),
					RouteRequestResult.GeometricallyImpossible("faces away")
				),
				Arguments.of(
					DenialCause.DivergesFromHeldRoute("doB2", "diverges"),
					RouteRequestResult.DivergesFromHeldRoute("doB2", "diverges")
				)
			)
	}
}
