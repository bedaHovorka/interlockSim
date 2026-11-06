/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Atomic rollback of a partially blocked reservation (Issue #1165 split). */
@Tag("integration-test")
class PathReservationAtomicRollbackTest : PathReservationServiceTestBase() {
	@Test
	fun `partial reservation failure rolls back all blocks`() {
		// Arrange - manually reserve one block in the middle of the path
		val allPaths = navigator.findAllTopologicalPaths(inOut1, inOut2)
		assertThat(allPaths).isNotNull()
		// After Issue #291 fix: vyhybna.xml correctly discovers 2 paths (k1 and k2)
		assertThat(allPaths.size).isEqualTo(2)

		// Verify the two paths are different (different sets of blocks)
		// Path 0: through MAIN branch (doA1 → doB1)
		// Path 1: through BRANCH branch (doA2 → doB2)
		val path0Blocks =
			allPaths[0]
				.map { it.getTrackBlock() }
				.filterIsInstance<DynamicTrackBlock>()
		val path1Blocks =
			allPaths[1]
				.map { it.getTrackBlock() }
				.filterIsInstance<DynamicTrackBlock>()

		// Both paths must have blocks
		assertThat(path0Blocks).isNotEmpty()
		assertThat(path1Blocks).isNotEmpty()

		// The two paths should have some different blocks (not identical)
		assertThat(path0Blocks.toSet()).isNotEqualTo(path1Blocks.toSet())

		// Use first path for rollback test
		val path = allPaths.first()
		val blocks =
			path
				.map { section ->
					val block = section.getTrackBlock()
					block as DynamicTrackBlock
				}.distinct()

		// Find blocks that exist in ALL paths to ensure conflict blocks all routes
		// This prevents the test from being non-deterministic with multiple paths
		val commonBlocks = path0Blocks.intersect(path1Blocks)
		require(commonBlocks.isNotEmpty()) {
			"Test requires common blocks between paths. Found ${path0Blocks.size} and ${path1Blocks.size} blocks."
		}
		val blockToReserve = blocks.first { it in commonBlocks }

		// Reserve a block that blocks ALL paths (simulate partial conflict)
		blockToReserve.setUpPath(inOut1, "other-train")

		// Act - try to reserve path for train1
		val result = service.reservePath("train1", inOut1, inOut2)

		// Assert - reservation should fail
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// Verify NO blocks are reserved for train1
		val reservedBlocks = service.getReservedBlocks("train1")
		assertThat(reservedBlocks).isEmpty()

		// Verify blocks not reserved by other-train are FREE (rollback succeeded)
		// Note: blockToReserve is intentionally RESERVED by "other-train", so check different blocks
		val freeBlocks = blocks.filter { it != blockToReserve }
		require(freeBlocks.isNotEmpty()) { "Test requires at least one block besides the conflict block" }
		assertThat(freeBlocks[0].getState()).isEqualTo(TrackFacility.State.FREE)
		assertThat(freeBlocks[0].trainName).isNull()
	}
}
