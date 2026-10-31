/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.ksimulantenbande.kdisco.SimulationEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The guard in [emitTrainJourneyEvent] on a bare kDisco run (Issue #673, Goal 6 SP2): a raw bus
 * listener that fails does not end the emitting process, while kDisco's own process control — a
 * listener terminating the running process — still passes through.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
class TrainJourneyEventEmitTest {
	/** The emitting process; [reachedAfterEmit] tells whether it ran on past the emit. */
	private class Emitter : Process() {
		var reachedAfterEmit = false

		override suspend fun actions() {
			emitTrainJourneyEvent(TrainJourneyEvent.Admitted("T1", time()))
			reachedAfterEmit = true
		}
	}

	private fun runWithListener(
		emitter: Emitter,
		listener: (SimulationEvent) -> Unit
	) = runBlocking {
		val simulation = Simulation.create { Process.activate(emitter) }
		simulation.onEvent { event ->
			if ((event as? SimulationEvent.Custom)?.payload is TrainJourneyEvent) listener(event)
		}
		simulation.run(10.0)
	}

	@Test
	fun `a raw listener that throws does not end the emitting process`() {
		val emitter = Emitter()
		var calls = 0

		runWithListener(emitter) {
			calls++
			error("raw listener failure injected by the test")
		}

		assertThat(calls).isEqualTo(1)
		assertThat(emitter.reachedAfterEmit, "process ran on past the emit").isTrue()
	}

	@Test
	fun `a listener terminating the emitting process still terminates it`() {
		val emitter = Emitter()

		runWithListener(emitter) { emitter.terminate() }

		assertThat(emitter.reachedAfterEmit, "process ran on past its own termination").isFalse()
		assertThat(emitter.terminated(), "process terminated").isTrue()
	}
}
