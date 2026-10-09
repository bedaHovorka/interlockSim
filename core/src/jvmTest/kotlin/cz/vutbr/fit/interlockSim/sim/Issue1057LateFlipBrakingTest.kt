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
import cz.vutbr.fit.interlockSim.domain.brakingDistanceFrom
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
 * Issue #1057 — the engine is commanded once per leg, so an aspect that turns restrictive while
 * the train is already running at line speed re-commands nothing. Before the fix the front was
 * then carried to the clearance stop line at full speed and snapped to zero by `fireStop`.
 *
 * The train must instead start braking under the braking law as soon as the room left to the
 * stop line is no more than the textbook braking distance at the deceleration bound, and stand
 * at the stop line having decelerated continuously.
 *
 * Fixture: `A —(200 m)— Sem —100 m— B`. Two scenarios pin the two paths into the defect: the
 * coast case (S30 forced onto the semaphore, flipped to STOP while the train runs at the cap,
 * well outside the braking distance) and the ramp case (FREE, flipped to STOP while the train is
 * still accelerating) — the ramp case also pins the threshold itself: braking starts only when
 * the room left reaches the braking distance at the bound, never at the flip.
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

		/**
		 * Largest speed change the stand may end with when the braking room ran out during the
		 * acceleration phase. The onset wait is root-found (Issue #760), but the velocity
		 * integration sees the engine's switch to braking one accepted step late, so the train
		 * overruns the braking point a little and the clearance gate ends the stand from a
		 * residual speed: 0.46 m/s measured at the generator's 1 ms `dtMax` (0.65 m/s before
		 * Issue #760, when the onset wait itself was a whole-step poll). The residual grows with
		 * the step — 0.92 m/s at 10 ms — which is why `dtMax` stays at 1 ms.
		 */
		const val MAX_RESIDUAL_STEP_MPS = 0.1

		/**
		 * Tolerance for the braking-onset margin (the room left minus the textbook braking
		 * distance): one 5 ms sample at the onset speed is about 0.13 m of slack, plus the
		 * generator's position error.
		 */
		const val ONSET_MARGIN_TOLERANCE_METERS = 0.5
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
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
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("A restrictive flip while accelerating brakes at the room threshold, not at the flip")
	fun restrictiveFlipWhileAcceleratingBrakesAtTheRoomThreshold() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork(approachLength = APPROACH_BLOCK_LENGTH)
		var speedAtFlip = -1.0
		var timeAtFlip = -1.0
		val flip =
			AspectFlipOnce(
				network.semaphore,
				Signal.STOP,
				// FREE lets the leg ramp towards the 80 m/s block cap, so the flip lands well
				// inside the acceleration phase and with clearly positive braking room.
				trigger = { it.distanceToSemaphore in 150.0..160.0 },
				onFlip = {
					speedAtFlip = it.velocity
					timeAtFlip = it.time
				}
			)
		val run =
			runClearanceStopScenario(
				network.context.tracked(),
				semaphores = listOf(network.semaphore),
				endTime = END_TIME,
				initialAspect = Signal.FREE,
				samplePeriod = FINE_SAMPLE_PERIOD
			) { _, sample -> flip.onSample(sample) }
		logger.info { "T1057 ramp final ${run.samples.last()}" }

		assertThat(flip.fired, name = "aspect turned restrictive mid-leg").isEqualTo(true)

		val afterFlip = run.samples.filter { it.time > timeAtFlip }

		// The flip found braking room left, so the run must have kept speeding up after it: what
		// ends the leg is the room running out, not the flip itself.
		val topSpeedAfterFlip = afterFlip.maxOf { it.velocity }
		assertThat(topSpeedAfterFlip, name = "top speed after the flip").isGreaterThan(speedAtFlip)

		// Braking starts only when the room left to the clearance stop line no longer exceeds the
		// textbook braking distance at the deceleration bound — the same rule the two-phase
		// approach applies (Issue #1014), here observed from the samples.
		val onset = afterFlip.zipWithNext().first { (a, b) -> b.velocity < a.velocity }.first
		val roomAtOnset =
			onset.distanceToSemaphore - Train.SEMAPHORE_STOP_CLEARANCE_METERS -
				brakingDistanceFrom(onset.velocity)
		assertThat(abs(roomAtOnset), name = "braking-room margin at braking onset")
			.isLessThanOrEqualTo(ONSET_MARGIN_TOLERANCE_METERS)

		// Every step of the stand is either braking at the bound or the final residual the front's
		// clearance gate ends. The residual comes from the velocity integration taking up the
		// braking one accepted step after the root-found onset (kDisco `dtMax` = 1 ms; about
		// 0.46 m/s measured at a 21.6 m/s onset), not the line-speed snap this issue fixes.
		val worstStep =
			afterFlip
				.zipWithNext()
				.maxOfOrNull { (a, b) -> abs(b.velocity - a.velocity) } ?: 0.0
		assertThat(worstStep, name = "largest speed change between consecutive samples")
			.isLessThanOrEqualTo(MAX_RESIDUAL_STEP_MPS)

		val last = run.samples.last()
		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertStoodAtClearanceStopLine(last.totalDistance, APPROACH_BLOCK_LENGTH)
	}
}
