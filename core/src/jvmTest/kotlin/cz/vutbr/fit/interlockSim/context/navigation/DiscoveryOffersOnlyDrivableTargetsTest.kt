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
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
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
 *
 * The `switchLoop` row is the Issue #1390 review-round regression: on that three-switch loop the
 * pre-filter walk offered the signal whose every route repeats a separator — and the start itself,
 * which the revisit arm reached again. The drivability predicate stays re-implemented in
 * [auditOffers] on purpose, so the pin stays an independent derivation, not a call into the
 * production filter.
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
		val (offered, undrivable) = auditOffers(ctx)

		assertThat(offered, name = "targets offered on $name (the check must not be vacuous)").isGreaterThan(0)
		assertThat(undrivable, name = "offered targets on $name without a drivable route").isEmpty()
	}

	/**
	 * The drivability audit of [ctx]: [offered] is every target
	 * [ReservationTargetDiscovery.findNextSemaphoresVia] offers from every semaphore and InOut
	 * through every section that leaves it; [undrivable] holds the "start via segment -> target"
	 * labels of the offered targets whose every route fails the drivable check below. The offer
	 * count also guards against a regression that filters the offers away entirely.
	 */
	private fun auditOffers(ctx: DefaultSimulationContext): Pair<Int, List<String>> {
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
					if (!isDrivable(navigator, ctx, start, next, target)) {
						undrivable += "${separatorLabel(start)} via $segment -> ${separatorLabel(target)}"
					}
				}
			}
		}
		return offered to undrivable
	}

	/**
	 * Whether [target] is drivable from [start] over [next]: some switch-legal route starts with
	 * [next] and — for a signal — ends facing the train at its own last section. An empty path
	 * (the start offered over a loop) starts no route and counts as undrivable.
	 */
	private fun isDrivable(
		navigator: TopologyNavigator,
		ctx: DefaultSimulationContext,
		start: DynamicPathSeparator,
		next: TrackSection,
		target: DynamicPathSeparator
	): Boolean =
		navigator.findAllSwitchConstrainedPaths(start, target, DEFAULT_MAX_PATH_DEPTH).any { path ->
			path.isNotEmpty() &&
				path.first() == next &&
				(
					target is DynamicInOut ||
						target !is OrientedPathSeparator ||
						semaphoreFacesArrivalVia(ctx, target, path.last()) == true
				)
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
				arrayOf("twoTracksParallel", NetworkResources.TWO_TRACKS_PARALLEL_XML),
				arrayOf("switchLoop", NetworkResources.SWITCH_LOOP_UNDRIVABLE_TARGET_XML)
			)
	}
}
