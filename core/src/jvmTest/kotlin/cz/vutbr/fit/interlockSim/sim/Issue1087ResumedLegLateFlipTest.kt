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
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.domain.SERVICE_BRAKING_DECELERATION_MPS2
import cz.vutbr.fit.interlockSim.domain.brakingDistanceFrom
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.AspectFlipOnce
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.TrainKinematicSample
import cz.vutbr.fit.interlockSim.testutil.assertStoodAtClearanceStopLine
import cz.vutbr.fit.interlockSim.testutil.clearanceStopLine
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
 * the run at the aspect's cap inside the motor's own approach loop; the resumed leg reaches the
 * cap half-way to the signal (see `ResumedLegSpeedLawTest`) and coasts; then the aspect flips
 * back to [Signal.STOP] with ample braking room left.
 * `Issue1014BrakingOnTooShortBlockTest`'s rung 3e pins the flip-back **before** the cap is
 * reached; this test pins the flip-back **after** it, the leg state that went unwatched.
 *
 * Two further rungs widen the cover. The FREE rung clears to [Signal.FREE] on a longer block, so
 * the resumed leg climbs to the line-speed cap — the "resumed speed is line speed" case the
 * issue names — and is flipped back with the much larger braking distance that speed needs. The
 * boundary rung flips back exactly as the braking room at the cap runs out: the watch's guard
 * jumps from its 1.0 bound straight past zero, the discontinuous crossing whose re-test the
 * `watchForLateRestrictiveAspect` tolerance comment guards (Issue #1057 review).
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
		 * Distance at which the aspect turns restrictive again. The resumed run reaches the S40
		 * cap (11.11 m/s) half-way between the clear and the signal, at about 110 m, so the train
		 * has coasted at the cap for about 30 m; the stop line is then 59 m away and the textbook
		 * braking distance from the cap at the bound is about 20.6 m.
		 */
		const val FLIP_BACK_DISTANCE = 140.0

		/**
		 * Stretch before the flip-back over which the train must already be coasting at the
		 * cap: the leg state in which the resumed leg's wait had ended and, before Issue #1087,
		 * the motor went idle.
		 */
		const val COASTING_STRETCH_METERS = 20.0

		/** Speed below which the braking law's final crawl is not treated as running speed. */
		const val CRAWL_SPEED_MPS = 0.5

		/** Headroom on the service-braking rate's exact per-sample step, for the sample noise of the stand. */
		const val SAMPLE_STEP_HEADROOM_MPS = 0.05

		/**
		 * Largest speed change between two consecutive samples of a train braking at the
		 * service-braking rate: the rate's step per 5 ms sample (0.015 m/s), with headroom. A
		 * snap to zero from the resumed cap is a change of several m/s.
		 */
		val MAX_SAMPLE_SPEED_STEP_MPS = SERVICE_BRAKING_DECELERATION_MPS2 * FINE_SAMPLE_PERIOD + SAMPLE_STEP_HEADROOM_MPS

		/**
		 * Slack around the resumed cap at the flip-back, proving the cap really was reached —
		 * that is, `resumeAtAspectCap`'s wait had ended and pre-fix the motor was idle. The
		 * resumed leg runs up at a constant rate and its wait wakes at most one 1 ms step late,
		 * so it overshoots the cap by well under a millimetre per second.
		 */
		const val CAP_TOLERANCE_MPS = 0.01

		/**
		 * Block length of the FREE rung: the line-speed cap
		 * ([TestTopologies.LINEAR_BLOCK_MAX_SPEED_MPS]) is much further out than an aspect cap
		 * (the resumed leg reaches it half-way to the signal), and the textbook braking distance
		 * from that cap is about 1067 m, so the flip-back needs that much room.
		 */
		const val FREE_APPROACH_BLOCK_LENGTH = 2600.0

		/**
		 * Distance at which the FREE rung flips back. The resumed run reaches the line-speed
		 * cap half-way between the clear and the signal, at about 1310 m, so the train has
		 * coasted at the cap for about 90 m; the stop line is then about 1199 m away and the
		 * textbook braking distance from the cap at the bound is about 1067 m.
		 */
		const val FREE_FLIP_BACK_DISTANCE = 1400.0

		/** The FREE rung's horizon: the line-speed run and its braking take about 65 s in all. */
		const val FREE_END_TIME = 120L

		/**
		 * Largest speed change the FREE rung's stand may end with: the crossing wait wakes at
		 * most one accepted step late, so the braking starts a hair past the room and the front's
		 * clearance gate ends the arrival crawl — about 0.7 m/s at the 80 m/s line-speed cap,
		 * the same residual `Issue1057LateFlipBrakingTest` bounds at 1.0.
		 */
		const val MAX_RESIDUAL_STEP_MPS = 1.0
	}

	/** One flip-back rung's outcome: the flip's own sample, the stand's samples, and the clear's state. */
	private class FlipBackRun(
		val atFlipBack: TrainKinematicSample,
		val samples: List<TrainKinematicSample>,
		val clearFired: Boolean
	)

	/**
	 * Runs one flip-back rung on `A —[approachLength]— Sem —100 m— B`: the signal starts at
	 * [Signal.STOP], clears to [clearTo] at [CLEAR_DISTANCE], flips back to [Signal.STOP] once
	 * [flipBackAt] is reached, and samples the stand every [FINE_SAMPLE_PERIOD] until [endTime].
	 */
	private fun runFlipBackRung(
		approachLength: Double,
		clearTo: Signal,
		flipBackAt: Double,
		endTime: Long,
		tag: String
	): FlipBackRun {
		val network = TestTopologies.linearPathWithSemaphoreNetwork(approachLength = approachLength)
		val ctx = network.context.tracked()
		val samples = mutableListOf<TrainKinematicSample>()
		val clear = AspectFlipOnce(network.semaphore, clearTo, trigger = { it.totalDistance >= CLEAR_DISTANCE })
		var flippedBackAt: TrainKinematicSample? = null
		val flipBack =
			AspectFlipOnce(
				network.semaphore,
				Signal.STOP,
				trigger = { clear.fired && it.totalDistance >= flipBackAt },
				onFlip = { flippedBackAt = it }
			)
		runClearanceStopScenario(
			ctx,
			semaphores = listOf(network.semaphore),
			endTime = endTime,
			initialAspect = Signal.STOP,
			samplePeriod = FINE_SAMPLE_PERIOD,
			onSample = { _, sample ->
				samples += sample
				clear.onSample(sample)
				flipBack.onSample(sample)
			}
		)
		val atFlipBack = requireNotNull(flippedBackAt) { "the aspect never turned restrictive again" }
		logger.info { "T1087 $tag: re-restricted at $atFlipBack, final ${samples.last()}" }
		return FlipBackRun(atFlipBack, samples, clear.fired)
	}

	/**
	 * Largest speed change between consecutive samples from [fromTime] on, over steps that
	 * start above a crawl: the braking law's final crawl is legitimately ended by the front's
	 * clearance gate, so a step that starts at a crawl is not the braking law's business.
	 */
	private fun worstSpeedStepAfter(
		samples: List<TrainKinematicSample>,
		fromTime: Double
	): Double =
		samples
			.filter { it.time >= fromTime }
			.zipWithNext()
			.filter { (a, _) -> a.velocity > CRAWL_SPEED_MPS }
			.map { (a, b) -> abs(b.velocity - a.velocity) }
			.maxOrNull() ?: 0.0

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a restrictive flip while coasting at the resumed cap brakes the train to the stop line")
	fun restrictiveFlipWhileCoastingAtTheResumedCapBrakesToTheStopLine() {
		val run =
			runFlipBackRung(
				approachLength = APPROACH_BLOCK_LENGTH,
				clearTo = Signal.S40,
				flipBackAt = FLIP_BACK_DISTANCE,
				endTime = END_TIME,
				tag = "S40"
			)
		val atFlipBack = run.atFlipBack

		assertThat(run.clearFired, name = "the held aspect was cleared to S40").isTrue()

		// The scenario proves nothing unless the resumed cap really was reached before the flip
		// and the train was coasting there: that is the leg state in which the motor went idle
		// before this fix, and the only one in which the late-aspect watch of `runApproachLoop`
		// is armed. A flip during the ramp is `resumeAtAspectCap`'s own exit (rung 3e of
		// `Issue1014BrakingOnTooShortBlockTest`) and would not reach the watch at all.
		val cap = Signal.S40.allowedSpeed()
		run.samples
			.filter { it.time <= atFlipBack.time && it.totalDistance >= FLIP_BACK_DISTANCE - COASTING_STRETCH_METERS }
			.forEach { sample ->
				assertThat(sample.velocity, name = "speed coasting at the cap before the flip-back, $sample")
					.isBetween(cap - CAP_TOLERANCE_MPS, cap + CAP_TOLERANCE_MPS)
			}

		// The flip must land with braking room left, or a stand at the line from whatever speed
		// the train has would be correct anyway and the rung would not discriminate.
		val stopLine = clearanceStopLine(APPROACH_BLOCK_LENGTH)
		assertThat(
			brakingDistanceFrom(atFlipBack.velocity),
			name = "braking distance needed at the flip-back"
		).isLessThan(stopLine - atFlipBack.totalDistance)

		// No speed step above the braking bound: the watch's crossing wait starts the braking
		// law at the room threshold, so every step of the stand is braking at the bound. The
		// final crawl is legitimately ended by the front's clearance gate, so only steps that
		// start above a crawl are inspected — and that the cut-off speed really is a crawl is
		// asserted by the zero final velocity below.
		assertThat(
			worstSpeedStepAfter(run.samples, atFlipBack.time),
			name = "largest speed change between consecutive samples after the flip-back"
		).isLessThanOrEqualTo(MAX_SAMPLE_SPEED_STEP_MPS)

		val last = run.samples.last()
		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertStoodAtClearanceStopLine(last.totalDistance, APPROACH_BLOCK_LENGTH)
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a restrictive flip while coasting at the line-speed cap after a FREE clear brakes to the stop line")
	fun restrictiveFlipWhileCoastingAtTheLineSpeedCapBrakesToTheStopLine() {
		val run =
			runFlipBackRung(
				approachLength = FREE_APPROACH_BLOCK_LENGTH,
				clearTo = Signal.FREE,
				flipBackAt = FREE_FLIP_BACK_DISTANCE,
				endTime = FREE_END_TIME,
				tag = "FREE"
			)
		val atFlipBack = run.atFlipBack

		assertThat(run.clearFired, name = "the held aspect was cleared to FREE").isTrue()

		// The same premise as the S40 rung, at line speed: the cap was reached before the flip
		// and the train was coasting there — pre-fix, the motor was idle in exactly this state.
		val cap = TestTopologies.LINEAR_BLOCK_MAX_SPEED_MPS
		run.samples
			.filter { it.time <= atFlipBack.time && it.totalDistance >= FREE_FLIP_BACK_DISTANCE - COASTING_STRETCH_METERS }
			.forEach { sample ->
				assertThat(sample.velocity, name = "speed coasting at the line-speed cap before the flip-back, $sample")
					.isBetween(cap - CAP_TOLERANCE_MPS, cap + CAP_TOLERANCE_MPS)
			}

		// The flip must land with braking room left even from line speed, or the rung would not
		// discriminate a brake from the front gate's snap.
		val stopLine = clearanceStopLine(FREE_APPROACH_BLOCK_LENGTH)
		assertThat(
			brakingDistanceFrom(atFlipBack.velocity),
			name = "braking distance needed at the flip-back"
		).isLessThan(stopLine - atFlipBack.totalDistance)

		// Every step of the stand is braking at the bound except the last: the crossing wait
		// wakes at most one accepted step late, the braking starts that hair past the room, and
		// the front's clearance gate ends the arrival crawl — about 0.7 m/s at the 80 m/s cap,
		// the same residual `Issue1057LateFlipBrakingTest` bounds at its `MAX_RESIDUAL_STEP_MPS`.
		// A pre-fix snap from the cap fails the residual bound below, not this tight one.
		val worstBrakingStep =
			run.samples
				.filter { it.time >= atFlipBack.time }
				.zipWithNext()
				.filter { (a, _) -> a.velocity > CRAWL_SPEED_MPS }
				.filter { (a, b) -> b.velocity > CRAWL_SPEED_MPS }
				.map { (a, b) -> abs(b.velocity - a.velocity) }
				.maxOrNull() ?: 0.0
		assertThat(worstBrakingStep, name = "largest speed change between consecutive braking samples after the flip-back")
			.isLessThanOrEqualTo(MAX_SAMPLE_SPEED_STEP_MPS)
		assertThat(
			worstSpeedStepAfter(run.samples, atFlipBack.time),
			name = "largest speed change between consecutive samples after the flip-back"
		).isLessThanOrEqualTo(MAX_RESIDUAL_STEP_MPS)

		val last = run.samples.last()
		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertStoodAtClearanceStopLine(last.totalDistance, FREE_APPROACH_BLOCK_LENGTH)
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a restrictive flip exactly when the braking room runs out still brakes to the stop line")
	fun restrictiveFlipExactlyAtTheBrakingRoomBoundaryStillBrakesToTheStopLine() {
		val cap = Signal.S40.allowedSpeed()
		// The flip lands exactly as the braking room at the cap runs out: at the flip the watch's
		// guard jumps from its 1.0 bound straight to (just below) zero, the discontinuous
		// crossing whose re-test the `watchForLateRestrictiveAspect` tolerance comment guards
		// (Issue #1057 review). The trigger fires on the first sample past the boundary — one
		// 5 ms sample at the cap is about 5.6 cm — so the room at the flip is at most that far
		// negative, never positive.
		val boundaryDistance = clearanceStopLine(APPROACH_BLOCK_LENGTH) - brakingDistanceFrom(cap)
		val run =
			runFlipBackRung(
				approachLength = APPROACH_BLOCK_LENGTH,
				clearTo = Signal.S40,
				flipBackAt = boundaryDistance,
				endTime = END_TIME,
				tag = "boundary"
			)
		val atFlipBack = run.atFlipBack

		assertThat(run.clearFired, name = "the held aspect was cleared to S40").isTrue()

		// The flip really landed in the coasting state: still at the cap over the stretch
		// before it, and no earlier — the room was gone at the flip, not before.
		run.samples
			.filter { it.time <= atFlipBack.time && it.totalDistance >= boundaryDistance - COASTING_STRETCH_METERS }
			.forEach { sample ->
				assertThat(sample.velocity, name = "speed coasting at the cap before the flip-back, $sample")
					.isBetween(cap - CAP_TOLERANCE_MPS, cap + CAP_TOLERANCE_MPS)
			}
		assertThat(
			atFlipBack.totalDistance,
			name = "flip-back distance, at or just past the boundary"
		).isGreaterThanOrEqualTo(boundaryDistance)

		// From a flip with the room gone, braking at the bound still stands the train at the
		// line; the pre-fix idle motor would have let the front's `fireStop` snap it to zero.
		assertThat(
			worstSpeedStepAfter(run.samples, atFlipBack.time),
			name = "largest speed change between consecutive samples after the flip-back"
		).isLessThanOrEqualTo(MAX_SAMPLE_SPEED_STEP_MPS)

		val last = run.samples.last()
		assertThat(last.velocity, name = "final velocity").isEqualTo(0.0)
		assertStoodAtClearanceStopLine(last.totalDistance, APPROACH_BLOCK_LENGTH)
	}
}
