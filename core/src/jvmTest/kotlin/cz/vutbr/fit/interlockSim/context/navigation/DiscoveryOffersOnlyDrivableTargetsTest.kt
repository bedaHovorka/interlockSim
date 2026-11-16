/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.component.get

/**
 * Issue #1390 (traffic-simulation-expert ruling, item F2): candidate discovery offers only targets a
 * train can drive to. For every signal and InOut of a shipped network, through every section that
 * leaves it, each separator [ReservationTargetDiscovery.findNextSemaphoresVia] returns must have at
 * least one switch-legal route ([TopologyNavigator.findAllSwitchConstrainedPaths]) that starts with
 * that section and ends facing the train (the G8 rule, [semaphoreFacesArrivalVia]).
 *
 * Before Issue #1390 the walk followed every edge at a switch and judged every signal against the
 * start's direction, so it offered signals on the neighbour track reached by turning back through a
 * crossover: 114 of the calls below changed, and every target they lost had no such route.
 */
@Tag("integration-test")
class DiscoveryOffersOnlyDrivableTargetsTest : CommonKoinTestBase() {
	@ParameterizedTest(name = "{0}")
	@MethodSource("networks")
	fun `every offered target has a switch-legal route through the same section that ends facing the train`(
		name: String,
		xml: String
	) {
		val ctx = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		val navigator = ctx.getRoutingServices().getTopologyNavigator()
		val discovery = ReservationTargetDiscovery(navigator, ctx)
		val starts: List<DynamicPathSeparator> =
			ctx.cellsOfType<DynamicRailSemaphore>() + ctx.getInOuts().map { ctx.toDynamic(it) }
		val undrivable = mutableListOf<String>()
		var offered = 0
		for (start in starts) {
			val location = ctx.getRailWayNetGrid().getLocation(start) ?: continue
			for ((segment, next) in ctx.getGraph().assignedEdges(location)) {
				for (target in discovery.findNextSemaphoresVia(start, next)) {
					offered++
					val drivable =
						navigator.findAllSwitchConstrainedPaths(start, target, DEFAULT_MAX_PATH_DEPTH).any { path ->
							path.first() == next &&
								(
									target is DynamicInOut ||
										target !is OrientedPathSeparator ||
										semaphoreFacesArrivalVia(ctx, target, path.last()) == true
								)
						}
					if (!drivable) undrivable += "${separatorLabel(start)} via $segment -> ${separatorLabel(target)}"
				}
			}
		}

		assertThat(offered, name = "targets offered on $name (the check must not be vacuous)").isGreaterThan(0)
		assertThat(undrivable, name = "offered targets on $name without a drivable route").isEmpty()
	}

	companion object {
		@JvmStatic
		fun networks(): List<Array<Any>> =
			listOf(
				arrayOf("vyhybna", NetworkResources.VYHYBNA_XML),
				arrayOf("cervenyUjezd", NetworkResources.CERVENY_UJEZD_XML),
				arrayOf("prahaStation", NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML),
				arrayOf("praha", NetworkResources.PRAHA_HLAVNI_NADRAZI_XML),
				arrayOf("stationThroat", NetworkResources.STATION_THROAT_AND_AUTOMATIC_BLOCK_XML),
				arrayOf("switchBetweenSemaphores", NetworkResources.SWITCH_BETWEEN_SEMAPHORES_XML),
				arrayOf("switchBasic", NetworkResources.SWITCH_BASIC_XML),
				arrayOf("twoTracksParallel", NetworkResources.TWO_TRACKS_PARALLEL_XML)
			)
	}
}
