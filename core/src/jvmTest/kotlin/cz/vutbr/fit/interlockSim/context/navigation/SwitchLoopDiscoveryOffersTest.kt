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
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.component.get

/**
 * Issue #1390 review round: the walk's visited key (separator, arrival section) may expand a
 * switch through both of its sides, so discovery offered the west semaphore `T` from the entry
 * `S` — and the InOut `S` from `T` — on the three-switch loop of
 * [NetworkResources.SWITCH_LOOP_UNDRIVABLE_TARGET_XML], although every switch-legal route to
 * them repeats a separator ([TopologyNavigator.findAllSwitchConstrainedPaths] refuses those).
 * The offer list of [ReservationTargetDiscovery.findNextSemaphoresVia] must be drivability
 * filtered: from `S` and from `T` exactly [u, s2] (InOut first) survive, while every offer a
 * simple route supports keeps. The exact lists pin the filter against both over-pruning and a
 * vacuous pass; the entry probe pins that the narrowing still routes the entry itself.
 */
@Tag("integration-test")
class SwitchLoopDiscoveryOffersTest : PathReservationServiceTestBase() {
	override fun loadTestContext(): DefaultSimulationContext =
		CommonTestFixtures.parseSimulationContext(NetworkResources.SWITCH_LOOP_UNDRIVABLE_TARGET_XML, get())

	private lateinit var discovery: ReservationTargetDiscovery

	@BeforeEach
	fun createDiscovery() {
		discovery = ReservationTargetDiscovery(navigator, simulationContext)
	}

	@Test
	fun `the entry offers exactly the targets simple routes can reach`() {
		assertThat(offers(inOutNamed(ENTRY), Cell.Segment.F), name = "targets offered from $ENTRY")
			.isEqualTo(listOf(EXIT, THROAT))
	}

	@Test
	fun `the west semaphore is not offered the entry through the loop`() {
		assertThat(offers(findSemaphoreByName(WEST), Cell.Segment.F), name = "targets offered from $WEST")
			.isEqualTo(listOf(EXIT, THROAT))
	}

	@Test
	fun `the exit keeps both of its loop targets`() {
		assertThat(offers(inOutNamed(EXIT), Cell.Segment.A), name = "targets offered from $EXIT")
			.isEqualTo(listOf(ENTRY, WEST))
	}

	@Test
	fun `the entry probe answers true from the drivable offers`() {
		val entry =
			inOutNamed(ENTRY)
		val next =
			sectionOf(entry, Cell.Segment.F)

		assertThat(discovery.isAnyNextTargetAvailable(entry, next), name = "$ENTRY availability").isTrue()
	}

	@Test
	fun `the dispatcher candidate query offers only the drivable entry targets`() {
		val candidates =
			discovery
				.findReservationTargetCandidates(inOutNamed(ENTRY), null)
				.map { separatorNameOf(it.separator)!! }

		assertThat(candidates, name = "candidate targets from $ENTRY").isEqualTo(listOf(EXIT, THROAT))
	}

	/** Names of the targets [findNextSemaphoresVia] offers from [start] leaving over [segment]. */
	private fun offers(
		start: DynamicPathSeparator,
		segment: Cell.Segment
	): List<String> = discovery.findNextSemaphoresVia(start, sectionOf(start, segment)).map { separatorNameOf(it)!! }

	/** The track section whose assigned edge at [start]'s grid cell is [segment]. */
	private fun sectionOf(
		start: DynamicPathSeparator,
		segment: Cell.Segment
	) = simulationContext
		.getGraph()
		.assignedEdges(simulationContext.getRailWayNetGrid().getLocation(start)!!)
		.getValue(segment)

	private companion object {
		const val ENTRY = "S"
		const val WEST = "T"
		const val THROAT = "s2"
		const val EXIT = "u"
	}
}
