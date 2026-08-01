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
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.TimeSource

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

	/**
	 * Proves `throttle()` actually sleeps — the core pacing behavior that makes this controller
	 * satisfy R8 (a real pacing controller, not a no-op-in-disguise). `assertPlannerPacingCompatible`
	 * only checks the controller is not `NoOpSimulationController`; it cannot verify pacing happens,
	 * so that invariant must be guarded here. Lower-bound only: a sleep never returns *early*, so
	 * scheduler jitter only lengthens the elapsed time and cannot make these assertions flaky.
	 */
	@Test
	fun `throttle sleeps at least the scaled wall-clock duration at 1x`() {
		val controller = ThrottlingSimulationController(initialSpeedMultiplier = 1.0)
		val mark = TimeSource.Monotonic.markNow()
		controller.throttle(0.05) // intended ~50 ms at 1.0x
		val elapsedMs = mark.elapsedNow().inWholeMilliseconds
		// >=30 ms proves a real sleep happened (intended 50 ms with jitter margin)
		assertThat(elapsedMs).isGreaterThanOrEqualTo(30L)
	}

	/**
	 * Proves the sleep scales with the simulation-time delta: a larger delta yields a longer sleep.
	 */
	@Test
	fun `throttle sleep scales with sim-time delta`() {
		val controller = ThrottlingSimulationController(initialSpeedMultiplier = 1.0)
		val shortMark = TimeSource.Monotonic.markNow()
		controller.throttle(0.05) // intended ~50 ms
		val shortMs = shortMark.elapsedNow().inWholeMilliseconds
		val longMark = TimeSource.Monotonic.markNow()
		controller.throttle(0.1) // intended ~100 ms
		val longMs = longMark.elapsedNow().inWholeMilliseconds
		assertThat(shortMs).isGreaterThanOrEqualTo(30L)
		assertThat(longMs).isGreaterThanOrEqualTo(80L) // bigger delta => bigger sleep
	}

	/**
	 * Proves the sleep is inversely proportional to the speed multiplier: a faster multiplier
	 * yields a shorter sleep. The 100 ms vs ~12.5 ms gap is large enough that the ordering
	 * (`fast < slow`) is robust under jitter, since monotonic sleeps guarantee the slow one
	 * (intended 100 ms) cannot finish earlier than the fast one (intended 12.5 ms).
	 */
	@Test
	fun `throttle sleep is inversely proportional to speed multiplier`() {
		val slow = ThrottlingSimulationController(initialSpeedMultiplier = 0.5) // 0.05s -> ~100 ms
		val fast = ThrottlingSimulationController(initialSpeedMultiplier = 4.0) // 0.05s -> ~12.5 ms
		val slowMark = TimeSource.Monotonic.markNow()
		slow.throttle(0.05)
		val slowMs = slowMark.elapsedNow().inWholeMilliseconds
		val fastMark = TimeSource.Monotonic.markNow()
		fast.throttle(0.05)
		val fastMs = fastMark.elapsedNow().inWholeMilliseconds
		assertThat(slowMs).isGreaterThanOrEqualTo(70L) // ~100 ms with jitter margin
		assertThat(fastMs).isGreaterThanOrEqualTo(8L) // ~12.5 ms with jitter margin
		assertThat(fastMs).isLessThan(slowMs) // faster multiplier => shorter sleep
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
