package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.math.abs
import cz.vutbr.fit.interlockSim.context.SimulationController as CoreSimulationController

/**
 * Unit tests for [SimulationKeyBindings] (Phase 3.1, Issue #193).
 *
 * Tests verify:
 * - Preset key bindings (1-5 → speed presets)
 * - Incremental speed adjustment (+/- keys)
 * - Pause/resume toggle (Space bar)
 * - Install/uninstall lifecycle
 * - Edge cases (no runner, invalid speeds)
 *
 * These tests use lightweight Swing components (JPanel/InputMap/ActionMap) and
 * [SimulationController] which is designed to be testable without a display.
 */
class SimulationKeyBindingsTest {
	private lateinit var simulationContext: SimulationContext
	private lateinit var simulationController: SimulationController
	private lateinit var keyBindings: SimulationKeyBindings
	private lateinit var rootPane: JPanel
	private lateinit var blockSim: CountDownLatch

	@BeforeEach
	fun setUp() {
		// Block context.run() with a latch to prevent the runner from exiting immediately,
		// avoiding race conditions where the monitor thread nulls out runner during tests.
		blockSim = CountDownLatch(1)

		// Create mocked simulation context that blocks until tearDown
		simulationContext = mockk(relaxed = true)
		every { simulationContext.run(ofType<CoreSimulationController>()) } answers {
			blockSim.await(10, TimeUnit.SECONDS)
		}

		// Create controller and key bindings on EDT (Swing components require EDT)
		SwingUtilities.invokeAndWait {
			simulationController = SimulationController()
			keyBindings = SimulationKeyBindings(simulationController)
			rootPane = JPanel()
		}
	}

	@AfterEach
	fun tearDown() {
		// Unblock any running simulation and stop the controller
		blockSim.countDown()
		simulationController.stop()
	}

	// ── Installation/Uninstallation ────────────────────────────────────────────

	@Test
	fun `install creates key bindings in InputMap and ActionMap`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)

			val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			val actionMap = rootPane.actionMap

			// Verify preset bindings (keys 1-5 → 0.5×, 1×, 2×, 5×, 10×)
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_1, 0)]).isEqualTo("speed_preset_0.5")
			assertThat(actionMap["speed_preset_0.5"]).isNotNull()

			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_2, 0)]).isEqualTo("speed_preset_1.0")
			assertThat(actionMap["speed_preset_1.0"]).isNotNull()

			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_3, 0)]).isEqualTo("speed_preset_2.0")
			assertThat(actionMap["speed_preset_2.0"]).isNotNull()

			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_4, 0)]).isEqualTo("speed_preset_5.0")
			assertThat(actionMap["speed_preset_5.0"]).isNotNull()

			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_5, 0)]).isEqualTo("speed_preset_10.0")
			assertThat(actionMap["speed_preset_10.0"]).isNotNull()

			// Verify incremental speed bindings (+/- with Shift and numpad)
			val shiftEqualsStroke = KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, KeyEvent.SHIFT_DOWN_MASK)
			assertThat(inputMap[shiftEqualsStroke]).isEqualTo("simulation_speed_up")
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0)]).isEqualTo("simulation_speed_up")
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0)]).isEqualTo("simulation_speed_down")
			val subtractStroke = KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0)
			assertThat(inputMap[subtractStroke]).isEqualTo("simulation_speed_down")
			assertThat(actionMap["simulation_speed_up"]).isNotNull()
			assertThat(actionMap["simulation_speed_down"]).isNotNull()

			// Verify pause toggle binding (Space)
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)]).isEqualTo("simulation_pause_toggle")
			assertThat(actionMap["simulation_pause_toggle"]).isNotNull()
		}
	}

	@Test
	fun `uninstall removes all key bindings`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
			keyBindings.uninstall(rootPane)

			val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			val actionMap = rootPane.actionMap

			// Verify all bindings are removed
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_1, 0)]).isNull()
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_2, 0)]).isNull()
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_3, 0)]).isNull()
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_4, 0)]).isNull()
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_5, 0)]).isNull()
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, KeyEvent.SHIFT_DOWN_MASK)]).isNull()
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0)]).isNull()
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0)]).isNull()
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0)]).isNull()
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)]).isNull()

			assertThat(actionMap["speed_preset_0.5"]).isNull()
			assertThat(actionMap["speed_preset_1.0"]).isNull()
			assertThat(actionMap["speed_preset_2.0"]).isNull()
			assertThat(actionMap["speed_preset_5.0"]).isNull()
			assertThat(actionMap["speed_preset_10.0"]).isNull()
			assertThat(actionMap["simulation_speed_up"]).isNull()
			assertThat(actionMap["simulation_speed_down"]).isNull()
			assertThat(actionMap["simulation_pause_toggle"]).isNull()
		}
	}

	@Test
	fun `uninstall is safe when not installed`() {
		SwingUtilities.invokeAndWait {
			// Should not throw
			keyBindings.uninstall(rootPane)
		}
	}

	// ── Speed Preset Actions ───────────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `key 1 sets speed to 0_5x`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)

		triggerAction("speed_preset_0.5")

		SwingUtilities.invokeAndWait {
			assertThat(abs(simulationController.runner!!.speedMultiplier - 0.5) < 0.01).isTrue()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `key 2 sets speed to 1x`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)

		triggerAction("speed_preset_1.0")

		SwingUtilities.invokeAndWait {
			assertThat(abs(simulationController.runner!!.speedMultiplier - 1.0) < 0.01).isTrue()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `key 3 sets speed to 2x`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)

		triggerAction("speed_preset_2.0")

		SwingUtilities.invokeAndWait {
			assertThat(abs(simulationController.runner!!.speedMultiplier - 2.0) < 0.01).isTrue()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `key 4 sets speed to 5x`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)

		triggerAction("speed_preset_5.0")

		SwingUtilities.invokeAndWait {
			assertThat(abs(simulationController.runner!!.speedMultiplier - 5.0) < 0.01).isTrue()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `key 5 sets speed to 10x`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)

		triggerAction("speed_preset_10.0")

		SwingUtilities.invokeAndWait {
			assertThat(abs(simulationController.runner!!.speedMultiplier - 10.0) < 0.01).isTrue()
		}
	}

	@Test
	fun `preset key updates desiredSpeed even when no simulation is running`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		// Do NOT start simulation

		// Should not throw; updates desiredSpeed for next start
		triggerAction("speed_preset_1.0")

		SwingUtilities.invokeAndWait {
			assertThat(simulationController.runner).isNull()
		}
	}

	// ── Incremental Speed Adjustment ───────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `plus key increases speed by 1_5x`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)
		simulationController.setSpeed(2.0) // Initial speed

		triggerAction("simulation_speed_up")

		SwingUtilities.invokeAndWait {
			// 2.0 × 1.5 = 3.0
			assertThat(abs(simulationController.runner!!.speedMultiplier - 3.0) < 0.01).isTrue()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `minus key decreases speed by dividing by 1_5`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)
		simulationController.setSpeed(3.0) // Initial speed

		triggerAction("simulation_speed_down")

		SwingUtilities.invokeAndWait {
			// 3.0 ÷ 1.5 = 2.0
			assertThat(abs(simulationController.runner!!.speedMultiplier - 2.0) < 0.01).isTrue()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `incremental speed is clamped to MIN_SPEED`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)
		simulationController.setSpeed(SimulationRunner.MIN_SPEED)

		triggerAction("simulation_speed_down")

		SwingUtilities.invokeAndWait {
			// Should remain at MIN_SPEED (0.1), not go below
			assertThat(abs(simulationController.runner!!.speedMultiplier - SimulationRunner.MIN_SPEED) < 0.01).isTrue()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `incremental speed is clamped to MAX_SPEED`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)
		simulationController.setSpeed(SimulationRunner.MAX_SPEED)

		triggerAction("simulation_speed_up")

		SwingUtilities.invokeAndWait {
			// Should remain at MAX_SPEED (100.0), not go above
			assertThat(abs(simulationController.runner!!.speedMultiplier - SimulationRunner.MAX_SPEED) < 0.01).isTrue()
		}
	}

	@Test
	fun `incremental adjustment is safe when no simulation is running`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		// Do NOT start simulation

		// Should not throw
		triggerAction("simulation_speed_up")

		SwingUtilities.invokeAndWait {
			assertThat(simulationController.runner).isNull()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `increment action updates desiredSpeed when no simulation is running`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		// No simulation running — default desiredSpeed is DEFAULT_SPEED (1.0)
		// Speed-up multiplier is 1.5, so desiredSpeed should become 1.5

		triggerAction("simulation_speed_up")

		// Now start a simulation and verify the runner adopts the incremented speed
		simulationController.start(simulationContext)

		SwingUtilities.invokeAndWait {
			assertThat(abs(simulationController.runner!!.speedMultiplier - 1.5) < 0.01).isTrue()
		}
	}

	// ── Pause/Resume Toggle ─────────────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `space bar pauses running simulation`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)
		
		SwingUtilities.invokeAndWait {
			assertThat(simulationController.runner!!.isPaused).isFalse()
		}

		triggerAction("simulation_pause_toggle")

		SwingUtilities.invokeAndWait {
			assertThat(simulationController.runner!!.isPaused).isTrue()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `space bar resumes paused simulation`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		simulationController.start(simulationContext)
		
		SwingUtilities.invokeAndWait {
			simulationController.runner!!.isPaused = true
			assertThat(simulationController.runner!!.isPaused).isTrue()
		}

		triggerAction("simulation_pause_toggle")

		SwingUtilities.invokeAndWait {
			assertThat(simulationController.runner!!.isPaused).isFalse()
		}
	}

	@Test
	fun `pause toggle is ignored when no simulation is running`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
		}
		// Do NOT start simulation

		// Should not throw
		triggerAction("simulation_pause_toggle")

		SwingUtilities.invokeAndWait {
			assertThat(simulationController.runner).isNull()
		}
	}

	// ── Helpers ─────────────────────────────────────────────────────────────────

	/**
	 * Trigger an action by its action key on the EDT (simulates user pressing the key).
	 */
	private fun triggerAction(actionKey: String) {
		SwingUtilities.invokeAndWait {
			val action = rootPane.actionMap[actionKey]
			assertThat(action).isNotNull()
			action.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, actionKey))
		}
	}
}
