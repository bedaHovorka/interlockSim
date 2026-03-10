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
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Component
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
 * 4. **Close:** `close()` or `stop()` - Stops timer, unregisters listener, prevents memory leaks
 *
 * ## Resource Management
 *
 * **CRITICAL:** Always call `stop()` or `close()` when disposing the parent component (Frame, canvas).
 * Failure to clean up resources will cause memory leaks:
 * - PropertyChangeListener registered on SimulationContext (prevents GC of controller)
 * - Swing Timer thread continues running (resource leak)
 * - Cache references prevent cell GC
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
 * // Option 1: Explicit cleanup (recommended with try-finally)
 * try {
 *   controller.start()
 *   // ... use controller ...
 * } finally {
 *   controller.stop()  // or controller.close()
 * }
 *
 * // Option 2: AutoCloseable pattern (Kotlin use function)
 * controller.use {
 *   it.start()
 *   // ... use controller ...
 * } // Automatically calls close() on exit
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
) : ContextPropertyChangeListener,
	AutoCloseable {
	/**
	 * Current animation state (immutable snapshot).
	 *
	 * **Threading Model:**
	 * - **Written on:** EDT only (via updateState called from SwingUtilities.invokeLater)
	 * - **Read on:** EDT only (via getCurrentState during rendering and time display)
	 * - **Thread-safety:** EDT-confined (strict single-threaded access)
	 *
	 * **Rationale for no @Volatile:**
	 * All access is EDT-confined (writes marshaled via invokeLater, reads during EDT paint/timer).
	 * No concurrent access occurs, so volatile memory barrier is unnecessary.
	 */
	private var currentState: AnimationState = AnimationState.EMPTY

	/**
	 * Swing timer for periodic canvas repainting.
	 *
	 * Fires at 30 FPS (33ms interval) to trigger rendering updates.
	 */
	private val repaintTimer: Timer =
		Timer(REPAINT_INTERVAL_MS) { _ ->
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
	 * Cache of all semaphores in the grid for O(1) animation state capture.
	 *
	 * Populated once during start() via single grid scan.
	 * Valid for controller lifetime (grid is immutable after context freeze).
	 */
	private var semaphoreCache: List<DynamicRailSemaphore>? = null

	/**
	 * Cache of all switches in the grid for O(1) animation state capture.
	 *
	 * Populated once during start() via single grid scan.
	 * Valid for controller lifetime (grid is immutable after context freeze).
	 */
	private var switchCache: List<DynamicRailSwitch>? = null

	/**
	 * Circuit breaker for state capture failures.
	 * Tracks consecutive failures to detect persistent vs transient issues.
	 */
	private var consecutiveFailures: Int = 0

	/**
	 * Whether animation is in error state (stopped due to persistent failures).
	 */
	private var isInErrorState: Boolean = false

	/**
	 * Get current animation state.
	 *
	 * **Must be called from EDT** (rendering or timer callbacks).
	 * Accessing from non-EDT threads would violate EDT-confinement guarantee.
	 *
	 * @return Current immutable animation state
	 */
	fun getCurrentState(): AnimationState = currentState

	/**
	 * Check if animation is in error state (testing support).
	 * @return true if animation is stopped due to persistent failures
	 */
	internal fun isInErrorState(): Boolean = isInErrorState

	/**
	 * Get consecutive failure count (testing support).
	 * @return Number of consecutive state capture failures
	 */
	internal fun getConsecutiveFailures(): Int = consecutiveFailures

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

		// Reset error state (allow restart after previous failure)
		consecutiveFailures = 0
		isInErrorState = false

		// Register as listener for simulation state changes
		context.addPropertyChangeListener(this)

		// Initialize caches with single grid scan (performance optimization)
		semaphoreCache = buildSemaphoreCache()
		switchCache = buildSwitchCache()
		logger.debug { "Initialized caches: ${semaphoreCache?.size} semaphores, ${switchCache?.size} switches" }

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
	 * - Clears caches for GC
	 *
	 * **Idempotent:** Safe to call multiple times (no-op if already stopped).
	 *
	 * **Must be called from EDT.**
	 */
	fun stop() {
		require(SwingUtilities.isEventDispatchThread()) {
			"AnimationController.stop() must be called from EDT"
		}

		if (!isRunning) {
			logger.debug { "AnimationController.stop() called but controller is not running (idempotent - already stopped)" }
			return
		}

		logger.info { "Stopping AnimationController" }

		// Stop repaint timer
		repaintTimer.stop()

		// Unregister listener
		context.removePropertyChangeListener(this)

		// Clear caches to allow GC
		semaphoreCache = null
		switchCache = null

		isRunning = false

		logger.debug { "AnimationController stopped successfully" }
	}

	/**
	 * AutoCloseable implementation for resource cleanup.
	 * Delegates to [stop] for consistency.
	 *
	 * **Must be called from EDT.**
	 */
	override fun close() {
		stop()
	}

	/**
	 * ContextPropertyChangeListener implementation for simulation state updates.
	 *
	 * **Called on jDisco simulation thread** (not EDT!).
	 * Marshals state capture and update to EDT via SwingUtilities.invokeLater.
	 *
	 * @param event ContextChangeEvent containing simulation reports and state changes
	 */
	override fun propertyChange(event: ContextChangeEvent) {
		// This method executes on jDisco simulation thread!
		require(!SwingUtilities.isEventDispatchThread()) {
			"PropertyChange event should come from simulation thread, not EDT"
		}

		logger.trace { "PropertyChange event received from simulation thread: ${event.propertyName}" }

		// Extract event information if available
		val simulationEvent = extractSimulationEvent(event)

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
	 *
	 * Implements circuit breaker pattern: tolerates transient failures but stops
	 * animation on persistent failures (3+ consecutive failures).
	 */
	private fun captureAndUpdateState() {
		require(SwingUtilities.isEventDispatchThread()) {
			"State capture and update must occur on EDT"
		}

		try {
			// Pass caches to avoid O(n²) grid scans on every PropertyChangeEvent
			val newState =
				AnimationStateCapture.captureState(
					context,
					semaphoreCache ?: emptyList(),
					switchCache ?: emptyList()
				)
			updateState(newState)

			// Reset failure counter on success
			if (consecutiveFailures > 0) {
				logger.info { "Animation state capture recovered after $consecutiveFailures failures" }
				consecutiveFailures = 0
			}

			logger.trace {
				"Animation state updated: time=${newState.simulationTime}, " +
					"trains=${newState.trainStates.size}, " +
					"tracks=${newState.trackStates.size}, " +
					"signals=${newState.signalStates.size}"
			}
		} catch (e: Exception) {
			consecutiveFailures++
			logger.error(e) {
				"Failed to capture simulation state for animation " +
					"(consecutive failures: $consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)"
			}

			// Circuit breaker: Stop animation on persistent failures
			if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
				handlePersistentFailure(e)
			}
		}
	}

	/**
	 * Handle persistent state capture failures (circuit breaker tripped).
	 * Stops animation and notifies user via error dialog.
	 */
	private fun handlePersistentFailure(lastException: Exception) {
		if (isInErrorState) {
			return // Already handled
		}

		isInErrorState = true
		logger.error(lastException) {
			"Animation stopped due to persistent state capture failures " +
				"($consecutiveFailures consecutive failures)"
		}

		// Stop repaint timer
		repaintTimer.stop()

		// Show error notification
		SwingUtilities.invokeLater {
			javax.swing.JOptionPane.showMessageDialog(
				canvas,
				"The simulation animation has been paused due to errors.\n" +
					"The simulation continues running, but visual updates are stopped.\n\n" +
					"Technical details: ${lastException.javaClass.simpleName}: ${lastException.message}\n\n" +
					"Check logs for more information.",
				"Animation Paused",
				javax.swing.JOptionPane.ERROR_MESSAGE
			)
		}
	}

	/**
	 * Update current animation state.
	 *
	 * Replaces current state with new immutable snapshot.
	 *
	 * **Must be called from EDT.**
	 *
	 * @param newState New immutable animation state
	 */
	private fun updateState(newState: AnimationState) {
		require(SwingUtilities.isEventDispatchThread()) {
			"State updates must occur on EDT (actual thread: ${Thread.currentThread().name})"
		}
		currentState = newState
	}

	/**
	 * Extract simulation event from ContextChangeEvent.
	 *
	 * This method parses property change events from the simulation context
	 * and converts them to SimulationEvent instances for the event timeline.
	 *
	 * The message from DefaultSimulationContext.report() is already formatted as:
	 * "[simulationTime] [object] message"
	 *
	 * **Called on simulation thread** (not EDT!).
	 *
	 * @param event ContextChangeEvent from simulation context
	 * @return SimulationEvent if the event can be parsed, null otherwise
	 */
	private fun extractSimulationEvent(event: ContextChangeEvent): SimulationEvent? {
		// Try to extract report type from property name
		val reportType =
			try {
				SimulationContext.ReportType.valueOf(event.propertyName)
			} catch (e: IllegalArgumentException) {
				return null
			}

		// Extract message from new value (format: "time object message")
		val fullMessage = event.newValue?.toString() ?: return null

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

	/**
	 * Build cache of all semaphores in grid.
	 *
	 * Performs single O(n²) grid scan to find all DynamicRailSemaphore cells.
	 * Called once during start() to avoid repeated scans on every PropertyChangeEvent.
	 *
	 * @return Immutable list of all semaphores in grid
	 */
	private fun buildSemaphoreCache(): List<DynamicRailSemaphore> {
		val grid = context.getRailWayNetGrid()
		val cache = mutableListOf<DynamicRailSemaphore>()

		for (x in 0 until grid.getCols()) {
			for (y in 0 until grid.getRows()) {
				val cell = grid.getCellAt(x, y)
				if (cell is DynamicRailSemaphore) {
					cache.add(cell)
				}
			}
		}

		return cache.toList() // Immutable
	}

	/**
	 * Build cache of all switches in grid.
	 *
	 * Performs single O(n²) grid scan to find all DynamicRailSwitch cells.
	 * Called once during start() to avoid repeated scans on every PropertyChangeEvent.
	 *
	 * @return Immutable list of all switches in grid
	 */
	private fun buildSwitchCache(): List<DynamicRailSwitch> {
		val grid = context.getRailWayNetGrid()
		val cache = mutableListOf<DynamicRailSwitch>()

		for (x in 0 until grid.getCols()) {
			for (y in 0 until grid.getRows()) {
				val cell = grid.getCellAt(x, y)
				if (cell is DynamicRailSwitch) {
					cache.add(cell)
				}
			}
		}

		return cache.toList() // Immutable
	}

	companion object {
		/**
		 * Repaint interval in milliseconds for 30 FPS rendering.
		 */
		private const val REPAINT_INTERVAL_MS = 33 // 1000ms / 30 FPS ≈ 33ms

		/**
		 * Maximum consecutive failures before stopping animation.
		 * 3 failures at 30 FPS = ~100ms of failures (tolerates transient issues).
		 */
		private const val MAX_CONSECUTIVE_FAILURES = 3
	}
}
