/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * SimulationKeyBindings — Phase 3.1: Global Keyboard Shortcuts (Issue #193)
 */
package cz.vutbr.fit.interlockSim.gui

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Global keyboard shortcuts for simulation speed control (Phase 3.1 of Goal 7, Issue #193).
 *
 * Provides:
 * - Number keys 1-5 → Speed presets (0.5×, 1×, 2×, 5×, 10×)
 * - Plus/minus keys → Incremental speed adjustment (×1.5 or ÷1.5)
 * - Space bar → Pause/resume toggle (Goal 8 preparation)
 *
 * All bindings use [JComponent.WHEN_IN_FOCUSED_WINDOW] scope so they work whenever
 * the [Frame] has focus, regardless of which component has keyboard focus.
 *
 * ## Usage
 * ```kotlin
 * val keyBindings = SimulationKeyBindings(frame.simulationController)
 * keyBindings.install(frame.rootPane)
 * ```
 *
 * **Thread Safety:**
 * - [install] and [uninstall] must be called from EDT
 * - Actions are executed on EDT by Swing's key binding mechanism
 *
 * @param simulationController The controller managing the active simulation runner
 * @since 2026-05-06 (Phase 3.1, Issue #193)
 * @see SimulationController
 * @see Frame
 */
internal class SimulationKeyBindings(
	private val simulationController: SimulationController
) {
	private val logger = KotlinLogging.logger {}

	/**
	 * Install keyboard shortcuts on the given [rootPane].
	 *
	 * Binds keys to actions using [JComponent.WHEN_IN_FOCUSED_WINDOW] scope so
	 * they work globally when the window has focus.
	 *
	 * Safe to call multiple times (idempotent). Previous bindings are replaced
	 * by subsequent calls.
	 *
	 * @param rootPane The root pane to install bindings on (typically [Frame.rootPane])
	 */
	fun install(rootPane: JComponent) {
		val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
		val actionMap = rootPane.actionMap

		// Speed presets: keys 1-5 map to 0.5×, 1×, 2×, 5×, 10×
		PRESET_BINDINGS.forEach { (keyCode, speed) ->
			val actionKey = "speed_preset_$speed"
			inputMap.put(KeyStroke.getKeyStroke(keyCode, 0), actionKey)
			actionMap.put(actionKey, SpeedPresetAction(speed))
		}

		// Incremental speed adjustment: + increases by ×1.5, - decreases by ÷1.5
		// '+' is typically Shift+'=' on most keyboards, so bind both Shift+VK_EQUALS and numpad plus
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, KeyEvent.SHIFT_DOWN_MASK), ACTION_KEY_SPEED_UP)
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0), ACTION_KEY_SPEED_UP) // Numpad +
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0), ACTION_KEY_SPEED_DOWN)
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0), ACTION_KEY_SPEED_DOWN) // Numpad -
		actionMap.put(ACTION_KEY_SPEED_UP, IncrementalSpeedAction(multiplier = SPEED_INCREMENT))
		actionMap.put(ACTION_KEY_SPEED_DOWN, IncrementalSpeedAction(multiplier = 1.0 / SPEED_INCREMENT))

		// Pause/resume toggle: Space bar (Goal 8 preparation)
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), ACTION_KEY_PAUSE_TOGGLE)
		actionMap.put(ACTION_KEY_PAUSE_TOGGLE, PauseToggleAction())

		logger.debug { "Simulation keyboard shortcuts installed" }
	}

	/**
	 * Uninstall keyboard shortcuts from the given [rootPane].
	 *
	 * Removes all key bindings and actions installed by [install].
	 * Safe to call when not installed (no-op).
	 *
	 * @param rootPane The root pane to uninstall bindings from
	 */
	fun uninstall(rootPane: JComponent) {
		val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
		val actionMap = rootPane.actionMap

		// Remove preset bindings
		PRESET_BINDINGS.forEach { (keyCode, speed) ->
			val actionKey = "speed_preset_$speed"
			inputMap.remove(KeyStroke.getKeyStroke(keyCode, 0))
			actionMap.remove(actionKey)
		}

		// Remove incremental speed bindings
		inputMap.remove(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, KeyEvent.SHIFT_DOWN_MASK))
		inputMap.remove(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0))
		inputMap.remove(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0))
		inputMap.remove(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0))
		actionMap.remove(ACTION_KEY_SPEED_UP)
		actionMap.remove(ACTION_KEY_SPEED_DOWN)

		// Remove pause toggle binding
		inputMap.remove(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0))
		actionMap.remove(ACTION_KEY_PAUSE_TOGGLE)

		logger.debug { "Simulation keyboard shortcuts uninstalled" }
	}

	// ── Action implementations ─────────────────────────────────────────────────

	/**
	 * Action that sets the simulation speed to a fixed preset value.
	 *
	 * If no simulation is running, [SimulationController.setSpeed] updates [SimulationController.desiredSpeed]
	 * which will be applied when the next simulation starts.
	 */
	private inner class SpeedPresetAction(private val speed: Double) : AbstractAction() {
		override fun actionPerformed(e: ActionEvent) {
			try {
				simulationController.setSpeed(speed)
				logger.debug { "Speed preset applied: ${speed}×" }
			} catch (ex: IllegalArgumentException) {
				logger.warn { "Invalid speed preset: $speed — ${ex.message}" }
			}
		}
	}

	/**
	 * Action that adjusts the simulation speed by a multiplicative factor.
	 *
	 * Reads the current effective speed via [SimulationController.speed] (live runner value
	 * when running, stored desired speed otherwise), multiplies by [multiplier], coerces to
	 * the valid range [SimulationRunner.MIN_SPEED]..[SimulationRunner.MAX_SPEED], and applies
	 * the new speed via [SimulationController.setSpeed].
	 *
	 * When no simulation is running, this updates [SimulationController.desiredSpeed] so the
	 * adjusted speed is honoured when the next simulation starts.
	 */
	private inner class IncrementalSpeedAction(private val multiplier: Double) : AbstractAction() {
		override fun actionPerformed(e: ActionEvent) {
			val currentSpeed = simulationController.speed
			val newSpeed = (currentSpeed * multiplier).coerceIn(
				SimulationRunner.MIN_SPEED,
				SimulationRunner.MAX_SPEED
			)

			try {
				simulationController.setSpeed(newSpeed)
				logger.debug { "Speed adjusted: ${currentSpeed}× → ${newSpeed}× (×$multiplier)" }
			} catch (ex: IllegalArgumentException) {
				logger.warn { "Invalid speed adjustment: $newSpeed — ${ex.message}" }
			}
		}
	}

	/**
	 * Action that toggles pause/resume state of the simulation.
	 *
	 * Reads the current [SimulationRunner.isPaused] flag and flips it.
	 * If no simulation is running, the action is a no-op.
	 *
	 * **Goal 8 preparation:** This is a minimal implementation that directly toggles
	 * the [SimulationRunner.isPaused] property. Future work (Goal 8) will wire this
	 * into UI elements and add comprehensive pause/resume state management.
	 */
	private inner class PauseToggleAction : AbstractAction() {
		override fun actionPerformed(e: ActionEvent) {
			val runner = simulationController.runner
			if (runner == null) {
				logger.debug { "Pause toggle ignored (no simulation running)" }
				return
			}

			val wasPaused = runner.isPaused
			runner.isPaused = !wasPaused
			logger.debug { "Simulation ${if (wasPaused) "resumed" else "paused"}" }
		}
	}

	companion object {
		/** Action key for speed-up shortcut. */
		private const val ACTION_KEY_SPEED_UP = "simulation_speed_up"

		/** Action key for speed-down shortcut. */
		private const val ACTION_KEY_SPEED_DOWN = "simulation_speed_down"

		/** Action key for pause/resume toggle. */
		private const val ACTION_KEY_PAUSE_TOGGLE = "simulation_pause_toggle"

		/** Incremental speed multiplier: ×1.5 for speed-up, ÷1.5 for speed-down. */
		private const val SPEED_INCREMENT = 1.5

		/**
		 * Mapping of key codes to speed preset values.
		 * Keys 1-5 → 0.5×, 1×, 2×, 5×, 10× (matches the five standard presets in the UI panel and menu).
		 * Note: 0.5× is accessible only via keyboard (key 1), panel, or menu — not via incremental shortcuts.
		 */
		private val PRESET_BINDINGS: Map<Int, Double> = mapOf(
			KeyEvent.VK_1 to 0.5,
			KeyEvent.VK_2 to 1.0,
			KeyEvent.VK_3 to 2.0,
			KeyEvent.VK_4 to 5.0,
			KeyEvent.VK_5 to 10.0
		)
	}
}
