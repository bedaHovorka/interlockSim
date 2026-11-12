/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
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
import cz.vutbr.fit.interlockSim.util.Util
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * The guards around [TrainJourneyEvent] delivery on a bare kDisco run (Issue #673, Goal 6 SP2): a
 * raw bus listener or a typed [TrainJourneyEvent] listener that fails does not end the emitting
 * process, while kDisco's own process control — a listener terminating the running process — still
 * passes through both guards.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
class TrainJourneyEventEmitTest {
	/** The train the typed tests carry; a constant, so a rename fails this compilation. */
	private companion object {
		const val TEST_TRAIN_NAME = "T1"
	}

	/** The emitting process; [reachedAfterEmit] tells whether it ran on past the emit. */
	private class Emitter : Process() {
		var reachedAfterEmit = false

		override suspend fun actions() {
			emitTrainJourneyEvent(TrainJourneyEvent.Admitted(TEST_TRAIN_NAME, time()))
			reachedAfterEmit = true
		}
	}

	/** Runs [emitter] on a bare kDisco `Simulation`, with [register] adding the listener under test. */
	private fun runWithListener(
		emitter: Emitter,
		register: (Simulation) -> Unit
	) = runBlocking {
		val simulation = Simulation.create { Process.activate(emitter) }
		register(simulation)
		simulation.run(10.0)
	}

	@Test
	fun `a raw listener that throws does not end the emitting process`() {
		val emitter = Emitter()
		var calls = 0

		runWithListener(emitter) { simulation ->
			simulation.onEvent {
				if ((it as? SimulationEvent.Custom)?.payload is TrainJourneyEvent) {
					calls++
					error("raw listener failure injected by the test")
				}
			}
		}

		assertThat(calls).isEqualTo(1)
		assertThat(emitter.reachedAfterEmit, "process ran on past the emit").isTrue()
	}

	@Test
	fun `a listener terminating the emitting process still terminates it`() {
		val emitter = Emitter()

		runWithListener(emitter) { simulation ->
			simulation.onEvent {
				if ((it as? SimulationEvent.Custom)?.payload is TrainJourneyEvent) emitter.terminate()
			}
		}

		assertThat(emitter.reachedAfterEmit, "process ran on past its own termination").isFalse()
		assertThat(emitter.terminated(), "process terminated").isTrue()
	}

	@Test
	fun `a raw listener cancelling is rethrown by the emit guard`() {
		val emitter = Emitter()

		runWithListener(emitter) { simulation ->
			simulation.onEvent {
				if ((it as? SimulationEvent.Custom)?.payload is TrainJourneyEvent) {
					throw CancellationException("cancellation injected by the test")
				}
			}
		}

		assertThat(emitter.reachedAfterEmit, "process ran past the emit after a cancellation").isFalse()
	}

	@Test
	fun `a typed listener that throws does not end the emitting process`() {
		val emitter = Emitter()
		var calls = 0

		runWithListener(emitter) { simulation ->
			simulation.onEvent(
				trainJourneyRecipient {
					calls++
					error("typed listener failure injected by the test")
				}
			)
		}

		assertThat(calls).isEqualTo(1)
		assertThat(emitter.reachedAfterEmit, "process ran on past the emit").isTrue()
	}

	@Test
	fun `a typed listener terminating the emitting process still terminates it`() {
		val emitter = Emitter()

		runWithListener(emitter) { simulation ->
			simulation.onEvent(trainJourneyRecipient { emitter.terminate() })
		}

		assertThat(emitter.reachedAfterEmit, "process ran on past its own termination").isFalse()
		assertThat(emitter.terminated(), "process terminated").isTrue()
	}

	@Test
	fun `a typed listener cancelling is rethrown by the guard`() {
		val guarded =
			trainJourneyGuard {
				throw CancellationException("cancellation injected by the test")
			}

		val caught = runCatching { guarded(TrainJourneyEvent.Admitted(TEST_TRAIN_NAME, 1.0)) }
		val thrown = caught.exceptionOrNull() ?: error("the guard swallowed the cancellation")

		Util.assertInstanceOf<CancellationException>(thrown)
	}
}
