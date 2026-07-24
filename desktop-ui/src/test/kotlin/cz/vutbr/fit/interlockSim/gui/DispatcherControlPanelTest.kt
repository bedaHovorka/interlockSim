/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for DispatcherControlPanel (Issue #561)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatcherMode
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * Unit tests for [DispatcherControlPanel].
 *
 * [DispatcherControlPanel] is a [javax.swing.JPanel] subclass and can be instantiated
 * in headless environments (no X11 display required). All state-mutation tests drive
 * it via its public API and inspect the child components directly.
 *
 * Covers:
 * - Initial state: combo box disabled, "Why this route?" button disabled
 * - Setting [DispatcherControlPanel.modeState] wires listeners and enables controls
 * - Mode selector changes call [onModeChanged] callback
 * - "Why this route?" button calls [onRationale] callback
 * - Decision rationale updates via [updateDecisionRationale]
 * - Visual indicator color changes based on mode (GREEN/ORANGE/RED)
 * - Null mode state disables controls without throwing
 * - PropertyChangeListener integration for external mode changes
 *
 * @since Issue #561 (SP2b.6 — Goal 10)
 */
@DisplayName("DispatcherControlPanel")
class DispatcherControlPanelTest {
	private lateinit var panel: DispatcherControlPanel
	private lateinit var modeState: DispatcherModeState

	@BeforeEach
	fun setUp() {
		SwingUtilities.invokeAndWait {
			panel = DispatcherControlPanel()
		}
		modeState = DispatcherModeState()
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private fun findComboBox(): JComboBox<DispatcherMode> =
		findComponent(panel, JComboBox::class.java)
			?: error("JComboBox not found in DispatcherControlPanel")

	private fun findButton(text: String): JButton =
		findAllComponents(panel, JButton::class.java)
			.firstOrNull { it.text == text }
			?: error("JButton with text '$text' not found in DispatcherControlPanel")

	private fun findIndicator(): JLabel =
		findAllComponents(panel, JLabel::class.java)
			.firstOrNull { it.text.contains("Active") }
			?: error("Active indicator label not found in DispatcherControlPanel")

	// ── Swing component helpers ────────────────────────────────────────────────

	/** Recursively find the first component of [type] in the container hierarchy. */
	private fun <T> findComponent(
		container: java.awt.Container,
		type: Class<T>
	): T? {
		for (c in container.components) {
			if (type.isInstance(c)) {
				@Suppress("UNCHECKED_CAST")
				return c as T
			}
			if (c is java.awt.Container) {
				val found = findComponent(c, type)
				if (found != null) return found
			}
		}
		return null
	}

	/** Recursively collect all components of [type] in the container hierarchy. */
	private fun <T> findAllComponents(
		container: java.awt.Container,
		type: Class<T>
	): List<T> {
		val result = mutableListOf<T>()
		for (c in container.components) {
			if (type.isInstance(c)) {
				@Suppress("UNCHECKED_CAST")
				result.add(c as T)
			}
			if (c is java.awt.Container) {
				result.addAll(findAllComponents(c, type))
			}
		}
		return result
	}

	// ── Initial state ──────────────────────────────────────────────────────────

	@Test
	fun `initial state has disabled controls`() {
		SwingUtilities.invokeAndWait {
			val comboBox = findComboBox()
			val whyButton = findButton("Why this route?")

			assertThat(comboBox.isEnabled).isFalse()
			assertThat(whyButton.isEnabled).isFalse()
		}
	}

	// ── Setting modeState ──────────────────────────────────────────────────────

	@Test
	fun `setting modeState enables controls`() {
		SwingUtilities.invokeAndWait {
			panel.modeState = modeState

			val comboBox = findComboBox()
			val whyButton = findButton("Why this route?")

			assertThat(comboBox.isEnabled).isTrue()
			assertThat(whyButton.isEnabled).isTrue()
		}
	}

	@Test
	fun `setting modeState syncs combo box to current effective mode`() {
		SwingUtilities.invokeAndWait {
			modeState.setOverride(DispatcherMode.SEMI_AUTO)
			panel.modeState = modeState

			val comboBox = findComboBox()
			assertThat(comboBox.selectedItem).isEqualTo(DispatcherMode.SEMI_AUTO)
		}
	}

	@Test
	fun `setting modeState to null disables controls`() {
		SwingUtilities.invokeAndWait {
			panel.modeState = modeState
			panel.modeState = null

			val comboBox = findComboBox()
			val whyButton = findButton("Why this route?")

			assertThat(comboBox.isEnabled).isFalse()
			assertThat(whyButton.isEnabled).isFalse()
		}
	}

	// ── Mode changes ───────────────────────────────────────────────────────────

	@Test
	fun `selecting a mode calls onModeChanged callback`() {
		val capturedModes = mutableListOf<DispatcherMode>()

		SwingUtilities.invokeAndWait {
			panel.modeState = modeState
			panel.onModeChanged = { mode -> capturedModes.add(mode) }

			val comboBox = findComboBox()
			comboBox.selectedItem = DispatcherMode.MANUAL
		}

		assertThat(capturedModes).isEqualTo(listOf(DispatcherMode.MANUAL))
	}

	@Test
	fun `setting override via modeState updates combo box without triggering callback`() {
		val capturedModes = mutableListOf<DispatcherMode>()

		SwingUtilities.invokeAndWait {
			panel.modeState = modeState
			panel.onModeChanged = { mode -> capturedModes.add(mode) }

			// Change the effective mode externally
			modeState.setOverride(DispatcherMode.SEMI_AUTO)

			val comboBox = findComboBox()
			assertThat(comboBox.selectedItem).isEqualTo(DispatcherMode.SEMI_AUTO)
		}

		// onModeChanged should not be called for external changes
		assertThat(capturedModes).isEqualTo(emptyList())
	}

	// ── Decision rationale ──────────────────────────────────────────────────────

	@Test
	fun `updateDecisionRationale stores rationale for display`() {
		val rationale = listOf("Rule 1", "Rule 2")
		val decision = TestDispatchDecision(rationale)

		SwingUtilities.invokeAndWait {
			panel.updateDecisionRationale(decision)
		}

		// Can't directly inspect the private field, but we can verify through the callback
		val capturedRationales = mutableListOf<List<String>>()
		SwingUtilities.invokeAndWait {
			panel.onRationale = { r -> capturedRationales.add(r) }
			findButton("Why this route?").doClick()
		}

		assertThat(capturedRationales).isEqualTo(listOf(rationale))
	}

	@Test
	fun `clicking 'Why this route?' calls onRationale callback`() {
		val capturedRationales = mutableListOf<List<String>>()
		val rationale = listOf("Path available", "Shortest route")

		SwingUtilities.invokeAndWait {
			panel.onRationale = { r -> capturedRationales.add(r) }
			panel.updateDecisionRationale(TestDispatchDecision(rationale))

			findButton("Why this route?").doClick()
		}

		assertThat(capturedRationales).isEqualTo(listOf(rationale))
	}

	@Test
	fun `clearRationale resets rationale to empty`() {
		val capturedRationales = mutableListOf<List<String>>()

		SwingUtilities.invokeAndWait {
			panel.onRationale = { r -> capturedRationales.add(r) }
			panel.updateDecisionRationale(TestDispatchDecision(listOf("Rule 1")))
			panel.clearRationale()

			findButton("Why this route?").doClick()
		}

		assertThat(capturedRationales).isEqualTo(listOf(emptyList()))
	}

	// ── Visual indicator ───────────────────────────────────────────────────────

	@Test
	fun `indicator color is green for AUTO mode`() {
		SwingUtilities.invokeAndWait {
			modeState.setOverride(DispatcherMode.AUTO)
			panel.modeState = modeState

			val indicator = findIndicator()
			// Color comparison: GREEN
			assertThat(indicator.foreground.rgb).isEqualTo(java.awt.Color.GREEN.rgb)
		}
	}

	@Test
	fun `indicator color is orange for SEMI_AUTO mode`() {
		SwingUtilities.invokeAndWait {
			modeState.setOverride(DispatcherMode.SEMI_AUTO)
			panel.modeState = modeState

			val indicator = findIndicator()
			// Color comparison: ORANGE
			assertThat(indicator.foreground.rgb).isEqualTo(java.awt.Color.ORANGE.rgb)
		}
	}

	@Test
	fun `indicator color is red for MANUAL mode`() {
		SwingUtilities.invokeAndWait {
			modeState.setOverride(DispatcherMode.MANUAL)
			panel.modeState = modeState

			val indicator = findIndicator()
			// Color comparison: RED
			assertThat(indicator.foreground.rgb).isEqualTo(java.awt.Color.RED.rgb)
		}
	}

	// ── Test helper class ──────────────────────────────────────────────────────

	private data class TestDispatchDecision(override val rationale: List<String> = emptyList()) :
		DispatchDecision()
}
