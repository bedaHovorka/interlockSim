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
import javax.swing.JFormattedTextField
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Speed control panel for the simulation, implementing Phase 2.1 of Goal 7 (Issue #190)
 * and the pause/step controls of Goal 8 (Issue #501).
 *
 * Provides:
 * - A linear [JSlider] covering 0.1× to 10× in 0.1× increments
 * - Seven preset buttons: 0.1×, 0.5×, 1×, 2×, 5×, 10×, 50×
 * - A live speed label showing the current multiplier
 * - Pause/Resume toggle, Step Event, and Step Time buttons (Goal 8)
 * - A [JSpinner] next to Step Time to configure [SimulationRunner.stepTimeDelta]
 *   (default 1.0 s, range 0.001–60.0 s)
 *
 * The slider range is 0.1×–10× (expert users reach 50× via the preset button only).
 * All slider integer values are mapped to `value / SLIDER_SCALE` so that the internal
 * int range [1..100] maps to double range [0.1..10.0].
 *
 * **PropertyChangeListener integration:**
 * - Setting [runner] installs listeners on [SimulationRunner.PROP_SPEED_MULTIPLIER] and
 *   [SimulationRunner.PROP_IS_PAUSED] so that changes made programmatically (e.g. from
 *   tests or keyboard shortcuts) are reflected in the UI.
 * - User interaction (slider drag, button click, spinner change) writes back to [SimulationRunner].
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

	/** Pause/Resume toggle button (Goal 8). */
	private val pauseButton: JButton

	/** Step Event button — advances one simulation event when paused (Goal 8). */
	private val stepEventButton: JButton

	/** Step Time button — advances [SimulationRunner.stepTimeDelta] sim-seconds when paused (Goal 8). */
	private val stepTimeButton: JButton

	/** Spinner that configures the time delta used by [stepTimeButton]. */
	private val stepTimeDeltaSpinner: JSpinner

	/**
	 * The [SimulationRunner] currently wired to this panel, or `null` when no
	 * simulation is running.  Setting this property:
	 * - Removes the listeners from the old runner (if any)
	 * - Installs listeners on the new runner (if non-null) for [SimulationRunner.PROP_SPEED_MULTIPLIER]
	 *   and [SimulationRunner.PROP_IS_PAUSED]
	 * - Synchronises the slider, label, pause buttons, and step-time spinner to the new runner's
	 *   current state (when non-null); setting to `null` retains the last displayed speed so the panel
	 *   is not visually reset, and disables the pause controls.
	 *
	 * Must be set from the EDT.
	 */
	var runner: SimulationRunner? = null
		set(value) {
			field?.removePropertyChangeListener(SimulationRunner.PROP_SPEED_MULTIPLIER, runnerListener)
			field?.removePropertyChangeListener(SimulationRunner.PROP_IS_PAUSED, pausedListener)
			field = value
			value?.addPropertyChangeListener(SimulationRunner.PROP_SPEED_MULTIPLIER, runnerListener)
			value?.addPropertyChangeListener(SimulationRunner.PROP_IS_PAUSED, pausedListener)
			if (value != null) {
				syncUiToSpeed(value.speedMultiplier)
				syncUiToPaused(value.isPaused)
				syncUiToStepTimeDelta(value.stepTimeDelta)
			} else {
				// When value is null: disable pause controls, keep speed display as-is.
				syncUiToPaused(false)
				pauseButton.isEnabled = false
				stepEventButton.isEnabled = false
				stepTimeButton.isEnabled = false
				stepTimeDeltaSpinner.isEnabled = false
			}
		}

	/** Listener that keeps the speed UI in sync when the runner's speed changes externally. */
	private val runnerListener =
		PropertyChangeListener { evt: PropertyChangeEvent ->
			val speed = evt.newValue as? Double ?: return@PropertyChangeListener
			if (SwingUtilities.isEventDispatchThread()) {
				syncUiToSpeed(speed)
			} else {
				SwingUtilities.invokeLater { syncUiToSpeed(speed) }
			}
		}

	/** Listener that keeps the pause UI in sync when the runner's paused state changes externally. */
	private val pausedListener =
		PropertyChangeListener { evt: PropertyChangeEvent ->
			val paused = evt.newValue as? Boolean ?: return@PropertyChangeListener
			if (SwingUtilities.isEventDispatchThread()) {
				syncUiToPaused(paused)
			} else {
				SwingUtilities.invokeLater { syncUiToPaused(paused) }
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

		// ── Row 3: pause / step controls (Goal 8, Issue #501) ───────────────────
		val pauseRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))

		pauseButton = JButton(LABEL_PAUSE)
		pauseButton.toolTipText = "Pause/resume the simulation (Space)"
		pauseButton.isEnabled = false
		pauseButton.addActionListener {
			val r = runner ?: return@addActionListener
			r.isPaused = !r.isPaused
		}
		pauseRow.add(pauseButton)

		stepEventButton = JButton("Step Event")
		stepEventButton.toolTipText = "Advance by one event (S)"
		stepEventButton.isEnabled = false
		stepEventButton.addActionListener {
			runner?.requestStepEvent()
		}
		pauseRow.add(stepEventButton)

		stepTimeButton = JButton("Step Time")
		stepTimeButton.toolTipText = "Advance by the configured time delta (T)"
		stepTimeButton.isEnabled = false
		stepTimeButton.addActionListener {
			val r = runner ?: return@addActionListener
			r.requestStepTime(r.stepTimeDelta)
		}
		pauseRow.add(stepTimeButton)

		stepTimeDeltaSpinner = JSpinner(
			SpinnerNumberModel(
				DEFAULT_STEP_TIME_DELTA,
				MIN_STEP_TIME_DELTA,
				MAX_STEP_TIME_DELTA,
				STEP_TIME_SPINNER_STEP
			)
		)
		stepTimeDeltaSpinner.toolTipText = "Simulation time delta for Step Time (seconds)"
		stepTimeDeltaSpinner.isEnabled = false
		// Commit the value immediately when focus is lost so action listeners always see
		// the latest delta, even if the user typed a value but did not press Enter.
		((stepTimeDeltaSpinner.editor as? JSpinner.DefaultEditor)?.textField as? JFormattedTextField)
			?.focusLostBehavior = JFormattedTextField.COMMIT
		stepTimeDeltaSpinner.addChangeListener {
			val value = (stepTimeDeltaSpinner.value as? Number)?.toDouble() ?: return@addChangeListener
			runner?.stepTimeDelta = value
		}
		pauseRow.add(JLabel("Delta:"))
		pauseRow.add(stepTimeDeltaSpinner)

		add(pauseRow)
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

	/**
	 * Synchronise the pause button text and the step buttons' enabled state to [paused].
	 *
	 * Called from [pausedListener] (which runs on EDT via [SwingUtilities.invokeLater]) and
	 * from the [runner] setter when a non-null runner is attached.
	 * When [runner] is `null` the button states are handled directly in the setter.
	 */
	private fun syncUiToPaused(paused: Boolean) {
		val simRunning = runner != null
		pauseButton.text = if (paused) LABEL_RESUME else LABEL_PAUSE
		pauseButton.isEnabled = simRunning
		stepEventButton.isEnabled = simRunning && paused
		stepTimeButton.isEnabled = simRunning && paused
		stepTimeDeltaSpinner.isEnabled = simRunning
	}

	/**
	 * Synchronise the step-time spinner to [delta] without writing it back to the runner.
	 *
	 * Called from the [runner] setter when a non-null runner is attached.
	 */
	private fun syncUiToStepTimeDelta(delta: Double) {
		val model = stepTimeDeltaSpinner.model as SpinnerNumberModel
		val coerced = delta.coerceIn(model.minimum as Double, model.maximum as Double)
		if (model.value as Double != coerced) {
			model.value = coerced
		}
	}

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

		/** Button labels for the pause/resume toggle. */
		private const val LABEL_PAUSE = "Pause"
		private const val LABEL_RESUME = "Resume"

		/** Default step-time delta (seconds). */
		private const val DEFAULT_STEP_TIME_DELTA: Double = 1.0

		/** Minimum step-time delta (seconds). */
		private const val MIN_STEP_TIME_DELTA: Double = 0.001

		/** Maximum step-time delta (seconds). */
		private const val MAX_STEP_TIME_DELTA: Double = 60.0

		/** Spinner step size for the step-time delta (seconds). */
		private const val STEP_TIME_SPINNER_STEP: Double = 0.1
	}
}
