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

import cz.vutbr.fit.interlockSim.context.SimulationContext
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Component
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities
import javax.swing.Timer

private val logger = KotlinLogging.logger {}

/**
 * Controller for railway simulation animation, managing state updates and rendering timing.
 *
 * This class bridges the jDisco simulation thread and the Swing Event Dispatch Thread (EDT),
 * ensuring thread-safe animation state updates and synchronized rendering.
 *
 * ## Threading Model
 *
 * ```
 * jDisco Simulation Thread:
 *   └─> SimulationContext.report() / PropertyChangeSupport
 *        └─> PropertyChangeListener [on simulation thread]
 *             └─> SwingUtilities.invokeLater { updateState() } [marshals to EDT]
 *
 * Swing EDT:
 *   └─> Timer.actionPerformed (30 FPS)
 *        └─> Component.repaint()
 *             └─> Renderer queries currentState [thread-safe read]
 * ```
 *
 * ## Lifecycle
 *
 * 1. **Create:** `AnimationController(context, canvas, eventPanel)`
 * 2. **Start:** `start()` - Begins Swing Timer (30 FPS rendering)
 * 3. **Update:** State updates occur automatically via PropertyChangeListener
 * 4. **Stop:** `stop()` - Stops Swing Timer, stops listening
 *
 * ## Usage
 *
 * ```kotlin
 * val eventPanel = EventTimelinePanel()
 * val controller = AnimationController(simulationContext, railwayCanvas, eventPanel)
 * controller.start()
 *
 * // ... simulation runs, state updates automatically ...
 *
 * controller.stop()
 * ```
 *
 * @property context Simulation context to observe for state changes
 * @property canvas Component to repaint on each animation frame
 * @property eventPanel Optional event timeline panel for displaying simulation events
 *
 * @see AnimationState
 * @see AnimationStateCapture
 * @see EventTimelinePanel
 */
class AnimationController(
	private val context: SimulationContext,
	private val canvas: Component,
	private val eventPanel: EventTimelinePanel? = null
) : PropertyChangeListener {

	/**
	 * Current animation state (immutable snapshot).
	 *
	 * Updated atomically via [updateState] on EDT.
	 * Read by renderer during paint operations (also on EDT).
	 *
	 * **Thread-safe:** EDT-confined (all reads/writes on EDT after marshaling)
	 */
	@Volatile
	private var currentState: AnimationState = AnimationState.EMPTY

	/**
	 * Swing timer for periodic canvas repainting.
	 *
	 * Fires at 30 FPS (33ms interval) to trigger rendering updates.
	 */
	private val repaintTimer: Timer = Timer(REPAINT_INTERVAL_MS) { _ ->
		// Timer callbacks execute on EDT
		require(SwingUtilities.isEventDispatchThread()) {
			"Timer callback must execute on EDT"
		}
		canvas.repaint()
	}

	/**
	 * Whether the animation controller is currently running.
	 */
	private var isRunning: Boolean = false

	/**
	 * Get current animation state (thread-safe).
	 *
	 * This method may be called from EDT during rendering.
	 *
	 * @return Current immutable animation state
	 */
	fun getCurrentState(): AnimationState = currentState

	/**
	 * Start animation controller.
	 *
	 * - Registers PropertyChangeListener with simulation context
	 * - Starts Swing Timer for 30 FPS rendering
	 * - Captures initial simulation state
	 *
	 * **Must be called from EDT.**
	 */
	fun start() {
		require(SwingUtilities.isEventDispatchThread()) {
			"AnimationController.start() must be called from EDT"
		}
		require(!isRunning) {
			"AnimationController is already running"
		}

		logger.info { "Starting AnimationController (30 FPS rendering)" }

		// Register as listener for simulation state changes
		context.addPropertyChangeListener(this)

		// Capture initial state
		captureAndUpdateState()

		// Start repaint timer
		repaintTimer.start()
		isRunning = true

		logger.debug { "AnimationController started successfully" }
	}

	/**
	 * Stop animation controller.
	 *
	 * - Unregisters PropertyChangeListener
	 * - Stops Swing Timer
	 *
	 * **Must be called from EDT.**
	 */
	fun stop() {
		require(SwingUtilities.isEventDispatchThread()) {
			"AnimationController.stop() must be called from EDT"
		}

		if (!isRunning) {
			logger.warn { "AnimationController.stop() called but controller is not running" }
			return
		}

		logger.info { "Stopping AnimationController" }

		// Stop repaint timer
		repaintTimer.stop()

		// Unregister listener
		context.removePropertyChangeListener(this)

		isRunning = false

		logger.debug { "AnimationController stopped successfully" }
	}

	/**
	 * PropertyChangeListener implementation for simulation state updates.
	 *
	 * **Called on jDisco simulation thread** (not EDT!).
	 * Marshals state capture and update to EDT via SwingUtilities.invokeLater.
	 *
	 * @param evt PropertyChangeEvent containing simulation reports and state changes
	 */
	override fun propertyChange(evt: PropertyChangeEvent?) {
		// This method executes on jDisco simulation thread!
		require(!SwingUtilities.isEventDispatchThread()) {
			"PropertyChange event should come from simulation thread, not EDT"
		}

		logger.trace { "PropertyChange event received from simulation thread: ${evt?.propertyName}" }

		// Extract event information if available
		val simulationEvent = extractSimulationEvent(evt)

		// Marshal state capture and update to EDT
		SwingUtilities.invokeLater {
			captureAndUpdateState()

			// Forward event to timeline panel if available
			if (simulationEvent != null && eventPanel != null) {
				eventPanel.addEvent(simulationEvent)
			}
		}
	}

	/**
	 * Capture current simulation state and update animation state.
	 *
	 * **Must be called from EDT.**
	 * Uses [AnimationStateCapture] to create immutable snapshot.
	 */
	private fun captureAndUpdateState() {
		require(SwingUtilities.isEventDispatchThread()) {
			"State capture and update must occur on EDT"
		}

		try {
			val newState = AnimationStateCapture.captureState(context)
			updateState(newState)
			logger.trace {
				"Animation state updated: time=${newState.simulationTime}, " +
					"trains=${newState.trainStates.size}, " +
					"tracks=${newState.trackStates.size}, " +
					"signals=${newState.signalStates.size}"
			}
		} catch (e: Exception) {
			logger.error(e) { "Failed to capture simulation state for animation" }
		}
	}

	/**
	 * Update current animation state (thread-safe).
	 *
	 * Atomically replaces current state with new immutable snapshot.
	 *
	 * **Must be called from EDT.**
	 *
	 * @param newState New immutable animation state
	 */
	private fun updateState(newState: AnimationState) {
		require(SwingUtilities.isEventDispatchThread()) {
			"State updates must occur on EDT"
		}
		currentState = newState
	}

	/**
	 * Extract simulation event from PropertyChangeEvent.
	 *
	 * This method parses property change events from the simulation context
	 * and converts them to SimulationEvent instances for the event timeline.
	 *
	 * The message from DefaultSimulationContext.report() is already formatted as:
	 * "[simulationTime] [object] message"
	 *
	 * **Called on simulation thread** (not EDT!).
	 *
	 * @param evt PropertyChangeEvent from simulation context
	 * @return SimulationEvent if the event can be parsed, null otherwise
	 */
	private fun extractSimulationEvent(evt: PropertyChangeEvent?): SimulationEvent? {
		if (evt == null) return null

		// Try to extract report type from property name
		val reportType = try {
			SimulationContext.ReportType.valueOf(evt.propertyName ?: return null)
		} catch (e: IllegalArgumentException) {
			return null
		}

		// Extract message from new value (format: "time object message")
		val fullMessage = evt.newValue?.toString() ?: return null

		// Parse simulation time from the beginning of the message
		val parts = fullMessage.trim().split(Regex("\\s+"), limit = 2)
		if (parts.isEmpty()) return null

		val simulationTime = parts[0].toDoubleOrNull() ?: 0.0
		val message = if (parts.size > 1) parts[1] else fullMessage

		return SimulationEvent(
			simulationTime = simulationTime,
			eventType = reportType,
			message = message
		)
	}

	companion object {
		/**
		 * Repaint interval in milliseconds for 30 FPS rendering.
		 */
		private const val REPAINT_INTERVAL_MS = 33 // 1000ms / 30 FPS ≈ 33ms
	}
}
