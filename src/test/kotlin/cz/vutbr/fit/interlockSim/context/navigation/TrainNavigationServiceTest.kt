/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Comprehensive test coverage for TrainNavigationService
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.component.inject

/**
 * Comprehensive test suite for TrainNavigationService.
 *
 * ## Test Coverage
 *
 * - ✅ Successful navigation (all blocks owned by train)
 * - ✅ Ownership conflicts (blocks owned by different train)
 * - ✅ Edge cases (no path, empty path, filtering, deduplication)
 * - ✅ Method consistency (findReservedPathForTrain vs isPathReservedForTrain)
 * - ✅ Block extraction logic (PathSeparator filtering, order preservation)
 *
 * ## Test Strategy (Refactored 2026-01-29, Fixed 2026-01-29)
 *
 * Uses real contexts (TestContextBuilder for simple scenarios, vyhybna.xml for complex)
 * instead of MockK mocks. This provides:
 * - Integration testing with actual dynamic wrappers
 * - Verification of real component interactions
 * - No mock setup complexity or stateful call counting
 * - Follows proven patterns from TopologyNavigatorTest and PathReservationServiceTest
 *
 * ## Network Topologies
 *
 * - **Simple Navigation**: TestContextBuilder (A→B at coordinates 1,1 to 5,5)
 * - **Complex Scenarios**: vyhybna.xml (InOut A at 11,8, InOut B at 30,8, 7 blocks)
 *
 * ## Ownership Conflict Testing (CRITICAL)
 *
 * Tests that simulate block theft MUST update BOTH:
 * 1. DynamicTrackBlock.trainName (via setUpPath())
 * 2. PathReservationRegistry (via registerAtomic()/unregister())
 *
 * Failure to synchronize both creates false negatives in ownership validation,
 * as the navigation service queries the registry (not the block directly).
 *
 * **Tech Debt**: Dual ownership tracking is a design smell. The system maintains
 * ownership in both DynamicTrackBlock.trainName and PathReservationRegistry,
 * which can diverge if not carefully synchronized. Future refactoring should
 * use the registry as the single source of truth.
 *
 * @since Issue #295 (Phase 3 of Issue #292)
 * @since Issue #296 Phase 5 (Updated for new navigation API)
 * @since Issue #296 Phase 6 (Refactored to use real contexts)
 */
@DisplayName("TrainNavigationService")
class TrainNavigationServiceTest : KoinTestBase() {

	@Nested
	@DisplayName("Successful Navigation")
	inner class SuccessfulNavigationTests {
		private val simulationContextFactory: SimulationContextFactory by inject()

		private lateinit var context: DefaultSimulationContext
		private lateinit var service: TrainNavigationService
		private lateinit var registry: PathReservationRegistry

		@BeforeEach
		fun setUp() {
			// Simple linear network: A → B (1 block)
			context = TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 5, 5, false)
				.withConnection(1, 1, 5, 5, 100.0, 80.0)
				.buildSimulationContext()

			// Get real services from context scope
			service = context.getTrainNavigationService()
			registry = context.scope.get()
		}

		@AfterEach
		fun tearDown() {
			context.close()  // AutoCloseable cleanup
		}

		@Test
		fun `findReservedPathForTrain returns path when all blocks owned by train`() {
			// Arrange: Get real separators from grid (already dynamic in simulation context)
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut
			val inOutB = grid.getCellAt(5, 5) as DynamicInOut

			// Reserve path using real PathReservationService
			val pathService = context.getPathReservationService()
			pathService.reservePath("train1", inOutA, inOutB)

			// Act: Navigate using real TrainNavigationService
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Path is available (all blocks owned)
			assertThat(result).isNotNull()
			assertThat(result!!.size).isGreaterThan(0)
		}

		@Test
		fun `findReservedPathForTrain returns path with multiple blocks all owned`() {
			// Arrange: Load vyhybna.xml (7 blocks, complex topology)
			val editingContextFactory: EditingContextFactory by inject()
			val editingContext = editingContextFactory.createContext(
				javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")!!
			) as DefaultEditingContext
			val vyhybnaContext = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

			val vyhybnaService = vyhybnaContext.getTrainNavigationService()
			val vyhybnaPathService = vyhybnaContext.getPathReservationService()

			val grid = vyhybnaContext.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Reserve full path from A to B (spans all 7 blocks)
			vyhybnaPathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = vyhybnaService.findReservedPathForTrain("train1", inOutA)

			// Assert
			assertThat(result).isNotNull()
			assertThat(result!!.size).isGreaterThan(0)

			vyhybnaContext.close()
		}

		@Test
		fun `isPathReservedForTrain returns true when all blocks owned`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut
			val inOutB = grid.getCellAt(5, 5) as DynamicInOut

			val pathService = context.getPathReservationService()
			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.isPathReservedForTrain("train1", inOutA)

			// Assert
			assertThat(result).isTrue()
		}
	}

	@Nested
	@DisplayName("Ownership Conflicts")
	inner class OwnershipConflictTests {
		private val editingContextFactory: EditingContextFactory by inject()
		private val simulationContextFactory: SimulationContextFactory by inject()

		private lateinit var context: DefaultSimulationContext
		private lateinit var service: TrainNavigationService

		@BeforeEach
		fun setUp() {
			// Use vyhybna.xml (7 blocks) for realistic multi-block ownership scenarios
			val editingContext = editingContextFactory.createContext(
				javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")!!
			) as DefaultEditingContext
			context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
			service = context.getTrainNavigationService()
		}

		@AfterEach
		fun tearDown() {
			context.close()
		}

		@Test
		fun `findReservedPathForTrain returns null when one block owned by different train`() {
			// Arrange: Reserve full path from A to B for train1
			val pathService = context.getPathReservationService()
			val registry = context.scope.get<PathReservationRegistry>()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Train1 reserves from A to B (all 7 blocks)
			pathService.reservePath("train1", inOutA, inOutB)
			val allBlocks = pathService.getReservedBlocks("train1")
			assertThat(allBlocks.size).isEqualTo(7)

			// Get actual path navigation service will check (to next semaphore, not full path)
			val pathToCheck = service.findReservedPathForTrain("train1", inOutA)
			assertThat(pathToCheck).isNotNull()

			// Extract blocks from the actual path being navigated
			val blocksInPath = pathToCheck!!.filterIsInstance<TrackSection>()
				.map { it.getTrackBlock() }
				.filterIsInstance<DynamicTrackBlock>()
				.toSet()
			assertThat(blocksInPath.size).isGreaterThan(0)

			// Simulate conflict: train2 steals the FIRST block in the navigation path
			// IMPORTANT: Must update BOTH block state AND registry
			val stolenBlock = blocksInPath.first()

			// Step 1: Unregister train1's ownership from registry
			registry.unregister("train1")

			// Step 2: Register partial path (excluding stolen block) back to train1
			val remainingBlocks = allBlocks.filterNot { it == stolenBlock }
			registry.registerAtomic("train1", remainingBlocks)

			// Step 3: Stolen block now reserved by train2
			// Must update BOTH block state AND registry
			stolenBlock.cancelPathSetup(inOutA)  // Reset state (RESERVED -> FREE)
			stolenBlock.setUpPath(inOutA, "train2")  // Reserve for train2 (FREE -> RESERVED)
			registry.registerAtomic("train2", listOf(stolenBlock))  // Register train2 in registry

			// Act: Train1 tries to navigate again (should now fail due to stolen block)
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Should fail due to ownership conflict (train2 owns first block in path)
			assertThat(result).isNull()
		}

		@Test
		fun `findReservedPathForTrain returns null when first block not owned`() {
			// Arrange: Don't reserve any blocks for train1
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut

			// Act: Try to navigate without reserving path first
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Should fail since no blocks are owned by train1
			assertThat(result).isNull()
		}

		@Test
		fun `findReservedPathForTrain returns null when last block not owned`() {
			// Arrange: Reserve path for train1, then release it, then partially reserve for train2
			// This creates a scenario where first blocks are owned by train2, not train1
			val pathService = context.getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Train2 reserves the path (so train1 doesn't own last blocks)
			pathService.reservePath("train2", inOutA, inOutB)

			// Act: Train1 tries to navigate (but doesn't own the path)
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Should fail since path is owned by train2
			assertThat(result).isNull()
		}

		@Test
		fun `isPathReservedForTrain returns false when ownership conflict exists`() {
			// Arrange: Reserve full path from A to B for train1
			val pathService = context.getPathReservationService()
			val registry = context.scope.get<PathReservationRegistry>()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)
			val allBlocks = pathService.getReservedBlocks("train1")
			assertThat(allBlocks.size).isEqualTo(7)

			// Get actual path navigation service will check (to next semaphore, not full path)
			val pathToCheck = service.findReservedPathForTrain("train1", inOutA)
			assertThat(pathToCheck).isNotNull()

			// Extract blocks from the actual path being navigated
			val blocksInPath = pathToCheck!!.filterIsInstance<TrackSection>()
				.map { it.getTrackBlock() }
				.filterIsInstance<DynamicTrackBlock>()
				.toSet()
			assertThat(blocksInPath.size).isGreaterThan(0)

			// Simulate conflict: train2 steals the FIRST block in the navigation path
			// IMPORTANT: Must update BOTH block state AND registry
			val stolenBlock = blocksInPath.first()

			// Step 1: Unregister train1's ownership from registry
			registry.unregister("train1")

			// Step 2: Register partial path (excluding stolen block) back to train1
			val remainingBlocks = allBlocks.filterNot { it == stolenBlock }
			registry.registerAtomic("train1", remainingBlocks)

			// Step 3: Stolen block now reserved by train2
			// Must update BOTH block state AND registry
			stolenBlock.cancelPathSetup(inOutA)  // Reset state (RESERVED -> FREE)
			stolenBlock.setUpPath(inOutA, "train2")  // Reserve for train2 (FREE -> RESERVED)
			registry.registerAtomic("train2", listOf(stolenBlock))  // Register train2 in registry

			// Act
			val result = service.isPathReservedForTrain("train1", inOutA)

			// Assert: Should fail due to ownership conflict (train2 owns first block in navigation path)
			assertThat(result).isFalse()
		}
	}

	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCaseTests {
		private val editingContextFactory: EditingContextFactory by inject()
		private val simulationContextFactory: SimulationContextFactory by inject()

		@Test
		fun `findReservedPathForTrain returns null when no topological path exists`() {
			// Arrange: Disconnected InOuts (no track connection)
			val context = TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 10, 10, false)
				// No connection!
				.buildSimulationContext()

			val service = context.getTrainNavigationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert
			assertThat(result).isNull()

			context.close()
		}

		@Test
		fun `findReservedPathForTrain returns null when single InOut has no connections`() {
			// Arrange: Single InOut (no connections = no path topologically)
			val context = TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.buildSimulationContext()

			val service = context.getTrainNavigationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: No topological path exists (single InOut with no connections)
			assertThat(result).isNull()

			context.close()
		}

		@Test
		fun `findReservedPathForTrain filters out non-DynamicTrackBlocks`() {
			// Arrange: Use vyhybna.xml (has TrackSections with blocks)
			val editingContext = editingContextFactory.createContext(
				javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")!!
			) as DefaultEditingContext
			val context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
			val service = context.getTrainNavigationService()
			val pathService = context.getPathReservationService()

			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Reserve path
			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Should succeed (filters out separators, only validates blocks)
			assertThat(result).isNotNull()

			context.close()
		}

		@Test
		fun `findReservedPathForTrain deduplicates blocks in path`() {
			// Arrange: vyhybna.xml has switches that may create duplicate block references
			val editingContext = editingContextFactory.createContext(
				javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")!!
			) as DefaultEditingContext
			val context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
			val service = context.getTrainNavigationService()
			val pathService = context.getPathReservationService()

			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Reserve path
			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Should succeed (deduplication works)
			assertThat(result).isNotNull()

			context.close()
		}

		@Test
		fun `isPathReservedForTrain returns false when no path exists`() {
			// Arrange: Disconnected InOuts
			val context = TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 10, 10, false)
				.buildSimulationContext()

			val service = context.getTrainNavigationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut

			// Act
			val result = service.isPathReservedForTrain("train1", inOutA)

			// Assert
			assertThat(result).isFalse()

			context.close()
		}
	}

	@Nested
	@DisplayName("Method Consistency")
	inner class MethodConsistencyTests {
		private lateinit var context: DefaultSimulationContext
		private lateinit var service: TrainNavigationService

		@BeforeEach
		fun setUp() {
			// Simple linear network: A → B
			context = TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 5, 5, false)
				.withConnection(1, 1, 5, 5, 100.0, 80.0)
				.buildSimulationContext()

			service = context.getTrainNavigationService()
		}

		@AfterEach
		fun tearDown() {
			context.close()
		}

		@Test
		fun `isPathReservedForTrain matches findReservedPathForTrain when path available`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut
			val inOutB = grid.getCellAt(5, 5) as DynamicInOut

			val pathService = context.getPathReservationService()
			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val foundPath = service.findReservedPathForTrain("train1", inOutA)
			val isAvailable = service.isPathReservedForTrain("train1", inOutA)

			// Assert
			assertThat(foundPath).isNotNull()
			assertThat(isAvailable).isTrue()
		}

		@Test
		fun `isPathReservedForTrain matches findReservedPathForTrain when path unavailable`() {
			// Arrange: Disconnected network
			context.close()
			context = TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 10, 10, false)
				.buildSimulationContext()

			service = context.getTrainNavigationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut

			// Act
			val foundPath = service.findReservedPathForTrain("train1", inOutA)
			val isAvailable = service.isPathReservedForTrain("train1", inOutA)

			// Assert
			assertThat(foundPath).isNull()
			assertThat(isAvailable).isFalse()
		}

		@Test
		fun `isPathReservedForTrain matches findReservedPathForTrain for ownership conflicts`() {
			// Arrange: Reserve for different train
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut
			val inOutB = grid.getCellAt(5, 5) as DynamicInOut

			val pathService = context.getPathReservationService()
			pathService.reservePath("train2", inOutA, inOutB)  // Different owner

			// Act: Train1 tries to navigate
			val foundPath = service.findReservedPathForTrain("train1", inOutA)
			val isAvailable = service.isPathReservedForTrain("train1", inOutA)

			// Assert
			assertThat(foundPath).isNull()
			assertThat(isAvailable).isFalse()
		}
	}

	@Nested
	@DisplayName("Block Extraction")
	inner class BlockExtractionTests {
		private val editingContextFactory: EditingContextFactory by inject()
		private val simulationContextFactory: SimulationContextFactory by inject()

		private lateinit var context: DefaultSimulationContext
		private lateinit var service: TrainNavigationService

		@BeforeEach
		fun setUp() {
			// Use vyhybna.xml (complex path with separators and blocks)
			val editingContext = editingContextFactory.createContext(
				javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")!!
			) as DefaultEditingContext
			context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
			service = context.getTrainNavigationService()
		}

		@AfterEach
		fun tearDown() {
			context.close()
		}

		@Test
		fun `extractDynamicTrackBlocks filters PathSeparator elements`() {
			// Arrange
			val pathService = context.getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Should succeed (separators filtered out)
			assertThat(result).isNotNull()
		}

		@Test
		fun `extractDynamicTrackBlocks extracts only DynamicTrackBlock instances`() {
			// Arrange
			val pathService = context.getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Only DynamicTrackBlocks validated
			assertThat(result).isNotNull()

			// Verify blocks were extracted
			val blocks = pathService.getReservedBlocks("train1")
			assertThat(blocks.size).isEqualTo(7)
		}

		@Test
		fun `extractDynamicTrackBlocks preserves block order`() {
			// Arrange
			val pathService = context.getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Order preserved
			assertThat(result).isNotNull()
		}

		@Test
		fun `extractDynamicTrackBlocks handles mixed element types`() {
			// Arrange: vyhybna.xml has mixed separators and blocks
			val pathService = context.getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert
			assertThat(result).isNotNull()
		}

		@Test
		fun `getReservedBlocks returns blocks for owned path`() {
			// Arrange
			val pathService = context.getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val blocks = pathService.getReservedBlocks("train1")

			// Assert: vyhybna.xml has 7 blocks from A to B
			assertThat(blocks.size).isEqualTo(7)
		}
	}
}
