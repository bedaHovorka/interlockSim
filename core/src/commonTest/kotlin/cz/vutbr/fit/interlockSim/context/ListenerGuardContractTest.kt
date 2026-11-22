/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.ksimulantenbande.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.context.navigation.SwitchClaimsReleased
import cz.vutbr.fit.interlockSim.context.navigation.reservationExpiredRecipient
import cz.vutbr.fit.interlockSim.context.navigation.switchClaimsReleasedRecipient
import cz.vutbr.fit.interlockSim.sim.RunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.RunEnded
import cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent
import cz.vutbr.fit.interlockSim.sim.runAccountingRecipient
import cz.vutbr.fit.interlockSim.sim.runEndedRecipient
import cz.vutbr.fit.interlockSim.sim.trainJourneyRecipient
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test

/**
 * The one listener-guard contract of the typed event lanes (Issue #1324), pinned per lane on a bare kDisco
 * `Simulation`, without a [SimulationEnvironment] and so without the context's own delivery guard: the raw
 * listener that each typed helper (`onTrainJourneyEvent`, `onRunAccountingEvent`, `onReservationExpired`,
 * `onSwitchClaimsReleased`, `onRunEnded`) hands to [SimulationEnvironment.onSimulationEvent] contains a
 * plain failure of its listener -- the process that emitted the payload carries on and the subscriber after
 * it still gets the payload -- and lets kDisco's process control pass: a listener terminating the emitting
 * process terminates it, a cancellation unwinds it as the very instance thrown.
 *
 * In `commonTest`, so linuxX64 pins the recognition of kDisco's `ProcessTerminatedException` by name as
 * well as the JVM does.
 *
 * @since Issue #1324
 */
class ListenerGuardContractTest {
	private companion object {
		const val TEST_TRAIN_NAME = "T1"
		const val END_TIME = 10.0
	}

	/**
	 * One typed lane: [payload] is a payload the lane carries, [recipient] builds the raw bus listener its
	 * helper registers around a listener body.
	 */
	private class Lane(
		val name: String,
		val payload: Any,
		val recipient: (body: () -> Unit) -> (SimulationEvent) -> Unit
	)

	private val lanes =
		listOf(
			Lane("TrainJourneyEvent", TrainJourneyEvent.Admitted(TEST_TRAIN_NAME, 0.0)) { body ->
				trainJourneyRecipient { body() }
			},
			Lane("RunAccountingEvent", RunAccountingEvent.EndTimeReached(0.0)) { body ->
				runAccountingRecipient { body() }
			},
			Lane(
				"ReservationExpired",
				ReservationExpired(
					trainId = TEST_TRAIN_NAME,
					kind = ReservationExpired.Kind.WHOLE_HOLDING,
					releasedBlocks = emptyList(),
					keptBlocks = emptyList(),
					stalledForSimSeconds = 1.0,
					ttlSimSeconds = 1.0,
					time = 0.0
				)
			) { body -> reservationExpiredRecipient { body() } },
			Lane("SwitchClaimsReleased", SwitchClaimsReleased(TEST_TRAIN_NAME, emptyList(), 0.0)) { body ->
				switchClaimsReleasedRecipient { body() }
			},
			Lane("RunEnded", RunEnded(0.0, endTimeReached = true, wallSeconds = 0.0)) { body ->
				runEndedRecipient { body() }
			}
		)

	/** Emits [payload] with kDisco's own, unguarded `emitCustom`; [reachedAfterEmit] tells whether it ran on. */
	private class Emitter(
		private val payload: Any
	) : Process() {
		var reachedAfterEmit = false

		override suspend fun actions() {
			emitCustom(payload)
			reachedAfterEmit = true
		}
	}

	/** Runs [emitter] on a bare kDisco `Simulation` whose bus carries [listeners] in order. */
	private fun run(
		emitter: Emitter,
		vararg listeners: (SimulationEvent) -> Unit
	) = runBlocking {
		val simulation = Simulation.create { Process.activate(emitter) }
		listeners.forEach { simulation.onEvent(it) }
		simulation.run(END_TIME)
	}

	@Test
	fun `a plain failure of a typed listener is contained on every lane`() {
		lanes.forEach { lane ->
			val emitter = Emitter(lane.payload)
			var later = 0

			run(
				emitter,
				lane.recipient { error("typed listener failure injected by the test") },
				lane.recipient { later++ }
			)

			assertThat(later, "${lane.name}: the subscriber after the failing one").isEqualTo(1)
			assertThat(emitter.reachedAfterEmit, "${lane.name}: the process ran on past the emit").isTrue()
		}
	}

	@Test
	fun `a typed listener terminating the emitting process terminates it on every lane`() {
		lanes.forEach { lane ->
			val emitter = Emitter(lane.payload)
			var later = 0

			run(emitter, lane.recipient { emitter.terminate() }, lane.recipient { later++ })

			assertThat(later, "${lane.name}: the subscriber after the terminating one").isEqualTo(0)
			assertThat(emitter.reachedAfterEmit, "${lane.name}: the process ran on past its termination").isFalse()
			assertThat(emitter.terminated(), "${lane.name}: the process terminated").isTrue()
		}
	}

	@Test
	fun `a typed listener cancelling unwinds the emitting process on every lane`() {
		lanes.forEach { lane ->
			val emitter = Emitter(lane.payload)
			var later = 0

			run(
				emitter,
				lane.recipient { throw CancellationException("cancellation injected by the test") },
				lane.recipient { later++ }
			)

			assertThat(later, "${lane.name}: the subscriber after the cancelling one").isEqualTo(0)
			assertThat(emitter.reachedAfterEmit, "${lane.name}: the process ran on past the cancellation").isFalse()
		}
	}

	@Test
	fun `a cancellation leaves every lane as the very instance thrown`() {
		lanes.forEach { lane ->
			val cancellation = CancellationException("cancellation injected by the test")
			val recipient = lane.recipient { throw cancellation }

			val caught = runCatching { recipient(SimulationEvent.Custom(0.0, lane.payload)) }

			assertThat(caught.exceptionOrNull(), "${lane.name}: what left the guard").isSameInstanceAs(cancellation)
		}
	}

	/** A process that terminates itself and keeps what kDisco threw to unwind it. */
	private class SelfTerminating : Process() {
		var thrown: Throwable? = null

		override suspend fun actions() {
			try {
				terminate()
			} catch (e: Throwable) {
				thrown = e
				throw e
			}
		}
	}

	/**
	 * What kDisco throws to unwind a terminated process is recognised as process control by name, here and
	 * on linuxX64: a kDisco rename of its `internal` `ProcessTerminatedException` fails this test instead of
	 * being contained as a listener failure.
	 */
	@Test
	fun `kDisco's process termination is recognised as process control`() {
		val process = SelfTerminating()
		runBlocking {
			val simulation = Simulation.create { Process.activate(process) }
			simulation.run(END_TIME)
		}

		val thrown = process.thrown
		assertThat(thrown, "what kDisco threw on terminate()").isNotNull()
		assertThat(isProcessControl(checkNotNull(thrown)), "recognised as process control").isTrue()
	}
}
