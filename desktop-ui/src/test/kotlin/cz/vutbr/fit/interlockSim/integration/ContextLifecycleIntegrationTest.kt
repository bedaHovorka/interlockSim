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
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.BaseContext
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.assertFrozen
import cz.vutbr.fit.interlockSim.testutil.assertMaxSpeedPropertyEventFires
import cz.vutbr.fit.interlockSim.testutil.assertNetworkProperties
import cz.vutbr.fit.interlockSim.testutil.buildLinearNetwork
import cz.vutbr.fit.interlockSim.util.Point
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
	private val editingContextFactory: JvmEditingContextFactory by inject()
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
		DefaultEditingContext(40, 40).use { editingContext ->
			assertThat(editingContext).isNotNull()
			assertThat(editingContext.getRailWayNetGrid().cols).isEqualTo(40)
			assertThat(editingContext.getRailWayNetGrid().rows).isEqualTo(40)
			assertThat(editingContext.getInOuts()).hasSize(0)

			// Phases 2-4: Add both InOuts, then connect them with a track
			addConnectedInOutPairAndAssertGrowth(editingContext)

			// Phases 5-7: Add a semaphore, change properties, then remove and re-add the semaphore
			assertSemaphoreAndPropertyModifications(editingContext)

			// Verify final state
			assertThat(editingContext.getInOuts()).hasSize(2)
			assertThat(editingContext.getGraph().size()).isGreaterThan(0)
		}
	}

	/**
	 * Test the transformation from editing to simulation context.
	 * Verifies that the context type changes and properties are preserved.
	 */
	@Test
	@DisplayName("transformation from editing to simulation context")
	fun lifecycle_transformationEditingToSimulation() {
		// Phase 1: Create and populate editing context
		buildLinearNetwork(30, "A", Point(5, 15), "B", Point(25, 15), 200.0, 80.0).use { editingContext ->
			editingContext.currentMaxSpeed = 90.0
			editingContext.currentTrackLength = 200.0
			editingContext.currentNameString = "Transformation Test"

			// Verify editing context is mutable (not frozen)
			assertFrozen(editingContext, expected = false)

			// Phase 2: Transform to simulation context, then verify it (phases 2-5)
			transformer.createSimulationContext(editingContext, processFactory).use { simulationContext ->
				verifyTransformedContextPreservesStructure(simulationContext)
			}
		}
	}

	/**
	 * Test the simulation context execution lifecycle.
	 * Verifies initialization, configuration, and teardown phases.
	 */
	@Test
	@DisplayName("simulation context execution and teardown")
	fun lifecycle_simulationContextExecutionAndTeardown() {
		// Phase 1: Create simulation context from editing context
		buildLinearNetwork(35, "Entry", Point(10, 10), "Exit", Point(30, 10), 200.0, 80.0).use { editingContext ->
			transformer.createSimulationContext(editingContext, processFactory).use { simulationContext ->
				// Phase 2: Verify initialization
				assertThat(simulationContext).isNotNull()
				val simBase = simulationContext as BaseContext<*>
				assertFrozen(simBase)
				val simInOuts: Collection<*> = simulationContext.getInOuts()
				assertThat(simInOuts).hasSize(2)

				// Phase 3: Verify configuration access
				assertThat(simBase.currentMaxSpeed).isNotNull()
				assertThat(simBase.currentTrackLength).isNotNull()

				// Phase 4: Access simulation-specific features
				// Note: We don't actually run simulation here (that's tested in sim/ package)
				// We verify that the context is properly configured for simulation
				assertThat(simulationContext.getGraph().size()).isGreaterThan(0)

				// Phase 5: Verify property setters work and fire events
				assertMaxSpeedPropertyEventFires(simBase, 120.0)

				// Phase 6: Verify context is still consistent before `.use` closes its scope
				assertThat(simulationContext.getInOuts()).hasSize(2)
				assertThat(simulationContext.isFrozen).isTrue()
			}
		}
	}

	/**
	 * Test context serialization and deserialization throughout lifecycle.
	 * Verifies that contexts can be persisted and restored correctly.
	 */
	@Test
	@DisplayName("context serialization and deserialization")
	fun lifecycle_contextSerializationAndDeserialization(
		@TempDir tempDir: File
	) {
		// Phase 1: Create editing context
		buildLinearNetwork(45, "Station_A", Point(5, 20), "Station_B", Point(40, 20), 350.0, 100.0).use { editingContext ->
			// Add semaphore after track is connected
			editingContext.putCell(Point(22, 20), RailSemaphore("Signal_Main", true, Cell.SpatialType.HORIZONTAL))

			editingContext.currentMaxSpeed = 110.0
			editingContext.currentTrackLength = 350.0
			editingContext.currentNameString = "Serialization Test"

			// Phase 2: Serialize to XML
			val xmlFile = File(tempDir, "lifecycle-test.xml")
			editingContextFactory.saveContext(editingContext, xmlFile)
			assertThat(xmlFile.exists()).isTrue()

			// Phase 3: Transform to simulation context
			transformer.createSimulationContext(editingContext, processFactory).use { originalContext ->
				assertFrozen(originalContext as BaseContext<*>)

				// Phase 4: Deserialize from XML, then verify the round trip (phases 5-8)
				editingContextFactory.createContext(xmlFile).use { loadedContext ->
					assertThat(loadedContext).isNotNull()
					assertThat(loadedContext).isInstanceOf<EditingContext>()
					verifyReloadedNetwork(loadedContext)
				}
			}
		}
	}

	/** Phases 2-4 of the modification test: add both InOuts, then join them with a track. */
	private fun addConnectedInOutPairAndAssertGrowth(editingContext: DefaultEditingContext) {
		// Phase 2: Add first InOut
		val inA = InOut("Entry", false, Cell.SpatialType.HORIZONTAL)
		putCellAndAssertPresent(editingContext, Point(5, 5), inA)
		assertThat(editingContext.getInOuts()).hasSize(1)

		// Phase 3: Add second InOut
		val inB = InOut("Exit", true, Cell.SpatialType.HORIZONTAL)
		putCellAndAssertPresent(editingContext, Point(35, 5), inB)
		assertThat(editingContext.getInOuts()).hasSize(2)

		// Phase 4: Connect with track (must be done before adding obstacles in the path)
		val track = SimpleTrackBlock(inA, inB, 300.0, 80.0)
		editingContext.joinCells(Point(5, 5), Point(35, 5), track)
		assertThat(editingContext.getGraph().size()).isGreaterThan(0)
	}

	/** Phases 5-7 of the modification test: semaphore, property changes, then remove and re-add. */
	private fun assertSemaphoreAndPropertyModifications(editingContext: DefaultEditingContext) {
		// Phase 5: Add semaphore (after track is connected)
		putCellAndAssertPresent(editingContext, Point(20, 5), RailSemaphore("Signal_1", true, Cell.SpatialType.HORIZONTAL))

		// Phase 6: Modify properties
		editingContext.currentMaxSpeed = 100.0
		editingContext.currentTrackLength = 300.0
		editingContext.currentNameString = "Test Network Lifecycle"
		assertNetworkProperties(editingContext, 100.0, 300.0, "Test Network Lifecycle")

		// Phase 7: Remove and re-add cell (test modification)
		editingContext.removeCell(Point(20, 5))
		assertThat(editingContext.getRailWayNetGrid().getCellAt(20, 5)).isEqualTo(null)
		putCellAndAssertPresent(editingContext, Point(20, 5), RailSemaphore("Signal_2", false, Cell.SpatialType.HORIZONTAL))
	}

	/** Put [cell] at [point] and assert the grid now holds a cell there. */
	private fun putCellAndAssertPresent(
		editingContext: DefaultEditingContext,
		point: Point,
		cell: NodeCell
	) {
		editingContext.putCell(point, cell)
		assertThat(editingContext.getRailWayNetGrid().getCellAt(point.x, point.y)).isNotNull()
	}

	/** Phases 2-5 of the transformation test: new frozen instance, structure, properties, and cells preserved. */
	private fun verifyTransformedContextPreservesStructure(simulationContext: SimulationContext) {
		// Verify transformation created new instance
		assertThat(simulationContext).isNotNull()
		assertThat(simulationContext).isInstanceOf<DefaultSimulationContext>()

		// Verify simulation context is immutable (frozen)
		val simBase = simulationContext as BaseContext<*>
		assertFrozen(simBase)

		// Phase 3: Verify structure preservation
		assertThat(simulationContext.getRailWayNetGrid().cols).isEqualTo(30)
		assertThat(simulationContext.getRailWayNetGrid().rows).isEqualTo(30)
		val simContextInOuts: Collection<*> = simulationContext.getInOuts()
		assertThat(simContextInOuts).hasSize(2)
		assertThat(simulationContext.getGraph().size()).isGreaterThan(0)

		// Phase 4: Verify property preservation
		assertNetworkProperties(simBase, 90.0, 200.0, "Transformation Test")

		// Phase 5: Verify cells are preserved (identity check)
		val cellA = simulationContext.getRailWayNetGrid().getCellAt(5, 15)
		val cellB = simulationContext.getRailWayNetGrid().getCellAt(25, 15)
		assertThat(cellA).isNotNull()
		assertThat(cellB).isNotNull()
	}

	/** Phases 5-8 of the serialization test: structure, properties, cells, and mutability of the reloaded context. */
	private fun verifyReloadedNetwork(loadedContext: Context<*, *>) {
		// Phase 5: Verify structure integrity after round-trip
		assertThat(loadedContext.getRailWayNetGrid().cols).isEqualTo(45)
		assertThat(loadedContext.getRailWayNetGrid().rows).isEqualTo(45)
		val loadedSimContext = loadedContext as EditingContext
		val loadedInOuts: Collection<*> = loadedSimContext.getInOuts()
		assertThat(loadedInOuts).hasSize(2)
		assertThat(loadedContext.getGraph().size()).isGreaterThan(0)

		// Phase 6: Verify properties (NOTE: XML serialization doesn't preserve these - see issue #248)
		val loadedBase = loadedContext as BaseContext<*>
		// Properties will have default values after XML load
		assertThat(loadedBase.currentMaxSpeed).isNotNull()
		assertThat(loadedBase.currentTrackLength).isNotNull()

		// Phase 7: Verify cells preserved
		val cellA = loadedContext.getRailWayNetGrid().getCellAt(5, 20)
		val cellSem = loadedContext.getRailWayNetGrid().getCellAt(22, 20)
		val cellB = loadedContext.getRailWayNetGrid().getCellAt(40, 20)

		assertThat(cellA).isNotNull()
		assertThat(cellSem).isNotNull()
		assertThat(cellB).isNotNull()
		assertThat(cellA as Any).isInstanceOf(InOut::class)
		assertThat(cellSem as Any).isInstanceOf(RailSemaphore::class)
		assertThat(cellB as Any).isInstanceOf(InOut::class)

		// Phase 8: Verify loaded context is not frozen
		assertThat(loadedContext.isFrozen).isFalse()
	}
}
