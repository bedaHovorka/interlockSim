/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.SimulationController
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit tests for [DelegatingSimulationController] (SP4.2, Issue #564).
 *
 * Verifies:
 * - Default delegate is [NoOpSimulationController] (safe before the GUI attaches a runner)
 * - [DelegatingSimulationController.throttle], [DelegatingSimulationController.isPaused] and
 *   [DelegatingSimulationController.requestPause] forward to the delegate
 * - [DelegatingSimulationController.pollStepEvent]/[DelegatingSimulationController.pollStepTime]
 *   never consume step requests from the delegate (those belong to the simulation thread)
 * - [DelegatingSimulationController.awaitIfPaused] waits while the delegate is paused and
 *   returns once it resumes, without calling the delegate's `awaitIfPaused`
 * - An [InterruptedException] thrown by the delegate's `throttle` is swallowed with the
 *   interrupt status preserved
 *
 * @since Issue #564 (SP4.2 — Goal 10 close the loop)
 */
@DisplayName("DelegatingSimulationController — late-bound agent-loop pacing")
@Timeout(30, unit = TimeUnit.SECONDS)
class DelegatingSimulationControllerTest {
	/** Recording [SimulationController] fake; configurable pause state and throttle behaviour. */
	private class RecordingDelegate : SimulationController {
		val calls = mutableListOf<String>()

		@Volatile
		var paused: Boolean = false

		@Volatile
		var throttleThrowsInterrupted: Boolean = false
		var lastThrottleDelta: Double? = null

		@Volatile
		var speedMultiplier: Double = 1.0

		override suspend fun awaitIfPaused() {
			calls += "awaitIfPaused"
		}

		override fun throttle(simDeltaSeconds: Double) {
			calls += "throttle"
			lastThrottleDelta = simDeltaSeconds
			if (throttleThrowsInterrupted) throw InterruptedException("test interrupt")
		}

		override fun isPaused(): Boolean = paused

		override fun pollStepEvent(): Boolean {
			calls += "pollStepEvent"
			return true
		}

		override fun pollStepTime(): Double? {
			calls += "pollStepTime"
			return 1.0
		}

		override fun requestPause() {
			calls += "requestPause"
			paused = true
		}

		override fun requestResume() {
			calls += "requestResume"
			paused = false
		}

		override fun currentSpeedMultiplier(): Double {
			calls += "currentSpeedMultiplier"
			return speedMultiplier
		}
	}

	@Nested
	@DisplayName("Default delegate")
	inner class DefaultDelegate {
		@Test
		@DisplayName("delegate defaults to NoOpSimulationController")
		fun defaultsToNoOp() {
			val controller = DelegatingSimulationController()

			assertThat(controller.delegate).isSameInstanceAs(NoOpSimulationController)
			assertThat(controller.isPaused()).isFalse()
		}

		@Test
		@DisplayName("awaitIfPaused returns immediately with the NoOp delegate")
		fun awaitIfPausedReturnsImmediatelyWithNoOp() {
			val controller = DelegatingSimulationController()

			runBlocking { controller.awaitIfPaused() }
			// Reaching this point without a timeout is the assertion.
		}

		@Test
		@DisplayName("currentSpeedMultiplier is 1.0 with the NoOp delegate (Issue #926)")
		fun currentSpeedMultiplierIsOneWithNoOp() {
			val controller = DelegatingSimulationController()

			assertThat(controller.currentSpeedMultiplier()).isEqualTo(1.0)
		}
	}

	@Nested
	@DisplayName("Forwarding to the attached delegate")
	inner class Forwarding {
		@Test
		@DisplayName("throttle forwards the simulation-time delta to the delegate")
		fun throttleForwards() {
			val delegate = RecordingDelegate()
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }

			controller.throttle(2.5)

			assertThat(delegate.calls).containsExactly("throttle")
			assertThat(delegate.lastThrottleDelta).isEqualTo(2.5)
		}

		@Test
		@DisplayName("isPaused reflects the delegate's pause state")
		fun isPausedForwards() {
			val delegate = RecordingDelegate()
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }

			delegate.paused = true
			assertThat(controller.isPaused()).isTrue()
			delegate.paused = false
			assertThat(controller.isPaused()).isFalse()
		}

		@Test
		@DisplayName("requestPause forwards to the delegate")
		fun requestPauseForwards() {
			val delegate = RecordingDelegate()
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }

			controller.requestPause()

			assertThat(delegate.calls).containsExactly("requestPause")
		}

		@Test
		@DisplayName("currentSpeedMultiplier forwards to the delegate (Issue #926)")
		fun currentSpeedMultiplierForwards() {
			val delegate = RecordingDelegate()
			delegate.speedMultiplier = 3.5
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }

			assertThat(controller.currentSpeedMultiplier()).isEqualTo(3.5)
			assertThat(delegate.calls).containsExactly("currentSpeedMultiplier")
		}
	}

	@Nested
	@DisplayName("Step requests are never consumed")
	inner class StepRequestsNeverConsumed {
		@Test
		@DisplayName("pollStepEvent returns false without touching the delegate")
		fun pollStepEventNeverConsumes() {
			val delegate = RecordingDelegate()
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }

			assertThat(controller.pollStepEvent()).isFalse()
			assertThat(delegate.calls).isEmpty()
		}

		@Test
		@DisplayName("pollStepTime returns null without touching the delegate")
		fun pollStepTimeNeverConsumes() {
			val delegate = RecordingDelegate()
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }

			assertThat(controller.pollStepTime()).isNull()
			assertThat(delegate.calls).isEmpty()
		}
	}

	@Nested
	@DisplayName("Pause waiting")
	inner class PauseWaiting {
		@Test
		@DisplayName("awaitIfPaused waits while paused and returns once the delegate resumes")
		fun awaitIfPausedWaitsUntilResumed() {
			val delegate = RecordingDelegate()
			delegate.paused = true
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }
			val resumed = AtomicBoolean(false)

			val unpauser =
				Thread {
					Thread.sleep(50)
					resumed.set(true)
					delegate.paused = false
				}
			unpauser.isDaemon = true
			unpauser.start()

			runBlocking { controller.awaitIfPaused() }

			assertThat(resumed.get()).isTrue()
			// The delegate's own awaitIfPaused must never be called: SimulationRunner's
			// implementation consumes step requests owned by the simulation thread.
			assertThat(delegate.calls).isEmpty()
			unpauser.join()
		}
	}

	@Nested
	@DisplayName("Interrupt handling")
	inner class InterruptHandling {
		@Test
		@DisplayName("throttle swallows InterruptedException and preserves the interrupt flag")
		fun throttlePreservesInterruptFlag() {
			val delegate = RecordingDelegate()
			delegate.throttleThrowsInterrupted = true
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }
			var interrupted = false

			val driverThread =
				Thread {
					controller.throttle(1.0)
					interrupted = Thread.currentThread().isInterrupted
				}
			driverThread.start()
			driverThread.join()

			assertThat(interrupted).isTrue()
		}
	}

	@Nested
	@DisplayName("Resume forwarding (Issue #872)")
	inner class ResumeForwarding {
		@Test
		@DisplayName("requestResume forwards to the delegate")
		fun requestResumeForwards() {
			val delegate = RecordingDelegate()
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }

			controller.requestResume()

			assertThat(delegate.calls).containsExactly("requestResume")
		}

		@Test
		@DisplayName("pause then resume round trip through the delegating controller")
		fun pauseResumeRoundTrip() {
			val delegate = RecordingDelegate()
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }

			controller.requestPause()
			assertThat(controller.isPaused()).isTrue()

			controller.requestResume()
			assertThat(controller.isPaused()).isFalse()
			assertThat(delegate.calls).containsExactly("requestPause", "requestResume")
		}

		@Test
		@DisplayName("requestResume while not paused is a harmless no-op")
		fun resumeWhileNotPausedIsNoOp() {
			val delegate = RecordingDelegate()
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }

			// Delegate starts unpaused; calling resume must not crash or change state
			assertThat(controller.isPaused()).isFalse()
			controller.requestResume()
			assertThat(controller.isPaused()).isFalse()
			assertThat(delegate.calls).containsExactly("requestResume")
		}

		@Test
		@DisplayName("resume from a thread other than the pauser wakes the waiting driver")
		fun resumeFromDifferentThreadWakesWaiter() {
			val delegate = RecordingDelegate()
			delegate.paused = true
			val controller = DelegatingSimulationController().apply { this.delegate = delegate }
			val resumed = AtomicBoolean(false)

			// Resumer thread: a separate thread (not the one that set paused) clears the pause
			val resumerThread =
				Thread {
					Thread.sleep(50)
					resumed.set(true)
					controller.requestResume()
				}
			resumerThread.isDaemon = true
			resumerThread.start()

			// Driver thread blocks in awaitIfPaused until resumed
			runBlocking { controller.awaitIfPaused() }

			assertThat(resumed.get()).isTrue()
			assertThat(controller.isPaused()).isFalse()
			resumerThread.join()
		}
	}
}
