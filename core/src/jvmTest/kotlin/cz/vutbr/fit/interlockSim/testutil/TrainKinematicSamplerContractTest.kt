/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Contract tests for the shared train-kinematics sampling helpers.
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Pins the public contracts of [TrainKinematicSampler], [TrainKinematicSample] and
 * [AspectFlipOnce]: the sampler produces one sample per period from activation until the end
 * time with a never-decreasing `totalDistance`, and the flip applies its aspect exactly once
 * on the first matching sample.
 *
 * Every scenario runs the shared [runClearanceStopScenario] chain on
 * [TestTopologies.linearPathWithSemaphoreSimulation]: reserve `A → B`, force the intermediate
 * semaphore to STOP (the reservation would otherwise light it), and sample the single train.
 */
@Tag("integration-test")
@DisplayName("Contract — train kinematics sampling helpers")
class TrainKinematicSamplerContractTest : KoinTestBase() {
	private companion object {
		/** Simulation end time; the train has long parked at the stop line by then. */
		const val END_TIME = 40L

		/** Sampling period shared by every scenario. */
		const val SAMPLE_PERIOD = 0.05

		/** Time tolerance: the sampler's holds are exact, so only floating-point noise remains. */
		const val TIME_TOLERANCE = 1e-6

		/** Proceed aspect the flip test applies. */
		val PROCEED_ASPECT = Signal.S30
	}

	/**
	 * Loads the single-semaphore fixture and registers its context for `tearDownKoin()`, so
	 * every scenario starts from the same context-plus-semaphore pair.
	 */
	private fun loadSemaphoreFixture() =
		TestTopologies.linearPathWithSemaphoreSimulation().tracked().let { ctx ->
			ctx to ctx.cellsOfType<DynamicRailSemaphore>().single()
		}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("cadence: one sample per period, from activation until the end time")
	fun samplesArriveOnTheRequestedCadenceUntilTheEndTime() {
		val (ctx, semaphore) = loadSemaphoreFixture()
		val samples = mutableListOf<TrainKinematicSample>()
		runClearanceStopScenario(
			ctx,
			semaphores = listOf(semaphore),
			endTime = END_TIME,
			samplePeriod = SAMPLE_PERIOD
		) { _, sample ->
			samples += sample
		}

		// The sampler is activated at the train's entry time (t = 1.0) and samples immediately,
		// not one period later.
		assertThat(samples.first().time, name = "first sample time")
			.isBetween(1.0 - TIME_TOLERANCE, 1.0 + TIME_TOLERANCE)
		// Every gap is one period: the hold is unconditional, so no event traffic can stretch it.
		samples.zipWithNext().forEachIndexed { index, (previous, next) ->
			assertThat(next.time - previous.time, name = "gap after sample #$index")
				.isBetween(SAMPLE_PERIOD - TIME_TOLERANCE, SAMPLE_PERIOD + TIME_TOLERANCE)
		}
		// The loop condition is `time() < endTime`: the last sample stays inside the window.
		assertThat(samples.last().time, name = "last sample time")
			.isLessThanOrEqualTo(END_TIME.toDouble())
		// The train parks at the stop line long before the end, so the sampler must keep
		// producing samples until the run ends.
		assertThat(samples.size, name = "sample count").isGreaterThan(700)
		// Distance travelled never decreases, on any scenario.
		val reversals =
			samples.zipWithNext().filter { (previous, next) -> next.totalDistance < previous.totalDistance }
		assertThat(reversals.isEmpty(), name = "monotone totalDistance").isTrue()
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("flip: the aspect is applied once on the first matching sample and never again")
	fun aspectFlipOnceAppliesTheAspectExactlyOnce() {
		val (ctx, semaphore) = loadSemaphoreFixture()
		var flips = 0
		var aspectAtFlip: Signal? = null
		// The semaphore is known before the run, so the flip is a plain val — no nullable
		// hand-off through the sampling callback.
		val flip =
			AspectFlipOnce(
				semaphore,
				PROCEED_ASPECT,
				trigger = { it.velocity == 0.0 && it.totalDistance > 0.0 },
				onFlip = {
					flips++
					// Read back at the flip moment: after the train later exits, the route
					// release legitimately resets the aspect to STOP.
					aspectAtFlip = semaphore.signal
				}
			)
		runClearanceStopScenario(
			ctx,
			semaphores = listOf(semaphore),
			endTime = END_TIME,
			samplePeriod = SAMPLE_PERIOD
		) { _, sample ->
			flip.onSample(sample)
		}

		assertThat(flip.fired, name = "flip fired").isTrue()
		assertThat(aspectAtFlip, name = "aspect applied at the flip").isEqualTo(PROCEED_ASPECT)
		assertThat(flips, name = "flips applied").isEqualTo(1)

		// One-shot: re-feeding a matching sample after the fire must not flip or write again.
		val matchingSample =
			TrainKinematicSample(
				time = END_TIME.toDouble(),
				distanceToSemaphore = 1.0,
				velocity = 0.0,
				totalDistance = 99.0
			)
		flip.onSample(matchingSample)
		assertThat(flips, name = "flips after re-feeding a matching sample").isEqualTo(1)
	}
}
