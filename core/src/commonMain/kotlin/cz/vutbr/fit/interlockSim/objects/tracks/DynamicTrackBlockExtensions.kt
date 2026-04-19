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

import cz.vutbr.fit.interlockSim.objects.core.TrackFacility

/*
 * Kotlin extension functions for DynamicTrackBlock operations.
 *
 * These extensions provide idiomatic Kotlin APIs for common operations,
 * improving readability and reducing boilerplate.
 *
 * @since Issue #292 Phase 2 Enhancement (Plan Phase 5)
 */

/**
 * Check if all blocks in this list are in FREE state.
 *
 * ## Usage
 *
 * ```kotlin
 * val blocks: List<DynamicTrackBlock> = getBlocks()
 * if (blocks.areAllFree()) {
 *     reservePath(blocks)
 * }
 * ```
 *
 * @return true if ALL blocks are FREE, false if any is RESERVED or OCCUPIED
 */
fun List<DynamicTrackBlock>.areAllFree(): Boolean = all { it.getState() == TrackFacility.State.FREE }

/**
 * Check if this block is reserved by a specific train.
 *
 * A block is considered "reserved by" a train if:
 * - Block state is RESERVED
 * - Block trainId matches the given ID
 *
 * ## Usage
 *
 * ```kotlin
 * if (block.isReservedBy("train123")) {
 *     logger.info { "Block owned by train123" }
 * }
 * ```
 *
 * @param trainId The train identifier to check
 * @return true if block is RESERVED by this train, false otherwise
 */
fun DynamicTrackBlock.isReservedBy(trainId: String): Boolean =
	this.trainName == trainId && getState() == TrackFacility.State.RESERVED

/**
 * Check if this block is occupied by a specific train.
 *
 * A block is considered "occupied by" a train if:
 * - Block state is OCCUPIED
 * - Block trainId matches the given ID
 *
 * ## Usage
 *
 * ```kotlin
 * if (block.isOccupiedBy("train123")) {
 *     logger.warn { "Collision detected!" }
 * }
 * ```
 *
 * @param trainId The train identifier to check
 * @return true if block is OCCUPIED by this train, false otherwise
 */
fun DynamicTrackBlock.isOccupiedBy(trainId: String): Boolean =
	this.trainName == trainId && getState() == TrackFacility.State.OCCUPIED

/**
 * Check if this block is owned by any train (RESERVED or OCCUPIED).
 *
 * ## Usage
 *
 * ```kotlin
 * if (block.isOwnedByAnyTrain()) {
 *     logger.debug { "Block is in use" }
 * }
 * ```
 *
 * @return true if block has a non-null trainId, false otherwise
 */
fun DynamicTrackBlock.isOwnedByAnyTrain(): Boolean = this.trainName != null

/**
 * Get the count of blocks in this list that are FREE.
 *
 * ## Usage
 *
 * ```kotlin
 * val freeCount = blocks.countFree()
 * logger.info { "$freeCount out of ${blocks.size} blocks are free" }
 * ```
 *
 * @return Number of blocks in FREE state
 */
fun List<DynamicTrackBlock>.countFree(): Int = count { it.getState() == TrackFacility.State.FREE }

/**
 * Get the count of blocks in this list that are RESERVED.
 *
 * @return Number of blocks in RESERVED state
 */
fun List<DynamicTrackBlock>.countReserved(): Int = count { it.getState() == TrackFacility.State.RESERVED }

/**
 * Get the count of blocks in this list that are OCCUPIED.
 *
 * @return Number of blocks in OCCUPIED state
 */
fun List<DynamicTrackBlock>.countOccupied(): Int = count { it.getState() == TrackFacility.State.OCCUPIED }

/**
 * Check if all blocks in this list are available for a specific train.
 *
 * A block is considered "available" for a train if:
 * - Block is FREE (no owner), OR
 * - Block is already owned by THIS train (RESERVED or OCCUPIED)
 *
 * This allows trains to re-reserve paths through blocks they already own,
 * which is necessary for incremental path extension as trains move forward.
 *
 * ## Usage
 *
 * ```kotlin
 * val blocks: List<DynamicTrackBlock> = getBlocks()
 * if (blocks.areAllFreeOrOwnedBy("train123")) {
 *     // All blocks are available - either free or already owned by train123
 *     reservePath("train123", blocks)
 * }
 * ```
 *
 * ## Bug Fix (Issue #296)
 * Previously, `areAllFree()` was used which rejected paths through blocks
 * already owned by the requesting train. This caused trains to fail path
 * reservation when trying to extend their reserved path forward.
 *
 * @param trainId The train identifier to check ownership
 * @return true if ALL blocks are FREE or owned by this train, false if any is owned by another train
 */
fun List<DynamicTrackBlock>.areAllFreeOrOwnedBy(trainId: String): Boolean =
	all { block ->
		block.getState() == TrackFacility.State.FREE ||
			block.trainName == trainId
	}
