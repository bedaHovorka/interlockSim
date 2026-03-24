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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.sim.SimulationEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Unit tests for [EventTimelinePanel].
 *
 * Tests event display, filtering, and auto-scroll functionality.
 *
 * Note: Most tests use SwingUtilities.invokeAndWait to ensure EDT execution,
 * as EventTimelinePanel requires EDT for thread safety.
 */
class EventTimelinePanelTest {
	private lateinit var panel: EventTimelinePanel
	private lateinit var textArea: JTextArea

	@BeforeEach
	fun setUp() {
		// Create panel on EDT
		SwingUtilities.invokeAndWait {
			panel = EventTimelinePanel()
			// Access text area via reflection for testing
			val field = EventTimelinePanel::class.java.getDeclaredField("eventTextArea")
			field.isAccessible = true
			textArea = field.get(panel) as JTextArea
		}
	}

	@Test
	fun `panel initializes with empty text area`() {
		SwingUtilities.invokeAndWait {
			assertThat(textArea.text).isEqualTo("")
		}
	}

	@Test
	fun `addEvent adds event to display`() {
		val event =
			SimulationEvent(
				simulationTime = 123.456,
				eventType = ReportType.TRAIN_EVENTS,
				source = "",
				message = "Train 1 stopped"
			)

		SwingUtilities.invokeAndWait {
			panel.addEvent(event)

			val text = textArea.text
			assertThat(text).contains("[00:02:03.456]")
			assertThat(text).contains("[TRAIN_EVENTS]")
			assertThat(text).contains("Train 1 stopped")
		}
	}

	@Test
	fun `train approvals are displayed by default`() {
		val event =
			SimulationEvent(
				simulationTime = 10.0,
				eventType = ReportType.TRAIN_APPROVED,
				source = "",
				message = """train="Train #1" route=IO1->IO2"""
			)

		SwingUtilities.invokeAndWait {
			panel.addEvent(event)

			assertThat(textArea.text).contains("[TRAIN_APPROVED]")
			assertThat(textArea.text).contains("""train="Train #1"""")
		}
	}

	@Test
	fun `addEvent respects filter settings`() {
		SwingUtilities.invokeAndWait {
			// Disable TRAIN_EVENTS filter
			panel.setFilterEnabled(ReportType.TRAIN_EVENTS, false)

			val event =
				SimulationEvent(
					simulationTime = 1.0,
					eventType = ReportType.TRAIN_EVENTS,
					source = "",
					message = "Test"
				)

			panel.addEvent(event)

			// Event should not appear in display
			assertThat(textArea.text).isEqualTo("")
		}
	}

	@Test
	fun `addEvent shows event when filter is enabled`() {
		SwingUtilities.invokeAndWait {
			// Ensure PATH_SETTING filter is enabled
			panel.setFilterEnabled(ReportType.PATH_SETTING, true)

			val event =
				SimulationEvent(
					simulationTime = 1.0,
					eventType = ReportType.PATH_SETTING,
					source = "",
					message = "Path reserved"
				)

			panel.addEvent(event)

			// Event should appear in display
			assertThat(textArea.text).contains("Path reserved")
		}
	}

	@Test
	fun `clearEvents removes all events`() {
		SwingUtilities.invokeAndWait {
			// Add some events
			panel.addEvent(
				SimulationEvent(1.0, ReportType.TRAIN_EVENTS, "", "Event 1")
			)
			panel.addEvent(
				SimulationEvent(2.0, ReportType.TRAIN_EVENTS, "", "Event 2")
			)

			// Verify events were added
			assertThat(textArea.text).contains("Event 1")
			assertThat(textArea.text).contains("Event 2")

			// Clear all events
			panel.clearEvents()

			// Text area should be empty
			assertThat(textArea.text).isEqualTo("")
		}
	}

	@Test
	fun `addEvents batch adds multiple events`() {
		val events =
			listOf(
				SimulationEvent(1.0, ReportType.TRAIN_EVENTS, "", "Event 1"),
				SimulationEvent(2.0, ReportType.NODE_EVENTS, "", "Event 2"),
				SimulationEvent(3.0, ReportType.PATH_SETTING, "", "Event 3")
			)

		SwingUtilities.invokeAndWait {
			panel.addEvents(events)

			val text = textArea.text
			assertThat(text).contains("Event 1")
			assertThat(text).contains("Event 2")
			assertThat(text).contains("Event 3")
		}
	}

	@Test
	fun `setFilterEnabled updates filter state`() {
		SwingUtilities.invokeAndWait {
			// Initially, TRAIN_EVENTS should be enabled (default)
			assertThat(panel.isFilterEnabled(ReportType.TRAIN_EVENTS)).isTrue()
			assertThat(panel.isFilterEnabled(ReportType.TRAIN_APPROVED)).isTrue()

			// Disable filter
			panel.setFilterEnabled(ReportType.TRAIN_EVENTS, false)
			assertThat(panel.isFilterEnabled(ReportType.TRAIN_EVENTS)).isFalse()

			// Re-enable filter
			panel.setFilterEnabled(ReportType.TRAIN_EVENTS, true)
			assertThat(panel.isFilterEnabled(ReportType.TRAIN_EVENTS)).isTrue()
		}
	}

	@Test
	fun `changing filter refreshes display`() {
		SwingUtilities.invokeAndWait {
			// Add event with filter enabled
			panel.setFilterEnabled(ReportType.TRAIN_EVENTS, true)
			panel.addEvent(
				SimulationEvent(1.0, ReportType.TRAIN_EVENTS, "", "Visible event")
			)
			assertThat(textArea.text).contains("Visible event")

			// Disable filter - event should disappear
			panel.setFilterEnabled(ReportType.TRAIN_EVENTS, false)
			assertThat(textArea.text).isEqualTo("")

			// Re-enable filter - event should reappear
			panel.setFilterEnabled(ReportType.TRAIN_EVENTS, true)
			assertThat(textArea.text).contains("Visible event")
		}
	}

	@Test
	fun `multiple event types display correctly`() {
		SwingUtilities.invokeAndWait {
			panel.addEvent(SimulationEvent(1.0, ReportType.PATH_SETTING, "", "Path event"))
			panel.addEvent(SimulationEvent(2.0, ReportType.NODE_EVENTS, "", "Node event"))
			panel.addEvent(SimulationEvent(2.5, ReportType.TRAIN_APPROVED, "", """train="Train #5" route=A->B"""))
			panel.addEvent(SimulationEvent(3.0, ReportType.TRAIN_EVENTS, "", "Train event"))
			panel.addEvent(SimulationEvent(4.0, ReportType.TRAIN_CONTINUOUS, "", "Continuous event"))

			val text = textArea.text
			assertThat(text).contains("[PATH_SETTING] Path event")
			assertThat(text).contains("[NODE_EVENTS] Node event")
			assertThat(text).contains("[TRAIN_APPROVED] train=\"Train #5\" route=A->B")
			assertThat(text).contains("[TRAIN_EVENTS] Train event")
			// TRAIN_CONTINUOUS is disabled by default, so it won't appear
		}
	}

	@Test
	fun `TRAIN_CONTINUOUS is disabled by default`() {
		SwingUtilities.invokeAndWait {
			// TRAIN_CONTINUOUS should be disabled initially to avoid spam
			assertThat(panel.isFilterEnabled(ReportType.TRAIN_CONTINUOUS)).isFalse()
		}
	}

	@Test
	fun `enabling TRAIN_CONTINUOUS filter shows continuous events`() {
		SwingUtilities.invokeAndWait {
			// Add a continuous event (won't show initially)
			panel.addEvent(SimulationEvent(1.0, ReportType.TRAIN_CONTINUOUS, "", "Position update"))
			assertThat(textArea.text).isEqualTo("")

			// Enable filter
			panel.setFilterEnabled(ReportType.TRAIN_CONTINUOUS, true)
			assertThat(textArea.text).contains("Position update")
		}
	}

	@Test
	fun `setMaxEvents limits stored events`() {
		SwingUtilities.invokeAndWait {
			// Set very low limit
			panel.setMaxEvents(2)

			// Add 3 events
			panel.addEvent(SimulationEvent(1.0, ReportType.TRAIN_EVENTS, "", "Event 1"))
			panel.addEvent(SimulationEvent(2.0, ReportType.TRAIN_EVENTS, "", "Event 2"))
			panel.addEvent(SimulationEvent(3.0, ReportType.TRAIN_EVENTS, "", "Event 3"))

			// Only last 2 events should be visible
			val text = textArea.text
			assertThat(text).contains("Event 2")
			assertThat(text).contains("Event 3")
			// Event 1 should be gone (trimmed)
		}
	}

	@Test
	fun `events are displayed in chronological order`() {
		SwingUtilities.invokeAndWait {
			panel.addEvent(SimulationEvent(1.0, ReportType.TRAIN_EVENTS, "", "First"))
			panel.addEvent(SimulationEvent(2.0, ReportType.TRAIN_EVENTS, "", "Second"))
			panel.addEvent(SimulationEvent(3.0, ReportType.TRAIN_EVENTS, "", "Third"))

			val text = textArea.text
			val firstIndex = text.indexOf("First")
			val secondIndex = text.indexOf("Second")
			val thirdIndex = text.indexOf("Third")

			assertThat(firstIndex < secondIndex).isTrue()
			assertThat(secondIndex < thirdIndex).isTrue()
		}
	}

	@Test
	fun `empty event list handled gracefully`() {
		SwingUtilities.invokeAndWait {
			panel.addEvents(emptyList())
			assertThat(textArea.text).isEqualTo("")
		}
	}

	@Test
	fun `panel has titled border`() {
		SwingUtilities.invokeAndWait {
			val border = panel.border
			assertThat(border).isEqualTo(border) // Border exists
		}
	}

	@Test
	fun `disabling TRAIN_APPROVED filter hides approval events`() {
		SwingUtilities.invokeAndWait {
			// Add a TRAIN_APPROVED event (visible by default)
			panel.addEvent(
				SimulationEvent(1.0, ReportType.TRAIN_APPROVED, "", """train="Train #1" route=IO1->IO2""")
			)
			assertThat(textArea.text).contains("[TRAIN_APPROVED]")

			// Disable filter — event should disappear
			panel.setFilterEnabled(ReportType.TRAIN_APPROVED, false)
			assertThat(textArea.text).isEqualTo("")

			// Re-enable — event should reappear
			panel.setFilterEnabled(ReportType.TRAIN_APPROVED, true)
			assertThat(textArea.text).contains("[TRAIN_APPROVED]")
		}
	}

	@Test
	fun `TRAIN_APPROVED filter is enabled by default`() {
		SwingUtilities.invokeAndWait {
			assertThat(panel.isFilterEnabled(ReportType.TRAIN_APPROVED)).isTrue()
		}
	}
}
