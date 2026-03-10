package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener

/**
 * Listener interface for context changes.
 * Replaces deprecated java.util.Observer pattern with PropertyChangeListener.
 *
 * @since 2026-01 (Java 21 migration)
 */
interface ContextChangeListener : ContextPropertyChangeListener {
	companion object {
		/**
		 * Property name for general context changes.
		 */
		const val CONTEXT_CHANGED = "contextChanged"

		/**
		 * Property name for cell addition.
		 */
		const val CELL_ADDED = "cellAdded"

		/**
		 * Property name for cell removal.
		 */
		const val CELL_REMOVED = "cellRemoved"

		/**
		 * Property name for track block removal.
		 */
		const val TRACK_BLOCK_REMOVED = "trackBlockRemoved"

		/**
		 * Property name for join operations.
		 */
		const val JOIN_CREATED = "joinCreated"

		/**
		 * Property name for failed join operations.
		 */
		const val JOIN_FAILED = "joinFailed"

		/**
		 * Property name for cell property modifications (e.g., name changes).
		 */
		const val CELL_MODIFIED = "cellModified"
	}

	/**
	 * Called when a context property changes.
	 *
	 * @param event ContextChangeEvent with property name and values
	 */
	override fun propertyChange(event: ContextChangeEvent)
}
