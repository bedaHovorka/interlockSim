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

import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolution
import javax.swing.DefaultListModel
import javax.swing.SwingUtilities

/**
 * List model for the conflict-resolution selection panel ([ConflictResolutionPanel]).
 *
 * Wraps [DefaultListModel]`<`[ConflictResolution]`>` and provides:
 * - EDT-safe [setResolutions] and [clearResolutions] helpers that dispatch to the
 *   Event Dispatch Thread when called from a background thread.
 * - [isEmpty] query used by [ConflictResolutionPanel] to decide visibility.
 *
 * @since Issue #590 (Goal 9 SP5)
 */
class ConflictResolutionListModel : DefaultListModel<ConflictResolution>() {
	/**
	 * Replace the current resolution list with [resolutions].
	 *
	 * EDT-safe: if called from a non-EDT thread the replacement is dispatched
	 * asynchronously via [SwingUtilities.invokeLater].
	 */
	fun setResolutions(resolutions: List<ConflictResolution>) {
		if (SwingUtilities.isEventDispatchThread()) {
			clear()
			resolutions.forEach { addElement(it) }
		} else {
			SwingUtilities.invokeLater {
				clear()
				resolutions.forEach { addElement(it) }
			}
		}
	}

	/**
	 * Remove all resolutions from the model.
	 *
	 * EDT-safe: same thread-dispatch contract as [setResolutions].
	 */
	fun clearResolutions() {
		if (SwingUtilities.isEventDispatchThread()) {
			clear()
		} else {
			SwingUtilities.invokeLater { clear() }
		}
	}

	/** Returns `true` when the model contains no resolutions. */
	val hasNoResolutions: Boolean get() = size == 0
}
