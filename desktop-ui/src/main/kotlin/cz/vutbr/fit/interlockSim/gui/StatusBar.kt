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
import cz.vutbr.fit.interlockSim.gui.GuiConstants.WARNING_BADGE_COLOR
import cz.vutbr.fit.interlockSim.gui.StatusBarColors.PAUSED_BADGE_COLOR
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import java.awt.BorderLayout
import java.awt.Color
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
 * Implemented as a [JPanel] containing these labels:
 * - [statusLabel] (CENTER): shows context property-change messages and mouse-position info.
 * - [pausedLabel] (EAST, leftmost): shows "[PAUSED]" when the simulation is paused; hidden
 *   otherwise. Written only from EDT via [setPaused].
 * - [warningLabel] (EAST, second): shows "⚠ WARNING" in red when at least one CRITICAL
 *   collision warning is unacknowledged; hidden otherwise. Written only from EDT via
 *   [setWarningIndicator].
 * - [starvedLabel] (EAST, third): shows "⚠ RAILWAY STARVED" in red when the run that just ended
 *   achieved nothing on the railway; hidden otherwise. Written only from EDT via
 *   [setStarvedIndicator].
 * - [speedLabel] (EAST, rightmost): shows the current simulation speed multiplier when it
 *   differs from 1.0x; hidden at default speed. Written only from EDT via [updateSpeedIndicator].
 *
 * Separating the labels avoids the conflict where simulation-thread property-change callbacks
 * (via [ContextChangeListener]) would otherwise overwrite the speed/pause indicator text.
 */
class StatusBar :
	JPanel(),
	ContextChangeListener {
	private val statusLabel = JLabel()
	private val pausedLabel = JLabel().apply { isVisible = false }
	private val warningLabel = JLabel().apply { isVisible = false }
	private val starvedLabel = JLabel().apply { isVisible = false }
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
		// East panel: pausedLabel (left) + warningLabel + starvedLabel + speedLabel (right).
		val eastPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0))
		eastPanel.isOpaque = false
		eastPanel.add(pausedLabel)
		eastPanel.add(warningLabel)
		eastPanel.add(starvedLabel)
		eastPanel.add(speedLabel)
		add(eastPanel, BorderLayout.EAST)
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

	/**
	 * Shows or hides the "[PAUSED]" indicator in [pausedLabel].
	 *
	 * When [paused] is `true`, [pausedLabel] shows "[PAUSED]" in [PAUSED_BADGE_COLOR]
	 * and becomes visible. When `false`, the label text is cleared and hidden.
	 *
	 * This method is EDT-safe: it executes synchronously when already on the EDT and
	 * uses [SwingUtilities.invokeLater] when called from a background thread.
	 *
	 * @param paused Current paused state from [SimulationRunner]
	 */
	fun setPaused(paused: Boolean) {
		if (SwingUtilities.isEventDispatchThread()) {
			applyPausedIndicator(paused)
		} else {
			SwingUtilities.invokeLater { applyPausedIndicator(paused) }
		}
	}

	private fun applyPausedIndicator(paused: Boolean) {
		pausedLabel.text = if (paused) "[PAUSED]" else ""
		pausedLabel.isVisible = paused
		pausedLabel.foreground = PAUSED_BADGE_COLOR
	}

	/** Returns `true` when the paused indicator label is currently visible. */
	internal fun isPausedIndicatorVisible(): Boolean = pausedLabel.isVisible

	/** Returns the current paused indicator text, or an empty string when hidden. */
	internal fun pausedIndicatorText(): String = pausedLabel.text ?: ""

	/** Returns the current paused indicator foreground color. */
	internal fun pausedIndicatorForeground(): Color = pausedLabel.foreground

	/**
	 * Shows or hides the "⚠ WARNING" indicator in [warningLabel].
	 *
	 * When [hasCritical] is `true`, [warningLabel] shows "⚠ WARNING" in
	 * [WARNING_BADGE_COLOR] (red) and becomes visible.  When `false`, the label text is
	 * cleared and hidden.
	 *
	 * This method is EDT-safe: it executes synchronously when already on the EDT and
	 * uses [SwingUtilities.invokeLater] when called from a background thread.
	 *
	 * @param hasCritical Whether at least one CRITICAL collision warning is unacknowledged.
	 * @since Issue #616 (Goal 3 SP6)
	 */
	fun setWarningIndicator(hasCritical: Boolean) {
		if (SwingUtilities.isEventDispatchThread()) {
			applyWarningIndicator(hasCritical)
		} else {
			SwingUtilities.invokeLater { applyWarningIndicator(hasCritical) }
		}
	}

	private fun applyWarningIndicator(hasCritical: Boolean) {
		warningLabel.text = if (hasCritical) "⚠ WARNING" else ""
		warningLabel.isVisible = hasCritical
		warningLabel.foreground = WARNING_BADGE_COLOR
	}

	/** Returns `true` when the warning indicator label is currently visible. */
	internal fun isWarningIndicatorVisible(): Boolean = warningLabel.isVisible

	/** Returns the current warning indicator text, or an empty string when hidden. */
	internal fun warningIndicatorText(): String = warningLabel.text ?: ""

	/**
	 * Shows or hides the "⚠ RAILWAY STARVED" indicator in [starvedLabel].
	 *
	 * ## Why this exists (Issue #930)
	 *
	 * The GUI had no run-quality signal of any kind. Everything the headless arm computes about a
	 * run — the actionable-tick rate, the railway outcome, the stall diagnostics — reached the GUI
	 * user only through the log file. A run in which no train completed a journey looked exactly
	 * like a healthy one, and was recorded as one.
	 *
	 * This is deliberately a persistent label rather than [showTemporaryMessage]: a verdict about
	 * the run that just ended must still be on screen when the user looks up.
	 *
	 * @param starved Whether the run that just ended was recorded as
	 *   [cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause.STARVED].
	 * @since Issue #930 (Wave 3 — GUI starvation flag)
	 */
	fun setStarvedIndicator(starved: Boolean) {
		if (SwingUtilities.isEventDispatchThread()) {
			applyStarvedIndicator(starved)
		} else {
			SwingUtilities.invokeLater { applyStarvedIndicator(starved) }
		}
	}

	private fun applyStarvedIndicator(starved: Boolean) {
		starvedLabel.text = if (starved) STARVED_BADGE_TEXT else ""
		starvedLabel.isVisible = starved
		starvedLabel.foreground = WARNING_BADGE_COLOR
	}

	/** Returns `true` when the starved indicator label is currently visible. */
	internal fun isStarvedIndicatorVisible(): Boolean = starvedLabel.isVisible

	/** Returns the current starved indicator text, or an empty string when hidden. */
	internal fun starvedIndicatorText(): String = starvedLabel.text ?: ""

	companion object {
		/** Text of the starved-run badge (Issue #930). */
		internal const val STARVED_BADGE_TEXT = "⚠ RAILWAY STARVED"

		private const val DEFAULT_SPEED = 1.0
		private const val SPEED_EPSILON = 0.001
	}
}
