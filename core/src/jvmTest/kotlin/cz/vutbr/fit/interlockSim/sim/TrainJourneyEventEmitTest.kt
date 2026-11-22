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
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.deliverContained
import cz.vutbr.fit.interlockSim.testutil.warnMessages
import cz.vutbr.fit.interlockSim.testutil.withLogOf
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
 * The context's delivery guard [deliverContained] (Issue #1361) is pinned in the same spirit: behind
 * it a plain failure still reaches the listener registered after the failing one (logged at WARN),
 * while a cancellation leaves it as the very instance thrown and a termination still ends the
 * emitting process.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
class TrainJourneyEventEmitTest {
	/** The train the typed tests carry; a constant, so a rename fails this compilation. */
	private companion object {
		const val TEST_TRAIN_NAME = "T1"

		/** The file logger that [deliverContained] logs its containment at: kotlin-logging names a
		 * file-level logger after its file, without the `Kt` suffix (Issue #1324 moved it to `context/`). */
		const val DELIVERY_GUARD_LOGGER = "cz.vutbr.fit.interlockSim.context.SimulationEventGuard"
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

	/**
	 * The delivery chain of the context's typed listeners (Issue #1361): every listener of [chain] gets
	 * the payload behind [deliverContained], in order, as `DefaultSimulationContext` wraps each listener
	 * for a payload type. On this bare bus the chain is the one listener registered, so a payload the
	 * chain does not carry goes nowhere else.
	 */
	private fun deliveryChain(chain: List<(TrainJourneyEvent) -> Unit>): (SimulationEvent) -> Unit =
		{ event ->
			val payload = (event as? SimulationEvent.Custom)?.payload
			if (payload is TrainJourneyEvent) chain.forEach { deliverContained(it, payload) }
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
		val recipient =
			trainJourneyRecipient {
				throw CancellationException("cancellation injected by the test")
			}

		val caught = runCatching { recipient(SimulationEvent.Custom(1.0, TrainJourneyEvent.Admitted(TEST_TRAIN_NAME, 1.0))) }
		val thrown = caught.exceptionOrNull() ?: error("the guard swallowed the cancellation")

		Util.assertInstanceOf<CancellationException>(thrown)
	}

	/**
	 * The containment branch of [deliverContained] (Issue #1361): the plain failure (Issue #673) is
	 * logged at WARN and never leaves the guard, so the listener after the failing one still receives
	 * the payload and the emitting process carries on.
	 */
	@Test
	fun `deliverContained contains a plain failure and the next listener still receives the payload`() {
		val emitter = Emitter()
		var calls = 0
		val seen = mutableListOf<TrainJourneyEvent>()

		withLogOf(DELIVERY_GUARD_LOGGER) { appender ->
			runWithListener(emitter) { simulation ->
				simulation.onEvent(
					deliveryChain(
						listOf(
							{ _ ->
								calls++
								error("chain listener failure injected by the test")
							},
							{ seen += it }
						)
					)
				)
			}
			val containmentWarnings =
				appender.warnMessages().filter { it.startsWith("A simulation event listener failed on ") }
			assertThat(containmentWarnings, "the containment WARN line").hasSize(1)
			assertThat(containmentWarnings.single()).contains("(Issue #1361)")
		}

		assertThat(calls, "the failing listener threw").isEqualTo(1)
		assertThat(seen, "the next listener still receives the payload").hasSize(1)
		assertThat(seen.single().trainId, "the payload the next listener got").isEqualTo(TEST_TRAIN_NAME)
		assertThat(emitter.reachedAfterEmit, "process ran on past the emit").isTrue()
	}

	/**
	 * The rethrow branch of [deliverContained] (Issue #1361): a cancellation is kDisco's process control
	 * ([cz.vutbr.fit.interlockSim.context.rethrowProcessControl]), so the very instance the listener threw leaves the guard.
	 */
	@Test
	fun `a listener cancelling leaves deliverContained as the very instance thrown`() {
		val cancellation = CancellationException("cancellation injected by the test")

		val caught =
			runCatching {
				deliverContained({ throw cancellation }, TrainJourneyEvent.Admitted(TEST_TRAIN_NAME, 1.0))
			}

		assertThat(caught.exceptionOrNull(), "the exception that escaped").isSameInstanceAs(cancellation)
	}

	/**
	 * The rethrow branch of [deliverContained] with kDisco's own `ProcessTerminatedException` (Issue
	 * #1361): a listener terminating the process unwinds the whole chain, so the listener after it
	 * misses the payload and the process is really terminated, not WARN-continued.
	 */
	@Test
	fun `a listener terminating the process leaves deliverContained and the next listener misses the payload`() {
		val emitter = Emitter()
		var calls = 0
		val seen = mutableListOf<TrainJourneyEvent>()

		runWithListener(emitter) { simulation ->
			simulation.onEvent(
				deliveryChain(
					listOf(
						{ _ ->
							calls++
							emitter.terminate()
						},
						{ seen += it }
					)
				)
			)
		}

		assertThat(calls, "the terminating listener had the payload").isEqualTo(1)
		assertThat(seen, "the next listener missed the payload").isEmpty()
		assertThat(emitter.reachedAfterEmit, "process ran on past its own termination").isFalse()
		assertThat(emitter.terminated(), "process terminated").isTrue()
	}
}
