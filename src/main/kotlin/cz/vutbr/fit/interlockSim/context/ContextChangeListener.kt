package cz.vutbr.fit.interlockSim.context;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * Listener interface for context changes.
 * Replaces deprecated java.util.Observer pattern with PropertyChangeListener.
 *
 * @since 2026-01 (Java 21 migration)
 */
public interface ContextChangeListener extends PropertyChangeListener {

	/**
	 * Property name for general context changes.
	 */
	String CONTEXT_CHANGED = "contextChanged";

	/**
	 * Property name for cell addition.
	 */
	String CELL_ADDED = "cellAdded";

	/**
	 * Property name for cell removal.
	 */
	String CELL_REMOVED = "cellRemoved";

	/**
	 * Property name for track block removal.
	 */
	String TRACK_BLOCK_REMOVED = "trackBlockRemoved";

	/**
	 * Property name for join operations.
	 */
	String JOIN_CREATED = "joinCreated";

	/**
	 * Property name for failed join operations.
	 */
	String JOIN_FAILED = "joinFailed";

	/**
	 * Called when a context property changes.
	 *
	 * @param evt PropertyChangeEvent with property name and values
	 */
	@Override
	void propertyChange(PropertyChangeEvent evt);
}
