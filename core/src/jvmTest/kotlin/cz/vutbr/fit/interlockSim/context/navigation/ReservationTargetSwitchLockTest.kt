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
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
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
 * Most tests put the lock in place the way a live route leaves it — switch thrown, locked and
 * registered to its train — without reserving blocks, so the switch is the only thing that can make
 * a target unavailable there. The block half of the conjunction is guarded separately: one test
 * reserves a route's blocks the way a live reservation leaves them ([DynamicTrackBlock.setUpPath]),
 * and the route, though switch-compatible, must not make the target available.
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

	/** The dynamic separator cell named [name], as [separatorLabel] spells it. */
	private fun separatorNamed(name: String): DynamicPathSeparator =
		ctx.cellsOfType<DynamicPathSeparator>().single { separatorLabel(it) == name }

	/** Target name to availability, for the candidates one section ahead of the separator [start]. */
	private fun availabilityFrom(
		start: String,
		ownerTrainId: String? = null
	): Map<String, Boolean> =
		query
			.findReservationTargetCandidates(separatorNamed(start) as OrientedPathSeparator, ownerTrainId)
			.associate { separatorLabel(it.separator) to it.available }

	/**
	 * The blocks of the route from [start] to the separator named [target] that runs over SW5 and SW9
	 * only — the Praha fixture's MAIN route to `S5` — MINUS the blocks its BRANCH route (over SW7 and
	 * SW8) shares with it. Enumerated the way the availability conjunction enumerates its route paths,
	 * so the reserved blocks fall on exactly the paths availability is evaluated over.
	 *
	 * The reservation must not touch a shared block: with every route block-held, the block-only first
	 * pass rules the target out and the two-conjunct switch pass — the code under test — is never
	 * reached; the BRANCH route must stay block-free so only its switch lock can kill it there.
	 */
	private fun exclusiveMainRouteBlocks(
		start: PathSeparator,
		target: String
	): List<DynamicTrackBlock> {
		val navigator = ctx.getRoutingServices().getTopologyNavigator()
		val targetSeparator = separatorNamed(target)
		val paths = navigator.findAllSwitchConstrainedPaths(start, targetSeparator)
		val mainRoute = paths.single { path -> switchesOn(start, path).none { it.name in BRANCH_ONLY_SWITCHES } }
		val branchBlocks =
			paths
				.filter { path -> path !== mainRoute }
				.flatMapTo(mutableSetOf()) { path -> extractUniqueBlocks(path) }
		return extractUniqueBlocks(mainRoute).filterNot { it in branchBlocks }
	}

	/** The switches [path] (a route out of [start]) joins its sections through, the way availability walks a route. */
	private fun switchesOn(
		start: PathSeparator,
		path: List<TrackSection>
	): List<DynamicRailSwitch> {
		var separator: PathSeparator = start
		return path
			.zipWithNext()
			.map { (incoming, _) ->
				separator = incoming.getSecondEnd(separator)
				ctx.toDynamic(separator) as? DynamicRailSwitch
			}.filterNotNull()
			.distinct()
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

	@Test
	fun `a switch-compatible route does not make the target available while another train holds its blocks`() {
		load(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML)
		val start = separatorNamed("S_8_4")

		lockFor(TRAIN_4, switchNamed("SW9"), RailSwitch.Conf.MAIN)
		val mainOnlyBlocks = exclusiveMainRouteBlocks(start, "S5")
		mainOnlyBlocks.forEach { it.setUpPath(start, TRAIN_5) }

		assertThat(
			availabilityFrom("S_8_4").getValue("S5"),
			name = "SW9 MAIN held, MAIN route's blocks held by Train #5"
		).isEqualTo(false)

		mainOnlyBlocks.forEach { it.cancelPathSetup(start) }
		assertThat(
			availabilityFrom("S_8_4").getValue("S5"),
			name = "blocks released, SW9 still locked in MAIN"
		).isEqualTo(true)
	}

	private companion object {
		const val TRAIN_4 = "Train #4"
		const val TRAIN_5 = "Train #5"
		const val TRAIN_6 = "Train #6"

		/** Switches only the `S_8_4` to `S5` BRANCH route runs over; the MAIN route weaves through SW5 and SW9. */
		val BRANCH_ONLY_SWITCHES = setOf("SW7", "SW8")
	}
}
