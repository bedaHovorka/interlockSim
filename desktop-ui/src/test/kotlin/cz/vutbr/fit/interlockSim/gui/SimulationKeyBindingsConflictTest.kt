/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * SimulationKeyBindingsConflictTest — Goal 8 Issue #511: prevent shortcut conflicts
 */
package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotIn
import assertk.assertions.isNull
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Regression tests ensuring that the simulation step shortcuts `S` and `T` do not
 * conflict with existing or future Swing menu mnemonics / accelerators.
 *
 * Issue #511: simulation-mode shortcuts must stay reserved and must not overlap
 * with standard menu hotkeys.
 */
class SimulationKeyBindingsConflictTest {
	private val reservedStepKeys = setOf(KeyEvent.VK_S, KeyEvent.VK_T)

	/**
	 * Verifies that no menu item in the application menu bar uses `S` or `T` as a
	 * mnemonic or accelerator key. If a future menu item tries to claim one of those
	 * letters, this test fails and forces an explicit conflict resolution.
	 */
	@Test
	fun `menu bar does not use S or T as mnemonics or accelerators`() {
		val menuBar = MenuBar()

		forEachMenuItem(menuBar) { item ->
			val mnemonic = item.mnemonic
			if (mnemonic != 0) {
				assertThat(mnemonic, "mnemonic for ${item.text}").isNotIn(reservedStepKeys)
			}

			val acceleratorKeyCode = item.accelerator?.keyCode
			assertThat(acceleratorKeyCode, "accelerator for ${item.text}").isNotIn(reservedStepKeys)
		}
	}

	/**
	 * Verifies that the `S` and `T` step shortcuts are registered as plain keystrokes
	 * without Alt or Ctrl modifiers. Using modifiers would risk collision with standard
	 * menu accelerators (e.g. Ctrl+S "Save", Ctrl+T "New Tab", Alt+S/Alt+T mnemonics).
	 */
	@Test
	fun `step shortcuts S and T are plain keystrokes without menu modifiers`() {
		lateinit var rootPane: JPanel
		lateinit var keyBindings: SimulationKeyBindings

		SwingUtilities.invokeAndWait {
			rootPane = JPanel()
			keyBindings = SimulationKeyBindings(mockk(relaxed = true))
			keyBindings.install(rootPane)
		}

		val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)

		val stepEventStroke = inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0)) as String
		val stepTimeStroke = inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_T, 0)) as String

		assertThat(stepEventStroke).isEqualTo("simulation_step_event")
		assertThat(stepTimeStroke).isEqualTo("simulation_step_time")

		// Ensure no Alt or Ctrl variants of these keys are bound by mistake
		val altS = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.ALT_DOWN_MASK)
		val ctrlS = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK)
		val altT = KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.ALT_DOWN_MASK)
		val ctrlT = KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK)

		assertThat(inputMap.get(altS), "Alt+S binding").isNull()
		assertThat(inputMap.get(ctrlS), "Ctrl+S binding").isNull()
		assertThat(inputMap.get(altT), "Alt+T binding").isNull()
		assertThat(inputMap.get(ctrlT), "Ctrl+T binding").isNull()
	}

	/**
	 * Recursively invokes [action] for every [JMenuItem] contained in [menuBar],
	 * including nested sub-menus.
	 */
	private fun forEachMenuItem(
		menuBar: JMenuBar,
		action: (JMenuItem) -> Unit
	) {
		val queue = ArrayDeque<JMenu>()
		repeat(menuBar.menuCount) { index ->
			val menu = menuBar.getMenu(index)
			if (menu != null) queue.add(menu)
		}

		while (queue.isNotEmpty()) {
			val menu = queue.removeFirst()
			action(menu)

			for (i in 0 until menu.itemCount) {
				when (val item = menu.getItem(i)) {
					is JMenu -> queue.add(item)
					is JMenuItem -> action(item)
					else -> { /* separators or null items are ignored */ }
				}
			}
		}
	}
}
