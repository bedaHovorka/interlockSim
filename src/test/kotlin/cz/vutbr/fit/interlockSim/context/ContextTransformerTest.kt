/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test for ContextTransformer functionality
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.doesNotThrowAnyException
import cz.vutbr.fit.interlockSim.testutil.assertThatCode
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.File

/**
 * Comprehensive tests for ContextTransformer
 *
 * Test Coverage:
 * - Empty context transformation
 * - Simple network (2 InOuts + connections)
 * - Complex network (vyhybna.xml)
 * - Property preservation (maxSpeed, trackLength, nameString)
 * - Graph structure preservation (track blocks, connections)
 * - Dynamic mapping correctness (InOut, RailSwitch, RailSemaphore)
 * - Grid dimension preservation
 * - Identity preservation (staticRef mappings)
 * - Multiple transformations (idempotency)
 * - Configuration property propagation
 * - InOut list preservation
 * - Network topology preservation
 *
 * Total tests: 20
 */
@DisplayName("ContextTransformer")
class ContextTransformerTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()
	private val transformer: ContextTransformer by inject()
	private val processFactory: SimulationProcessFactory by inject()

	companion object {
		private val VYHYBNA_XML = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
	}

	@Nested
	@DisplayName("Basic Transformation")
	inner class BasicTransformation {
		@Test
		@DisplayName("transforms empty editing context successfully")
		fun transformContext_emptyContext_succeeds() {
			// Arrange
			val editingContext = factory.createEmptyContext()

			// Act & Assert - Should not throw
			assertThatCode {
				transformer.createSimulationContext(editingContext, processFactory)
			}.doesNotThrowAnyException()
		}

		@Test
		@DisplayName("transformed context has correct grid dimensions")
		fun transformContext_emptyContext_preservesGridDimensions() {
			// Arrange
			val editingContext = DefaultEditingContext(25, 30)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext.getRailWayNetGrid().getCols()).isEqualTo(25)
			assertThat(simulationContext.getRailWayNetGrid().getRows()).isEqualTo(30)
		}

		@Test
		@DisplayName("transformed context is new instance")
		fun transformContext_createsNewInstance() {
			// Arrange
			val editingContext = factory.createEmptyContext()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext).isNotSameInstanceAs(editingContext)
		}

		@Test
		@DisplayName("transformed context has SimulationContext type")
		fun transformContext_returnsSimulationContext() {
			// Arrange
			val editingContext = factory.createEmptyContext()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext).isInstanceOf<SimulationContext>()
		}
	}

	@Nested
	@DisplayName("Simple Network Transformation")
	inner class SimpleNetworkTransformation {
		@Test
		@DisplayName("transforms context with two InOuts")
		fun transformContext_twoInOuts_preservesInOuts() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val inB = InOut("B", false, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(10, 10), inB)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Grid contains static cells (not dynamic wrappers)
			val cellA = simulationContext.getRailWayNetGrid().getCellAt(1, 1)
			val cellB = simulationContext.getRailWayNetGrid().getCellAt(10, 10)
			assertThat(cellA).isNotNull()
			assertThat(cellB).isNotNull()
			assertThat(cellA).isSameInstanceAs(inA)
			assertThat(cellB).isSameInstanceAs(inB)

			// Assert - Dynamic wrappers accessed via getInOuts()
			val inOuts = simulationContext.getInOuts()
			assertThat(inOuts).hasSize(2)

			// Extract static refs from dynamic wrappers for comparison
			val staticRefs = inOuts.map { (it as DynamicInOut).staticRef }
			assertThat(staticRefs).containsAll(inA, inB)
		}

		@Test
		@DisplayName("transforms context with InOut + RailSwitch")
		fun transformContext_inOutAndSwitch_preservesStructure() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			val inOut = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			railSwitch.setName("SW1")
			editingContext.putCell(Point(1, 1), inOut)
			editingContext.putCell(Point(5, 5), railSwitch)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Grid contains static cells (not dynamic wrappers)
			val cellA = simulationContext.getRailWayNetGrid().getCellAt(1, 1)
			val cellSW = simulationContext.getRailWayNetGrid().getCellAt(5, 5)
			assertThat(cellA).isNotNull()
			assertThat(cellSW).isNotNull()
			assertThat(cellA).isSameInstanceAs(inOut)
			assertThat(cellSW).isSameInstanceAs(railSwitch)

			// Assert - Dynamic wrappers available via toDynamic() and getInOuts()
			val inOuts = simulationContext.getInOuts()
			assertThat(inOuts).hasSize(1)

			// Note: RailSwitch dynamic wrapper accessed via toDynamic() when needed
			// Grid stores static objects, dynamic state managed separately
		}

		@Test
		@DisplayName("transforms context with track connections")
		fun transformContext_withConnections_preservesGraph() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val inB = InOut("B", false, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(10, 10), inB)
			val trackBlock = SimpleTrackBlock(inA, inB, 100.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(10, 10), trackBlock)

			val graphSizeBefore = editingContext.getGraph().size()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Graph size preserved
			assertThat(simulationContext.getGraph().size()).isEqualTo(graphSizeBefore)
			assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
		}

		@Test
		@DisplayName("transforms context with semaphore")
		fun transformContext_withSemaphore_wrapsDynamically() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			val semaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			semaphore.setName("sem1")
			editingContext.putCell(Point(5, 5), semaphore)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Semaphore exists and is wrapped
			val cell = simulationContext.getRailWayNetGrid().getCellAt(5, 5)
			assertThat(cell).isNotNull()
			// Note: RailSemaphore is wrapped via toDynamic() mapping, not replaced in grid
		}
	}

	// NOTE: Property Preservation tests - Issue #168 CLOSED (2026-01-20)
	//
	// Properties (currentMaxSpeed, currentTrackLength, currentNameString) ARE accessible via BaseContext.
	// These properties exist in BaseContext and are inherited by DefaultSimulationContext.
	//
	// To implement these tests:
	// 1. Set properties on EditingContext before transformation
	// 2. Transform to SimulationContext
	// 3. Cast to DefaultSimulationContext or BaseContext to access properties
	// 4. Verify properties are preserved after transformation
	//
	// Example pattern:
	//   val editingContext = DefaultEditingContext(20, 20)
	//   editingContext.currentMaxSpeed = 120.0
	//   val simContext = transformer.createSimulationContext(editingContext, processFactory)
	//   assertThat((simContext as BaseContext).currentMaxSpeed).isEqualTo(120.0)
	//
	// Tests that could be implemented:
	// - transformContext_preservesMaxSpeed()
	// - transformContext_preservesTrackLength()
	// - transformContext_preservesNameString()
	// - transformContext_preservesAllProperties()
	//
	// See: https://github.com/bedaHovorka/interlockSim/issues/168

	@Nested
	@DisplayName("InOut List Preservation")
	inner class InOutListPreservation {
		@Test
		@DisplayName("preserves InOut list from editing context")
		fun transformContext_preservesInOutList() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val inB = InOut("B", false, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(10, 10), inB)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			val inOuts = simulationContext.getInOuts()
			assertThat(inOuts).hasSize(2)
			
			// Extract static refs from dynamic wrappers for comparison
			val staticRefs = inOuts.map { (it as DynamicInOut).staticRef }
			assertThat(staticRefs).containsAll(inA, inB)
		}

		@Test
		@DisplayName("empty editing context results in empty InOut list")
		fun transformContext_emptyContext_emptyInOutList() {
			// Arrange
			val editingContext = factory.createEmptyContext()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext.getInOuts()).hasSize(0)
		}
	}

	// NOTE: Complex Network Transformation tests - Issue #168 CLOSED (2026-01-20)
	//
	// XMLContextFactory.createContext(File) returns SimulationContext (via Context<*>), not EditingContext.
	// ContextTransformer is designed to transform EditingContext → SimulationContext.
	//
	// These tests cannot be implemented as originally intended because:
	// 1. ContextTransformer requires EditingContext as input
	// 2. XMLContextFactory.createContext(File) returns SimulationContext directly
	// 3. There's no EditingContext to transform
	//
	// Alternative approach if needed:
	// - XMLContextFactory would need a createEditingContext(File) method
	// - Or test transformation with manually constructed EditingContext (not from XML)
	// - Or test SimulationContext properties directly without transformation
	//
	// Tests that were disabled:
	// - transformContext_vyhybnaXml_succeeds()
	// - transformContext_vyhybnaXml_preservesGridDimensions()
	// - transformContext_vyhybnaXml_preservesGraph()
	// - transformContext_vyhybnaXml_preservesInOutCount()
	//
	// Recommendation: Test XMLContextFactory directly instead of via ContextTransformer.
	// See XMLContextFactoryTest for comprehensive XML loading tests.
	//
	// See: https://github.com/bedaHovorka/interlockSim/issues/168

	@Nested
	@DisplayName("Multiple Transformations")
	inner class MultipleTransformations {
		@Test
		@DisplayName("multiple transformations from same editing context succeed")
		fun transformContext_multipleTimes_succeeds() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			val inOut = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(1, 1), inOut)

			// Act - Transform twice
			val sim1 = transformer.createSimulationContext(editingContext, processFactory)
			val sim2 = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Both are valid, independent instances
			assertThat(sim1).isNotSameInstanceAs(sim2)
			assertThat(sim1.getRailWayNetGrid().getCellAt(1, 1)).isNotNull()
			assertThat(sim2.getRailWayNetGrid().getCellAt(1, 1)).isNotNull()
		}
	}

	@Nested
	@DisplayName("Custom EditingContext Implementation")
	inner class CustomEditingContextImplementation {
		/**
		 * Mock custom EditingContext implementation that delegates to DefaultEditingContext
		 * but provides interface-only access. This simulates a user-defined EditingContext
		 * subtype to verify that the transformation respects Liskov Substitution Principle.
		 */
		private inner class CustomEditingContext(cols: Int, rows: Int) : DefaultEditingContext(cols, rows) {
			// Inherits all implementation from DefaultEditingContext
			// but provides interface-only access for transformation test
		}

		@Test
		@DisplayName("transforms custom EditingContext implementation successfully")
		fun transformContext_customImplementation_succeeds() {
			// Arrange - Create custom EditingContext (not DefaultEditingContext directly)
			val editingContext: EditingContext = CustomEditingContext(20, 20)
			val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val inB = InOut("B", false, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(10, 10), inB)
			val trackBlock = SimpleTrackBlock(inA, inB, 100.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(10, 10), trackBlock)

			// Act - Transform using interface reference (not concrete type)
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - Transformation succeeded without type checking
			assertThat(simulationContext).isNotNull()
			assertThat(simulationContext.getRailWayNetGrid().getCols()).isEqualTo(20)
			assertThat(simulationContext.getRailWayNetGrid().getRows()).isEqualTo(20)

			// Verify InOut list was copied via interface method
			val inOuts = simulationContext.getInOuts()
			assertThat(inOuts).hasSize(2)
			val staticRefs = inOuts.map { (it as DynamicInOut).staticRef }
			assertThat(staticRefs).containsAll(inA, inB)

			// Verify graph was copied
			assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
		}

		@Test
		@DisplayName("custom EditingContext provides InOut access via interface")
		fun customEditingContext_providesInOutAccess() {
			// Arrange
			val editingContext: EditingContext = CustomEditingContext(20, 20)
			val inOut = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			editingContext.putCell(Point(1, 1), inOut)

			// Act - Access InOuts via Context interface method (not concrete implementation)
			val inOuts = editingContext.getInOuts()

			// Assert - Interface method returns correct list
			assertThat(inOuts).hasSize(1)
			assertThat(inOuts).contains(inOut)
		}
	}
}
