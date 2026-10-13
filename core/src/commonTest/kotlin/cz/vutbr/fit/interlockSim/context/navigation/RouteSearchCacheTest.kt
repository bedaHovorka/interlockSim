/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP4 (#1151): static route cache of the topology navigator.
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.component.get
import kotlin.test.Test

/**
 * The switch-constrained route search of a simulation context's [DefaultTopologyNavigator] is
 * cached per `(in, out)` pair for the lifetime of the navigator (Issue #1151).
 *
 * The cache holds topology only: a route stays in it whether its blocks are free or reserved, so
 * the reservation outcome is always decided on the live block state, never on a cached answer.
 */
class RouteSearchCacheTest : CommonKoinTestBase() {
	private fun vyhybna(): DefaultSimulationContext =
		CommonTestFixtures.parseSimulationContext(NetworkResources.VYHYBNA_XML, get()).tracked()

	private fun DefaultSimulationContext.navigator(): DefaultTopologyNavigator =
		getRoutingServices().getTopologyNavigator() as DefaultTopologyNavigator

	private fun DefaultSimulationContext.inOutPair(): Pair<InOut, InOut> {
		val inOuts = getInOuts().toList()
		return inOuts[0].staticRef to inOuts[1].staticRef
	}

	@Test
	fun secondQueryForSamePairDoesNotReEnumerate() {
		val ctx = vyhybna()
		val navigator = ctx.navigator()
		val (from, to) = ctx.inOutPair()
		val routeFinder = ctx.getRouteFinder()

		val first = routeFinder.findRoutes(from, to, ctx).map { it.segments }
		val second = routeFinder.findRoutes(from, to, ctx).map { it.segments }

		assertThat(second, name = "routes of the second query").isEqualTo(first)
		assertThat(navigator.routeEnumerationCount, name = "enumerations").isEqualTo(1)
		assertThat(navigator.routeCacheSize, name = "cached pairs").isEqualTo(1)
	}

	@Test
	fun cachedRoutesMatchAnUncachedEnumerationInOrder() {
		val ctx = vyhybna()
		val (from, to) = ctx.inOutPair()
		val uncached = DefaultTopologyNavigator(ctx, cacheRoutes = false)

		val expected = uncached.findAllSwitchConstrainedPaths(from, to)
		val cachedNavigator = ctx.navigator()
		cachedNavigator.findAllSwitchConstrainedPaths(from, to)

		assertThat(cachedNavigator.findAllSwitchConstrainedPaths(from, to), name = "cached routes")
			.isEqualTo(expected)
		assertThat(uncached.routeCacheSize, name = "uncached navigator cache").isZero()
	}

	@Test
	fun topologyChangeBuildsNewNavigatorWithEmptyCache() {
		val first = vyhybna()
		val (from, to) = first.inOutPair()
		first.navigator().findAllSwitchConstrainedPaths(from, to)
		assertThat(first.navigator().routeCacheSize, name = "warm cache").isEqualTo(1)

		// A simulation context's topology never changes; a different topology is a new context,
		// and a new context gets its own navigator.
		val changed =
			CommonTestFixtures.parseSimulationContext(NetworkResources.CERVENY_UJEZD_XML, get()).tracked()

		assertThat(changed.navigator(), name = "navigator of the changed topology")
			.isNotSameInstanceAs(first.navigator())
		assertThat(changed.navigator().routeCacheSize, name = "cache of the new navigator").isZero()
		assertThat(changed.navigator().routeEnumerationCount, name = "enumerations of the new navigator")
			.isZero()
	}

	@Test
	fun editingContextNavigatorDoesNotCache() {
		// The editor changes the topology in place, so its navigator must enumerate every time.
		val editing = CommonTestFixtures.parseEditingContext(NetworkResources.VYHYBNA_XML).tracked()
		val navigator = editing.getTopologyNavigator() as DefaultTopologyNavigator
		val inOuts = editing.getInOuts().toList()

		navigator.findAllSwitchConstrainedPaths(inOuts[0], inOuts[1])
		navigator.findAllSwitchConstrainedPaths(inOuts[0], inOuts[1])

		assertThat(navigator.routeEnumerationCount, name = "enumerations").isEqualTo(2)
		assertThat(navigator.routeCacheSize, name = "cached pairs").isZero()
	}

	@Test
	fun cacheHoldsNoReservationState() {
		val ctx = vyhybna()
		val navigator = ctx.navigator()
		val service = ctx.getRoutingServices().getPathReservationService()
		val (from, to) = ctx.inOutPair()
		val dynamicFrom = ctx.getInOuts().single { it.staticRef === from }
		val dynamicTo = ctx.getInOuts().single { it.staticRef === to }
		val routesBefore = ctx.getRouteFinder().findRoutes(from, to, ctx).map { it.segments }
		assertThat(routesBefore.size, name = "vyhybna routes between the InOuts").isEqualTo(2)

		// t1 takes a route; both vyhybna routes share the entry and exit blocks, so the cached
		// routes are still both listed, but t2 now finds them blocked.
		assertThat(service.reservePath("t1", dynamicFrom, dynamicTo))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(ctx.getRouteFinder().findRoutes(from, to, ctx).map { it.segments }, name = "routes after t1")
			.isEqualTo(routesBefore)
		assertThat(service.reservePath("t2", dynamicFrom, dynamicTo))
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// After the release the same cached routes serve t2: the cache kept no "blocked" answer.
		service.unregister("t1")
		assertThat(service.reservePath("t2", dynamicFrom, dynamicTo))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()

		assertThat(ctx.getRouteFinder().findRoutes(from, to, ctx).map { it.segments }, name = "routes at the end")
			.isEqualTo(routesBefore)
		assertThat(navigator.routeEnumerationCount, name = "enumerations").isEqualTo(navigator.routeCacheSize)
	}
}
