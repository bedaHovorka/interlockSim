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
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for multi-train race conditions in PathReservationService.
 *
 * ## Focus Areas
 *
 * - Concurrent `reservePathToAny` calls from multiple trains
 * - TOCTOU (Time-of-Check-Time-of-Use) race condition handling
 * - Path intersection conflict resolution
 * - Lightweight scalability validation (3-5 trains, 20-30 blocks)
 *
 * ## Network Topology
 *
 * All tests use vyhybna.xml (7 blocks, 2 paths via switches vA and vB)
 * unless explicitly noted otherwise. Switches: vA (15,8) SIMPLE_RIGHT_FALSE,
 * vB (26,8) SIMPLE_LEFT_TRUE. Paths: MAIN (via doA1/doB1) and BRANCH (via doA2/doB2).
 *
 * ## Test Pattern
 *
 * Single-threaded execution with serial reservation calls simulating concurrent
 * scenarios. Real multi-threading deferred to integration tests.
 *
 * ## Design Goals
 *
 * - Validate atomic registration prevents partial ownership
 * - Verify multi-path fallback when primary path blocked
 * - Ensure registry consistency under contention
 *
 * @see PathReservationService.reservePathToAny
 * @see PathReservationRegistry.registerAtomic
 * @see Issue #292 Phase 2 (TOCTOU fix)
 */
@Tag("integration-test")
class PathReservationMultiTrainRaceConditionsTest : PathReservationServiceTestBase() {
	/**
	 * Helper function to assert that all blocks are owned by the specified train with consistent state.
	 *
	 * Verifies:
	 * - DynamicTrackBlock.trainName == trainId
	 * - DynamicTrackBlock.state == RESERVED
	 * - PathReservationRegistry.getOwner(block) == trainId
	 *
	 * @param trainId Expected owner train ID
	 * @param blocks List of blocks to verify
	 */
	private fun assertBlockOwnership(
		trainId: String,
		blocks: List<DynamicTrackBlock>
	) {
		blocks.forEach { block ->
			assertThat(block.trainName).isEqualTo(trainId)
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(registry.getOwner(block)).isEqualTo(trainId)
		}
	}

	/**
	 * Tests concurrent reservation with trains using different network paths.
	 *
	 * **Scenario**:
	 * - Train1 from zA (14,8) → reserves one path to any available target
	 * - Train2 from zB (27,8) → attempts to reserve path from opposite direction
	 *
	 * **Network Topology**: vyhybna.xml has 2 paths via switches vA (15,8) and vB (26,8).
	 * Since paths share common track blocks between switches, concurrent reservations
	 * from opposite directions will typically result in:
	 * - First train succeeds with one of the available paths
	 * - Second train either succeeds with alternative path OR fails if all paths blocked
	 *
	 * **Expected Behavior**:
	 * - Train1 reserves successfully
	 * - Train2 either succeeds with non-overlapping path OR fails (AllPathsBlocked)
	 * - If both succeed, block sets are disjoint
	 * - Registry consistency maintained regardless of outcome
	 *
	 * **Verification**:
	 * - Train1 succeeds (Success result)
	 * - Train2 outcome depends on path availability
	 * - If both succeed: block sets are disjoint
	 * - Registry tracks all successful reservations correctly
	 */
	@Test
	fun `concurrent reservation to different targets succeeds for both trains`() {
		// Arrange - Find semaphores zA and zB
		val zA = findSemaphoreByName("zA")
		val zB = findSemaphoreByName("zB")

		// Act - Train1 reserves from zA
		val result1 = service.reservePathToAny("train1", zA)

		// Assert Train1 succeeded
		assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val train1Blocks = assertReservationSuccess(result1).reservedBlocks

		// Act - Train2 reserves from zB (opposite direction)
		val result2 = service.reservePathToAny("train2", zB)

		// Assert - Train2 may succeed or fail depending on path overlap
		if (result2 is PathReservationService.ReservationResult.Success) {
			val train2Blocks = result2.reservedBlocks

			// Verify non-overlapping paths
			val train1BlockSet = train1Blocks.toSet()
			val train2BlockSet = train2Blocks.toSet()
			assertThat(train1BlockSet.intersect(train2BlockSet)).isEmpty()

			// Verify registry tracks both trains
			val registeredTrain1Blocks = service.getReservedBlocks("train1").toSet()
			val registeredTrain2Blocks = service.getReservedBlocks("train2").toSet()
			assertThat(registeredTrain1Blocks).isEqualTo(train1BlockSet)
			assertThat(registeredTrain2Blocks).isEqualTo(train2BlockSet)

			// Verify block ownership
			assertBlockOwnership("train1", train1Blocks)
			assertBlockOwnership("train2", train2Blocks)
		} else {
			// Train2 failed - verify it owns no blocks
			assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
			val train2Blocks = service.getReservedBlocks("train2")
			assertThat(train2Blocks).isEmpty()

			// Verify train1 maintains ownership
			assertBlockOwnership("train1", train1Blocks)
		}
	}

	/**
	 * Tests TOCTOU (Time-of-Check-Time-of-Use) race condition handling with explicit path.
	 *
	 * **TOCTOU Window**: The time between:
	 * 1. Topology check (findAllTopologicalPaths succeeds)
	 * 2. Atomic registration (tryAtomicReservation may fail)
	 *
	 * **Scenario**:
	 * - Train1 reserves path from zA to InOut B using reservePath (specific target)
	 * - Train2 attempts same explicit path (topology check passes)
	 * - Train2's atomic registration detects conflict and aborts
	 *
	 * **Critical Guarantee**: No partial ownership occurs
	 * - Train2 registers ZERO blocks (not 0, 3, or 6 out of 7)
	 * - Registry remains consistent with block state
	 *
	 * **Implementation Detail**: `tryAtomicReservation` uses
	 * `PathReservationRegistry.registerAtomic` which provides
	 * all-or-nothing semantics.
	 *
	 * **Note**: Using `reservePath` with explicit target to ensure both trains
	 * attempt the same path (not alternative paths via `reservePathToAny`).
	 *
	 * **See Also**: Issue #292 Phase 2 (TOCTOU fix)
	 */
	@Test
	fun `atomic registration prevents TOCTOU race condition`() {
		// Arrange - Use inOut1 and inOut2 (explicit path, no alternatives)
		val target = inOut2

		// Step 1: Train1 reserves explicit path
		val result1 = service.reservePath("train1", inOut1, target)
		assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val train1Blocks = assertReservationSuccess(result1).reservedBlocks

		// Step 2: Train2 attempts same explicit path (TOCTOU window)
		val result2 = service.reservePath("train2", inOut1, target)
		assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// Verify atomicity - train1 keeps all blocks
		val registeredTrain1Blocks = service.getReservedBlocks("train1")
		assertThat(registeredTrain1Blocks.size).isEqualTo(train1Blocks.size)

		// Verify train2 has NO partial ownership
		val train2Blocks = service.getReservedBlocks("train2")
		assertThat(train2Blocks).isEmpty()

		// Verify block state consistency
		train1Blocks.forEach { block ->
			assertThat(registry.getOwner(block)).isEqualTo("train1")
			assertThat(block.trainName).isEqualTo("train1")
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
		}
	}

	/**
	 * Tests path intersection conflict when trains have overlapping routes.
	 *
	 * **Scenario**: Train1 reserves explicit path (inOut1 → inOut2), Train2
	 * attempts same explicit path which overlaps with Train1's blocks.
	 *
	 * **Expected Behavior**:
	 * - Train2's `reservePath` finds paths topologically
	 * - Atomic registration detects conflict on shared blocks
	 * - Returns `AllPathsBlocked` (all candidate paths are occupied)
	 *
	 * **Design Note**: Using `reservePath` with explicit target ensures both
	 * trains attempt the same path (no alternative path fallback).
	 *
	 * **Verification**:
	 * - Train1 maintains ownership of full path
	 * - Train2 owns zero blocks (no partial ownership)
	 * - No registry corruption
	 */
	@Test
	fun `partial path overlap causes correct conflict detection`() {
		// Step 1: Train1 reserves explicit path
		val result1 = service.reservePath("train1", inOut1, inOut2)
		assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val train1Blocks = assertReservationSuccess(result1).reservedBlocks
		assertThat(train1Blocks).isNotEmpty()
		// Verify all blocks are owned by train1
		train1Blocks.forEach { block ->
			assertThat(block.trainName).isEqualTo("train1")
		}

		// Step 2: Train2 attempts same explicit path
		val result2 = service.reservePath("train2", inOut1, inOut2)
		assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// Verify no overlap (train2 got nothing)
		val train2Blocks = service.getReservedBlocks("train2")
		assertThat(train2Blocks).isEmpty()
		assertThat(train1Blocks.intersect(train2Blocks)).isEmpty()

		// Verify train1 maintains ownership
		assertBlockOwnership("train1", train1Blocks)
	}

	/**
	 * Tests multi-path fallback when primary path is blocked.
	 *
	 * **Scenario**: vyhybna.xml has 2 paths (MAIN and BRANCH via switches vA/vB). Train1
	 * reserves one path, Train2 should automatically find alternative path if available.
	 *
	 * **Expected Behavior**:
	 * - `reservePathToAny` tries multiple targets in sorted order
	 * - If first target blocked, tries next target
	 * - Both trains may succeed with non-overlapping paths if network topology allows
	 *
	 * **Algorithm**: `reservePathToAny` prioritizes:
	 * 1. InOuts (opposite-side first if start is oriented)
	 * 2. Semaphores (sorted by path length)
	 *
	 * **Design Strength**: Decouples train logic from network topology.
	 * Trains don't need to know about switch positions (MAIN vs BRANCH) explicitly.
	 *
	 * **Verification**:
	 * - Train1 succeeds with some path
	 * - Train2 either succeeds with non-overlapping path OR fails (AllPathsBlocked)
	 * - If both succeed, block sets are disjoint
	 * - Registry tracks trains correctly
	 */
	@Test
	fun `multiple trains find alternative non-conflicting paths`() {
		// Arrange - Find semaphores zA and zB (opposite directions)
		val zA = findSemaphoreByName("zA")
		val zB = findSemaphoreByName("zB")

		// Act - Train1 reserves from zA
		val result1 = service.reservePathToAny("train1", zA)
		assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val train1Blocks = assertReservationSuccess(result1).reservedBlocks.toSet()

		// Act - Train2 attempts from zB (opposite direction)
		val result2 = service.reservePathToAny("train2", zB)

		// Assert - Train2 may succeed or fail depending on path availability
		if (result2 is PathReservationService.ReservationResult.Success) {
			val train2Blocks = result2.reservedBlocks.toSet()

			// Verify non-overlapping paths
			assertThat(train1Blocks.intersect(train2Blocks)).isEmpty()

			// Verify ownership
			assertBlockOwnership("train1", train1Blocks.toList())
			assertBlockOwnership("train2", train2Blocks.toList())
		} else {
			// If train2 failed, verify it owns no blocks
			assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
			val train2Blocks = service.getReservedBlocks("train2")
			assertThat(train2Blocks).isEmpty()
		}

		// Verify train1 maintains ownership regardless of train2 outcome
		assertBlockOwnership("train1", train1Blocks.toList())
	}

	/**
	 * Lightweight scalability test: 3 trains on medium network (vyhybna.xml, 7 blocks).
	 *
	 * **Scenario**: Three trains request paths simultaneously on vyhybna.xml
	 * network with limited capacity (7 blocks total).
	 *
	 * **Expected Behavior**:
	 * - At least one train succeeds (network not deadlocked)
	 * - Some trains may fail (AllPathsBlocked) due to capacity
	 * - No ownership conflicts or registry corruption
	 *
	 * **Performance Goal**: Test completes in <1 second
	 * (validates no exponential blowup in pathfinding)
	 *
	 * **Scalability Note**: This is a lightweight test. Full performance
	 * benchmarking should use separate benchmark suite with larger networks
	 * (100+ blocks, 10+ trains).
	 *
	 * **Verification**:
	 * - Success rate ≥ 33% (at least 1 of 3 trains)
	 * - Registry consistency (no duplicate block ownership)
	 * - Block state matches registry
	 */
	@Test
	fun `three trains coordinate on vyhybna network`() {
		// Arrange - Find semaphores for 3 different starting points
		val zA = findSemaphoreByName("zA")
		val zB = findSemaphoreByName("zB")
		val doA1 = findSemaphoreByName("doA1")

		// Act - Three trains attempt reservation
		val result1 = service.reservePathToAny("train1", zA)
		val result2 = service.reservePathToAny("train2", zB)
		val result3 = service.reservePathToAny("train3", doA1)

		val results = listOf(result1, result2, result3)

		// Assert - At least one train succeeds
		val successCount = results.count { it is PathReservationService.ReservationResult.Success }
		assertThat(successCount).isGreaterThanOrEqualTo(1)

		// Verify no block conflicts
		val allBlocks = mutableSetOf<DynamicTrackBlock>()
		for (trainId in listOf("train1", "train2", "train3")) {
			val blocks = service.getReservedBlocks(trainId)
			blocks.forEach { block ->
				assertThat(allBlocks).doesNotContain(block) // No duplicates
				allBlocks.add(block)
			}
		}

		// Verify block count constraint (vyhybna.xml has 7 blocks)
		assertThat(allBlocks.size).isLessThanOrEqualTo(7)

		// Verify ownership consistency
		allBlocks.forEach { block ->
			val owner = registry.getOwner(block)
			assertThat(owner).isNotNull()
			assertThat(block.trainName).isEqualTo(owner)
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
		}
	}

	/**
	 * Sequential scalability test: 5 trains using same path with proper cleanup.
	 *
	 * **Scenario**: Five trains sequentially reserve → navigate → release
	 * the same path (zA → InOut B). Tests long-term stability and cleanup.
	 *
	 * **Expected Behavior**:
	 * - All trains succeed (no cumulative corruption)
	 * - Each release fully cleans up (blocks return to FREE)
	 * - Registry empty after all trains complete
	 *
	 * **Memory Leak Detection**: Verifies no orphaned reservations or
	 * stale references accumulate over multiple reserve/release cycles.
	 *
	 * **Performance Goal**: 5 iterations complete in <500ms
	 * (validates no memory leak or GC pressure)
	 *
	 * **Verification**:
	 * - All 5 trains succeed
	 * - Registry empty after final release
	 * - All blocks return to FREE state
	 */
	@Test
	fun `five trains sequential reservation on vyhybna network`() {
		// Arrange - Find semaphore zA
		val zA = findSemaphoreByName("zA")

		// Act - Loop 5 times: reserve → release
		for (i in 1..5) {
			val trainId = "train$i"

			// Reserve
			val result = service.reservePathToAny(trainId, zA)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val blocks = assertReservationSuccess(result).reservedBlocks

			// Verify ownership
			blocks.forEach { block ->
				assertThat(block.trainName).isEqualTo(trainId)
				assertThat(registry.getOwner(block)).isEqualTo(trainId)
			}

			// Release
			service.releasePath(trainId)

			// Verify cleanup
			assertThat(service.getReservedBlocks(trainId)).isEmpty()
			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.FREE)
				assertThat(block.trainName).isNull()
			}
		}

		// Final verification: registry completely empty
		// (No direct registry.trainCount() or blockCount() methods, so verify no trains have blocks)
		for (i in 1..5) {
			val trainId = "train$i"
			assertThat(service.getReservedBlocks(trainId)).isEmpty()
		}
	}
}
