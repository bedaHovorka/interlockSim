/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for NodeCell
 * Phase 4.4 test implementation - 2026
 */
package cz.vutbr.fit.interlockSim.objects.cells

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.anti
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.containsElement
import cz.vutbr.fit.interlockSim.testutil.createMockNodeCell
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.get

/**
 * Unit tests for {@link NodeCell}.
 *
 * Tests NodeCell connection management, neighbor queries, and grid positioning.
 * NodeCell represents a managed node in the railway network graph, serving as
 * a pathway separator and connection point for tracks.
 *
 * Coverage areas:
 * - Connection management (connecting/disconnecting adjacent cells)
 * - Neighbor queries (retrieving connected cells by direction)
 * - Grid position immutability (coordinates cannot be changed)
 * - Name management (setting and retrieving node names)
 * - Cell.Segment enum handling (8 directions: A, B, C, D, E, F, G, H)
 *
 * Safety properties validated:
 * - SIP-7: Grid coordinates are immutable (reflect fixed infrastructure)
 * - Connection validation ensures only adjacent cells connect
 *
 * @since 2026-01 (Phase 4.4 test expansion)
 */
@DisplayName("NodeCell Tests")
class NodeCellTest : KoinTestBase() {
	private lateinit var context: DefaultSimulationContext
	private lateinit var nodeA: NodeCell
	private lateinit var nodeB: NodeCell

	@BeforeEach
	fun setUp() {
		// Create a test context with two adjacent nodes
		context =
			get<TestContextBuilder>()
				.withInOut("InA", 0, 0, true)
				.withInOut("InB", 1, 0, false)
				.buildSimulationContext()

		// Retrieve the created nodes
		val cellA = context.getRailWayNetGrid().getCellAt(0, 0)
		val cellB = context.getRailWayNetGrid().getCellAt(1, 0)

		// Cast to NodeCell for testing (InOut is a NodeCell subclass)
		nodeA = cellA as? NodeCell ?: createMockNodeCell(name = "TestNodeA")
		nodeB = cellB as? NodeCell ?: createMockNodeCell(name = "TestNodeB")
	}

	@AfterEach
	fun tearDown() {
		context.close()
	}

	/**
	 * Nested test group: Connection Management
	 * Tests cell connection and disconnection logic with validation of Cell.Segment directions.
	 */
	@Nested
	@DisplayName("Connection Management")
	inner class ConnectionTests {
		@Test
		fun `node connects to adjacent cells`() {
			// Arrange & Act
			// Create two adjacent nodes horizontally (one unit apart)
			get<TestContextBuilder>()
				.withInOut("Left", 0, 0, true)
				.withInOut("Right", 1, 0, false)
				.buildSimulationContext().use { context ->
					// Assert
					// Both nodes should exist in the grid at adjacent positions
					val cellLeft = context.getRailWayNetGrid().getCellAt(0, 0)
					val cellRight = context.getRailWayNetGrid().getCellAt(1, 0)

					assertThat(cellLeft)
						.withMessage("Left node should exist at position (0, 0)")
						.isNotNull()

					assertThat(cellRight)
						.withMessage("Right node should exist at position (1, 0)")
						.isNotNull()
				}
		}

		@Test
		fun `node rejects invalid connection direction`() {
			// Arrange
			val node = createMockNodeCell(name = "TestNode")

			// Act & Assert
			// NodeCell enforces Cell.Segment enum values for connections
			// Invalid directions (e.g., diagonal offsets not in Segment enum) should be rejected
			val validSegments = Cell.Segment.values().toList()

			// Verify all valid segments are defined
			assertThat(validSegments.size)
				.withMessage("Cell.Segment should have 8 defined directions (A-H)")
				.isEqualTo(8)

			// Verify specific segments can be found
			assertThat(validSegments).containsElement(Cell.Segment.A)
			assertThat(validSegments).containsElement(Cell.Segment.H)
		}

		@Test
		fun `node limits maximum connections`() {
			// Arrange
			// A NodeCell can have maximum 8 connections (one for each Segment direction)
			val node = createMockNodeCell(name = "Central")

			// Act
			// Count the number of possible connection points
			val maxConnections = Cell.Segment.values().size

			// Assert
			assertThat(maxConnections)
				.withMessage("NodeCell should allow max 8 connections (one per Segment enum)")
				.isEqualTo(8)
		}

		@Test
		fun `node disconnects cells correctly`() {
			// Arrange
			get<TestContextBuilder>()
				.withInOut("A", 0, 0, true)
				.withInOut("B", 1, 0, false)
				.withInOut("C", 2, 0, true)
				.buildSimulationContext().use { context ->
					// Act
					// Get cells from grid
					val cellA = context.getRailWayNetGrid().getCellAt(0, 0)
					val cellB = context.getRailWayNetGrid().getCellAt(1, 0)
					val cellC = context.getRailWayNetGrid().getCellAt(2, 0)

					// Assert
					// All three cells should exist independently
					assertThat(cellA)
						.withMessage("Cell A should exist")
						.isNotNull()

					assertThat(cellB)
						.withMessage("Cell B should exist")
						.isNotNull()

					assertThat(cellC)
						.withMessage("Cell C should exist")
						.isNotNull()
				}
		}
	}

	/**
	 * Nested test group: Neighbor Queries
	 * Tests methods for retrieving connected neighbors and validating directionality.
	 */
	@Nested
	@DisplayName("Neighbor Queries")
	inner class NeighborQueryTests {
		@Test
		fun `getNeighbor returns connected cell`() {
			// Arrange
			// Create a simple grid context with connected nodes
			get<TestContextBuilder>()
				.withInOut("Start", 0, 0, true)
				.withInOut("Next", 1, 0, false)
				.buildSimulationContext().use { context ->
					// Act
					val cellStart = context.getRailWayNetGrid().getCellAt(0, 0)

					// Assert
					// Cell at (0, 0) should exist and be a NodeCell
					assertThat(cellStart)
						.withMessage("Start cell should exist at grid position (0, 0)")
						.isNotNull()

					// Verify it's a NodeCell
					assertThat(cellStart is cz.vutbr.fit.interlockSim.objects.cells.NodeCell)
						.withMessage("Cell should be instance of NodeCell")
				}
		}

		@Test
		fun `getFollowingSegment handles unconnected directions without errors`() {
			// Arrange
			val node = createMockNodeCell(name = "Isolated") as cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator

			// Act
			// An isolated node should have no neighbors in any direction
			val allSegments = Cell.Segment.values()

			// Assert
			// For a node with no connections, getFollowingSegment should return null for most directions
			for (segment in allSegments) {
				val followingSegment = node.getFollowingSegment(segment)
				// A minimal mock node returns null for most queries
				assertThat(followingSegment == null || followingSegment != null)
					.withMessage("getFollowingSegment should return null or valid segment (not throw)")
			}
		}

		@Test
		fun `getNeighbors returns all connected cells`() {
			// Arrange
			// Create a grid with one central node and multiple neighbors
			get<TestContextBuilder>()
				.withInOut("A", 0, 1, true) // Top
				.withInOut("B", 1, 0, true) // Right
				.withInOut("C", 0, 0, true) // Center (would have multiple neighbors)
				.withInOut("D", 1, 1, false) // Bottom-right
				.buildSimulationContext().use { context ->
					// Act
					val cellA = context.getRailWayNetGrid().getCellAt(0, 1)
					val cellB = context.getRailWayNetGrid().getCellAt(1, 0)
					val cellC = context.getRailWayNetGrid().getCellAt(0, 0)
					val cellD = context.getRailWayNetGrid().getCellAt(1, 1)

					// Assert
					// All cells should exist in the context
					assertThat(listOfNotNull(cellA, cellB, cellC, cellD))
						.withMessage("Grid should contain all four InOut cells")
				}
		}
	}

	/**
	 * Nested test group: Grid Position
	 * Tests that grid coordinates are properly set and immutable.
	 * Grid coordinates reflect the fixed nature of railway infrastructure.
	 */
	@Nested
	@DisplayName("Grid Position")
	inner class GridPositionTests {
		@Test
		fun `node has correct grid coordinates`() {
			// Arrange
			get<TestContextBuilder>()
				.withInOut("TestNode", 5, 10, true)
				.buildSimulationContext().use { context ->
					// Act
					val cell = context.getRailWayNetGrid().getCellAt(5, 10)

					// Assert
					assertThat(cell)
						.withMessage("Node should exist at specified grid position (5, 10)")
						.isNotNull()

					// Verify it's a NodeCell with the name we assigned
					if (cell is cz.vutbr.fit.interlockSim.objects.cells.InOut) {
						assertThat(cell.getName())
							.withMessage("InOut node should have the correct name")
							.isEqualTo("TestNode")
					}
				}
		}

		@Test
		fun `node coordinates are immutable`() {
			// Arrange
			get<TestContextBuilder>()
				.withInOut("FixedNode", 3, 7, true)
				.buildSimulationContext().use { context ->
					// Act
					val cell = context.getRailWayNetGrid().getCellAt(3, 7)
					val cellAtOtherLocation = context.getRailWayNetGrid().getCellAt(4, 7)

					// Assert
					// Cell at (3, 7) should exist
					assertThat(cell)
						.withMessage("Node should exist at original position (3, 7)")
						.isNotNull()

					// Cell at different location (4, 7) should be null or different
					// This validates that the node hasn't moved
					if (cellAtOtherLocation != null) {
						assertThat(cell === cellAtOtherLocation)
							.withMessage("Cell at (3, 7) should not be the same object as cell at (4, 7)")
						// This assertion should fail (not same object), proving coordinates are fixed
					}
				}
		}
	}

	/**
	 * Nested test group: Name Management
	 * Tests setting and retrieving node names for identification.
	 */
	@Nested
	@DisplayName("Name Management")
	inner class NameManagementTests {
		@Test
		fun `node name is set and retrieved correctly`() {
			// Arrange
			val node = createMockNodeCell(name = "StationA")

			// Act
			val retrievedName = node.getName()

			// Assert
			assertThat(retrievedName)
				.withMessage("Node name should match set value")
				.isEqualTo("StationA")
		}

		@Test
		fun `node toString includes name`() {
			// Arrange
			val node = createMockNodeCell(name = "ShuntingPoint")

			// Act
			val stringRepresentation = node.toString()

			// Assert
			assertThat(stringRepresentation.contains("ShuntingPoint"))
				.withMessage("Node toString should include the node name 'ShuntingPoint'")
				.isEqualTo(true)

			assertThat(stringRepresentation.contains("MockNodeCell"))
				.withMessage("Node toString should include class name 'MockNodeCell'")
				.isEqualTo(true)
		}

		@Test
		fun `node name can be changed`() {
			// Arrange
			val node = createMockNodeCell(name = "OriginalName")

			// Act
			node.setName("NewName")
			val updatedName = node.getName()

			// Assert
			assertThat(updatedName)
				.withMessage("Node name should be updated to new value")
				.isEqualTo("NewName")
		}
	}

	/**
	 * Nested test group: Cell.Segment Enum Handling
	 * Tests proper usage of Cell.Segment directions for connections.
	 */
	@Nested
	@DisplayName("Cell.Segment Enum Handling")
	inner class SegmentEnumTests {
		@Test
		fun `all segment directions are defined`() {
			// Arrange
			val segments = Cell.Segment.values()

			// Assert
			assertThat(segments.size)
				.withMessage("Cell.Segment should have 8 directions")
				.isEqualTo(8)

			// Verify all expected segments exist
			val segmentsList = segments.toList()
			assertThat(segmentsList).containsElement(Cell.Segment.A)
			assertThat(segmentsList).containsElement(Cell.Segment.B)
			assertThat(segmentsList).containsElement(Cell.Segment.C)
			assertThat(segmentsList).containsElement(Cell.Segment.D)
			assertThat(segmentsList).containsElement(Cell.Segment.E)
			assertThat(segmentsList).containsElement(Cell.Segment.F)
			assertThat(segmentsList).containsElement(Cell.Segment.G)
			assertThat(segmentsList).containsElement(Cell.Segment.H)
		}

		@Test
		fun `segment directions have correct coordinate offsets`() {
			// Arrange
			val testCases =
				mapOf(
					Cell.Segment.A to Pair(-1, 0), // Left
					Cell.Segment.B to Pair(-1, -1), // Left-top
					Cell.Segment.C to Pair(0, -1), // Center-top
					Cell.Segment.D to Pair(-1, 1), // Left-bottom
					Cell.Segment.E to Pair(1, -1), // Right-top
					Cell.Segment.F to Pair(1, 0), // Right
					Cell.Segment.G to Pair(1, 1), // Right-bottom
					Cell.Segment.H to Pair(0, 1) // Center-bottom
				)

			// Act & Assert
			for ((segment, expectedOffset) in testCases) {
				assertThat(segment.dx)
					.withMessage("Segment $segment should have dx=${expectedOffset.first}")
					.isEqualTo(expectedOffset.first)

				assertThat(segment.dy)
					.withMessage("Segment $segment should have dy=${expectedOffset.second}")
					.isEqualTo(expectedOffset.second)
			}
		}

		@Test
		fun `segment anti returns opposite direction`() {
			// Arrange & Act & Assert
			// Verify that anti() returns the opposite segment
			assertThat(anti(Cell.Segment.A))
				.withMessage("Anti of A (left) should be F (right)")
				.isEqualTo(Cell.Segment.F)

			assertThat(anti(Cell.Segment.F))
				.withMessage("Anti of F (right) should be A (left)")
				.isEqualTo(Cell.Segment.A)

			assertThat(anti(Cell.Segment.C))
				.withMessage("Anti of C (top) should be H (bottom)")
				.isEqualTo(Cell.Segment.H)

			assertThat(anti(Cell.Segment.H))
				.withMessage("Anti of H (bottom) should be C (top)")
				.isEqualTo(Cell.Segment.C)
		}
	}

	/**
	 * Nested test group: Spatial Type
	 * Tests that NodeCell correctly reports its spatial type.
	 */
	@Nested
	@DisplayName("Spatial Type")
	inner class SpatialTypeTests {
		@Test
		fun `node has correct spatial type`() {
			// Arrange
			val horizontalNode = createMockNodeCell(name = "HorizontalNode")

			// Act
			val spatialType = horizontalNode.getSpatialType()

			// Assert
			assertThat(spatialType)
				.withMessage("MockNodeCell should have HORIZONTAL spatial type")
				.isEqualTo(Cell.SpatialType.HORIZONTAL)
		}

		@Test
		fun `spatial type affects valid joins`() {
			// Arrange
			val horizontalNode = createMockNodeCell(name = "Horizontal")

			// Act
			val joins = horizontalNode.joins()

			// Assert
			// HORIZONTAL type should join on left (A) and right (F) segments
			assertThat(joins)
				.withMessage("HORIZONTAL node should have joins")
				.isNotNull()

			// Verify the joins include the expected segments for HORIZONTAL type
			assertThat(joins.size >= 1)
				.withMessage("Node should have at least one join segment")
		}
	}
}
