/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.warning

import cz.vutbr.fit.interlockSim.gui.GuiConstants
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Warning log panel that lists collision warnings detected during simulation.
 *
 * Each row in the list shows:
 * - A severity icon (⚠ for all current subtypes which are treated as CRITICAL)
 * - Simulation time of detection (formatted as `T=XX.XXs`)
 * - Involved trains (e.g. `[Train-A vs Train-B]`)
 * - A brief message describing the hazard type
 *
 * ## Layout
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │  Collision Warnings        [Clear]            │
 * ├──────────────────────────────────────────────┤
 * │  ⚠ T=12.30s  [Train-A vs Train-B]            │
 * │    ReservationConflict on Block-7             │
 * │  ⚠ T=15.00s  [Train-A]                       │
 * │    BlockEntryViolation (reserved for Train-B) │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * ## Thread safety
 *
 * All public mutating methods must be called from the EDT.  [addWarning] is
 * additionally EDT-safe for background-thread callers (it dispatches to the EDT
 * when needed).
 *
 * ## Highlight callback
 *
 * Set [onWarningSelected] to receive the selected [CollisionWarning] whenever the
 * operator clicks a row.  The canvas can use the contained block reference (for
 * [CollisionWarning.BlockEntryViolation] and [CollisionWarning.ReservationConflict])
 * to highlight the relevant section.
 *
 * @since Issue #616 (Goal 3 SP6)
 */
class WarningPanel : JPanel() {
	/** Backing list model. */
	val listModel: WarningListModel = WarningListModel()

	private val warningList = JList(listModel)

	/**
	 * Invoked (on EDT) when the operator selects a warning in the list.
	 *
	 * Receives the selected [CollisionWarning] so the canvas can highlight the
	 * involved block.  Set to `null` to disable highlight feedback.
	 */
	var onWarningSelected: ((warning: CollisionWarning) -> Unit)? = null

	/**
	 * Invoked (on EDT) whenever [clearWarnings] runs, whether from the Clear button or a
	 * programmatic call. Set to `null` to disable.
	 */
	var onClear: (() -> Unit)? = null

	init {
		layout = BorderLayout()
		border = BorderFactory.createTitledBorder("Collision Warnings")
		preferredSize = Dimension(100, WARNING_PANEL_HEIGHT)

		warningList.selectionMode = ListSelectionModel.SINGLE_SELECTION
		warningList.cellRenderer = WarningCellRenderer()
		warningList.addListSelectionListener { e ->
			if (!e.valueIsAdjusting) {
				val selected = warningList.selectedValue ?: return@addListSelectionListener
				onWarningSelected?.invoke(selected)
			}
		}

		val scrollPane = JScrollPane(warningList)
		add(scrollPane, BorderLayout.CENTER)

		val clearButton = JButton("Clear")
		clearButton.addActionListener { clearWarnings() }

		val toolBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))
		toolBar.isOpaque = false
		toolBar.add(clearButton)
		add(toolBar, BorderLayout.NORTH)
	}

	/**
	 * Append [warning] to the log.
	 *
	 * EDT-safe: may be called from any thread; delegates to [WarningListModel.addWarning].
	 */
	fun addWarning(warning: CollisionWarning) {
		listModel.addWarning(warning)
	}

	/**
	 * Remove all warnings from the log and deselect any selection.
	 *
	 * **Must be called from EDT.**
	 */
	fun clearWarnings() {
		require(SwingUtilities.isEventDispatchThread()) { "clearWarnings must be called from EDT" }
		listModel.clearWarnings()
		onClear?.invoke()
	}

	/** Returns `true` when the model contains at least one unacknowledged warning. */
	internal fun hasCriticalWarning(): Boolean = listModel.hasCriticalWarning()

	// ── Cell renderer ─────────────────────────────────────────────────────────

	private class WarningCellRenderer : DefaultListCellRenderer() {
		override fun getListCellRendererComponent(
			list: JList<*>,
			value: Any?,
			index: Int,
			isSelected: Boolean,
			cellHasFocus: Boolean
		): Component {
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
			if (value is CollisionWarning) {
				text = formatWarning(value)
				foreground = if (isSelected) list.selectionForeground else CRITICAL_COLOR
				toolTipText = formatTooltip(value)
			}
			return this
		}

		private fun formatWarning(warning: CollisionWarning): String {
			val time = String.format(Locale.ROOT, "T=%05.2fs", warning.time)
			return when (warning) {
				is CollisionWarning.ReservationConflict ->
					"⚠ $time  [${warning.trainId} vs ${warning.conflictingTrainId}]  ReservationConflict"

				is CollisionWarning.BlockEntryViolation ->
					"⚠ $time  [${warning.trainId}]  BlockEntryViolation" +
						(warning.reservedForAtDetection?.let { "  (reserved for $it)" } ?: "")

				is CollisionWarning.PredictiveCollision ->
					"⚠ $time  [${warning.trainId} vs ${warning.targetTrainId}]  PredictiveCollision"
			}
		}

		private fun formatTooltip(warning: CollisionWarning): String =
			when (warning) {
				is CollisionWarning.ReservationConflict ->
					"<html><b>Reservation Conflict</b><br>" +
						"Train <b>${warning.trainId}</b> tried to reserve a block already held " +
						"by <b>${warning.conflictingTrainId}</b>." +
						(warning.conflictingBlock?.let { "<br>Block: ${it.name}" } ?: "") +
						"</html>"

				is CollisionWarning.BlockEntryViolation -> {
					val reservation =
						warning.reservedForAtDetection
							?.let { " which was reserved for <b>$it</b>" }
							?: ""
					"<html><b>Block Entry Violation</b><br>" +
						"Train <b>${warning.trainId}</b> entered block <b>${warning.block.name}</b>" +
						reservation +
						".</html>"
				}

				is CollisionWarning.PredictiveCollision ->
					"<html><b>Predictive Collision</b><br>" +
						"Train <b>${warning.trainId}</b> and <b>${warning.targetTrainId}</b> " +
						"are on a collision course.</html>"
			}
	}

	companion object {
		/** Default preferred height for the warning panel when visible. */
		const val WARNING_PANEL_HEIGHT: Int = GuiConstants.WARNING_PANEL_HEIGHT

		/** Foreground color for warning rows (matches status-bar warning indicator). */
		val CRITICAL_COLOR: Color = GuiConstants.WARNING_BADGE_COLOR
	}
}
