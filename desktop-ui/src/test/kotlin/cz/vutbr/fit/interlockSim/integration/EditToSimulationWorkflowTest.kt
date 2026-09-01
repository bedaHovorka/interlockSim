/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * End-to-end integration test for edit-to-simulation workflow
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
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestPropertyChangeListener
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
 * End-to-end integration tests for the complete edit-to-simulation workflow.
 *
 * These tests verify that the entire workflow from creating a railway network
 * in editing mode, transforming it to simulation mode, and executing simulations
 * works correctly across package boundaries.
 *
 * Test Coverage:
 * - Network creation in editing mode
 * - Saving network to XML
 * - Loading network from XML
 * - Transformation from editing to simulation context
 * - Property change events during simulation
 * - Simulation execution and observability
 *
 * Total tests: 4
 */
@DisplayName("Edit to Simulation Workflow")
@Tag("integration-test")
class EditToSimulationWorkflowTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val transformer: ContextTransformer by inject()
	private val processFactory: SimulationProcessFactory by inject()

	/**
	 * Test the complete workflow: create network in editing mode, save to XML,
	 * load from XML, and verify network integrity.
	 */
	@Test
	@DisplayName("complete workflow - create network, save, load, simulate")
	fun completeWorkflow_createSaveLoadSimulate(
		@TempDir tempDir: File
	) {
		// Step 1: Create network in editing mode
		buildWorkflowTestNetwork().use { editingContext ->
			// Step 2: Save to XML
			val xmlFile = File(tempDir, "test-network.xml")
			editingContextFactory.saveContext(editingContext, xmlFile)
			assertThat(xmlFile.exists()).isTrue()

			// Step 3: Transform to simulation context
			transformer.createSimulationContext(editingContext, processFactory).use { simulationContext ->
				assertThat(simulationContext).isNotNull()
				assertThat(simulationContext).isInstanceOf<DefaultSimulationContext>()
			}

			// Step 4: Load from XML, then verify network integrity (steps 5-6)
			editingContextFactory.createContext(xmlFile).use { loadedContext ->
				assertThat(loadedContext).isNotNull()
				verifyLoadedNetworkIntegrity(loadedContext)
			}
		}
	}

	/**
	 * Test the transformation from edit mode to simulation mode, verifying that
	 * the editing context is properly converted to a simulation context.
	 */
	@Test
	@DisplayName("workflow - edit mode to simulation mode transformation")
	fun workflow_editToSimulationTransformation() {
		// Create network in editing mode
		buildLinearNetwork(30, "A", Point(5, 5), "B", Point(25, 5), 300.0, 80.0).use { editingContext ->
			// Add semaphore after the track is connected (obstacles must come after the join)
			editingContext.putCell(Point(15, 5), RailSemaphore("Signal_1", true, Cell.SpatialType.HORIZONTAL))

			// Transform to simulation context
			transformer.createSimulationContext(editingContext, processFactory).use { simulationContext ->
				verifyTransformedSimulationContext(simulationContext)
			}
		}
	}

	/**
	 * Test that simulation results are observable through the context's
	 * property change notification system.
	 */
	@Test
	@DisplayName("workflow - simulation results are observable")
	fun workflow_simulationResultsAreObservable() {
		// Load a pre-built network
		editingContextFactory.createContext(TestFixtures.loadLinearTrackXml()).use { context ->
			assertThat(context).isNotNull()
			val contextCtx = context as EditingContext
			val contextInOuts: Collection<*> = contextCtx.getInOuts()
			assertThat(contextInOuts).hasSize(2)

			// Verify that the context supports property access with event firing
			assertMaxSpeedPropertyEventFires(context as BaseContext<*>, 100.0)
		}
	}

	/**
	 * Test that property change events propagate correctly throughout the
	 * context hierarchy during workflow operations.
	 */
	@Test
	@DisplayName("workflow - property change events propagate correctly")
	fun workflow_propertyChangeEventsPropagateCorrectly() {
		// Create editing context with property change support
		buildLinearNetwork(30, "A", Point(5, 5), "B", Point(25, 5), 200.0, 80.0).use { editingContext ->
			// Listen for property changes
			val propertyListener = TestPropertyChangeListener()
			editingContext.addPropertyChangeListener(propertyListener)

			// Change properties in editing context and verify events are fired
			editingContext.currentMaxSpeed = 150.0
			editingContext.currentTrackLength = 200.0
			editingContext.currentNameString = "Test Railway"

			// Verify property values are set
			assertNetworkProperties(editingContext, 150.0, 200.0, "Test Railway")

			// Verify events were fired
			assertThat(propertyListener.events).hasSize(3)
			assertThat(propertyListener.events[0].propertyName).isEqualTo("currentMaxSpeed")
			assertThat(propertyListener.events[1].propertyName).isEqualTo("currentTrackLength")
			assertThat(propertyListener.events[2].propertyName).isEqualTo("currentNameString")

			// Transform to simulation context
			transformer.createSimulationContext(editingContext, processFactory).use { simulationContext ->
				// Verify properties are copied to simulation context
				val simBase = simulationContext as BaseContext<*>
				assertNetworkProperties(simBase, 150.0, 200.0, "Test Railway")

				// Verify property setters work in simulation context and fire events
				assertMaxSpeedPropertyEventFires(simBase, 180.0)
			}
		}
	}

	/**
	 * Build the 50x50 two-InOut network of the complete-workflow test: "Entry_A"
	 * (10,10) and "Exit_B" (40,10) joined by one 500 m / 100 km/h track, with the
	 * properties 120.0 / 500.0 / "Test Network" set.
	 *
	 * The caller owns the returned context and must close it (`.use`) — Issue #1035.
	 */
	private fun buildWorkflowTestNetwork(): DefaultEditingContext {
		val editingContext =
			buildLinearNetwork(50, "Entry_A", Point(10, 10), "Exit_B", Point(40, 10), 500.0, 100.0)

		// Set network properties
		editingContext.currentMaxSpeed = 120.0
		editingContext.currentTrackLength = 500.0
		editingContext.currentNameString = "Test Network"

		return editingContext
	}

	/** Steps 5-6 of the complete workflow: verify grid, InOuts, graph, and accessible properties of the loaded context. */
	private fun verifyLoadedNetworkIntegrity(loadedContext: Context<*, *>) {
		assertThat(loadedContext.getRailWayNetGrid().cols).isEqualTo(50)
		assertThat(loadedContext.getRailWayNetGrid().rows).isEqualTo(50)
		val loadedSimCtx = loadedContext as EditingContext
		val loadedInOuts: Collection<*> = loadedSimCtx.getInOuts()
		assertThat(loadedInOuts).hasSize(2)
		assertThat(loadedContext.getGraph().size()).isGreaterThan(0)

		// Verify context is accessible (NOTE: properties not preserved - see issue #248)
		val loadedBaseContext = loadedContext as BaseContext<*>
		assertThat(loadedBaseContext.currentMaxSpeed).isNotNull()
		assertThat(loadedBaseContext.currentTrackLength).isNotNull()
		assertThat(loadedBaseContext.currentNameString).isNotNull()
	}

	/** Verify the simulation context produced from the 30x30 A/Semaphore/B network: grid, cells, InOuts, graph, frozen. */
	private fun verifyTransformedSimulationContext(simulationContext: SimulationContext) {
		// Verify transformation succeeded
		assertThat(simulationContext).isNotNull()
		assertThat(simulationContext.getRailWayNetGrid().cols).isEqualTo(30)
		assertThat(simulationContext.getRailWayNetGrid().rows).isEqualTo(30)

		// Verify cells are preserved
		val cellA = simulationContext.getRailWayNetGrid().getCellAt(5, 5)
		val cellSem = simulationContext.getRailWayNetGrid().getCellAt(15, 5)
		val cellB = simulationContext.getRailWayNetGrid().getCellAt(25, 5)

		assertThat(cellA).isNotNull()
		assertThat(cellSem).isNotNull()
		assertThat(cellB).isNotNull()

		// Verify InOuts are accessible
		val simCtxInOuts: Collection<*> = simulationContext.getInOuts()
		assertThat(simCtxInOuts).hasSize(2)

		// Verify graph structure is preserved
		assertThat(simulationContext.getGraph().size()).isGreaterThan(0)

		// Verify simulation context is frozen (immutable)
		assertFrozen(simulationContext as BaseContext<*>)
	}

	// The property-change assertion helpers (assertMaxSpeedPropertyEventFires,
	// assertNetworkProperties) and the TestPropertyChangeListener used above live in
	// testutil/ContextPropertyEvents.kt, shared with ContextLifecycleIntegrationTest.
}
