/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit test for Issues #1030, #1028: the published front identity matches the live getters.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.frontMismatches
import cz.vutbr.fit.interlockSim.testutil.multiTrainSpecs
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.runSampled
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit test for [Train.frontIdentity] (Issues #1030, #1028).
 *
 * The identity is published at the end of every discrete front mutation in `Train.Site.actions()`
 * — the entry into the network, the `next` lookup, `onNext = true`, and the crossing block. The
 * frames sampled here (every train report and every block event, on the simulation thread) fall
 * between those mutations, so at each of them the published identity must equal what the live
 * getters report right now. A missing publication leaves a stale identity behind, which the next
 * frame after the unpublished mutation catches.
 *
 * Built on the Issue #788 scenarios of `TrainFrontBoundaryStateTest`: the 4-block linear arrival
 * run (reaches the destination boundary state) and the incremental-reservation shunting run (every
 * separator of `vyhybna.xml`, including waits in front of a STOP signal).
 *
 * @since Issues #1030, #1028
 */
@DisplayName("Train.frontIdentity matches the live front getters (Issues #1030, #1028)")
class TrainFrontIdentityTest : KoinTestBase() {
	private companion object {
		const val TRAIN_LENGTH: Double = 40.0
		const val MULTI_TRAIN_END_TIME: Long = 600L
		const val SHUNTING_END_TIME: Long = 700L
	}

	private val mismatches = mutableListOf<String>()
	private var enteredFrames = 0
	private var onNextFrames = 0
	private var boundaryFrames = 0

	private fun sample(trains: List<Train>) {
		for (train in trains) {
			// Read the identity first, then the live values — the simulation thread is the only
			// writer and this callback runs on it, so nothing changes in between.
			val identity = train.frontIdentity
			val integrated = train.frontIntegratedPosition
			if (identity.entrySeparator != null) enteredFrames++
			if (identity.onNext) onNextFrames++
			if (!identity.onNext && identity.section != null) boundaryFrames++
			val problems = identity.frontMismatches(train)
			if (problems.isNotEmpty()) {
				mismatches.add(
					"train #${train.trainNumber}: stale $problems — identity=(entry=" +
						"${separatorLabel(identity.entrySeparator)}, onNext=${identity.onNext}, " +
						"previousBlocks=${identity.previousBlocksLength}), live=(entry=" +
						"${separatorLabel(train.trainEntrySeparator)}, frontPosition=${train.frontPosition}, " +
						"totalDistance=${train.totalDistance}, integrated=$integrated)"
				)
			}
		}
	}

	private fun runAndSample(
		ctx: DefaultSimulationContext,
		trains: () -> List<Train>
	) {
		runSampled(ctx, setOf(ReportType.TRAIN_EVENTS, ReportType.TRAIN_CONTINUOUS)) { sample(trains()) }
		mismatches.take(10).forEach { println("FRONT_IDENTITY_MISMATCH: $it") }
		println(
			"WITNESS: mismatches=${mismatches.size} enteredFrames=$enteredFrames " +
				"onNextFrames=$onNextFrames boundaryFrames=$boundaryFrames"
		)
	}

	private fun assertIdentityMatchedLiveGetters() {
		assertThat(
			mismatches.isEmpty(),
			name = "published identity equals the live getters at every frame (mismatches: ${mismatches.take(3)})"
		).isTrue()
		assertThat(enteredFrames, name = "frames with an entered front").isGreaterThan(0)
		assertThat(onNextFrames, name = "frames with the front entering the next section").isGreaterThan(0)
		assertThat(boundaryFrames, name = "frames in the Issue #788 boundary state").isGreaterThan(0)
	}

	@Test
	@DisplayName("a train that has not entered the network publishes NOT_ENTERED")
	fun `identity before entry is NOT_ENTERED`() {
		val ctx = TestFixtures.newShuntingSimulationContext(initializeDynamicMapping = true).tracked()
		val inOuts = ctx.getInOuts().toList()
		val train = Train(ctx, Timetable(inOuts.first(), inOuts.last(), Time(0.0), Time(10.0), TRAIN_LENGTH))
		assertThat(train.frontIdentity).isSameInstanceAs(TrainFrontIdentity.NOT_ENTERED)
		assertThat(train.frontIdentity.totalDistance(train.frontIntegratedPosition)).isEqualTo(0.0)
	}

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	@DisplayName("identity matches the live getters through an arrival run")
	fun `identity matches the live getters through an arrival run`() {
		// 4-block linear topology: A -> Sem1 -> Sem2 -> Sem3 -> B (4 x 100 m).
		val ctx = TestTopologies.linearPathWithSemaphoreSequenceSimulation(semaphoreCount = 3).tracked()
		val loop =
			MultiTrainLoop(
				context = ctx,
				endTime = MULTI_TRAIN_END_TIME,
				trainSpecs = multiTrainSpecs(count = 2, interval = 2.0, length = TRAIN_LENGTH)
			)
		ctx.setMainProcess(loop)

		runAndSample(ctx) { loop.getApprovedTrains() }

		assertIdentityMatchedLiveGetters()
	}

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	@DisplayName("identity matches the live getters through an incremental-reservation run")
	fun `identity matches the live getters through an incremental-reservation run`() {
		val ctx = TestFixtures.newShuntingSimulationContext(initializeDynamicMapping = true).tracked()
		val loop = prepareShuntingLoop(ctx, SHUNTING_END_TIME)

		runAndSample(ctx) { loop.getApprovedTrains() }

		assertIdentityMatchedLiveGetters()
	}
}
