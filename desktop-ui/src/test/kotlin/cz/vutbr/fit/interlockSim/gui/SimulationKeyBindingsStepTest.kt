/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * SimulationKeyBindingsStepTest — Goal 8 Phase 2.3: Step keyboard shortcuts (Issue #502)
 */
package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.DefaultKeyboardFocusManager
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Unit tests for the Goal 8 step keyboard shortcuts in [SimulationKeyBindings].
 *
 * These tests verify:
 * - `S` requests a single simulation event step via [SimulationRunner.requestStepEvent]
 * - `T` requests a time step using the runner's [SimulationRunner.stepTimeDelta]
 * - `Space` toggles the runner's pause state
 * - All three shortcuts are suppressed while a text component has keyboard focus
 */
class SimulationKeyBindingsStepTest {
	private lateinit var controller: SimulationController
	private lateinit var runner: SimulationRunner
	private lateinit var keyBindings: SimulationKeyBindings
	private lateinit var rootPane: JPanel
	private var originalKfm: KeyboardFocusManager? = null

	@BeforeEach
	fun setUp() {
		SwingUtilities.invokeAndWait {
			controller = mockk(relaxed = true)
			runner = mockk(relaxed = true)
			every { controller.runner } returns runner
			every { runner.isPaused } returns false
			keyBindings = SimulationKeyBindings(controller)
			rootPane = JPanel()
		}
	}

	@AfterEach
	fun tearDown() {
		originalKfm?.let {
			SwingUtilities.invokeAndWait { KeyboardFocusManager.setCurrentKeyboardFocusManager(it) }
		}
		originalKfm = null
	}

	@Test
	fun `install registers S and T step bindings`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)

			val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			val actionMap = rootPane.actionMap

			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_S, 0)]).isEqualTo(
				ACTION_KEY_STEP_EVENT
			)
			assertThat(actionMap[ACTION_KEY_STEP_EVENT]).isNotNull()

			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_T, 0)]).isEqualTo(
				ACTION_KEY_STEP_TIME
			)
			assertThat(actionMap[ACTION_KEY_STEP_TIME]).isNotNull()
		}
	}

	@Test
	fun `uninstall removes S and T step bindings`() {
		SwingUtilities.invokeAndWait {
			keyBindings.install(rootPane)
			keyBindings.uninstall(rootPane)

			val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			val actionMap = rootPane.actionMap

			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_S, 0)]).isNull()
			assertThat(actionMap[ACTION_KEY_STEP_EVENT]).isNull()
			assertThat(inputMap[KeyStroke.getKeyStroke(KeyEvent.VK_T, 0)]).isNull()
			assertThat(actionMap[ACTION_KEY_STEP_TIME]).isNull()
		}
	}

	@Test
	fun `pressing S requests step event on runner`() {
		SwingUtilities.invokeAndWait { keyBindings.install(rootPane) }

		triggerAction(ACTION_KEY_STEP_EVENT)

		verify { runner.requestStepEvent() }
	}

	@Test
	fun `pressing T requests step time with runner stepTimeDelta`() {
		SwingUtilities.invokeAndWait {
			every { runner.stepTimeDelta } returns 2.5
			keyBindings.install(rootPane)
		}

		triggerAction(ACTION_KEY_STEP_TIME)

		verify { runner.requestStepTime(2.5) }
	}

	@Test
	fun `pressing space toggles runner paused state`() {
		SwingUtilities.invokeAndWait { keyBindings.install(rootPane) }

		triggerAction(ACTION_KEY_PAUSE_TOGGLE)

		verify { runner.isPaused = true }
	}

	@Test
	fun `S is suppressed when focus is in a text field`() {
		focusOnTextField()
		SwingUtilities.invokeAndWait { keyBindings.install(rootPane) }

		triggerAction(ACTION_KEY_STEP_EVENT)

		verify(exactly = 0) { runner.requestStepEvent() }
	}

	@Test
	fun `T is suppressed when focus is in a text field`() {
		focusOnTextField()
		SwingUtilities.invokeAndWait { keyBindings.install(rootPane) }

		triggerAction(ACTION_KEY_STEP_TIME)

		verify(exactly = 0) { runner.requestStepTime(any()) }
	}

	@Test
	fun `space is suppressed when focus is in a text field`() {
		focusOnTextField()
		SwingUtilities.invokeAndWait { keyBindings.install(rootPane) }

		triggerAction(ACTION_KEY_PAUSE_TOGGLE)

		verify(exactly = 0) { runner.isPaused = any() }
	}

	/**
	 * Replace the current keyboard focus manager with a stub that reports the
	 * given [textField] as the focus owner. The original manager is restored in
	 * [tearDown].
	 */
	private fun focusOnTextField(textField: JTextField = JTextField()) {
		SwingUtilities.invokeAndWait {
			originalKfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
			val stub =
				object : DefaultKeyboardFocusManager() {
					override fun getFocusOwner() = textField

					override fun getPermanentFocusOwner() = textField
				}
			KeyboardFocusManager.setCurrentKeyboardFocusManager(stub)
		}
	}

	/**
	 * Trigger an installed action by its action key, asserting it exists.
	 */
	private fun triggerAction(actionKey: String) {
		SwingUtilities.invokeAndWait {
			val action = rootPane.actionMap[actionKey]
			assertThat(action).isNotNull()
			action.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, actionKey))
		}
	}

	companion object {
		private const val ACTION_KEY_STEP_EVENT = "simulation_step_event"
		private const val ACTION_KEY_STEP_TIME = "simulation_step_time"
		private const val ACTION_KEY_PAUSE_TOGGLE = "simulation_pause_toggle"
	}
}
