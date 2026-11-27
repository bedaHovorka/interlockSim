/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Pin for the #1414 front-block origin decision (1B-SP12.9).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.multiTrainSpecs
import cz.vutbr.fit.interlockSim.testutil.runSampled
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The one case in which [RouteRequestAdmission.legalOriginsOf] covers the front-block origin rule
 * (Issue #1414, demand 1B-SP12.9): a train standing **at its stop signal**. It reports the block it
 * has traversed up to the signal as its front section (Issue #788), so the signal it is held to
 * ([standingAtSignalName]) bounds its front block. This does not hold for a train that stops short
 * of its signal (a mid-block standstill); there the kernel's contiguity guard is the rule.
 *
 * Three trains on a 4-block line with no switches, released 2 s apart, queue behind each other at
 * the semaphores, so the standing-at-signal state is sampled on real trains. Each sample checks that
 * the signal the perception port reports as "standing at" is an end of `Train.frontSection`.
 */
@DisplayName("A train standing at its stop signal is held to its front block's boundary (Issue #1414, 1B-SP12.9)")
class StandingOriginBoundsFrontBlockTest : KoinTestBase() {
	private companion object {
		const val END_TIME: Long = 600L
		const val TRAIN_COUNT: Int = 3
		const val TRAIN_LENGTH: Double = 40.0
		const val RELEASE_INTERVAL: Double = 2.0
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `the signal a train stands at is an end of its front block`() {
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
				.tracked()

		val loop =
			MultiTrainLoop(
				context = ctx,
				endTime = END_TIME,
				trainSpecs = multiTrainSpecs(count = TRAIN_COUNT, interval = RELEASE_INTERVAL, length = TRAIN_LENGTH)
			)
		ctx.setMainProcess(loop)
		val port = DefaultNetworkPerceptionPort(env = ctx, activeTrains = { loop.getApprovedTrains() })

		val standingSamples = mutableSetOf<String>()
		val violations = mutableListOf<String>()
		runSampled(ctx, setOf(ReportType.TRAIN_EVENTS, ReportType.TRAIN_CONTINUOUS)) {
			for (train in loop.getApprovedTrains()) {
				val signal = port.trainPerception(train.name)?.standingAtSignalName() ?: continue
				val front = train.frontSection
				// No front section: the not-yet-entered arm of legalOriginsOf governs, not this one.
				if (front == null) continue
				standingSamples.add("${train.name}@$signal")
				if (front.ends().none { separatorLabel(it) == signal }) {
					violations.add("${train.name} stands at '$signal', front block ends ${front.ends().map(::separatorLabel)}")
				}
			}
		}

		assertThat(standingSamples.isNotEmpty(), name = "a standing train with a front block was sampled").isTrue()
		assertThat(violations.isEmpty(), name = "standing signal bounds the front block (${violations.take(3)})").isTrue()
	}
}
