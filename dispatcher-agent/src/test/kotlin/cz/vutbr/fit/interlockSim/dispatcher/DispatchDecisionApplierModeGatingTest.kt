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
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import cz.vutbr.fit.interlockSim.ports.TrainLifecyclePort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatcherMode
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * SP2b.4 (Issue #559) tests for [DispatchDecisionApplier] mode gating.
 *
 * Verifies that when a [DispatcherModeState] is wired the applier honours the
 * effective [DispatcherMode]:
 * - [DispatcherMode.AUTO] — apply directly, matching pre-SP2b.4 behaviour.
 * - [DispatcherMode.SEMI_AUTO] — call the approver; apply on `true`, drop on `false`;
 *   drop with a warning when no approver is wired.
 * - [DispatcherMode.MANUAL] — drop every actuating decision;
 *   [DispatchDecision.NoAction] still flows through as a no-op.
 * - Mode changes take effect immediately for the next drained decision.
 *
 * @since Issue #559 (SP2b.4 — Goal 10)
 */
@DisplayName("DispatchDecisionApplier — SP2b.4 DispatcherMode gating (Issue #559)")
@Timeout(30, unit = TimeUnit.SECONDS)
class DispatchDecisionApplierModeGatingTest {
	private lateinit var networkActuator: NetworkActuatorPort
	private lateinit var trainLifecyclePort: TrainLifecyclePort
	private val approvedTrains = mutableListOf<String>()
	private val onApproveTrain: (String) -> Unit = { approvedTrains.add(it) }

	@BeforeEach
	fun setUp() {
		networkActuator = mockk(relaxed = true)
		trainLifecyclePort = mockk(relaxed = true)
		every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 1)
		every { trainLifecyclePort.holdTrain(any(), any()) } returns true
		approvedTrains.clear()
	}

	private fun makeApplier(
		modeState: DispatcherModeState?,
		semiAutoApprover: ((DispatchDecision) -> Boolean)? = null
	): Pair<ActuatorCommandQueue, DispatchDecisionApplier> {
		val queue = ActuatorCommandQueue()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = onApproveTrain,
				trainLifecyclePort = trainLifecyclePort,
				modeState = modeState,
				semiAutoApprover = semiAutoApprover
			)
		return queue to applier
	}

	// ── Default behaviour: no mode state → apply everything ───────────────────

	@Test
	@DisplayName("With no modeState wired, every decision is applied (backward compatibility)")
	fun noModeState_appliesEverything() {
		val (queue, applier) = makeApplier(modeState = null)
		queue.postAll(
			listOf(
				DispatchDecision.ApproveTrain("T1"),
				DispatchDecision.ReservePath("T1", "zA", "doA1"),
				DispatchDecision.HoldTrain("T1", 10.0),
				DispatchDecision.NoAction
			)
		)

		applier.onControlStep()

		assertThat(approvedTrains).containsExactly("T1")
		verify(exactly = 1) { networkActuator.requestRoute("T1", "zA", "doA1") }
		verify(exactly = 1) { trainLifecyclePort.holdTrain("T1", 10.0) }
	}

	// ── AUTO mode: apply everything ───────────────────────────────────────────

	@Nested
	@DisplayName("AUTO mode")
	inner class AutoMode {
		@Test
		@DisplayName("AUTO applies all decisions like pre-SP2b.4 behaviour")
		fun auto_appliesAll() {
			val state = DispatcherModeState() // defaults to AUTO
			val (queue, applier) = makeApplier(modeState = state)
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ReservePath("T1", "zA", "doA1"),
					DispatchDecision.HoldTrain("T1", 5.0)
				)
			)

			applier.onControlStep()

			assertThat(approvedTrains).containsExactly("T1")
			verify(exactly = 1) { networkActuator.requestRoute("T1", "zA", "doA1") }
			verify(exactly = 1) { trainLifecyclePort.holdTrain("T1", 5.0) }
		}

		@Test
		@DisplayName("AUTO still allows NoAction through as a no-op")
		fun auto_allowsNoAction() {
			val state = DispatcherModeState() // defaults to AUTO
			val (queue, applier) = makeApplier(modeState = state)
			queue.postAll(listOf(DispatchDecision.NoAction))

			// Must not throw and must not touch any actuator — NoAction is a no-op
			// regardless of mode and bypasses the mode gate in all three modes.
			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
			verify(exactly = 0) { trainLifecyclePort.holdTrain(any(), any()) }
		}
	}

	// ── MANUAL mode: drop everything except NoAction ─────────────────────────

	@Nested
	@DisplayName("MANUAL mode")
	inner class ManualMode {
		@Test
		@DisplayName("MANUAL drops ApproveTrain")
		fun manual_dropsApproveTrain() {
			val state = DispatcherModeState().apply { setOverride(DispatcherMode.MANUAL) }
			val (queue, applier) = makeApplier(modeState = state)
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))

			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
		}

		@Test
		@DisplayName("MANUAL drops ReservePath (no actuator port call)")
		fun manual_dropsReservePath() {
			val state = DispatcherModeState().apply { setOverride(DispatcherMode.MANUAL) }
			val (queue, applier) = makeApplier(modeState = state)
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zA", "doA1")))

			applier.onControlStep()

			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
		}

		@Test
		@DisplayName("MANUAL drops HoldTrain (no train-lifecycle port call)")
		fun manual_dropsHoldTrain() {
			val state = DispatcherModeState().apply { setOverride(DispatcherMode.MANUAL) }
			val (queue, applier) = makeApplier(modeState = state)
			queue.postAll(listOf(DispatchDecision.HoldTrain("T1", 5.0)))

			applier.onControlStep()

			verify(exactly = 0) { trainLifecyclePort.holdTrain(any(), any()) }
		}

		@Test
		@DisplayName("MANUAL still allows NoAction through as a no-op")
		fun manual_allowsNoAction() {
			val state = DispatcherModeState().apply { setOverride(DispatcherMode.MANUAL) }
			val (queue, applier) = makeApplier(modeState = state)
			queue.postAll(listOf(DispatchDecision.NoAction))

			// Must not throw and must not touch any actuator.
			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
			verify(exactly = 0) { trainLifecyclePort.holdTrain(any(), any()) }
		}
	}

	// ── SEMI_AUTO mode: consult approver ──────────────────────────────────────

	@Nested
	@DisplayName("SEMI_AUTO mode")
	inner class SemiAutoMode {
		@Test
		@DisplayName("SEMI_AUTO with approver returning true applies decisions")
		fun semiAuto_approverTrue_applies() {
			val state = DispatcherModeState().apply { setOverride(DispatcherMode.SEMI_AUTO) }
			val (queue, applier) = makeApplier(modeState = state, semiAutoApprover = { true })
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ReservePath("T1", "zA", "doA1")
				)
			)

			applier.onControlStep()

			assertThat(approvedTrains).containsExactly("T1")
			verify(exactly = 1) { networkActuator.requestRoute("T1", "zA", "doA1") }
		}

		@Test
		@DisplayName("SEMI_AUTO with approver returning false drops decisions")
		fun semiAuto_approverFalse_drops() {
			val state = DispatcherModeState().apply { setOverride(DispatcherMode.SEMI_AUTO) }
			val (queue, applier) = makeApplier(modeState = state, semiAutoApprover = { false })
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ReservePath("T1", "zA", "doA1")
				)
			)

			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
		}

		@Test
		@DisplayName("SEMI_AUTO approver receives each decision in order")
		fun semiAuto_approverSeesEachDecision() {
			val state = DispatcherModeState().apply { setOverride(DispatcherMode.SEMI_AUTO) }
			val seen = mutableListOf<DispatchDecision>()
			val approver: (DispatchDecision) -> Boolean = { d ->
				seen.add(d)
				true
			}
			val (queue, applier) = makeApplier(modeState = state, semiAutoApprover = approver)
			val d1 = DispatchDecision.ApproveTrain("T1")
			val d2 = DispatchDecision.ReservePath("T1", "zA", "doA1")
			queue.postAll(listOf(d1, d2))

			applier.onControlStep()

			assertThat(seen).containsExactly(d1, d2)
		}

		@Test
		@DisplayName("SEMI_AUTO with no approver wired drops actuating decisions")
		fun semiAuto_noApprover_drops() {
			val state = DispatcherModeState().apply { setOverride(DispatcherMode.SEMI_AUTO) }
			val (queue, applier) = makeApplier(modeState = state, semiAutoApprover = null)
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ReservePath("T1", "zA", "doA1")
				)
			)

			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
		}

		@Test
		@DisplayName("SEMI_AUTO approver is not consulted for NoAction (bypass)")
		fun semiAuto_noAction_bypassesApprover() {
			val state = DispatcherModeState().apply { setOverride(DispatcherMode.SEMI_AUTO) }
			var calls = 0
			val approver: (DispatchDecision) -> Boolean = {
				calls++
				false
			}
			val (queue, applier) = makeApplier(modeState = state, semiAutoApprover = approver)
			queue.postAll(listOf(DispatchDecision.NoAction))

			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
			// NoAction bypasses the approver — it is a no-op regardless of mode.
			assertThat(calls).isEqualTo(0)
		}
	}

	// ── Mode transitions ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("Mode transitions")
	inner class ModeTransitions {
		@Test
		@DisplayName("Switching from AUTO to MANUAL takes effect on the next drained decision")
		fun autoToManual_appliesToNextDecision() {
			val state = DispatcherModeState()
			val (queue, applier) = makeApplier(modeState = state)

			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))
			applier.onControlStep()
			assertThat(approvedTrains).containsExactly("T1")

			state.setOverride(DispatcherMode.MANUAL)
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T2")))
			applier.onControlStep()

			// T2 must be dropped; approved list still contains only T1.
			assertThat(approvedTrains).containsExactly("T1")
		}

		@Test
		@DisplayName("Clearing an override returns to AUTO and reapplies decisions")
		fun clearOverride_returnsToAuto() {
			val state = DispatcherModeState().apply { setOverride(DispatcherMode.MANUAL) }
			val (queue, applier) = makeApplier(modeState = state)

			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))
			applier.onControlStep()
			assertThat(approvedTrains).isEmpty()

			state.clearOverride()
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T2")))
			applier.onControlStep()
			assertThat(approvedTrains).containsExactly("T2")
		}
	}
}
