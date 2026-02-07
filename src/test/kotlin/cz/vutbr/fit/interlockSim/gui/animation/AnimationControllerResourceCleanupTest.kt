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
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Tests for AnimationController resource cleanup and AutoCloseable implementation.
 *
 * Verifies:
 * - stop() is idempotent (safe to call multiple times)
 * - close() delegates to stop() and is idempotent
 * - PropertyChangeListener is unregistered
 * - Swing Timer is stopped
 * - Caches are cleared for GC
 *
 * @see AnimationController
 */
@Tag("integration-test")
class AnimationControllerResourceCleanupTest : KoinTestBase() {
	private var context: SimulationContext? = null

	@AfterEach
	fun tearDown() {
		// Clean up simulation context
		context?.close()
		context = null
	}

	/**
	 * Verify stop() can be called multiple times safely (idempotent).
	 */
	@Test
	fun stopIsIdempotent() {
		runOnEDT {
			val (controller, _) = createController()

			// Start controller
			controller.start()
			assertThat(isRunning(controller)).isTrue()

			// Stop once
			controller.stop()
			assertThat(isRunning(controller)).isFalse()

			// Stop again - should not throw exception
			controller.stop()
			assertThat(isRunning(controller)).isFalse()

			// Stop third time - still safe
			controller.stop()
			assertThat(isRunning(controller)).isFalse()
		}
	}

	/**
	 * Verify close() delegates to stop() and is idempotent.
	 */
	@Test
	fun closeIsIdempotent() {
		runOnEDT {
			val (controller, _) = createController()

			// Start controller
			controller.start()
			assertThat(isRunning(controller)).isTrue()

			// Close once
			controller.close()
			assertThat(isRunning(controller)).isFalse()

			// Close again - should not throw exception
			controller.close()
			assertThat(isRunning(controller)).isFalse()

			// Close third time - still safe
			controller.close()
			assertThat(isRunning(controller)).isFalse()
		}
	}

	/**
	 * Verify PropertyChangeListener is unregistered after stop().
	 */
	@Test
	fun stopUnregistersListener() {
		runOnEDT {
			val (controller, context) = createController()

			// Start controller (registers listener)
			controller.start()

			// Verify listener is registered by checking if controller is in listener array
			val listenersBefore = getListeners(context)
			val containsControllerBefore = listenersBefore.any { it === controller }
			assertThat(containsControllerBefore).isTrue()

			// Stop controller (unregisters listener)
			controller.stop()

			// Verify listener is unregistered
			val listenersAfter = getListeners(context)
			val containsControllerAfter = listenersAfter.any { it === controller }
			assertThat(containsControllerAfter).isFalse()
		}
	}

	/**
	 * Verify Swing Timer is stopped after stop().
	 */
	@Test
	fun stopStopsTimer() {
		runOnEDT {
			val (controller, _) = createController()

			// Start controller (starts timer)
			controller.start()
			val timer = getRepaintTimer(controller)
			assertThat(timer.isRunning).isTrue()

			// Stop controller (stops timer)
			controller.stop()
			assertThat(timer.isRunning).isFalse()
		}
	}

	/**
	 * Verify caches are cleared after stop() for GC.
	 */
	@Test
	fun stopClearsCaches() {
		runOnEDT {
			val (controller, _) = createController()

			// Start controller (populates caches)
			controller.start()
			assertThat(getSemaphoreCache(controller)).isNotNull()
			assertThat(getSwitchCache(controller)).isNotNull()

			// Stop controller (clears caches)
			controller.stop()
			assertThat(getSemaphoreCache(controller)).isNull()
			assertThat(getSwitchCache(controller)).isNull()
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

	private fun getSemaphoreCache(controller: AnimationController): List<*>? {
		val field = AnimationController::class.java.getDeclaredField("semaphoreCache")
		field.isAccessible = true
		return field.get(controller) as List<*>?
	}

	private fun getSwitchCache(controller: AnimationController): List<*>? {
		val field = AnimationController::class.java.getDeclaredField("switchCache")
		field.isAccessible = true
		return field.get(controller) as List<*>?
	}

	// Helper to get registered listeners via reflection
	private fun getListeners(context: SimulationContext): List<Any> {
		// Unwrap MockSimulationContext to get BaseContext
		val actualContext: Any =
			if (context is cz.vutbr.fit.interlockSim.testutil.MockSimulationContext) {
				val delegateField =
					cz.vutbr.fit.interlockSim.testutil.MockSimulationContext::class.java.getDeclaredField(
						"delegate"
					)
				delegateField.isAccessible = true
				delegateField.get(context)
			} else {
				context
			}

		val field = actualContext.javaClass.superclass.getDeclaredField("changeSupport")
		field.isAccessible = true
		val changeSupport = field.get(actualContext) as java.beans.PropertyChangeSupport
		return changeSupport.propertyChangeListeners.toList()
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
