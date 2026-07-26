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
import cz.vutbr.fit.interlockSim.ports.TrainLifecyclePort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatcherMode
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import cz.vutbr.fit.interlockSim.sim.SemiAutoApprovalGateway
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
 * Tests that [DispatchDecisionApplier] works correctly with [SemiAutoApprovalGateway] as the
 * `semiAutoApprover` (SP2b.6 follow-up — Issue #806).
 *
 * Verifies the end-to-end wiring pattern used by `ExampleRegistry.wireDispatcherAgent`:
 * the applier receives a `SemiAutoApprovalGateway` reference and uses `gateway::approve` as
 * its `semiAutoApprover`. The gateway's mutable approver is installed later (by
 * `Frame.wireDispatcherControlPanel`), bridging the construction-time / GUI-start-time gap.
 *
 * Covers:
 * - Gateway with no approver installed (headless/pre-wiring state) → decisions are dropped.
 * - Gateway with approver returning `true` → decisions are applied (gateway is transparent).
 * - Gateway with approver returning `false` → decisions are dropped.
 * - Clearing the approver after installation → subsequent decisions are dropped.
 *
 * @since Issue #806 (SP2b.6 follow-up — Goal 10)
 */
@DisplayName("DispatchDecisionApplier + SemiAutoApprovalGateway wiring (Issue #806)")
@Timeout(30, unit = TimeUnit.SECONDS)
class DispatchDecisionApplierSemiAutoGatewayTest {
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

	private fun makeApplierWithGateway(
		gateway: SemiAutoApprovalGateway
	): Pair<ActuatorCommandQueue, DispatchDecisionApplier> {
		val queue = ActuatorCommandQueue()
		val state = DispatcherModeState().apply { setOverride(DispatcherMode.SEMI_AUTO) }
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = onApproveTrain,
				trainLifecyclePort = trainLifecyclePort,
				modeState = state,
				semiAutoApprover = gateway::approve
			)
		return queue to applier
	}

	// ── Gateway with no approver installed ────────────────────────────────────

	@Nested
	@DisplayName("Gateway has no approver installed (headless / pre-wiring state)")
	inner class NoApproverInstalled {
		@Test
		@DisplayName("ApproveTrain is dropped when gateway has no approver")
		fun noApprover_dropsApproveTrain() {
			val gateway = SemiAutoApprovalGateway()
			val (queue, applier) = makeApplierWithGateway(gateway)
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))

			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
		}

		@Test
		@DisplayName("ReservePath is dropped when gateway has no approver")
		fun noApprover_dropsReservePath() {
			val gateway = SemiAutoApprovalGateway()
			val (queue, applier) = makeApplierWithGateway(gateway)
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zA", "doA1")))

			applier.onControlStep()

			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
		}
	}

	// ── Gateway with approver returning true ──────────────────────────────────

	@Nested
	@DisplayName("Gateway approver returns true (simulates operator Approve click)")
	inner class ApproverReturnsTrue {
		@Test
		@DisplayName("ApproveTrain is applied when gateway approves")
		fun approverTrue_appliesApproveTrain() {
			val gateway = SemiAutoApprovalGateway()
			gateway.setApprover { true }
			val (queue, applier) = makeApplierWithGateway(gateway)
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))

			applier.onControlStep()

			assertThat(approvedTrains).containsExactly("T1")
		}

		@Test
		@DisplayName("ReservePath is applied when gateway approves")
		fun approverTrue_appliesReservePath() {
			val gateway = SemiAutoApprovalGateway()
			gateway.setApprover { true }
			val (queue, applier) = makeApplierWithGateway(gateway)
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zA", "doA1")))

			applier.onControlStep()

			verify(exactly = 1) { networkActuator.requestRoute("T1", "zA", "doA1") }
		}
	}

	// ── Gateway with approver returning false ─────────────────────────────────

	@Nested
	@DisplayName("Gateway approver returns false (simulates operator Dismiss click)")
	inner class ApproverReturnsFalse {
		@Test
		@DisplayName("ApproveTrain is dropped when gateway dismisses")
		fun approverFalse_dropsApproveTrain() {
			val gateway = SemiAutoApprovalGateway()
			gateway.setApprover { false }
			val (queue, applier) = makeApplierWithGateway(gateway)
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))

			applier.onControlStep()

			assertThat(approvedTrains).isEmpty()
		}

		@Test
		@DisplayName("ReservePath is dropped when gateway dismisses")
		fun approverFalse_dropsReservePath() {
			val gateway = SemiAutoApprovalGateway()
			gateway.setApprover { false }
			val (queue, applier) = makeApplierWithGateway(gateway)
			queue.postAll(listOf(DispatchDecision.ReservePath("T1", "zA", "doA1")))

			applier.onControlStep()

			verify(exactly = 0) { networkActuator.requestRoute(any(), any(), any()) }
		}
	}

	// ── Clearing the approver after installation ──────────────────────────────

	@Test
	@DisplayName("Clearing the approver (simulates sim stop) drops subsequent decisions")
	fun clearApprover_dropsSubsequentDecisions() {
		val gateway = SemiAutoApprovalGateway()
		gateway.setApprover { true }
		val (queue, applier) = makeApplierWithGateway(gateway)

		// First control step: approver is installed, decision is applied.
		queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")))
		applier.onControlStep()
		assertThat(approvedTrains).containsExactly("T1")

		// Simulate sim stop: detach the approver (Frame.wireDispatcherControlPanel cleanup).
		gateway.setApprover(null)

		// Second control step: approver is gone, decision must be dropped.
		queue.postAll(listOf(DispatchDecision.ApproveTrain("T2")))
		applier.onControlStep()
		// List still contains only T1 — T2 was dropped.
		assertThat(approvedTrains).containsExactly("T1")
	}
}
