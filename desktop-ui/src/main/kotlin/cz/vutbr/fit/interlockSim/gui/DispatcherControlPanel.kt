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
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Control panel for the AI dispatcher, implementing Goal 10 SP2b.6 (Issue #561).
 *
 * Provides:
 * - A mode selector combo box (AUTO / SEMI_AUTO / MANUAL) with visual indicator
 * - A "Why this route?" button to display the last decision's rationale
 * - Real-time sync with [DispatcherModeState] for human override control
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
 * - PropertyChangeListener pattern for external state updates
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
	 * - Removes listeners from the old state (if any)
	 * - Installs a mode-change listener on the new state (if non-null)
	 * - Synchronises the combo box to the new state's effective mode
	 * - Disables controls if state is null
	 *
	 * Must be set from the EDT.
	 */
	var modeState: DispatcherModeState? = null
		set(value) {
			// Remove listener from old state if it exists
			if (field != null) {
				field.removePropertyChangeListener(STATE_PROP_EFFECTIVE_MODE, stateListener)
			}
			field = value

			// Install listener on new state if non-null
			if (value != null) {
				value.addPropertyChangeListener(STATE_PROP_EFFECTIVE_MODE, stateListener)
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
	 * Not called when the mode changes programmatically (i.e. from external state
	 * updates via the PropertyChangeListener).
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

	/** Listener that keeps the UI in sync when the mode state changes externally. */
	private val stateListener =
		PropertyChangeListener { evt: PropertyChangeEvent ->
			val newMode = evt.newValue as? DispatcherMode ?: return@PropertyChangeListener
			if (SwingUtilities.isEventDispatchThread()) {
				syncUiToMode(newMode)
			} else {
				SwingUtilities.invokeLater { syncUiToMode(newMode) }
			}
		}

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
		whyButton.addActionListener {
			onRationale?.invoke(lastDecisionRationale)
		}
		headerPanel.add(whyButton)

		// Active indicator
		activeIndicator.foreground = Color.GREEN
		headerPanel.add(activeIndicator)

		add(headerPanel)
	}

	/**
	 * Updates the UI to reflect a new effective mode.
	 *
	 * Called when the mode state changes externally or when [modeState] is set.
	 * Updates the combo box selection and visual indicator color.
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

	companion object {
		/**
		 * Property name for [DispatcherModeState] effective mode changes.
		 *
		 * **Known limitation (SP2b.6):** [DispatcherModeState] does not currently fire
		 * PropertyChangeEvents when mode changes. Currently, mode propagation works via:
		 * - **Direct UI calls:** User selects a mode in the dropdown; `updateEffectiveMode()`
		 *   synchronously updates the UI, calling registered `onModeChanged` callbacks
		 * - **Decision rationale updates:** `updateDecisionRationale()` updates the rationale
		 *   field only (does not trigger mode changes)
		 *
		 * When [DispatcherModeState] is enhanced to fire PropertyChangeEvents for mode
		 * changes, automatic UI synchronization via [stateListener] will be possible.
		 *
		 * TODO(#561): When [DispatcherModeState] implements PropertyChangeEvent firing,
		 * update the [stateListener] to respond to automatic mode changes.
		 */
		const val STATE_PROP_EFFECTIVE_MODE = "effectiveMode"
	}
