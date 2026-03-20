package cz.vutbr.fit.interlockSim.gui.animation

import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Control panel for displaying simulation time and status during animated simulation.
 *
 * This component appears at the top of the frame during simulation mode and is hidden
 * during editing mode. It provides real-time feedback on simulation progress without
 * simulation control buttons (due to kDisco framework limitations - simulations cannot
 * be paused, only started and stopped).
 *
 * **Layout Structure:**
 * ```
 * ┌─────────────────────────────────────────────┐
 * │ Time: 00:12:34.567   Status: Running        │
 * └─────────────────────────────────────────────┘
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
 * - No pause/resume buttons: kDisco simulations cannot be paused once started
 * - Time formatting: HH:MM:SS.mmm (hours:minutes:seconds.milliseconds)
 * - Status values: "Ready", "Running", "Stopped"
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

	init {
		layout = FlowLayout(FlowLayout.LEFT, 10, 5)
		border = BorderFactory.createEtchedBorder()

		// Create time label with initial state
		timeLabel = JLabel("Time: 00:00:00.000")
		add(timeLabel)

		// Create status label with initial state
		statusLabel = JLabel("Status: Ready")
		add(statusLabel)
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
	 * **Valid Status Values:**
	 * - "Ready" - Simulation initialized but not started
	 * - "Running" - Simulation actively executing
	 * - "Stopped" - Simulation terminated
	 *
	 * **Thread Safety:**
	 * Must be called from the Event Dispatch Thread (EDT).
	 *
	 * @param status The new status to display (typically "Ready", "Running", or "Stopped")
	 */
	fun updateStatus(status: String) {
		statusLabel.text = "Status: $status"
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
}
