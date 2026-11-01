/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.each
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.OpenContention
import cz.vutbr.fit.interlockSim.sim.RunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.TrainJourneyRecord
import cz.vutbr.fit.interlockSim.sim.onRunAccountingEvent
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContext
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * The per-train wait figures and the open contentions of the registry examples (Issue #1305, Goal 6
 * SP7), run once each at the 600 s horizon of the #895 re-baseline. Counter-based: every figure is
 * read from the metrics service or the contention query, never from a log.
 *
 * @since Issue #1305 (Goal 6 SP7)
 */
@DisplayName("Per-train wait figures of the registry examples")
class TrainWaitExampleTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun build(
		factoryMethod: String,
		exampleName: String
	): DefaultSimulationContext =
		createExampleContext(get<ExampleRegistry>(), get(), factoryMethod, exampleName, END_TIME).tracked()

	private fun DefaultSimulationContext.metrics(): MetricsCollectionService =
		getMetricsServices().getMetricsCollectionService()

	/** The open contentions as the contention query answers at the end time, the kernel's last instant. */
	private fun DefaultSimulationContext.openAtEndTime(): () -> List<OpenContention>? {
		var seen: List<OpenContention>? = null
		onRunAccountingEvent { event ->
			if (event is RunAccountingEvent.EndTimeReached) {
				seen = getRoutingServices().getPathReservationService().openContentions()
			}
		}
		return { seen }
	}

	/** Every journey carries its queue time; blocked time equals the contention query's, train by train. */
	private fun assertEveryJourneyHasTheWaitFigures(context: DefaultSimulationContext) {
		val records = context.metrics().journeys()
		val endTime = checkNotNull(context.metrics().runEndSummary().endTime) { "run did not end" }
		assertThat(records, name = "journey records").isNotEmpty()
		assertThat(records.map { it.queueSeconds }, name = "queue time of every journey").each { it.isNotNull() }
		val blocked = context.getRoutingServices().getPathReservationService().blockedSeconds(endTime)
		assertThat(records.filter { it.blockedSeconds > 0.0 }.associate { it.trainId to it.blockedSeconds })
			.isEqualTo(blocked)
		assertThat(records.sumOf { it.expiries }, name = "expiries in the records")
			.isEqualTo(context.metrics().runEndSummary().let { it.expiredHoldingCount + it.expiredTailCount })
	}

	@Test
	@Tag("integration-test")
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	@DisplayName("shuntingLoop 600 s (#895 control arm): no contention open at the end and the totals unchanged")
	fun shuntingLoopEndsWithNoOpenContention() {
		val context = build("createShuntingLoopExample", ExampleNames.SHUNTING_LOOP)
		val openAtEnd = context.openAtEndTime()

		context.run()

		assertThat(context.metrics().runEndSummary().endTimeReached, name = "end time reached").isTrue()
		assertThat(openAtEnd(), name = "open contentions at the end time").isNotNull().isEmpty()
		assertThat(context.metrics().latestOpenContentions(), name = "published open contentions").isEmpty()
		assertThat(context.metrics().latestSnapshot().openContentions, name = "open contentions sampled").isEqualTo(0)
		val s = context.metrics().runEndSummary()
		val loop = context.mainProcess as ShuntingLoop
		// The #895 control arm per run (SP6 measured all 10 runs identical): the figures stay as they were.
		assertThat(listOf(s.trainsEntered, s.trainsExited, s.maxConcurrentTrains, s.blockTransitions, s.conflicts))
			.isEqualTo(listOf(BASELINE_TRAINS, BASELINE_TRAINS, BASELINE_MAX_CONCURRENT, BASELINE_TRANSITIONS, 0))
		assertThat(s.failedReservations, name = "failed reservations").isEqualTo(loop.getFailedReservations())
		assertThat(s.unresolvedAtRunEnd, name = "unresolved at run end").isEqualTo(0)
		assertEveryJourneyHasTheWaitFigures(context)
		assertThat(context.metrics().journeys().size, name = "one journey per train").isEqualTo(BASELINE_TRAINS)
	}

	@Test
	@Tag("integration-test")
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	@DisplayName("cervenyUjezd 600 s: every journey carries its wait figures and trains really stood blocked")
	fun cervenyUjezdJourneysCarryTheWaitFigures() {
		val context = build("createCervenyUjezdExample", ExampleNames.CERVENY_UJEZD)

		context.run()

		assertEveryJourneyHasTheWaitFigures(context)
		val records: List<TrainJourneyRecord> = context.metrics().journeys()
		// Non-vacuous: the busy station queues and blocks trains.
		assertThat(records.sumOf { it.blockedSeconds }, name = "blocked seconds in all").isGreaterThan(0.0)
		assertThat(records.sumOf { it.queueSeconds ?: 0.0 }, name = "queued seconds in all").isGreaterThan(0.0)
		assertThat(
			context
				.metrics()
				.history()
				.all()
				.maxOf { it.queuedTrains },
			name = "peak queued trains"
		).isGreaterThan(0)
	}

	private companion object {
		const val END_TIME = "600"
		const val BASELINE_TRAINS = 15
		const val BASELINE_MAX_CONCURRENT = 2
		const val BASELINE_TRANSITIONS = 30
	}
}
