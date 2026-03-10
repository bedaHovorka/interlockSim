/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for StatusBar GUI component
	Phase 4: GUI and Simulation Tests - 2026
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.PROGRAM_NAME
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import java.awt.Component
import java.awt.event.MouseEvent
import cz.vutbr.fit.interlockSim.objects.cells.ContextChangeEvent

/**
 * Tests for StatusBar GUI component.
 *
 * Tests status bar initialization, mouse motion listener registration,
 * status message display, and property change handling.
 *
 * These tests use MockK for mocking Swing components and StatusProducer.
 */
@DisplayName("StatusBar")
class StatusBarTest : KoinTestBase() {
	private lateinit var statusBar: StatusBar

	override fun getTestModule(): Module = testModuleFull

	@BeforeEach
	fun setUp() {
		statusBar = StatusBar()
	}

	@Test
	@DisplayName("status bar initializes with welcome message")
	fun statusBarInitializesWithWelcomeMessage() {
		// Verify initial text contains program name
		assertThat(statusBar.text).contains(PROGRAM_NAME)
	}

	@Test
	@DisplayName("status bar has correct preferred size")
	fun statusBarHasCorrectPreferredSize() {
		// Verify preferred size is set
		assertThat(statusBar.preferredSize.width).isEqualTo(100)
		assertThat(statusBar.preferredSize.height).isEqualTo(25)
	}

	@Test
	@DisplayName("registers status producer mouse listener")
	fun registersStatusProducerMouseListener() {
		// Create mock producer that is also a Component
		val mockProducer = mockk<TestStatusProducer>(relaxed = true)

		// Register producer
		statusBar.registerProducer(mockProducer)

		// Verify mouse motion listener was added (exactly once)
		verify(exactly = 1) { mockProducer.addMouseMotionListener(any()) }
	}

	@Test
	@DisplayName("unregisters status producer mouse listener")
	fun unregistersStatusProducerMouseListener() {
		// Create mock producer that is also a Component
		val mockProducer = mockk<TestStatusProducer>(relaxed = true)

		// Register then unregister producer
		statusBar.registerProducer(mockProducer)
		statusBar.unregisterProducer(mockProducer)

		// Verify mouse motion listener was added and then removed
		verify(exactly = 1) { mockProducer.addMouseMotionListener(any()) }
		verify(exactly = 1) { mockProducer.removeMouseMotionListener(any()) }
	}

	@Test
	@DisplayName("updates status on mouse move from producer")
	fun updatesStatusOnMouseMoveFromProducer() {
		// Create a real producer that can handle mouse events
		val producer = TestStatusProducerImpl()

		// Register producer
		statusBar.registerProducer(producer)

		// Simulate mouse move
		val mouseEvent =
			MouseEvent(
				producer,
				MouseEvent.MOUSE_MOVED,
				System.currentTimeMillis(),
				0,
				10,
				20,
				0,
				false
			)

		// Trigger mouse moved event
		producer.fireMouseMove(mouseEvent)

		// Verify status bar text was updated
		assertThat(statusBar.text).isEqualTo("Test status: 10, 20")
	}

	@Test
	@DisplayName("handles property change with CharSequence value")
	fun handlesPropertyChangeWithCharSequenceValue() {
		// Create property change event with CharSequence
		val event = ContextChangeEvent("status", "old", "New status message")

		// Trigger property change
		statusBar.propertyChange(event)

		// Verify text was updated
		assertThat(statusBar.text).isEqualTo("New status message")
	}

	@Test
	@DisplayName("handles property change with non-CharSequence value")
	fun handlesPropertyChangeWithNonCharSequenceValue() {
		// Create property change event with Integer
		val event = ContextChangeEvent("status", null, 12345)

		// Trigger property change
		statusBar.propertyChange(event)

		// Verify text was updated with toString()
		assertThat(statusBar.text).isEqualTo("12345")
	}

	@Test
	@DisplayName("handles property change with null value")
	fun handlesPropertyChangeWithNullValue() {
		// Set initial text
		statusBar.text = "Initial text"

		// Create property change event with null new value
		val event = ContextChangeEvent("status", "old", null)

		// Trigger property change
		statusBar.propertyChange(event)

		// Verify text remains unchanged when new value is null
		assertThat(statusBar.text).isEqualTo("Initial text")
	}

	/**
	 * Test implementation of StatusProducer as a Component.
	 * This allows us to test the actual mouse listener behavior.
	 */
	private inner class TestStatusProducerImpl :
		Component(),
		StatusProducer {
		override fun getStatus(e: MouseEvent): String = "Test status: ${e.x}, ${e.y}"

		fun fireMouseMove(e: MouseEvent) {
			// Get all mouse motion listeners and fire event
			mouseMotionListeners.forEach { listener ->
				listener.mouseMoved(e)
			}
		}
	}

	/**
	 * Mock interface for testing that extends both Component and StatusProducer.
	 * MockK requires concrete types to mock both interfaces together.
	 */
	private abstract class TestStatusProducer :
		Component(),
		StatusProducer
}
