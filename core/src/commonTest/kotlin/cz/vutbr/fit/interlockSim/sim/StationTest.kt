/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.component.get
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Topology derivation of [Station] (Goal 1B SP2.1, Issue #1161; SIM-004).
 *
 * The vyhybna parity tests pin the derived blocks, signals and switches against the
 * hand-written `ShuntingLoop.COORD_*` contract that the derivation replaces. The other tests
 * show that the same derivation builds a [Station] from the larger fixture networks and
 * rejects networks `ShuntingLoop` cannot run.
 */
class StationTest : CommonKoinTestBase() {
	private fun load(xml: String): DefaultSimulationContext {
		val ctx = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		return ctx
	}

	private fun vyhybna(): Pair<DefaultSimulationContext, Station> {
		val ctx = load(NetworkResources.VYHYBNA_XML)
		return ctx to Station.from(ctx)
	}

	private fun DefaultSimulationContext.locationOf(cell: Cell): Point? = getRailWayNetGrid().getLocation(cell)

	@Test
	fun vyhybnaPlatformTracksMatchTheOldCoordinateContract() {
		val (ctx, station) = vyhybna()

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
		val (ctx, station) = vyhybna()

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
		val (ctx, station) = vyhybna()

		assertThat(station.switches.map { ctx.locationOf(it) }).containsExactlyInAnyOrder(
			Point(ShuntingLoop.COORD_SW_A_X, ShuntingLoop.COORD_SW_A_Y),
			Point(ShuntingLoop.COORD_SW_B_X, ShuntingLoop.COORD_SW_B_Y)
		)
	}

	@Test
	fun applyBlockNamesNamesTheVyhybnaBlocks() {
		val (_, station) = vyhybna()

		station.applyBlockNames()

		assertThat(station.allBlocks.map { it.block.name })
			.containsExactly("k1", "k2", "kB", "kA")
	}

	@Test
	fun buildsFromCervenyUjezd() {
		val station = Station.from(load(NetworkResources.CERVENY_UJEZD_XML))

		// Four signal-bounded station tracks (rows 30-33), four InOut lines, twelve switches.
		assertThat(station.platformTracks).hasSize(4)
		assertThat(station.entryExitBlocks).hasSize(4)
		assertThat(station.entryExitBlocks.map { it.signals.size }).containsExactly(1, 1, 1, 1)
		assertThat(station.switches).hasSize(12)
	}

	@Test
	fun buildsFromPrahaHlavniNadrazi() {
		val station = Station.from(load(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML))

		// Eight platform tracks plus the bypass station track; the signal-bounded block next to
		// the N-Bypass home signal is a throat block, not a station track.
		assertThat(station.platformTracks).hasSize(9)
		assertThat(station.entryExitBlocks).hasSize(11)
		assertThat(station.switches).hasSize(50)
	}

	@Test
	fun entryExitBlockWithoutHomeSignalHasNoSignals() {
		val ctx = load(NetworkResources.SWITCH_BETWEEN_SEMAPHORES_XML)
		val station = Station.from(ctx)

		// No block here is bounded by semaphores at both ends, so no platform track exists; and
		// the block at InOut C ends at the switch, not at a semaphore, so C has no home signal.
		assertThat(station.platformTracks).isEmpty()
		assertThat(station.entryExitBlocks.map { it.name }).containsExactly("kA", "kB", "kC")
		assertThat(station.entryExitBlocks.single { it.name == "kC" }.signals).isEmpty()
	}

	@Test
	fun cellOnlyNetworkYieldsAnEmptyStation() {
		val ctx = load(NetworkResources.LEGACY_NETWORK_NO_NAMES_XML)
		val station = Station.from(ctx)

		// The fixture has cells but no track blocks, so nothing joins the track graph.
		assertThat(station.allBlocks).isEmpty()
		assertThat(station.switches).isEmpty()
	}

	@Test
	fun shuntingLoopGuardRejectsASemaphoreFreeNetwork() {
		val ctx = load(NetworkResources.LINEAR_TRACK_XML)

		val exception = assertFailsWith<SimulationException> { ShuntingLoop(ctx, 1000) }

		assertThat(exception.message).isNotNull().contains("found 0 platform track(s)")
	}
}