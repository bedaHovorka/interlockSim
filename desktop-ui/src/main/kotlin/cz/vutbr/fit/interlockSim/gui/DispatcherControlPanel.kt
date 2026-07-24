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

import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatcherMode
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Control panel for the AI dispatcher, implementing Goal 10 SP2b.6 (Issue #561).
 *
 * Provides:
 * - A mode selector combo box (AUTO / SEMI_AUTO / MANUAL) with visual indicator
 * - A "Why this route?" button to display the last decision's rationale
 * - Sync with [DispatcherModeState] at the moment [modeState] is assigned
 * - Thread-safe EDT integration for mode changes
 *
 * **Layout Structure:**
 * ```
 * ┌─────────────────────────────────────────────────────────┐
 * │ Dispatcher: ┌─────────────┐  [Why this route?]  │ ● ●  │
 * │             │ [AUTO ▼]    │                      │Active│
 * └─────────────────────────────────────────────────────────┘
 * ```
 *
 * **Integration:**
 * - Wired via Koin DI in [guiDispatcherModule]
 * - Must be manually wired to [DispatcherModeState] instance on simulation start
 * - Callbacks ([onModeChanged], [onRationale]) route user actions to the simulation
 *
 * **Thread Safety:**
 * All Swing operations must be called from the Event Dispatch Thread (EDT).
 * Mode state queries are thread-safe via [DispatcherModeState]'s internal synchronization.
 *
 * @since Issue #561 (SP2b.6 — Goal 10)
 * @see DispatcherModeState
 * @see DispatchDecision
 */
class DispatcherControlPanel : JPanel() {
	/** Mode selector dropdown. */
	private val modeComboBox: JComboBox<DispatcherMode> = JComboBox(DispatcherMode.values())

	/** Active mode indicator (visual circle). */
	private val activeIndicator: JLabel = JLabel("● Active")

	/** "Why this route?" button to display rationale for last decision. */
	private val whyButton: JButton = JButton("Why this route?")

	/** Holds the last decision's rationale for display on button click. */
	private var lastDecisionRationale: List<String> = emptyList()

	/**
	 * The [DispatcherModeState] instance currently wired to this panel, or `null`.
	 *
	 * Setting this property:
	 * - Synchronises the combo box to the new state's effective mode
	 * - Disables controls if state is null
	 *
	 * Note: this is a one-time sync taken when the property is assigned, not a live
	 * subscription — [DispatcherModeState] lives in commonMain and has no event-firing
	 * capability (java.beans PropertyChange types are JVM-only and cannot appear there).
	 * External mode changes made through [DispatcherModeState.setOverride] are only
	 * reflected here the next time [modeState] is (re-)assigned.
	 *
	 * Must be set from the EDT.
	 */
	var modeState: DispatcherModeState? = null
		set(value) {
			field = value

			if (value != null) {
				syncUiToMode(value.getEffectiveMode())
				modeComboBox.isEnabled = true
				whyButton.isEnabled = true
			} else {
				// When value is null: disable controls
				modeComboBox.isEnabled = false
				whyButton.isEnabled = false
				activeIndicator.foreground = Color.GRAY
			}
		}

	/**
	 * Optional callback invoked when the user selects a new mode from the combo box.
	 *
	 * The callback receives the selected [DispatcherMode] and is responsible for
	 * updating the [modeState] override via [DispatcherModeState.setOverride].
	 *
	 * Not called when the mode changes programmatically (i.e. when [modeState] is
	 * (re-)assigned and the combo box is synced to the new state).
	 */
	var onModeChanged: ((DispatcherMode) -> Unit)? = null

	/**
	 * Optional callback invoked when the user clicks the "Why this route?" button.
	 *
	 * Called with the [lastDecisionRationale] list to allow the UI to display it.
	 * Typically opens a dialog or updates a status display with the rationale text.
	 */
	var onRationale: ((List<String>) -> Unit)? = null

	/** Flag to suppress recursive combo box → state → combo box feedback loops. */
	private var updatingFromState = false

	init {
		layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
		border = BorderFactory.createEtchedBorder()
		preferredSize = Dimension(400, 50)
		isOpaque = true

		// Header panel: mode selector + indicator + why button
		val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
		headerPanel.isOpaque = false

		// Label
		val label = JLabel("Dispatcher:")
		headerPanel.add(label)

		// Mode combo box
		modeComboBox.isEditable = false
		modeComboBox.isEnabled = false
		modeComboBox.addActionListener { evt ->
			if (!updatingFromState) {
				val selected = modeComboBox.selectedItem as? DispatcherMode
				if (selected != null) {
					onModeChanged?.invoke(selected)
				}
			}
		}
		headerPanel.add(modeComboBox)

		// "Why this route?" button
		whyButton.isEnabled = false
		whyButton.addActionListener {
			onRationale?.invoke(lastDecisionRationale)
		}
		headerPanel.add(whyButton)

		// Active indicator (disabled by default, matches modeState == null)
		activeIndicator.foreground = Color.GRAY
		headerPanel.add(activeIndicator)

		add(headerPanel)
	}

	/**
	 * Updates the UI to reflect a new effective mode.
	 *
	 * Called when [modeState] is (re-)assigned. Updates the combo box selection and
	 * visual indicator color.
	 *
	 * Must be called from the EDT.
	 */
	private fun syncUiToMode(mode: DispatcherMode) {
		updatingFromState = true
		try {
			modeComboBox.selectedItem = mode

			// Update visual indicator color based on mode
			activeIndicator.foreground =
				when (mode) {
					DispatcherMode.AUTO -> Color.GREEN
					DispatcherMode.SEMI_AUTO -> Color.ORANGE
					DispatcherMode.MANUAL -> Color.RED
				}
		} finally {
			updatingFromState = false
		}
	}

	/**
	 * Updates the last decision's rationale from the dispatcher.
	 *
	 * Call this method when a new [DispatchDecision] is applied by the dispatcher.
	 * The rationale is stored for later display when the user clicks the
	 * "Why this route?" button.
	 *
	 * Must be called from the EDT.
	 *
	 * @param decision The decision returned by the dispatcher
	 */
	fun updateDecisionRationale(decision: DispatchDecision) {
		lastDecisionRationale = decision.rationale
	}

	/**
	 * Clears the stored decision rationale (e.g. when a simulation stops).
	 *
	 * Must be called from the EDT.
	 */
	fun clearRationale() {
		lastDecisionRationale = emptyList()
	}
}
