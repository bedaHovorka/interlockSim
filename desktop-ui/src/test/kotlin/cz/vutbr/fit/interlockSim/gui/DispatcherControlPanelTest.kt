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
import assertk.assertions.isTrue
import assertk.fail
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatcherMode
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import cz.vutbr.fit.interlockSim.testutil.findAllComponents
import cz.vutbr.fit.interlockSim.testutil.findComponent
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
 * - Setting [DispatcherControlPanel.modeState] syncs the UI and enables controls
 * - Mode selector changes call [onModeChanged] callback
 * - "Why this route?" button calls [onRationale] callback
 * - Decision rationale updates via [updateDecisionRationale]
 * - Visual indicator color changes based on mode (GREEN/ORANGE/RED)
 * - Null mode state disables controls without throwing
 * - Re-assigning [DispatcherControlPanel.modeState] re-syncs the UI to external changes
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

	private fun findComboBox(): JComboBox<DispatcherMode> {
		@Suppress("UNCHECKED_CAST")
		val type = JComboBox::class.java as Class<JComboBox<DispatcherMode>>
		return findComponent(panel, type)
			?: fail("Component finder: JComboBox<DispatcherMode> not found in DispatcherControlPanel")
	}

	private fun findButton(text: String): JButton =
		findAllComponents(panel, JButton::class.java)
			.firstOrNull { it.text == text }
			?: fail("Component finder: JButton with text '$text' not found in DispatcherControlPanel")

	private fun findIndicator(): JLabel =
		findAllComponents(panel, JLabel::class.java)
			.firstOrNull { it.text.contains("Active") }
			?: fail("Component finder: Active indicator label not found in DispatcherControlPanel")

	// ── Swing component helpers ────────────────────────────────────────────────

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
	fun `re-assigning modeState after an external override syncs combo box without triggering callback`() {
		val capturedModes = mutableListOf<DispatcherMode>()

		SwingUtilities.invokeAndWait {
			panel.modeState = modeState
			panel.onModeChanged = { mode -> capturedModes.add(mode) }

			// Change the effective mode externally, then re-assign modeState to pick it up.
			// DispatcherModeState has no event-firing capability (it lives in commonMain,
			// so it cannot depend on java.beans types), so external changes are only
			// reflected here the next time modeState is (re-)assigned.
			modeState.setOverride(DispatcherMode.SEMI_AUTO)
			panel.modeState = modeState

			val comboBox = findComboBox()
			assertThat(comboBox.selectedItem).isEqualTo(DispatcherMode.SEMI_AUTO)
		}

		// onModeChanged should not be called for programmatic re-sync
		assertThat(capturedModes).isEqualTo(emptyList())
	}

	// ── Decision rationale ──────────────────────────────────────────────────────

	@Test
	fun `updateDecisionRationale stores rationale for display`() {
		val rationale = listOf("Rule 1", "Rule 2")
		val decision = DispatchDecision.ApproveTrain(trainId = "T1", rationale = rationale)

		SwingUtilities.invokeAndWait {
			// Wire modeState so the button is enabled
			panel.modeState = modeState
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
	fun `clicking 'Why this route' calls onRationale callback`() {
		val capturedRationales = mutableListOf<List<String>>()
		val rationale = listOf("Path available", "Shortest route")

		SwingUtilities.invokeAndWait {
			// Wire modeState so the button is enabled
			panel.modeState = modeState
			panel.onRationale = { r -> capturedRationales.add(r) }
			panel.updateDecisionRationale(DispatchDecision.ApproveTrain(trainId = "T1", rationale = rationale))

			findButton("Why this route?").doClick()
		}

		assertThat(capturedRationales).isEqualTo(listOf(rationale))
	}

	@Test
	fun `clearRationale resets rationale to empty`() {
		val capturedRationales = mutableListOf<List<String>>()

		SwingUtilities.invokeAndWait {
			// Wire modeState so the button is enabled
			panel.modeState = modeState
			panel.onRationale = { r -> capturedRationales.add(r) }
			panel.updateDecisionRationale(DispatchDecision.ApproveTrain(trainId = "T1", rationale = listOf("Rule 1")))
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

	@Test
	fun `selecting a mode in the combo updates the indicator color immediately`() {
		// Regression for Critical 2: the combo action listener must refresh the
		// indicator on operator selection, not only on the next modeState sync
		// (DispatcherModeState has no event-firing — without this the indicator
		// would stay stale until modeState is re-assigned).
		SwingUtilities.invokeAndWait {
			panel.modeState = modeState

			val comboBox = findComboBox()
			comboBox.selectedItem = DispatcherMode.MANUAL

			val indicator = findIndicator()
			assertThat(indicator.foreground.rgb).isEqualTo(java.awt.Color.RED.rgb)
		}
	}
}
