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
 * hand-written ShuntingLoop coordinate constants that the derivation replaced (frozen in
 * [Former]). The other tests show that the same derivation builds a [Station] from the larger
 * fixture networks and rejects networks `ShuntingLoop` cannot run.
 */
class StationTest : CommonKoinTestBase() {
	/**
	 * The hand-written vyhybna coordinate contract of ShuntingLoop that [Station] replaced, frozen
	 * here verbatim when the constants were deleted (Issue #1162), so the parity check stays.
	 */
	private object Former {
		val IN_A = Point(11, 8)
		val IN_B = Point(30, 8)
		val SW_A = Point(15, 8)
		val SW_B = Point(26, 8)
		val SEM_ZA = Point(14, 8)
		val SEM_DOA1 = Point(16, 8)
		val SEM_DOA2 = Point(17, 9)
		val SEM_DOB1 = Point(25, 8)
		val SEM_DOB2 = Point(24, 9)
		val SEM_ZB = Point(27, 8)
	}

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
			Former.SEM_DOA1,
			Former.SEM_DOB1
		)
		assertThat(k2.signals.map { ctx.locationOf(it) }).containsExactlyInAnyOrder(
			Former.SEM_DOA2,
			Former.SEM_DOB2
		)
		// The same graph edges the old coordinate lookup resolved.
		val graph = ctx.getGraph()
		assertThat(k1.block).isSameInstanceAs(
			graph[
				Former.SEM_DOA1,
				Former.SEM_DOB1
			]
		)
		assertThat(k2.block).isSameInstanceAs(
			graph[
				Former.SEM_DOA2,
				Former.SEM_DOB2
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
			Former.IN_B
		)
		assertThat(kB.signals.map { ctx.locationOf(it) }).containsExactly(
			Former.SEM_ZB
		)
		assertThat(ctx.locationOf(requireNotNull(kA.inOut))).isEqualTo(
			Former.IN_A
		)
		assertThat(kA.signals.map { ctx.locationOf(it) }).containsExactly(
			Former.SEM_ZA
		)
		val graph = ctx.getGraph()
		assertThat(kA.block).isSameInstanceAs(
			graph[
				Former.IN_A,
				Former.SEM_ZA
			]
		)
		assertThat(kB.block).isSameInstanceAs(
			graph[
				Former.IN_B,
				Former.SEM_ZB
			]
		)
	}

	@Test
	fun vyhybnaSwitchesMatchTheOldCoordinateContract() {
		val (ctx, station) = vyhybna()

		assertThat(station.switches.map { ctx.locationOf(it) }).containsExactlyInAnyOrder(
			Former.SW_A,
			Former.SW_B
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

		assertThat(exception.message).isNotNull().contains("no platform track: no block has a RailSemaphore at both ends")
	}
}
