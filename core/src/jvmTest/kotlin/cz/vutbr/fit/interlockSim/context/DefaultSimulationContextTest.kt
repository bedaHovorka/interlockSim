/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.assertThatCode
import cz.vutbr.fit.interlockSim.testutil.buildLinearTrack
import cz.vutbr.fit.interlockSim.testutil.buildLinearTrackWithSemaphore
import cz.vutbr.fit.interlockSim.testutil.buildMinimalSimulation
import cz.vutbr.fit.interlockSim.testutil.doesNotThrowAnyException
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.get
import org.koin.test.inject

/**
 * Comprehensive unit tests for {@link DefaultSimulationContext}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Grid operations: putCell, removeCell, moveCell</li>
 *   <li>Track navigation: getNextTrackBlock, getNextTrackSection</li>
 *   <li>Path operations: pathToNextSemaphore, isSeparatorInDirection</li>
 *   <li>Configuration: maxSpeed, trackLength settings</li>
 *   <li>Report management: addReportTypes, isReporting</li>
 * </ul>
 */
@DisplayName("DefaultSimulationContext")
class DefaultSimulationContextTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	@Nested
	@DisplayName("Grid Operations - Immutability Enforcement")
	inner class GridOperationsTests {
		private lateinit var context: SimulationContext

		@BeforeEach
		fun setUp() {
			context = simulationContextFactory.createEmptyContext().tracked()
		}

		// Note: Editing operation tests removed after Issue #153.5
		// SimulationContext no longer inherits from EditingContext,
		// so editing methods (putCell, removeCell, moveCell, removeLine, joinCells)
		// are not available on SimulationContext. This enforces architectural
		// separation and Interface Segregation Principle.

		@Test
		@DisplayName("getRailWayNetGrid returns grid for read-only access")
		fun getRailWayNetGrid_returnsGrid() {
			// Act
			val grid = context.getRailWayNetGrid()

			// Assert
			assertThat(grid).isNotNull()
			assertThat(grid.cols).isGreaterThan(0)
			assertThat(grid.rows).isGreaterThan(0)
		}
	}

	@Nested
	@DisplayName("Configuration Properties")
	inner class ConfigurationTests {
		private lateinit var context: DefaultSimulationContext

		@BeforeEach
		fun setUp() {
			context = (simulationContextFactory.createEmptyContext() as DefaultSimulationContext).tracked()
		}

		@Test
		@DisplayName("currentMaxSpeed can be read and set")
		fun currentMaxSpeed_canBeAccessedAndModified() {
			// Arrange
			val newSpeed = 120.0

			// Act
			context.currentMaxSpeed = newSpeed

			// Assert
			assertThat(context.currentMaxSpeed).isEqualTo(newSpeed)
		}

		@Test
		@DisplayName("currentTrackLength can be read and set")
		fun currentTrackLength_canBeAccessedAndModified() {
			// Arrange
			val newLength = 250.0

			// Act
			context.currentTrackLength = newLength

			// Assert
			assertThat(context.currentTrackLength).isEqualTo(newLength)
		}

		@Test
		@DisplayName("currentNameString can be read and set")
		fun currentNameString_canBeAccessedAndModified() {
			// Arrange
			val newName = "TestTrain"

			// Act
			context.currentNameString = newName

			// Assert
			assertThat(context.currentNameString).isEqualTo(newName)
		}

		@Test
		@DisplayName("currentNameString generates random name when empty")
		fun currentNameString_generatesRandomNameWhenEmpty() {
			// Arrange - set to empty
			context.currentNameString = ""

			// Act
			val randomName = context.currentNameString

			// Assert - should be a single character A-T
			assertThat(randomName).isNotNull()
			assertThat(randomName.length).isEqualTo(1)
			assertThat(randomName[0])
				.prop("character code") { it.code }
				.isGreaterThan(64) // 'A' is 65
		}
	}

	@Nested
	@DisplayName("Report Management")
	inner class ReportManagementTests {
		private lateinit var context: SimulationContext

		@BeforeEach
		fun setUp() {
			context = simulationContextFactory.createEmptyContext().tracked()
		}

		@Test
		@DisplayName("addReportTypes enables reporting for specified types")
		fun addReportTypes_enablesReporting() {
			// Arrange
			val reportType = SimulationContext.ReportType.TRAIN_EVENTS

			// Act
			context.addReportTypes(reportType)

			// Assert
			assertThat(context.isReporting(reportType)).isTrue()
		}

		@Test
		@DisplayName("removeReportTypes disables reporting for specified types")
		fun removeReportTypes_disablesReporting() {
			// Arrange
			val reportType = SimulationContext.ReportType.TRAIN_EVENTS
			context.addReportTypes(reportType)

			// Act
			context.removeReportTypes(reportType)

			// Assert
			assertThat(context.isReporting(reportType)).isFalse()
		}

		@Test
		@DisplayName("report does nothing when report type not enabled")
		fun report_doesNothingWhenTypeNotEnabled() {
			// Arrange
			val reportType = SimulationContext.ReportType.TRAIN_EVENTS

			// Act & Assert - should not throw
			assertThatCode {
				context.report("Test report", this, reportType)
			}.doesNotThrowAnyException()
		}
	}

	// Remove old grid operation tests that tested editing functionality
	// Keep only simulation-specific tests below this line

	@Nested
	@DisplayName("Track Navigation")
	inner class TrackNavigationTests {
		private lateinit var context: DefaultSimulationContext
		private lateinit var inA: InOut
		private lateinit var rs1: RailSemaphore
		private lateinit var rs2: RailSemaphore
		private lateinit var outB: InOut
		private lateinit var tl1: SimpleTrackBlock
		private lateinit var tl2: SimpleTrackBlock

		@BeforeEach
		fun setUp() {
			// Create a multi-block track using EditingContext, then convert to SimulationContext
			// InOut-A -> RS1 -> RS2 -> InOut-B
			val editingContext =
				cz.vutbr.fit.interlockSim.context
					.DefaultEditingContext(30, 30)
			inA = InOut("A", false, SpatialType.HORIZONTAL)
			rs1 = RailSemaphore(false, SpatialType.DIAGONAL1)
			rs2 = RailSemaphore(false, SpatialType.HORIZONTAL)
			outB = InOut("B", true, SpatialType.HORIZONTAL)
			tl1 = SimpleTrackBlock(inA, rs1, 1000.0, 80.0)
			tl2 = SimpleTrackBlock(rs2, outB, 1000.0, 80.0)

			val pA = Point(1, 1)
			val r1 = Point(4, 2)
			val r2 = Point(6, 3)
			val pB = Point(8, 5)

			// Build network using editing context
			editingContext.putCell(pA, inA)
			editingContext.putCell(r1, rs1)
			editingContext.putCell(r2, rs2)
			editingContext.putCell(pB, outB)
			editingContext.joinCells(pA, r1, tl1)
			editingContext.joinCells(r2, pB, tl2)

			// Convert to simulation context
			val processFactory =
				org.koin.java.KoinJavaComponent.get<cz.vutbr.fit.interlockSim.context.SimulationProcessFactory>(
					cz.vutbr.fit.interlockSim.context.SimulationProcessFactory::class.java
				)
			context = DefaultSimulationContext.fromEditingContext(editingContext, processFactory).tracked()
		}

		@Test
		@DisplayName("getNextTrackBlock from InOut with null returns block")
		fun getNextTrackBlock_fromInOutWithNull_returnsTrackBlock() {
			// Get dynamic blocks from simulation context
			val dynamicBlock1 = context.getNextTrackBlock(inA, null)
			val dynamicBlock2 = context.getNextTrackBlock(outB, null)

			assertThat(dynamicBlock1).isNotNull()
			assertThat(dynamicBlock2).isNotNull()
		}

		@Test
		@DisplayName("getNextTrackBlock from InOut with current block returns null")
		fun getNextTrackBlock_fromInOutWithBlock_returnsNull() {
			// Get dynamic blocks from simulation context
			val dynamicBlock1 = context.getNextTrackBlock(inA, null)
			val dynamicBlock2 = context.getNextTrackBlock(outB, null)

			assertThat(context.getNextTrackBlock(inA, dynamicBlock1)).isNull()
			assertThat(context.getNextTrackBlock(outB, dynamicBlock2)).isNull()
		}

		@Test
		@DisplayName("isSeparatorInDirection validates direction correctly")
		fun isSeparatorInDirection_validDirections_returnsTrue() {
			// Get dynamic block from simulation context
			val dynamicBlock = context.getNextTrackBlock(inA, null)

			// Test with proper track blocks that have direction information
			assertThat(context.isSeparatorInDirection(inA, dynamicBlock, dynamicBlock)).isTrue()
		}
	}

	@Nested
	@DisplayName("Path Operations")
	inner class PathOperationsTests {
		private lateinit var context: SimulationContext
		private lateinit var inA: InOut
		private lateinit var rs1: RailSemaphore
		private lateinit var outB: InOut
		private lateinit var tl: SimpleTrackBlock

		@BeforeEach
		fun setUp() {
			// Create a simple track: InOut-A -> Semaphore -> InOut-B
			// Build with editing context first
			val editingContext = editingContextFactory.createEmptyContext()
			inA = InOut("A", false, SpatialType.HORIZONTAL)
			rs1 = RailSemaphore(false, SpatialType.DIAGONAL1)
			outB = InOut("B", true, SpatialType.HORIZONTAL)
			tl = SimpleTrackBlock(inA, rs1, 1000.0, 80.0)

			val pA = Point(1, 1)
			val r1 = Point(4, 2)
			val pB = Point(5, 5)

			editingContext.putCell(pA, inA)
			editingContext.putCell(r1, rs1)
			editingContext.putCell(pB, outB)
			editingContext.joinCells(pA, r1, tl)

			// Convert to simulation context for testing
			context = this@DefaultSimulationContextTest.simulationContextFactory.createContext(editingContext).tracked()
			// Trigger lazy initialization of dynamic wrappers
			context.getInOuts()
		}
	}

	@Nested
	@DisplayName("Configuration Management")
	inner class ConfigurationManagementTests {
		private lateinit var context: DefaultSimulationContext

		@BeforeEach
		fun setUp() {
			context = (simulationContextFactory.createEmptyContext() as DefaultSimulationContext).tracked()
		}

		@Test
		@DisplayName("setCurrentMaxSpeed updates max speed setting")
		fun setCurrentMaxSpeed_validValue_updates() {
			// Arrange
			val newSpeed = 120.0

			// Act
			context.currentMaxSpeed = newSpeed

			// Assert
			assertThat(context)
				.prop(DefaultSimulationContext::currentMaxSpeed)
				.isEqualTo(newSpeed)
		}

		@Test
		@DisplayName("setCurrentTrackLength updates track length setting")
		fun setCurrentTrackLength_validValue_updates() {
			// Arrange
			val newLength = 500.0

			// Act
			context.currentTrackLength = newLength

			// Assert
			assertThat(context)
				.prop(DefaultSimulationContext::currentTrackLength)
				.isEqualTo(newLength)
		}

		@Test
		@DisplayName("setCurrentNameString updates context name")
		fun setCurrentNameString_validName_updates() {
			// Arrange
			val name = "Test Network"

			// Act
			context.currentNameString = name

			// Assert
			assertThat(context)
				.prop(DefaultSimulationContext::currentNameString)
				.isEqualTo(name)
		}
	}

	@Nested
	@DisplayName("Report Management")
	inner class ReportManagementTests2 {
		private lateinit var context: SimulationContext

		@BeforeEach
		fun setUp() {
			context = simulationContextFactory.createEmptyContext().tracked()
		}

		@Test
		@DisplayName("addReportTypes enables reporting for specified types")
		fun addReportTypes_validTypes_enablesReporting() {
			// Act
			context.addReportTypes(SimulationContext.ReportType.PATH_SETTING, SimulationContext.ReportType.NODE_EVENTS)

			// Assert
			assertThat(context.isReporting(SimulationContext.ReportType.PATH_SETTING)).isTrue()
			assertThat(context.isReporting(SimulationContext.ReportType.NODE_EVENTS)).isTrue()
		}

		@Test
		@DisplayName("removeReportTypes disables reporting for specified types")
		fun removeReportTypes_previouslyEnabled_disablesReporting() {
			// Arrange
			context.addReportTypes(SimulationContext.ReportType.PATH_SETTING, SimulationContext.ReportType.NODE_EVENTS)

			// Act
			context.removeReportTypes(SimulationContext.ReportType.PATH_SETTING)

			// Assert
			assertThat(context.isReporting(SimulationContext.ReportType.PATH_SETTING)).isFalse()
			assertThat(context.isReporting(SimulationContext.ReportType.NODE_EVENTS)).isTrue()
		}

		@Test
		@DisplayName("isReporting returns false for never-enabled type")
		fun isReporting_neverEnabled_returnsFalse() {
			// Act & Assert
			assertThat(context.isReporting(SimulationContext.ReportType.TRAIN_CONTINUOUS)).isFalse()
		}
	}

	@Nested
	@DisplayName("TestContextBuilder Integration")
	inner class TestContextBuilderIntegrationTests {
		@Test
		@DisplayName("buildLinearTrack creates valid context")
		fun buildLinearTrack_createsValidContext() {
			// Act
			val context = buildLinearTrack()

			// Assert
			assertThat(context).isNotNull()
			assertThat(context.getRailWayNetGrid().getCellAt(1, 1)).isNotNull().isInstanceOf(DynamicInOut::class)
			assertThat(context.getRailWayNetGrid().getCellAt(5, 5)).isNotNull().isInstanceOf(DynamicInOut::class)
		}

		@Test
		@DisplayName("buildLinearTrackWithSemaphore includes semaphore")
		fun buildLinearTrackWithSemaphore_includesSemaphore() {
			// Act
			val context = buildLinearTrackWithSemaphore()

			// Assert
			assertThat(context).isNotNull()
			val semaphoreCell = context.getRailWayNetGrid().getCellAt(4, 2)
			assertThat(semaphoreCell).isNotNull().isInstanceOf(DynamicRailSemaphore::class)
		}

		@Test
		@DisplayName("buildMinimal creates single InOut context")
		fun buildMinimal_createsSingleInOut() {
			// Act
			val context = buildMinimalSimulation()

			// Assert
			assertThat(context).isNotNull()
			assertThat(context.getRailWayNetGrid().getCellAt(1, 1)).isNotNull().isInstanceOf(DynamicInOut::class)
		}

		@Test
		@DisplayName("fluent API creates custom context")
		fun fluentAPI_customContext_works() {
			// Act
			get<TestContextBuilder>()
				.withInOut("Entry", 2, 2, false)
				.withSemaphore(5, 5, true)
				.withInOut("Exit", 8, 8, true)
				.buildSimulationContext()
				.use { context ->
					// Assert
					assertThat(context).isNotNull()
					assertThat(context.getRailWayNetGrid().getCellAt(2, 2)).isNotNull().isInstanceOf(DynamicInOut::class)
					assertThat(context.getRailWayNetGrid().getCellAt(5, 5)).isNotNull().isInstanceOf(DynamicRailSemaphore::class)
					assertThat(context.getRailWayNetGrid().getCellAt(8, 8)).isNotNull().isInstanceOf(DynamicInOut::class)
				}
		}
	}

	@Nested
	@DisplayName("Grid Consistency - Issue #38")
	inner class GridConsistencyTests {
		private lateinit var context: EditingContext

		@BeforeEach
		fun setUp() {
			context = editingContextFactory.createEmptyContext().tracked()
		}

		@Test
		@DisplayName("joinCells maintains reverse table consistency")
		fun joinCells_maintainsReverseTableConsistency() {
			// Arrange - create two nodes far apart that require intermediate cells
			val inA = InOut("A", false, SpatialType.HORIZONTAL)
			val inB = InOut("B", true, SpatialType.HORIZONTAL)
			val trackBlock = SimpleTrackBlock(inA, inB, 1000.0, 80.0)
			val pointA = Point(1, 1)
			val pointB = Point(10, 10)

			context.putCell(pointA, inA)
			context.putCell(pointB, inB)

			// Act - join cells (creates intermediate TrackBlockPart cells)
			context.joinCells(pointA, pointB, trackBlock)

			// Assert - all intermediate points should pass containsKey check without assertion error
			// This is the scenario described in issue #38
			assertThatCode {
				for (x in 0 until context.getRailWayNetGrid().cols) {
					for (y in 0 until context.getRailWayNetGrid().rows) {
						val point = Point(x, y)
						context.getRailWayNetGrid().containsKey(point)
					}
				}
			}.doesNotThrowAnyException()
		}

		@Test
		@DisplayName("removeLine after joinCells maintains reverse table consistency")
		fun removeLine_afterJoinCells_maintainsReverseTableConsistency() {
			// Arrange - create joined cells
			val inA = InOut("A", false, SpatialType.HORIZONTAL)
			val inB = InOut("B", true, SpatialType.HORIZONTAL)
			val trackBlock = SimpleTrackBlock(inA, inB, 1000.0, 80.0)
			val pointA = Point(1, 1)
			val pointB = Point(10, 10)

			context.putCell(pointA, inA)
			context.putCell(pointB, inB)

			// Capture intermediate cells before removal
			val cellsBeforeRemoval = mutableListOf<Point>()
			for (x in 0 until context.getRailWayNetGrid().cols) {
				for (y in 0 until context.getRailWayNetGrid().rows) {
					val point = Point(x, y)
					if (context.getRailWayNetGrid().getCellAt(x, y) != null) {
						cellsBeforeRemoval.add(point)
					}
				}
			}

			context.joinCells(pointA, pointB, trackBlock)

			// Act - remove the track line (should clean up intermediate cells)
			context.removeLine(trackBlock)

			// Assert - intermediate cells should be removed (only original nodes remain)
			for (x in 0 until context.getRailWayNetGrid().cols) {
				for (y in 0 until context.getRailWayNetGrid().rows) {
					val point = Point(x, y)
					val cell = context.getRailWayNetGrid().getCellAt(x, y)
					if (point != pointA && point != pointB) {
						// Intermediate cells should be removed
						if (!cellsBeforeRemoval.contains(point)) {
							assertThat(cell).withMessage("Intermediate cell at ($x,$y) should be removed").isNull()
						}
					}
				}
			}

			// Assert - all points should pass containsKey check without assertion error
			assertThatCode {
				for (x in 0 until context.getRailWayNetGrid().cols) {
					for (y in 0 until context.getRailWayNetGrid().rows) {
						val point = Point(x, y)
						context.getRailWayNetGrid().containsKey(point)
					}
				}
			}.doesNotThrowAnyException()
		}

		@Test
		@DisplayName("removeCell on node with tracks maintains reverse table consistency")
		fun removeCell_nodeWithTracks_maintainsReverseTableConsistency() {
			// Arrange - create connected nodes
			val inA = InOut("A", false, SpatialType.HORIZONTAL)
			val inB = InOut("B", true, SpatialType.HORIZONTAL)
			val trackBlock = SimpleTrackBlock(inA, inB, 1000.0, 80.0)
			val pointA = Point(1, 1)
			val pointB = Point(10, 10)

			context.putCell(pointA, inA)
			context.putCell(pointB, inB)
			context.joinCells(pointA, pointB, trackBlock)

			// Act - remove one of the nodes (should cascade remove track and intermediate cells)
			context.removeCell(pointA)

			// Assert - removed node should not be present
			assertThat(context.getRailWayNetGrid().getCellAt(pointA.x, pointA.y))
				.withMessage("Removed node at pointA should be null")
				.isNull()
			assertThat(context.getRailWayNetGrid().getLocation(inA))
				.withMessage("Removed node inA should not be in reverse table")
				.isNull()

			// Assert - all points should pass containsKey check without assertion error
			assertThatCode {
				for (x in 0 until context.getRailWayNetGrid().cols) {
					for (y in 0 until context.getRailWayNetGrid().rows) {
						val point = Point(x, y)
						context.getRailWayNetGrid().containsKey(point)
					}
				}
			}.doesNotThrowAnyException()
		}

		@Test
		@DisplayName("multiple joinCells and removals maintain consistency")
		fun multipleJoinsAndRemovals_maintainConsistency() {
			// Arrange - create a small network
			val inA = InOut("A", false, SpatialType.HORIZONTAL)
			val rs1 = RailSemaphore(false, SpatialType.DIAGONAL1)
			val inB = InOut("B", true, SpatialType.HORIZONTAL)
			val track1 = SimpleTrackBlock(inA, rs1, 500.0, 80.0)
			val track2 = SimpleTrackBlock(rs1, inB, 500.0, 80.0)

			val pointA = Point(1, 1)
			val pointS = Point(5, 5)
			val pointB = Point(10, 10)

			context.putCell(pointA, inA)
			context.putCell(pointS, rs1)
			context.putCell(pointB, inB)

			// Act - join, remove, join again
			context.joinCells(pointA, pointS, track1)
			context.joinCells(pointS, pointB, track2)
			context.removeLine(track1)
			context.removeLine(track2)

			// Assert - only the original nodes should remain
			assertThat(context.getRailWayNetGrid().getCellAt(pointA.x, pointA.y))
				.withMessage("Node inA should still be present")
				.isSameInstanceAs(inA)
			assertThat(context.getRailWayNetGrid().getCellAt(pointS.x, pointS.y))
				.withMessage("Node rs1 should still be present")
				.isSameInstanceAs(rs1)
			assertThat(context.getRailWayNetGrid().getCellAt(pointB.x, pointB.y))
				.withMessage("Node inB should still be present")
				.isSameInstanceAs(inB)

			// Verify intermediate cells between nodes are removed
			var intermediateCount = 0
			for (x in 0 until context.getRailWayNetGrid().cols) {
				for (y in 0 until context.getRailWayNetGrid().rows) {
					val point = Point(x, y)
					if (point != pointA && point != pointS && point != pointB) {
						val cell = context.getRailWayNetGrid().getCellAt(x, y)
						if (cell != null) {
							intermediateCount++
						}
					}
				}
			}
			assertThat(intermediateCount)
				.withMessage("Intermediate cells should be removed after removeLine")
				.isEqualTo(0)

			// Assert - grid should be consistent after all operations
			assertThatCode {
				for (x in 0 until context.getRailWayNetGrid().cols) {
					for (y in 0 until context.getRailWayNetGrid().rows) {
						val point = Point(x, y)
						context.getRailWayNetGrid().containsKey(point)
					}
				}
			}.doesNotThrowAnyException()
		}
	}
}
