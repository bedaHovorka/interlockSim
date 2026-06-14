package cz.vutbr.fit.interlockSim.gui.animation

import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Control panel for displaying simulation time and status during animated simulation.
 *
 * This component appears at the top of the frame during simulation mode and is hidden
 * during editing mode. It provides real-time feedback on simulation progress and a
 * Stop button to terminate the running simulation.
 *
 * **Layout Structure:**
 * ```
 * ┌────────────────────────────────────────────────────────┐
 * │ Time: 00:12:34.567   Status: Running   [Stop]          │
 * └────────────────────────────────────────────────────────┘
 * ```
 *
 * **Update Frequency:**
 * - Time display: Updated at 10 Hz (100ms timer in Frame)
 * - Status display: Updated on simulation state changes
 *
 * **Thread Safety:**
 * All methods must be called from the Event Dispatch Thread (EDT).
 *
 * **Design Constraints:**
 * - No pause/resume button: kDisco simulations cannot be paused once started
 * - Stop button: enabled while a simulation is running; calls [onStop] callback
 * - Time formatting: HH:MM:SS.mmm (hours:minutes:seconds.milliseconds)
 * - Status values: [SimulationStatus.READY], [SimulationStatus.RUNNING], [SimulationStatus.STOPPED]
 *
 * @since 2026-01-22
 * @see cz.vutbr.fit.interlockSim.gui.Frame
 * @see AnimationController
 */
class ControlPanel : JPanel() {
	/**
	 * Label displaying formatted simulation time (HH:MM:SS.mmm).
	 */
	private val timeLabel: JLabel

	/**
	 * Label displaying current simulation status (Ready/Running/Stopped).
	 */
	private val statusLabel: JLabel

	/**
	 * Button that requests simulation termination.
	 * Enabled only while a simulation is running.
	 */
	private val stopButton: JButton

	/**
	 * Callback invoked when the Stop button is clicked.
	 * Set by [cz.vutbr.fit.interlockSim.gui.Frame] before starting a simulation.
	 */
	var onStop: (() -> Unit)? = null

	init {
		layout = FlowLayout(FlowLayout.LEFT, 10, 5)
		border = BorderFactory.createEtchedBorder()

		// Create time label with initial state
		timeLabel = JLabel("Time: 00:00:00.000")
		add(timeLabel)

		// Create status label with initial state
		statusLabel = JLabel("Status: ${SimulationStatus.READY.displayName}")
		add(statusLabel)

		// Create stop button (disabled until simulation starts)
		stopButton = JButton("Stop")
		stopButton.isEnabled = false
		stopButton.addActionListener { onStop?.invoke() }
		add(stopButton)
	}

	/**
	 * Enable or disable the Stop button.
	 *
	 * Should be `true` while simulation is running, `false` otherwise.
	 *
	 * **Thread Safety:**
	 * Must be called from the Event Dispatch Thread (EDT).
	 *
	 * @param enabled Whether the Stop button should respond to clicks
	 */
	fun setStopEnabled(enabled: Boolean) {
		stopButton.isEnabled = enabled
	}

	/**
	 * Updates the displayed simulation time.
	 *
	 * This method is typically called by a Swing Timer at 10 Hz (100ms intervals)
	 * to provide responsive time display without excessive CPU overhead.
	 *
	 * **Time Format:** HH:MM:SS.mmm
	 * - Hours: 0-999 (zero-padded to 2 digits)
	 * - Minutes: 0-59 (zero-padded to 2 digits)
	 * - Seconds: 0-59 (zero-padded to 2 digits)
	 * - Milliseconds: 0-999 (zero-padded to 3 digits)
	 *
	 * **Examples:**
	 * - `updateTime(0.0)` → "Time: 00:00:00.000"
	 * - `updateTime(75.5)` → "Time: 00:01:15.500"
	 * - `updateTime(3661.234)` → "Time: 01:01:01.234"
	 *
	 * **Thread Safety:**
	 * Must be called from the Event Dispatch Thread (EDT).
	 *
	 * @param simulationTime Current simulation time in seconds (0.0 or greater)
	 * @throws IllegalArgumentException if simulationTime is negative
	 */
	fun updateTime(simulationTime: Double) {
		require(simulationTime >= 0.0) { "Simulation time cannot be negative: $simulationTime" }
		timeLabel.text = "Time: ${formatTime(simulationTime)}"
	}

	/**
	 * Updates the displayed simulation status.
	 *
	 * **Thread Safety:**
	 * Must be called from the Event Dispatch Thread (EDT).
	 *
	 * @param status The new status to display
	 */
	fun updateStatus(status: SimulationStatus) {
		statusLabel.text = "Status: ${status.displayName}"
	}

	/**
	 * Formats simulation time as HH:MM:SS.mmm.
	 *
	 * **Format Details:**
	 * - Hours: Total hours (0-999), zero-padded to 2 digits
	 * - Minutes: 0-59, zero-padded to 2 digits
	 * - Seconds: 0-59, zero-padded to 2 digits
	 * - Milliseconds: 0-999, zero-padded to 3 digits
	 *
	 * **Examples:**
	 * ```kotlin
	 * formatTime(0.0)      // "00:00:00.000"
	 * formatTime(1.5)      // "00:00:01.500"
	 * formatTime(75.234)   // "00:01:15.234"
	 * formatTime(3661.0)   // "01:01:01.000"
	 * formatTime(36000.0)  // "10:00:00.000"
	 * ```
	 *
	 * @param simulationTime Time in seconds (0.0 or greater)
	 * @return Formatted time string in HH:MM:SS.mmm format
	 */
	private fun formatTime(simulationTime: Double): String {
		val totalMilliseconds = (simulationTime * 1000).toLong()
		val hours = totalMilliseconds / 3_600_000
		val minutes = (totalMilliseconds % 3_600_000) / 60_000
		val seconds = (totalMilliseconds % 60_000) / 1_000
		val milliseconds = totalMilliseconds % 1_000

		return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
	}

	/**
	 * Simulation status values used to control the [statusLabel] display.
	 *
	 * @property displayName Human-readable label text shown in the UI.
	 */
	enum class SimulationStatus(
		val displayName: String
	) {
		/** Context set but simulation not yet started. */
		READY("Ready"),

		/** Simulation is actively executing. */
		RUNNING("Running"),

		/** Simulation has finished or was stopped. */
		STOPPED("Stopped")
	}
}
