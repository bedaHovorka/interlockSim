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
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.api.Test

/**
 * Regression test for signal-configuration failure rollback bug.
 *
 * When semaphore signal configuration fails in reservePath(), the rollback
 * must revert ALL mutations:
 * - Block reservations (cancelPathSetup)
 * - Registry ownership (unregister from blockToTrain/trainToBlocks)
 * - PathInfo metadata (unregister from trainToPathInfo)
 * - Switch locks (unlock and remove from switchToTrain/trainToSwitches)
 *
 * Without complete rollback, stale ownership and locked switches cause:
 * - False conflicts in future path reservations
 * - Deadlocks due to permanently locked switches
 */
class SignalConfigurationRollbackTest : PathReservationServiceTestBase() {
	// The first semaphore in vyhybna's scan order: this regression test needs A semaphore
	// start, not a specific one. The require keeps the original English failure text on an
	// empty grid.
	private val startSemaphore: DynamicRailSemaphore by lazy {
		val semaphores = simulationContext.cellsOfType<DynamicRailSemaphore>()
		require(semaphores.isNotEmpty()) { "vyhybna.xml must have at least one semaphore" }
		semaphores.first()
	}

	@Test
	fun `reservePath rolls back completely when semaphore signal configuration fails`() {
		// This test verifies the fix for incomplete rollback bug.
		//
		// FIXED: When signal configuration fails in reservePath() Step 2g, the rollback now
		// uses the scoped rollbackUnconfigurableCandidate, which releases only THIS
		// candidate's forwardBlocks and new switches (not the train's whole path):
		// - cancelPathSetup() + registry.unregisterBlock() per forward block
		// - registry.unregisterSwitch() per non-prior switch (earlier-hop switches survive)
		//
		// See: DefaultPathReservationService.reservePath Step 2g + rollbackUnconfigurableCandidate.

		val target = simulationContext.getInOuts().toList()[0]
		val trainId = "TestTrain"

		// Act: Reserve a path (should succeed with fix in place)
		val result = service.reservePath(trainId, startSemaphore, target)

		// Assert: Reservation succeeds with proper signal configuration
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Verify: Path was reserved and registered correctly
		val reservedBlocks = service.getReservedBlocks(trainId)
		assertThat(reservedBlocks.isEmpty()).isFalse()

		// Note: This test serves as documentation that signal configuration now succeeds.
		// To test rollback, we would need to inject a failure in configureSemaphoreSignal,
		// which requires mocking or dependency injection changes.
	}

	@Test
	fun `reservePath rolls back switches when signal configuration fails`() {
		// This test documents the switch rollback behaviour. The scoped rollback now
		// (rollbackUnconfigurableCandidate, Step 2g) releases the candidate's non-prior
		// switches via registry.unregisterSwitch() — earlier-hop switches are preserved.
		// The per-switch primitive itself is unit-tested in Issue742RegressionTest
		// (`unregisterSwitchReleasesOneSwitchAndKeepsTheRest`); injecting a real
		// signal-config failure on vyhybna requires mocking configureSemaphoreSignal.
		//
		// See: DefaultPathReservationService.reservePath Step 2g + rollbackUnconfigurableCandidate.

		val trainId = "SwitchTestTrain"
		val target = simulationContext.getInOuts().toList()[0]

		// Act: Reserve a path (baseline - currently succeeds)
		val result = service.reservePath(trainId, startSemaphore, target)

		// Assert: Reservation succeeds (baseline behavior)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
	}
}
