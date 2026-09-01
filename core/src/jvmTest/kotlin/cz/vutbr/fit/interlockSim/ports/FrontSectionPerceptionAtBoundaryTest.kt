/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test for Issue #788: the dispatcher must keep seeing the traversed
 * block while a train's front stands at a section boundary.
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.testutil.ArrivalTally
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.multiTrainSpecs
import cz.vutbr.fit.interlockSim.testutil.runSampled
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Regression test for the #788 design decision — **fix the published `(entry, position)`
 * pair, never advance `frontSection` at the boundary**.
 *
 * [DefaultNetworkPerceptionPort.frontSectionName] is derived from `Train.frontSection`, and
 * the dispatcher's `ActionValidator` gates on it. Nothing else pins that `frontSection` keeps
 * reporting the traversed block while the front stands at the boundary, so a future
 * "simplification" that advances (or nulls) `frontSection` there would silently change what
 * the dispatcher perceives — every admission decision downstream — while all heading tests
 * stayed green. This test locks the decision in: at the arrival boundary, the perception port
 * must still report the block that ends at the destination InOut.
 *
 * The expected block name is derived from the topology, not from the train, so the assertion
 * bites exactly when `frontSection` stops pointing at the traversed block.
 *
 * @since Issue #788
 */
@DisplayName("Dispatcher perception of frontSection at section boundaries (Issue #788)")
class FrontSectionPerceptionAtBoundaryTest : KoinTestBase() {
	private companion object {
		const val MULTI_TRAIN_END_TIME: Long = 600L
		const val TRAIN_LENGTH: Double = 40.0

		/** Destination InOut of the 4-block linear scenario. */
		const val DESTINATION_NAME: String = "B"

		/** How close the published position must sit to the section length at the boundary. */
		const val BOUNDARY_POSITION_TOLERANCE: Double = 1.0e-6

		/** Trains that must complete their journey for the boundary state to be exercised. */
		const val MIN_ARRIVALS: Int = 2
	}

	private val arrivals = ArrivalTally()
	private val boundarySamples = mutableSetOf<Int>()
	private val perceptionViolations = mutableListOf<String>()

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	@DisplayName("frontSectionName still reports the traversed block at the boundary")
	fun `frontSectionName keeps reporting the traversed block while the front stands at the boundary`() {
		// 4-block linear topology: A -> Sem1 -> Sem2 -> Sem3 -> B (4 x 100 m), same as
		// TrainFrontBoundaryStateTest's arrival scenario.
		val ctx =
			TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withSemaphore(3, 3, false)
				.withSemaphore(5, 5, false)
				.withSemaphore(7, 7, false)
				.withInOut("B", 9, 9, false)
				.withConnection(1, 1, 3, 3, 100.0, 80.0)
				.withConnection(3, 3, 5, 5, 100.0, 80.0)
				.withConnection(5, 5, 7, 7, 100.0, 80.0)
				.withConnection(7, 7, 9, 9, 100.0, 80.0)
				.buildSimulationContext()
		testContext = ctx

		val loop =
			MultiTrainLoop(
				context = ctx,
				endTime = MULTI_TRAIN_END_TIME,
				trainSpecs = multiTrainSpecs(count = 2, interval = 2.0, length = TRAIN_LENGTH)
			)
		ctx.setMainProcess(loop)

		// The expected block comes from the topology alone: the one block ending at the
		// destination InOut. `runCatching` mirrors BlockIdentity's defensive `ends()` read.
		val destinationBlock =
			ctx
				.getGraph()
				.values()
				.filterIsInstance<DynamicTrackBlock>()
				.singleOrNull { block ->
					runCatching { block.ends() }
						.getOrDefault(emptyArray())
						.any { separatorLabel(it) == DESTINATION_NAME }
				}
		checkNotNull(destinationBlock) { "No block ends at the destination InOut $DESTINATION_NAME" }
		val expectedName = BlockIdentity.stableBlockId(destinationBlock)

		val port = DefaultNetworkPerceptionPort(env = ctx, activeTrains = { loop.getApprovedTrains() })

		runSampled(ctx, setOf(ReportType.TRAIN_EVENTS, ReportType.TRAIN_CONTINUOUS)) { message ->
			message?.let(arrivals::record)
			for (train in loop.getApprovedTrains()) {
				val section = train.frontSection ?: continue
				// The arrival boundary: the front stands at the far end of the section it
				// reports, and that section ends at the destination InOut.
				if (abs(train.frontPosition - section.length()) > BOUNDARY_POSITION_TOLERANCE) continue
				if (section.ends().none { separatorLabel(it) == DESTINATION_NAME }) continue

				boundarySamples.add(train.trainNumber)
				val perceived = port.trainPosition(train.name)?.frontSectionName
				if (perceived != expectedName) {
					perceptionViolations.add(
						"train #${train.trainNumber}: frontSectionName=$perceived, expected=$expectedName " +
							"(the traversed block ending at $DESTINATION_NAME)"
					)
				}
			}
		}

		perceptionViolations.forEach { println("PERCEPTION_VIOLATION: $it") }
		assertThat(
			arrivals.count >= MIN_ARRIVALS,
			name = "both trains reached the destination InOut (boundary state exercised)"
		).isTrue()
		assertThat(
			boundarySamples.size >= MIN_ARRIVALS,
			name = "the boundary state was sampled for every train (sampled: ${boundarySamples.toList().sorted()})"
		).isTrue()
		assertThat(
			perceptionViolations.isEmpty(),
			name = "frontSectionName reports the traversed block at the boundary (violations: ${perceptionViolations.take(3)})"
		).isTrue()
	}
}
