/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1057 — a restrictive aspect arriving mid-leg must be braked for, not snapped to zero.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.AspectFlipOnce
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.assertStoodAtClearanceStopLine
import cz.vutbr.fit.interlockSim.testutil.runClearanceStopScenario
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/**
 * Issue #1057 — the motor is commanded once per leg, so an aspect that turns restrictive while
 * the train is already running at line speed re-commands nothing. Before the fix the front was
 * then carried to the clearance stop line at full speed and snapped to zero by `fireStop`.
 *
 * The train must instead start braking under the braking law as soon as the room left to the
 * stop line is no more than the textbook braking distance at the deceleration bound, and stand
 * at the stop line having decelerated continuously.
 *
 * Fixture: `A —(200 m)— Sem —100 m— B`, the S30 aspect (8.3 m/s) forced onto the semaphore, then
 * flipped to STOP while the train is well outside the braking distance.
 */
@Tag("integration-test")
@DisplayName("Issue #1057 — a late restrictive aspect is braked for")
class Issue1057LateFlipBrakingTest : KoinTestBase() {
	private companion object {
		const val APPROACH_BLOCK_LENGTH = 200.0
		const val END_TIME = 60L
		const val FINE_SAMPLE_PERIOD = 0.005

		/** The train must be at line speed when the aspect flips, or the scenario proves nothing. */
		const val LINE_SPEED_MIN_MPS = 7.0

		/** Speed below which the braking law's final crawl is not treated as running speed. */
		const val CRAWL_SPEED_MPS = 0.5

		/**
		 * Largest speed change between two consecutive samples of a train braking at the
		 * deceleration bound (3 m/s squared): 0.015 m/s per 5 ms sample, with margin. A snap to
		 * zero from line speed is a change of several m/s.
		 */
		const val MAX_SAMPLE_SPEED_STEP_MPS = 0.1
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("A restrictive flip mid-leg with braking room brakes the train instead of snapping it to zero")
	fun restrictiveFlipMidLegBrakesInsteadOfSnapping() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork(approachLength = APPROACH_BLOCK_LENGTH)
		var speedAtFlip = -1.0
		val flip =
			AspectFlipOnce(
				network.semaphore,
				Signal.STOP,
				trigger = { network.semaphore.signal.isAllowing() && it.distanceToSemaphore in 35.0..45.0 },
				onFlip = { speedAtFlip = it.velocity }
			)
		val run =
			runClearanceStopScenario(
				network.context.tracked(),
				semaphores = listOf(network.semaphore),
				endTime = END_TIME,
				initialAspect = Signal.S30,
				samplePeriod = FINE_SAMPLE_PERIOD
			) { _, sample -> flip.onSample(sample) }
		run.samples
			.filter { it.distanceToSemaphore in 0.0..45.0 }
			.filterIndexed { i, _ -> i % 100 == 0 }
			.forEach { logger.info { "T1057 $it" } }
		logger.info { "T1057 final ${run.samples.last()}" }

		assertThat(flip.fired, name = "aspect turned restrictive mid-leg").isEqualTo(true)
		assertThat(speedAtFlip, name = "speed when the aspect flipped").isGreaterThanOrEqualTo(LINE_SPEED_MIN_MPS)

		val worstStep =
			run.samples
				.zipWithNext()
				// The final crawl is legitimately ended by the front's clearance gate (T2 of Issue #989
				// pins it below 0.5 m/s), so only steps that start above a crawl are inspected.
				.filter { (a, _) -> a.velocity > CRAWL_SPEED_MPS }
				.map { (a, b) -> abs(b.velocity - a.velocity) }
				.maxOrNull() ?: 0.0
		assertThat(worstStep, name = "largest speed change between consecutive samples")
			.isLessThanOrEqualTo(MAX_SAMPLE_SPEED_STEP_MPS)

		val last = run.samples.last()
		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertStoodAtClearanceStopLine(last.totalDistance, APPROACH_BLOCK_LENGTH)
		assertThat(last.distanceToSemaphore, name = "final distance to the signal")
			.isGreaterThan(Train.SEMAPHORE_STOP_CLEARANCE_METERS - 1e-2)
	}
}
