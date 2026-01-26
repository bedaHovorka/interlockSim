/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

/**
 * Standard error reason constants for DynamicTrackBlock operations.
 *
 * These constants are used in TrackOperationException messages to enable
 * type-safe error classification without requiring exception subclasses.
 *
 * @since Issue #301
 */
object DynamicTrackBlockErrors {
	/**
	 * Error when attempting to reserve a block that's already reserved
	 * from a different separator (conflict).
	 */
	const val ALREADY_RESERVED_CONFLICT = "Block already reserved from different separator"

	/**
	 * Error when attempting state transition from invalid source state.
	 */
	const val INVALID_STATE_TRANSITION = "Invalid state transition"
}
