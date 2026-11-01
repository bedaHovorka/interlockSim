/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Guard around RunAccountingEvent delivery (Issue #1304).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * The typed [RunAccountingEvent] subscriber behaves like the [TrainJourneyEvent] one (Issue #1304): a
 * listener that fails does not end the emitting process, while kDisco's process control -- a
 * cancellation, a listener terminating the running process -- still unwinds it.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
class RunAccountingEventEmitTest {
	private companion object {
		const val TEST_TRAIN_NAME = "T1"
	}

	private class Emitter : Process() {
		var reachedAfterEmit = false

		override suspend fun actions() {
			emitRunAccountingEvent(RunAccountingEvent.RouteGranted(TEST_TRAIN_NAME, time()))
			reachedAfterEmit = true
		}
	}

	private fun runWithListener(
		emitter: Emitter,
		listener: (RunAccountingEvent) -> Unit
	) = runBlocking {
		val simulation = Simulation.create { Process.activate(emitter) }
		simulation.onEvent(runAccountingRecipient(listener))
		simulation.run(10.0)
	}

	@Test
	fun `a listener that throws does not end the emitting process`() {
		val emitter = Emitter()
		var calls = 0

		runWithListener(emitter) {
			calls++
			error("listener failure injected by the test")
		}

		assertThat(calls).isEqualTo(1)
		assertThat(emitter.reachedAfterEmit, "process ran on past the emit").isTrue()
	}

	@Test
	fun `a listener cancelling is rethrown and unwinds the emitting process`() {
		val emitter = Emitter()

		runWithListener(emitter) { throw CancellationException("cancellation injected by the test") }

		assertThat(emitter.reachedAfterEmit, "process ran past the emit after a cancellation").isFalse()
	}

	@Test
	fun `a listener terminating the emitting process still terminates it`() {
		val emitter = Emitter()

		runWithListener(emitter) { emitter.terminate() }

		assertThat(emitter.reachedAfterEmit, "process ran on past its own termination").isFalse()
		assertThat(emitter.terminated(), "process terminated").isTrue()
	}
}
