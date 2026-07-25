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
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Modal Swing dialog for SEMI_AUTO dispatcher-decision approval (SP2b.6 follow-up — Issue #806).
 *
 * Surfaces the details of a pending [DispatchDecision] — decision type, train identifier, any
 * route-specific information, and the rationale list — and waits for the operator to click
 * **Approve** or **Dismiss** before returning.
 *
 * ## Layout
 * ```
 * ┌─────────────────────────────────────────────────────────┐
 * │ Dispatcher Decision Pending                             │
 * ├─────────────────────────────────────────────────────────┤
 * │ Decision:    ApproveTrain                               │
 * │ Train:       T1                                         │
 * │ Route:       zA → doA1        (only for ReservePath)    │
 * │ Rationale:                                              │
 * │ ┌──────────────────────────────────────────────────┐   │
 * │ │  • Rule 1                                        │   │
 * │ │  • Rule 2                                        │   │
 * │ └──────────────────────────────────────────────────┘   │
 * │           [Approve]  [Dismiss]                          │
 * └─────────────────────────────────────────────────────────┘
 * ```
 *
 * ## Thread safety
 *
 * Instances must be **created and shown on the EDT**. The typical caller pattern
 * (invoked from the kDisco simulation thread) is:
 *
 * ```kotlin
 * var approved = false
 * SwingUtilities.invokeAndWait {
 *     val dialog = SemiAutoApprovalDialog(ownerFrame, decision)
 *     dialog.isVisible = true   // blocks EDT until Approve or Dismiss is clicked
 *     approved = dialog.approved
 * }
 * // The sim thread resumes here with `approved` set.
 * ```
 *
 * Calling `SemiAutoApprovalDialog.promptOnEdt` encapsulates this pattern; the
 * [cz.vutbr.fit.interlockSim.sim.SemiAutoApprovalGateway] approver lambda installed by
 * [cz.vutbr.fit.interlockSim.gui.Frame.wireDispatcherControlPanel] delegates to it.
 *
 * @param owner         The owning [Frame] (may be `null`).
 * @param decision      The pending [DispatchDecision] to present to the operator.
 *
 * @since Issue #806 (SP2b.6 follow-up — Goal 10)
 * @see cz.vutbr.fit.interlockSim.sim.SemiAutoApprovalGateway
 */
class SemiAutoApprovalDialog(
	owner: Frame?,
	private val decision: DispatchDecision
) : JDialog(owner, "Dispatcher Decision Pending", true) {
	/**
	 * `true` if the operator clicked **Approve**, `false` if they clicked **Dismiss**
	 * (or closed the dialog without clicking either button).
	 *
	 * Only meaningful after the dialog has been shown and closed.
	 */
	var approved: Boolean = false
		private set

	init {
		isResizable = true
		minimumSize = Dimension(420, 280)
		preferredSize = Dimension(480, 320)
		defaultCloseOperation = HIDE_ON_CLOSE

		contentPane.layout = BorderLayout(8, 8)

		// ── Details panel ─────────────────────────────────────────────────
		val detailsPanel =
			JPanel().apply {
				layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
				border = BorderFactory.createEmptyBorder(12, 16, 8, 16)
			}

		fun labelRow(key: String, value: String): JPanel =
			JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
				isOpaque = false
				add(JLabel("<html><b>$key</b>&nbsp;</html>"))
				add(JLabel(value))
			}

		detailsPanel.add(labelRow("Decision:", decisionTypeName(decision)))

		val trainId = trainIdOf(decision)
		if (trainId != null) {
			detailsPanel.add(labelRow("Train:", trainId))
		}

		val route = routeOf(decision)
		if (route != null) {
			detailsPanel.add(labelRow("Route:", route))
		}

		detailsPanel.add(Box.createVerticalStrut(8))

		// Rationale area
		val rationaleLabel = JLabel("<html><b>Rationale:</b></html>")
		rationaleLabel.alignmentX = LEFT_ALIGNMENT
		detailsPanel.add(rationaleLabel)
		detailsPanel.add(Box.createVerticalStrut(4))

		val rationaleArea =
			JTextArea(formatRationale(decision.rationale)).apply {
				isEditable = false
				lineWrap = true
				wrapStyleWord = true
				rows = 5
				border = BorderFactory.createEtchedBorder()
				alignmentX = LEFT_ALIGNMENT
			}
		val scrollPane = JScrollPane(rationaleArea)
		scrollPane.alignmentX = LEFT_ALIGNMENT
		detailsPanel.add(scrollPane)

		contentPane.add(detailsPanel, BorderLayout.CENTER)

		// ── Button panel ──────────────────────────────────────────────────
		val buttonPanel =
			JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply {
				border = BorderFactory.createEmptyBorder(0, 8, 4, 8)
			}

		val approveButton =
			JButton("Approve").apply {
				addActionListener {
					approved = true
					isVisible = false
				}
			}
		val dismissButton =
			JButton("Dismiss").apply {
				addActionListener {
					approved = false
					isVisible = false
				}
			}

		buttonPanel.add(approveButton)
		buttonPanel.add(dismissButton)
		contentPane.add(buttonPanel, BorderLayout.SOUTH)

		pack()
		setLocationRelativeTo(owner)
	}

	companion object {
		/**
		 * Shows a blocking [SemiAutoApprovalDialog] for [decision] on the EDT and returns the
		 * operator's choice.
		 *
		 * **Must be called from a non-EDT thread** (typically the kDisco simulation thread).
		 * Uses [SwingUtilities.invokeAndWait] to show the modal dialog on the EDT; the calling
		 * thread blocks until the operator clicks **Approve** (`true`) or **Dismiss** / closes
		 * the dialog (`false`).
		 *
		 * @param owner    The owning [Frame] for centering the dialog (may be `null`).
		 * @param decision The pending dispatcher decision to present.
		 * @return `true` if the operator approved, `false` otherwise.
		 */
		fun promptOnEdt(
			owner: Frame?,
			decision: DispatchDecision
		): Boolean {
			var result = false
			SwingUtilities.invokeAndWait {
				val dialog = SemiAutoApprovalDialog(owner, decision)
				dialog.isVisible = true
				result = dialog.approved
			}
			return result
		}

		private fun decisionTypeName(decision: DispatchDecision): String =
			when (decision) {
				is DispatchDecision.ApproveTrain -> "Approve Train"
				is DispatchDecision.ReservePath -> "Reserve Path"
				is DispatchDecision.HoldTrain -> "Hold Train"
				is DispatchDecision.SetSignalAspect -> "Set Signal Aspect"
				is DispatchDecision.SetSwitchPosition -> "Set Switch Position"
				is DispatchDecision.ReleaseRoute -> "Release Route"
				is DispatchDecision.RequestRoute -> "Request Route"
				is DispatchDecision.NoAction -> "No Action"
			}

		private fun trainIdOf(decision: DispatchDecision): String? =
			when (decision) {
				is DispatchDecision.ApproveTrain -> decision.trainId
				is DispatchDecision.ReservePath -> decision.trainId
				is DispatchDecision.HoldTrain -> decision.trainId
				is DispatchDecision.ReleaseRoute -> decision.trainName
				is DispatchDecision.RequestRoute -> decision.trainName
				else -> null
			}

		private fun routeOf(decision: DispatchDecision): String? =
			when (decision) {
				is DispatchDecision.ReservePath ->
					"${decision.fromSemaphoreName} → ${decision.toSeparatorName}"
				is DispatchDecision.RequestRoute ->
					"${decision.fromEndpointName} → ${decision.toEndpointName}"
				else -> null
			}

		private fun formatRationale(rationale: List<String>): String =
			if (rationale.isEmpty()) {
				"(no rationale recorded)"
			} else {
				rationale.joinToString("\n") { "• $it" }
			}
	}
}
