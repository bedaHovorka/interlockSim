/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.timing

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.integrationTestModule
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * SP2c.26 (Issue #849) evidence — **AC4: how pausing interacts with real-time sync and speed**.
 *
 * Issue #849 asks how pausing and resuming on *every* tick interacts with `SimulationRunner`'s
 * real-time synchronisation and the speed multiplier. Three properties are checked per speed:
 *
 * 1. **Orthogonality** — a pause/resume cycle leaves [SimulationRunner.speedMultiplier] untouched.
 * 2. **Rate fidelity** — the simulation clock advances at approximately `speed x` wall-clock, so
 *    the multiplier still means what it means once F1 pauses are in play.
 * 3. **No catch-up debt** — the post-pause rate matches the pre-pause rate.
 *    [SimulationRunner.throttle] sleeps proportionally to the simulation delta and keeps no
 *    wall-clock deadline, so a pause cannot leave the run "behind schedule" and cause it to
 *    sprint afterwards. That matters for F1: an inference pause must not be repaid by a burst of
 *    unthrottled simulation, which would break the pacing guarantee the 2x cap exists to provide.
 *
 * ## Measurement quantisation — why the tolerances are what they are
 *
 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop.iteration] ends with `hold(1.0)`, so the simulation
 * clock advances in whole-second steps. A sampling window of W seconds therefore resolves the rate
 * only to within `1.0 / W` sim s/s, and a measured rate sits on that lattice rather than exactly on
 * `speed`. The window below is sized so that quantum stays small relative to the slowest speed
 * tested, and the catch-up check is expressed **against the quantum** instead of against an
 * arbitrary percentage — a one-quantum difference between the before and after samples is the
 * measurement grid, not the simulation sprinting.
 *
 * @since Issue #849 (SP2c.26 — Goal 10 F1 paused-clock spike)
 */
@DisplayName("F1 paused-clock spike — speed-multiplier and real-time-sync interaction (#849)")
@Timeout(120, unit = TimeUnit.SECONDS)
class PausedClockSpeedMultiplierInteractionTest : KoinTestBase() {
	override fun getTestModule(): Module = integrationTestModule

	@ParameterizedTest(name = "speed {0}x")
	@ValueSource(doubles = [1.0, 2.0, 5.0])
	@DisplayName("AC4: pausing is orthogonal to the speed multiplier and leaves no catch-up debt")
	fun pauseIsOrthogonalToSpeed(speed: Double) {
		val harness = PausedClockSpikeHarness.create(get<SimulationContextFactory>(), speedMultiplier = speed)
		try {
			harness.start()
			harness.awaitTick(minTick = 2L)
			val controller = harness.controller

			val beforeRate = measureRate(controller)

			harness.runner.isPaused = true
			Thread.sleep(PAUSE_MILLIS)
			harness.runner.isPaused = false
			// Let the run settle back into its cadence before sampling again.
			Thread.sleep(SETTLE_MILLIS)

			val afterRate = measureRate(controller)
			logger.info {
				"Speed ${speed}x — clock rate before pause: $beforeRate sim s/s, after resume: $afterRate sim s/s " +
					"(measurement quantum ${QUANTUM_RATE} sim s/s)"
			}

			// 1. Orthogonality.
			assertThat(harness.runner.speedMultiplier).isEqualTo(speed)
			// 2. Rate fidelity: the clock tracks the requested multiplier.
			assertThat(beforeRate).isBetween(speed * RATE_TOLERANCE_LOW, speed * RATE_TOLERANCE_HIGH)
			assertThat(afterRate).isBetween(speed * RATE_TOLERANCE_LOW, speed * RATE_TOLERANCE_HIGH)
			// 3. No catch-up debt: after a pause the rate returns to where it was, to within the
			// measurement quantum. A run repaying the paused wall-clock time would show a markedly
			// HIGHER after-rate, which this bound would reject.
			assertThat(abs(afterRate - beforeRate)).isLessThanOrEqualTo(QUANTUM_RATE * QUANTUM_ALLOWANCE)
		} finally {
			harness.stop()
		}
	}

	/** Simulation seconds advanced per wall-clock second over [WINDOW_MILLIS]. */
	private fun measureRate(controller: RecordingPacingController): Double {
		val before = controller.observedSimTime
		Thread.sleep(WINDOW_MILLIS)
		return (controller.observedSimTime - before) / (WINDOW_MILLIS / MILLIS_PER_SECOND)
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		private const val MILLIS_PER_SECOND: Double = 1000.0

		/**
		 * Sampling window. At 3 s the quantisation floor is 1/3 sim s/s — a third of the slowest
		 * speed tested (1x), which keeps the rate-fidelity band meaningful rather than dominated by
		 * the measurement grid.
		 */
		private const val WINDOW_MILLIS: Long = 3_000L

		/** Rate resolution imposed by `ShuntingLoop`'s `hold(1.0)` over [WINDOW_MILLIS]. */
		private const val QUANTUM_RATE: Double = 1.0 / (WINDOW_MILLIS / MILLIS_PER_SECOND)

		/**
		 * How many quanta the before/after rates may differ by. Two: each of the two samples can
		 * independently land on either side of the lattice, so a one-quantum error in each is
		 * expected even with no catch-up behaviour whatsoever.
		 */
		private const val QUANTUM_ALLOWANCE: Double = 2.0

		/** Pause long enough that any catch-up behaviour would be unmistakable in the after-rate. */
		private const val PAUSE_MILLIS: Long = 800L

		private const val SETTLE_MILLIS: Long = 200L

		/**
		 * Rate tolerance band. Generous on purpose: the measurement competes with JIT warm-up, GC and
		 * OS scheduling on shared CI hardware, and the property under test is "the multiplier is still
		 * honoured", not a precise timing benchmark.
		 */
		private const val RATE_TOLERANCE_LOW: Double = 0.5
		private const val RATE_TOLERANCE_HIGH: Double = 1.5
	}
}
