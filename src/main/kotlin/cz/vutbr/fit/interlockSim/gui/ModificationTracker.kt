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

import cz.vutbr.fit.interlockSim.context.ContextChangeListener
import cz.vutbr.fit.interlockSim.context.ContextChangeListener.Companion.CELL_ADDED
import cz.vutbr.fit.interlockSim.context.ContextChangeListener.Companion.CELL_REMOVED
import cz.vutbr.fit.interlockSim.context.ContextChangeListener.Companion.JOIN_CREATED
import cz.vutbr.fit.interlockSim.context.ContextChangeListener.Companion.TRACK_BLOCK_REMOVED
import java.beans.PropertyChangeEvent
import java.io.File

/**
 * Tracks modification state for railway network editing.
 *
 * Listens to PropertyChangeEvents from EditingContext and maintains a dirty flag
 * to indicate whether unsaved changes exist.
 *
 * Designed for extensibility:
 * - Current: Tracks network editing changes (Issue #259)
 * - Future Goal 5: Can be extended to track simulation state
 * - Future Goal 14: Can be extended to track train type changes
 * - Future Goal 15: Can be extended to track tutorial metadata
 *
 * @param onDirtyStateChanged Optional callback invoked when dirty state changes
 */
class ModificationTracker(
	private val onDirtyStateChanged: ((Boolean) -> Unit)? = null
) : ContextChangeListener {
	private var isDirty: Boolean = false
	private var currentFilePath: File? = null

	/**
	 * Returns whether there are unsaved changes.
	 */
	fun isDirty(): Boolean = isDirty

	/**
	 * Returns the current file path (if any).
	 */
	fun getCurrentFile(): File? = currentFilePath

	/**
	 * Sets the current file path.
	 * Called after successful save or load operations.
	 * Notifies listeners to update UI (e.g., window title).
	 */
	fun setCurrentFile(file: File?) {
		currentFilePath = file
		notifyListeners()
	}

	/**
	 * Marks the context as modified (dirty).
	 */
	fun markDirty() {
		if (!isDirty) {
			isDirty = true
			notifyListeners()
		}
	}

	/**
	 * Marks the context as clean (no unsaved changes).
	 * Called after successful save operations.
	 */
	fun markClean() {
		if (isDirty) {
			isDirty = false
			notifyListeners()
		}
	}

	/**
	 * Resets the tracker to initial state (clean, no file).
	 * Called when creating a new context or clearing the current one.
	 */
	fun reset() {
		isDirty = false
		currentFilePath = null
		notifyListeners()
	}

	/**
	 * Handles property change events from EditingContext.
	 * Sets dirty flag on structural modification events.
	 *
	 * Event Coverage (ContextChangeListener):
	 * - CELL_ADDED: ✓ Marks dirty (structural change)
	 * - CELL_REMOVED: ✓ Marks dirty (structural change)
	 * - JOIN_CREATED: ✓ Marks dirty (structural change)
	 * - TRACK_BLOCK_REMOVED: ✓ Marks dirty (structural change)
	 * - JOIN_FAILED: ✗ Ignored (failed operation, no actual change)
	 * - CONTEXT_CHANGED: ✗ Ignored (generic event, too broad for dirty tracking)
	 *
	 * Rationale:
	 * - Only track events that represent successful structural modifications
	 * - Failed operations (JOIN_FAILED) don't modify the network
	 * - Generic notifications (CONTEXT_CHANGED) are informational only
	 *
	 * Future extensions (as needed):
	 * - Configuration changes (maxSpeed, trackLength): Could mark dirty
	 * - Cell property modifications: Could mark dirty
	 * - InOut list modifications: Could mark dirty
	 */
	override fun propertyChange(evt: PropertyChangeEvent) {
		when (evt.propertyName) {
			CELL_ADDED, CELL_REMOVED, JOIN_CREATED, TRACK_BLOCK_REMOVED -> markDirty()
			// Explicitly ignore JOIN_FAILED (failed operation, no change)
			// Explicitly ignore CONTEXT_CHANGED (generic event)
		}
	}

	/**
	 * Notifies listeners of dirty state change.
	 */
	private fun notifyListeners() {
		onDirtyStateChanged?.invoke(isDirty)
	}

	/**
	 * Returns a formatted title suffix for display.
	 * Returns empty string if no file or clean state.
	 * Returns "*" suffix if dirty.
	 */
	fun getTitleSuffix(): String =
		when {
			currentFilePath == null -> ""
			isDirty -> "*"
			else -> ""
		}

	/**
	 * Returns a formatted filename for display in window title.
	 * Returns null if no file is currently loaded.
	 */
	fun getDisplayFileName(): String? = currentFilePath?.name
}
