/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Topology derivation of [Station] (Goal 1B SP2.1, Issue #1161; SIM-004).
 *
 * The vyhybna parity tests pin the derived blocks, signals and switches against the
 * hand-written `ShuntingLoop.COORD_*` contract that the derivation replaces. The other tests
 * show that the same derivation builds a [Station] from the larger fixture networks.
 */
class StationTest : CommonKoinTestBase() {
	private fun load(xml: String): DefaultSimulationContext {
		val ctx = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		return ctx
	}

	private fun DefaultSimulationContext.locationOf(cell: Cell): Point? = getRailWayNetGrid().getLocation(cell)

	@Test
	fun vyhybnaPlatformTracksMatchTheOldCoordinateContract() {
		val ctx = load(NetworkResources.VYHYBNA_XML)
		val station = Station.from(ctx)

		assertThat(station.platformTracks.map { it.name }).containsExactly("k1", "k2")
		val (k1, k2) = station.platformTracks
		assertThat(k1.signals.map { ctx.locationOf(it) }).containsExactlyInAnyOrder(
			Point(ShuntingLoop.COORD_SEM_DOA1_X, ShuntingLoop.COORD_SEM_DOA1_Y),
			Point(ShuntingLoop.COORD_SEM_DOB1_X, ShuntingLoop.COORD_SEM_DOB1_Y)
		)
		assertThat(k2.signals.map { ctx.locationOf(it) }).containsExactlyInAnyOrder(
			Point(ShuntingLoop.COORD_SEM_DOA2_X, ShuntingLoop.COORD_SEM_DOA2_Y),
			Point(ShuntingLoop.COORD_SEM_DOB2_X, ShuntingLoop.COORD_SEM_DOB2_Y)
		)
		// The same graph edges the old coordinate lookup resolved.
		val graph = ctx.getGraph()
		assertThat(k1.block).isSameInstanceAs(
			graph[
				Point(ShuntingLoop.COORD_SEM_DOA1_X, ShuntingLoop.COORD_SEM_DOA1_Y),
				Point(ShuntingLoop.COORD_SEM_DOB1_X, ShuntingLoop.COORD_SEM_DOB1_Y)
			]
		)
		assertThat(k2.block).isSameInstanceAs(
			graph[
				Point(ShuntingLoop.COORD_SEM_DOA2_X, ShuntingLoop.COORD_SEM_DOA2_Y),
				Point(ShuntingLoop.COORD_SEM_DOB2_X, ShuntingLoop.COORD_SEM_DOB2_Y)
			]
		)
	}

	@Test
	fun vyhybnaEntryExitBlocksMatchTheOldCoordinateContract() {
		val ctx = load(NetworkResources.VYHYBNA_XML)
		val station = Station.from(ctx)

		// Order kB, kA is the old ShuntingLoop insertion order; it feeds the dispatcher
		// observation, so it must not change.
		assertThat(station.entryExitBlocks.map { it.name }).containsExactly("kB", "kA")
		val (kB, kA) = station.entryExitBlocks
		assertThat(ctx.locationOf(requireNotNull(kB.inOut))).isEqualTo(
			Point(ShuntingLoop.COORD_IN_B_X, ShuntingLoop.COORD_IN_B_Y)
		)
		assertThat(kB.signals.map { ctx.locationOf(it) }).containsExactly(
			Point(ShuntingLoop.COORD_SEM_ZB_X, ShuntingLoop.COORD_SEM_ZB_Y)
		)
		assertThat(ctx.locationOf(requireNotNull(kA.inOut))).isEqualTo(
			Point(ShuntingLoop.COORD_IN_A_X, ShuntingLoop.COORD_IN_A_Y)
		)
		assertThat(kA.signals.map { ctx.locationOf(it) }).containsExactly(
			Point(ShuntingLoop.COORD_SEM_ZA_X, ShuntingLoop.COORD_SEM_ZA_Y)
		)
		val graph = ctx.getGraph()
		assertThat(kA.block).isSameInstanceAs(
			graph[
				Point(ShuntingLoop.COORD_IN_A_X, ShuntingLoop.COORD_IN_A_Y),
				Point(ShuntingLoop.COORD_SEM_ZA_X, ShuntingLoop.COORD_SEM_ZA_Y)
			]
		)
		assertThat(kB.block).isSameInstanceAs(
			graph[
				Point(ShuntingLoop.COORD_IN_B_X, ShuntingLoop.COORD_IN_B_Y),
				Point(ShuntingLoop.COORD_SEM_ZB_X, ShuntingLoop.COORD_SEM_ZB_Y)
			]
		)
	}

	@Test
	fun vyhybnaSwitchesMatchTheOldCoordinateContract() {
		val ctx = load(NetworkResources.VYHYBNA_XML)
		val station = Station.from(ctx)

		assertThat(station.switches.map { ctx.locationOf(it) }).containsExactlyInAnyOrder(
			Point(ShuntingLoop.COORD_SW_A_X, ShuntingLoop.COORD_SW_A_Y),
			Point(ShuntingLoop.COORD_SW_B_X, ShuntingLoop.COORD_SW_B_Y)
		)
	}

	@Test
	fun signalsOfReturnsTheSignalPerBlockMapping() {
		val ctx = load(NetworkResources.VYHYBNA_XML)
		val station = Station.from(ctx)

		for (stationBlock in station.platformTracks + station.entryExitBlocks) {
			assertThat(station.signalsOf(stationBlock.block)).isEqualTo(stationBlock.signals)
		}
	}

	@Test
	fun applyBlockNamesNamesTheVyhybnaBlocks() {
		val ctx = load(NetworkResources.VYHYBNA_XML)
		val station = Station.from(ctx)

		station.applyBlockNames()

		assertThat((station.platformTracks + station.entryExitBlocks).map { it.block.name })
			.containsExactly("k1", "k2", "kB", "kA")
	}

	@Test
	fun buildsFromCervenyUjezd() {
		val ctx = load(NetworkResources.CERVENY_UJEZD_XML)
		val station = Station.from(ctx)

		// Four signal-bounded station tracks (rows 30-33), four InOut lines, twelve switches.
		assertThat(station.platformTracks).hasSize(4)
		assertThat(station.entryExitBlocks).hasSize(4)
		assertThat(station.entryExitBlocks.map { it.signals.size }).containsExactly(1, 1, 1, 1)
		assertThat(station.switches).hasSize(12)
	}

	@Test
	fun buildsFromPrahaHlavniNadrazi() {
		val ctx = load(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML)
		val station = Station.from(ctx)

		// Eight platform tracks plus the bypass station track; the signal-bounded block next to
		// the N-Bypass home signal is a throat block, not a station track.
		assertThat(station.platformTracks).hasSize(9)
		assertThat(station.entryExitBlocks).hasSize(11)
		assertThat(station.switches).hasSize(50)
	}
}
