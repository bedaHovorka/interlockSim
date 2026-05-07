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

import java.awt.FlowLayout
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Speed control panel for the simulation, implementing Phase 2.1 of Goal 7 (Issue #190).
 *
 * Provides:
 * - A linear [JSlider] covering 0.1× to 10× in 0.1× increments
 * - Seven preset buttons: 0.1×, 0.5×, 1×, 2×, 5×, 10×, 50×
 * - A live speed label showing the current multiplier
 *
 * The slider range is 0.1×–10× (expert users reach 50× via the preset button only).
 * All slider integer values are mapped to `value / SLIDER_SCALE` so that the internal
 * int range [1..100] maps to double range [0.1..10.0].
 *
 * **PropertyChangeListener integration:**
 * - Setting [runner] installs a listener on [SimulationRunner.PROP_SPEED_MULTIPLIER] so
 *   that speed changes made programmatically (e.g. from tests) are reflected in the UI.
 * - User interaction (slider drag, button click) writes back to [SimulationRunner.speedMultiplier].
 * - The panel is automatically hidden/shown by [cz.vutbr.fit.interlockSim.gui.Frame] when
 *   switching between editing and simulation modes.
 *
 * **Thread Safety:**
 * All methods must be called from the Event Dispatch Thread (EDT).
 *
 * @since 2026-05-05 (Phase 2.1, Issue #190)
 * @see SimulationRunner
 * @see cz.vutbr.fit.interlockSim.gui.Frame
 */
class SimulationControlPanel : JPanel() {

	/** Scale factor: slider int value → speed double (1 → 0.1, 100 → 10.0). */
	private val slider: JSlider

	/** Label showing the current speed multiplier (e.g. "1.0x"). */
	private val speedLabel: JLabel

	/**
	 * The [SimulationRunner] currently wired to this panel, or `null` when no
	 * simulation is running.  Setting this property:
	 * - Removes the listener from the old runner (if any)
	 * - Installs a listener on the new runner (if non-null) for [SimulationRunner.PROP_SPEED_MULTIPLIER]
	 * - Synchronises the slider and label to the new runner's current speed (when non-null);
	 *   setting to `null` retains the last displayed speed so the panel is not visually reset.
	 *
	 * Must be set from the EDT.
	 */
	var runner: SimulationRunner? = null
		set(value) {
			field?.removePropertyChangeListener(SimulationRunner.PROP_SPEED_MULTIPLIER, runnerListener)
			field = value
			value?.addPropertyChangeListener(SimulationRunner.PROP_SPEED_MULTIPLIER, runnerListener)
			if (value != null) {
				syncUiToSpeed(value.speedMultiplier)
			}
			// When value is null, keep the current UI state so the speed display is not reset.
		}

	/** Listener that keeps the UI in sync when the runner's speed changes externally. */
	private val runnerListener = PropertyChangeListener { evt: PropertyChangeEvent ->
		val speed = evt.newValue as? Double ?: return@PropertyChangeListener
		if (SwingUtilities.isEventDispatchThread()) {
			syncUiToSpeed(speed)
		} else {
			SwingUtilities.invokeLater { syncUiToSpeed(speed) }
		}
	}

	/**
	 * Optional callback invoked whenever the user changes the speed (slider or preset button).
	 *
	 * Wire this in [cz.vutbr.fit.interlockSim.gui.Frame] to route speed changes through
	 * [SimulationController.setSpeed] so that [SimulationController.desiredSpeed] stays in sync
	 * and is honoured on the next [SimulationController.start] call.
	 *
	 * Not called when the runner fires a [SimulationRunner.PROP_SPEED_MULTIPLIER] event (i.e.
	 * when the UI is updated from the runner rather than by the user).
	 */
	var onSpeedChanged: ((Double) -> Unit)? = null

	/** Flag to suppress recursive slider → runner → slider feedback loops. */
	private var updatingFromRunner = false

	init {
		layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
		border = BorderFactory.createEtchedBorder()

		// ── Row 1: slider ──────────────────────────────────────────────────────
		val sliderRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))

		val sliderLabel = JLabel("Speed:")
		sliderRow.add(sliderLabel)

		slider = JSlider(SwingConstants.HORIZONTAL, SLIDER_MIN, SLIDER_MAX, speedToSlider(DEFAULT_SPEED))
		slider.majorTickSpacing = SLIDER_MAJOR_TICK
		slider.minorTickSpacing = SLIDER_MINOR_TICK
		slider.paintTicks = true
		slider.paintLabels = false
		slider.toolTipText = "Simulation speed: 0.1x – 10x"
		slider.addChangeListener {
			if (!updatingFromRunner) {
				val speed = sliderToSpeed(slider.value)
				speedLabel.text = formatSpeedLabel(speed)
				if (onSpeedChanged != null) {
					onSpeedChanged!!.invoke(speed)
				} else {
					runner?.speedMultiplier = speed
				}
			}
		}
		sliderRow.add(slider)

		speedLabel = JLabel(formatSpeedLabel(DEFAULT_SPEED))
		sliderRow.add(speedLabel)

		add(sliderRow)

		// ── Row 2: preset buttons ─────────────────────────────────────────────
		val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
		buttonRow.add(JLabel("Presets:"))
		PRESETS.forEach { speed ->
			val btn = JButton(formatPresetLabel(speed))
			btn.toolTipText = "Set speed to ${formatPresetLabel(speed)}"
			btn.addActionListener { applyPreset(speed) }
			buttonRow.add(btn)
		}

		add(buttonRow)
	}

	// ── Internal helpers ───────────────────────────────────────────────────────

	/** Convert a slider int value to a speed double. */
	private fun sliderToSpeed(value: Int): Double = value / SLIDER_SCALE

	/** Convert a speed double to a slider int (rounded to nearest tick, clamped to [SLIDER_MIN]..[SLIDER_MAX]). */
	private fun speedToSlider(speed: Double): Int =
		Math.round(speed * SLIDER_SCALE).toInt().coerceIn(SLIDER_MIN, SLIDER_MAX)

	/**
	 * Apply a preset speed: update UI and propagate to the runner.
	 *
	 * When [onSpeedChanged] is wired (normal production use via [SimulationController]), the
	 * speed update is routed exclusively through the callback to avoid a double-write to the
	 * runner. When [onSpeedChanged] is null (standalone panel usage without a controller),
	 * the runner is updated directly so the panel remains functional.
	 */
	private fun applyPreset(speed: Double) {
		syncUiToSpeed(speed)
		if (onSpeedChanged != null) {
			onSpeedChanged!!.invoke(speed)
		} else {
			runner?.speedMultiplier = speed
		}
	}

	/**
	 * Synchronise both slider and label to [speed] without triggering the
	 * slider's change listener feedback loop.
	 */
	private fun syncUiToSpeed(speed: Double) {
		updatingFromRunner = true
		try {
			val sliderValue = speedToSlider(speed)
			if (slider.value != sliderValue) {
				slider.value = sliderValue
			}
			speedLabel.text = formatSpeedLabel(speed)
		} finally {
			updatingFromRunner = false
		}
	}

	private fun formatSpeedLabel(speed: Double): String = "%.1fx".format(Locale.ROOT, speed)

	private fun formatPresetLabel(speed: Double): String =
		if (speed >= 1.0) "%.0fx".format(Locale.ROOT, speed) else "%.1fx".format(Locale.ROOT, speed)

	companion object {
		/** Slider integer range: [1..100] maps to speed [0.1..10.0]. */
		private const val SLIDER_MIN: Int = 1
		private const val SLIDER_MAX: Int = 100
		private const val SLIDER_SCALE: Double = 10.0
		private const val SLIDER_MAJOR_TICK: Int = 10
		private const val SLIDER_MINOR_TICK: Int = 5

		/** Default speed for the panel (1.0× = real-time). */
		const val DEFAULT_SPEED: Double = 1.0

		/** Seven preset speed multipliers. Values above 10× exceed the slider range. */
		val PRESETS: List<Double> = listOf(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 50.0)
	}
}
