/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Integration test for context lifecycle
 */
package cz.vutbr.fit.interlockSim.integration

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.BaseContext
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.test.inject
import java.io.File

/**
 * Integration tests for context lifecycle management.
 *
 * These tests verify the complete lifecycle of railway network contexts:
 * creation, modification, transformation, execution, and persistence.
 * Tests ensure proper state management across context types.
 *
 * Test Coverage:
 * - Editing context creation and modification
 * - Transformation from editing to simulation context
 * - Simulation context execution lifecycle
 * - Context serialization and deserialization
 *
 * Total tests: 4
 */
@DisplayName("Context Lifecycle Integration")
@Tag("integration-test")
class ContextLifecycleIntegrationTest : KoinTestBase() {
	private val xmlFactory: XMLContextFactory by inject()
	private val transformer: ContextTransformer by inject()
	private val processFactory: SimulationProcessFactory by inject()

	/**
	 * Test the creation and modification of an editing context.
	 * Verifies that editing operations work correctly throughout the lifecycle.
	 */
	@Test
	@DisplayName("editing context creation and modification")
	fun lifecycle_editingContextCreationAndModification() {
		// Phase 1: Create empty editing context
		val editingContext: EditingContext = DefaultEditingContext(40, 40)
		assertThat(editingContext).isNotNull()
		assertThat(editingContext.getRailWayNetGrid().getCols()).isEqualTo(40)
		assertThat(editingContext.getRailWayNetGrid().getRows()).isEqualTo(40)
		assertThat(editingContext.getInOuts()).hasSize(0)
		
		// Phase 2: Add first InOut
		val inA = InOut("Entry", false, Cell.SpatialType.HORIZONTAL)
		editingContext.putCell(Point(5, 5), inA)
		assertThat(editingContext.getRailWayNetGrid().getCellAt(5, 5)).isNotNull()
		assertThat(editingContext.getInOuts()).hasSize(1)
		
		// Phase 3: Add second InOut
		val inB = InOut("Exit", true, Cell.SpatialType.HORIZONTAL)
		editingContext.putCell(Point(35, 5), inB)
		assertThat(editingContext.getRailWayNetGrid().getCellAt(35, 5)).isNotNull()
		assertThat(editingContext.getInOuts()).hasSize(2)
		
		// Phase 4: Add semaphore
		val semaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		semaphore.setName("Signal_1")
		editingContext.putCell(Point(20, 5), semaphore)
		assertThat(editingContext.getRailWayNetGrid().getCellAt(20, 5)).isNotNull()
		
		// Phase 5: Connect with track
		val track = SimpleTrackBlock(inA, inB, 300.0, 80.0)
		editingContext.joinCells(Point(5, 5), Point(35, 5), track)
		assertThat(editingContext.getGraph().size()).isGreaterThan(0)
		
		// Phase 6: Modify properties
		editingContext.currentMaxSpeed = 100.0
		editingContext.currentTrackLength = 300.0
		editingContext.currentNameString = "Test Network Lifecycle"
		
		assertThat(editingContext.currentMaxSpeed).isEqualTo(100.0)
		assertThat(editingContext.currentTrackLength).isEqualTo(300.0)
		assertThat(editingContext.currentNameString).isEqualTo("Test Network Lifecycle")
		
		// Phase 7: Remove and re-add cell (test modification)
		editingContext.removeCell(Point(20, 5))
		assertThat(editingContext.getRailWayNetGrid().getCellAt(20, 5)).isEqualTo(null)
		
		val newSemaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		newSemaphore.setName("Signal_2")
		editingContext.putCell(Point(20, 5), newSemaphore)
		assertThat(editingContext.getRailWayNetGrid().getCellAt(20, 5)).isNotNull()
		
		// Verify final state
		assertThat(editingContext.getInOuts()).hasSize(2)
		assertThat(editingContext.getGraph().size()).isGreaterThan(0)
	}

	/**
	 * Test the transformation from editing to simulation context.
	 * Verifies that the context type changes and properties are preserved.
	 */
	@Test
	@DisplayName("transformation from editing to simulation context")
	fun lifecycle_transformationEditingToSimulation() {
		// Phase 1: Create and populate editing context
		val editingContext = DefaultEditingContext(30, 30)
		
		val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
		val inB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
		editingContext.putCell(Point(5, 15), inA)
		editingContext.putCell(Point(25, 15), inB)
		
		val track = SimpleTrackBlock(inA, inB, 200.0, 80.0)
		editingContext.joinCells(Point(5, 15), Point(25, 15), track)
		
		editingContext.currentMaxSpeed = 90.0
		editingContext.currentTrackLength = 200.0
		editingContext.currentNameString = "Transformation Test"
		
		// Verify editing context is mutable (not frozen)
		assertThat(editingContext.isFrozen()).isEqualTo(false)
		
		// Phase 2: Transform to simulation context
		val simulationContext: SimulationContext = transformer.createSimulationContext(
			editingContext,
			processFactory
		)
		
		// Verify transformation created new instance
		assertThat(simulationContext).isNotNull()
		assertThat(simulationContext).isInstanceOf<DefaultSimulationContext>()
		
		// Verify simulation context is immutable (frozen)
		assertThat(simulationContext.isFrozen()).isTrue()
		
		// Phase 3: Verify structure preservation
		assertThat(simulationContext.getRailWayNetGrid().getCols()).isEqualTo(30)
		assertThat(simulationContext.getRailWayNetGrid().getRows()).isEqualTo(30)
		assertThat(simulationContext.getInOuts()).hasSize(2)
		assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
		
		// Phase 4: Verify property preservation
		val simBase = simulationContext as BaseContext
		assertThat(simBase.currentMaxSpeed).isEqualTo(90.0)
		assertThat(simBase.currentTrackLength).isEqualTo(200.0)
		assertThat(simBase.currentNameString).isEqualTo("Transformation Test")
		
		// Phase 5: Verify cells are preserved (identity check)
		val cellA = simulationContext.getRailWayNetGrid().getCellAt(5, 15)
		val cellB = simulationContext.getRailWayNetGrid().getCellAt(25, 15)
		assertThat(cellA).isNotNull()
		assertThat(cellB).isNotNull()
	}

	/**
	 * Test the simulation context execution lifecycle.
	 * Verifies initialization, configuration, and teardown phases.
	 */
	@Test
	@DisplayName("simulation context execution and teardown")
	fun lifecycle_simulationContextExecutionAndTeardown() {
		// Phase 1: Create simulation context from editing context
		val editingContext = DefaultEditingContext(35, 35)
		
		val inA = InOut("Entry", false, Cell.SpatialType.HORIZONTAL)
		val inB = InOut("Exit", true, Cell.SpatialType.HORIZONTAL)
		editingContext.putCell(Point(10, 10), inA)
		editingContext.putCell(Point(30, 10), inB)
		
		val track = SimpleTrackBlock(inA, inB, 200.0, 80.0)
		editingContext.joinCells(Point(10, 10), Point(30, 10), track)
		
		val simulationContext = transformer.createSimulationContext(editingContext, processFactory)
		
		// Phase 2: Verify initialization
		assertThat(simulationContext).isNotNull()
		assertThat(simulationContext.isFrozen()).isTrue()
		assertThat(simulationContext.getInOuts()).hasSize(2)
		
		// Phase 3: Verify configuration access
		val simBase = simulationContext as BaseContext
		assertThat(simBase.currentMaxSpeed).isNotNull()
		assertThat(simBase.currentTrackLength).isNotNull()
		
		// Phase 4: Access simulation-specific features
		// Note: We don't actually run simulation here (that's tested in sim/ package)
		// We verify that the context is properly configured for simulation
		assertThat(simulationContext.getGraph().size()).isGreaterThan(0)
		
		// Phase 5: Verify property change listeners work
		var eventFired = false
		simulationContext.addPropertyChangeListener { eventFired = true }
		simBase.currentMaxSpeed = 120.0
		assertThat(eventFired).isTrue()
		
		// Phase 6: Teardown (implicit - context goes out of scope)
		// Verify context is still accessible and consistent
		assertThat(simulationContext.getInOuts()).hasSize(2)
		assertThat(simulationContext.isFrozen()).isTrue()
	}

	/**
	 * Test context serialization and deserialization throughout lifecycle.
	 * Verifies that contexts can be persisted and restored correctly.
	 */
	@Test
	@DisplayName("context serialization and deserialization")
	fun lifecycle_contextSerializationAndDeserialization(@TempDir tempDir: File) {
		// Phase 1: Create editing context
		val editingContext = DefaultEditingContext(45, 45)
		
		val inA = InOut("Station_A", false, Cell.SpatialType.HORIZONTAL)
		val semaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		semaphore.setName("Signal_Main")
		val inB = InOut("Station_B", true, Cell.SpatialType.HORIZONTAL)
		
		editingContext.putCell(Point(5, 20), inA)
		editingContext.putCell(Point(22, 20), semaphore)
		editingContext.putCell(Point(40, 20), inB)
		
		val track = SimpleTrackBlock(inA, inB, 350.0, 100.0)
		editingContext.joinCells(Point(5, 20), Point(40, 20), track)
		
		editingContext.currentMaxSpeed = 110.0
		editingContext.currentTrackLength = 350.0
		editingContext.currentNameString = "Serialization Test"
		
		// Phase 2: Transform to simulation context
		val originalContext = transformer.createSimulationContext(editingContext, processFactory)
		assertThat(originalContext.isFrozen()).isTrue()
		
		// Phase 3: Serialize to XML
		val xmlFile = File(tempDir, "lifecycle-test.xml")
		xmlFactory.saveContext(originalContext, xmlFile)
		assertThat(xmlFile.exists()).isTrue()
		
		// Phase 4: Deserialize from XML
		val loadedContext = xmlFactory.createContext(xmlFile)
		assertThat(loadedContext).isNotNull()
		assertThat(loadedContext).isInstanceOf<SimulationContext>()
		
		// Phase 5: Verify structure integrity after round-trip
		assertThat(loadedContext.getRailWayNetGrid().getCols()).isEqualTo(45)
		assertThat(loadedContext.getRailWayNetGrid().getRows()).isEqualTo(45)
		assertThat(loadedContext.getInOuts()).hasSize(2)
		assertThat(loadedContext.getGraph().size()).isGreaterThan(0)
		
		// Phase 6: Verify properties preserved
		val loadedBase = loadedContext as BaseContext
		assertThat(loadedBase.currentMaxSpeed).isEqualTo(110.0)
		assertThat(loadedBase.currentTrackLength).isEqualTo(350.0)
		assertThat(loadedBase.currentNameString).isEqualTo("Serialization Test")
		
		// Phase 7: Verify cells preserved
		val cellA = loadedContext.getRailWayNetGrid().getCellAt(5, 20)
		val cellSem = loadedContext.getRailWayNetGrid().getCellAt(22, 20)
		val cellB = loadedContext.getRailWayNetGrid().getCellAt(40, 20)
		
		assertThat(cellA).isNotNull()
		assertThat(cellSem).isNotNull()
		assertThat(cellB).isNotNull()
		assertThat(cellA).isInstanceOf<InOut>()
		assertThat(cellSem).isInstanceOf<RailSemaphore>()
		assertThat(cellB).isInstanceOf<InOut>()
		
		// Phase 8: Verify loaded context is also frozen
		assertThat(loadedContext.isFrozen()).isTrue()
	}
}
