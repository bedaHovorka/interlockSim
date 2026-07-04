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

import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import javax.swing.DefaultListModel
import javax.swing.SwingUtilities

/**
 * List model for the collision-warning log panel ([WarningPanel]).
 *
 * Wraps [DefaultListModel]`<`[CollisionWarning]`>` and provides:
 * - EDT-safe [addWarning] and [clearWarnings] helpers that dispatch to the
 *   Event Dispatch Thread when called from a background thread.
 * - [hasCriticalWarning] query used by [cz.vutbr.fit.interlockSim.gui.StatusBar]
 *   to decide whether the warning indicator should be shown.
 *
 * ## Severity mapping
 *
 * All [CollisionWarning] subtypes are treated as **CRITICAL** in the current
 * implementation because each causes the simulation to pause and represents
 * a safety-critical situation:
 * - [CollisionWarning.ReservationConflict] — double-reservation
 * - [CollisionWarning.BlockEntryViolation] — train entered a foreign block
 * - [CollisionWarning.PredictiveCollision] — pre-emptive head-on warning
 *
 * @since Issue #616 (Goal 3 SP6)
 */
class WarningListModel : DefaultListModel<CollisionWarning>() {
	/**
	 * Add [warning] to the model.
	 *
	 * EDT-safe: if called from a non-EDT thread the addition is dispatched
	 * asynchronously via [SwingUtilities.invokeLater].
	 */
	fun addWarning(warning: CollisionWarning) {
		if (SwingUtilities.isEventDispatchThread()) {
			addElement(warning)
		} else {
			SwingUtilities.invokeLater { addElement(warning) }
		}
	}

	/**
	 * Remove all warnings from the model.
	 *
	 * EDT-safe: same thread-dispatch contract as [addWarning].
	 */
	fun clearWarnings() {
		if (SwingUtilities.isEventDispatchThread()) {
			clear()
		} else {
			SwingUtilities.invokeLater { clear() }
		}
	}

	/**
	 * Returns `true` when the model contains at least one unacknowledged warning.
	 *
	 * In the current implementation every warning is treated as CRITICAL, so this
	 * is equivalent to `!isEmpty`.  The method name is kept intention-revealing for
	 * future sub-phasing where severity tiers may differ.
	 *
	 * **Must be called from EDT** (reads Swing model state).
	 */
	fun hasCriticalWarning(): Boolean = !isEmpty
}
