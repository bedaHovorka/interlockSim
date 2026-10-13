/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Station capacity, unique block ids and the ShuntingLoop compatibility rule (Goal 1B SP2.2,
 * Issue #1162).
 *
 * Capacity comes from the topology: the number of signal-bounded station tracks, which is not the
 * platform count of the station description (Praha has 8 platforms and a bypass station track,
 * capacity 9). A per-scenario override wins. The dispatcher default
 * [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS] (2, from vyhybna) must not leak into other
 * stations.
 */
class StationCapacityTest : CommonKoinTestBase() {
	private fun load(xml: String): DefaultSimulationContext {
		val ctx = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		return ctx
	}

	@Test
	fun vyhybnaCapacityStillResolvesToTwo() {
		val station = Station.from(load(NetworkResources.VYHYBNA_XML))

		assertThat(station.topologyCapacity).isEqualTo(2)
		assertThat(station.capacity()).isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
	}

	@Test
	fun cervenyUjezdCapacityEqualsItsFourStationTracks() {
		val station = Station.from(load(NetworkResources.CERVENY_UJEZD_XML))

		assertThat(station.topologyCapacity).isEqualTo(4)
		assertThat(station.capacity()).isEqualTo(4)
	}

	@Test
	fun prahaCapacityCountsTheBypassStationTrackNotOnlyThePlatforms() {
		val station = Station.from(load(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML))

		assertThat(station.topologyCapacity).isEqualTo(9)
		assertThat(station.capacity()).isEqualTo(9)
	}

	@Test
	fun scenarioOverrideWinsOverTheTopologyValue() {
		val station = Station.from(load(NetworkResources.CERVENY_UJEZD_XML))

		assertThat(station.capacity(scenarioOverride = 3)).isEqualTo(3)
		assertThat(station.capacity(scenarioOverride = 6)).isEqualTo(6)
	}

	@Test
	fun nonPositiveScenarioOverrideIsRejected() {
		val station = Station.from(load(NetworkResources.VYHYBNA_XML))

		assertFailure { station.capacity(scenarioOverride = 0) }.isInstanceOf<IllegalArgumentException>()
	}

	@Test
	fun vyhybnaBlockIdsAreTheirEndPoints() {
		val station = Station.from(load(NetworkResources.VYHYBNA_XML))

		assertThat(station.blocks.map { it.name to it.id }).containsExactly(
			"k1" to "(16,8)-(25,8)",
			"k2" to "(17,9)-(24,9)",
			"kB" to "(27,8)-(30,8)",
			"kA" to "(11,8)-(14,8)"
		)
	}

	@Test
	fun blockIdsAreUniqueOnEveryFixtureStation() {
		for (xml in listOf(
			NetworkResources.VYHYBNA_XML,
			NetworkResources.CERVENY_UJEZD_XML,
			NetworkResources.PRAHA_HLAVNI_NADRAZI_XML,
			NetworkResources.LINEAR_TRACK_XML
		)) {
			val ids = Station.from(load(xml)).blocks.map { it.id }
			assertThat(ids.toSet().size).isEqualTo(ids.size)
		}
	}

	@Test
	fun oneBlockJoiningTwoInOutsIsOneEntryExitBlockNamingBoth() {
		val station = Station.from(load(NetworkResources.LINEAR_TRACK_XML))

		assertThat(station.entryExitBlocks.map { it.name }).containsExactly("kA/B")
		assertThat(
			station.entryExitBlocks
				.single()
				.inOuts
				.map { it.name }
		).containsExactly("A", "B")
	}

	@Test
	fun shuntingLoopCapacityOnVyhybnaComesFromTheStation() {
		val ctx = load(NetworkResources.VYHYBNA_XML)

		val loop = ShuntingLoop(ctx, endTime = 10L)

		assertThat(loop.getStationCapacity()).isEqualTo(Station.from(ctx).topologyCapacity)
	}

	@Test
	fun shuntingLoopScenarioOverrideWins() {
		val ctx = load(NetworkResources.VYHYBNA_XML)

		val loop = ShuntingLoop(ctx, endTime = 10L, maxConcurrentTrains = 1)

		assertThat(loop.getStationCapacity()).isEqualTo(1)
	}

	@Test
	fun synchronousDispatcherDefaultsToTheLoopCapacityNotTheVyhybnaConstant() {
		val ctx = load(NetworkResources.VYHYBNA_XML)
		val loop = ShuntingLoop(ctx, endTime = 10L, maxConcurrentTrains = 1)

		val dispatcher = wireSynchronousDispatcher(ctx, loop)

		assertThat(dispatcher.maxConcurrentTrains).isEqualTo(1)
	}

	@Test
	fun shuntingLoopCompatibilityAcceptsVyhybna() {
		assertThat(ShuntingLoop.compatibilityIssues(Station.from(load(NetworkResources.VYHYBNA_XML)))).isEmpty()
	}

	@Test
	fun shuntingLoopRejectsCervenyUjezdUntilItHasItsOwnScenario() {
		val ctx = load(NetworkResources.CERVENY_UJEZD_XML)
		val issues = ShuntingLoop.compatibilityIssues(Station.from(ctx))

		// The train generator pairs random InOuts, so four InOuts send trains between same-side
		// exits that ShuntingLoop cannot run (SP3, Issue #1150, owns that scenario).
		assertThat(issues.any { "exactly 2 InOuts" in it }).isEqualTo(true)
		assertFailure { ShuntingLoop(ctx, endTime = 10L) }.isInstanceOf<SimulationException>()
	}

	@Test
	fun shuntingLoopRejectsPrahaHlavniNadrazi() {
		val issues = ShuntingLoop.compatibilityIssues(Station.from(load(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML)))

		assertThat(issues.any { "exactly 2 InOuts" in it }).isEqualTo(true)
		assertThat(issues.any { "no home signal" in it }).isEqualTo(true)
		assertThat(issues.any { "neither a home signal nor a platform-track end" in it }).isEqualTo(true)
	}

	@Test
	fun shuntingLoopCompatibilityNamesWhatALinearTrackLacks() {
		val issues = ShuntingLoop.compatibilityIssues(Station.from(load(NetworkResources.LINEAR_TRACK_XML)))

		assertThat(issues).isNotEmpty()
		assertThat(issues.any { "platform track" in it }).isEqualTo(true)
		assertThat(issues.any { "no home signal" in it }).isEqualTo(true)
	}
}
