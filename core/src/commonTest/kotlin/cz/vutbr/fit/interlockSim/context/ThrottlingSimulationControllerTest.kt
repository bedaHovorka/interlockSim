/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit tests for ThrottlingSimulationController.
 * Added for Issue #873 (SP2c.26 follow-up I2 — headless pacing controller).
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Tests for [ThrottlingSimulationController] verifying that the headless pacing
 * implementation throttles correctly and behaves neutrally on pause/step operations.
 */
class ThrottlingSimulationControllerTest {

	@Test
	fun `isPaused returns false`() {
		val controller = ThrottlingSimulationController()
		assertThat(controller.isPaused()).isFalse()
	}

	@Test
	fun `pollStepEvent returns false`() {
		val controller = ThrottlingSimulationController()
		assertThat(controller.pollStepEvent()).isFalse()
	}

	@Test
	fun `pollStepTime returns null`() {
		val controller = ThrottlingSimulationController()
		assertThat(controller.pollStepTime()).isNull()
	}

	@Test
	fun `awaitIfPaused returns immediately`() =
		runBlocking {
			val controller = ThrottlingSimulationController()
			// Should complete without suspending
			controller.awaitIfPaused()
		}

	@Test
	fun `throttle with zero delta does not block`() {
		val controller = ThrottlingSimulationController(initialSpeedMultiplier = ThrottlingSimulationController.MAX_SPEED)
		// Should return immediately for zero or negative deltas regardless of speed
		controller.throttle(0.0)
		controller.throttle(-1.0)
	}

	@Test
	fun `requestPause does nothing`() {
		val controller = ThrottlingSimulationController()
		// Should not throw — headless implementation ignores pause requests
		controller.requestPause()
		assertThat(controller.isPaused()).isFalse()
	}

	@Test
	fun `requestResume does nothing`() {
		val controller = ThrottlingSimulationController()
		// Should not throw — headless implementation is never paused
		controller.requestResume()
		assertThat(controller.isPaused()).isFalse()
	}

	@Test
	fun `requestResume after requestPause leaves controller unpaused`() {
		val controller = ThrottlingSimulationController()
		controller.requestPause()
		controller.requestResume()
		assertThat(controller.isPaused()).isFalse()
	}

	@Test
	fun `implements SimulationController`() {
		val controller: SimulationController = ThrottlingSimulationController()
		assertThat(controller).isInstanceOf(ThrottlingSimulationController::class)
	}

	@Test
	fun `default speedMultiplier is DEFAULT_SPEED`() {
		val controller = ThrottlingSimulationController()
		assertThat(controller.speedMultiplier).isEqualTo(ThrottlingSimulationController.DEFAULT_SPEED)
	}

	@Test
	fun `custom speedMultiplier is applied`() {
		val controller = ThrottlingSimulationController(initialSpeedMultiplier = 2.0)
		assertThat(controller.speedMultiplier).isEqualTo(2.0)
	}

	@Test
	fun `speedMultiplier can be updated`() {
		val controller = ThrottlingSimulationController(initialSpeedMultiplier = 1.0)
		controller.speedMultiplier = 5.0
		assertThat(controller.speedMultiplier).isEqualTo(5.0)
	}

	@Test
	fun `constructor rejects speedMultiplier below MIN_SPEED`() {
		assertFailure {
			ThrottlingSimulationController(initialSpeedMultiplier = ThrottlingSimulationController.MIN_SPEED - 0.01)
		}.isInstanceOf(IllegalArgumentException::class)
	}

	@Test
	fun `constructor rejects speedMultiplier above MAX_SPEED`() {
		assertFailure {
			ThrottlingSimulationController(initialSpeedMultiplier = ThrottlingSimulationController.MAX_SPEED + 1.0)
		}.isInstanceOf(IllegalArgumentException::class)
	}

	@Test
	fun `constructor accepts MIN_SPEED`() {
		// Should not throw
		ThrottlingSimulationController(initialSpeedMultiplier = ThrottlingSimulationController.MIN_SPEED)
	}

	@Test
	fun `constructor accepts MAX_SPEED`() {
		// Should not throw
		ThrottlingSimulationController(initialSpeedMultiplier = ThrottlingSimulationController.MAX_SPEED)
	}
}
