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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.OrphanReservationSweeper
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndSummary
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
 * The run-end accounting of the registry examples (Issue #1304, Goal 6 SP6): every figure comes from
 * the metrics service alone, and for one run of each example it equals what the loop's own counters
 * say for the same run (per-run equality; the #895 ten-run totals are a measurement, not a test).
 *
 * The examples are built through the registry's private factories, as `Main` builds them, and run
 * once each at the 600 s horizon of the #895 re-baseline.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
@DisplayName("Run-end accounting of the registry examples comes from the metrics alone")
class RunEndAccountingExampleTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun build(
		factoryMethod: String,
		exampleName: String,
		endTime: String = END_TIME
	): DefaultSimulationContext =
		createExampleContext(get<ExampleRegistry>(), get(), factoryMethod, exampleName, endTime).tracked()

	private fun DefaultSimulationContext.summary(): RunEndSummary =
		getMetricsServices().getMetricsCollectionService().runEndSummary()

	/**
	 * The figures `DispatcherRunSummaries.railwayOutcomeFrom` reads from the loop, as the metrics
	 * count them, apart from the two whose moment differs (asserted by each test).
	 */
	private fun assertShuntingLoopCounters(
		s: RunEndSummary,
		loop: ShuntingLoop
	) {
		assertThat(s.trainsEntered, name = "trains entered").isEqualTo(loop.getTrainsEntered())
		assertThat(s.blockTransitions, name = "block transitions")
			.isEqualTo(loop.getAllBlockTransitions().values.sum())
		assertThat(s.failedReservations, name = "failed reservations").isEqualTo(loop.getFailedReservations())
		assertThat(s.failedReservationsByKind.values.sum(), name = "failed reservations by kind, summed")
			.isEqualTo(loop.getFailedReservations())
	}

	@Test
	@Tag("integration-test")
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	@DisplayName("shuntingLoop 600 s: the metrics equal the loop counters and the run completes naturally")
	fun shuntingLoopFiguresEqualTheLoopCounters() {
		val context = build("createShuntingLoopExample", ExampleNames.SHUNTING_LOOP)
		val loop = context.mainProcess as ShuntingLoop

		context.run()

		val s = context.summary()
		assertShuntingLoopCounters(s, loop)
		// Every train left well before the end time, so the moments agree here (see the cutoff test).
		assertThat(s.trainsExited, name = "trains exited").isEqualTo(loop.getTrainsExited())
		assertThat(s.maxConcurrentTrains, name = "max concurrent trains").isEqualTo(loop.getMaxConcurrentTrains())
		assertThat(s.trainsEntered, name = "the run admitted trains").isGreaterThan(0)
		val sweeper = checkNotNull(context.scope.getOrNull<OrphanReservationSweeper>()) { "sweeper not wired" }
		assertThat(s.staleReleaseCount, name = "stale releases").isEqualTo(sweeper.staleReleaseCount)
		assertThat(s.partialReleaseCount, name = "partial releases").isEqualTo(sweeper.partialReleaseCount)
		assertThat(s.capacityShownToDispatcher, name = "capacity shown")
			.isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
		assertThat(s.topologyCapacity, name = "topology capacity").isEqualTo(loop.topologyCapacity)
		assertThat(s.stall, name = "stall").isNull()
		assertThat(s.endTimeReached, name = "end time reached").isTrue()
		assertThat(s.classification, name = "classification").isEqualTo(RunEndClassification.NATURAL_COMPLETION)
	}

	@Test
	@Tag("integration-test")
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	@DisplayName("cervenyUjezd 600 s: the same figures are available and the run is a cutoff")
	fun cervenyUjezdIsACutoffWithTheLoopFigures() {
		val context = build("createCervenyUjezdExample", ExampleNames.CERVENY_UJEZD)
		val loop = context.mainProcess as ShuntingLoop

		context.run()

		val s = context.summary()
		assertShuntingLoopCounters(s, loop)
		// The metrics count an exit when the front reaches the InOut; the loop counts it once the
		// train's process has terminated, on its next tick. A train caught between the two at the
		// cutoff is exited for the metrics and not yet for the loop -- and no longer on the network.
		assertThat(s.trainsExited, name = "trains exited, at least the loop's").isGreaterThanOrEqualTo(loop.getTrainsExited())
		assertThat(s.maxConcurrentTrains, name = "max concurrent trains, at most the loop's")
			.isLessThanOrEqualTo(loop.getMaxConcurrentTrains())
		assertThat(s.unfinishedJourneys, name = "admitted trains still on the network").isGreaterThan(0)
		assertThat(s.capacityShownToDispatcher, name = "capacity shown (synchronous wiring)")
			.isEqualTo(loop.stationCapacity)
		assertThat(s.topologyCapacity, name = "topology capacity").isEqualTo(CERVENY_UJEZD_CAPACITY)
		assertThat(s.classification, name = "classification")
			.isEqualTo(RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS)
	}

	@Test
	@Tag("integration-test")
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	@DisplayName("multiTrainLoop 600 s: the same figures are available and equal the loop counters")
	fun multiTrainLoopFiguresEqualTheLoopCounters() {
		val context = build("createMultiTrainLoopExample", ExampleNames.MULTI_TRAIN_LOOP)
		val loop = context.mainProcess as MultiTrainLoop

		context.run()

		val s = context.summary()
		assertThat(s.trainsEntered, name = "trains entered").isEqualTo(loop.getTrainsEntered())
		assertThat(s.trainsExited, name = "trains exited").isEqualTo(loop.getTrainsExited())
		// The loop's old figure counts approved trains, waiters included; the metrics count the trains
		// on the network, so they can only be lower (traffic-simulation-expert ruling, 2026-10-31).
		assertThat(s.maxConcurrentTrains, name = "max concurrent trains").isLessThanOrEqualTo(loop.getMaxConcurrentTrains())
		assertThat(s.blockTransitions, name = "granted entry routes").isEqualTo(loop.getTrainsExited())
		assertThat(s.entryFailures, name = "entry failures").isEqualTo(loop.getEntryFailures().size)
		assertThat(s.capacityShownToDispatcher, name = "no dispatcher, no capacity shown").isNull()
		assertThat(s.classification, name = "classification").isEqualTo(RunEndClassification.NATURAL_COMPLETION)
	}

	@Test
	@DisplayName("cervenyUjezd GUI wiring records the dispatcher cap 2 against the topology capacity 4")
	fun cervenyUjezdGuiRecordsTheCapacityShown() {
		val registry = get<ExampleRegistry>()
		val factory = checkNotNull(registry.guiExamples[ExampleNames.CERVENY_UJEZD])
		val context =
			(factory(get(), arrayOf("example", ExampleNames.CERVENY_UJEZD, "60")) as DefaultSimulationContext).tracked()

		val s = context.summary()
		assertThat(s.capacityShownToDispatcher, name = "capacity shown")
			.isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
		assertThat(s.topologyCapacity, name = "topology capacity").isEqualTo(CERVENY_UJEZD_CAPACITY)
		assertThat(s.classification, name = "not run, not classified").isNull()
	}

	private companion object {
		const val END_TIME = "600"
		const val CERVENY_UJEZD_CAPACITY = 4
	}
}
