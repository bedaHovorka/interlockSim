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
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.integrationTestModule
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * SP2c.26 (Issue #849) evidence — **the failure mode the draft ruling omitted**.
 *
 * A paused-clock budget brackets inference with `pause()` … `resume()`. Emission is exactly the
 * step most likely to fail: an LLM call can throw, and
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.DeadlineTickBudget] can abandon it mid-flight via
 * [kotlinx.coroutines.withTimeoutOrNull]. If the resume is not in a `finally`, the simulation stays
 * parked forever — the run does not crash, it silently stops advancing, which is far harder to
 * diagnose than a thrown exception.
 *
 * These two tests establish that the hazard is real and that `finally` is sufficient, so the ruling
 * can state the requirement as a verified constraint rather than a stylistic preference.
 *
 * @since Issue #849 (SP2c.26 — Goal 10 F1 paused-clock spike)
 */
@DisplayName("F1 paused-clock spike — resume must survive emission failure (#849)")
@Timeout(60, unit = TimeUnit.SECONDS)
class PausedClockResumeOnFailureTest : KoinTestBase() {
	override fun getTestModule(): Module = integrationTestModule

	@Test
	@DisplayName("a throwing emission that resumes in finally leaves the simulation running")
	fun throwingEmissionWithFinallyResumesSimulation() {
		val harness = PausedClockSpikeHarness.create(get<SimulationContextFactory>())
		try {
			harness.start()
			val tickBefore = harness.awaitTick(minTick = 2L)

			val tickLoop =
				harness.buildTickLoop { _, _ ->
					harness.runner.isPaused = true
					try {
						throw IllegalStateException("simulated emission failure")
					} finally {
						harness.runner.isPaused = false
					}
				}

			val thrown = runCatching { runBlocking { tickLoop.runTick() } }.exceptionOrNull()

			// The failure surfaces to the caller...
			assertThat(thrown is IllegalStateException).isTrue()
			// ...and the clock is running again, so the run is diagnosable rather than silently frozen.
			assertThat(harness.runner.isPaused).isFalse()
			assertThat(harness.awaitTick(minTick = tickBefore + 1L)).isGreaterThan(tickBefore)
		} finally {
			harness.stop()
		}
	}

	@Test
	@DisplayName("a throwing emission without finally leaves the simulation parked indefinitely")
	fun throwingEmissionWithoutFinallyLeavesSimulationParked() {
		val harness = PausedClockSpikeHarness.create(get<SimulationContextFactory>())
		try {
			harness.start()
			harness.awaitTick(minTick = 2L)

			val tickLoop =
				harness.buildTickLoop { _, _ ->
					harness.runner.isPaused = true
					throw IllegalStateException("simulated emission failure")
				}

			runCatching { runBlocking { tickLoop.runTick() } }

			val tickAfterFailure = harness.latest().tick
			Thread.sleep(OBSERVATION_MILLIS)

			// Still parked: nothing in the stack unwinds the pause on its own. This is the hazard.
			assertThat(harness.runner.isPaused).isTrue()
			assertThat(harness.latest().tick).isEqualTo(tickAfterFailure)
		} finally {
			harness.stop()
		}
	}

	companion object {
		/** Several ticks' worth of wall-clock at the harness's 10x speed. */
		private const val OBSERVATION_MILLIS: Long = 750L
	}
}
