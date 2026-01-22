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

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret

private val logger = KotlinLogging.logger {}

/**
 * Event timeline panel for displaying simulation events with filtering.
 *
 * This panel displays a scrollable log of simulation events with real-time filtering
 * by event type. Events are formatted with simulation time (HH:MM:SS.mmm) and
 * automatically scroll to show the latest events.
 *
 * ## Features
 *
 * - **Real-time Event Display**: Events appear as they occur during simulation
 * - **Type Filtering**: Checkboxes to show/hide specific event types
 * - **Auto-scroll**: Automatically scrolls to latest events (unless manually disabled)
 * - **Formatted Time**: All events show simulation time in HH:MM:SS.mmm format
 * - **Thread-safe**: Can receive events from simulation thread via SwingUtilities.invokeLater
 *
 * ## Event Types
 *
 * - **Path Commands** ([ReportType.PATH_SETTING]) - Path reservation/release
 * - **Node Events** ([ReportType.NODE_EVENTS]) - Switch/signal state changes
 * - **Train Events** ([ReportType.TRAIN_EVENTS]) - Train discrete events (stop, exit, etc.)
 * - **Train Updates** ([ReportType.TRAIN_CONTINUOUS]) - Position/velocity updates (1Hz)
 *
 * ## Usage
 *
 * ```kotlin
 * val eventPanel = EventTimelinePanel()
 *
 * // Add events (from simulation thread)
 * SwingUtilities.invokeLater {
 *     eventPanel.addEvent(SimulationEvent(
 *         simulationTime = 123.456,
 *         eventType = ReportType.TRAIN_EVENTS,
 *         message = "Train 1 stopped at signal S1"
 *     ))
 * }
 *
 * // Clear all events
 * eventPanel.clearEvents()
 * ```
 *
 * ## Layout
 *
 * ```
 * ┌─────────────────────────────────────────┐
 * │ Filter Panel (Checkboxes)               │
 * ├─────────────────────────────────────────┤
 * │ [00:00:12.345] [TRAIN_EVENTS] Train 1...│
 * │ [00:00:15.678] [NODE_EVENTS] Signal...  │
 * │ [00:00:18.901] [PATH_SETTING] Path...   │
 * │ ...                                      │
 * └─────────────────────────────────────────┘
 * ```
 *
 * @see SimulationEvent
 * @see AnimationController
 */
class EventTimelinePanel : JPanel() {

	/**
	 * Text area for displaying events.
	 */
	private val eventTextArea: JTextArea = JTextArea().apply {
		isEditable = false
		font = Font(Font.MONOSPACED, Font.PLAIN, 11)
		lineWrap = false
	}

	/**
	 * Scroll pane for text area.
	 */
	private val scrollPane: JScrollPane = JScrollPane(eventTextArea).apply {
		// Configure auto-scroll behavior
		val caret = eventTextArea.caret as DefaultCaret
		caret.updatePolicy = DefaultCaret.ALWAYS_UPDATE
	}

	/**
	 * Filter checkboxes for event types.
	 */
	private val filterCheckboxes: Map<ReportType, JCheckBox> = mapOf(
		ReportType.PATH_SETTING to JCheckBox("Path Commands", true),
		ReportType.NODE_EVENTS to JCheckBox("Node Events", true),
		ReportType.TRAIN_EVENTS to JCheckBox("Train Events", true),
		ReportType.TRAIN_CONTINUOUS to JCheckBox("Train Updates", false) // Initially disabled (high frequency)
	)

	/**
	 * List of all events (unfiltered).
	 */
	private val allEvents: MutableList<SimulationEvent> = mutableListOf()

	/**
	 * Maximum number of events to keep in memory.
	 *
	 * Older events are discarded to prevent memory issues during long simulations.
	 */
	private var maxEvents: Int = DEFAULT_MAX_EVENTS

	init {
		layout = BorderLayout()
		border = BorderFactory.createTitledBorder("Event Timeline")

		// Create filter panel
		val filterPanel = createFilterPanel()
		add(filterPanel, BorderLayout.NORTH)

		// Add scroll pane with text area
		add(scrollPane, BorderLayout.CENTER)

		logger.debug { "EventTimelinePanel initialized with default filters" }
	}

	/**
	 * Create filter panel with checkboxes for event types.
	 *
	 * @return Filter panel component
	 */
	private fun createFilterPanel(): JPanel {
		val panel = JPanel(FlowLayout(FlowLayout.LEFT))

		// Add checkboxes with listeners
		filterCheckboxes.values.forEach { checkbox ->
			checkbox.addActionListener { refreshDisplay() }
			panel.add(checkbox)
		}

		return panel
	}

	/**
	 * Add a new simulation event to the timeline.
	 *
	 * **Thread-safe:** This method may be called from EDT or marshaled via SwingUtilities.invokeLater
	 *
	 * @param event Event to add
	 */
	fun addEvent(event: SimulationEvent) {
		require(SwingUtilities.isEventDispatchThread()) {
			"addEvent() must be called from EDT"
		}

		// Add to internal list
		allEvents.add(event)

		// Enforce max events limit
		if (allEvents.size > maxEvents) {
			val toRemove = allEvents.size - maxEvents
			repeat(toRemove) {
				allEvents.removeAt(0)
			}
			logger.debug { "Trimmed $toRemove old events (limit: $maxEvents)" }
		}

		// Update display if this event type is visible
		if (isEventTypeVisible(event.eventType)) {
			appendEventToDisplay(event)
		}

		logger.trace { "Event added: ${event.formatForDisplay()}" }
	}

	/**
	 * Add multiple events in batch.
	 *
	 * More efficient than calling [addEvent] multiple times.
	 *
	 * **Thread-safe:** Must be called from EDT
	 *
	 * @param events Events to add
	 */
	fun addEvents(events: List<SimulationEvent>) {
		require(SwingUtilities.isEventDispatchThread()) {
			"addEvents() must be called from EDT"
		}

		if (events.isEmpty()) {
			return
		}

		// Add all events to internal list
		allEvents.addAll(events)

		// Enforce max events limit
		if (allEvents.size > maxEvents) {
			val toRemove = allEvents.size - maxEvents
			repeat(toRemove) {
				allEvents.removeAt(0)
			}
			logger.debug { "Trimmed $toRemove old events (limit: $maxEvents)" }
		}

		// Update display with filtered events
		refreshDisplay()

		logger.debug { "Added ${events.size} events in batch" }
	}

	/**
	 * Clear all events from the timeline.
	 *
	 * **Thread-safe:** Must be called from EDT
	 */
	fun clearEvents() {
		require(SwingUtilities.isEventDispatchThread()) {
			"clearEvents() must be called from EDT"
		}

		allEvents.clear()
		eventTextArea.text = ""

		logger.debug { "All events cleared" }
	}

	/**
	 * Set maximum number of events to keep in memory.
	 *
	 * @param max Maximum number of events (must be positive)
	 */
	fun setMaxEvents(max: Int) {
		require(max > 0) { "Max events must be positive, got: $max" }
		maxEvents = max
		logger.debug { "Max events set to $max" }
	}

	/**
	 * Check if a specific event type is currently visible (filter enabled).
	 *
	 * @param eventType Event type to check
	 * @return true if events of this type should be displayed
	 */
	private fun isEventTypeVisible(eventType: ReportType): Boolean {
		return filterCheckboxes[eventType]?.isSelected ?: false
	}

	/**
	 * Append a single event to the display (without refresh).
	 *
	 * @param event Event to append
	 */
	private fun appendEventToDisplay(event: SimulationEvent) {
		eventTextArea.append(event.formatForDisplay() + "\n")
	}

	/**
	 * Refresh the entire display based on current filters.
	 *
	 * Rebuilds the text area content from all stored events.
	 */
	private fun refreshDisplay() {
		require(SwingUtilities.isEventDispatchThread()) {
			"refreshDisplay() must be called from EDT"
		}

		// Build text from filtered events
		val text = buildString {
			allEvents.forEach { event ->
				if (isEventTypeVisible(event.eventType)) {
					append(event.formatForDisplay())
					append("\n")
				}
			}
		}

		// Update display
		eventTextArea.text = text

		// Scroll to bottom
		eventTextArea.caretPosition = eventTextArea.document.length

		logger.trace { "Display refreshed with ${countVisibleEvents()} visible events" }
	}

	/**
	 * Count number of currently visible events (based on filters).
	 *
	 * @return Number of visible events
	 */
	private fun countVisibleEvents(): Int {
		return allEvents.count { isEventTypeVisible(it.eventType) }
	}

	/**
	 * Get filter state for specific event type.
	 *
	 * @param eventType Event type to query
	 * @return true if filter is enabled (events visible)
	 */
	fun isFilterEnabled(eventType: ReportType): Boolean {
		return filterCheckboxes[eventType]?.isSelected ?: false
	}

	/**
	 * Set filter state for specific event type.
	 *
	 * @param eventType Event type to configure
	 * @param enabled Whether to show events of this type
	 */
	fun setFilterEnabled(eventType: ReportType, enabled: Boolean) {
		require(SwingUtilities.isEventDispatchThread()) {
			"setFilterEnabled() must be called from EDT"
		}

		filterCheckboxes[eventType]?.isSelected = enabled
		refreshDisplay()

		logger.debug { "Filter for $eventType set to $enabled" }
	}

	companion object {
		/**
		 * Default maximum number of events to keep in memory.
		 *
		 * This prevents memory issues during long simulations with high event rates.
		 */
		private const val DEFAULT_MAX_EVENTS = 10000
	}
}
