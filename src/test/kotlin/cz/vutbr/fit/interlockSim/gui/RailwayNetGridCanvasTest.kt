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
import org.junit.jupiter.api.DisplayName
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

	@Test
	@DisplayName("canvas initializes with correct properties")
	fun canvasInitializesWithCorrectProperties() {
		// Verify background color is black
		assertThat(canvas.background).isEqualTo(java.awt.Color.BLACK)
		
		// Verify autoscrolls is enabled
		assertThat(canvas.autoscrolls).isEqualTo(true)
		
		// Verify grid is initially hidden
		assertThat(canvas.isShowGrid()).isEqualTo(false)
	}

	@Test
	@DisplayName("canvas dimensions match grid size")
	fun canvasDimensionsMatchGridSize() {
		// When: Setting a context
		canvas.setContext(editingContext)
		
		// Then: Preferred size should match grid dimensions
		val grid = editingContext.getRailWayNetGrid()
		val expectedWidth = RailwayNetGridCanvas.getCellWidth() * grid.getCols()
		val expectedHeight = RailwayNetGridCanvas.getCellHeight() * grid.getRows()
		
		assertThat(canvas.preferredSize.width).isEqualTo(expectedWidth)
		assertThat(canvas.preferredSize.height).isEqualTo(expectedHeight)
	}

	@Test
	@DisplayName("toggle grid visibility")
	fun toggleGridVisibility() {
		// Initially hidden
		assertThat(canvas.isShowGrid()).isEqualTo(false)
		
		// Show grid
		canvas.setShowGrid(true)
		assertThat(canvas.isShowGrid()).isEqualTo(true)
		
		// Hide grid
		canvas.setShowGrid(false)
		assertThat(canvas.isShowGrid()).isEqualTo(false)
	}

	@Test
	@DisplayName("setNodeOnToolbar stores toolbar state")
	fun setNodeOnToolbarStoresToolbarState() {
		// When: Setting toolbar node
		val args = arrayOf<Any?>("testArg1", "testArg2")
		canvas.setNodeOnToolbar(InOut::class.java, args)
		
		// Then: Canvas should store the state (no exception, state is internal)
		// This test verifies the method executes without error
	}

	@Test
	@DisplayName("getStatus returns empty string when no cell")
	fun getStatusReturnsEmptyStringWhenNoCell() {
		// Given: Canvas with context but no cell at position
		canvas.setContext(editingContext)
		
		// When: Getting status for empty cell
		val mouseEvent = java.awt.event.MouseEvent(
			canvas,
			java.awt.event.MouseEvent.MOUSE_MOVED,
			System.currentTimeMillis(),
			0,
			0, 0, // Position (0,0) - empty
			0,
			false
		)
		val status = canvas.getStatus(mouseEvent)
		
		// Then: Status should be empty string
		assertThat(status).isEqualTo("")
	}

	@Test
	@DisplayName("getStatus returns cell info when cell exists")
	fun getStatusReturnsCellInfoWhenCellExists() {
		// Given: Canvas with context and a cell
		canvas.setContext(editingContext)
		val cellPoint = Point(5, 5)
		val grid = editingContext.getRailWayNetGrid()
		val cell = grid.get(cellPoint)
		
		// When: Getting status for cell position
		val x = cellPoint.x * RailwayNetGridCanvas.getCellWidth()
		val y = cellPoint.y * RailwayNetGridCanvas.getCellHeight()
		val mouseEvent = java.awt.event.MouseEvent(
			canvas,
			java.awt.event.MouseEvent.MOUSE_MOVED,
			System.currentTimeMillis(),
			0,
			x, y,
			0,
			false
		)
		val status = canvas.getStatus(mouseEvent)
		
		// Then: Status should contain cell info
		assertThat(status).isNotNull()
		assertThat(status).isEqualTo(cell.toString())
	}

	@Test
	@DisplayName("scrollable viewport size matches preferred size")
	fun scrollableViewportSizeMatchesPreferredSize() {
		// Given: Canvas with context
		canvas.setContext(editingContext)
		
		// Then: Scrollable viewport size should match preferred size
		assertThat(canvas.preferredScrollableViewportSize).isEqualTo(canvas.preferredSize)
	}

	@Test
	@DisplayName("scrollable tracks viewport height is false")
	fun scrollableTracksViewportHeightIsFalse() {
		assertThat(canvas.scrollableTracksViewportHeight).isEqualTo(false)
	}

	@Test
	@DisplayName("scrollable tracks viewport width is false")
	fun scrollableTracksViewportWidthIsFalse() {
		assertThat(canvas.scrollableTracksViewportWidth).isEqualTo(false)
	}

	@Test
	@DisplayName("scrollable block increment returns reasonable value")
	fun scrollableBlockIncrementReturnsReasonableValue() {
		// Given: Canvas with context
		canvas.setContext(editingContext)
		
		// When: Getting block increment for horizontal scroll
		val visibleRect = java.awt.Rectangle(0, 0, 800, 600)
		val increment = canvas.getScrollableBlockIncrement(
			visibleRect,
			javax.swing.SwingConstants.HORIZONTAL,
			1
		)
		
		// Then: Increment should be less than viewport width
		assertThat(increment).isEqualTo(visibleRect.width - 35) // MAX_UNIT_INCREMENT = 35
	}

	@Test
	@DisplayName("scrollable unit increment returns positive value")
	fun scrollableUnitIncrementReturnsPositiveValue() {
		// Given: Canvas with context
		canvas.setContext(editingContext)
		
		// When: Getting unit increment
		val visibleRect = java.awt.Rectangle(0, 0, 800, 600)
		val increment = canvas.getScrollableUnitIncrement(
			visibleRect,
			javax.swing.SwingConstants.HORIZONTAL,
			1 // Forward direction
		)
		
		// Then: Increment should be positive
		assertThat(increment > 0).isEqualTo(true)
	}

	@Test
	@DisplayName("property change with Point triggers partial repaint")
	fun propertyChangeWithPointTriggersPartialRepaint() {
		// Given: Canvas with context
		canvas.setContext(editingContext)
		
		// When: Property change with Point value
		val point = java.awt.Point(5, 5)
		val event = java.beans.PropertyChangeEvent(
			editingContext,
			"cell",
			null,
			point
		)
		canvas.propertyChange(event)
		
		// Then: Canvas should handle property change (no exception)
		// Repaint is called internally, we just verify no exception
	}

	@Test
	@DisplayName("property change without Point triggers full repaint")
	fun propertyChangeWithoutPointTriggersFullRepaint() {
		// Given: Canvas with context
		canvas.setContext(editingContext)
		
		// When: Property change with non-Point value
		val event = java.beans.PropertyChangeEvent(
			editingContext,
			"property",
			null,
			"value"
		)
		canvas.propertyChange(event)
		
		// Then: Canvas should handle property change (no exception)
		// Repaint is called internally, we just verify no exception
	}

	@Test
	@DisplayName("cell dimensions are accessible via static methods")
	fun cellDimensionsAreAccessibleViaStaticMethods() {
		// Cell dimensions should be constant
		assertThat(RailwayNetGridCanvas.getCellWidth()).isEqualTo(16)
		assertThat(RailwayNetGridCanvas.getCellHeight()).isEqualTo(16)
	}

	@Test
	@DisplayName("canvas has mouse motion listeners")
	fun canvasHasMouseMotionListeners() {
		// Canvas should have at least one mouse motion listener (itself)
		val listeners = canvas.mouseMotionListeners
		assertThat(listeners.size > 0).isEqualTo(true)
	}

	@Test
	@DisplayName("canvas has mouse listeners in editing mode")
	fun canvasHasMouseListenersInEditingMode() {
		// Given: Canvas in editing mode
		canvas.setContext(editingContext)
		
		// Then: Canvas should have mouse listeners for editing
		val listeners = canvas.mouseListeners
		assertThat(listeners.size > 0).isEqualTo(true)
	}

	@Test
	@DisplayName("deprecated setEditing method still works")
	fun deprecatedSetEditingMethodStillWorks() {
		// When: Using deprecated method
		@Suppress("DEPRECATION")
		canvas.setEditing(editingContext)
		
		// Then: Canvas should be in editing mode
		assertThat(canvas.getEditingContext()).isEqualTo(editingContext)
	}

	@Test
	@DisplayName("deprecated setSimulation method still works")
	fun deprecatedSetSimulationMethodStillWorks() {
		// When: Using deprecated method
		@Suppress("DEPRECATION")
		canvas.setSimulation(simulationContext)
		
		// Then: Canvas should be in simulation mode
		assertThat(canvas.getSimulationContext()).isEqualTo(simulationContext)
	}
}
