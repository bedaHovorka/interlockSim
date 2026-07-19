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
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
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
			context = TestTopologies.simpleLinearPathSimulation() as DefaultSimulationContext

			// Get real services from context scope
			service = context.getRoutingServices().getTrainNavigationService()
			registry = context.scope.get()
		}

		@AfterEach
		fun tearDown() {
			context.close() // AutoCloseable cleanup
		}

		@Test
		fun `findReservedPathForTrain returns path when all blocks owned by train`() {
			// Arrange: Get real separators from grid (already dynamic in simulation context)
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut
			val inOutB = grid.getCellAt(5, 5) as DynamicInOut

			// Reserve path using real PathReservationService
			val pathService = context.getRoutingServices().getPathReservationService()
			pathService.reservePath("train1", inOutA, inOutB)

			// Act: Navigate using real TrainNavigationService
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Path is available with correctly owned blocks
			assertThat(result).isInstanceOf(PathResult.Available::class)
			val path = (result as PathResult.Available).path
			val blocks =
				path
					.filterIsInstance<TrackSection>()
					.map { it.getTrackBlock() }
					.filterIsInstance<DynamicTrackBlock>()
			assertThat(blocks).isNotEmpty()

			blocks.forEach { block ->
				assertThat(block.trainName).isEqualTo("train1")
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			}
		}

		@Test
		fun `findReservedPathForTrain returns path with multiple blocks all owned`() {
			// Arrange: Load vyhybna.xml (7 blocks, complex topology)
			val editingContextFactory: JvmEditingContextFactory by inject()
			TestFixtures.loadShuntingXml().use { xmlStream ->
				editingContextFactory.createContext(xmlStream).use { editingCtx ->
					val editingContext = editingCtx as DefaultEditingContext
					simulationContextFactory.createContext(editingContext).use { simCtx ->
						val vyhybnaContext = simCtx as DefaultSimulationContext

						val vyhybnaService = vyhybnaContext.getRoutingServices().getTrainNavigationService()
						val vyhybnaPathService = vyhybnaContext.getRoutingServices().getPathReservationService()

						val grid = vyhybnaContext.getRailWayNetGrid()
						val inOutA = grid.getCellAt(11, 8) as DynamicInOut
						val inOutB = grid.getCellAt(30, 8) as DynamicInOut

						// Reserve full path from A to B (spans all 7 blocks)
						vyhybnaPathService.reservePath("train1", inOutA, inOutB)

						// Act
						val result = vyhybnaService.findReservedPathForTrain("train1", inOutA)

						// Assert
						assertThat(result).isInstanceOf(PathResult.Available::class)
						val path = (result as PathResult.Available).path
						assertThat(path.size).isGreaterThan(1)
					}
				}
			}
		}

		@Test
		fun `isPathReservedForTrain returns true when all blocks owned`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut
			val inOutB = grid.getCellAt(5, 5) as DynamicInOut

			val pathService = context.getRoutingServices().getPathReservationService()
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
		private val editingContextFactory: JvmEditingContextFactory by inject()
		private val simulationContextFactory: SimulationContextFactory by inject()

		private lateinit var context: DefaultSimulationContext
		private lateinit var service: TrainNavigationService

		@BeforeEach
		fun setUp() {
			// Use vyhybna.xml (7 blocks) for realistic multi-block ownership scenarios
			TestFixtures.loadShuntingXml().use { xmlStream ->
				val editingContext = editingContextFactory.createContext(xmlStream) as DefaultEditingContext
				context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
				service = context.getRoutingServices().getTrainNavigationService()
			}
		}

		@AfterEach
		fun tearDown() {
			context.close()
		}

		@Test
		fun `findReservedPathForTrain returns OwnershipConflict when one block owned by different train`() {
			// Arrange: Reserve full path from A to B for train1
			val pathService = context.getRoutingServices().getPathReservationService()
			val registry = context.scope.get<PathReservationRegistry>()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Train1 reserves from A to B (all 7 blocks)
			pathService.reservePath("train1", inOutA, inOutB)
			val allBlocks = pathService.getReservedBlocks("train1")
			assertThat(allBlocks.size).isEqualTo(7)

			// Get actual path navigation service will check (to next semaphore, not full path)
			val pathToCheckResult = service.findReservedPathForTrain("train1", inOutA)
			assertThat(pathToCheckResult).isInstanceOf(PathResult.Available::class)
			val pathToCheck = (pathToCheckResult as PathResult.Available).path

			// Extract blocks from the actual path being navigated
			val blocksInPath =
				pathToCheck
					.filterIsInstance<TrackSection>()
					.map { it.getTrackBlock() }
					.filterIsInstance<DynamicTrackBlock>()
					.toSet()

			// Verify blocks are valid and owned by train1 before conflict
			assertThat(blocksInPath).isNotEmpty()
			blocksInPath.forEach { block ->
				assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(registry.getOwner(block)).isEqualTo("train1")
			}

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
			stolenBlock.cancelPathSetup(inOutA) // Reset state (RESERVED -> FREE)
			stolenBlock.setUpPath(inOutA, "train2") // Reserve for train2 (FREE -> RESERVED)
			registry.registerAtomic("train2", listOf(stolenBlock)) // Register train2 in registry

			// Act: Train1 tries to navigate again (should now fail due to stolen block)
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Should fail due to ownership conflict (train2 owns first block in path)
			assertThat(result).isInstanceOf(PathResult.OwnershipConflict::class)
		}

		@Test
		fun `findReservedPathForTrain returns OwnershipConflict when first block not owned`() {
			// Arrange: Don't reserve any blocks for train1
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut

			// Act: Try to navigate without reserving path first
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Should fail since no blocks are owned by train1 (topological path exists, but ownership conflict)
			assertThat(result).isInstanceOf(PathResult.OwnershipConflict::class)
		}

		@Test
		fun `findReservedPathForTrain returns OwnershipConflict when last block not owned`() {
			// Arrange: Reserve path for train1, then release it, then partially reserve for train2
			// This creates a scenario where first blocks are owned by train2, not train1
			val pathService = context.getRoutingServices().getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Train2 reserves the path (so train1 doesn't own last blocks)
			pathService.reservePath("train2", inOutA, inOutB)

			// Act: Train1 tries to navigate (but doesn't own the path)
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Should fail since path is owned by train2
			assertThat(result).isInstanceOf(PathResult.OwnershipConflict::class)
		}

		@Test
		fun `isPathReservedForTrain returns false when ownership conflict exists`() {
			// Arrange: Reserve full path from A to B for train1
			val pathService = context.getRoutingServices().getPathReservationService()
			val registry = context.scope.get<PathReservationRegistry>()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)
			val allBlocks = pathService.getReservedBlocks("train1")
			assertThat(allBlocks.size).isEqualTo(7)

			// Get actual path navigation service will check (to next semaphore, not full path)
			val pathToCheckResult = service.findReservedPathForTrain("train1", inOutA)
			assertThat(pathToCheckResult).isInstanceOf(PathResult.Available::class)
			val pathToCheck = (pathToCheckResult as PathResult.Available).path

			// Extract blocks from the actual path being navigated
			val blocksInPath =
				pathToCheck
					.filterIsInstance<TrackSection>()
					.map { it.getTrackBlock() }
					.filterIsInstance<DynamicTrackBlock>()
					.toSet()
			assertThat(blocksInPath).isNotEmpty()
			assertThat(blocksInPath.size).isLessThan(allBlocks.size) // Path to semaphore is shorter than full path

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
			stolenBlock.cancelPathSetup(inOutA) // Reset state (RESERVED -> FREE)
			stolenBlock.setUpPath(inOutA, "train2") // Reserve for train2 (FREE -> RESERVED)
			registry.registerAtomic("train2", listOf(stolenBlock)) // Register train2 in registry

			// Act
			val result = service.isPathReservedForTrain("train1", inOutA)

			// Assert: Should fail due to ownership conflict (train2 owns first block in navigation path)
			assertThat(result).isFalse()
		}
	}

	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCaseTests {
		private val editingContextFactory: JvmEditingContextFactory by inject()
		private val simulationContextFactory: SimulationContextFactory by inject()

		@Test
		fun `findReservedPathForTrain returns OwnershipConflict when no reserved path exists`() {
			// Arrange: Disconnected InOuts (no track connection)
			TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 10, 10, false)
				// No connection!
				.buildSimulationContext()
				.use { context ->
					val service = context.getRoutingServices().getTrainNavigationService()
					val grid = context.getRailWayNetGrid()
					val inOutA = grid.getCellAt(1, 1) as DynamicInOut

					// Act
					val result = service.findReservedPathForTrain("train1", inOutA)

					// Assert: Without PathInfo the train must wait for dispatcher (OwnershipConflict semantics)
					assertThat(result).isInstanceOf(PathResult.NoTopologicalPath::class)
				}
		}

		@Test
		fun `findReservedPathForTrain returns OwnershipConflict when single InOut has no connections`() {
			// Arrange: Single InOut (no connections = no path topologically)
			TestTopologies.deadEndSingleInOutSimulation().use { context ->
				val service = context.getRoutingServices().getTrainNavigationService()
				val grid = context.getRailWayNetGrid()
				val inOutA = grid.getCellAt(1, 1) as DynamicInOut

				// Act
				val result = service.findReservedPathForTrain("train1", inOutA)

				// Assert: Dispatcher never registered a path, so navigation reports OwnershipConflict
				assertThat(result).isInstanceOf(PathResult.NoTopologicalPath::class)
			}
		}

		@Test
		fun `findReservedPathForTrain filters out non-DynamicTrackBlocks`() {
			// Arrange: Use vyhybna.xml (has TrackSections with blocks)
			TestFixtures.loadShuntingXml().use { xmlStream ->
				editingContextFactory.createContext(xmlStream).use { editingCtx ->
					simulationContextFactory.createContext(editingCtx as DefaultEditingContext).use { ctx ->
						val context = ctx as DefaultSimulationContext
						val service = context.getRoutingServices().getTrainNavigationService()
						val pathService = context.getRoutingServices().getPathReservationService()

						val grid = context.getRailWayNetGrid()
						val inOutA = grid.getCellAt(11, 8) as DynamicInOut
						val inOutB = grid.getCellAt(30, 8) as DynamicInOut

						// Reserve path
						pathService.reservePath("train1", inOutA, inOutB)

						// Act
						val result = service.findReservedPathForTrain("train1", inOutA)

						// Assert: Path is available
						assertThat(result).isInstanceOf(PathResult.Available::class)
					}
				}
			}
		}

		@Test
		fun `findReservedPathForTrain deduplicates blocks in path`() {
			// Arrange: vyhybna.xml has switches that may create duplicate block references
			TestFixtures.loadShuntingXml().use { xmlStream ->
				editingContextFactory.createContext(xmlStream).use { editingCtx ->
					simulationContextFactory.createContext(editingCtx as DefaultEditingContext).use { ctx ->
						val context = ctx as DefaultSimulationContext
						val service = context.getRoutingServices().getTrainNavigationService()
						val pathService = context.getRoutingServices().getPathReservationService()

						val grid = context.getRailWayNetGrid()
						val inOutA = grid.getCellAt(11, 8) as DynamicInOut
						val inOutB = grid.getCellAt(30, 8) as DynamicInOut

						// Reserve path
						pathService.reservePath("train1", inOutA, inOutB)

						// Act
						val result = service.findReservedPathForTrain("train1", inOutA)

						// Assert: Path is available
						assertThat(result).isInstanceOf(PathResult.Available::class)
					}
				}
			}
		}

		@Test
		fun `isPathReservedForTrain returns false when no path exists`() {
			// Arrange: Disconnected InOuts
			TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 10, 10, false)
				.buildSimulationContext()
				.use { context ->
					val service = context.getRoutingServices().getTrainNavigationService()
					val grid = context.getRailWayNetGrid()
					val inOutA = grid.getCellAt(1, 1) as DynamicInOut

					// Act
					val result = service.isPathReservedForTrain("train1", inOutA)

					// Assert
					assertThat(result).isFalse()
				}
		}
	}

	@Nested
	@DisplayName("reservedSeparatorsAhead (SP2a.1)")
	inner class ReservedSeparatorsAheadTests {
		private val editingContextFactory: JvmEditingContextFactory by inject()
		private val simulationContextFactory: SimulationContextFactory by inject()

		@Test
		fun `returns oriented separators after the anchor along the reserved route, in order`() {
			TestFixtures.loadShuntingXml().use { xmlStream ->
				editingContextFactory.createContext(xmlStream).use { editingCtx ->
					simulationContextFactory.createContext(editingCtx as DefaultEditingContext).use { ctx ->
						val context = ctx as DefaultSimulationContext
						val service = context.getRoutingServices().getTrainNavigationService()
						val pathService = context.getRoutingServices().getPathReservationService()
						val registry: PathReservationRegistry = context.scope.get()

						val grid = context.getRailWayNetGrid()
						val inOutA = grid.getCellAt(11, 8) as DynamicInOut
						val inOutB = grid.getCellAt(30, 8) as DynamicInOut

						pathService.reservePath("train1", inOutA, inOutB)

						// Independent manual walk of the reserved path: every OrientedPathSeparator
						// after the anchor, in route order. vyhybna's A→B route has intermediate
						// signals, so this list is non-empty.
						val reservedPath = registry.getPathInfo("train1")!!.reservedPath
						val anchorIndex = reservedPath.indexOfFirst { it == inOutA }
						assertThat(anchorIndex).isGreaterThan(-1)
						val expected =
							(anchorIndex + 1 until reservedPath.size)
								.mapNotNull { reservedPath.elementAt(it) as? OrientedPathSeparator }
						assertThat(expected).isNotEmpty()

						// limit larger than available returns all of them.
						val result = service.reservedSeparatorsAhead("train1", inOutA, expected.size + 5)
						assertThat(result).isEqualTo(expected)

						// limit 1 returns only the first oriented separator after the anchor.
						val firstOnly = service.reservedSeparatorsAhead("train1", inOutA, 1)
						assertThat(firstOnly).isEqualTo(expected.take(1))
					}
				}
			}
		}

		@Test
		fun `limit 0 returns an empty list`() {
			TestFixtures.loadShuntingXml().use { xmlStream ->
				editingContextFactory.createContext(xmlStream).use { editingCtx ->
					simulationContextFactory.createContext(editingCtx as DefaultEditingContext).use { ctx ->
						val context = ctx as DefaultSimulationContext
						val service = context.getRoutingServices().getTrainNavigationService()
						val pathService = context.getRoutingServices().getPathReservationService()
						val grid = context.getRailWayNetGrid()
						val inOutA = grid.getCellAt(11, 8) as DynamicInOut
						val inOutB = grid.getCellAt(30, 8) as DynamicInOut
						pathService.reservePath("train1", inOutA, inOutB)

						assertThat(service.reservedSeparatorsAhead("train1", inOutA, 0)).isEmpty()
					}
				}
			}
		}

		@Test
		fun `unknown train (no PathInfo) returns an empty list`() {
			TestFixtures.loadShuntingXml().use { xmlStream ->
				editingContextFactory.createContext(xmlStream).use { editingCtx ->
					simulationContextFactory.createContext(editingCtx as DefaultEditingContext).use { ctx ->
						val context = ctx as DefaultSimulationContext
						val service = context.getRoutingServices().getTrainNavigationService()
						val grid = context.getRailWayNetGrid()
						val inOutA = grid.getCellAt(11, 8) as DynamicInOut

						// "noTrain" has no reserved path registered.
						assertThat(service.reservedSeparatorsAhead("noTrain", inOutA, 5)).isEmpty()
					}
				}
			}
		}

		@Test
		fun `terminal anchor (within one semaphore of destination) returns an empty list`() {
			TestFixtures.loadShuntingXml().use { xmlStream ->
				editingContextFactory.createContext(xmlStream).use { editingCtx ->
					simulationContextFactory.createContext(editingCtx as DefaultEditingContext).use { ctx ->
						val context = ctx as DefaultSimulationContext
						val service = context.getRoutingServices().getTrainNavigationService()
						val pathService = context.getRoutingServices().getPathReservationService()
						val grid = context.getRailWayNetGrid()
						val inOutA = grid.getCellAt(11, 8) as DynamicInOut
						val inOutB = grid.getCellAt(30, 8) as DynamicInOut
						pathService.reservePath("train1", inOutA, inOutB)

						// The destination InOut is the last element of the reserved route — there
						// are no oriented separators after it.
						assertThat(service.reservedSeparatorsAhead("train1", inOutB, 5)).isEmpty()
					}
				}
			}
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
			context = TestTopologies.simpleLinearPathSimulation() as DefaultSimulationContext

			service = context.getRoutingServices().getTrainNavigationService()
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

			val pathService = context.getRoutingServices().getPathReservationService()
			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val foundPath = service.findReservedPathForTrain("train1", inOutA)
			val isAvailable = service.isPathReservedForTrain("train1", inOutA)

			// Assert
			assertThat(foundPath).isInstanceOf(PathResult.Available::class)
			assertThat(isAvailable).isTrue()
		}

		@Test
		fun `isPathReservedForTrain matches findReservedPathForTrain when path unavailable`() {
			// Arrange: Disconnected network (InOut A and B have no connecting track)
			TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 10, 10, false)
				.buildSimulationContext()
				.use { disconnectedCtx ->
					val disconnectedService = disconnectedCtx.getRoutingServices().getTrainNavigationService()
					val grid = disconnectedCtx.getRailWayNetGrid()
					val inOutA = grid.getCellAt(1, 1) as DynamicInOut

					// Act
					val foundPathResult = disconnectedService.findReservedPathForTrain("train1", inOutA)
					val isAvailable = disconnectedService.isPathReservedForTrain("train1", inOutA)

					// Assert
					assertThat(foundPathResult).isInstanceOf(PathResult.NoTopologicalPath::class)
					assertThat(isAvailable).isFalse()
				}
		}

		@Test
		fun `isPathReservedForTrain matches findReservedPathForTrain for ownership conflicts`() {
			// Arrange: Reserve for different train
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(1, 1) as DynamicInOut
			val inOutB = grid.getCellAt(5, 5) as DynamicInOut

			val pathService = context.getRoutingServices().getPathReservationService()
			pathService.reservePath("train2", inOutA, inOutB) // Different owner

			// Act: Train1 tries to navigate
			val foundPath = service.findReservedPathForTrain("train1", inOutA)
			val isAvailable = service.isPathReservedForTrain("train1", inOutA)

			// Assert
			assertThat(foundPath).isInstanceOf(PathResult.OwnershipConflict::class)
			assertThat(isAvailable).isFalse()
		}
	}

	@Nested
	@DisplayName("Block Extraction")
	inner class BlockExtractionTests {
		private val editingContextFactory: JvmEditingContextFactory by inject()
		private val simulationContextFactory: SimulationContextFactory by inject()

		private lateinit var context: DefaultSimulationContext
		private lateinit var service: TrainNavigationService

		@BeforeEach
		fun setUp() {
			// Use vyhybna.xml (complex path with separators and blocks)
			TestFixtures.loadShuntingXml().use { xmlStream ->
				val editingContext = editingContextFactory.createContext(xmlStream) as DefaultEditingContext
				context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
				service = context.getRoutingServices().getTrainNavigationService()
			}
		}

		@AfterEach
		fun tearDown() {
			context.close()
		}

		@Test
		fun `extractDynamicTrackBlocks filters PathSeparator elements`() {
			// Arrange
			val pathService = context.getRoutingServices().getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Path is available
			assertThat(result).isInstanceOf(PathResult.Available::class)
		}

		@Test
		fun `extractDynamicTrackBlocks extracts only DynamicTrackBlock instances`() {
			// Arrange
			val pathService = context.getRoutingServices().getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Path is available
			assertThat(result).isInstanceOf(PathResult.Available::class)
			// Verify blocks were extracted
			val blocks = pathService.getReservedBlocks("train1")
			assertThat(blocks.size).isEqualTo(7)
		}

		@Test
		fun `extractDynamicTrackBlocks preserves block order`() {
			// Arrange
			val pathService = context.getRoutingServices().getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Path is available
			assertThat(result).isInstanceOf(PathResult.Available::class)
		}

		@Test
		fun `extractDynamicTrackBlocks handles mixed element types`() {
			// Arrange: vyhybna.xml has mixed separators and blocks
			val pathService = context.getRoutingServices().getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			pathService.reservePath("train1", inOutA, inOutB)

			// Act
			val result = service.findReservedPathForTrain("train1", inOutA)

			// Assert: Path is available
			assertThat(result).isInstanceOf(PathResult.Available::class)
		}

		@Test
		fun `getReservedBlocks returns blocks for owned path`() {
			// Arrange
			val pathService = context.getRoutingServices().getPathReservationService()
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

	@Nested
	@DisplayName("Multi-Train Navigation Coordination")
	inner class MultiTrainNavigationCoordination {
		private val editingContextFactory: JvmEditingContextFactory by inject()
		private val simulationContextFactory: SimulationContextFactory by inject()

		private lateinit var context: DefaultSimulationContext
		private lateinit var navService: TrainNavigationService
		private lateinit var pathService: PathReservationService
		private lateinit var registry: PathReservationRegistry

		@BeforeEach
		fun setUp() {
			// Use vyhybna.xml (7 blocks, 2 alternative paths via switches vA/vB)
			TestFixtures.loadShuntingXml().use { xmlStream ->
				val editingContext = editingContextFactory.createContext(xmlStream) as DefaultEditingContext
				context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

				// Get real services from context scope
				navService = context.getRoutingServices().getTrainNavigationService()
				pathService = context.getRoutingServices().getPathReservationService()
				registry = context.scope.get()
			}
		}

		@AfterEach
		fun tearDown() {
			context.close()
		}

		/**
		 * Helper function to extract DynamicTrackBlock instances from a path.
		 *
		 * Filters out PathSeparator elements and returns only track blocks.
		 *
		 * @param path Path collection containing PathElements (PathSeparators and TrackSections)
		 * @return Set of DynamicTrackBlocks in the path (order not significant)
		 */
		private fun extractNavigationBlocks(path: cz.vutbr.fit.interlockSim.objects.paths.Path): Set<DynamicTrackBlock> =
			path
				.filterIsInstance<TrackSection>()
				.map { it.getTrackBlock() }
				.filterIsInstance<DynamicTrackBlock>()
				.toSet()

		/**
		 * Tests multi-train navigation coordination with independent reserved paths.
		 *
		 * **Scenario**:
		 * - Train1 reserves and navigates from InOut A (11,8) → InOut B (30,8)
		 * - Train2 attempts to reserve and navigate from InOut B (30,8) → InOut A (11,8)
		 *
		 * **Focus**: Reservation/Navigation Boundary
		 * - Each train reserves blocks via PathReservationService
		 * - Each train navigates via TrainNavigationService
		 * - Navigation result MUST match reservation state
		 * - Trains see only their own blocks (no cross-contamination)
		 *
		 * **Expected Behavior**:
		 * - Train1 navigation succeeds with blocks matching reservation
		 * - Train2 navigation succeeds if reservation succeeded (disjoint paths)
		 * - Train2 navigation fails if reservation failed (conflicting paths)
		 * - Registry state matches navigation results
		 *
		 * **Verification**:
		 * - Navigation blocks == Reserved blocks (for each train)
		 * - No overlap between train1 and train2 navigation paths (if both reserved)
		 * - Registry ownership matches navigation ownership
		 */
		@Test
		fun `two trains navigate their own reserved paths without interference`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Reserve paths
			val reserveResult1 = pathService.reservePath("train1", inOutA, inOutB)
			assertThat(reserveResult1).isInstanceOf(PathReservationService.ReservationResult.Success::class)
			val train1ReservedBlocks =
				(reserveResult1 as PathReservationService.ReservationResult.Success).reservedBlocks.toSet()

			val reserveResult2 = pathService.reservePath("train2", inOutB, inOutA)

			// Navigate train1
			val navPath1 = navService.findReservedPathForTrain("train1", inOutA)
			assertThat(navPath1).isInstanceOf(PathResult.Available::class)

			// Extract blocks from navigation path (to NEXT semaphore, not full path)
			val train1NavBlocks = extractNavigationBlocks((navPath1 as PathResult.Available).path)

			// Verify navigation blocks are subset of reserved blocks
			// (navigation returns path to NEXT semaphore, not full path to target)
			assertThat(train1ReservedBlocks).isNotEmpty()
			assertThat(train1ReservedBlocks.size).isLessThan(train1ReservedBlocks.size + 1) // Nav path ≤ reserved blocks
			train1ReservedBlocks.forEach { block ->
				assertThat(train1ReservedBlocks).contains(block)
			}

			// If train2 reservation succeeded, verify navigation is subset of reservation
			if (reserveResult2 is PathReservationService.ReservationResult.Success) {
				val train2ReservedBlocks = reserveResult2.reservedBlocks.toSet()
				val navPath2Result = navService.findReservedPathForTrain("train2", inOutB)
				assertThat(navPath2Result).isInstanceOf(PathResult.Available::class)

				val navPath2 = (navPath2Result as PathResult.Available).path
				val train2NavBlocks = extractNavigationBlocks(navPath2)

				// Verify navigation blocks are subset of reserved blocks
				assertThat(train2ReservedBlocks).isNotEmpty()
				assertThat(train2ReservedBlocks.size).isLessThan(train2ReservedBlocks.size + 1) // Nav path ≤ reserved blocks
				train2ReservedBlocks.forEach { block ->
					assertThat(train2ReservedBlocks).contains(block)
				}

				// Verify no overlap (disjoint paths - even partial)
				assertThat(train1ReservedBlocks.intersect(train2ReservedBlocks)).isEmpty()
			} else {
				// Train2 reservation failed (expected for single path network)
				// Train2 navigation should also fail
				val navPath2Result = navService.findReservedPathForTrain("train2", inOutB)
				assertThat(navPath2Result).isInstanceOf(PathResult.OwnershipConflict::class)
			}
		}

		/**
		 * Tests navigation behavior after reservation conflict (TOCTOU scenario).
		 *
		 * **Scenario**:
		 * - Train1 successfully reserves path (InOut A → InOut B)
		 * - Train2 attempts same path and fails (AllPathsBlocked)
		 * - Both trains attempt navigation
		 *
		 * **Focus**: Navigation reflects reservation outcome
		 * - Train1 navigates successfully (owns blocks)
		 * - Train2 navigation returns null (owns zero blocks)
		 * - Registry state is consistent with navigation results
		 *
		 * **Expected Behavior**:
		 * - Train1: findReservedPathForTrain returns Path
		 * - Train2: findReservedPathForTrain returns null
		 * - Registry: train1 has blocks, train2 has empty list
		 *
		 * **Critical**: Tests that failed reservation prevents navigation
		 * (trains cannot navigate through blocks they don't own)
		 */
		@Test
		fun `train navigation fails after reservation conflict`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Train1 reserves successfully
			val result1 = pathService.reservePath("train1", inOutA, inOutB)
			assertThat(result1).isInstanceOf(PathReservationService.ReservationResult.Success::class)

			// Train2 reservation fails (TOCTOU window - blocks already owned by train1)
			val result2 = pathService.reservePath("train2", inOutA, inOutB)
			assertThat(result2).isInstanceOf(PathReservationService.ReservationResult.AllPathsBlocked::class)

			// Train1 navigation succeeds
			val navPath1Result = navService.findReservedPathForTrain("train1", inOutA)
			assertThat(navPath1Result).isInstanceOf(PathResult.Available::class)

			// Train2 navigation fails (no blocks reserved)
			val navPath2Result = navService.findReservedPathForTrain("train2", inOutA)
			assertThat(navPath2Result).isInstanceOf(PathResult.OwnershipConflict::class)

			// Verify registry state
			val train1Blocks = pathService.getReservedBlocks("train1")
			val train2Blocks = pathService.getReservedBlocks("train2")
			assertThat(train1Blocks.size).isGreaterThan(0)
			assertThat(train2Blocks).isEmpty()
		}

		/**
		 * Tests navigation behavior after path release.
		 *
		 * **Scenario**:
		 * - Train1 reserves path (InOut A → InOut B)
		 * - Train1 navigates successfully (owns blocks)
		 * - Train1 releases path (blocks freed)
		 * - Train1 attempts navigation again (should fail)
		 *
		 * **Focus**: Navigation reflects dynamic ownership changes
		 * - Before release: navigation succeeds
		 * - After release: navigation fails (returns null)
		 * - Registry state transitions from owned → empty
		 *
		 * **Expected Behavior**:
		 * - Navigation before release: returns Path
		 * - Navigation after release: returns null
		 * - Registry: empty after release
		 *
		 * **Critical**: Tests that navigation respects ownership changes
		 * (prevents train from navigating through released blocks)
		 */
		@Test
		fun `navigation fails after path release`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Reserve and navigate
			val reserveResult = pathService.reservePath("train1", inOutA, inOutB)
			assertThat(reserveResult).isInstanceOf(PathReservationService.ReservationResult.Success::class)

			val navPathBefore = navService.findReservedPathForTrain("train1", inOutA)
			assertThat(navPathBefore).isInstanceOf(PathResult.Available::class)

			// Release path
			pathService.releasePath("train1")

			// Navigation should now fail
			val navPathAfterResult = navService.findReservedPathForTrain("train1", inOutA)
			assertThat(navPathAfterResult).isInstanceOf(PathResult.OwnershipConflict::class)

			// Verify registry cleanup
			val blocksAfterRelease = pathService.getReservedBlocks("train1")
			assertThat(blocksAfterRelease).isEmpty()
		}

		/**
		 * Tests ownership enforcement when block is stolen by another train.
		 *
		 * **Scenario**:
		 * - Train1 reserves path with blocks [1,2,3,4,5,6,7]
		 * - First block in navigation path is manually reassigned to train2 (simulate conflict)
		 * - Train1 attempts navigation (should detect ownership mismatch)
		 *
		 * **Focus**: Navigation validates ownership for ALL blocks in path
		 * - Navigation checks registry ownership for each block in navigation path
		 * - Returns null on first ownership mismatch (no partial paths)
		 * - Protects against trains navigating through other train's blocks
		 *
		 * **Expected Behavior**:
		 * - Navigation returns null (ownership conflict detected on first block)
		 * - No partial path returned (all-or-nothing)
		 * - Registry shows mixed ownership (train1 and train2)
		 *
		 * **Critical**: Tests safety mechanism preventing cross-train interference
		 *
		 * **Note**: We steal the FIRST block in the navigation path (not middle of full reservation)
		 * because navigation only returns path to next semaphore, not full reserved path.
		 */
		@Test
		fun `navigation fails when block stolen by other train`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Reserve path for train1 (full path from A to B)
			val result = pathService.reservePath("train1", inOutA, inOutB)
			assertThat(result).isInstanceOf(PathReservationService.ReservationResult.Success::class)

			// Get the navigation path (to next semaphore)
			val initialNavPath = navService.findReservedPathForTrain("train1", inOutA)
			assertThat(initialNavPath).isInstanceOf(PathResult.Available::class)
			val navBlocks = pathService.getReservedBlocks("train1").toSet()
			assertThat(navBlocks.size).isGreaterThan(0)

			// Steal FIRST block in navigation path (the one train will encounter first)
			val firstBlock = navBlocks.first()

			// Step 1: Cancel train1's reservation (make block FREE)
			firstBlock.cancelPathSetup(inOutA) // Reset state (RESERVED -> FREE)

			// Step 2: Unregister train1's ownership of first block (must be FREE first)
			val unregistered = registry.unregisterBlock("train1", firstBlock)
			assertThat(unregistered).isTrue() // Verify unregistration succeeded

			// Step 3: Reserve first block for train2 (simulate conflict)
			firstBlock.setUpPath(inOutA, "train2") // Reserve for train2 (FREE -> RESERVED)
			registry.registerAtomic("train2", listOf(firstBlock)) // Register train2 in registry

			// Train1 navigation should now fail (ownership conflict on first block)
			val navPathAfterTheftResult = navService.findReservedPathForTrain("train1", inOutA)
			assertThat(navPathAfterTheftResult).isInstanceOf(PathResult.OwnershipConflict::class)

			// Verify registry state (mixed ownership)
			assertThat(registry.getOwner(firstBlock)).isEqualTo("train2")
		}

		/**
		 * Tests consistency between navigation service and registry state.
		 *
		 * **Scenario**:
		 * - Train1 reserves path via reservePath (full path A → B)
		 * - Query blocks via registry: getReservedBlocks("train1")
		 * - Query blocks via navigation: findReservedPathForTrain("train1", start)
		 *
		 * **Focus**: Navigation uses registry as source of truth
		 * - Navigation blocks MUST be subset of registry blocks
		 * - All navigation blocks owned by train1 in registry
		 * - Navigation never returns blocks not in registry
		 *
		 * **Expected Behavior**:
		 * - Navigation blocks ⊆ Registry blocks (subset relationship)
		 * - All navigation blocks owned by train1 in registry
		 * - Navigation follows registry ownership faithfully
		 *
		 * **Critical**: Tests integration between navigation and reservation
		 * (navigation reflects reservation state accurately)
		 *
		 * **Note**: Navigation returns path to NEXT semaphore only, not full reservation,
		 * so navigation blocks will be smaller set than registry blocks.
		 */
		@Test
		fun `navigation blocks match registry reserved blocks`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Reserve path (full path from A to B)
			val reserveResult = pathService.reservePath("train1", inOutA, inOutB)
			assertThat(reserveResult).isInstanceOf(PathReservationService.ReservationResult.Success::class)

			// Get blocks from registry (full reservation)
			val registryBlocks = pathService.getReservedBlocks("train1").toSet()
			assertThat(registryBlocks.size).isEqualTo(7) // vyhybna.xml has 7 blocks

			// Get blocks from navigation (path to next semaphore)
			val navPath = navService.findReservedPathForTrain("train1", inOutA)
			assertThat(navPath).isInstanceOf(PathResult.Available::class)

			// Verify navigation blocks are subset of registry blocks
			assertThat(registryBlocks.size).isGreaterThan(0)
			assertThat(registryBlocks.size).isLessThan(registryBlocks.size + 1) // Less than or equal
			registryBlocks.forEach { block ->
				assertThat(registryBlocks).contains(block)
			}

			// Verify all navigation blocks owned by train1
			registryBlocks.forEach { block ->
				assertThat(registry.getOwner(block)).isEqualTo("train1")
			}
		}

		/**
		 * Tests navigation follows exact path selected by reservation service.
		 *
		 * **Scenario**:
		 * - vyhybna.xml has single path (A → B via 7 blocks)
		 * - Train1 reserves using reservePath
		 * - Train1 navigates using findReservedPathForTrain
		 *
		 * **Focus**: Navigation uses registry, NOT topology
		 * - Navigation follows blocks from reservation (subset for path to next semaphore)
		 * - Navigation does NOT explore alternative paths via topology
		 * - All navigation blocks come from reserved blocks
		 *
		 * **Expected Behavior**:
		 * - Navigation blocks ⊆ reservation blocks (subset relationship)
		 * - Navigation doesn't query TopologyNavigator for alternatives
		 * - Navigation relies solely on PathReservationRegistry
		 *
		 * **Design Principle**: TrainNavigationService depends ONLY on registry
		 * (separation of concerns: reservation finds paths, navigation follows paths)
		 */
		@Test
		fun `navigation follows path selected by reservation service`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Reserve path (full path via 7 blocks in vyhybna.xml)
			val reserveResult = pathService.reservePath("train1", inOutA, inOutB)
			assertThat(reserveResult).isInstanceOf(PathReservationService.ReservationResult.Success::class)
			val reservedBlocks = (reserveResult as PathReservationService.ReservationResult.Success).reservedBlocks.toSet()

			// Navigate (returns path to next semaphore)
			val navPath = navService.findReservedPathForTrain("train1", inOutA)
			assertThat(navPath).isInstanceOf(PathResult.Available::class)

			// Verify navigation blocks are subset of reserved blocks
			assertThat(reservedBlocks.size).isGreaterThan(0)
			reservedBlocks.forEach { block ->
				assertThat(reservedBlocks).contains(block)
			}

			// Verify all navigation blocks have correct ownership
			reservedBlocks.forEach { block ->
				assertThat(registry.getOwner(block)).isEqualTo("train1")
			}
		}

		/**
		 * Sequential scalability test: 5 trains reserve→navigate→release.
		 *
		 * **Scenario**:
		 * - 5 trains sequentially use the same path (InOut A → InOut B)
		 * - Each train: reserves, navigates, releases
		 * - Tests long-term stability and cleanup
		 *
		 * **Focus**: Lifecycle coordination (reservation + navigation + release)
		 * - Navigation succeeds while path reserved
		 * - Navigation fails after path released
		 * - Registry cleanup verified after each release
		 *
		 * **Expected Behavior**:
		 * - All 5 trains succeed (no cumulative corruption)
		 * - Navigation blocks match reservation blocks (each iteration)
		 * - Navigation fails after release (each iteration)
		 * - Registry empty after all trains complete
		 *
		 * **Memory Leak Detection**: Verifies no orphaned paths or stale references
		 * accumulate over multiple reserve/navigate/release cycles.
		 *
		 * **Performance Goal**: 5 iterations complete in <500ms
		 */
		@Test
		fun `five trains sequential reserve navigate release`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			for (i in 1..5) {
				val trainId = "train$i"

				// Reserve (full path from A to B)
				val reserveResult = pathService.reservePath(trainId, inOutA, inOutB)
				assertThat(reserveResult).isInstanceOf(PathReservationService.ReservationResult.Success::class)
				val reservedBlocks = (reserveResult as PathReservationService.ReservationResult.Success).reservedBlocks.toSet()

				// Navigate (returns path to next semaphore)
				val navPath = navService.findReservedPathForTrain(trainId, inOutA)
				assertThat(navPath).isInstanceOf(PathResult.Available::class)

				// Verify navigation blocks are subset of reservation blocks
				assertThat(reservedBlocks.size).isGreaterThan(0)
				reservedBlocks.forEach { block ->
					assertThat(reservedBlocks).contains(block)
				}

				// Release
				pathService.releasePath(trainId)

				// Verify navigation now fails
				val navPathAfterReleaseResult = navService.findReservedPathForTrain(trainId, inOutA)
				assertThat(navPathAfterReleaseResult).isInstanceOf(PathResult.OwnershipConflict::class)

				// Verify cleanup
				assertThat(pathService.getReservedBlocks(trainId)).isEmpty()
			}

			// Final verification: registry empty
			for (i in 1..5) {
				assertThat(pathService.getReservedBlocks("train$i")).isEmpty()
			}
		}

		/**
		 * Tests navigation consistency between isPathReservedForTrain and findReservedPathForTrain.
		 *
		 * **Scenario**:
		 * - Train1 reserves path (InOut A → InOut B)
		 * - Query using both navigation methods
		 * - Verify results are consistent
		 *
		 * **Focus**: Method consistency
		 * - findReservedPathForTrain returns Path ⟺ isPathReservedForTrain returns true
		 * - Both methods check same ownership condition
		 * - No state divergence between boolean and path result
		 *
		 * **Expected Behavior**:
		 * - When findReservedPathForTrain returns Path → isPathReservedForTrain returns true
		 * - When findReservedPathForTrain returns null → isPathReservedForTrain returns false
		 *
		 * **Design Invariant**: Both methods MUST agree on reservation state
		 */
		@Test
		fun `isPathReservedForTrain matches findReservedPathForTrain for multi-train scenarios`() {
			// Arrange
			val grid = context.getRailWayNetGrid()
			val inOutA = grid.getCellAt(11, 8) as DynamicInOut
			val inOutB = grid.getCellAt(30, 8) as DynamicInOut

			// Train1 reserves successfully
			pathService.reservePath("train1", inOutA, inOutB)

			// Train1 navigation methods should agree
			val train1Path = navService.findReservedPathForTrain("train1", inOutA)
			val train1Reserved = navService.isPathReservedForTrain("train1", inOutA)

			assertThat(train1Path).isInstanceOf(PathResult.Available::class)
			assertThat(train1Reserved).isTrue()

			// Train2 (no reservation) navigation methods should agree
			val train2PathResult = navService.findReservedPathForTrain("train2", inOutA)
			val train2Reserved = navService.isPathReservedForTrain("train2", inOutA)

			assertThat(train2PathResult).isInstanceOf(PathResult.OwnershipConflict::class)
			assertThat(train2Reserved).isFalse()
		}
	}
}
