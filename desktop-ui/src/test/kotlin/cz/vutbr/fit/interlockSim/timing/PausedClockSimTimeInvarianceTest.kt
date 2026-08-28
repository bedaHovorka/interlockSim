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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.IntegrationKoinTestBase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * SP2c.26 (Issue #849) evidence — **AC2: is the simulation clock genuinely frozen?**
 *
 * ## Why this does not assert on `obs.simTime`
 *
 * Issue #849 phrases the criterion as "verify `obs.simTime` is genuinely unchanged across a slow
 * emission". Taken literally that is vacuous:
 * [DispatcherObservation][cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation]
 * is an immutable data class handed to `emit` by value, so its `simTime` field *cannot* change no
 * matter what the simulation does. Asserting it would prove nothing about P8 reproducibility.
 *
 * The falsifiable question is whether the **simulation clock itself** stops. That is what these
 * tests measure, via [RecordingPacingController], which reconstructs the clock from the deltas the
 * controlled event loop reports to
 * [SimulationController.throttle][cz.vutbr.fit.interlockSim.context.SimulationController.throttle].
 *
 * ## Two-sided by construction
 *
 * A "the clock did not advance" assertion is worthless on its own — a broken probe reads zero too.
 * [simulationClockIsFrozenWhilePaused] therefore measures the *same* probe over the *same* window
 * length in both states and requires the running case to be non-zero. If the instrumentation were
 * dead, that control would fail.
 *
 * ## Pause latency
 *
 * `DefaultSimulationContext.advanceControlledStep` checks the pause flag at **every event
 * boundary**, so the clock cannot run past the boundary at which the pause is observed.
 * [pauseLatencyStaysWithinOneTickPeriod] bounds the residual — the simulation time that can still
 * elapse between the flag being set and the thread parking — against
 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop]'s `hold(1.0)` tick period, the coarsest scheduled
 * gap in this topology.
 *
 * @since Issue #849 (SP2c.26 — Goal 10 F1 paused-clock spike)
 */
@DisplayName("F1 paused-clock spike — simulation-clock freeze and pause latency (#849)")
@Timeout(120, unit = TimeUnit.SECONDS)
class PausedClockSimTimeInvarianceTest : IntegrationKoinTestBase() {
	@Test
	@DisplayName("AC2: the simulation clock is frozen while paused, and demonstrably advances when not")
	fun simulationClockIsFrozenWhilePaused() {
		PausedClockSpikeHarness.withStarted(get<SimulationContextFactory>(), minTick = 3L) { harness, _ ->
			val controller = harness.controller

			// Control: over a running window of WINDOW_MILLIS the clock must move. This is what makes
			// the frozen-window assertion below meaningful rather than vacuous.
			val runningBefore = controller.observedSimTime
			Thread.sleep(WINDOW_MILLIS)
			val runningAdvance = controller.observedSimTime - runningBefore

			harness.runner.isPaused = true
			awaitPark(harness)

			// Measurement: over an identical window with the clock paused, it must not move at all.
			val pausedBefore = controller.observedSimTime
			Thread.sleep(WINDOW_MILLIS)
			val pausedAdvance = controller.observedSimTime - pausedBefore

			logger.info {
				"Clock advance over ${WINDOW_MILLIS} ms — running: $runningAdvance sim s, paused: $pausedAdvance sim s"
			}

			assertThat(runningAdvance).isGreaterThan(0.0)
			assertThat(pausedAdvance).isEqualTo(0.0)
		}
	}

	@Test
	@DisplayName("AC2: pause latency never exceeds one ShuntingLoop tick period")
	fun pauseLatencyStaysWithinOneTickPeriod() {
		PausedClockSpikeHarness.withStarted(get<SimulationContextFactory>(), minTick = 3L) { harness, _ ->
			val controller = harness.controller
			val latencies = mutableListOf<Double>()

			repeat(PAUSE_CYCLES) {
				val simTimeAtRequest = controller.observedSimTime
				harness.runner.isPaused = true
				awaitPark(harness)
				latencies += (controller.simTimeAtPark ?: error("simulation thread never parked")) - simTimeAtRequest
				harness.runner.isPaused = false
				// Let the run make progress so the next pause lands at a different point in the cycle.
				Thread.sleep(BETWEEN_CYCLES_MILLIS)
			}

			val max = latencies.max()
			logger.info {
				"Pause latency over $PAUSE_CYCLES cycles — max: $max sim s, mean: ${latencies.average()} sim s"
			}

			assertThat(max).isLessThanOrEqualTo(TICK_PERIOD_SIM_SECONDS)
		}
	}

	/** Blocks until the simulation thread records a park, clearing the previous record first. */
	private fun awaitPark(
		harness: PausedClockSpikeHarness,
		timeoutMillis: Long = PARK_WAIT_MILLIS
	) {
		harness.controller.clearParkRecord()
		val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
		while (harness.controller.simTimeAtPark == null && System.nanoTime() < deadline) {
			Thread.sleep(PausedClockSpikeHarness.POLL_MILLIS)
		}
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		/** Observation window used identically for the running control and the paused measurement. */
		private const val WINDOW_MILLIS: Long = 600L

		/** Number of pause/resume cycles sampled for the latency distribution. */
		private const val PAUSE_CYCLES: Int = 20

		/** Progress allowed between latency samples so pauses land at varied points in the tick. */
		private const val BETWEEN_CYCLES_MILLIS: Long = 60L

		/** `ShuntingLoop.iteration` ends with `hold(1.0)` — the coarsest scheduled gap in this topology. */
		private const val TICK_PERIOD_SIM_SECONDS: Double = 1.0

		private const val PARK_WAIT_MILLIS: Long = 5_000L
		private const val NANOS_PER_MILLI: Long = 1_000_000L
	}
}
