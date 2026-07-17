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
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
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
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
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
@Timeout(30, unit = TimeUnit.SECONDS)
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

	// ── Duplicate-reservation suppression (SP0.11 regression fix) ────────────

	/**
	 * Regression coverage for the SP0.11 async-driver race (Issue #733 follow-up):
	 * [AgentLoopDriver] runs decoupled from the sim thread, so it can legitimately
	 * decide the *same* `ReservePath` more than once before the block-input
	 * observation reflects the first application. Re-applying an already-reserved
	 * hop is not a harmless no-op — it corrupts the train's registered path via
	 * `PathReservationRegistry.registerPathInfo`'s merge logic and permanently
	 * stalls the train. [DispatchDecisionApplier] must apply each distinct
	 * `(trainId, from, to)` reservation at most once.
	 */
	@Nested
	@DisplayName("Duplicate-reservation suppression (SP0.11 regression fix)")
	inner class DuplicateReservationSuppression {
		@Test
		@DisplayName("Identical ReservePath decision drained twice is applied to the actuator port only once")
		fun duplicateReservePath_appliedOnlyOnce() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 1)
			val (queue, applier) = makeApplier()

			// First drain: the reservation is genuinely new.
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zB", "doA1")))
			applier.onControlStep()

			// Second drain: the driver decided the identical hop again before observing
			// the first application (the race this guard exists to suppress).
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zB", "doA1")))
			applier.onControlStep()

			verify(exactly = 1) { networkActuator.requestRoute("T1", "zB", "doA1") }
		}

		@Test
		@DisplayName("Identical ReservePath decision applied twice within the same drain is applied only once")
		fun duplicateReservePath_sameDrain_appliedOnlyOnce() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 1)
			val (queue, applier) = makeApplier()

			queue.postAll(
				listOf(
					DispatchDecision.ReservePath("T1", "zB", "doA1"),
					DispatchDecision.ReservePath("T1", "zB", "doA1")
				)
			)
			applier.onControlStep()

			verify(exactly = 1) { networkActuator.requestRoute("T1", "zB", "doA1") }
		}

		@Test
		@DisplayName("onBlockTransition fires only once for a duplicated ReservePath")
		fun duplicateReservePath_blockTransitionCountedOnce() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 1)
			var transitionCount = 0
			val queue = ActuatorCommandQueue()
			val applier =
				DispatchDecisionApplier(
					queue = queue,
					networkActuator = networkActuator,
					onApproveTrain = onApproveTrain,
					onBlockTransition = { transitionCount++ }
				)

			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zB", "doA1")))
			applier.onControlStep()
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zB", "doA1")))
			applier.onControlStep()

			assertThat(transitionCount).isEqualTo(1)
		}

		@Test
		@DisplayName("A different (trainId, from, to) triple is not suppressed by an earlier reservation")
		fun differentTriple_notSuppressed() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 1)
			val (queue, applier) = makeApplier()

			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zB", "doA1")))
			applier.onControlStep()
			// Same train, different hop — genuine forward progress, must still be applied.
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "doA1", "A")))
			applier.onControlStep()

			verify(exactly = 1) { networkActuator.requestRoute("T1", "zB", "doA1") }
			verify(exactly = 1) { networkActuator.requestRoute("T1", "doA1", "A") }
		}

		@Test
		@DisplayName("A failed reservation is not remembered, so a later retry of the same triple is applied")
		fun failedReservation_retryIsApplied() {
			var callCount = 0
			every { networkActuator.requestRoute("T1", "zB", "doA1") } answers {
				callCount++
				if (callCount == 1) {
					RouteRequestResult.AllPathsBlocked(attemptedPaths = 1)
				} else {
					RouteRequestResult.Reserved("T1", 1)
				}
			}
			val (queue, applier) = makeApplier()

			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zB", "doA1")))
			applier.onControlStep() // fails — must not be recorded as applied

			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zB", "doA1")))
			applier.onControlStep() // legitimate retry — must reach the actuator port

			verify(exactly = 2) { networkActuator.requestRoute("T1", "zB", "doA1") }
		}

		@Test
		@DisplayName(
			"evictReservationsFor clears a train's suppressed keys, so a later legitimate " +
				"re-reservation of the same triple (e.g. after a reversal) is applied again"
		)
		fun evictReservationsFor_allowsLegitimateReReservation() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 1)
			val (queue, applier) = makeApplier()

			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zB", "doA1")))
			applier.onControlStep()

			// Simulate the wiring layer's BlockOccupancyListener reacting to a BLOCK_RELEASED
			// event for this train (e.g. the train reversed and released the hop).
			applier.evictReservationsFor("T1")

			// The identical triple is now a fresh request, not a stale in-flight duplicate.
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zB", "doA1")))
			applier.onControlStep()

			verify(exactly = 2) { networkActuator.requestRoute("T1", "zB", "doA1") }
		}

		@Test
		@DisplayName("evictReservationsFor only clears entries for the given train, not other trains'")
		fun evictReservationsFor_onlyAffectsGivenTrain() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 1)
			val (queue, applier) = makeApplier()

			queue.postAll(
				listOf(
					DispatchDecision.ReservePath("T1", "zB", "doA1"),
					DispatchDecision.ReservePath("T2", "zB", "doA1")
				)
			)
			applier.onControlStep()

			applier.evictReservationsFor("T1")

			// T1's triple is fresh again (evicted); T2's identical triple must remain suppressed.
			queue.postAll(
				listOf(
					DispatchDecision.ReservePath("T1", "zB", "doA1"),
					DispatchDecision.ReservePath("T2", "zB", "doA1")
				)
			)
			applier.onControlStep()

			verify(exactly = 2) { networkActuator.requestRoute("T1", "zB", "doA1") }
			verify(exactly = 1) { networkActuator.requestRoute("T2", "zB", "doA1") }
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

	// ── SP1.7 tool-driven actuator decisions (Issue #774) ─────────────────────

	/**
	 * Tests for the four SP1.7 tool-driven [DispatchDecision] subtypes introduced to satisfy
	 * the kDisco threading contract.  These subtypes allow actuator tools running on the Koog
	 * agent driver thread to marshal their commands through [ActuatorCommandQueue] and have
	 * [DispatchDecisionApplier.onControlStep] apply them on the kDisco simulation thread.
	 */
	@Nested
	@DisplayName("SP1.7 tool-driven actuator decisions (Issue #774)")
	inner class ToolDrivenActuatorDecisions {

		@Test
		@DisplayName("SetSignalAspect is routed to NetworkActuatorPort.setSignalAspect")
		fun setSignalAspect_routedToActuatorPort() {
			every { networkActuator.setSignalAspect(any(), any()) } returns true
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.SetSignalAspect("zA", Signal.FREE)))

			applier.onControlStep()

			verify(exactly = 1) { networkActuator.setSignalAspect("zA", Signal.FREE) }
			assertThat(approvedTrains).isEmpty()
		}

		@Test
		@DisplayName("SetSignalAspect passes correct semaphore name and signal to the port")
		fun setSignalAspect_passesCorrectArguments() {
			every { networkActuator.setSignalAspect(any(), any()) } returns true
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.SetSignalAspect("zB", Signal.STOP)))

			applier.onControlStep()

			verify { networkActuator.setSignalAspect("zB", Signal.STOP) }
		}

		@Test
		@DisplayName("SetSignalAspect false result (e.g. constant semaphore) does not throw")
		fun setSignalAspect_falseResult_doesNotThrow() {
			every { networkActuator.setSignalAspect(any(), any()) } returns false
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.SetSignalAspect("zC", Signal.STOP)))

			applier.onControlStep() // must not throw
		}

		@Test
		@DisplayName("SetSwitchPosition is routed to NetworkActuatorPort.setSwitchPosition")
		fun setSwitchPosition_routedToActuatorPort() {
			every { networkActuator.setSwitchPosition(any(), any()) } returns true
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.SetSwitchPosition("v1", RailSwitch.Conf.MAIN)))

			applier.onControlStep()

			verify(exactly = 1) { networkActuator.setSwitchPosition("v1", RailSwitch.Conf.MAIN) }
			assertThat(approvedTrains).isEmpty()
		}

		@Test
		@DisplayName("SetSwitchPosition passes correct switch name and position to the port")
		fun setSwitchPosition_passesCorrectArguments() {
			every { networkActuator.setSwitchPosition(any(), any()) } returns true
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.SetSwitchPosition("v2", RailSwitch.Conf.BRANCH)))

			applier.onControlStep()

			verify { networkActuator.setSwitchPosition("v2", RailSwitch.Conf.BRANCH) }
		}

		@Test
		@DisplayName("SetSwitchPosition false result (e.g. locked switch) does not throw")
		fun setSwitchPosition_falseResult_doesNotThrow() {
			every { networkActuator.setSwitchPosition(any(), any()) } returns false
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.SetSwitchPosition("v3", RailSwitch.Conf.MAIN)))

			applier.onControlStep() // must not throw
		}

		@Test
		@DisplayName("ReleaseRoute is routed to NetworkActuatorPort.releaseRoute")
		fun releaseRoute_routedToActuatorPort() {
			every { networkActuator.releaseRoute(any()) } returns true
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ReleaseRoute("Train #1")))

			applier.onControlStep()

			verify(exactly = 1) { networkActuator.releaseRoute("Train #1") }
			assertThat(approvedTrains).isEmpty()
		}

		@Test
		@DisplayName("ReleaseRoute false result (train held no reservation) does not throw")
		fun releaseRoute_falseResult_doesNotThrow() {
			every { networkActuator.releaseRoute(any()) } returns false
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.ReleaseRoute("Train #2")))

			applier.onControlStep() // must not throw
		}

		@Test
		@DisplayName("RequestRoute is routed to NetworkActuatorPort.requestRoute with train/from/to")
		fun requestRoute_routedToActuatorPort() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns
				RouteRequestResult.Reserved("Train #1", 3)
			val (queue, applier) = makeApplier()
			queue.postAll(
				listOf(DispatchDecision.RequestRoute("Train #1", "A", "B"))
			)

			applier.onControlStep()

			verify(exactly = 1) { networkActuator.requestRoute("Train #1", "A", "B") }
			assertThat(approvedTrains).isEmpty()
		}

		@Test
		@DisplayName("RequestRoute AllPathsBlocked result does not throw")
		fun requestRoute_allPathsBlocked_doesNotThrow() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns
				RouteRequestResult.AllPathsBlocked(attemptedPaths = 2)
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.RequestRoute("Train #1", "A", "B")))

			applier.onControlStep() // must not throw
		}

		@Test
		@DisplayName("RequestRoute Conflict result does not throw")
		fun requestRoute_conflict_doesNotThrow() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns
				RouteRequestResult.Conflict(blockName = "k1", existingOwner = "Train #2")
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.RequestRoute("Train #1", "A", "B")))

			applier.onControlStep() // must not throw
		}

		@Test
		@DisplayName("RequestRoute NoRouteExists result does not throw")
		fun requestRoute_noRouteExists_doesNotThrow() {
			every { networkActuator.requestRoute(any(), any(), any()) } returns
				RouteRequestResult.NoRouteExists(fromEndpointName = "A", toEndpointName = "B")
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.RequestRoute("Train #1", "A", "B")))

			applier.onControlStep() // must not throw
		}

		@Test
		@DisplayName("All four tool-driven decisions applied in a single drain")
		fun allFourToolDecisions_appliedInSingleDrain() {
			every { networkActuator.setSignalAspect(any(), any()) } returns true
			every { networkActuator.setSwitchPosition(any(), any()) } returns true
			every { networkActuator.releaseRoute(any()) } returns true
			every { networkActuator.requestRoute(any(), any(), any()) } returns
				RouteRequestResult.Reserved("Train #1", 2)
			val (queue, applier) = makeApplier()
			queue.postAll(
				listOf(
					DispatchDecision.SetSignalAspect("zA", Signal.FREE),
					DispatchDecision.SetSwitchPosition("v1", RailSwitch.Conf.BRANCH),
					DispatchDecision.ReleaseRoute("Train #1"),
					DispatchDecision.RequestRoute("Train #1", "A", "B")
				)
			)

			applier.onControlStep()

			verify(exactly = 1) { networkActuator.setSignalAspect("zA", Signal.FREE) }
			verify(exactly = 1) { networkActuator.setSwitchPosition("v1", RailSwitch.Conf.BRANCH) }
			verify(exactly = 1) { networkActuator.releaseRoute("Train #1") }
			verify(exactly = 1) { networkActuator.requestRoute("Train #1", "A", "B") }
		}

		@Test
		@DisplayName("Tool-driven decisions are applied on the thread invoking onControlStep")
		fun toolDecisions_appliedOnControlStepThread() {
			val actuatorCallThread = AtomicReference<Thread>()
			every { networkActuator.setSignalAspect(any(), any()) } answers {
				actuatorCallThread.set(Thread.currentThread())
				true
			}
			val (queue, applier) = makeApplier()
			queue.postAll(listOf(DispatchDecision.SetSignalAspect("zA", Signal.FREE)))

			val callerThread = Thread.currentThread()
			applier.onControlStep()

			assertThat(actuatorCallThread.get()).isNotNull()
			assertThat(actuatorCallThread.get()).isEqualTo(callerThread)
		}
	}
}
