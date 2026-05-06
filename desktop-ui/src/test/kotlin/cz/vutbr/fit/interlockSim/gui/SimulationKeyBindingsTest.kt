package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.gui.animation.ControlPanel
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.math.abs

/**
 * Unit tests for [SimulationKeyBindings] (Phase 3.1, Issue #193).
 *
 * Tests verify:
 * - Preset key bindings (1-5 → speed presets)
 * - Incremental speed adjustment (+/- keys)
 * - Pause/resume toggle (Space bar)
 * - Install/uninstall lifecycle
 * - Edge cases (no runner, invalid speeds)
 */
class SimulationKeyBindingsTest {
	private lateinit var simulationContext: SimulationContext
	private lateinit var controlPanel: ControlPanel
	private lateinit var simulationController: SimulationController
	private lateinit var keyBindings: SimulationKeyBindings
	private lateinit var rootPane: JPanel

	@BeforeEach
	fun setUp() {
		// Skip tests in headless environments (CI servers without display)
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless())

		// Create mocked simulation context for testing
		simulationContext = mockk(relaxed = true)

		// Create controller and key bindings
		controlPanel = ControlPanel()
		simulationController = SimulationController(controlPanel)
		keyBindings = SimulationKeyBindings(simulationController)

		// Create a root pane for key bindings (simulates Frame.rootPane)
		rootPane = JPanel()
	}

	@AfterEach
	fun tearDown() {
		// No need to close mocked context
	}

	// ── Installation/Uninstallation ────────────────────────────────────────────

	@Test
	fun `install creates key bindings in InputMap and ActionMap`() {
		keyBindings.install(rootPane)

		val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
		val actionMap = rootPane.actionMap

		// Verify preset bindings (keys 1-5)
		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_1, 0)]).isEqualTo("speed_preset_1.0")
		assertThat(actionMap["speed_preset_1.0"]).isNotNull()

		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_2, 0)]).isEqualTo("speed_preset_2.0")
		assertThat(actionMap["speed_preset_2.0"]).isNotNull()

		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_3, 0)]).isEqualTo("speed_preset_5.0")
		assertThat(actionMap["speed_preset_5.0"]).isNotNull()

		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_4, 0)]).isEqualTo("speed_preset_10.0")
		assertThat(actionMap["speed_preset_10.0"]).isNotNull()

		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_5, 0)]).isEqualTo("speed_preset_50.0")
		assertThat(actionMap["speed_preset_50.0"]).isNotNull()

		// Verify incremental speed bindings (+/-)
		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, 0)]).isEqualTo("simulation_speed_up")
		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, 0)]).isEqualTo("simulation_speed_up")
		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0)]).isEqualTo("simulation_speed_down")
		assertThat(actionMap["simulation_speed_up"]).isNotNull()
		assertThat(actionMap["simulation_speed_down"]).isNotNull()

		// Verify pause toggle binding (Space)
		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)]).isEqualTo("simulation_pause_toggle")
		assertThat(actionMap["simulation_pause_toggle"]).isNotNull()
	}

	@Test
	fun `uninstall removes all key bindings`() {
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
		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, 0)]).isNull()
		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, 0)]).isNull()
		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0)]).isNull()
		assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)]).isNull()

		assertThat(actionMap["speed_preset_1.0"]).isNull()
		assertThat(actionMap["speed_preset_2.0"]).isNull()
		assertThat(actionMap["speed_preset_5.0"]).isNull()
		assertThat(actionMap["speed_preset_10.0"]).isNull()
		assertThat(actionMap["speed_preset_50.0"]).isNull()
		assertThat(actionMap["simulation_speed_up"]).isNull()
		assertThat(actionMap["simulation_speed_down"]).isNull()
		assertThat(actionMap["simulation_pause_toggle"]).isNull()
	}

	@Test
	fun `uninstall is safe when not installed`() {
		// Should not throw
		keyBindings.uninstall(rootPane)
	}

	// ── Speed Preset Actions ───────────────────────────────────────────────────

	@Test
	fun `key 1 sets speed to 1x`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)

		triggerAction("speed_preset_1.0")
		waitForEDT()

		assertThat(abs(simulationController.runner!!.speedMultiplier - 1.0) < 0.01).isTrue()
	}

	@Test
	fun `key 2 sets speed to 2x`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)

		triggerAction("speed_preset_2.0")
		waitForEDT()

		assertThat(abs(simulationController.runner!!.speedMultiplier - 2.0) < 0.01).isTrue()
	}

	@Test
	fun `key 3 sets speed to 5x`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)

		triggerAction("speed_preset_5.0")
		waitForEDT()

		assertThat(abs(simulationController.runner!!.speedMultiplier - 5.0) < 0.01).isTrue()
	}

	@Test
	fun `key 4 sets speed to 10x`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)

		triggerAction("speed_preset_10.0")
		waitForEDT()

		assertThat(abs(simulationController.runner!!.speedMultiplier - 10.0) < 0.01).isTrue()
	}

	@Test
	fun `key 5 sets speed to 50x`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)

		triggerAction("speed_preset_50.0")
		waitForEDT()

		assertThat(abs(simulationController.runner!!.speedMultiplier - 50.0) < 0.01).isTrue()
	}

	@Test
	fun `preset key is ignored when no simulation is running`() {
		keyBindings.install(rootPane)
		// Do NOT start simulation

		// Should not throw
		triggerAction("speed_preset_5.0")
		waitForEDT()

		assertThat(simulationController.runner).isNull()
	}

	// ── Incremental Speed Adjustment ───────────────────────────────────────────

	@Test
	fun `plus key increases speed by 1_5x`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)
		simulationController.setSpeed(2.0) // Initial speed

		triggerAction("simulation_speed_up")
		waitForEDT()

		// 2.0 × 1.5 = 3.0
		assertThat(abs(simulationController.runner!!.speedMultiplier - 3.0) < 0.01).isTrue()
	}

	@Test
	fun `minus key decreases speed by dividing by 1_5`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)
		simulationController.setSpeed(3.0) // Initial speed

		triggerAction("simulation_speed_down")
		waitForEDT()

		// 3.0 ÷ 1.5 = 2.0
		assertThat(abs(simulationController.runner!!.speedMultiplier - 2.0) < 0.01).isTrue()
	}

	@Test
	fun `incremental speed is clamped to MIN_SPEED`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)
		simulationController.setSpeed(SimulationRunner.MIN_SPEED)

		triggerAction("simulation_speed_down")
		waitForEDT()

		// Should remain at MIN_SPEED (0.1), not go below
		assertThat(abs(simulationController.runner!!.speedMultiplier - SimulationRunner.MIN_SPEED) < 0.01).isTrue()
	}

	@Test
	fun `incremental speed is clamped to MAX_SPEED`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)
		simulationController.setSpeed(SimulationRunner.MAX_SPEED)

		triggerAction("simulation_speed_up")
		waitForEDT()

		// Should remain at MAX_SPEED (100.0), not go above
		assertThat(abs(simulationController.runner!!.speedMultiplier - SimulationRunner.MAX_SPEED) < 0.01).isTrue()
	}

	@Test
	fun `incremental adjustment is ignored when no simulation is running`() {
		keyBindings.install(rootPane)
		// Do NOT start simulation

		// Should not throw
		triggerAction("simulation_speed_up")
		waitForEDT()

		assertThat(simulationController.runner).isNull()
	}

	// ── Pause/Resume Toggle ─────────────────────────────────────────────────────

	@Test
	fun `space bar pauses running simulation`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)
		assertThat(simulationController.runner!!.isPaused).isFalse()

		triggerAction("simulation_pause_toggle")
		waitForEDT()

		assertThat(simulationController.runner!!.isPaused).isTrue()
	}

	@Test
	fun `space bar resumes paused simulation`() {
		keyBindings.install(rootPane)
		simulationController.start(simulationContext)
		simulationController.runner!!.isPaused = true
		assertThat(simulationController.runner!!.isPaused).isTrue()

		triggerAction("simulation_pause_toggle")
		waitForEDT()

		assertThat(simulationController.runner!!.isPaused).isFalse()
	}

	@Test
	fun `pause toggle is ignored when no simulation is running`() {
		keyBindings.install(rootPane)
		// Do NOT start simulation

		// Should not throw
		triggerAction("simulation_pause_toggle")
		waitForEDT()

		assertThat(simulationController.runner).isNull()
	}

	// ── Helpers ─────────────────────────────────────────────────────────────────

	/**
	 * Trigger an action by its action key (simulates user pressing the key).
	 */
	private fun triggerAction(actionKey: String) {
		val action = rootPane.actionMap[actionKey]
		assertThat(action).isNotNull()
		action.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, actionKey))
	}

	/**
	 * Wait for all pending EDT events to complete.
	 */
	private fun waitForEDT() {
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeAndWait { /* no-op */ }
		}
	}
}
