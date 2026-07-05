/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.conflict

import cz.vutbr.fit.interlockSim.gui.GuiConstants
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolution
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Side panel that presents ranked conflict-resolution candidates to the dispatcher.
 *
 * When a [ConflictDetectedEvent] is received, the resolution engine generates a
 * ranked [List]`<`[ConflictResolution]`>` and calls [showResolutions] to populate
 * this panel. The dispatcher:
 * 1. Reviews the ranked candidates (least → most disruptive).
 * 2. Selects a candidate in the list.
 * 3. Clicks **Apply** to invoke [onResolutionApplied] with the selected resolution.
 * 4. Clicks **Dismiss** to discard the conflict without applying any resolution.
 *
 * ## Layout
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │  Conflict Resolution         [Dismiss]        │
 * │  Train-A vs Train-B on Block-7               │
 * ├──────────────────────────────────────────────┤
 * │  1. HOLD_TRAIN  — Hold Train-A for 30s       │
 * │  2. SPEED_ADJUST — Reduce Train-A speed 50%  │
 * │  3. REROUTE  — Reroute Train-A via 3-segment │
 * ├──────────────────────────────────────────────┤
 * │  [Apply]                                     │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * ## Thread safety
 *
 * [showResolutions] and [clearResolutions] are EDT-safe and may be called from any
 * thread; they dispatch to the EDT when needed. All other public methods must be
 * called from the EDT.
 *
 * @since Issue #590 (Goal 9 SP5)
 */
class ConflictResolutionPanel : JPanel() {
	/** Backing list model. */
	val listModel: ConflictResolutionListModel = ConflictResolutionListModel()

	private val resolutionList = JList(listModel)
	private val conflictLabel = JLabel(" ")
	private val applyButton = JButton("Apply")

	/**
	 * Invoked (on EDT) when the dispatcher clicks **Apply** with a resolution selected.
	 *
	 * Receives the chosen [ConflictResolution] so the caller can act on it
	 * (e.g. log, schedule, or feed into auto-application in Issue #568).
	 * Set to `null` to disable the apply action.
	 */
	var onResolutionApplied: ((resolution: ConflictResolution) -> Unit)? = null

	/**
	 * Invoked (on EDT) when the dispatcher clicks **Dismiss** or when [clearResolutions]
	 * is called. Set to `null` to disable.
	 */
	var onDismiss: (() -> Unit)? = null

	init {
		isVisible = false // Hidden until a conflict requires dispatcher input
		layout = BorderLayout()
		border = BorderFactory.createTitledBorder("Conflict Resolution")
		preferredSize = Dimension(PANEL_WIDTH, 100)

		// Header: conflict description + Dismiss button
		val headerPanel = JPanel(BorderLayout())
		headerPanel.isOpaque = false
		conflictLabel.foreground = CONFLICT_COLOR
		headerPanel.add(conflictLabel, BorderLayout.CENTER)

		val dismissButton = JButton("Dismiss")
		dismissButton.addActionListener { dismiss() }
		val headerToolBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))
		headerToolBar.isOpaque = false
		headerToolBar.add(dismissButton)
		headerPanel.add(headerToolBar, BorderLayout.EAST)
		add(headerPanel, BorderLayout.NORTH)

		// Resolution list
		resolutionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
		resolutionList.cellRenderer = ResolutionCellRenderer()
		resolutionList.addListSelectionListener { e ->
			if (!e.valueIsAdjusting) {
				applyButton.isEnabled = resolutionList.selectedValue != null
			}
		}
		val scrollPane = JScrollPane(resolutionList)
		add(scrollPane, BorderLayout.CENTER)

		// Footer: Apply button
		applyButton.isEnabled = false
		applyButton.addActionListener {
			val selected = resolutionList.selectedValue ?: return@addActionListener
			onResolutionApplied?.invoke(selected)
			clearResolutions()
		}
		val footerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
		footerPanel.isOpaque = false
		footerPanel.add(applyButton)
		add(footerPanel, BorderLayout.SOUTH)
	}

	/**
	 * Populate the panel with [resolutions] generated for [conflict].
	 *
	 * Updates [conflictLabel] to describe the active conflict, replaces the list contents,
	 * and makes the panel visible.
	 *
	 * EDT-safe: may be called from any thread.
	 */
	fun showResolutions(
		conflict: ConflictDetectedEvent,
		resolutions: List<ConflictResolution>
	) {
		val labelText =
			"T=${String.format(Locale.ROOT, "%05.2f", conflict.time)}s  " +
				"${conflict.trainId} vs ${conflict.conflictingTrainId}  " +
				"on ${conflict.block.name}"
		if (SwingUtilities.isEventDispatchThread()) {
			conflictLabel.text = labelText
			applyButton.isEnabled = false
			listModel.setResolutions(resolutions)
			isVisible = true
		} else {
			SwingUtilities.invokeLater {
				conflictLabel.text = labelText
				applyButton.isEnabled = false
				listModel.setResolutions(resolutions)
				isVisible = true
			}
		}
	}

	/**
	 * Remove all resolutions from the panel and hide it.
	 *
	 * EDT-safe: may be called from any thread.
	 */
	fun clearResolutions() {
		if (SwingUtilities.isEventDispatchThread()) {
			conflictLabel.text = " "
			applyButton.isEnabled = false
			listModel.clearResolutions()
			isVisible = false
			onDismiss?.invoke()
		} else {
			SwingUtilities.invokeLater {
				conflictLabel.text = " "
				applyButton.isEnabled = false
				listModel.clearResolutions()
				isVisible = false
				onDismiss?.invoke()
			}
		}
	}

	private fun dismiss() {
		require(SwingUtilities.isEventDispatchThread()) { "dismiss must be called from EDT" }
		clearResolutions()
	}

	// ── Cell renderer ─────────────────────────────────────────────────────────

	private class ResolutionCellRenderer : DefaultListCellRenderer() {
		override fun getListCellRendererComponent(
			list: JList<*>,
			value: Any?,
			index: Int,
			isSelected: Boolean,
			cellHasFocus: Boolean
		): Component {
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
			if (value is ConflictResolution) {
				text = formatResolution(index + 1, value)
				toolTipText = formatTooltip(value)
			}
			return this
		}

		private fun formatResolution(
			rank: Int,
			resolution: ConflictResolution
		): String {
			val strategy = resolution.strategy.name
			val description = resolution.estimatedImpact.description
			val delay = resolution.estimatedImpact.delaySeconds
			val delayStr =
				if (delay > 0.0) String.format(Locale.ROOT, " (+%.0fs)", delay) else ""
			return "$rank. $strategy$delayStr — $description"
		}

		private fun formatTooltip(resolution: ConflictResolution): String {
			val affected = resolution.affectedTrains.joinToString(", ")
			return "<html><b>${resolution.strategy}</b><br>" +
				"Affected trains: <b>$affected</b><br>" +
				"${resolution.estimatedImpact.description}</html>"
		}
	}

	companion object {
		/** Default preferred width for the conflict resolution panel when visible. */
		const val PANEL_WIDTH: Int = GuiConstants.CONFLICT_RESOLUTION_PANEL_WIDTH

		/** Foreground color for conflict-label text. */
		val CONFLICT_COLOR: Color = GuiConstants.CONFLICT_LABEL_COLOR
	}
}
