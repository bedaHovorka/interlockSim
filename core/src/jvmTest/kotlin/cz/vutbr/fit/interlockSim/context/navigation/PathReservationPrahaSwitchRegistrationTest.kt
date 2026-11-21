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
import assertk.assertions.containsAtLeast
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.component.get

/**
 * Issue #1173 pin, through `reservePath` (Step 2f), on a route that crosses many switches
 * (`praha-hlavni-nadrazi.xml`, `N-Lib-1 → S-Vin-1`): every switch the route rolls over is
 * registered, locked and not stale, and some of them have a held block on BOTH sides -- the
 * mid-path case `vyhybna.xml` cannot show, where each of its two switches sits next to the entry
 * or the exit. The born-stale filter must keep all of them
 * ([PathReservationSwitchRegistrationTest] has the two-switch case).
 */
@Tag("integration-test")
class PathReservationPrahaSwitchRegistrationTest : CommonKoinTestBase() {
	private lateinit var ctx: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry

	private fun reserveRoute() {
		ctx = CommonTestFixtures.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML, get()).tracked()
		registry = ctx.scope.get()
		val inOuts =
			ctx
				.getInOuts()
				.map { ctx.toDynamic(it) }
				.filterIsInstance<DynamicInOut>()
				.associateBy { it.name }
		val service = ctx.getRoutingServices().getPathReservationService()
		val result = service.reservePath(TRAIN, inOuts.getValue(ENTRY), inOuts.getValue(EXIT))
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
	}

	private fun routeSwitches(): Set<DynamicRailSwitch> {
		val pathInfo = registry.getPathInfo(TRAIN)
		assertThat(pathInfo, name = "path info").isNotNull()
		return pathInfo!!.reservedPath.filterIsInstance<DynamicRailSwitch>().toSet()
	}

	@Test
	fun `every switch of a many-switch route is registered, locked and not stale`() {
		reserveRoute()

		val routeSwitches = routeSwitches()
		assertThat(routeSwitches.size, name = "switches on the route").isGreaterThan(2)
		assertThat(registry.getSwitches(TRAIN)).containsAtLeast(*routeSwitches.toTypedArray())
		routeSwitches.forEach { switch ->
			assertThat(registry.getSwitchOwner(switch), name = "owner of ${switch.name}").isEqualTo(TRAIN)
			assertThat(switch.locked, name = "${switch.name} locked").isTrue()
			assertThat(registry.isStaleSwitchOwnership(switch), name = "${switch.name} stale").isFalse()
		}
	}

	@Test
	fun `a switch with a held block on both sides is kept`() {
		reserveRoute()

		val held = registry.getBlocks(TRAIN)
		val midRoute = routeSwitches().filter { switch -> held.count { switch in it.ends() } == 2 }

		assertThat(midRoute.size, name = "switches with a held block on both sides").isGreaterThan(0)
		midRoute.forEach { switch ->
			assertThat(registry.wouldBeStaleClaim(switch, TRAIN), name = "${switch.name} would be stale").isFalse()
			assertThat(registry.getSwitchOwner(switch), name = "owner of ${switch.name}").isEqualTo(TRAIN)
		}
	}

	private companion object {
		const val TRAIN = "train1"
		const val ENTRY = "N-Lib-1"
		const val EXIT = "S-Vin-1"
	}
}
