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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.single
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The Goal 6 SP7 surface of [DefaultMetricsCollectionService] (Issue #1305): the per-train wait
 * figures in [MetricsCollectionService.journeys], the capacity pressure in the published snapshot
 * and [MetricsCollectionService.latestOpenContentions], all behind [MetricGroup.TRAIN_WAIT].
 */
class DefaultMetricsCollectionServiceTrainWaitTest {
	private var context: DefaultSimulationContext? = null

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDown() {
		context?.close()
		context = null
		stopKoin()
	}

	private fun realBlock(): DynamicTrackBlock {
		val ctx =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.LINEAR_TRACK_XML,
				DefaultSimulationProcessFactory()
			)
		context = ctx
		return ctx.getGraph().values().first()
	}

	private fun DefaultMetricsCollectionService.journey(vararg events: TrainJourneyEvent) {
		events.forEach {
			handleJourneyEvent(it)
			trainWait?.onJourney(it)
		}
	}

	@Test
	fun `with TRAIN_WAIT on the journey record carries the queue time`() {
		val service = DefaultMetricsCollectionService()

		service.journey(TrainJourneyEvent.Queued("T1", "A", "B", 2.0), TrainJourneyEvent.Admitted("T1", 5.0))

		assertThat(service.journeys()).single().transform { it.queueSeconds }.isEqualTo(3.0)
	}

	@Test
	fun `a published snapshot carries the queued trains into the history`() {
		val service = DefaultMetricsCollectionService()
		service.journey(TrainJourneyEvent.Queued("T1", "A", "B", 0.0), TrainJourneyEvent.Queued("T2", "A", "B", 1.0))

		service.handleBlockEvent(BlockEvent.BlockReserved(realBlock(), "T0", 2.0))

		assertThat(service.latestSnapshot().queuedTrains).isEqualTo(2)
		assertThat(
			service
				.history()
				.all()
				.last()
				.queuedTrains
		).isEqualTo(2)
		assertThat(service.getSnapshot().queuedTrains).isEqualTo(2)
	}

	@Test
	fun `with TRAIN_WAIT off nothing of SP7 is recorded`() {
		val service = DefaultMetricsCollectionService(config = MetricsConfig(setOf(MetricGroup.JOURNEYS)))

		assertThat(service.trainWait).isNull()
		service.journey(TrainJourneyEvent.Queued("T1", "A", "B", 2.0), TrainJourneyEvent.Admitted("T1", 5.0))
		service.handleBlockEvent(BlockEvent.BlockReserved(realBlock(), "T1", 6.0))

		val record = service.journeys().single()
		assertThat(record.queueSeconds).isNull()
		assertThat(record.admissionTime).isNotNull()
		assertThat(service.latestSnapshot().queuedTrains).isEqualTo(0)
		assertThat(service.latestOpenContentions()).isEmpty()
	}
}
