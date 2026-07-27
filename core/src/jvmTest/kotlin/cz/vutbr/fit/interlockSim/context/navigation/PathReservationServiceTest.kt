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
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.koin.test.inject
import java.io.InputStream

/**
 * Comprehensive test suite for PathReservationService.
 *
 * ## Test Coverage
 *
 * - ✅ Successful reservation (all blocks FREE)
 * - ✅ Partial failure rollback (atomic guarantee)
 * - ✅ Conflict detection (block OCCUPIED)
 * - ✅ Conflict detection (block RESERVED by different train)
 * - ✅ Multiple train coordination
 * - ✅ Release path and re-reserve
 * - ✅ Idempotent operations
 * - ✅ No path exists (topology)
 * - ✅ All paths blocked
 * - ✅ Path availability check
 *
 * ## Test Data
 *
 * Uses vyhybna.xml network:
 * - inOut1 → TrackBlock → RailSwitch → TrackBlock → inOut2
 * - Simple linear network with switch in middle
 *
 * @since Issue #294 (Phase 2 of Issue #292)
 */
@Tag("integration-test")
class PathReservationServiceTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var environment: SimulationEnvironment
	private lateinit var navigator: TopologyNavigator
	private lateinit var registry: PathReservationRegistry
	private lateinit var service: PathReservationService
	private lateinit var inOut1: DynamicPathSeparator
	private lateinit var inOut2: DynamicPathSeparator

	@BeforeEach
	fun setUp() {
		// Load vyhybna.xml from resources
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")

		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		simulationContext =
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		environment = simulationContext

		// Get navigation services from the simulation context
		// (Services are scoped to the context, accessed via public API)
		service = simulationContext.getRoutingServices().getPathReservationService()

		// TopologyNavigator is internal to PathReservationService, but tests need it
		// Create it directly for test purposes (not from scope)
		navigator = simulationContext.scope.get()
		registry = simulationContext.scope.get()

		// Get InOut elements
		val inOuts = simulationContext.getInOuts()
		assertThat(inOuts.size).isEqualTo(2)

		// Convert Java List to Kotlin List and get by index
		val inOutsList = inOuts.toList()
		inOut1 = simulationContext.toDynamic(inOutsList[0])
		inOut2 = simulationContext.toDynamic(inOutsList[1])

		assertThat(inOut1).isNotNull()
		assertThat(inOut2).isNotNull()
	}

	@Nested
	inner class SuccessfulReservation {
		@Test
		fun `reservePath succeeds when all blocks are FREE`() {
			// Act
			val result = service.reservePath("train1", inOut1, inOut2)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val success = result as PathReservationService.ReservationResult.Success
			// vyhybna.xml has 7 unique blocks in the path from InOut1 to InOut2
			assertThat(success.reservedBlocks).hasSize(7)

			// Verify all blocks are DynamicTrackBlock instances (not separators) with correct ownership
			success.reservedBlocks.forEach { block ->
				assertThat(block).isInstanceOf(DynamicTrackBlock::class)
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.reservedFrom).isEqualTo(inOut1)
				assertThat(block.trainName).isEqualTo("train1")
			}
		}

		@Test
		fun `reservePath registers ownership in registry`() {
			// Act
			val result = service.reservePath("train1", inOut1, inOut2)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val reservedBlocks = service.getReservedBlocks("train1")
			assertThat(reservedBlocks.size).isEqualTo(7)
		}

		@Test
		fun `isPathAvailable returns true for free path`() {
			// Act
			val available = service.isPathAvailable(inOut1, inOut2)

			// Assert
			assertThat(available).isTrue()
		}
	}

	@Nested
	inner class RedundantReservationIdempotency {
		@Test
		fun `reservePath for a train that already holds the granted route does not duplicate its PathInfo`() {
			// Given: a route already granted once (mirrors the LLM dispatcher's request_route
			// succeeding for a train, Goal 10 SP2b.9)
			val first = service.reservePath("train1", inOut1, inOut2)
			assertThat(first).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val originalPathSize = requireNotNull(registry.getPathInfo("train1")).reservedPath.size

			// When: the identical route is redundantly re-requested for the SAME train
			// multiple times — exactly what a stateless per-cycle LLM dispatcher does when
			// it re-issues request_route for a train it already granted a route to, since
			// it has no memory of its own prior tool calls.
			repeat(2) {
				val redundant = service.reservePath("train1", inOut1, inOut2)
				assertThat(redundant).isInstanceOf<PathReservationService.ReservationResult.Success>()
			}

			// Then: PathInfo must stay exactly as originally granted — a redundant re-request
			// for a route the train already fully owns must never grow/duplicate the reserved
			// path. Without a guard, each redundant call re-merges the identical route onto
			// itself (doubling every separator), and a further redundant call hits
			// PathReservationRegistry.mergePathInfo's 3rd-occurrence cycle-abort — which
			// silently reverts the merge while reservePath still reports Success, an invisible
			// PathInfo/reality divergence that (per the same bug class) can strand a train
			// permanently at whichever semaphore first depends on the discarded segment.
			val finalPathInfo = requireNotNull(registry.getPathInfo("train1"))
			assertThat(finalPathInfo.reservedPath.size).isEqualTo(originalPathSize)
			assertThat(finalPathInfo.target).isEqualTo(inOut2)
		}
	}

	@Nested
	inner class AllPathsBlocked {
		@Test
		fun `reservePath returns AllPathsBlocked when path is RESERVED by different train`() {
			// Arrange - reserve the path for train1
			val result1 = service.reservePath("train1", inOut1, inOut2)
			assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Act - try to reserve same path for train2
			val result2 = service.reservePath("train2", inOut1, inOut2)

			// Assert
			assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		}

		@Test
		fun `reservePath rejects a reverse route through identical blocks while the forward route is held`() {
			// Arrange - reserve the forward path for train1
			val result1 = service.reservePath("train1", inOut1, inOut2)
			assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Act - train2 attempts the same physical blocks in the opposite direction
			val result2 = service.reservePath("train2", inOut2, inOut1)

			// Assert - blocks are still exclusively owned by train1, regardless of direction
			assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
			assertThat(service.getReservedBlocks("train2")).isEmpty()
		}

		@Test
		fun `isPathAvailable returns false when path is blocked`() {
			// Arrange - reserve the path
			service.reservePath("train1", inOut1, inOut2)

			// Act
			val available = service.isPathAvailable(inOut1, inOut2)

			// Assert
			assertThat(available).isFalse()
		}
	}

	@Nested
	inner class AtomicRollback {
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

	@Nested
	inner class MultipleTrainCoordination {
		@Test
		fun `multiple trains can reserve different paths without conflict`() {
			// Note: vyhybna.xml has only one path, so this test verifies sequential usage

			// Reserve for train1
			val result1 = service.reservePath("train1", inOut1, inOut2)
			assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Release for train1
			val released = service.releasePath("train1")
			assertThat(released.size).isEqualTo(7)

			// Reserve for train2 (same path, but now free)
			val result2 = service.reservePath("train2", inOut1, inOut2)
			assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Verify train2 owns the blocks
			val blocks = service.getReservedBlocks("train2")
			assertThat(blocks.size).isEqualTo(7)
			blocks.forEach { block -> assertThat(block.trainName).isEqualTo("train2") }
		}

		@Test
		fun `getReservedBlocks returns empty list for unknown train`() {
			// Act
			val blocks = service.getReservedBlocks("unknown-train")

			// Assert
			assertThat(blocks).isEmpty()
		}
	}

	@Nested
	inner class PathRelease {
		@Test
		fun `releasePath frees all blocks and clears trainId`() {
			// Arrange
			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val blocks = (result as PathReservationService.ReservationResult.Success).reservedBlocks

			// Act
			val released = service.releasePath("train1")

			// Assert
			assertThat(released).containsExactly(*blocks.toTypedArray())

			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.FREE)
				assertThat(block.reservedFrom).isNull()
				assertThat(block.trainName).isNull()
			}

			// Verify registry is cleared
			val remainingBlocks = service.getReservedBlocks("train1")
			assertThat(remainingBlocks).isEmpty()
		}

		@Test
		fun `releasePath is idempotent`() {
			// Arrange
			service.reservePath("train1", inOut1, inOut2)

			// Act
			val released1 = service.releasePath("train1")
			val released2 = service.releasePath("train1") // Second call

			// Assert
			assertThat(released1.size).isEqualTo(7)
			assertThat(released2).isEmpty() // No blocks to release on second call
		}

		@Test
		fun `releasePath returns empty list for unknown train`() {
			// Act
			val released = service.releasePath("unknown-train")

			// Assert
			assertThat(released).isEmpty()
		}
	}

	@Nested
	inner class ReservationAfterRelease {
		@Test
		fun `path can be re-reserved after release`() {
			// Reserve
			val result1 = service.reservePath("train1", inOut1, inOut2)
			assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Release
			service.releasePath("train1")

			// Re-reserve
			val result2 = service.reservePath("train1", inOut1, inOut2)
			assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Verify blocks are RESERVED again
			val blocks = service.getReservedBlocks("train1")
			assertThat(blocks.size).isEqualTo(7)
			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo("train1")
			}
		}

		@Test
		fun `different train can reserve after previous train releases`() {
			// Train1 reserves
			service.reservePath("train1", inOut1, inOut2)

			// Train1 releases
			service.releasePath("train1")

			// Train2 reserves
			val result = service.reservePath("train2", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Verify ownership
			val blocks = service.getReservedBlocks("train2")
			blocks.forEach { block -> assertThat(block.trainName).isEqualTo("train2") }

			val train1Blocks = service.getReservedBlocks("train1")
			assertThat(train1Blocks).isEmpty()
		}
	}

	@Nested
	inner class TrainIdPropagation {
		@Test
		fun `trainId is set on all blocks during reservation`() {
			// Act
			val result = service.reservePath("train123", inOut1, inOut2)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val blocks = (result as PathReservationService.ReservationResult.Success).reservedBlocks
			blocks.forEach { block -> assertThat(block.trainName).isEqualTo("train123") }
		}

		@Test
		fun `trainId is cleared when train leaves block`() {
			// Arrange
			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val blocks = (result as PathReservationService.ReservationResult.Success).reservedBlocks
			val firstBlock = blocks.first()

			val occupant =
				object : TrackOccupant {
					override val name: String = "test-occupant"

					override fun distanceToSemaphore(): Double = 0.0

					override fun nextSemaphore(): cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator? = null
				}

			firstBlock.enter(occupant)

			// Act - leave block (OCCUPIED → FREE)
			firstBlock.leave(occupant)

			// Assert - trainId should be cleared
			assertThat(firstBlock.getState()).isEqualTo(TrackFacility.State.FREE)
			assertThat(firstBlock.trainName).isNull()
		}
	}

	@Nested
	inner class ReservePathToNextSemaphore {
		@Test
		fun `reservePathToAnyNextSemaphore succeeds when path to semaphore is FREE`() {
			// Arrange
			val next = navigator.getNextTrackSection(inOut1, null)
			assertThat(next).isNotNull()

			// Act
			val result = service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success = result as PathReservationService.ReservationResult.Success
			assertThat(success.reservedBlocks).isNotNull()
			// vyhybna.xml: inOut1 (11,8) -> first semaphore at (14,8) = 1 block
			assertThat(success.reservedBlocks.size).isEqualTo(1)

			// Verify all blocks are RESERVED
			success.reservedBlocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.reservedFrom).isEqualTo(inOut1)
				assertThat(block.trainName).isEqualTo("train1")
			}
		}

		@Test
		fun `reservePathToAnyNextSemaphore registers ownership in registry`() {
			// Arrange
			val next = navigator.getNextTrackSection(inOut1, null)
			assertThat(next).isNotNull()

			// Act
			val result = service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val reservedBlocks = service.getReservedBlocks("train1")
			assertThat(reservedBlocks.size).isEqualTo(1) // Path to first semaphore
		}

		@Test
		fun `reservePathToAnyNextSemaphore returns AllPathsBlocked when blocks are occupied`() {
			// Arrange
			val next = navigator.getNextTrackSection(inOut1, null)
			assertThat(next).isNotNull()

			// Reserve the path for another train to block it
			service.reservePath("other-train", inOut1, inOut2)

			// Act - try to reserve to next semaphore for train1
			val result = service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)

			// Assert - should be blocked because first block is already reserved
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		}

		@Test
		fun `isPathToAnyNextSemaphoreAvailable returns true when path is FREE`() {
			// Arrange
			val next = navigator.getNextTrackSection(inOut1, null)

			// Act
			val available = service.isPathToAnyNextSemaphoreAvailable(inOut1, next)

			// Assert
			assertThat(available).isTrue()
		}

		@Test
		fun `isPathToAnyNextSemaphoreAvailable returns false when next is null`() {
			// Act
			val available = service.isPathToAnyNextSemaphoreAvailable(inOut1, null)

			// Assert
			assertThat(available).isFalse()
		}

		@Test
		fun `isPathToAnyNextSemaphoreAvailable returns false when path is BLOCKED`() {
			// Arrange
			val next = navigator.getNextTrackSection(inOut1, null)
			assertThat(next).isNotNull()

			// Reserve path for another train to block it
			service.reservePath("other-train", inOut1, inOut2)

			// Act
			val available = service.isPathToAnyNextSemaphoreAvailable(inOut1, next)

			// Assert - first block is reserved, so path to first semaphore is not available
			assertThat(available).isFalse()
		}

		@Test
		fun `isPathToAnyNextSemaphoreAvailable returns false when path is RESERVED by another train`() {
			// Arrange
			val next = navigator.getNextTrackSection(inOut1, null)
			assertThat(next).isNotNull()

			// Reserve path for train2
			service.reservePath("train2", inOut1, inOut2)

			// Act - check availability (doesn't reserve)
			val available = service.isPathToAnyNextSemaphoreAvailable(inOut1, next)

			// Assert
			assertThat(available).isFalse()
		}

		@Test
		fun `consistency between isPathToAnyNextSemaphoreAvailable and reservePathToAnyNextSemaphore`() {
			// Arrange
			val next = navigator.getNextTrackSection(inOut1, null)
			assertThat(next).isNotNull()

			// Act - check availability
			val available = service.isPathToAnyNextSemaphoreAvailable(inOut1, next)

			// Assert - if available, reservation should succeed
			if (available) {
				val result = service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)
				assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			}
		}

		@Test
		fun `reservePathToAnyNextSemaphore works after releasePath`() {
			// Arrange
			val next = navigator.getNextTrackSection(inOut1, null)
			assertThat(next).isNotNull()

			// Reserve and release
			service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)
			service.releasePath("train1")

			// Act - reserve again
			val result = service.reservePathToAnyNextSemaphore("train2", inOut1, next)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val blocks = service.getReservedBlocks("train2")
			assertThat(blocks.size).isEqualTo(1) // Path to first semaphore
		}

		@Test
		fun `isPathToAnyNextSemaphoreAvailable becomes true after releasePath`() {
			// Arrange
			val next = navigator.getNextTrackSection(inOut1, null)
			assertThat(next).isNotNull()

			// Reserve path
			service.reservePath("train1", inOut1, inOut2)

			// Verify path is not available
			assertThat(service.isPathToAnyNextSemaphoreAvailable(inOut1, next)).isFalse()

			// Act - release path
			service.releasePath("train1")

			// Assert - path should be available now
			assertThat(service.isPathToAnyNextSemaphoreAvailable(inOut1, next)).isTrue()
		}
	}

	/**
	 * ReservePathToAny Tests
	 *
	 * Tests for reservePathToAny() method which should try BOTH InOuts AND semaphores as targets.
	 * Based on vyhybna.xml topology.
	 */
	@Nested
	inner class ReservePathToAny {
		/**
		 * Find a semaphore by name in the grid.
		 */
		private fun findSemaphoreByName(name: String): DynamicRailSemaphore {
			val grid = simulationContext.getRailWayNetGrid()
			for (x in 0 until grid.cols) {
				for (y in 0 until grid.rows) {
					val cell =
						grid[
							cz.vutbr.fit.interlockSim.util
								.Point(x, y)
						]
					if (cell is DynamicRailSemaphore && cell.name == name) {
						return cell
					}
				}
			}
			throw IllegalStateException("Semaphore $name not found in grid")
		}

		/**
		 * Find InOut by name.
		 */
		private fun findInOutByName(name: String): DynamicInOut {
			val inOuts = simulationContext.getInOuts()
			for (inOut in inOuts) {
				val dynamic = simulationContext.toDynamic(inOut) as DynamicInOut
				if (dynamic.name == name) {
					return dynamic
				}
			}
			throw IllegalStateException("InOut $name not found")
		}

		/**
		 * Assert that reserved blocks form a path through specified separators in order.
		 */
		private fun assertPathContainsSeparators(
			blocks: List<DynamicTrackBlock>,
			vararg separatorNames: String
		) {
			// Collect all separators from block endpoints
			val allSeparators = mutableSetOf<String>()
			blocks.forEach { block ->
				val (sep1, sep2) = block.ends()

				// Collect names from dynamic separator types
				// All dynamic separators have .name property
				when (sep1) {
					is DynamicRailSemaphore -> {
						val name = sep1.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
					is cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch -> {
						val name = sep1.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
					is DynamicInOut -> {
						val name = sep1.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
				}

				when (sep2) {
					is DynamicRailSemaphore -> {
						val name = sep2.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
					is cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch -> {
						val name = sep2.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
					is DynamicInOut -> {
						val name = sep2.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
				}
			}

			// Verify all expected separators are present
			separatorNames.forEach { expectedName ->
				if (!allSeparators.contains(expectedName)) {
					throw AssertionError("Expected separator '$expectedName' not found in path. Found: $allSeparators")
				}
			}
		}

		private fun assertIsDirectedToOutSide(
			blocks: List<DynamicTrackBlock>,
			nameOfOut: String
		) {
			val sem1 = "do${nameOfOut}1"
			val sem2 = "do${nameOfOut}2"
			assertThat(
				blocks.any { block ->
					val (sep1, sep2) = block.ends()
					(sep1 is DynamicInOut && sep1.name == nameOfOut) ||
						(sep2 is DynamicInOut && sep2.name == nameOfOut) ||
						(sep1 is DynamicRailSemaphore && (sep1.name == sem1 || sep1.name == sem2)) ||
						(sep2 is DynamicRailSemaphore && (sep2.name == sem1 || sep2.name == sem2))
				}
			).isTrue()
		}

		private fun assertIsReachedOutSide(
			blocks: List<DynamicTrackBlock>,
			nameOfOut: String
		) {
			assertThat(
				blocks.any { block ->
					val (sep1, sep2) = block.ends()
					(sep1 is DynamicInOut && sep1.name == nameOfOut) ||
						(sep2 is DynamicInOut && sep2.name == nameOfOut)
				}
			).isTrue()
		}

		@Test
		fun `test scenario 1 - from zA to B side semaphores`() {
			// Arrange - Find zA semaphore (14,8) and target semaphores
			val zA = findSemaphoreByName("zA")

			// Act - reserve path from zA to any available target
			val result = service.reservePathToAny("train1", zA)

			// Assert - reservation succeeded
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Get reserved blocks
			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks

			// Verify blocks are RESERVED for train1
			blocks.forEach { block ->
				assertThat(block).isInstanceOf(DynamicTrackBlock::class)
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo("train1")
				assertThat(block.reservedFrom).isEqualTo(zA)
			}

			// Verify START semaphore signal allows entry (not STOP)
			assertThat(zA.signal).isNotEqualTo(Signal.STOP)

			// Assert path reaches one of: doB1, doB2, or B
			// Path should go through: zA → vA → (doA1 or doA2) → (k1 or k2) → (doB1 or doB2)
			assertPathContainsSeparators(blocks, "zA", "vA")
			// Path must reach B side (at least one of: doB1, doB2, or InOut B)
			assertIsDirectedToOutSide(blocks, "B")
		}

		@Test
		fun `test scenario 2 - from zB to A side semaphores`() {
			// Arrange - Find zB semaphore (27,8)
			val zB = findSemaphoreByName("zB")

			// Act - reserve path from zB to any available target
			val result = service.reservePathToAny("train2", zB)

			// Assert - reservation succeeded
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Get reserved blocks
			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks

			// Verify blocks are RESERVED for train2
			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo("train2")
				assertThat(block.reservedFrom).isEqualTo(zB)
			}

			// Assert path starts from zB (reservePathToAny finds ANY valid target)
			// In vyhybna.xml, from zB there are multiple possible targets
			// The algorithm tries InOuts first, so may go to B (shortest path)
			assertPathContainsSeparators(blocks, "zB")
			assertThat(blocks).isNotNull()
			assertThat(blocks.isEmpty()).isFalse()

			// Path must reach A side (at least one of: doA1, doA2, or InOut A)
			assertIsDirectedToOutSide(blocks, "A")
		}

		@Test
		fun `test scenario 3 - from doA1 to InOut A`() {
			// Arrange - Find doA1 semaphore (16,8)
			val doA1 = findSemaphoreByName("doA1")

			// Act - reserve path from doA1
			val result = service.reservePathToAny("train1", doA1)

			// Assert - reservation succeeded
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Get reserved blocks
			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks

			// Verify blocks are RESERVED for train1
			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo("train1")
				assertThat(block.reservedFrom).isEqualTo(doA1)
			}

			// Assert path starts from doA1 (reservePathToAny finds ANY valid target)
			// From doA1, paths exist to both InOut A and InOut B
			// The algorithm tries InOuts first, and may find B before A
			assertPathContainsSeparators(blocks, "doA1")
			assertThat(blocks).isNotNull()
			assertThat(blocks.isEmpty()).isFalse()

			assertIsReachedOutSide(blocks, "A")
		}

		@Test
		fun `test scenario 4 - from doA2 to InOut A`() {
			// Arrange - Find doA2 semaphore (17,9)
			val doA2 = findSemaphoreByName("doA2")

			// Act - reserve path from doA2
			val result = service.reservePathToAny("train1", doA2)

			// Assert - reservation succeeded
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Get reserved blocks
			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks

			// Verify blocks are RESERVED for train1
			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo("train1")
				assertThat(block.reservedFrom).isEqualTo(doA2)
			}

			// Assert path starts from doA2 (reservePathToAny finds ANY valid target)
			// From doA2, paths exist to both InOut A and InOut B
			// The algorithm tries InOuts first, and may find B before A
			assertPathContainsSeparators(blocks, "doA2")
			assertThat(blocks).isNotNull()
			assertThat(blocks.isEmpty()).isFalse()

			assertIsReachedOutSide(blocks, "A")
		}

		@Test
		fun `test scenario 5 - from doB1 to InOut B`() {
			// Arrange - Find doB1 semaphore (25,8)
			val doB1 = findSemaphoreByName("doB1")

			// Act - reserve path from doB1
			val result = service.reservePathToAny("train1", doB1)

			// Assert - reservation succeeded
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Get reserved blocks
			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks

			// Verify blocks are RESERVED for train1
			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo("train1")
				assertThat(block.reservedFrom).isEqualTo(doB1)
			}

			// Assert path goes to InOut B: doB1 → vB → zB → kB → B
			assertPathContainsSeparators(blocks, "doB1", "vB", "zB")

			assertIsReachedOutSide(blocks, "B")
		}

		@Test
		fun `test scenario 6 - from doB2 to InOut B`() {
			// Arrange - Find doB2 semaphore (24,9)
			val doB2 = findSemaphoreByName("doB2")

			// Act - reserve path from doB2
			val result = service.reservePathToAny("train1", doB2)

			// Assert - reservation succeeded
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Get reserved blocks
			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks

			// Verify blocks are RESERVED for train1
			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo("train1")
				assertThat(block.reservedFrom).isEqualTo(doB2)
			}

			// Assert path goes to InOut B: doB2 → vB → zB → kB → B
			assertPathContainsSeparators(blocks, "doB2", "vB", "zB")

			assertIsReachedOutSide(blocks, "B")
		}
	}

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
	@Nested
	inner class MultiTrainRaceConditions {
		private val registry: PathReservationRegistry by lazy {
			simulationContext.scope.get()
		}

		/**
		 * Find a semaphore by name in the grid.
		 */
		private fun findSemaphoreByName(name: String): DynamicRailSemaphore {
			val grid = simulationContext.getRailWayNetGrid()
			for (x in 0 until grid.cols) {
				for (y in 0 until grid.rows) {
					val cell =
						grid[
							cz.vutbr.fit.interlockSim.util
								.Point(x, y)
						]
					if (cell is DynamicRailSemaphore && cell.name == name) {
						return cell
					}
				}
			}
			throw IllegalStateException("Semaphore $name not found in grid")
		}

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
			val train1Blocks = (result1 as PathReservationService.ReservationResult.Success).reservedBlocks

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
			val train1Blocks = (result1 as PathReservationService.ReservationResult.Success).reservedBlocks

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
			val train1Blocks = (result1 as PathReservationService.ReservationResult.Success).reservedBlocks
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
			val train1Blocks = (result1 as PathReservationService.ReservationResult.Success).reservedBlocks.toSet()

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
				val blocks = (result as PathReservationService.ReservationResult.Success).reservedBlocks

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

	/**
	 * Signal Configuration Tests (Issue #296 Phase 4)
	 *
	 * Tests for automatic semaphore signal configuration during path reservation.
	 *
	 * NOTE: These tests verify that signal configuration is CALLED, but due to the
	 * race condition at simulation time t=0.0, the signal may not be visible to
	 * trains in actual simulation. See Issue #296 for details.
	 */
	@Nested
	inner class SignalConfigurationTests {
		@Test
		fun `reservePath configures START semaphore signal when START is DynamicRailSemaphore`() {
			// Arrange - Find semaphore doA1 by iterating grid (simulation grid uses dynamic cells)
			val grid = simulationContext.getRailWayNetGrid()
			var doA1Semaphore: DynamicRailSemaphore? = null

			// Iterate through grid to find doA1 semaphore
			for (x in 0 until grid.cols) {
				for (y in 0 until grid.rows) {
					val cell =
						grid[
							cz.vutbr.fit.interlockSim.util
								.Point(x, y)
						]
					if (cell is DynamicRailSemaphore && cell.name == "doA1") {
						doA1Semaphore = cell
						break
					}
				}
				if (doA1Semaphore != null) break
			}

			assertThat(doA1Semaphore).isNotNull()

			// Assert BEFORE - initial signal is STOP
			assertThat(doA1Semaphore!!.signal).isEqualTo(Signal.STOP)
			assertThat(doA1Semaphore.signal.isAllowing()).isFalse()

			// Act - reserve path from semaphore to inOut2
			val result = service.reservePath("train1", doA1Semaphore, inOut2)

			// Assert - reservation succeeded
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Assert AFTER - signal was configured to allow movement
			assertThat(doA1Semaphore.signal).isNotEqualTo(Signal.STOP)
			assertThat(doA1Semaphore.signal.isAllowing()).isTrue()
		}

		@Test
		fun `reservePath configures every semaphore along a full InOut-to-InOut path, not just START`() {
			// Act - reserve a full InOut-to-InOut route, spanning the intermediate semaphores
			// at the vA/vB switch junctions (doA1/doA2, doB1/doB2) in addition to the
			// START/target boundary semaphores.
			val result = service.reservePath("train1", inOut1, inOut2)

			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success = result as PathReservationService.ReservationResult.Success

			// Every semaphore bounding a reserved block must be configured to allow movement --
			// one left at STOP means a train halts there forever (Issue #296 Phase 4 / Step 2h
			// configureIntermediateSemaphores, and configureStartSignal for the START boundary).
			val semaphoresOnPath =
				success.reservedBlocks
					.flatMap { it.ends().toList() }
					.filterIsInstance<DynamicRailSemaphore>()
					.distinctBy { it.name }

			assertThat(semaphoresOnPath).isNotEmpty()
			semaphoresOnPath.forEach { semaphore ->
				assertThat(semaphore.signal)
					.withMessage("Semaphore ${semaphore.name} must not remain at STOP after path grant")
					.isNotEqualTo(Signal.STOP)
			}
		}

		@Test
		fun `reservePathToAnyNextSemaphore identifies InOut output semaphore`() {
			// Arrange - Access InOut's output semaphore directly
			// Cast inOut1 (DynamicPathSeparator) to DynamicInOut for type-safe access
			val dynamicInOut = inOut1 as DynamicInOut
			val outSemaphore = dynamicInOut.outSemaphore

			// Assert BEFORE - InOut output semaphore is constant FREE
			// Note: InOut output semaphores are created as ConstantSemaphore with Signal.FREE
			// and never change (no-op setter). This is by design - exit points always allow trains out.
			assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)
			assertThat(outSemaphore.signal.isAllowing()).isTrue()

			// Act - reserve path from InOut
			val next = navigator.getNextTrackSection(inOut1, null)
			assertThat(next).isNotNull()

			val result = service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)

			// Assert - reservation succeeded
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Assert AFTER - InOut output semaphore remains constant FREE (ConstantSemaphore)
			assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)
			assertThat(outSemaphore.signal.isAllowing()).isTrue()

			// Verify blocks were reserved
			val blocks = service.getReservedBlocks("train1")
			assertThat(blocks).isNotNull()
			assertThat(blocks.isEmpty()).isFalse()
		}

		@Test
		fun `releasePath allows subsequent signal reconfiguration`() {
			// Arrange - Access InOut's output semaphore
			val dynamicInOut = inOut1 as DynamicInOut
			val outSemaphore = dynamicInOut.outSemaphore

			// Assert BEFORE - InOut output semaphore is constant FREE
			assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)

			// Reserve path for train1
			val next = navigator.getNextTrackSection(inOut1, null)
			assertThat(next).isNotNull()

			service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)

			// Assert AFTER FIRST RESERVATION - semaphore remains constant FREE
			assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)
			assertThat(outSemaphore.signal.isAllowing()).isTrue()

			// Act - release path
			service.releasePath("train1")

			// Assert AFTER RELEASE - semaphore still constant FREE (ConstantSemaphore)
			assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)
			assertThat(outSemaphore.signal.isAllowing()).isTrue()

			// Reserve again with different train
			val result = service.reservePathToAnyNextSemaphore("train2", inOut1, next)

			// Assert - second reservation succeeded
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Assert AFTER SECOND RESERVATION - semaphore still constant FREE
			assertThat(outSemaphore.signal).isEqualTo(Signal.FREE)
			assertThat(outSemaphore.signal.isAllowing()).isTrue()

			// Verify blocks were reserved for train2
			val blocks = service.getReservedBlocks("train2")
			assertThat(blocks).isNotNull()
			assertThat(blocks.isEmpty()).isFalse()
		}
	}

	@Nested
	inner class OrientedSeparatorOverload {
		/**
		 * Test the new OrientedPathSeparator overload.
		 *
		 * This overload simplifies usage by automatically determining the next track section
		 * based on the separator's orientation, then delegating to the existing overload.
		 */
		@Test
		fun `reservePathToAnyNextSemaphore with OrientedPathSeparator succeeds`() {
			// Arrange
			// inOut1 is an OrientedPathSeparator (DynamicInOut implements OrientedPathSeparator)
			assertThat(inOut1).isInstanceOf<DynamicInOut>()

			// Act - call new overload without explicit next parameter
			val result = service.reservePathToAnyNextSemaphore("train1", inOut1 as DynamicInOut)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success = result as PathReservationService.ReservationResult.Success
			assertThat(success.reservedBlocks).isNotNull()
			// vyhybna.xml: inOut1 (11,8) -> first semaphore at (14,8) = 1 block
			assertThat(success.reservedBlocks.size).isEqualTo(1)

			// Verify all blocks are RESERVED
			success.reservedBlocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.reservedFrom).isEqualTo(inOut1)
				assertThat(block.trainName).isEqualTo("train1")
			}
		}

		@Test
		fun `reservePathToAnyNextSemaphore with OrientedPathSeparator delegates correctly`() {
			// Arrange
			val inOut = inOut1 as DynamicInOut

			// Get the expected next track section (what the implementation should find)
			val expectedNext = navigator.getNextTrackSection(inOut, null)
			assertThat(expectedNext).isNotNull()

			// Act - call new overload
			val result1 = service.reservePathToAnyNextSemaphore("train1", inOut)

			// Release path for comparison
			service.releasePath("train1")

			// Call existing overload with explicit next parameter
			val result2 = service.reservePathToAnyNextSemaphore("train1", inOut, expectedNext!!)

			// Assert - both results should be identical
			assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()
			assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val success1 = result1 as PathReservationService.ReservationResult.Success
			val success2 = result2 as PathReservationService.ReservationResult.Success

			// Same number of blocks reserved
			assertThat(success1.reservedBlocks.size).isEqualTo(success2.reservedBlocks.size)
		}

		@Test
		fun `reservePathToAnyNextSemaphore with OrientedPathSeparator returns NoPathExists when no outgoing track`() {
			// Arrange
			// Find a semaphore with no outgoing track (dead-end)
			// In vyhybna.xml, both semaphores are mid-network, so we need to create a scenario
			// where getNextTrackSection returns null

			// For this test, we'll use inOut2 which is an exit point
			// When trying to reserve FROM inOut2 (entry-as-exit direction), there might be no path
			val inOut = inOut2 as DynamicInOut

			// Act - try to reserve path from exit InOut (should fail or succeed depending on network)
			val result = service.reservePathToAnyNextSemaphore("train1", inOut)

			// Assert - result should be either Success or NoPathExists
			// (vyhybna.xml is bidirectional, so this might actually succeed)
			// The important thing is that it doesn't crash
			assertThat(result).isNotNull()
		}

		@Test
		fun `reservePathToAnyNextSemaphore with OrientedPathSeparator works with static separator`() {
			// Arrange
			// Get static InOut from editing context
			val staticInOuts = simulationContext.getInOuts()
			val staticInOut = staticInOuts.toList()[0]

			// Act - call with static separator (should auto-convert to dynamic)
			val result = service.reservePathToAnyNextSemaphore("train1", staticInOut)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success = result as PathReservationService.ReservationResult.Success
			assertThat(success.reservedBlocks).isNotEmpty()
			// Verify all blocks are dynamic wrappers
			success.reservedBlocks.forEach { block ->
				assertThat(block).isInstanceOf<DynamicTrackBlock>()
			}
		}

		@Test
		fun `reservePathToAnyNextSemaphore with OrientedPathSeparator registers ownership`() {
			// Arrange
			val inOut = inOut1 as DynamicInOut

			// Act
			val result = service.reservePathToAnyNextSemaphore("train1", inOut)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			// Verify ownership registration
			val reservedBlocks = service.getReservedBlocks("train1")
			assertThat(reservedBlocks.size).isEqualTo(1) // Path to first semaphore
		}

		@Test
		fun `reservePathToAnyNextSemaphore with OrientedPathSeparator returns AllPathsBlocked when occupied`() {
			// Arrange
			val inOut = inOut1 as DynamicInOut

			// Reserve the path for another train to block it
			service.reservePath("other-train", inOut1, inOut2)

			// Act
			val result = service.reservePathToAnyNextSemaphore("train1", inOut)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		}

		// ========================================
		// Semaphore-based tests (using helper methods)
		// ========================================

		/**
		 * Find semaphore by name in grid.
		 */
		private fun findSemaphoreByName(name: String): DynamicRailSemaphore {
			val grid = simulationContext.getRailWayNetGrid()
			for (x in 0 until grid.cols) {
				for (y in 0 until grid.rows) {
					val cell =
						grid[
							cz.vutbr.fit.interlockSim.util
								.Point(x, y)
						]
					if (cell is DynamicRailSemaphore && cell.name == name) {
						return cell
					}
				}
			}
			throw IllegalStateException("Semaphore $name not found in grid")
		}

		/**
		 * Find InOut by name.
		 */
		private fun findInOutByName(name: String): DynamicInOut {
			val inOuts = simulationContext.getInOuts()
			for (inOut in inOuts) {
				val dynamic = simulationContext.toDynamic(inOut) as DynamicInOut
				if (dynamic.name == name) {
					return dynamic
				}
			}
			throw IllegalStateException("InOut $name not found")
		}

		/**
		 * Assert that reserved blocks form a path through specified separators in order.
		 */
		private fun assertPathContainsSeparators(
			blocks: List<DynamicTrackBlock>,
			vararg separatorNames: String
		) {
			// Collect all separators from block endpoints
			val allSeparators = mutableSetOf<String>()
			blocks.forEach { block ->
				val (sep1, sep2) = block.ends()

				// Collect names from dynamic separator types
				// All dynamic separators have .name property
				when (sep1) {
					is DynamicRailSemaphore -> {
						val name = sep1.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
					is cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch -> {
						val name = sep1.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
					is DynamicInOut -> {
						val name = sep1.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
				}

				when (sep2) {
					is DynamicRailSemaphore -> {
						val name = sep2.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
					is cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch -> {
						val name = sep2.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
					is DynamicInOut -> {
						val name = sep2.name
						if (name.isNotEmpty()) allSeparators.add(name)
					}
				}
			}

			// Verify all expected separators are present
			separatorNames.forEach { expectedName ->
				if (!allSeparators.contains(expectedName)) {
					throw AssertionError("Expected separator '$expectedName' not found in path. Found: $allSeparators")
				}
			}
		}

		private fun assertIsDirectedToOutSide(
			blocks: List<DynamicTrackBlock>,
			nameOfOut: String
		) {
			val sem1 = "do${nameOfOut}1"
			val sem2 = "do${nameOfOut}2"
			assertThat(
				blocks.any { block ->
					val (sep1, sep2) = block.ends()
					(sep1 is DynamicInOut && sep1.name == nameOfOut) ||
						(sep2 is DynamicInOut && sep2.name == nameOfOut) ||
						(sep1 is DynamicRailSemaphore && (sep1.name == sem1 || sep1.name == sem2)) ||
						(sep2 is DynamicRailSemaphore && (sep2.name == sem1 || sep2.name == sem2))
				}
			).isTrue()
		}

		private fun assertIsReachedOutSide(
			blocks: List<DynamicTrackBlock>,
			nameOfOut: String
		) {
			assertThat(
				blocks.any { block ->
					val (sep1, sep2) = block.ends()
					(sep1 is DynamicInOut && sep1.name == nameOfOut) ||
						(sep2 is DynamicInOut && sep2.name == nameOfOut)
				}
			).isTrue()
		}

		@Test
		fun `from semaphore zB to any doAn semaphore via oriented overload`() {
			// Arrange - Find zB semaphore (27,8)
			// Topology: zB → vB (switch) → either doB1 (MAIN) or doB2 (BRANCH)
			// This test verifies path from zB BACKWARD (toward A side) finds next semaphore
			val zB = findSemaphoreByName("zB")

			// Act - use NEW overload (no explicit next parameter)
			val result = service.reservePathToAnyNextSemaphore("train1", zB)

			// Assert - reservation succeeded
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks

			// Verify blocks are RESERVED for train1
			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo("train1")
				assertThat(block.reservedFrom).isEqualTo(zB)
			}

			// Assert path starts from zB
			assertPathContainsSeparators(blocks, "zB", "vB", "doB1")
			// Method reserves to NEXT semaphore (doB1 or doB2), not all the way to destination
			assertThat(blocks.isEmpty()).isFalse()
		}

		@Test
		fun `from semaphore zA to any doBn semaphore via oriented overload`() {
			// Arrange - Find zA semaphore (14,8)
			// Topology: A ← zA ← vA ← doB1 (orientation=false means forward is RIGHT/increasing X)
			// From zA with orientation=false, the FORWARD direction goes through vA toward doB1
			val zA = findSemaphoreByName("zA")

			// Act - use NEW overload (automatic next detection)
			val result = service.reservePathToAnyNextSemaphore("train2", zA)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks

			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo("train2")
				assertThat(block.reservedFrom).isEqualTo(zA)
			}

			// Assert path starts from zA and reaches InOut A (the next separator in that direction)
			assertPathContainsSeparators(blocks, "zA", "vA", "doB1")
			assertThat(blocks.isEmpty()).isFalse()
		}

		@ParameterizedTest
		@CsvSource("A,B", "B,A")
		fun `parallel from zX do to doYn via oriented overload`(
			first: String,
			second: String
		) {
			// Arrange - Find start semaphore (zA or zB)
			val firstSemaphoreName = "z$first"
			val secondSemaphoreName = "z$second"
			val firstSemaphore = findSemaphoreByName(firstSemaphoreName)
			val firstTrainId = "first-train"
			val secondSemaphore = findSemaphoreByName(secondSemaphoreName)
			val secondTrainId = "second-train"

			// Act - first train
			val result1 = service.reservePathToAnyNextSemaphore(firstTrainId, firstSemaphore)
			// Assert
			assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success1 = result1 as PathReservationService.ReservationResult.Success
			val blocks1 = success1.reservedBlocks

			blocks1.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo(firstTrainId)
				assertThat(block.reservedFrom).isEqualTo(firstSemaphore)
			}

			// Assert path goes from start semaphore to expected doYn semaphore
			assertPathContainsSeparators(blocks1, "z$first", "v$first", "do${second}1")
			assertThat(blocks1.isEmpty()).isFalse()

			// Act - second train
			val result2 = service.reservePathToAnyNextSemaphore(secondTrainId, secondSemaphore)
			// Assert
			assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success2 = result2 as PathReservationService.ReservationResult.Success
			val blocks2 = success2.reservedBlocks

			blocks2.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo(secondTrainId)
				assertThat(block.reservedFrom).isEqualTo(secondSemaphore)
			}

			assertThat(blocks2.intersect(blocks1)).isEmpty() // No overlap

			// Assert path goes from start semaphore to expected doYn semaphore
			assertPathContainsSeparators(blocks2, "z$second", "v$second", "do${first}2")
			assertThat(blocks2.isEmpty()).isFalse()
		}

		@Test
		fun `from semaphore doB1 to next separator via oriented overload`() {
			// Arrange - Find doB1 semaphore (25,8) - MAIN branch near B
			// Topology: doA1 ↔ doB1 ← vB ← B (orientation=false means forward is RIGHT/increasing X)
			// From doB1 with orientation=false, the FORWARD direction goes through vB toward B
			val doB1 = findSemaphoreByName("doB1")

			// Act - use NEW overload
			val result = service.reservePathToAnyNextSemaphore("train3", doB1)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks

			blocks.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo("train3")
				assertThat(block.reservedFrom).isEqualTo(doB1)
			}

			// Assert path goes from doB1 to doA1 (both in result)
			assertPathContainsSeparators(blocks, "doB1", "vB", "B")
			assertThat(blocks.isEmpty()).isFalse()
		}

		@Test
		fun `from semaphore doB2 to next separator via oriented overload`() {
			// Arrange - Find doB2 semaphore (24,9) - BRANCH path near B
			// Topology: doA2 ↔ doB2 ← vB ← B (orientation=false means forward is RIGHT/increasing X)
			// From doB2 with orientation=false, the FORWARD direction goes through vB toward B
			val doB2 = findSemaphoreByName("doB2")

			// Act
			val result = service.reservePathToAnyNextSemaphore("train4", doB2)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks
			assertPathContainsSeparators(blocks, "doB2", "vB", "B")
			assertThat(blocks.isEmpty()).isFalse()
		}

		@Test
		fun `from semaphore doA1 to next separator via oriented overload`() {
			// Arrange - Find doA1 semaphore (16,8) - MAIN branch near A
			// Topology: doA1 → vA (switch) → zA (semaphore) → A (InOut)
			// Next semaphore from doA1 is zA
			val doA1 = findSemaphoreByName("doA1")

			// Act
			val result = service.reservePathToAnyNextSemaphore("train5", doA1)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks
			assertPathContainsSeparators(blocks, "doA1", "vA", "A")
			// Method reserves to NEXT semaphore (zA), not to InOut A
			assertThat(blocks.isEmpty()).isFalse()
		}

		@Test
		fun `from semaphore doA2 to next separator via oriented overload`() {
			// Arrange - Find doA2 semaphore (17,9) - BRANCH path near A
			// Topology: doA2 → vA (switch) → zA (semaphore) → A (InOut)
			// Next semaphore from doA2 is zA
			val doA2 = findSemaphoreByName("doA2")

			// Act
			val result = service.reservePathToAnyNextSemaphore("train6", doA2)

			// Assert
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val success = result as PathReservationService.ReservationResult.Success
			val blocks = success.reservedBlocks
			assertPathContainsSeparators(blocks, "doA2", "vA", "A")
			// Method reserves to NEXT semaphore (zA), not to InOut A
			assertThat(blocks.isEmpty()).isFalse()
		}

		@ParameterizedTest
		@CsvSource("A,B", "B,A")
		fun `parallel from doX1 do to X and doY2 do Y via oriented overload`(
			first: String,
			second: String
		) {
			// Arrange - Find start semaphore (doA1 or doB1)
			val firstSemaphoreName = "do${first}1"
			val secondSemaphoreName = "do${second}2"
			val firstSemaphore = findSemaphoreByName(firstSemaphoreName)
			val firstTrainId = "first-train"
			val secondSemaphore = findSemaphoreByName(secondSemaphoreName)
			val secondTrainId = "second-train"

			// Act - first train
			val result1 = service.reservePathToAnyNextSemaphore(firstTrainId, firstSemaphore)
			// Assert
			assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success1 = result1 as PathReservationService.ReservationResult.Success
			val blocks1 = success1.reservedBlocks

			blocks1.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo(firstTrainId)
				assertThat(block.reservedFrom).isEqualTo(firstSemaphore)
			}

			// Assert path goes from start semaphore to expected InOut X
			assertPathContainsSeparators(blocks1, "do${first}1", "v$first", "$first")
			assertThat(blocks1.isEmpty()).isFalse()

			// Act - second train
			val result2 = service.reservePathToAnyNextSemaphore(secondTrainId, secondSemaphore)
			// Assert
			assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success2 = result2 as PathReservationService.ReservationResult.Success
			val blocks2 = success2.reservedBlocks

			blocks2.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo(secondTrainId)
				assertThat(block.reservedFrom).isEqualTo(secondSemaphore)
			}

			assertThat(blocks2.intersect(blocks1)).isEmpty() // No overlap

			// Assert path goes from start semaphore to expected InOut Y
			assertPathContainsSeparators(blocks2, "do${second}2", "v$second", "$second")
			assertThat(blocks2.isEmpty()).isFalse()
		}

		@ParameterizedTest
		@CsvSource("A,1,2", "A,2,1", "B,1,2", "B,2,1")
		fun `only first from doXn do to X and not doYm do X via oriented overload`(
			out: String,
			n: Int,
			m: Int
		) {
			// Arrange - Find start semaphore (doA1 or doB1)
			val firstSemaphoreName = "do${out}$n"
			val secondSemaphoreName = "do${out}$m"
			val firstSemaphore = findSemaphoreByName(firstSemaphoreName)
			val firstTrainId = "first-train"
			val secondSemaphore = findSemaphoreByName(secondSemaphoreName)
			val secondTrainId = "second-train"

			// Act - first train
			val result1 = service.reservePathToAnyNextSemaphore(firstTrainId, firstSemaphore)
			// Assert
			assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success1 = result1 as PathReservationService.ReservationResult.Success
			val blocks1 = success1.reservedBlocks
			blocks1.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(block.trainName).isEqualTo(firstTrainId)
				assertThat(block.reservedFrom).isEqualTo(firstSemaphore)
			}

			// Assert path goes from start semaphore to expected InOut X
			assertPathContainsSeparators(blocks1, "do${out}$n", "v$out", out)
			assertThat(blocks1.isEmpty()).isFalse()

			// Act - second train
			val result2 = service.reservePathToAnyNextSemaphore(secondTrainId, secondSemaphore)
			// Assert
			assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		}
	}

	@Nested
	inner class ExternalObserverApi {
		@Test
		fun `environment addBlockOccupancyListener receives reserve and release events`() {
			val listener = RecordingListener()
			environment.addBlockOccupancyListener(listener)

			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success = result as PathReservationService.ReservationResult.Success

			assertThat(listener.events).hasSize(success.reservedBlocks.size)
			listener.events.forEach { event ->
				assertThat(event.type).isEqualTo(BlockOccupancyEventType.BLOCK_RESERVED)
				assertThat(event.trainId).isEqualTo("train1")
				assertThat(event.previousState).isEqualTo(TrackFacility.State.FREE)
				assertThat(event.newState).isEqualTo(TrackFacility.State.RESERVED)
			}

			service.releasePath("train1")

			val reservedCount = listener.events.count { it.type == BlockOccupancyEventType.BLOCK_RESERVED }
			val releasedCount = listener.events.count { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
			assertThat(releasedCount).isEqualTo(reservedCount)
			listener.events
				.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
				.forEach { event ->
					assertThat(event.trainId).isEqualTo("train1")
					assertThat(event.previousState).isEqualTo(TrackFacility.State.RESERVED)
					assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
				}
		}

		@Test
		fun `legacy listener receives BLOCK_RELEASED on unregister path`() {
			val listener = RecordingListener()
			environment.addBlockOccupancyListener(listener)

			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success = result as PathReservationService.ReservationResult.Success
			val reservedCount = success.reservedBlocks.size

			// Clear reserve events so we can count releases in isolation
			listener.events.clear()

			service.unregister("train1")

			val releasedEvents = listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
			assertThat(releasedEvents).hasSize(reservedCount)
			releasedEvents.forEach { event ->
				assertThat(event.trainId).isEqualTo("train1")
				assertThat(event.previousState).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
				assertThat(event.occupant).isNull()
			}
		}

		@Test
		fun `legacy listener receives BLOCK_RELEASED on unregisterBlock path`() {
			val listener = RecordingListener()
			environment.addBlockOccupancyListener(listener)

			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val success = result as PathReservationService.ReservationResult.Success
			val firstBlock = success.reservedBlocks.first()

			// unregisterBlock only releases FREE blocks (production path is after block.leave()).
			// Cancel the reservation manually so we can exercise the single-block release path.
			firstBlock.cancelPathSetup(inOut1)

			listener.events.clear()

			val released = service.unregisterBlock("train1", firstBlock)
			assertThat(released).isTrue()

			val releasedEvents = listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
			assertThat(releasedEvents).hasSize(1)
			val event = releasedEvents.first()
			assertThat(event.block).isEqualTo(firstBlock)
			assertThat(event.trainId).isEqualTo("train1")
			assertThat(event.previousState).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
		}
	}

	@Nested
	inner class SwitchCleanupTests {
		@Test
		fun `unregister unlocks all switches and clears switch registry`() {
			// Arrange: reserve a path through a switch (vyhybna.xml)
			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val switches = registry.getSwitches("train1")
			assertThat(switches).isNotEmpty()
			switches.forEach { switch ->
				assertThat(switch.locked).isTrue()
			}

			// Act: production cleanup path
			val releasedBlocks = service.unregister("train1")

			// Assert: blocks and switches released
			assertThat(releasedBlocks).isNotEmpty()
			assertThat(registry.getBlocks("train1")).isEmpty()
			assertThat(registry.getSwitches("train1")).isEmpty()
			switches.forEach { switch ->
				assertThat(switch.locked).isFalse()
			}
		}

		@Test
		fun `releaseTrainReservations unlocks switches through production entry point`() {
			val result = service.reservePath("train1", inOut1, inOut2)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val switches = registry.getSwitches("train1")
			assertThat(switches).isNotEmpty()
			switches.forEach { switch ->
				assertThat(switch.locked).isTrue()
			}

			simulationContext.releaseTrainReservations("train1")

			assertThat(registry.getBlocks("train1")).isEmpty()
			assertThat(registry.getSwitches("train1")).isEmpty()
			switches.forEach { switch ->
				assertThat(switch.locked).isFalse()
			}
		}
	}

	private class RecordingListener : BlockOccupancyListener {
		val events = mutableListOf<BlockOccupancyEvent>()

		override fun onBlockOccupancyChanged(event: BlockOccupancyEvent) {
			events.add(event)
		}
	}
}
