/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.PlanningSnapshotLiveComparator
import cz.vutbr.fit.interlockSim.testutil.TrainSampleOracle
import cz.vutbr.fit.interlockSim.testutil.runSampled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.component.get

/**
 * Issue #1472 (Goal 9B SP4.6, #1467 spec §7): each train field of the published train sample
 * equals the live train at the same tick.
 *
 * [PlanningSnapshotLiveComparator] decorates the control-step listener: it is installed after
 * [wireSynchronousDispatcher], so under spec rule P3 it is the first code to run after the tick's
 * publication, where live state still equals the published snapshot. It reads the tick's sample from
 * [ShuntingLoop.latestDispatchLoopSnapshot] (the reader-facing record, rule P2) and compares (with the
 * switch and contention facets on the same ticks):
 * - the train list, by id and in order, with the loop's approved trains;
 * - every [TrainPositionReading] field with [TrainSampleOracle.liveSample] — velocity,
 *   acceleration, totalDistance, frontSectionName, length and state.
 *
 * Doubles are compared exactly: nothing advances simulated time between the capture and the
 * listener, so any difference is a sample taken from another instant. Mismatches are collected and
 * asserted after the run, because a throw inside the listener would end the run
 * (`MainProcessFailureRunEndTest`).
 */
class TrainSampleLiveEqualityTest : KoinTestBase() {
	private fun runAndCompare(
		xml: String,
		trains: Int,
		maxConcurrent: Int?,
		switchLegalPairsOnly: Boolean
	) {
		val ctx: DefaultSimulationContext = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		val loop =
			ShuntingLoop(
				ctx,
				END_TIME,
				maxConcurrentTrains = maxConcurrent,
				seed = SEED,
				maxTrains = trains,
				switchLegalPairsOnly = switchLegalPairsOnly
			)
		wireSynchronousDispatcher(ctx, loop)
		val comparator = PlanningSnapshotLiveComparator(ctx, loop)
		loop.controlStepListener = comparator.wrap(checkNotNull(loop.controlStepListener))
		ctx.setMainProcess(loop)
		runSampled(ctx) { }

		assertThat(comparator.mismatches, name = "planning snapshot vs live mismatches").isEmpty()
		assertThat(comparator.comparedTrainReadings, name = "compared train samples").isGreaterThan(0)
		assertThat(comparator.trainStatesSeen, name = "train states seen").contains(TrainMotionState.MOVING)
	}

	@Test
	fun cervenyUjezdTrainSampleEqualsTheLiveTrainsAtEveryTick() {
		runAndCompare(NetworkResources.CERVENY_UJEZD_XML, trains = 3, maxConcurrent = null, switchLegalPairsOnly = false)
	}

	@Test
	@Tag("integration-test")
	fun prahaTrainSampleEqualsTheLiveTrainsAtEveryTick() {
		runAndCompare(
			NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML,
			trains = 4,
			maxConcurrent = 4,
			switchLegalPairsOnly = true
		)
	}

	private companion object {
		const val END_TIME: Long = 600L
		const val SEED: Long = 1472L
	}
}
