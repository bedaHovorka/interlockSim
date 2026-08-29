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

import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility

/**
 * Base exception for all track reservation errors.
 *
 * This sealed hierarchy replaces the former string-constant error classification
 * with type-safe exception subtypes, enabling compile-time error handling
 * validation and cleaner catch blocks.
 *
 * ## Design Benefits
 *
 * - **Type safety**: Compiler verifies exception handling is exhaustive
 * - **Structured data**: Each exception carries relevant context fields
 * - **Clear intent**: Exception type clearly communicates the error category
 * - **Maintainability**: Adding new error types requires no string constant changes
 *
 * ## Usage Example
 *
 * ```kotlin
 * try {
 *     block.setUpPathWithTrainId(separator, trainId)
 * } catch (e: TrackReservationException) {
 *     when (e) {
 *         is TrackReservationException.AlreadyReservedConflict -> {
 *             logger.warn { "TOCTOU race: ${e.block} reserved by ${e.existingSeparator}" }
 *             tryNextPath()
 *         }
 *         is TrackReservationException.InvalidStateTransition -> {
 *             logger.error(e) { "State machine violation: ${e.operation}" }
 *             abort()
 *         }
 *     }
 * }
 * ```
 *
 * @since Issue #292 Phase 2 Enhancement (Plan Phase 1)
 */
sealed class TrackReservationException(
	message: String,
	cause: Throwable? = null
) : RuntimeException(message, cause) {
	/**
	 * Block already reserved from a different separator.
	 *
	 * Thrown when attempting to reserve a block that's already
	 * reserved from a different direction. This typically indicates:
	 * - TOCTOU race condition (block became reserved between check and use)
	 * - Conflicting path reservations from different trains
	 *
	 * ## Scenario
	 *
	 * ```
	 * 1. Train A checks if block is FREE → true
	 * 2. Train B reserves block from separator X
	 * 3. Train A attempts to reserve from separator Y → AlreadyReservedConflict
	 * ```
	 *
	 * ## Recovery Strategy
	 *
	 * - Try next available path (fallback)
	 * - Report AllPathsBlocked if no alternatives exist
	 * - Do NOT propagate as fatal error (expected in multi-path scenarios)
	 *
	 * @property block The conflicting block
	 * @property existingSeparator The separator that already reserved the block
	 * @property attemptedSeparator The separator attempting to reserve
	 */
	class AlreadyReservedConflict(
		val block: DynamicTrackBlock,
		val existingSeparator: PathSeparator,
		val attemptedSeparator: PathSeparator
	) : TrackReservationException(
			"Block $block already reserved from $existingSeparator " +
				"(attempted reservation from $attemptedSeparator)"
		)

	/**
	 * Invalid state transition attempted.
	 *
	 * Thrown when attempting a state transition that violates
	 * the track block state machine. This indicates:
	 * - Incorrect operation sequencing
	 * - State corruption
	 * - Programming error
	 *
	 * ## State Machine
	 *
	 * Valid transitions:
	 * - FREE → RESERVED (setUpPath)
	 * - RESERVED → OCCUPIED (enter)
	 * - OCCUPIED → FREE (leave)
	 * - RESERVED → FREE (cancelPathSetup)
	 *
	 * Invalid transitions (will throw this exception):
	 * - OCCUPIED → RESERVED (cannot reserve occupied block)
	 * - FREE → OCCUPIED (must reserve first)
	 * - etc.
	 *
	 * ## Recovery Strategy
	 *
	 * This is typically a fatal error indicating programming logic issue:
	 * - Log full error with stack trace
	 * - Abort current operation
	 * - Report to caller as failure
	 *
	 * @property block The block with invalid state
	 * @property fromState The current state
	 * @property operation The attempted operation
	 */
	class InvalidStateTransition(
		val block: DynamicTrackBlock,
		val fromState: TrackFacility.State,
		val operation: String
	) : TrackReservationException(
			"Invalid state transition for $block: " +
				"Cannot $operation from state $fromState"
		)
}
