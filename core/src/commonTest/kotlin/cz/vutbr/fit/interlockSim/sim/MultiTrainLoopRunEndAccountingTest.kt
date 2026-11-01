/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.sim.metrics.DefaultMetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsConfig
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndSummary
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import kotlin.test.Test

/**
 * The run-end accounting of `MultiTrainLoop` runs (Issue #1304, Goal 6 SP6): the metrics service
 * counts entered and exited trains, route grants, refused reservations and entry failures from
 * events, and the totals equal the loop's own counters for the same run.
 *
 * Reuses the refusing-fake shape of `MultiTrainLoopBoundedEntryTest` (every `reservePath` answers
 * one fixed result) and its scripted releases ([ReleasingMultiTrainLoop]).
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
class MultiTrainLoopRunEndAccountingTest : CommonKoinTestBase() {
	private companion object {
		const val MAX_ATTEMPTS: Int = 5
		const val END_TIME: Long = 60L
		const val LONG_END_TIME: Long = 300L

		/** Too short for a train to cross the line: both are still on it when the run ends. */
		const val CUTOFF_END_TIME: Long = 5L
		const val RELEASE_PERIOD: Double = 5.0
		const val TRAIN_LENGTH: Double = 20.0
	}

	/** Answers [refusal] to every `reservePath` and counts the calls. */
	private class RefusingReservationService(
		delegate: PathReservationService,
		private val refusal: PathReservationService.ReservationResult
	) : PathReservationService by delegate {
		var calls: Int = 0

		override fun reservePath(
			trainId: String,
			start: DynamicPathSeparator,
			target: DynamicPathSeparator,
			maxDepth: Int
		): PathReservationService.ReservationResult {
			calls++
			return refusal
		}
	}

	private fun spec(
		inTime: Double,
		inName: String = "A",
		outName: String = "B"
	) = MultiTrainLoop.TrainSpec(inName = inName, outName = outName, inTime = inTime, length = TRAIN_LENGTH)

	private fun context(): DefaultSimulationContext =
		TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked().also { it.getInOuts() }

	private fun DefaultSimulationContext.summary(): RunEndSummary =
		getMetricsServices().getMetricsCollectionService().runEndSummary()

	@Test
	fun refusalsAndEntryFailuresAreCountedByKindAndEqualTheLoopTotals() {
		val ctx = context()
		val refusing =
			RefusingReservationService(
				ctx.getRoutingServices().getPathReservationService(),
				PathReservationService.ReservationResult.AllPathsBlocked(attemptedPaths = 1)
			)
		val releases = ReleasingMultiTrainLoop.every(RELEASE_PERIOD, END_TIME)
		val loop =
			ReleasingMultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs = listOf(spec(inTime = 0.0), spec(inTime = 1.0, inName = "NoSuchInOut")),
				pathReservationService = refusing,
				maxEntryAttempts = MAX_ATTEMPTS,
				releaseTimes = releases
			)
		ctx.setMainProcess(loop)

		ctx.run()

		val s = ctx.summary()
		assertThat(s.failedReservations, name = "refused reservations = reservePath calls").isEqualTo(refusing.calls)
		assertThat(s.failedReservationsByKind, name = "refusals by kind")
			.isEqualTo(mapOf(FailedReservationKind.ALL_PATHS_BLOCKED to MAX_ATTEMPTS))
		assertThat(s.entryFailures, name = "entry failures = the loop's").isEqualTo(loop.getEntryFailures().size)
		assertThat(s.entryFailuresByKind, name = "entry failures by kind").isEqualTo(
			mapOf(
				MultiTrainLoop.EntryFailureKind.ATTEMPTS_EXHAUSTED to 1,
				MultiTrainLoop.EntryFailureKind.UNKNOWN_IN_OUT to 1
			)
		)
		assertThat(s.trainsEntered, name = "trains entered = the loop's").isEqualTo(loop.getTrainsEntered())
		assertThat(s.blockTransitions, name = "route grants").isZero()
		// ReleasingMultiTrainLoop announces its scripted releases as whole-holding expiries.
		assertThat(s.expiredHoldingCount, name = "expiries seen").isEqualTo(releases.size)
		// No train was ever admitted: the refused train was only queued, so it is no unfinished journey.
		assertThat(s.classification, name = "classification").isEqualTo(RunEndClassification.NATURAL_COMPLETION)
	}

	@Test
	fun permanentRefusalsHaveTheirKind() {
		val ctx = context()
		val refusing =
			RefusingReservationService(
				ctx.getRoutingServices().getPathReservationService(),
				PathReservationService.ReservationResult.NoPathExists
			)
		val loop = MultiTrainLoop(ctx, endTime = END_TIME, trainSpecs = listOf(spec(0.0)), pathReservationService = refusing)
		ctx.setMainProcess(loop)

		ctx.run()

		val s = ctx.summary()
		assertThat(s.failedReservationsByKind).isEqualTo(mapOf(FailedReservationKind.NO_ROUTE to 1))
		assertThat(s.entryFailuresByKind).isEqualTo(mapOf(MultiTrainLoop.EntryFailureKind.NO_ROUTE to 1))
	}

	@Test
	fun aRunWhoseTrainsAllArriveIsANaturalCompletionWithTheLoopFigures() {
		val ctx = context()
		val loop =
			MultiTrainLoop(ctx, endTime = LONG_END_TIME, trainSpecs = listOf(spec(inTime = 0.0), spec(inTime = 1.0)))
		ctx.setMainProcess(loop)

		ctx.run()

		val s = ctx.summary()
		assertThat(s.trainsEntered, name = "trains entered = the loop's").isEqualTo(loop.getTrainsEntered())
		assertThat(s.trainsExited, name = "trains exited = the loop's").isEqualTo(loop.getTrainsExited())
		assertThat(s.trainsExited, name = "both trains arrived").isEqualTo(2)
		assertThat(s.wrongExits, name = "wrong exits").isZero()
		assertThat(s.blockTransitions, name = "one granted entry route per started train").isEqualTo(2)
		assertThat(s.failedReservations, name = "refusals = waits of the second train")
			.isEqualTo(s.failedReservationsByKind.values.sum())
		assertThat(s.stall, name = "stall").isNull()
		assertThat(s.endTimeReached, name = "end time reached").isTrue()
		assertThat(s.classification, name = "classification").isEqualTo(RunEndClassification.NATURAL_COMPLETION)
	}

	@Test
	fun runEndedReachesItsSubscribersOnceEvenPastAFailingRawListener() {
		val ctx = context()
		// A raw listener that fails on the run-end delivery, registered before the subscriber.
		ctx.onSimulationEvent { event ->
			check((event as? SimulationEvent.Custom)?.payload !is RunEnded) {
				"raw listener failing on purpose"
			}
		}
		val seen = mutableListOf<RunEnded>()
		ctx.onRunEnded { seen += it }
		ctx.setMainProcess(MultiTrainLoop(ctx, endTime = END_TIME))

		ctx.run()

		assertThat(seen.size, name = "RunEnded deliveries").isEqualTo(1)
		assertThat(seen.single().time, name = "run-end time").isEqualTo(ctx.lastRunEndTime)
		assertThat(seen.single().endTimeReached, name = "end time reached").isTrue()
	}

	/**
	 * The run-end figures are the CORE metric group (Issue #1304 on top of #674): a service built with
	 * [MetricsConfig.coreOnly] -- no journey records, no history -- counts exactly what the default
	 * (all groups) service counts, at a cutoff and at a natural completion.
	 */
	@Test
	fun runEndFiguresStayOnWithOnlyTheCoreGroup() {
		val horizons =
			listOf(
				CUTOFF_END_TIME to RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS,
				LONG_END_TIME to RunEndClassification.NATURAL_COMPLETION
			)
		for ((endTime, expected) in horizons) {
			val ctx = context()
			val coreOnly = DefaultMetricsCollectionService(ctx, config = MetricsConfig.coreOnly())
			ctx.setMainProcess(
				MultiTrainLoop(ctx, endTime = endTime, trainSpecs = listOf(spec(inTime = 0.0), spec(inTime = 1.0)))
			)

			ctx.run()

			val core = coreOnly.runEndSummary()
			// The performance row is its own group (Issue #1306): absent with only CORE on.
			assertThat(core.performance, name = "no performance row with PERFORMANCE off at $endTime s").isNull()
			assertThat(core, name = "core-only summary = all-groups summary at $endTime s")
				.isEqualTo(ctx.summary().copy(performance = null))
			assertThat(core.classification, name = "classification at $endTime s").isEqualTo(expected)
			assertThat(core.trainsEntered, name = "trains entered at $endTime s").isEqualTo(2)
			assertThat(coreOnly.journeys(), name = "no journey records with JOURNEYS off").isEmpty()
			assertThat(coreOnly.history().size, name = "no history with HISTORY off").isZero()
		}
	}
}
