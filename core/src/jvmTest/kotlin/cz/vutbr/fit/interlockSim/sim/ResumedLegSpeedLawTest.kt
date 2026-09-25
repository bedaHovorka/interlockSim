/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * The speed law of a leg the motor resumes by itself after a held restrictive signal clears.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.domain.MAXIMAL_TRAIN_ACCELERATION
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.AspectFlipOnce
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.TrainKinematicSample
import cz.vutbr.fit.interlockSim.testutil.runClearanceStopScenario
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

/**
 * The speed law of the leg `Motor.resumeAtAspectCap` starts when a held restrictive signal
 * clears during the clearance-stop approach (Issue #1087 decision).
 *
 * Every other leg aims the law `a = (T² − v²) / (2s)` at the signal, so it reaches its target
 * speed only *at* the signal. For the resumed leg that meant a train never ran at the cap it was
 * resumed to anywhere in the block, and the late-aspect watch armed after the cap could never
 * run. An **accelerating** resume therefore runs up at the constant rate the same law gives when
 * aimed at half the distance left at the resume instant — `(T² − v₀²) / s₀`, bounded by
 * [MAXIMAL_TRAIN_ACCELERATION] — reaches the cap half-way, and coasts at it to the signal. A
 * **decelerating** resume (the cap below the current speed) keeps the old law: the permitted
 * speed of the aspect applies at the signal, and slowing down over the whole remaining distance
 * gets there without any coasting state for a watch to cover.
 *
 * Fixture: `A —(200 m)— Sem —100 m— B`, the signal starting at [Signal.STOP] and cleared at
 * [CLEAR_DISTANCE], where the onWarning phase 1 has the train at about 8.8 m/s.
 */
@Tag("integration-test")
@DisplayName("the leg resumed after a held signal clears reaches its cap before the signal")
class ResumedLegSpeedLawTest : KoinTestBase() {
	private companion object {
		const val APPROACH_BLOCK_LENGTH = 200.0
		const val END_TIME = 60L
		const val FINE_SAMPLE_PERIOD = 0.005

		/** Distance at which the held aspect clears: early in the approach, inside phase 1. */
		const val CLEAR_DISTANCE = 20.0

		/**
		 * Slack on where the cap is first reached. One 5 ms sample at the cap covers under 6 cm;
		 * the rest is room for the root finder locating the resume instant.
		 */
		const val CAP_POINT_TOLERANCE_METERS = 1.0

		/**
		 * Slack on a speed read off the constant-rate ramp or the coast. The ramp's wait wakes at
		 * most one 1 ms step late, well under a millimetre per second at these rates.
		 */
		const val SPEED_TOLERANCE_MPS = 0.01

		/** Stretch after the cap point skipped before the coast is checked. */
		const val COAST_SETTLE_METERS = 1.0

		/**
		 * Stretch before the signal left out of the coast check: at the separator the front
		 * re-commands the motor for the next leg.
		 */
		const val SEPARATOR_MARGIN_METERS = 1.0
	}

	/** The clear sample and all samples up to the separator. */
	private class ResumedRun(
		val atClear: TrainKinematicSample,
		val beforeSignal: List<TrainKinematicSample>
	)

	private fun runResumedLeg(clearTo: Signal): ResumedRun {
		val network = TestTopologies.linearPathWithSemaphoreNetwork(approachLength = APPROACH_BLOCK_LENGTH)
		val ctx = network.context.tracked()
		val samples = mutableListOf<TrainKinematicSample>()
		var clearedAt: TrainKinematicSample? = null
		val clear =
			AspectFlipOnce(
				network.semaphore,
				clearTo,
				trigger = { it.totalDistance >= CLEAR_DISTANCE },
				onFlip = { clearedAt = it }
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
			}
		)
		assertThat(clear.fired, name = "the held aspect was cleared to $clearTo").isTrue()
		val atClear = requireNotNull(clearedAt)
		return ResumedRun(
			atClear = atClear,
			beforeSignal = samples.filter { it.time >= atClear.time && it.totalDistance < APPROACH_BLOCK_LENGTH }
		)
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("an accelerating resume reaches its cap half-way to the signal and coasts at it")
	fun acceleratingResumeReachesItsCapHalfWayAndCoasts() {
		val cap = Signal.S40.allowedSpeed()
		val run = runResumedLeg(Signal.S40)
		val v0 = run.atClear.velocity
		val s0 = run.atClear.distanceToSemaphore
		val x0 = run.atClear.totalDistance
		logger.info { "resumed at ${run.atClear}" }
		assertThat(v0, name = "speed when the aspect cleared").isLessThan(cap)
		assertThat((cap * cap - v0 * v0) / s0, name = "the resumed rate")
			.isLessThanOrEqualTo(MAXIMAL_TRAIN_ACCELERATION.toDouble())

		// The ramp is the law aimed half-way: a constant rate, so v² grows linearly with distance
		// and a quarter of the way along it sits half-way between v₀² and the cap².
		val quarterPoint = x0 + s0 / 4.0
		val atQuarter = requireNotNull(run.beforeSignal.minByOrNull { abs(it.totalDistance - quarterPoint) })
		assertThat(atQuarter.velocity, name = "speed a quarter of the way to the signal, $atQuarter")
			.isBetween(
				sqrt((v0 * v0 + cap * cap) / 2.0) - SPEED_TOLERANCE_MPS,
				sqrt((v0 * v0 + cap * cap) / 2.0) + SPEED_TOLERANCE_MPS
			)

		val capReached = run.beforeSignal.firstOrNull { it.velocity >= cap - SPEED_TOLERANCE_MPS }
		assertThat(capReached, name = "first sample at the cap").isNotNull()
		val capPoint = requireNotNull(capReached).totalDistance
		logger.info { "cap reached at $capReached" }
		assertThat(capPoint, name = "distance at which the cap was reached")
			.isBetween(x0 + s0 / 2.0 - CAP_POINT_TOLERANCE_METERS, x0 + s0 / 2.0 + CAP_POINT_TOLERANCE_METERS)

		// From the cap to the signal the train coasts: the speed neither creeps on nor sags.
		val coasting =
			run.beforeSignal.filter {
				it.totalDistance >= capPoint + COAST_SETTLE_METERS &&
					it.totalDistance <= APPROACH_BLOCK_LENGTH - SEPARATOR_MARGIN_METERS
			}
		assertThat(coasting.size, name = "samples coasting at the cap").isGreaterThan(0)
		coasting.forEach { sample ->
			assertThat(sample.velocity, name = "speed coasting at the cap, $sample")
				.isBetween(cap - SPEED_TOLERANCE_MPS, cap + SPEED_TOLERANCE_MPS)
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a decelerating resume keeps the old law and reaches its cap at the signal")
	fun deceleratingResumeReachesItsCapAtTheSignal() {
		val cap = Signal.S30.allowedSpeed()
		val run = runResumedLeg(Signal.S30)
		val v0 = run.atClear.velocity
		val s0 = run.atClear.distanceToSemaphore
		val x0 = run.atClear.totalDistance
		logger.info { "resumed at ${run.atClear}" }
		assertThat(v0, name = "speed when the aspect cleared").isGreaterThan(cap + SPEED_TOLERANCE_MPS)

		// Aimed at the signal: v² falls linearly over the whole distance left, so half-way it sits
		// half-way between v₀² and the cap² — still above the cap.
		val halfPoint = x0 + s0 / 2.0
		val atHalf = requireNotNull(run.beforeSignal.minByOrNull { abs(it.totalDistance - halfPoint) })
		assertThat(atHalf.velocity, name = "speed half-way to the signal, $atHalf")
			.isBetween(
				sqrt((v0 * v0 + cap * cap) / 2.0) - SPEED_TOLERANCE_MPS,
				sqrt((v0 * v0 + cap * cap) / 2.0) + SPEED_TOLERANCE_MPS
			)

		val atSignal = run.beforeSignal.last()
		logger.info { "last sample before the signal $atSignal" }
		assertThat(atSignal.velocity, name = "speed at the signal, $atSignal")
			.isBetween(cap - SPEED_TOLERANCE_MPS, cap + SPEED_TOLERANCE_MPS)
	}
}
