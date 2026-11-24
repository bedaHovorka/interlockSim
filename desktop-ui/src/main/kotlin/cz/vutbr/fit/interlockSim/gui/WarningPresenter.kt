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

import cz.vutbr.fit.interlockSim.gui.warning.WarningPanel
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import java.awt.Toolkit

/**
 * What one collision warning does to the UI (Issue #616, Goal 3 SP6): it is listed in the
 * [WarningPanel]; a [CollisionWarning.Severity.CRITICAL] warning lights the status-bar indicator,
 * selects the Warnings tab, beeps when [show]'s sound flag is on, and can resume the run the
 * collision service paused. The selection highlight and the clear wiring live here too (Issue
 * #1331: only a CRITICAL warning gets the collision highlight; an advisory clears it).
 *
 * The collision-response settings themselves stay on [Frame] (MenuBar's menu toggles write them
 * and the simulation-thread listener reads them atomically at registration).
 *
 * **All methods are EDT-only.**
 */
internal class WarningPresenter(
	private val warningPanel: WarningPanel,
	private val statusBar: StatusBar,
	private val canvas: RailwayNetGridCanvas,
	private val bottomPane: BottomPane,
	/** Resumes the run the collision service paused, called when [show]'s autoPause is off. */
	private val resumeRun: () -> Unit
) {
	init {
		// Selection highlights the involved block on the canvas when known (Issue #1331: only
		// a CRITICAL warning gets the collision highlight; an advisory clears it instead -- the
		// rationale lives on RailwayNetGridCanvas.clearWarningHighlights).
		warningPanel.onWarningSelected = { warning ->
			if (warning.severity == CollisionWarning.Severity.CRITICAL) {
				canvas.highlightWarningBlock(warning)
			} else {
				canvas.clearWarningHighlights()
			}
		}
		// Clearing the log also silences the status-bar warning indicator (Issue #616 follow-up).
		warningPanel.onClear = {
			statusBar.setWarningIndicator(false)
		}
	}

	/**
	 * Show one collision warning; **must be called from EDT**.
	 *
	 * Every warning is listed in [warningPanel]. Only a [CollisionWarning.Severity.CRITICAL] warning
	 * lights the status-bar indicator, beeps when [sound] is on, and resumes the run the collision
	 * service paused when [autoPause] is off. An advisory [CollisionWarning.Severity.WARNING] (a train
	 * still queued when the run stopped, Issue #1331) neither paused the run nor is an alarm, so it
	 * leaves the indicator, the sound and the pause state alone.
	 */
	fun show(
		warning: CollisionWarning,
		autoPause: Boolean,
		sound: Boolean
	) {
		warningPanel.addWarning(warning)
		if (warning.severity != CollisionWarning.Severity.CRITICAL) return
		statusBar.setWarningIndicator(true)
		// A critical warning must not hide behind another tab or behind a bottom pane the user
		// folded up with the split bar (Issue #1421, PR #1430 review)
		bottomPane.revealCriticalWarningsTab()
		if (sound) {
			Toolkit.getDefaultToolkit().beep()
		}
		// If auto-pause is OFF, resume the simulation immediately.
		if (!autoPause) {
			resumeRun()
		}
	}
}
