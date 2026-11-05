/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.component.get

/**
 * Target availability in the read-only candidate query takes the switch locks of live routes into
 * account (Issue #1359): a target is available only if at least one route to it is block-free AND
 * switch-compatible — every switch on it is unlocked, locked in the position the route needs, or
 * locked by the asking train itself.
 *
 * The lock is put in place the way a live route leaves it — switch thrown, locked and registered to
 * its train — without reserving blocks, so the switch is the only thing that can make a target
 * unavailable here.
 *
 * Routes measured on the fixtures (switch-constrained enumeration):
 * - `cerveny_ujezd.xml`, from `homeW2`: `e31` over vW2/vW3 BRANCH + vW5 MAIN, `e30` over vW2/vW3/vW5
 *   BRANCH, `e32`/`e33` over vW2/vW4 MAIN (one route each); `homeW1` is a candidate without a
 *   route. This is the #1359 log: Train #4 holds vW5 in MAIN and Train #5's route to `e30` needs
 *   BRANCH.
 * - `praha-hlavni-nadrazi.xml`, from `S_8_4`: two routes to `S5`, one over SW5 MAIN + SW9 MAIN, the
 *   other over SW5 BRANCH, SW7 BRANCH, SW8 BRANCH, SW9 BRANCH.
 */
@DisplayName("Reservation target availability and switch locks (Issue #1359)")
class ReservationTargetSwitchLockTest : CommonKoinTestBase() {
	private lateinit var ctx: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var query: ReservationTargetQuery

	private fun load(xml: String) {
		ctx = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		registry = ctx.scope.get()
		query = ctx.getRoutingServices().getReservationTargetQuery()
	}

	private fun switchNamed(name: String): DynamicRailSwitch =
		ctx.cellsOfType<DynamicRailSwitch>().single {
			it.name ==
				name
		}

	/** Throw [switch] to [conf], lock it and register it to [owner] — the state a live route over it leaves. */
	private fun lockFor(
		owner: String,
		switch: DynamicRailSwitch,
		conf: RailSwitch.Conf
	) {
		if (switch.conf != conf) switch.changeConf()
		switch.lock()
		registry.registerSwitches(owner, listOf(switch))
	}

	/** Target name to availability, for the candidates one section ahead of the separator [start]. */
	private fun availabilityFrom(
		start: String,
		ownerTrainId: String? = null
	): Map<String, Boolean> {
		val separator =
			ctx.cellsOfType<DynamicPathSeparator>().single { separatorLabel(it) == start } as OrientedPathSeparator
		return query
			.findReservationTargetCandidates(separator, ownerTrainId)
			.associate { separatorLabel(it.separator) to it.available }
	}

	@Test
	fun `a target whose only route needs a switch locked the other way is unavailable until the lock is released`() {
		load(NetworkResources.CERVENY_UJEZD_XML)
		lockFor(TRAIN_4, switchNamed("vW5"), RailSwitch.Conf.MAIN)

		assertThat(availabilityFrom("homeW2"), name = "while vW5 is locked in MAIN")
			.isEqualTo(mapOf("homeW1" to false, "e31" to true, "e30" to false, "e32" to true, "e33" to true))

		registry.unregisterSwitches(TRAIN_4)

		assertThat(availabilityFrom("homeW2"), name = "after vW5 is released")
			.isEqualTo(mapOf("homeW1" to false, "e31" to true, "e30" to true, "e32" to true, "e33" to true))
	}

	@Test
	fun `a switch locked by the asking train itself does not make the target unavailable`() {
		load(NetworkResources.CERVENY_UJEZD_XML)
		lockFor(TRAIN_5, switchNamed("vW5"), RailSwitch.Conf.MAIN)

		assertThat(availabilityFrom("homeW2", TRAIN_5).getValue("e30"), name = "e30 asked by the lock holder")
			.isEqualTo(true)
		assertThat(availabilityFrom("homeW2", TRAIN_6).getValue("e30"), name = "e30 asked by another train")
			.isEqualTo(false)
	}

	@Test
	fun `a target stays available while one of its routes is switch-compatible`() {
		load(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML)
		val sw5 = switchNamed("SW5")
		val sw9 = switchNamed("SW9")

		lockFor(TRAIN_4, sw9, RailSwitch.Conf.MAIN)
		assertThat(availabilityFrom("S_8_4").getValue("S5"), name = "SW9 locked in MAIN (the MAIN route fits)")
			.isEqualTo(true)
		registry.unregisterSwitches(TRAIN_4)

		lockFor(TRAIN_4, sw9, RailSwitch.Conf.BRANCH)
		assertThat(availabilityFrom("S_8_4").getValue("S5"), name = "SW9 locked in BRANCH (the BRANCH route fits)")
			.isEqualTo(true)

		lockFor(TRAIN_6, sw5, RailSwitch.Conf.MAIN)
		assertThat(availabilityFrom("S_8_4").getValue("S5"), name = "SW5 MAIN + SW9 BRANCH (no route fits)")
			.isEqualTo(false)
	}

	private companion object {
		const val TRAIN_4 = "Train #4"
		const val TRAIN_5 = "Train #5"
		const val TRAIN_6 = "Train #6"
	}
}
