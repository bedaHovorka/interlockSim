/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit tests for SimulationController interface and NoOpSimulationController.
 * Added for Issue #498 (Goal 8 Phase 1.1).
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Tests for [NoOpSimulationController] verifying that the no-op implementation
 * behaves neutrally — never pauses, never throttles, never reports step requests.
 */
class SimulationControllerTest {
	@Test
	fun `NoOpSimulationController isPaused returns false`() {
		assertThat(NoOpSimulationController.isPaused()).isFalse()
	}

	@Test
	fun `NoOpSimulationController pollStepEvent returns false`() {
		assertThat(NoOpSimulationController.pollStepEvent()).isFalse()
	}

	@Test
	fun `NoOpSimulationController pollStepTime returns null`() {
		assertThat(NoOpSimulationController.pollStepTime()).isNull()
	}

	@Test
	fun `NoOpSimulationController awaitIfPaused returns immediately`() = runBlocking {
		// Should complete without suspending
		NoOpSimulationController.awaitIfPaused()
	}

	@Test
	fun `NoOpSimulationController throttle does nothing`() {
		// Should not throw or block
		NoOpSimulationController.throttle(0.0)
		NoOpSimulationController.throttle(1.0)
		NoOpSimulationController.throttle(100.0)
	}

	@Test
	fun `NoOpSimulationController implements SimulationController`() {
		val controller: SimulationController = NoOpSimulationController
		assertThat(controller).isSameInstanceAs(NoOpSimulationController)
	}
}
