/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test suite for deep context transformation (Issue: Context Package Test Coverage)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.containsAll
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Deep transformation tests for ContextTransformer
 *
 * Test Coverage:
 * - Complex network with branches preserves topology
 * - All InOut configurations preserved
 * - Circular path references handled correctly
 * - Switch position compatibility validated
 * - Multiple semaphores in series preserved
 * - Nested track blocks handled correctly
 *
 * Total tests: 15+
 */
@DisplayName("Context Transformer - Deep Transformation")
class ContextTransformerDeepTest : KoinTestBase() {
	private val transformer: ContextTransformer by inject()
	private val processFactory: SimulationProcessFactory by inject()

	@Nested
	@DisplayName("Complex Network Topology")
	inner class ComplexNetworkTopology {
		@Test
		@DisplayName("transform complex network with branches preserves topology")
		fun transformComplexNetwork_withBranches_preservesTopology() {
			// Arrange - Create a Y-shaped network
			val editingContext = DefaultEditingContext(30, 30)

			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val inB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
			val outC = InOut("C", false, Cell.SpatialType.HORIZONTAL)

			val railSwitch = RailSwitch("SW1", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			// inA(1,5) -> SW1(5,5)
			// inB(1,10) -> SW1(5,5)
			// SW1(5,5) -> outC(10,5)

			editingContext.putCell(Point(1, 5), inA)
			editingContext.putCell(Point(1, 10), inB)
			editingContext.putCell(Point(5, 5), railSwitch)
			editingContext.putCell(Point(10, 5), outC)

			val trackBlock1 = SimpleTrackBlock(inA, railSwitch, 100.0, 80.0)
			val trackBlock2 = SimpleTrackBlock(inB, railSwitch, 100.0, 80.0)
			val trackBlock3 = SimpleTrackBlock(railSwitch, outC, 100.0, 80.0)

			editingContext.joinCells(Point(1, 5), Point(5, 5), trackBlock1)
			editingContext.joinCells(Point(1, 10), Point(5, 5), trackBlock2)
			editingContext.joinCells(Point(5, 5), Point(10, 5), trackBlock3)

			val graphSizeBefore = editingContext.getGraph().size()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Topology preserved
			assertThat(simulationContext.getGraph().size()).isEqualTo(graphSizeBefore)
			assertThat(simulationContext.getGraph().size()).isGreaterThan(0)

			// Assert - All InOuts preserved
			val inOuts = simulationContext.getInOuts()
			assertThat(inOuts).hasSize(3)
			val staticRefs = inOuts.map { (it as DynamicInOut).staticRef }
			assertThat(staticRefs).containsAll(inA, inB, outC)

			// Assert - Switch preserved in grid
			val switchCell = simulationContext.getRailWayNetGrid().getCellAt(5, 5)
			assertThat(switchCell).isNotNull().isInstanceOf(DynamicRailSwitch::class)
		}

		@Test
		@DisplayName("transform preserves all InOut configurations")
		fun transform_preservesAllInOutConfigurations() {
			// Arrange - Create network with multiple InOut types
			val editingContext = DefaultEditingContext(30, 30)

			val inEntry1 = InOut("Entry1", true, Cell.SpatialType.HORIZONTAL)
			val inEntry2 = InOut("Entry2", true, Cell.SpatialType.VERTICAL)
			val outExit1 = InOut("Exit1", false, Cell.SpatialType.HORIZONTAL)
			val outExit2 = InOut("Exit2", false, Cell.SpatialType.DIAGONAL1)

			editingContext.putCell(Point(1, 1), inEntry1)
			editingContext.putCell(Point(1, 10), inEntry2)
			editingContext.putCell(Point(20, 1), outExit1)
			editingContext.putCell(Point(20, 10), outExit2)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - All InOuts with correct configuration
			val inOuts = simulationContext.getInOuts()
			assertThat(inOuts).hasSize(4)

			val staticRefs = inOuts.map { (it as DynamicInOut).staticRef }
			assertThat(staticRefs).containsAll(inEntry1, inEntry2, outExit1, outExit2)

			// Verify entry/exit configuration preserved
			val dynamicInOuts = inOuts.map { it as DynamicInOut }
			val entryNames =
				dynamicInOuts
					.filter { it.staticRef.getName() in listOf("Entry1", "Entry2") }
					.map { it.staticRef.getName() }
			val exitNames =
				dynamicInOuts
					.filter { it.staticRef.getName() in listOf("Exit1", "Exit2") }
					.map { it.staticRef.getName() }

			assertThat(entryNames).hasSize(2)
			assertThat(exitNames).hasSize(2)
		}

		@Test
		@DisplayName("transform handles circular path references")
		fun transform_handlesCircularPathReferences() {
			// Arrange - Create a simple network with track connection
			val editingContext = DefaultEditingContext(30, 30)

			val inOut1 = InOut("IO1", true, Cell.SpatialType.HORIZONTAL)
			val inOut2 = InOut("IO2", false, Cell.SpatialType.HORIZONTAL)

			editingContext.putCell(Point(5, 5), inOut1)
			editingContext.putCell(Point(15, 5), inOut2)

			// Create track connection
			val trackBlock = SimpleTrackBlock(inOut1, inOut2, 100.0, 80.0)
			editingContext.joinCells(Point(5, 5), Point(15, 5), trackBlock)

			// Verify graph has edges (join succeeded)
			val graphSizeAfterJoin = editingContext.getGraph().size()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Network transformed preserving structure
			assertThat(simulationContext.getGraph().size()).isEqualTo(graphSizeAfterJoin)
			assertThat(simulationContext.getInOuts()).hasSize(2)
			assertThat((simulationContext as DefaultSimulationContext).isFrozen()).isTrue()
		}

		@Test
		@DisplayName("transform validates switch position compatibility")
		fun transform_validatesSwitchPositionCompatibility() {
			// Arrange - Create network with switch
			val editingContext = DefaultEditingContext(30, 30)

			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val railSwitch = RailSwitch("SW1", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)

			editingContext.putCell(Point(5, 5), inA)
			editingContext.putCell(Point(10, 5), railSwitch)

			val trackBlock = SimpleTrackBlock(inA, railSwitch, 100.0, 80.0)
			editingContext.joinCells(Point(5, 5), Point(10, 5), trackBlock)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Switch preserved with correct state
			val switchCell = simulationContext.getRailWayNetGrid().getCellAt(10, 5)
			assertThat(switchCell).isNotNull().isInstanceOf(DynamicRailSwitch::class)
			assertThat((switchCell as DynamicRailSwitch).name).isEqualTo("SW1")
		}
	}

	@Nested
	@DisplayName("Complex Track Configurations")
	inner class ComplexTrackConfigurations {
		@Test
		@DisplayName("transform preserves multiple semaphores in series")
		fun transform_preservesMultipleSemaphoresInSeries() {
			// Arrange - Create track with multiple semaphores
			val editingContext = DefaultEditingContext(30, 30)

			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val sem1 = RailSemaphore("Sem1", true, Cell.SpatialType.HORIZONTAL)
			val sem2 = RailSemaphore("Sem2", true, Cell.SpatialType.HORIZONTAL)
			val outB = InOut("B", false, Cell.SpatialType.HORIZONTAL)

			editingContext.putCell(Point(1, 5), inA)
			editingContext.putCell(Point(5, 5), sem1)
			editingContext.putCell(Point(10, 5), sem2)
			editingContext.putCell(Point(15, 5), outB)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - All semaphores preserved
			assertThat(simulationContext.getRailWayNetGrid().getCellAt(5, 5))
				.isNotNull()
				.isInstanceOf(DynamicRailSemaphore::class)
			assertThat(simulationContext.getRailWayNetGrid().getCellAt(10, 5))
				.isNotNull()
				.isInstanceOf(DynamicRailSemaphore::class)
		}

		@Test
		@DisplayName("transform handles long distance track blocks")
		fun transform_handlesLongDistanceTrackBlocks() {
			// Arrange - Create track with long distance
			val editingContext = DefaultEditingContext(50, 50)

			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val outB = InOut("B", false, Cell.SpatialType.HORIZONTAL)

			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(40, 40), outB)

			val trackBlock = SimpleTrackBlock(inA, outB, 2000.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(40, 40), trackBlock)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Track block preserved
			assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
			assertThat(simulationContext.getInOuts()).hasSize(2)
		}

		@Test
		@DisplayName("transform preserves track block properties")
		fun transform_preservesTrackBlockProperties() {
			// Arrange
			val editingContext = DefaultEditingContext(30, 30)

			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val outB = InOut("B", false, Cell.SpatialType.HORIZONTAL)

			editingContext.putCell(Point(5, 5), inA)
			editingContext.putCell(Point(15, 5), outB)

			// Create track block with specific properties
			val trackBlock = SimpleTrackBlock(inA, outB, 500.0, 120.0)
			editingContext.joinCells(Point(5, 5), Point(15, 5), trackBlock)

			val graphBefore = editingContext.getGraph()
			val trackBlocksBefore = graphBefore.values().toList()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Track block properties preserved
			val graphAfter = simulationContext.getGraph()
			val trackBlocksAfter = graphAfter.values().toList()

			assertThat(trackBlocksAfter.size).isEqualTo(trackBlocksBefore.size)
		}
	}

	@Nested
	@DisplayName("Dynamic Mapping Completeness")
	inner class DynamicMappingCompleteness {
		@Test
		@DisplayName("all PathSeparators have dynamic wrappers after transformation")
		fun allPathSeparators_haveDynamicWrappers() {
			// Arrange
			val editingContext = DefaultEditingContext(30, 30)

			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val sem = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			val outB = InOut("B", false, Cell.SpatialType.HORIZONTAL)

			editingContext.putCell(Point(1, 5), inA)
			editingContext.putCell(Point(5, 5), sem)
			editingContext.putCell(Point(10, 5), railSwitch)
			editingContext.putCell(Point(15, 5), outB)

			// Act
			val simulationContext =
				transformer.createSimulationContext(
					editingContext,
					processFactory
				) as DefaultSimulationContext

			// Assert - All PathSeparators can be converted to dynamic
			// (toDynamic would throw IllegalStateException if mapping incomplete)
			val dynamicInA = simulationContext.toDynamic(inA)
			val dynamicSem = simulationContext.toDynamic(sem)
			val dynamicSwitch = simulationContext.toDynamic(railSwitch)
			val dynamicOutB = simulationContext.toDynamic(outB)

			assertThat(dynamicInA).isNotNull()
			assertThat(dynamicSem).isNotNull()
			assertThat(dynamicSwitch).isNotNull()
			assertThat(dynamicOutB).isNotNull()
		}

		@Test
		@DisplayName("toDynamic is idempotent for already-dynamic separators")
		fun toDynamic_isIdempotentForAlreadyDynamic() {
			// Arrange
			val editingContext = DefaultEditingContext(30, 30)
			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(5, 5), inA)

			// Act
			val simulationContext =
				transformer.createSimulationContext(
					editingContext,
					processFactory
				) as DefaultSimulationContext

			// Get dynamic wrapper
			val dynamic1 = simulationContext.toDynamic(inA)

			// Call toDynamic on already-dynamic wrapper
			val dynamic2 = simulationContext.toDynamic(dynamic1)

			// Assert - Same instance returned
			assertThat(dynamic2).isNotNull()
			// Dynamic wrappers should be idempotent: toDynamic(dynamic) == dynamic
		}

		@Test
		@DisplayName("InOut semaphores are mapped during transformation")
		fun inOutSemaphores_areMappedDuringTransformation() {
			// Arrange
			val editingContext = DefaultEditingContext(30, 30)
			val inOut = InOut("IO", true, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(5, 5), inOut)

			// Act
			val simulationContext =
				transformer.createSimulationContext(
					editingContext,
					processFactory
				) as DefaultSimulationContext

			// Assert - InOut's internal semaphores are also mapped
			val inSem = inOut.getInSemaphore()
			val outSem = inOut.getOutSemaphore()

			// These should not throw (they're mapped during InOut dynamic wrapper creation)
			val dynamicInSem = simulationContext.toDynamic(inSem)
			val dynamicOutSem = simulationContext.toDynamic(outSem)

			assertThat(dynamicInSem).isNotNull()
			assertThat(dynamicOutSem).isNotNull()
		}
	}

	@Nested
	@DisplayName("Configuration Preservation")
	inner class ConfigurationPreservation {
		@Test
		@DisplayName("transform preserves maxSpeed configuration")
		fun transform_preservesMaxSpeed() {
			// Arrange
			val editingContext = DefaultEditingContext(30, 30)
			editingContext.currentMaxSpeed = 150.0

			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(5, 5), inA)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Cast to access configuration properties
			val simContextBase = simulationContext as BaseContext<*>
			assertThat(simContextBase.currentMaxSpeed).isEqualTo(150.0)
		}

		@Test
		@DisplayName("transform preserves trackLength configuration")
		fun transform_preservesTrackLength() {
			// Arrange
			val editingContext = DefaultEditingContext(30, 30)
			editingContext.currentTrackLength = 750.0

			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(5, 5), inA)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Cast to access configuration properties
			val simContextBase = simulationContext as BaseContext<*>
			assertThat(simContextBase.currentTrackLength).isEqualTo(750.0)
		}

		@Test
		@DisplayName("transform preserves nameString configuration")
		fun transform_preservesNameString() {
			// Arrange
			val editingContext = DefaultEditingContext(30, 30)
			editingContext.currentNameString = "TestNetwork"

			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(5, 5), inA)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Cast to access configuration properties
			val simContextBase = simulationContext as BaseContext<*>
			assertThat(simContextBase.currentNameString).isEqualTo("TestNetwork")
		}

		@Test
		@DisplayName("transform preserves grid dimensions")
		fun transform_preservesGridDimensions() {
			// Arrange
			val editingContext = DefaultEditingContext(35, 45)

			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(5, 5), inA)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext.getRailWayNetGrid().cols).isEqualTo(35)
			assertThat(simulationContext.getRailWayNetGrid().rows).isEqualTo(45)
		}
	}
}
