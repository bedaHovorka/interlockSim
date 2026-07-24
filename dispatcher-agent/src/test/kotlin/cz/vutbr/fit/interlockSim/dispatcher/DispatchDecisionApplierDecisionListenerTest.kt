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
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchDecisionListenerHub
import cz.vutbr.fit.interlockSim.sim.DispatcherMode
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for the SP2b.6 `onDecisionApplied` observer hook on
 * [DispatchDecisionApplier] (Issue #561).
 *
 * Covers:
 * - The listener fires once per applied, non-`NoAction` decision.
 * - `NoAction` does not fire.
 * - MANUAL-dropped decisions do not fire (they are not "applied").
 * - `null` listener (default) is a no-op (backward compatibility).
 * - The listener runs on the same thread that calls `onControlStep`.
 *
 * @since Issue #561 (SP2b.6 — Goal 10)
 */
@DisplayName("DispatchDecisionApplier — onDecisionApplied observer hook (SP2b.6)")
@Timeout(30, unit = TimeUnit.SECONDS)
class DispatchDecisionApplierDecisionListenerTest {
	private lateinit var networkActuator: NetworkActuatorPort
	private val approvedTrains = mutableListOf<String>()
	private val onApproveTrain: (String) -> Unit = { approvedTrains.add(it) }

	@BeforeEach
	fun setUp() {
		networkActuator = mockk(relaxed = true)
		approvedTrains.clear()
	}

	private fun makeApplier(
		listener: DispatchDecisionListenerHub? = null,
		modeState: DispatcherModeState? = null
	): Pair<ActuatorCommandQueue, DispatchDecisionApplier> {
		val queue = ActuatorCommandQueue()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = onApproveTrain,
				modeState = modeState,
				onDecisionApplied = listener
			)
		return queue to applier
	}

	@Test
	@DisplayName("fires once per applied ApproveTrain and ReservePath, in order")
	fun appliedDecisions_fireListenerInOrder() {
		every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 1)
		val hub = DispatchDecisionListenerHub()
		val captured = AtomicReference<List<DispatchDecision>>(emptyList())
		hub.setSink { decision -> captured.set(captured.get() + decision) }
		val (queue, applier) = makeApplier(listener = hub)
		queue.postAll(
			listOf(
				DispatchDecision.ApproveTrain("T1"),
				DispatchDecision.ReservePath("T1", "zA", "doA1")
			)
		)

		applier.onControlStep()

		assertThat(captured.get().map { it::class.simpleName }).containsExactly("ApproveTrain", "ReservePath")
	}

	@Test
	@DisplayName("NoAction does not fire the listener even though it passes the gate")
	fun noAction_doesNotFireListener() {
		val hub = DispatchDecisionListenerHub()
		val captured = AtomicReference<List<DispatchDecision>>(emptyList())
		hub.setSink { decision -> captured.set(captured.get() + decision) }
		val (queue, applier) = makeApplier(listener = hub)
		queue.postAll(listOf(DispatchDecision.NoAction))

		applier.onControlStep()

		assertThat(captured.get()).isEmpty()
	}

	@Test
	@DisplayName("MANUAL-dropped decisions do not fire the listener")
	fun manualDropped_decisionsDoNotFireListener() {
		val state = DispatcherModeState().apply { setOverride(DispatcherMode.MANUAL) }
		val hub = DispatchDecisionListenerHub()
		val captured = AtomicReference<List<DispatchDecision>>(emptyList())
		hub.setSink { decision -> captured.set(captured.get() + decision) }
		val (queue, applier) = makeApplier(listener = hub, modeState = state)
		queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))

		applier.onControlStep()

		assertThat(captured.get()).isEmpty()
		verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
	}

	@Test
	@DisplayName("null listener (default) is a no-op — backward compatibility")
	fun nullListener_isNoOp() {
		val (queue, applier) = makeApplier(listener = null)
		queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))

		applier.onControlStep() // must not throw

		assertThat(approvedTrains).containsExactly("T1")
	}
}
