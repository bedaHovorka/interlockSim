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
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [SemiAutoApprovalGateway] (SP2b.6 follow-up — Issue #806).
 *
 * Verifies:
 * 1. With no approver installed, [SemiAutoApprovalGateway.approve] returns `false`.
 * 2. After [SemiAutoApprovalGateway.setApprover], approve delegates to the installed callback.
 * 3. Replacing the approver swaps the callback.
 * 4. `setApprover(null)` detaches (subsequent calls return `false`).
 * 5. Concurrent setApprover + approve do not corrupt state (last-writer-wins approver).
 *
 * @since Issue #806 (SP2b.6 follow-up — Goal 10)
 */
class SemiAutoApprovalGatewayTest {
	@Test
	fun noApprover_approve_returnsFalse() {
		val gateway = SemiAutoApprovalGateway()
		val decision = DispatchDecision.ApproveTrain("T1")

		assertThat(gateway.approve(decision)).isFalse()
	}

	@Test
	fun setApprover_returningTrue_approvesDecision() {
		val gateway = SemiAutoApprovalGateway()
		gateway.setApprover { true }

		assertThat(gateway.approve(DispatchDecision.ApproveTrain("T1"))).isTrue()
	}

	@Test
	fun setApprover_returningFalse_dropsDecision() {
		val gateway = SemiAutoApprovalGateway()
		gateway.setApprover { false }

		assertThat(gateway.approve(DispatchDecision.ApproveTrain("T1"))).isFalse()
	}

	@Test
	fun approve_passesDecisionToApprover() {
		val gateway = SemiAutoApprovalGateway()
		var received: DispatchDecision? = null
		gateway.setApprover { d ->
			received = d
			true
		}

		val decision = DispatchDecision.ReservePath("T1", "zA", "doA1")
		gateway.approve(decision)

		assertThat(received === decision).isTrue()
	}

	@Test
	fun setApprover_replacesPreviousApprover() {
		val gateway = SemiAutoApprovalGateway()
		var firstCalled = false
		var secondCalled = false

		gateway.setApprover {
			firstCalled = true
			true
		}
		gateway.setApprover {
			secondCalled = true
			false
		}

		assertThat(gateway.approve(DispatchDecision.ApproveTrain("T1"))).isFalse()
		assertThat(firstCalled).isFalse()
		assertThat(secondCalled).isTrue()
	}

	@Test
	fun setApproverNull_detachesApprover() {
		val gateway = SemiAutoApprovalGateway()
		gateway.setApprover { true }
		gateway.setApprover(null)

		assertThat(gateway.approve(DispatchDecision.ApproveTrain("T1"))).isFalse()
	}

	@Test
	fun concurrentSetApproverAndApprove_doesNotCorrupt() {
		runBlocking {
			val gateway = SemiAutoApprovalGateway()
			val lock = SynchronizedObject()
			var callCount = 0

			withTimeout(5.seconds) {
				val jobs =
					(1..50).map { i ->
						launch(Dispatchers.Default) {
							if (i % 2 == 0) {
								gateway.setApprover {
									synchronized(lock) { callCount++ }
									true
								}
							} else {
								gateway.approve(DispatchDecision.ApproveTrain("T$i"))
							}
						}
					}
				jobs.joinAll()
			}

			// No exception thrown; the gateway is still usable after concurrent access.
			gateway.setApprover { true }
			assertThat(gateway.approve(DispatchDecision.ApproveTrain("final"))).isTrue()
		}
	}
}
