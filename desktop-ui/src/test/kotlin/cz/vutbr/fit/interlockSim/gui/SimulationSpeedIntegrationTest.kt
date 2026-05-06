/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Integration tests for simulation speed control (Goal 7, Phase 4.3)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * Integration tests for [SimulationRunner] speed control under real concurrent conditions
 * (Goal 7, Phase 4.3).
 *
 * These tests complement the unit tests in [SimulationRunnerTest] by exercising
 * real multi-threaded scenarios: actual [Thread.sleep] throttling, concurrent
 * speed changes, pause/resume blocking, and stop-under-load.
 *
 * All tests are tagged [Tag] "integration-test" and run via `./gradlew integrationTest`.
 *
 * Acceptance criteria verified:
 * - No crashes or deadlocks during rapid speed changes
 * - Pause/resume works correctly at all speeds
 * - Stop always completes within 5 seconds
 * - No race conditions between EDT and simulation thread
 *
 * Test scenarios:
 * 1. Rapid speed changes (50 changes every 10ms) do not crash or deadlock
 * 2. Pause and resume at 0.1×, 1×, and 10× speed do not deadlock
 * 3. Ten consecutive pause/resume cycles complete cleanly
 * 4. Stop at 0.1× (slow) speed completes within 5 seconds
 * 5. Stop while paused completes within 5 seconds
 * 6. Concurrent speed changes from 4 threads cause no data races
 * 7. Stop at simulated 25%, 50%, 75% completion terminates cleanly
 * 8. Speed changes posted from EDT do not race with the simulation thread
 *
 * @see SimulationRunner
 * @see SimulationRunnerTest for unit-level tests
 */
@Tag("integration-test")
@DisplayName("SimulationSpeed Integration")
class SimulationSpeedIntegrationTest {
	private lateinit var context: SimulationContext
	private lateinit var runner: SimulationRunner

	@BeforeEach
	fun setUp() {
		context = mockk(relaxed = true)
		runner = SimulationRunner(context)
	}

	@AfterEach
	fun tearDown() {
		// Unpause first so the simulation thread can receive the interrupt cleanly.
		runner.isPaused = false
		runner.stop()
	}

	// ── 1. Rapid speed changes ────────────────────────────────────────────────

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("50 rapid speed changes every 10ms do not crash or deadlock")
	fun rapidSpeedChangesNoCrash() {
		val simRunning = CountDownLatch(1)
		every { context.run() } answers {
			simRunning.countDown()
			try {
				while (!Thread.currentThread().isInterrupted) {
					runner.throttle(0.01)
				}
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
		}

		runner.speedMultiplier = 100.0
		runner.start()
		assertThat(simRunning.await(5, TimeUnit.SECONDS)).isTrue()

		val speeds = doubleArrayOf(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 50.0, 100.0)
		val changesDone = CountDownLatch(1)
		Thread {
			try {
				repeat(50) { i ->
					runner.speedMultiplier = speeds[i % speeds.size]
					Thread.sleep(10)
				}
			} catch (_: InterruptedException) {
				// ignored
			} finally {
				changesDone.countDown()
			}
		}.also { it.isDaemon = true; it.start() }

		assertThat(changesDone.await(10, TimeUnit.SECONDS)).isTrue()
		assertThat(runner.isRunning()).isTrue()

		runner.stop()
		assertThat(waitForStop(5_000)).isTrue()
	}

	// ── 2. Pause/resume at different speeds ───────────────────────────────────

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("pause and resume at 0.1x speed do not deadlock")
	fun pauseResumeAt01xSpeed() {
		verifyPauseResumeAtSpeed(0.1)
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("pause and resume at 1x speed do not deadlock")
	fun pauseResumeAt1xSpeed() {
		verifyPauseResumeAtSpeed(1.0)
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("pause and resume at 10x speed do not deadlock")
	fun pauseResumeAt10xSpeed() {
		verifyPauseResumeAtSpeed(10.0)
	}

	// ── 3. Multiple pause/resume cycles ──────────────────────────────────────

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("10 pause/resume cycles complete without deadlock")
	fun multiplePauseResumeCycles() {
		val simRunning = CountDownLatch(1)
		every { context.run() } answers {
			simRunning.countDown()
			try {
				while (!Thread.currentThread().isInterrupted) {
					runner.throttle(0.01)
				}
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
		}

		runner.speedMultiplier = 100.0
		runner.start()
		assertThat(simRunning.await(5, TimeUnit.SECONDS)).isTrue()

		repeat(10) {
			runner.isPaused = true
			assertThat(runner.isPaused).isTrue()
			Thread.sleep(50)
			runner.isPaused = false
			assertThat(runner.isPaused).isFalse()
			Thread.sleep(50)
		}

		assertThat(runner.isRunning()).isTrue()
		runner.stop()
		assertThat(waitForStop(5_000)).isTrue()
	}

	// ── 4. Stop at slow speed ─────────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("stop at 0.1x speed completes within 5 seconds")
	fun stopAtSlowSpeedCompletesWithinFiveSeconds() {
		val simRunning = CountDownLatch(1)
		every { context.run() } answers {
			simRunning.countDown()
			try {
				while (!Thread.currentThread().isInterrupted) {
					runner.throttle(0.1)
				}
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
		}

		// At 0.1x speed each throttle(0.1) sleeps 1000ms; stop() interrupts that sleep.
		runner.speedMultiplier = 0.1
		runner.start()
		assertThat(simRunning.await(5, TimeUnit.SECONDS)).isTrue()

		val stopStart = System.currentTimeMillis()
		runner.stop()
		assertThat(waitForStop(5_000)).isTrue()
		assertThat(System.currentTimeMillis() - stopStart < 5_000L).isTrue()
	}

	// ── 5. Stop while paused ──────────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("stop while paused completes within 5 seconds")
	fun stopWhilePausedCompletesWithinFiveSeconds() {
		val simRunning = CountDownLatch(1)
		every { context.run() } answers {
			simRunning.countDown()
			try {
				while (!Thread.currentThread().isInterrupted) {
					runner.throttle(0.1)
				}
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
		}

		runner.start()
		assertThat(simRunning.await(5, TimeUnit.SECONDS)).isTrue()

		// Pause and give the simulation thread time to enter awaitIfPaused().
		runner.isPaused = true
		Thread.sleep(100)

		val stopStart = System.currentTimeMillis()
		runner.stop()
		assertThat(waitForStop(5_000)).isTrue()
		assertThat(System.currentTimeMillis() - stopStart < 5_000L).isTrue()
	}

	// ── 6. Concurrent speed changes ───────────────────────────────────────────

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("concurrent speed changes from 4 threads cause no data races")
	fun concurrentSpeedChangesFromMultipleThreads() {
		val simRunning = CountDownLatch(1)
		val failure = AtomicReference<Exception?>(null)

		every { context.run() } answers {
			simRunning.countDown()
			try {
				while (!Thread.currentThread().isInterrupted) {
					runner.throttle(0.001)
				}
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
		}

		runner.speedMultiplier = 100.0
		runner.start()
		assertThat(simRunning.await(5, TimeUnit.SECONDS)).isTrue()

		val allReady = CountDownLatch(4)
		val allDone = CountDownLatch(4)
		val speedSets = listOf(
			doubleArrayOf(0.1, 0.5, 1.0),
			doubleArrayOf(2.0, 5.0, 10.0),
			doubleArrayOf(20.0, 50.0, 100.0),
			doubleArrayOf(1.0, 3.0, 7.0),
		)

		speedSets.forEach { speeds ->
			Thread {
				allReady.countDown()
				allReady.await(5, TimeUnit.SECONDS)
				try {
					repeat(20) { i ->
						runner.speedMultiplier = speeds[i % speeds.size]
						Thread.sleep(5)
					}
				} catch (e: Exception) {
					failure.compareAndSet(null, e)
				} finally {
					allDone.countDown()
				}
			}.also { it.isDaemon = true; it.start() }
		}

		assertThat(allDone.await(10, TimeUnit.SECONDS)).isTrue()
		assertThat(failure.get()).isNull()
		assertThat(runner.isRunning()).isTrue()

		runner.stop()
		assertThat(waitForStop(5_000)).isTrue()
	}

	// ── 7. Stop at various progress points ───────────────────────────────────

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("stop at simulated 25% completion terminates cleanly")
	fun stopAt25PercentProgress() {
		verifyStopAfterIterations(25)
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("stop at simulated 50% completion terminates cleanly")
	fun stopAt50PercentProgress() {
		verifyStopAfterIterations(50)
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("stop at simulated 75% completion terminates cleanly")
	fun stopAt75PercentProgress() {
		verifyStopAfterIterations(75)
	}

	// ── 8. Speed changes from EDT ─────────────────────────────────────────────

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("speed changes posted from EDT do not race with simulation thread")
	fun speedChangesFromEdtNoRaceConditions() {
		val simRunning = CountDownLatch(1)
		val failure = AtomicReference<Exception?>(null)

		every { context.run() } answers {
			simRunning.countDown()
			try {
				while (!Thread.currentThread().isInterrupted) {
					runner.throttle(0.001)
				}
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
		}

		runner.speedMultiplier = 100.0
		runner.start()
		assertThat(simRunning.await(5, TimeUnit.SECONDS)).isTrue()

		// Simulate GUI speed changes arriving on the Swing Event Dispatch Thread.
		val edtDone = CountDownLatch(1)
		SwingUtilities.invokeLater {
			try {
				listOf(1.0, 2.0, 5.0, 10.0, 50.0, 100.0, 0.5, 0.1, 1.0).forEach { speed ->
					runner.speedMultiplier = speed
				}
			} catch (e: Exception) {
				failure.set(e)
			} finally {
				edtDone.countDown()
			}
		}

		assertThat(edtDone.await(5, TimeUnit.SECONDS)).isTrue()
		assertThat(failure.get()).isNull()
		assertThat(runner.isRunning()).isTrue()

		runner.stop()
		assertThat(waitForStop(5_000)).isTrue()
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	/**
	 * Start a simulation throttling at [speed], pause it briefly, resume it,
	 * and verify the runner is still alive throughout. No deadlock or crash.
	 */
	private fun verifyPauseResumeAtSpeed(speed: Double) {
		val simRunning = CountDownLatch(1)
		every { context.run() } answers {
			simRunning.countDown()
			try {
				while (!Thread.currentThread().isInterrupted) {
					runner.throttle(0.01)
				}
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
		}

		runner.speedMultiplier = speed
		runner.start()
		assertThat(simRunning.await(5, TimeUnit.SECONDS)).isTrue()

		runner.isPaused = true
		assertThat(runner.isPaused).isTrue()
		// Give the simulation thread time to enter awaitIfPaused() inside throttle().
		Thread.sleep(100)

		runner.isPaused = false
		assertThat(runner.isPaused).isFalse()
		Thread.sleep(50)

		assertThat(runner.isRunning()).isTrue()
		runner.stop()
		assertThat(waitForStop(5_000)).isTrue()
	}

	/**
	 * Run an infinite simulation loop, stop it after at least [iterations] throttle
	 * calls have completed, and verify the runner terminates cleanly.
	 *
	 * This simulates stopping mid-simulation: the infinite loop guarantees the runner
	 * is still active when [SimulationRunner.stop] is called.
	 */
	private fun verifyStopAfterIterations(iterations: Int) {
		val latch = CountDownLatch(iterations)
		every { context.run() } answers {
			try {
				while (!Thread.currentThread().isInterrupted) {
					runner.throttle(0.001)
					if (latch.count > 0L) latch.countDown()
				}
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
		}

		runner.speedMultiplier = 100.0
		runner.start()
		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
		// The infinite loop guarantees the runner is still alive at this point.
		assertThat(runner.isRunning()).isTrue()

		runner.stop()
		assertThat(waitForStop(5_000)).isTrue()
	}

	/**
	 * Polls [SimulationRunner.isRunning] until the runner stops or [timeoutMs] elapses.
	 *
	 * @return `true` if the runner stopped within the timeout, `false` on timeout.
	 */
	private fun waitForStop(timeoutMs: Long): Boolean {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (runner.isRunning()) {
			if (System.currentTimeMillis() >= deadline) return false
			Thread.sleep(50)
		}
		return true
	}
}
