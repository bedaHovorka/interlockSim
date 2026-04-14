/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for SimulationRunner (Issue #188)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@DisplayName("SimulationRunner")
class SimulationRunnerTest {
	private lateinit var context: SimulationContext
	private lateinit var runner: SimulationRunner

	@BeforeEach
	fun setUp() {
		context = mockk(relaxed = true)
		runner = SimulationRunner(context)
	}

	@Test
	@DisplayName("defaults: speedMultiplier=1.0, isPaused=false, not running")
	fun defaults() {
		assertThat(runner.speedMultiplier).isEqualTo(1.0)
		assertThat(runner.isPaused).isFalse()
		assertThat(runner.isRunning()).isFalse()
	}

	@Test
	@DisplayName("speed 0.09 below lower bound throws")
	fun speedBelowLowerBoundThrows() {
		assertThrows(IllegalArgumentException::class.java) { runner.speedMultiplier = 0.09 }
	}

	@Test
	@DisplayName("speed 0.1 accepted (inclusive lower bound)")
	fun speedLowerBoundAccepted() {
		runner.speedMultiplier = 0.1
		assertThat(runner.speedMultiplier).isEqualTo(0.1)
	}

	@Test
	@DisplayName("speed 1.0 accepted")
	fun speedOneAccepted() {
		runner.speedMultiplier = 1.0
		assertThat(runner.speedMultiplier).isEqualTo(1.0)
	}

	@Test
	@DisplayName("speed 100.0 accepted (inclusive upper bound)")
	fun speedUpperBoundAccepted() {
		runner.speedMultiplier = 100.0
		assertThat(runner.speedMultiplier).isEqualTo(100.0)
	}

	@Test
	@DisplayName("speed 100.1 above upper bound throws")
	fun speedAboveUpperBoundThrows() {
		assertThrows(IllegalArgumentException::class.java) { runner.speedMultiplier = 100.1 }
	}

	@Test
	@DisplayName("PropertyChangeListener fires on speedMultiplier change with old/new values")
	fun speedChangeFiresListener() {
		val events = mutableListOf<PropertyChangeEvent>()
		runner.addPropertyChangeListener(SimulationRunner.PROP_SPEED_MULTIPLIER, PropertyChangeListener { events.add(it) })

		runner.speedMultiplier = 2.5

		assertThat(events.size).isEqualTo(1)
		val evt = events[0]
		assertThat(evt.propertyName).isEqualTo(SimulationRunner.PROP_SPEED_MULTIPLIER)
		assertThat(evt.oldValue as Double).isEqualTo(1.0)
		assertThat(evt.newValue as Double).isEqualTo(2.5)
	}

	@Test
	@DisplayName("speed listener not fired when value unchanged")
	fun speedUnchangedDoesNotFire() {
		val events = mutableListOf<PropertyChangeEvent>()
		runner.addPropertyChangeListener(PropertyChangeListener { events.add(it) })

		runner.speedMultiplier = 1.0 // same as default

		assertThat(events.size).isEqualTo(0)
	}

	@Test
	@DisplayName("PropertyChangeListener fires on isPaused change")
	fun pausedChangeFiresListener() {
		val events = mutableListOf<PropertyChangeEvent>()
		runner.addPropertyChangeListener(SimulationRunner.PROP_IS_PAUSED, PropertyChangeListener { events.add(it) })

		runner.isPaused = true

		assertThat(events.size).isEqualTo(1)
		assertThat(events[0].oldValue as Boolean).isFalse()
		assertThat(events[0].newValue as Boolean).isTrue()
	}

	@Test
	@DisplayName("isPaused unchanged does not fire")
	fun pausedUnchangedDoesNotFire() {
		val events = mutableListOf<PropertyChangeEvent>()
		runner.addPropertyChangeListener(PropertyChangeListener { events.add(it) })

		runner.isPaused = false // same as default

		assertThat(events.size).isEqualTo(0)
	}

	@Test
	@DisplayName("removePropertyChangeListener stops notifications")
	fun removeListener() {
		val events = mutableListOf<PropertyChangeEvent>()
		val listener = PropertyChangeListener { events.add(it) }
		runner.addPropertyChangeListener(listener)
		runner.speedMultiplier = 2.0
		runner.removePropertyChangeListener(listener)
		runner.speedMultiplier = 3.0

		assertThat(events.size).isEqualTo(1)
	}

	@Test
	@DisplayName("start invokes context.run on background thread")
	fun startInvokesContextRun() {
		val started = CountDownLatch(1)
		val finish = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			finish.await(5, TimeUnit.SECONDS)
		}

		runner.start()
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
		assertThat(runner.isRunning()).isTrue()

		finish.countDown()
		runner.stop()
		verify { context.run() }
	}

	@Test
	@DisplayName("start is idempotent while thread alive")
	fun startIdempotent() {
		val started = CountDownLatch(1)
		val finish = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			finish.await(5, TimeUnit.SECONDS)
		}

		runner.start()
		started.await(5, TimeUnit.SECONDS)
		runner.start() // second call — should not invoke context.run again
		runner.start()

		finish.countDown()
		runner.stop()
		verify(exactly = 1) { context.run() }
	}

	@Test
	@DisplayName("stop on never-started runner is safe")
	fun stopIdempotentOnUnstarted() {
		runner.stop() // no exception
		runner.stop()
		assertThat(runner.isRunning()).isFalse()
	}

	@Test
	@DisplayName("stop interrupts sim thread and terminates it")
	fun stopInterruptsSimThread() {
		val started = CountDownLatch(1)
		val interrupted = CountDownLatch(1)
		every { context.run() } answers {
			started.countDown()
			try {
				Thread.sleep(60_000)
			} catch (e: InterruptedException) {
				interrupted.countDown()
				Thread.currentThread().interrupt()
			}
		}

		runner.start()
		started.await(5, TimeUnit.SECONDS)
		runner.stop()

		assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue()
	}

	@Test
	@DisplayName("awaitIfPaused blocks while paused and resumes on unpause")
	fun pauseThenResume() {
		runner.isPaused = true

		val resumed = CountDownLatch(1)
		val waiter = Thread {
			try {
				runner.awaitIfPaused()
				resumed.countDown()
			} catch (_: InterruptedException) {
				// ignored
			}
		}
		waiter.isDaemon = true
		waiter.start()

		// Give the waiter time to enter the wait
		Thread.sleep(100)
		assertThat(resumed.count).isEqualTo(1L)

		runner.isPaused = false
		assertThat(resumed.await(5, TimeUnit.SECONDS)).isTrue()
		waiter.join(1000)
	}

	@Test
	@DisplayName("awaitIfPaused is a no-op when not paused")
	fun awaitIfPausedNoop() {
		// should return promptly
		runner.awaitIfPaused()
	}

	@Test
	@DisplayName("throttle with non-positive delta is a no-op")
	fun throttleNonPositive() {
		runner.throttle(0.0)
		runner.throttle(-1.0)
		// no sleep, no exception
	}

	@Test
	@DisplayName("throttle sleeps roughly simDelta / speed seconds")
	fun throttleSleeps() {
		runner.speedMultiplier = 10.0 // 100ms for 1.0s delta
		val startNs = System.nanoTime()
		runner.throttle(1.0)
		val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
		// Allow generous timing slack; must have slept at least ~80ms, at most ~500ms
		assertThat(elapsedMs >= 80).isTrue()
		assertThat(elapsedMs < 500).isTrue()
	}
}
