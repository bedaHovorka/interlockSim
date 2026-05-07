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

import cz.vutbr.fit.interlockSim.PROGRAM_NAME
import cz.vutbr.fit.interlockSim.context.ContextChangeListener
import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionListener
import java.util.Locale
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs

/**
 * Status bar for displaying context information and mouse motion status.
 *
 * Implemented as a [JPanel] containing two labels:
 * - [statusLabel] (CENTER): shows context property-change messages and mouse-position info.
 * - [speedLabel] (EAST): shows the current simulation speed multiplier when it differs from
 *   1.0x; hidden at default speed. Written only from EDT via [updateSpeedIndicator].
 *
 * Separating the two labels avoids the conflict where simulation-thread property-change
 * callbacks (via [ContextChangeListener]) would otherwise overwrite the speed indicator text.
 */
class StatusBar :
	JPanel(),
	ContextChangeListener {
	private val statusLabel = JLabel()
	private val speedLabel = JLabel().apply { isVisible = false }

	private val mouseListener =
		object : MouseMotionListener {
			override fun mouseDragged(e: MouseEvent) {
				// Not used
			}

			override fun mouseMoved(e: MouseEvent) {
				val source = e.source
				requireValidState(source is StatusProducer) { "Source must be a StatusProducer" }
				val status = (source as StatusProducer).getStatus(e)
				text = status
			}
		}

	/** Delegates to [statusLabel], providing a backward-compatible text property. */
	var text: String
		get() = statusLabel.text ?: ""
		set(value) {
			statusLabel.text = value
		}

	init {
		layout = BorderLayout()
		add(statusLabel, BorderLayout.CENTER)
		add(speedLabel, BorderLayout.EAST)
		preferredSize = Dimension(100, 25)
		text = "Welcome to " + PROGRAM_NAME
	}

	private fun checkComponent(producer: StatusProducer): Component {
		requireValidState(producer is Component) { "StatusProducer must be a Component" }
		return producer as Component
	}

	fun registerProducer(producer: StatusProducer) {
		val producerComponent = checkComponent(producer)
		producerComponent.addMouseMotionListener(mouseListener)
	}

	fun unregisterProducer(producer: StatusProducer) {
		val producerComponent = checkComponent(producer)
		producerComponent.removeMouseMotionListener(mouseListener)
	}

	override fun propertyChange(event: ContextChangeEvent) {
		val newValue = event.newValue ?: return
		val newText = newValue.toString()
		if (SwingUtilities.isEventDispatchThread()) {
			text = newText
		} else {
			SwingUtilities.invokeLater { text = newText }
		}
	}

	/**
	 * Shows a temporary message in the status bar.
	 * The message will be displayed for the specified duration, then cleared.
	 *
	 * @param message The message to display
	 * @param durationMs Duration in milliseconds (default: 3000ms = 3 seconds)
	 */
	fun showTemporaryMessage(
		message: String,
		durationMs: Long = 3000
	) {
		val originalText = text
		text = message

		// Use Swing Timer to restore original text after duration
		val timer =
			javax.swing.Timer(durationMs.toInt()) {
				text = originalText
			}
		timer.isRepeats = false
		timer.start()
	}

	/**
	 * Updates the speed indicator in [speedLabel] (separate from [statusLabel]).
	 *
	 * When [multiplier] differs from 1.0x, [speedLabel] shows "Speed: X.Xx" and becomes
	 * visible. At default speed (1.0x) the label is hidden and its text is cleared so
	 * no stale speed string is shown if the status bar later becomes visible again.
	 *
	 * This method is EDT-safe: it executes synchronously when already on the EDT (so
	 * [SimulationController.stop] on the EDT takes effect before subsequent mode-switch
	 * calls), and uses [SwingUtilities.invokeLater] when called from a background thread.
	 *
	 * @param multiplier Current speed multiplier from [SimulationRunner]
	 */
	fun updateSpeedIndicator(multiplier: Double) {
		if (SwingUtilities.isEventDispatchThread()) {
			applySpeedIndicator(multiplier)
		} else {
			SwingUtilities.invokeLater { applySpeedIndicator(multiplier) }
		}
	}

	private fun applySpeedIndicator(multiplier: Double) {
		if (abs(multiplier - DEFAULT_SPEED) > SPEED_EPSILON) {
			speedLabel.text = String.format(Locale.ROOT, "Speed: %.1fx", multiplier)
			speedLabel.isVisible = true
		} else {
			speedLabel.text = ""
			speedLabel.isVisible = false
		}
	}

	/** Returns `true` when the speed indicator label is currently visible. */
	internal fun isSpeedIndicatorVisible(): Boolean = speedLabel.isVisible

	/** Returns the current speed indicator text, or an empty string when hidden. */
	internal fun speedIndicatorText(): String = speedLabel.text ?: ""

	companion object {
		private const val DEFAULT_SPEED = 1.0
		private const val SPEED_EPSILON = 0.001
	}
}
