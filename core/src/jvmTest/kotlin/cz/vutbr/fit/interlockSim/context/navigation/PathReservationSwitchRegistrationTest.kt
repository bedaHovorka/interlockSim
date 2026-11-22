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
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Pins that the born-stale filter of `SwitchConfigurator.registerSwitches` (Issue #1173) never
 * drops a switch the reserved route really rolls over, tested through `reservePath` (Step 2f)
 * rather than on a hand-built block list.
 *
 * Runs on `vyhybna.xml`, where `inOut1 → inOut2` crosses exactly `vA` and `vB`; the expected
 * switches are pinned by name so the test does not derive them from the reservation's own
 * `PathInfo`. Only running (traversed) switches are covered: a flank claim is never stale
 * (`isStaleSwitchOwnership` is `false` for it) and `wouldBeStaleClaim` is not applied to it.
 * A route that crosses many switches, some with a held block on both sides:
 * [PathReservationPrahaSwitchRegistrationTest].
 */
@Tag("integration-test")
class PathReservationSwitchRegistrationTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePath registers and locks every switch of the reserved route`() {
		val result = service.reservePath(TRAIN, inOut1, inOut2)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val expected = listOf(findSwitchByName("vA"), findSwitchByName("vB"))
		assertThat(registry.getSwitches(TRAIN)).containsExactlyInAnyOrder(*expected.toTypedArray())
		expected.forEach { switch ->
			assertThat(registry.getSwitchOwner(switch), name = "owner of ${switch.name}").isEqualTo(TRAIN)
			assertThat(switch.locked, name = "${switch.name} locked").isTrue()
			assertThat(registry.isStaleSwitchOwnership(switch), name = "${switch.name} stale").isFalse()
		}
	}

	@Test
	fun `the registered switches are exactly the switches of the reserved path`() {
		service.reservePath(TRAIN, inOut1, inOut2)

		val pathInfo = registry.getPathInfo(TRAIN)
		assertThat(pathInfo, name = "path info").isNotNull()
		val pathSwitches = pathInfo!!.reservedPath.filterIsInstance<DynamicRailSwitch>().toSet()

		assertThat(registry.getSwitches(TRAIN)).containsExactlyInAnyOrder(*pathSwitches.toTypedArray())
	}

	private companion object {
		const val TRAIN = "train1"
	}
}
