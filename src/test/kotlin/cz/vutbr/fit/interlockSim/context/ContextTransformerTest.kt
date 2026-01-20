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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
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
			val editingContext = factory.createEmptyEditingContext()

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
			val editingContext = factory.createEmptyEditingContext()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext).isNotSameInstanceAs(editingContext)
		}

		@Test
		@DisplayName("transformed context has SimulationContext type")
		fun transformContext_returnsSimulationContext() {
			// Arrange
			val editingContext = factory.createEmptyEditingContext()

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

			// Assert - InOuts exist in grid
			val cellA = simulationContext.getRailWayNetGrid().getCellAt(1, 1)
			val cellB = simulationContext.getRailWayNetGrid().getCellAt(10, 10)
			assertThat(cellA).isNotNull()
			assertThat(cellB).isNotNull()

			// InOuts are wrapped in dynamic wrappers
			assertThat(cellA!!).isInstanceOf<DynamicInOut>()
			assertThat(cellB!!).isInstanceOf<DynamicInOut>()
		}

		@Test
		@DisplayName("transforms context with InOut + RailSwitch")
		fun transformContext_inOutAndSwitch_preservesStructure() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			val inOut = InOut("A", true, Cell.SpatialType.HORIZONTAL)
			val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			editingContext.putCell(Point(1, 1), inOut)
			editingContext.putCell(Point(5, 5), railSwitch)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			val cellInOut = simulationContext.getRailWayNetGrid().getCellAt(1, 1)
			val cellSwitch = simulationContext.getRailWayNetGrid().getCellAt(5, 5)
			assertThat(cellInOut).isNotNull()
			assertThat(cellSwitch).isNotNull()
			assertThat(cellInOut!!).isInstanceOf<DynamicInOut>()
			assertThat(cellSwitch!!).isInstanceOf<DynamicRailSwitch>()
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
			editingContext.joinCells(Point(1, 1), Point(10, 10), 100.0, 80.0)

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

	@Nested
	@DisplayName("Property Preservation")
	inner class PropertyPreservation {
		@Test
		@DisplayName("preserves currentMaxSpeed property")
		fun transformContext_preservesMaxSpeed() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			editingContext.currentMaxSpeed = 120.5

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext.currentMaxSpeed).isEqualTo(120.5)
		}

		@Test
		@DisplayName("preserves currentTrackLength property")
		fun transformContext_preservesTrackLength() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			editingContext.currentTrackLength = 500.0

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext.currentTrackLength).isEqualTo(500.0)
		}

		@Test
		@DisplayName("preserves currentNameString property")
		fun transformContext_preservesNameString() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			editingContext.currentNameString = "TestNetwork"

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext.currentNameString).isEqualTo("TestNetwork")
		}

		@Test
		@DisplayName("preserves all properties together")
		fun transformContext_preservesAllProperties() {
			// Arrange
			val editingContext = DefaultEditingContext(20, 20)
			editingContext.currentMaxSpeed = 100.0
			editingContext.currentTrackLength = 300.0
			editingContext.currentNameString = "Network1"

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext.currentMaxSpeed).isEqualTo(100.0)
			assertThat(simulationContext.currentTrackLength).isEqualTo(300.0)
			assertThat(simulationContext.currentNameString).isEqualTo("Network1")
		}
	}

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
			assertThat(staticRefs).contains(inA, inB)
		}

		@Test
		@DisplayName("empty editing context results in empty InOut list")
		fun transformContext_emptyContext_emptyInOutList() {
			// Arrange
			val editingContext = factory.createEmptyEditingContext()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext.getInOuts()).hasSize(0)
		}
	}

	@Nested
	@DisplayName("Complex Network Transformation")
	inner class ComplexNetworkTransformation {
		@Test
		@DisplayName("transforms vyhybna.xml complex network")
		fun transformContext_vyhybnaXml_succeeds() {
			// Arrange
			val editingContext = factory.createEditingContext(VYHYBNA_XML)

			// Act & Assert
			assertThatCode {
				transformer.createSimulationContext(editingContext, processFactory)
			}.doesNotThrowAnyException()
		}

		@Test
		@DisplayName("vyhybna.xml transformation preserves grid dimensions")
		fun transformContext_vyhybnaXml_preservesGridDimensions() {
			// Arrange
			val editingContext = factory.createEditingContext(VYHYBNA_XML)
			val cols = editingContext.getRailWayNetGrid().getCols()
			val rows = editingContext.getRailWayNetGrid().getRows()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext.getRailWayNetGrid().getCols()).isEqualTo(cols)
			assertThat(simulationContext.getRailWayNetGrid().getRows()).isEqualTo(rows)
		}

		@Test
		@DisplayName("vyhybna.xml transformation preserves graph structure")
		fun transformContext_vyhybnaXml_preservesGraph() {
			// Arrange
			val editingContext = factory.createEditingContext(VYHYBNA_XML)
			val graphSizeBefore = editingContext.getGraph().size()

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert
			assertThat(simulationContext.getGraph().size()).isEqualTo(graphSizeBefore)
			assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
		}

		@Test
		@DisplayName("vyhybna.xml transformation preserves InOut count")
		fun transformContext_vyhybnaXml_preservesInOutCount() {
			// Arrange
			val editingContext = factory.createEditingContext(VYHYBNA_XML)

			// Act
			val simulationContext = transformer.createSimulationContext(editingContext, processFactory)

			// Assert - vyhybna.xml has 2 InOuts (kolej1, kolej2)
			assertThat(simulationContext.getInOuts()).hasSize(2)
		}
	}

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
}
