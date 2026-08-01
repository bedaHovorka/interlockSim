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
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * SP2c.26 (Issue #849) evidence — **AC1: pause → emit → resume, both sides of the boundary**.
 *
 * This is the F1 prototype the issue asks for, run against a real
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop] over a real `vyhybna.xml` simulation.
 * It settles the design question with two complementary tests:
 *
 * 1. **The forbidden design.** An emission that pauses the clock and then waits for a *fresh*
 *    sim-thread capture never gets one — the pause parked the only thread that could publish it
 *    ([PausedClockCaptureHookTest] pins down why). The tick still completes here only because the
 *    prototype releases the pause in a `finally`; an implementation that waited unbounded while
 *    holding the pause would hang the run outright.
 *
 * 2. **The viable design.** An emission that pauses the clock and works solely from the immutable
 *    `obs0` it was handed — SP2c.5's option A, already how `DispatchTickLoop.applyOptimistically`
 *    re-validates — completes normally, and the tick it records carries the pre-pause simulation
 *    time even though the emission took hundreds of wall-clock milliseconds.
 *
 * Together these make SP2c.5's optimistic-projection choice **evidence-based rather than assumed**,
 * which is precisely what Issue #849 §2 asks the spike to establish.
 *
 * @since Issue #849 (SP2c.26 — Goal 10 F1 paused-clock spike)
 */
@DisplayName("F1 paused-clock spike — pause → emit → resume through DispatchTickLoop (#849)")
@Timeout(60, unit = TimeUnit.SECONDS)
class PausedClockFreshCaptureDeadlockTest : KoinTestBase() {
	override fun getTestModule(): Module = integrationTestModule

	@Test
	@DisplayName("AC1: a paused emit window can never obtain a fresh sim-thread capture")
	fun pausedEmitCannotObtainFreshCapture() {
		val harness = PausedClockSpikeHarness.create(get<SimulationContextFactory>())
		val sawFreshCapture = AtomicBoolean(false)
		try {
			harness.start()
			harness.awaitTick(minTick = 2L)

			val tickLoop =
				harness.buildTickLoop { _, observation ->
					harness.runner.isPaused = true
					try {
						sawFreshCapture.set(
							harness.awaitFreshCapture(afterTick = observation.tick, timeoutMillis = FRESH_CAPTURE_WAIT_MILLIS)
						)
					} finally {
						// Releasing here is what keeps this test from hanging. It is also the
						// mandatory shape for any real F1 budget — see PausedClockResumeOnFailureTest.
						harness.runner.isPaused = false
					}
					emptyList()
				}

			val record = runBlocking { tickLoop.runTick() }

			// Reproduces the hazard: with the pause held the sim thread parks in awaitIfPaused before the
			// next snapshotCaptureHook, so no fresh tick is published and this stays false. The one
			// theoretical false-fail: if isPaused is set during the microsecond window the sim thread is
			// mid-event, it can publish one more tick before parking, flipping this true. The window is
			// ~us against a ~100 ms tick at 10x, so this is a flake risk only — and a false-fail, never a
			// false-pass, so the ruling is unaffected. Re-run if it ever fires.
			assertThat(sawFreshCapture.get()).isFalse()
			// The tick itself still completed: the deadlock is in waiting for the capture, not in
			// pausing as such.
			assertThat(record).isNotNull()
		} finally {
			harness.stop()
		}
	}

	@Test
	@DisplayName("AC1/AC2: a paused emit that uses only obs0 completes, and its tick keeps the pre-pause simTime")
	fun pausedEmitOnObservationSnapshotCompletes() {
		val harness = PausedClockSpikeHarness.create(get<SimulationContextFactory>())
		val observedSimTime = AtomicReference(0.0)
		val emitWallClockMillis = AtomicLong(0L)
		try {
			harness.start()
			harness.awaitTick(minTick = 2L)

			val tickLoop =
				harness.buildTickLoop { _, observation ->
					val startedAt = System.nanoTime()
					harness.runner.isPaused = true
					try {
						// Stand-in for LLM inference: slow, and reading only the immutable snapshot.
						Thread.sleep(SIMULATED_INFERENCE_MILLIS)
						observedSimTime.set(observation.simTime)
					} finally {
						harness.runner.isPaused = false
					}
					emitWallClockMillis.set((System.nanoTime() - startedAt) / NANOS_PER_MILLI)
					emptyList()
				}

			val record = runBlocking { tickLoop.runTick() }

			assertThat(record).isNotNull()
			assertThat(emitWallClockMillis.get()).isGreaterThanOrEqualTo(SIMULATED_INFERENCE_MILLIS)
			// The recorded tick carries the simulation time of the snapshot the decision was made
			// from — not whatever the clock reached afterwards.
			assertThat(record?.simTime).isEqualTo(observedSimTime.get())
		} finally {
			harness.stop()
		}
	}

	companion object {
		/** Bounded wait for a fresh capture inside the paused emit window — several ticks' worth at 10x. */
		private const val FRESH_CAPTURE_WAIT_MILLIS: Long = 750L

		/** Stand-in for a slow LLM emission, long enough to dwarf the harness's ~100 ms tick period. */
		private const val SIMULATED_INFERENCE_MILLIS: Long = 400L

		private const val NANOS_PER_MILLI: Long = 1_000_000L
	}
}
