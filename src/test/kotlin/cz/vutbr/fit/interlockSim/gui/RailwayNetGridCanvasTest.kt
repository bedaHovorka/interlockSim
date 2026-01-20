/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * RailwayNetGridCanvas tests for context type handling
 */
package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.test.inject

/**
 * Tests for RailwayNetGridCanvas context type handling.
 *
 * These tests validate that RailwayNetGridCanvas can handle both EditingContext
 * and SimulationContext without assuming inheritance relationship between them.
 *
 * This is critical preparation for Issue #153.5 where SimulationContext will no
 * longer extend EditingContext.
 */
class RailwayNetGridCanvasTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()
	private lateinit var canvas: RailwayNetGridCanvas
	private lateinit var editingContext: EditingContext
	private lateinit var simulationContext: SimulationContext

	// Use full test module to get GUI components
	override fun getTestModule(): Module = testModuleFull

	@BeforeEach
	fun setUp() {
		// Create canvas instance
		canvas = RailwayNetGridCanvas()

		// Create a simple editing context with one InOut node
		editingContext = factory.createEmptyContext()
		val inA = InOut("A", false, SpatialType.HORIZONTAL)
		val pA = Point(5, 5)
		editingContext.putCell(pA, inA)

		// Create simulation context from editing context
		simulationContext = factory.createContext(editingContext)
	}

	@Test
	fun testSetContextWithEditingContext() {
		// When: Setting an EditingContext
		canvas.setContext(editingContext)

		// Then: Canvas should accept it
		assertThat(canvas.getEditingContext()).isSameInstanceAs(editingContext)
	}

	@Test
	fun testSetContextWithSimulationContext() {
		// When: Setting a SimulationContext
		canvas.setContext(simulationContext)

		// Then: Canvas should accept it and provide access via getSimulationContext()
		assertThat(canvas.getSimulationContext()).isSameInstanceAs(simulationContext)
	}

	@Test
	fun testSwitchFromEditingToSimulation() {
		// Given: Canvas with EditingContext
		canvas.setContext(editingContext)
		val firstContext = canvas.getEditingContext()
		assertThat(firstContext).isSameInstanceAs(editingContext)

		// When: Switching to SimulationContext
		canvas.setContext(simulationContext)

		// Then: Context should be updated and accessible via getSimulationContext()
		val secondContext = canvas.getSimulationContext()
		assertThat(secondContext).isSameInstanceAs(simulationContext)
	}

	@Test
	fun testSwitchFromSimulationToEditing() {
		// Given: Canvas with SimulationContext
		canvas.setContext(simulationContext)

		// When: Switching back to EditingContext
		canvas.setContext(editingContext)

		// Then: Context should be updated to editing context
		assertThat(canvas.getEditingContext()).isSameInstanceAs(editingContext)
	}

	@Test
	fun testMultipleContextSwitches() {
		// Test multiple switches between context types
		canvas.setContext(editingContext)
		assertThat(canvas.getEditingContext()).isSameInstanceAs(editingContext)

		canvas.setContext(simulationContext)
		assertThat(canvas.getSimulationContext()).isSameInstanceAs(simulationContext)

		canvas.setContext(editingContext)
		assertThat(canvas.getEditingContext()).isSameInstanceAs(editingContext)

		canvas.setContext(simulationContext)
		assertThat(canvas.getSimulationContext()).isSameInstanceAs(simulationContext)
	}

	@Test
	fun testContextIsStoredCorrectly() {
		// When: Setting context
		canvas.setContext(editingContext)

		// Then: The grid should be accessible and match
		val grid = canvas.getEditingContext().getRailWayNetGrid()
		assertThat(grid).isNotNull()
		assertThat(grid.getCols()).isEqualTo(100) // Default grid size from XMLContextFactory.createEmptyContext()
	}

	@Test
	fun testEditingContextType() {
		// When: Setting editing context
		canvas.setContext(editingContext)

		// Then: Retrieved context should be EditingContext type
		val context = canvas.getEditingContext()
		assertThat(context).isInstanceOf(EditingContext::class)
	}

	@Test
	fun testSimulationContextType() {
		// When: Setting simulation context
		canvas.setContext(simulationContext)

		// Then: Retrieved context should be SimulationContext type
		val context = canvas.getSimulationContext()
		assertThat(context).isInstanceOf(SimulationContext::class)
		assertThat(context).isSameInstanceAs(simulationContext)
	}

	@Test
	fun testCannotGetEditingContextWhenInSimulationMode() {
		// Given: Canvas in simulation mode
		canvas.setContext(simulationContext)

		// When/Then: Attempting to get EditingContext should throw
		org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
			canvas.getEditingContext()
		}
	}

	@Test
	fun testCannotGetSimulationContextWhenInEditingMode() {
		// Given: Canvas in editing mode
		canvas.setContext(editingContext)

		// When/Then: Attempting to get SimulationContext should throw
		org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
			canvas.getSimulationContext()
		}
	}
}
