/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1087 — a restrictive aspect arriving after a leg resumed from the clearance hold
 * must be braked for, not snapped to zero.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.AspectFlipOnce
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.TrainKinematicSample
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
 * Issue #1087 — the late-aspect watch of Issue #1057 covered only legs commanded by
 * `accelerateTo`. A leg the motor resumed by itself after a clearance hold
 * (`Motor.resumeAtAspectCap`) went idle once the resumed cap was reached, so an aspect turning
 * restrictive while the train coasted at that cap re-commanded nothing: the front reached the
 * clearance line at the resumed speed and `fireStop` snapped it to zero — the Issue #1057 defect
 * class on a different path into it.
 *
 * The train must instead start braking under the braking law as soon as the room left to the
 * stop line is no more than the textbook braking distance at the deceleration bound, and stand
 * at the clearance stop line having decelerated continuously.
 *
 * Fixture: `A —(200 m)— Sem —100 m— B`. The signal starts at [Signal.STOP], so the leg is held
 * by the clearance-stop approach; it clears to [Signal.S40] early in the approach, which resumes
 * the run at the aspect's cap inside the motor's own approach loop; the train reaches the cap
 * and coasts; then the aspect flips back to [Signal.STOP] with ample braking room left.
 * `Issue1014BrakingOnTooShortBlockTest`'s rung 3e pins the flip-back **before** the cap is
 * reached; this test pins the flip-back **after** it, the leg state that went unwatched.
 */
@Tag("integration-test")
@DisplayName("Issue #1087 — a restrictive aspect after a resumed leg reached its cap is braked for")
class Issue1087ResumedLegLateFlipTest : KoinTestBase() {
	private companion object {
		/** Long enough for the resumed run to reach the S40 cap and still leave braking room. */
		const val APPROACH_BLOCK_LENGTH = 200.0

		/** The run ends with the train held at the stop line, well before this. */
		const val END_TIME = 60L

		const val FINE_SAMPLE_PERIOD = 0.005

		/** Distance at which the held aspect clears to [Signal.S40]: early in the approach. */
		const val CLEAR_DISTANCE = 20.0

		/**
		 * Distance at which the aspect turns restrictive again: far past the point the resumed
		 * run reaches the S40 cap (11.11 m/s, about 20.6 m of braking distance at the bound),
		 * with about 90 m of braking room left.
		 */
		const val FLIP_BACK_DISTANCE = 110.0

		/** Speed below which the braking law's final crawl is not treated as running speed. */
		const val CRAWL_SPEED_MPS = 0.5

		/**
		 * Largest speed change between two consecutive samples of a train braking at the
		 * deceleration bound (3 m/s squared): 0.015 m/s per 5 ms sample, with margin. A snap to
		 * zero from the resumed cap is a change of several m/s.
		 */
		const val MAX_SAMPLE_SPEED_STEP_MPS = 0.1

		/** The braking law's deceleration bound, |`Train`'s MINIMAL_DECELERATION|: 3 m/s squared. */
		const val DECELERATION_BOUND_MPS2 = 3.0

		/**
		 * Slack around the resumed cap at the flip-back, proving the cap really was reached —
		 * that is, `resumeAtAspectCap`'s wait had ended and pre-fix the motor was idle. The
		 * resumed run approaches its capped target asymptotically, so a sample can land a hair
		 * below it, or on numerical noise a hair above.
		 */
		const val CAP_TOLERANCE_MPS = 0.5
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a restrictive flip while coasting at the resumed cap brakes the train to the stop line")
	fun restrictiveFlipWhileCoastingAtTheResumedCapBrakesToTheStopLine() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork(approachLength = APPROACH_BLOCK_LENGTH)
		val ctx = network.context.tracked()
		val samples = mutableListOf<TrainKinematicSample>()
		val clear =
			AspectFlipOnce(
				network.semaphore,
				Signal.S40,
				trigger = { it.totalDistance >= CLEAR_DISTANCE }
			)
		var flippedBackAt: TrainKinematicSample? = null
		val flipBack =
			AspectFlipOnce(
				network.semaphore,
				Signal.STOP,
				trigger = { clear.fired && it.totalDistance >= FLIP_BACK_DISTANCE },
				onFlip = { flippedBackAt = it }
			)
		runClearanceStopScenario(
			ctx,
			semaphores = listOf(network.semaphore),
			endTime = END_TIME,
			initialAspect = Signal.STOP,
			samplePeriod = FINE_SAMPLE_PERIOD,
			onSample = { _, sample ->
				samples += sample
				clear.onSample(sample)
				flipBack.onSample(sample)
			}
		)
		val atFlipBack = requireNotNull(flippedBackAt) { "the aspect never turned restrictive again" }
		logger.info { "T1087: re-restricted at $atFlipBack, final ${samples.last()}" }

		assertThat(clear.fired, name = "the held aspect was cleared to S40").isTrue()

		// The scenario proves nothing unless the resumed cap really was reached before the flip:
		// that is the leg state in which the motor went idle before this fix.
		assertThat(atFlipBack.velocity, name = "speed when the aspect flipped back")
			.isGreaterThanOrEqualTo(Signal.S40.allowedSpeed() - CAP_TOLERANCE_MPS)
		assertThat(atFlipBack.velocity, name = "speed when the aspect flipped back")
			.isLessThanOrEqualTo(Signal.S40.allowedSpeed() + CAP_TOLERANCE_MPS)

		// The flip must land with braking room left, or a stand at the line from whatever speed
		// the train has would be correct anyway and the rung would not discriminate.
		val stopLine = APPROACH_BLOCK_LENGTH - Train.SEMAPHORE_STOP_CLEARANCE_METERS
		assertThat(
			atFlipBack.velocity * atFlipBack.velocity / (2 * DECELERATION_BOUND_MPS2),
			name = "braking distance needed at the flip-back"
		).isLessThan(stopLine - atFlipBack.totalDistance)

		// No speed step above the braking bound: the watch's crossing wait starts the braking
		// law at the room threshold, so every step of the stand is braking at the bound. The
		// final crawl is legitimately ended by the front's clearance gate, so only steps that
		// start above a crawl are inspected — and that the cut-off speed really is a crawl is
		// asserted by the zero final velocity below.
		val worstStep =
			samples
				.filter { it.time >= atFlipBack.time }
				.zipWithNext()
				.filter { (a, _) -> a.velocity > CRAWL_SPEED_MPS }
				.map { (a, b) -> abs(b.velocity - a.velocity) }
				.maxOrNull() ?: 0.0
		assertThat(worstStep, name = "largest speed change between consecutive samples after the flip-back")
			.isLessThanOrEqualTo(MAX_SAMPLE_SPEED_STEP_MPS)

		val last = samples.last()
		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertStoodAtClearanceStopLine(last.totalDistance, APPROACH_BLOCK_LENGTH)
	}
}
