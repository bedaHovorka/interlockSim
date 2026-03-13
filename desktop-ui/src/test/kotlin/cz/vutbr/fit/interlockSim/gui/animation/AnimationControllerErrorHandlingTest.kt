/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Tests for AnimationController error handling and circuit breaker pattern.
 *
 * Verifies:
 * - Transient failures (< 3) are tolerated
 * - Persistent failures (3+ consecutive) trigger circuit breaker
 * - Circuit breaker stops animation and shows error dialog
 * - Error state resets on restart
 *
 * @see AnimationController
 */
@Tag("integration-test")
class AnimationControllerErrorHandlingTest : KoinTestBase() {
	private var context: SimulationContext? = null

	@BeforeEach
	fun setUp() {
		// Mock AnimationStateCapture to simulate failures
		mockkObject(AnimationStateCapture)
	}

	@AfterEach
	fun tearDown() {
		// Unmock AnimationStateCapture
		unmockkObject(AnimationStateCapture)

		// Clean up simulation context
		context?.close()
		context = null
	}

	/**
	 * Verify that transient failures (< 3) are tolerated without stopping animation.
	 */
	@Test
	fun toleratesTransientFailures() {
		runOnEDT {
			val (controller, context) = createController()

			// Mock to fail twice, then succeed
			// Note: start() calls captureAndUpdateState() once, so first failure happens during start()
			var callCount = 0
			every {
				AnimationStateCapture.captureState(
					context,
					any<List<DynamicRailSemaphore>>(),
					any<List<DynamicRailSwitch>>()
				)
			} answers {
				callCount++
				if (callCount <= 2) {
					throw RuntimeException("Simulated transient failure $callCount")
				} else {
					// Return valid state after 2 failures
					AnimationState.EMPTY
				}
			}

			// Start controller (calls captureAndUpdateState internally - Failure 1)
			controller.start()
			assertThat(isRunning(controller)).isTrue()
			assertThat(controller.getConsecutiveFailures()).isEqualTo(1)

			// Trigger state captures (simulate PropertyChangeEvents)
			triggerStateCapture(controller) // Failure 2
			assertThat(controller.getConsecutiveFailures()).isEqualTo(2)
			assertThat(controller.isInErrorState()).isFalse()
			assertThat(isRunning(controller)).isTrue()

			triggerStateCapture(controller) // Success
			assertThat(controller.getConsecutiveFailures()).isEqualTo(0) // Reset
			assertThat(controller.isInErrorState()).isFalse()
			assertThat(isRunning(controller)).isTrue()

			// Clean up
			controller.stop()
		}
	}

	/**
	 * Verify that persistent failures (3+ consecutive) trigger circuit breaker.
	 */
	@Test
	fun stopAnimationOnPersistentFailures() {
		runOnEDT {
			val (controller, context) = createController()

			// Mock to always fail
			// Note: start() calls captureAndUpdateState() once, so first failure happens during start()
			every {
				AnimationStateCapture.captureState(
					context,
					any<List<DynamicRailSemaphore>>(),
					any<List<DynamicRailSwitch>>()
				)
			} throws RuntimeException("Simulated persistent failure")

			// Start controller (calls captureAndUpdateState internally - Failure 1)
			controller.start()
			assertThat(isRunning(controller)).isTrue()
			assertThat(controller.getConsecutiveFailures()).isEqualTo(1)
			val timer = getRepaintTimer(controller)
			assertThat(timer.isRunning).isTrue()

			// Trigger state captures (simulate PropertyChangeEvents)
			triggerStateCapture(controller) // Failure 2
			assertThat(controller.getConsecutiveFailures()).isEqualTo(2)
			assertThat(controller.isInErrorState()).isFalse()
			assertThat(timer.isRunning).isTrue()

			triggerStateCapture(controller) // Failure 3 - circuit breaker trips
			assertThat(controller.getConsecutiveFailures()).isEqualTo(3)
			assertThat(controller.isInErrorState()).isTrue()
			assertThat(timer.isRunning).isFalse() // Timer stopped by circuit breaker

			// Note: Error dialog is shown via SwingUtilities.invokeLater (not tested here)

			// Clean up
			controller.stop()
		}
	}

	/**
	 * Verify that error state resets when restarting controller.
	 */
	@Test
	fun resetsErrorStateOnRestart() {
		runOnEDT {
			val (controller, context) = createController()

			// Mock to always fail
			// Note: start() calls captureAndUpdateState() once, so we need 2 more triggers for 3 total
			every {
				AnimationStateCapture.captureState(
					context,
					any<List<DynamicRailSemaphore>>(),
					any<List<DynamicRailSwitch>>()
				)
			} throws RuntimeException("Simulated failure")

			// Start controller and trigger circuit breaker (1 call in start + 2 triggers = 3 failures)
			controller.start()
			assertThat(controller.getConsecutiveFailures()).isEqualTo(1)
			repeat(2) { triggerStateCapture(controller) }
			assertThat(controller.isInErrorState()).isTrue()
			assertThat(controller.getConsecutiveFailures()).isEqualTo(3)

			// Stop controller
			controller.stop()

			// Mock to succeed on restart
			every {
				AnimationStateCapture.captureState(
					context,
					any<List<DynamicRailSemaphore>>(),
					any<List<DynamicRailSwitch>>()
				)
			} returns AnimationState.EMPTY

			// Restart controller (calls captureAndUpdateState, which now succeeds)
			controller.start()

			// Verify error state is reset
			assertThat(controller.isInErrorState()).isFalse()
			assertThat(controller.getConsecutiveFailures()).isEqualTo(0)
			assertThat(isRunning(controller)).isTrue()

			// Clean up
			controller.stop()
		}
	}

	// Reflection-based accessors for private fields

	private fun isRunning(controller: AnimationController): Boolean {
		val field = AnimationController::class.java.getDeclaredField("isRunning")
		field.isAccessible = true
		return field.getBoolean(controller)
	}

	private fun getRepaintTimer(controller: AnimationController): Timer {
		val field = AnimationController::class.java.getDeclaredField("repaintTimer")
		field.isAccessible = true
		return field.get(controller) as Timer
	}

	/**
	 * Trigger state capture directly (bypasses PropertyChangeEvent marshaling).
	 */
	private fun triggerStateCapture(controller: AnimationController) {
		val method = AnimationController::class.java.getDeclaredMethod("captureAndUpdateState")
		method.isAccessible = true
		method.invoke(controller)
	}

	// Test utilities

	private fun createController(): Pair<AnimationController, SimulationContext> {
		// Use vyhybna.xml for realistic simulation context
		context = createMockSimulationContext(TestFixtures.loadShuntingXml())

		// Create dummy canvas (JPanel is concrete Component subclass)
		val canvas = JPanel()

		// Create controller
		val controller = AnimationController(context!!, canvas, null)

		return Pair(controller, context!!)
	}

	private fun runOnEDT(block: () -> Unit) {
		if (SwingUtilities.isEventDispatchThread()) {
			block()
		} else {
			SwingUtilities.invokeAndWait(block)
		}
	}
}
