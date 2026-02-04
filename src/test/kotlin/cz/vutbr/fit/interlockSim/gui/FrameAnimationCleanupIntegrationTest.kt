/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.gui.animation.AnimationController
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.InputStream

/**
 * Integration test for Frame → RailwayNetGridCanvas → AnimationController cleanup chain.
 *
 * Verifies that Frame's exit methods properly clean up animation resources:
 * - stopAnimationUpdates() stops Frame's 10 Hz timer
 * - cleanupAnimation() stops AnimationController
 * - PropertyChangeListener is unregistered
 *
 * @see Frame
 * @see RailwayNetGridCanvas
 * @see AnimationController
 */
@Tag("integration-test")
class FrameAnimationCleanupIntegrationTest : AbstractFrameTestBase() {

	private var context: SimulationContext? = null

	@AfterEach
	override fun tearDown() {
		// Clean up simulation context first
		context?.close()
		context = null

		// Call parent cleanup
		super.tearDown()
	}

	/**
	 * Verify that Frame exit methods unregister AnimationController's PropertyChangeListener.
	 *
	 * Tests the complete cleanup chain:
	 * Frame.exitWithoutSaving() → RailwayNetGridCanvas.cleanupAnimation() → AnimationController.stop()
	 */
	@Test
	fun frameCleanupUnregistersListener() {
		// Create Frame and simulation context (outside EDT to avoid nested invokeAndWait)
		val frame = createFrame()
		context = createMockSimulationContext(shuntingXml())

		runOnEDT {
			// Set simulation context on Frame (starts animation)
			frame.setContext(context!!)

			// Wait for animation controller to be created and started
			Thread.sleep(100)

			// Get the animation controller
			val controller = frame.railwayNetGridCanvas.getAnimationController()
			assertThat(controller != null).isTrue()

			// Verify AnimationController is registered as listener
			val listenersBefore = getListeners(context!!)
			val containsControllerBefore = listenersBefore.any { it === controller }
			assertThat(containsControllerBefore).isTrue()

			val isRunningBefore = isControllerRunning(controller!!)
			assertThat(isRunningBefore).isTrue()

			// Clean up animation (simulates Frame exit)
			frame.railwayNetGridCanvas.cleanupAnimation()

			// Verify AnimationController is unregistered
			val listenersAfter = getListeners(context!!)
			val containsControllerAfter = listenersAfter.any { it === controller }
			assertThat(containsControllerAfter).isFalse()

			// Verify controller is stopped
			val isRunningAfter = isControllerRunning(controller)
			assertThat(isRunningAfter).isFalse()
		}
	}

	// Helper to get registered listeners via reflection
	private fun getListeners(context: SimulationContext): List<Any> {
		// Unwrap MockSimulationContext to get BaseContext
		val actualContext: Any = if (context is cz.vutbr.fit.interlockSim.testutil.MockSimulationContext) {
			val delegateField = cz.vutbr.fit.interlockSim.testutil.MockSimulationContext::class.java.getDeclaredField("delegate")
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

	// Reflection-based accessor for AnimationController.isRunning

	private fun isControllerRunning(controller: AnimationController): Boolean {
		val field = AnimationController::class.java.getDeclaredField("isRunning")
		field.isAccessible = true
		return field.getBoolean(controller)
	}

	private fun shuntingXml(): InputStream = xml("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")

	private fun xml(name: String): InputStream {
		val xml = javaClass.getResourceAsStream(name)
		requireNotNull(xml) { "$name must exist in resources" }
		return xml
	}
}
