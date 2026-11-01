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
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.metrics.RunRecord
import cz.vutbr.fit.interlockSim.sim.metrics.RunRecordSource
import cz.vutbr.fit.interlockSim.sim.metrics.TrainJourneyRecord
import cz.vutbr.fit.interlockSim.sim.metrics.toRunRecord
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import kotlin.test.Test

/**
 * The run record of a `MultiTrainLoop` run (Issue #676, Goal 6 SP5), read through the one
 * [RunRecordSource] contract of `:core`, and checked against what SP2's history and journey records
 * say about the same run.
 *
 * @since Issue #676 (Goal 6 SP5)
 */
class MultiTrainLoopRunRecordTest : CommonKoinTestBase() {
	private companion object {
		/** Too short for a train to cross the line: both are still on it when the run ends. */
		const val CUTOFF_END_TIME: Long = 5L
		const val LONG_END_TIME: Long = 300L
		const val TRAIN_LENGTH: Double = 20.0
	}

	private fun spec(inTime: Double) =
		MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = inTime, length = TRAIN_LENGTH)

	private fun context(): DefaultSimulationContext =
		TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked().also { it.getInOuts() }

	private fun DefaultSimulationContext.recordSource(): RunRecordSource = getMetricsServices().getRunRecordSource()

	/** Peak of trains admitted and not yet exited, from the journey records alone. */
	private fun peakAdmitted(journeys: List<TrainJourneyRecord>): Int {
		// An exit at the same instant as an admission is counted first.
		val steps =
			journeys.mapNotNull { j -> j.admissionTime?.let { it to 1 } } +
				journeys.mapNotNull { j -> j.exitTime?.let { it to -1 } }
		var onNetwork = 0
		var peak = 0
		for ((_, delta) in steps.sortedWith(compareBy({ it.first }, { it.second }))) {
			onNetwork += delta
			if (onNetwork > peak) peak = onNetwork
		}
		return peak
	}

	@Test
	fun noRecordBeforeTheRunHasEnded() {
		val ctx = context()
		ctx.setMainProcess(MultiTrainLoop(ctx, endTime = LONG_END_TIME, trainSpecs = listOf(spec(inTime = 0.0))))

		assertThat(ctx.recordSource().runRecord(), name = "record before run()").isNull()
	}

	@Test
	fun theRecordEqualsWhatTheHistoryAndJourneyRecordsSayForTheSameRun() {
		val horizons =
			listOf(
				CUTOFF_END_TIME to RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS,
				LONG_END_TIME to RunEndClassification.NATURAL_COMPLETION
			)
		for ((endTime, expected) in horizons) {
			val ctx = context()
			ctx.setMainProcess(
				MultiTrainLoop(ctx, endTime = endTime, trainSpecs = listOf(spec(inTime = 0.0), spec(inTime = 1.0)))
			)

			ctx.run()

			val record: RunRecord? = ctx.recordSource().runRecord()
			assertThat(record, name = "record at $endTime s").isNotNull()
			checkNotNull(record)
			val metrics = ctx.getMetricsServices().getMetricsCollectionService()
			val lastSnapshot = metrics.history().all().last()
			val journeys = metrics.journeys()
			val at = "at $endTime s"
			assertThat(record.journeysCompleted, name = "journeys = history $at").isEqualTo(lastSnapshot.completedTrains)
			assertThat(record.conflicts, name = "conflicts = history $at").isEqualTo(lastSnapshot.conflictCount)
			assertThat(record.unresolvedAtRunEnd, name = "unresolved = history $at")
				.isEqualTo(lastSnapshot.unresolvedAtRunEndCount)
			assertThat(record.trainsEntered, name = "entered = journeys queued $at")
				.isEqualTo(journeys.count { it.entryTime != null })
			assertThat(record.trainsExited, name = "exited = journeys exited $at")
				.isEqualTo(journeys.count { it.exitTime != null })
			assertThat(record.wrongExits, name = "wrong exits = journeys $at").isEqualTo(journeys.count { it.wrongExit })
			assertThat(record.unfinishedJourneys, name = "unfinished = journeys admitted, not exited $at")
				.isEqualTo(journeys.count { it.admissionTime != null && it.exitTime == null })
			assertThat(record.maxConcurrentTrains, name = "peak admitted = journeys $at").isEqualTo(peakAdmitted(journeys))
			assertThat(record.endTime, name = "end time $at").isEqualTo(ctx.lastRunEndTime)
			assertThat(record.classification, name = "classification $at").isEqualTo(expected)
			assertThat(record, name = "record = run-end summary $at")
				.isEqualTo(checkNotNull(metrics.runEndSummary().toRunRecord()))
		}
	}
}
