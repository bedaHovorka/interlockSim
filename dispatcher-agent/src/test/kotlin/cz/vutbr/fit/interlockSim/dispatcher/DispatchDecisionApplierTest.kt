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
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [DispatchDecisionApplier].
 *
 * Covers:
 * - Correct routing of each [DispatchDecision] subtype to the right actuator/callback
 * - FIFO application order
 * - Empty-queue no-op behaviour
 * - **Thread-identity contract**: actuator calls and the approval callback must
 *   execute on the thread that calls [DispatchDecisionApplier.onControlStep], never
 *   on the driver thread that posted the decisions into the queue.
 *
 * @since Issue #731 (SP0.9 — Goal 10)
 */
@DisplayName("DispatchDecisionApplier — drain and apply decisions via actuator ports")
class DispatchDecisionApplierTest {
	private lateinit var networkActuator: NetworkActuatorPort
	private val approvedTrains = mutableListOf<String>()
	private val onApproveTrain: (String) -> Unit = { approvedTrains.add(it) }

	@BeforeEach
	fun setUp() {
		networkActuator = mockk(relaxed = true)
		approvedTrains.clear()
	}

	private fun makeApplier(
		capacity: Int = ActuatorCommandQueue.DEFAULT_CAPACITY
	): Pair<ActuatorCommandQueue, DispatchDecisionApplier> {
		val queue = ActuatorCommandQueue(capacity)
		val applier = DispatchDecisionApplier(queue, networkActuator, onApproveTrain)
		return queue to applier
	}

	// ── Basic decision routing ────────────────────────────────────────────────

	@Nested
	@DisplayName("Decision routing")
	inner class DecisionRouting {
		@Test
		@DisplayName("ApproveTrain is routed to the onApproveTrain callback")
		fun approveTrain_routedToCallback() {
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))

			applier.onControlStep()

			assertThat(approvedTrains).containsExactly("T1")
			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
		}

		@Test
		@DisplayName("ReservePath is routed to NetworkActuatorPort.requestRoute")
		fun reservePath_routedToActuatorPort() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 1)
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zA", "doA1")))

			applier.onControlStep()

			verify(exactly = 1) { networkActuator.requestRoute("T1", "zA", "doA1") }
			assertThat(approvedTrains).isEmpty()
		}

		@Test
		@DisplayName("ReservePath passes correct semaphore and separator names to the port")
		fun reservePath_passesCorrectNames() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T2", 2)
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ReservePath("T2", "doB1", "B")))

			applier.onControlStep()

			verify { networkActuator.requestRoute("T2", "doB1", "B") }
		}

		@Test
		@DisplayName("NoAction does not trigger any side effect")
		fun noAction_noSideEffect() {
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.NoAction))

			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
		}

		@Test
		@DisplayName("Mixed decisions are all applied")
		fun mixedDecisions_allApplied() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 1)
			val (queue, applier) = makeApplier()
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ReservePath("T1", "zA", "doA1"),
					DispatchDecision.NoAction
				)
			)

			applier.onControlStep()

			assertThat(approvedTrains).containsExactly("T1")
			verify(exactly = 1) { networkActuator.requestRoute("T1", "zA", "doA1") }
		}

		@Test
		@DisplayName("Decisions applied in FIFO order matching posting order")
		fun decisions_appliedInFifoOrder() {
			val (queue, applier) = makeApplier()
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ApproveTrain("T2"),
					DispatchDecision.ApproveTrain("T3")
				)
			)

			applier.onControlStep()

			assertThat(approvedTrains).containsExactly("T1", "T2", "T3")
		}
	}

	// ── Empty-queue behaviour ─────────────────────────────────────────────────

	@Nested
	@DisplayName("Empty queue")
	inner class EmptyQueue {
		@Test
		@DisplayName("onControlStep is a no-op when queue is empty")
		fun emptyQueue_noSideEffect() {
			val (_, applier) = makeApplier()

			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
		}

		@Test
		@DisplayName("Repeated onControlStep calls with empty queue are all no-ops")
		fun repeatedCallsOnEmptyQueue_noSideEffect() {
			val (_, applier) = makeApplier()

			repeat(5) { applier.onControlStep() }

			assertThat(approvedTrains).isEmpty()
			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
		}

		@Test
		@DisplayName("Queue is empty after onControlStep drains it")
		fun queueEmptyAfterDrain() {
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))

			applier.onControlStep()
			// Second call — queue already drained
			approvedTrains.clear()
			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
		}
	}

	// ── Actuator outcome logging (non-Reserved results) ───────────────────────

	@Nested
	@DisplayName("ReservePath outcome handling")
	inner class ReservePathOutcomes {
		@Test
		@DisplayName("AllPathsBlocked result does not throw")
		fun allPathsBlocked_doesNotThrow() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns
				RouteRequestResult.AllPathsBlocked(attemptedPaths = 2)
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zA", "doA1")))

			applier.onControlStep() // must not throw
		}

		@Test
		@DisplayName("Conflict result does not throw")
		fun conflict_doesNotThrow() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns
				RouteRequestResult.Conflict(blockName = "k1", existingOwner = "T2")
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zA", "doA1")))

			applier.onControlStep() // must not throw
		}

		@Test
		@DisplayName("NoRouteExists result does not throw")
		fun noRouteExists_doesNotThrow() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns
				RouteRequestResult.NoRouteExists(fromEndpointName = "zA", toEndpointName = "doA1")
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zA", "doA1")))

			applier.onControlStep() // must not throw
		}
	}

	// ── Thread-identity contract ──────────────────────────────────────────────

	@Nested
	@DisplayName("Thread-identity contract (SP0.9 concurrency requirement)")
	inner class ThreadIdentity {
		@Test
		@DisplayName("requestRoute is called on the thread invoking onControlStep")
		fun requestRoute_calledOnControlStepThread() {
			val actuatorCallThread = AtomicReference<Thread>()
			every { networkActuator.requestRoute(any(), any(), any()) } answers {
				actuatorCallThread.set(Thread.currentThread())
				RouteRequestResult.Reserved("T1", 1)
			}
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zA", "doA1")))

			val callerThread = Thread.currentThread()
			applier.onControlStep()

			assertThat(actuatorCallThread.get()).isNotNull()
			assertThat(actuatorCallThread.get()).isEqualTo(callerThread)
		}

		@Test
		@DisplayName("onApproveTrain callback is called on the thread invoking onControlStep")
		fun approveCallback_calledOnControlStepThread() {
			val callbackThread = AtomicReference<Thread>()
			val trackingCallback: (String) -> Unit = { callbackThread.set(Thread.currentThread()) }
			val queue = ActuatorCommandQueue()
			val applier = DispatchDecisionApplier(queue, networkActuator, trackingCallback)
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))

			val callerThread = Thread.currentThread()
			applier.onControlStep()

			assertThat(callbackThread.get()).isNotNull()
			assertThat(callbackThread.get()).isEqualTo(callerThread)
		}

		@Test
		@DisplayName("Decisions posted from a different thread are applied on the onControlStep thread")
		fun crossThreadPosting_appliedOnControlStepThread() {
			val actuatorCallThread = AtomicReference<Thread>()
			every { networkActuator.requestRoute(any(), any(), any()) } answers {
				actuatorCallThread.set(Thread.currentThread())
				RouteRequestResult.Reserved("T1", 1)
			}
			val (queue, applier) = makeApplier()

			// Post from a background thread (simulating the future SP0.10 driver thread)
			val driverThread =
				Thread {
					queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zA", "doA1")))
				}
			driverThread.start()
			driverThread.join()

			// Apply on the "sim thread" (current test thread)
			val simThread = Thread.currentThread()
			applier.onControlStep()

			assertThat(actuatorCallThread.get()).isNotNull()
			// Actuator was called on the sim thread, not the driver thread
			assertThat(actuatorCallThread.get()).isEqualTo(simThread)
			assertThat(actuatorCallThread.get() === driverThread).isFalse()
		}

		@Test
		@DisplayName("Sim-state mutation does not originate from the posting thread")
		fun posting_doesNotMutateState() {
			// The posting thread must never directly invoke actuator or approval callbacks —
			// it only enqueues decisions. Verify by confirming no callbacks fire during postAll.
			var callbackInvoked = false
			val trackingCallback: (String) -> Unit = { callbackInvoked = true }
			every { networkActuator.requestRoute(any(), any(), any()) } returns
				RouteRequestResult.Reserved("T1", 1)

			val queue = ActuatorCommandQueue()
			val applier = DispatchDecisionApplier(queue, networkActuator, trackingCallback)

			// Simulate driver thread posting — must not cause any mutation
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ReservePath("T1", "zA", "doA1")
				)
			)

			// After posting, nothing should have been applied yet
			assertThat(callbackInvoked).isFalse()
			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }

			// Only after the sim-thread calls onControlStep are decisions applied
			applier.onControlStep()
			assertThat(callbackInvoked).isEqualTo(true)
			verify(exactly = 1) { networkActuator.requestRoute("T1", "zA", "doA1") }
		}
	}
}
