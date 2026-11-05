/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.util.Util
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * The guard around the [ReservationExpired] emit (Issue #1310): the expiry sends the event through
 * the shared guarded emit of the journey events (Goal 6 SP2), so kDisco's process control passes
 * through it. A subscriber on the real kDisco bus that terminates the expiring process ends that
 * process, a cancellation and an [Error] leave the expiry call, and the expiry is counted before any
 * of them, so the counters still include it.
 *
 * The evidence is the expiry's own counters and the expiring process, never a second subscriber:
 * a raw subscriber that throws ends the bus's delivery to the subscribers after it.
 *
 * @since Issue #1310
 */
class ReservationExpiredEmitGuardTest : ReservationExpiryTestBase() {
	/** The expiring process; [reachedAfterExpiry] tells whether it ran on past the expiry call. */
	private inner class ExpiringProcess : Process() {
		var reachedAfterExpiry = false
		lateinit var blocks: List<DynamicTrackBlock>

		override suspend fun actions() {
			val zA = findSemaphoreByName("zA")
			blocks =
				assertReservationSuccess(service.reservePath("train1", zA, findSemaphoreByName("doB1")))
					.reservedBlocks
			// No proceed aspect at the head, so one call frees the whole holding.
			zA.signal = Signal.STOP
			hold(TTL)
			service.expireStaleReservations()
			reachedAfterExpiry = true
		}
	}

	/**
	 * Runs one [ExpiringProcess] on a bare kDisco `Simulation`, with [listenerFor] the raw bus
	 * subscriber under test. Unlike [atSimTimes], nothing here catches what leaves the process, so
	 * a termination really ends it.
	 */
	private fun runExpiring(listenerFor: (ExpiringProcess) -> (SimulationEvent) -> Unit): ExpiringProcess {
		val expiring = ExpiringProcess()
		runBlocking {
			val simulation = Simulation.create { Process.activate(expiring) }
			simulation.onEvent(listenerFor(expiring))
			simulation.run(TTL + 10.0)
		}
		return expiring
	}

	/** Whether [event] carries a [ReservationExpired]. */
	private fun isExpired(event: SimulationEvent) = (event as? SimulationEvent.Custom)?.payload is ReservationExpired

	@Test
	fun `a raw subscriber terminating the expiring process still terminates it`() {
		val expiring = runExpiring { process -> { event -> if (isExpired(event)) process.terminate() } }

		assertThat(expiring.reachedAfterExpiry, "process ran on past its own termination").isFalse()
		assertThat(expiring.terminated(), "process terminated").isTrue()
		// Counted before the emit, so the termination cannot lose the completed expiry.
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
		assertBlocksFreeAndUnowned(expiring.blocks)
	}

	@Test
	fun `an onReservationExpired subscriber terminating the expiring process still terminates it`() {
		// Subscribe through the real extension; the environment hands its wrapper to the kDisco bus.
		val env = mockk<SimulationEnvironment>()
		val wrappers = mutableListOf<(SimulationEvent) -> Unit>()
		every { env.onSimulationEvent(capture(wrappers)) } just Runs
		lateinit var target: Process
		env.onReservationExpired { target.terminate() }

		val expiring =
			runExpiring { process ->
				target = process
				wrappers.single()
			}

		assertThat(expiring.reachedAfterExpiry, "process ran on past its own termination").isFalse()
		assertThat(expiring.terminated(), "process terminated").isTrue()
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
	}

	@Test
	fun `a raw subscriber cancelling is rethrown by the expiry`() {
		busListeners += { event -> if (isExpired(event)) throw CancellationException("injected by the test") }
		val caught = mutableListOf<Throwable?>()

		atSimTimes(
			0.0 to { reserveTrain1WithHeadAtStop() },
			TTL to { caught += runCatching { service.expireStaleReservations() }.exceptionOrNull() }
		)

		val thrown = caught.single() ?: error("the expiry swallowed the cancellation")
		Util.assertInstanceOf<CancellationException>(thrown)
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
	}

	@Test
	fun `a raw subscriber throwing an Error is not caught by the expiry`() {
		busListeners += { event -> if (isExpired(event)) throw InjectedError() }
		val caught = mutableListOf<Throwable?>()

		atSimTimes(
			0.0 to { reserveTrain1WithHeadAtStop() },
			TTL to { caught += runCatching { service.expireStaleReservations() }.exceptionOrNull() }
		)

		val thrown = caught.single() ?: error("the expiry swallowed the Error")
		Util.assertInstanceOf<InjectedError>(thrown)
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
	}

	/** Reserve zA -> doB1 for train1 and drop zA to STOP, so one expiry call frees the whole holding. */
	private fun reserveTrain1WithHeadAtStop() {
		val zA = findSemaphoreByName("zA")
		assertReservationSuccess(service.reservePath("train1", zA, findSemaphoreByName("doB1")))
		zA.signal = Signal.STOP
	}

	/** An [Error] the expiry must never catch. */
	private class InjectedError : Error("Error injected by the test")
}
