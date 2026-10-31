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
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
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
	fun nonPositiveLoopOverrideIsRejected() {
		val ctx = load(NetworkResources.VYHYBNA_XML)

		assertFailure { ShuntingLoop(ctx, endTime = 10L, maxConcurrentTrains = 0) }
			.isInstanceOf<IllegalArgumentException>()
		assertFailure { ShuntingLoop(ctx, endTime = 10L, maxConcurrentTrains = -1) }
			.isInstanceOf<IllegalArgumentException>()
			.hasMessage("maxConcurrentTrains must be positive, got: -1")
	}

	/**
	 * `maxTrains` has no separate validator: it is forwarded to [Generator]'s constructor, whose
	 * `require` surfaces through [ShuntingLoop]'s super-call — checked for real here (PR #1267
	 * review round, Copilot thread; `LoopProcess.actions()` runs `iteration()` before the cap).
	 */
	@Test
	fun nonPositiveGeneratorLimitIsRejected() {
		val ctx = load(NetworkResources.VYHYBNA_XML)

		assertFailure { ShuntingLoop(ctx, endTime = 10L, maxTrains = 0) }
			.isInstanceOf<IllegalArgumentException>()
			.hasMessage("maxTrains must be positive, got: 0")
		assertFailure { ShuntingLoop(ctx, endTime = 10L, maxTrains = -1) }
			.isInstanceOf<IllegalArgumentException>()
			.hasMessage("maxTrains must be positive, got: -1")
	}

	@Test
	fun vyhybnaBlockIdsAreTheirEndPoints() {
		val station = Station.from(load(NetworkResources.VYHYBNA_XML))

		assertThat(station.allBlocks.map { it.name to it.id }).containsExactly(
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
			val ids = Station.from(load(xml)).allBlocks.map { it.id }
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

		assertThat(loop.stationCapacity).isEqualTo(Station.from(ctx).topologyCapacity)
	}

	@Test
	fun shuntingLoopScenarioOverrideWins() {
		val ctx = load(NetworkResources.VYHYBNA_XML)

		val loop = ShuntingLoop(ctx, endTime = 10L, maxConcurrentTrains = 1)

		assertThat(loop.stationCapacity).isEqualTo(1)
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
	fun shuntingLoopAcceptsCervenyUjezdWithItsFourInOuts() {
		val ctx = load(NetworkResources.CERVENY_UJEZD_XML)

		// Two InOuts per side: the generator pairs opposite sides (SP3.1, Issue #1163).
		assertThat(ShuntingLoop.compatibilityIssues(Station.from(ctx))).isEmpty()
		assertThat(ShuntingLoop(ctx, endTime = 10L).stationCapacity).isEqualTo(4)
	}

	@Test
	fun shuntingLoopRejectsAStationWithInOutsOnOneSideOnly() {
		val issues = ShuntingLoop.compatibilityIssues(Station.from(load(NetworkResources.SINGLE_INOUT_XML)))

		assertThat(issues.any { "both sides" in it }).isEqualTo(true)
	}

	@Test
	fun shuntingLoopRejectsPrahaHlavniNadrazi() {
		val issues = ShuntingLoop.compatibilityIssues(Station.from(load(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML)))

		assertThat(issues.any { "no home signal" in it }).isEqualTo(true)
		assertThat(issues.any { "neither a home signal nor a platform-track end" in it }).isEqualTo(true)
	}

	@Test
	fun shuntingLoopRejectsAHomeSignalFacingItsInOut() {
		// zA ends the entry/exit block kA but faces the InOut A, so a train entering from A has
		// no home signal (signal direction, Issue #1272); the issue names zA instead of claiming
		// the block does not end at a RailSemaphore.
		val station = Station.from(load(NetworkResources.STATION_HOME_SIGNAL_FACING_AWAY_XML))

		assertThat(ShuntingLoop.compatibilityIssues(station)).containsExactly(
			"entry/exit block kA (11,8)-(14,8) has no home signal: its end RailSemaphore zA faces " +
				"the InOut, away from the incoming train",
			"1 RailSemaphore(s) are neither a home signal nor a platform-track end"
		)
	}

	@Test
	fun shuntingLoopNamesAnUnnamedHomeSignalFacingItsInOut() {
		// The same facing-away zA, but nameless in the XML: the loader names it by its grid
		// position (Issue #1273), so the issue prints that name instead of an empty one.
		val station = Station.from(load(NetworkResources.STATION_UNNAMED_HOME_SIGNAL_XML))

		assertThat(ShuntingLoop.compatibilityIssues(station)).containsExactly(
			"entry/exit block kA (11,8)-(14,8) has no home signal: its end RailSemaphore " +
				"S_14_8 faces the InOut, away from the incoming train",
			"1 RailSemaphore(s) are neither a home signal nor a platform-track end"
		)
	}

	@Test
	fun shuntingLoopCompatibilityNamesWhatALinearTrackLacks() {
		val issues = ShuntingLoop.compatibilityIssues(Station.from(load(NetworkResources.LINEAR_TRACK_XML)))

		assertThat(issues).isNotEmpty()
		assertThat(issues.any { "platform track" in it }).isEqualTo(true)
		assertThat(issues.any { "no home signal" in it }).isEqualTo(true)
		// No semaphore at all, so no direction to blame (Issue #1272): the reasons say so.
		assertThat(issues).contains("no platform track: no block has a RailSemaphore at both ends facing away from it")
		assertThat(issues.any { it.endsWith("has no home signal: it does not end at a RailSemaphore") }).isEqualTo(true)
	}
}
