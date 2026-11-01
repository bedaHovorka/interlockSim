/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isZero
import assertk.assertions.single
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import dev.mokkery.MockMode
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import kotlin.test.Test

/**
 * Unit tests for the Goal 6 SP2 surface of [DefaultMetricsCollectionService]: the published
 * [MetricsCollectionService.latestSnapshot], the [MetricsCollectionService.history] buffer and
 * the [MetricsCollectionService.journeys] records. Kept apart from
 * [DefaultMetricsCollectionServiceTest], which covers the SP1 KPI derivation.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
class DefaultMetricsCollectionServiceHistoryTest : CommonKoinTestBase() {
	private fun realBlock(): DynamicTrackBlock {
		val context =
			CommonTestFixtures
				.parseSimulationContext(
					NetworkResources.LINEAR_TRACK_XML,
					DefaultSimulationProcessFactory()
				).tracked()
		return context.getGraph().values().first()
	}

	@Test
	fun `before any event the latest snapshot is the all-zero snapshot`() {
		val service = DefaultMetricsCollectionService()

		val latest = service.latestSnapshot()
		assertThat(latest.time).isZero()
		assertThat(latest.completedTrains).isZero()
		assertThat(latest.totalBlocks).isZero()
		assertThat(service.history().size).isZero()
	}

	@Test
	fun `every handled block event publishes a latest snapshot equal to the current one`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
		assertThat(service.latestSnapshot()).isEqualTo(service.getSnapshot())

		service.handleBlockEvent(BlockEvent.ReservationConflictDetected(block, "T2", "T1", time = 2.0))
		assertThat(service.latestSnapshot().conflictCount).isEqualTo(1)
		assertThat(service.latestSnapshot().time).isEqualTo(2.0)
	}

	@Test
	fun `listeners receive the very snapshot that was published`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()
		val received = mutableListOf<MetricsSnapshot>()
		service.onSnapshot { received += it }

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))

		assertThat(received.single()).isSameInstanceAs(service.latestSnapshot())
	}

	@Test
	fun `every published snapshot is appended to the history in time order`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
		service.handleBlockEvent(BlockEvent.ReservationConflictDetected(block, "T2", "T1", time = 2.0))
		service.handleBlockEvent(BlockEvent.ReservationConflictDetected(block, "T3", "T1", time = 3.0))

		assertThat(service.history().all().map { it.time }).containsExactly(1.0, 2.0, 3.0)
		assertThat(service.history().between(2.0, 3.0).map { it.conflictCount }).containsExactly(1, 2)
		assertThat(service.history().all().last()).isSameInstanceAs(service.latestSnapshot())
	}

	@Test
	fun `the configured history bounds what the service keeps`() {
		val service = DefaultMetricsCollectionService(history = MetricsHistory(maxEntries = 2))
		val block = realBlock()

		listOf(1.0, 2.0, 3.0).forEach { t ->
			service.handleBlockEvent(BlockEvent.ReservationConflictDetected(block, "T2", "T1", time = t))
		}

		assertThat(service.history().all().map { it.time }).containsExactly(2.0, 3.0)
	}

	@Test
	fun `with HISTORY disabled nothing is appended but the latest snapshot and listeners still work`() {
		val service = DefaultMetricsCollectionService(config = MetricsConfig(setOf(MetricGroup.JOURNEYS)))
		val block = realBlock()
		val received = mutableListOf<MetricsSnapshot>()
		service.onSnapshot { received += it }

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
		service.handleBlockEvent(BlockEvent.ReservationConflictDetected(block, "T2", "T1", time = 2.0))

		assertThat(service.history().size).isZero()
		assertThat(received.size).isEqualTo(2)
		assertThat(service.latestSnapshot().conflictCount).isEqualTo(1)
		assertThat(service.getSnapshot().conflictCount).isEqualTo(1)
	}

	@Test
	fun `with JOURNEYS disabled journey events add no records`() {
		val service = DefaultMetricsCollectionService(config = MetricsConfig(setOf(MetricGroup.HISTORY)))

		service.handleJourneyEvent(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))
		service.handleJourneyEvent(TrainJourneyEvent.Exited("T1", "B", 40.0))

		assertThat(service.journeys()).isEmpty()
	}

	@Test
	fun `enabling JOURNEYS adds exactly one simulation-event listener`() {
		val disabledEnv = mock<SimulationEnvironment>(MockMode.autofill)
		val enabledEnv = mock<SimulationEnvironment>(MockMode.autofill)
		// Both sides collect PERFORMANCE too (it subscribes by itself, Issue #1306): only JOURNEYS differs.
		val withoutJourneys = MetricsConfig(setOf(MetricGroup.HISTORY, MetricGroup.PERFORMANCE))
		val withJourneys = MetricsConfig(setOf(MetricGroup.HISTORY, MetricGroup.PERFORMANCE, MetricGroup.JOURNEYS))

		DefaultMetricsCollectionService(env = disabledEnv, config = withoutJourneys)
		DefaultMetricsCollectionService(env = enabledEnv, config = withJourneys)

		// The always-on run-end accounting (CORE) subscribes by itself, so compare the two runs
		// instead of expecting zero: only the journey recorder's own listener may differ.
		val listenersWithoutJourneys = countSimulationEventListeners(disabledEnv)
		assertThat(countSimulationEventListeners(enabledEnv)).isEqualTo(listenersWithoutJourneys + 1)
	}

	private fun countSimulationEventListeners(env: SimulationEnvironment): Int {
		var count = 0
		repeat(MAX_LISTENERS) { n ->
			val registered =
				runCatching { verify(exactly(n + 1)) { env.onSimulationEvent(any()) } }.isSuccess
			if (registered) count = n + 1
		}
		return count
	}

	@Test
	fun `core only collects neither history nor journeys`() {
		val service = DefaultMetricsCollectionService(config = MetricsConfig.coreOnly())
		val block = realBlock()

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
		service.handleJourneyEvent(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))

		assertThat(service.history().size).isZero()
		assertThat(service.journeys()).isEmpty()
	}

	@Test
	fun `the default config collects history and journeys`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
		service.handleJourneyEvent(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))

		assertThat(service.history().size).isEqualTo(1)
		assertThat(service.journeys().size).isEqualTo(1)
	}

	@Test
	fun `journey events become journey records`() {
		val service = DefaultMetricsCollectionService()

		service.handleJourneyEvent(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))
		service.handleJourneyEvent(TrainJourneyEvent.Admitted("T1", 2.0))
		service.handleJourneyEvent(TrainJourneyEvent.Exited("T1", "A", 40.0))

		val record = service.journeys().single()
		assertThat(record.entryInOut).isEqualTo("A")
		assertThat(record.exitTime).isEqualTo(40.0)
		assertThat(record.wrongExit).isEqualTo(true)
	}

	@Test
	fun `journey events do not publish a snapshot`() {
		val service = DefaultMetricsCollectionService()

		service.handleJourneyEvent(TrainJourneyEvent.Queued("T1", "A", "B", 1.0))

		assertThat(service.history().all()).isEmpty()
	}

	private companion object {
		const val MAX_LISTENERS = 16
	}
}
