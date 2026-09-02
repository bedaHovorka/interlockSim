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
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Tests for RailwayNetGridCanvas context type handling.
 *
 * These tests validate that RailwayNetGridCanvas can handle both EditingContext
 * and SimulationContext without assuming inheritance relationship between them.
 *
 * This is critical preparation for Issue #153.5 where SimulationContext will no
 * longer extend EditingContext.
 *
 * These tests extend AbstractFrameTestBase to ensure:
 * - Proper EDT execution for GUI operations
 * - Headless environment handling
 * - Integration test tagging
 */
class RailwayNetGridCanvasTest : AbstractFrameTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var canvas: RailwayNetGridCanvas
	private lateinit var editingContext: EditingContext
	private lateinit var simulationContext: SimulationContext

	@BeforeEach
	override fun setUp() {
		super.setUp()

		runOnEDT {
			// Create canvas instance
			canvas = RailwayNetGridCanvas()

			// Create a simple editing context with one InOut node
			editingContext = editingContextFactory.createEmptyContext()
			val inA = InOut("A", false, SpatialType.HORIZONTAL)
			val pA = Point(5, 5)
			editingContext.putCell(pA, inA)

			// Create simulation context from editing context
			simulationContext = simulationContextFactory.createContext(editingContext)
			testContext = simulationContext
		}
	}

	/** [testContext] tracks only the simulation context; close the editing context by hand. */
	@AfterEach
	override fun tearDown() {
		super.tearDown()
		if (::editingContext.isInitialized) {
			editingContext.close()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	fun testSetContextWithEditingContext() {
		runOnEDT {
			// When: Setting an EditingContext
			canvas.setContext(editingContext)

			// Then: Canvas should accept it
			assertThat(canvas.getEditingContext()).isSameInstanceAs(editingContext)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	fun testSetContextWithSimulationContext() {
		runOnEDT {
			// When: Setting a SimulationContext
			canvas.setContext(simulationContext)

			// Then: Canvas should accept it and provide access via getSimulationContext()
			assertThat(canvas.getSimulationContext()).isSameInstanceAs(simulationContext)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	fun testSwitchFromEditingToSimulation() {
		runOnEDT {
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
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	fun testSwitchFromSimulationToEditing() {
		runOnEDT {
			// Given: Canvas with SimulationContext
			canvas.setContext(simulationContext)

			// When: Switching back to EditingContext
			canvas.setContext(editingContext)

			// Then: Context should be updated to editing context
			assertThat(canvas.getEditingContext()).isSameInstanceAs(editingContext)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	fun testMultipleContextSwitches() {
		runOnEDT {
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
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	fun testContextIsStoredCorrectly() {
		runOnEDT {
			// When: Setting context
			canvas.setContext(editingContext)

			// Then: The grid should be accessible and match
			val grid = canvas.getEditingContext().getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(100) // Default grid size from XMLContextFactory.createEmptyContext()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	fun testEditingContextType() {
		runOnEDT {
			// When: Setting editing context
			canvas.setContext(editingContext)

			// Then: Retrieved context should be EditingContext type
			val context = canvas.getEditingContext()
			assertThat(context).isInstanceOf(EditingContext::class)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	fun testSimulationContextType() {
		runOnEDT {
			// When: Setting simulation context
			canvas.setContext(simulationContext)

			// Then: Retrieved context should be SimulationContext type
			val context = canvas.getSimulationContext()
			assertThat(context).isInstanceOf(SimulationContext::class)
			assertThat(context).isSameInstanceAs(simulationContext)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	fun testCannotGetEditingContextWhenInSimulationMode() {
		runOnEDT {
			// Given: Canvas in simulation mode
			canvas.setContext(simulationContext)

			// When/Then: Attempting to get EditingContext should throw
			org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
				canvas.getEditingContext()
			}
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	fun testCannotGetSimulationContextWhenInEditingMode() {
		runOnEDT {
			// Given: Canvas in editing mode
			canvas.setContext(editingContext)

			// When/Then: Attempting to get SimulationContext should throw
			org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
				canvas.getSimulationContext()
			}
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("canvas initializes with correct properties")
	fun canvasInitializesWithCorrectProperties() {
		runOnEDT {
			// Verify background color is black
			assertThat(canvas.background).isEqualTo(java.awt.Color.BLACK)

			// Verify autoscrolls is enabled
			assertThat(canvas.autoscrolls).isEqualTo(true)

			// Verify grid is initially hidden
			assertThat(canvas.showGrid).isEqualTo(false)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("canvas dimensions match grid size")
	fun canvasDimensionsMatchGridSize() {
		runOnEDT {
			// When: Setting a context
			canvas.setContext(editingContext)

			// Then: Preferred size should match grid dimensions
			val grid = editingContext.getRailWayNetGrid()
			val expectedWidth = RailwayNetGridCanvas.getCellWidth() * grid.cols
			val expectedHeight = RailwayNetGridCanvas.getCellHeight() * grid.rows

			assertThat(canvas.preferredSize.width).isEqualTo(expectedWidth)
			assertThat(canvas.preferredSize.height).isEqualTo(expectedHeight)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("toggle grid visibility")
	fun toggleGridVisibility() {
		runOnEDT {
			// Initially hidden
			assertThat(canvas.showGrid).isEqualTo(false)

			// Show grid
			canvas.showGrid = true
			assertThat(canvas.showGrid).isEqualTo(true)

			// Hide grid
			canvas.showGrid = false
			assertThat(canvas.showGrid).isEqualTo(false)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setNodeOnToolbar stores toolbar state")
	fun setNodeOnToolbarStoresToolbarState() {
		runOnEDT {
			// Given: Initial state should be null
			assertThat(canvas.toolbarCellClass).isEqualTo(null)
			assertThat(canvas.toolbarArgs).isEqualTo(null)

			// When: Setting toolbar node with specific class and arguments
			val expectedClass = InOut::class.java
			val expectedArgs = arrayOf<Any?>("testArg1", "testArg2")
			canvas.setNodeOnToolbar(expectedClass, expectedArgs)

			// Then: Toolbar state should be stored correctly
			assertThat(canvas.toolbarCellClass).isEqualTo(expectedClass)
			assertThat(canvas.toolbarArgs).isNotNull()
			assertThat(canvas.toolbarArgs!!.size).isEqualTo(2)
			assertThat(canvas.toolbarArgs!![0]).isEqualTo("testArg1")
			assertThat(canvas.toolbarArgs!![1]).isEqualTo("testArg2")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setNodeOnToolbar can clear toolbar state")
	fun setNodeOnToolbarCanClearToolbarState() {
		runOnEDT {
			// Given: Toolbar has been set
			canvas.setNodeOnToolbar(InOut::class.java, arrayOf("test"))
			assertThat(canvas.toolbarCellClass).isNotNull()

			// When: Clearing toolbar by setting null
			canvas.setNodeOnToolbar(null, null)

			// Then: Toolbar state should be null
			assertThat(canvas.toolbarCellClass).isEqualTo(null)
			assertThat(canvas.toolbarArgs).isEqualTo(null)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setNodeOnToolbar can replace toolbar state")
	fun setNodeOnToolbarCanReplaceToolbarState() {
		runOnEDT {
			// Given: Toolbar set with InOut
			canvas.setNodeOnToolbar(InOut::class.java, arrayOf("initial"))
			assertThat(canvas.toolbarCellClass).isEqualTo(InOut::class.java)

			// When: Replacing with RailSemaphore
			val newClass = cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore::class.java
			val newArgs = arrayOf<Any?>(true, SpatialType.HORIZONTAL)
			canvas.setNodeOnToolbar(newClass, newArgs)

			// Then: Toolbar state should be updated
			assertThat(canvas.toolbarCellClass).isEqualTo(newClass)
			assertThat(canvas.toolbarArgs).isNotNull()
			assertThat(canvas.toolbarArgs!!.size).isEqualTo(2)
			assertThat(canvas.toolbarArgs!![0]).isEqualTo(true)
			assertThat(canvas.toolbarArgs!![1]).isEqualTo(SpatialType.HORIZONTAL)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("getStatus returns empty string when no cell")
	fun getStatusReturnsEmptyStringWhenNoCell() {
		runOnEDT {
			// Given: Canvas with context but no cell at position
			canvas.setContext(editingContext)

			// When: Getting status for empty cell
			val mouseEvent =
				java.awt.event.MouseEvent(
					canvas,
					java.awt.event.MouseEvent.MOUSE_MOVED,
					System.currentTimeMillis(),
					0,
					0,
					0, // Position (0,0) - empty
					0,
					false
				)
			val status = canvas.getStatus(mouseEvent)

			// Then: Status should be empty string
			assertThat(status).isEqualTo("")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("getStatus returns cell info when cell exists")
	fun getStatusReturnsCellInfoWhenCellExists() {
		runOnEDT {
			// Given: Canvas with context and a cell
			canvas.setContext(editingContext)
			val cellPoint = Point(5, 5)
			val grid = editingContext.getRailWayNetGrid()
			val cell = grid.get(cellPoint)

			// When: Getting status for cell position
			val x = cellPoint.x * RailwayNetGridCanvas.getCellWidth()
			val y = cellPoint.y * RailwayNetGridCanvas.getCellHeight()
			val mouseEvent =
				java.awt.event.MouseEvent(
					canvas,
					java.awt.event.MouseEvent.MOUSE_MOVED,
					System.currentTimeMillis(),
					0,
					x,
					y,
					0,
					false
				)
			val status = canvas.getStatus(mouseEvent)

			// Then: Status should contain cell info
			assertThat(status).isEqualTo(cell.toString())
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("scrollable viewport size matches preferred size")
	fun scrollableViewportSizeMatchesPreferredSize() {
		runOnEDT {
			// Given: Canvas with context
			canvas.setContext(editingContext)

			// Then: Scrollable viewport size should match preferred size
			assertThat(canvas.preferredScrollableViewportSize).isEqualTo(canvas.preferredSize)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("scrollable tracks viewport height is false")
	fun scrollableTracksViewportHeightIsFalse() {
		runOnEDT {
			assertThat(canvas.scrollableTracksViewportHeight).isEqualTo(false)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("scrollable tracks viewport width is false")
	fun scrollableTracksViewportWidthIsFalse() {
		runOnEDT {
			assertThat(canvas.scrollableTracksViewportWidth).isEqualTo(false)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("scrollable block increment returns reasonable value")
	fun scrollableBlockIncrementReturnsReasonableValue() {
		runOnEDT {
			// Given: Canvas with context
			canvas.setContext(editingContext)

			// When: Getting block increment for horizontal scroll
			val visibleRect = java.awt.Rectangle(0, 0, 800, 600)
			val increment =
				canvas.getScrollableBlockIncrement(
					visibleRect,
					javax.swing.SwingConstants.HORIZONTAL,
					1
				)

			// Then: Increment should be less than viewport width
			assertThat(increment).isEqualTo(visibleRect.width - 35) // MAX_UNIT_INCREMENT = 35
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("scrollable unit increment returns positive value")
	fun scrollableUnitIncrementReturnsPositiveValue() {
		runOnEDT {
			// Given: Canvas with context
			canvas.setContext(editingContext)

			// When: Getting unit increment
			val visibleRect = java.awt.Rectangle(0, 0, 800, 600)
			val increment =
				canvas.getScrollableUnitIncrement(
					visibleRect,
					javax.swing.SwingConstants.HORIZONTAL,
					1 // Forward direction
				)

			// Then: Increment should be positive
			assertThat(increment > 0).isEqualTo(true)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("property change with Point triggers partial repaint")
	fun propertyChangeWithPointTriggersPartialRepaint() {
		runOnEDT {
			// Given: Canvas with context
			canvas.setContext(editingContext)

			// When: Property change with Point value
			val point = java.awt.Point(5, 5)
			val event = ContextChangeEvent("cell", null, point)
			canvas.propertyChange(event)

			// Then: Canvas should handle property change (no exception)
			// Repaint is called internally, we just verify no exception
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("property change without Point triggers full repaint")
	fun propertyChangeWithoutPointTriggersFullRepaint() {
		runOnEDT {
			// Given: Canvas with context
			canvas.setContext(editingContext)

			// When: Property change with non-Point value
			val event = ContextChangeEvent("property", null, "value")
			canvas.propertyChange(event)

			// Then: Canvas should handle property change (no exception)
			// Repaint is called internally, we just verify no exception
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("cell dimensions are accessible via static methods")
	fun cellDimensionsAreAccessibleViaStaticMethods() {
		runOnEDT {
			// Cell dimensions should be constant
			assertThat(RailwayNetGridCanvas.getCellWidth()).isEqualTo(16)
			assertThat(RailwayNetGridCanvas.getCellHeight()).isEqualTo(16)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("canvas has mouse motion listeners")
	fun canvasHasMouseMotionListeners() {
		runOnEDT {
			// Canvas should have at least one mouse motion listener (itself)
			val listeners = canvas.mouseMotionListeners
			assertThat(listeners.size > 0).isEqualTo(true)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("canvas has mouse listeners in editing mode")
	fun canvasHasMouseListenersInEditingMode() {
		runOnEDT {
			// Given: Canvas in editing mode
			canvas.setContext(editingContext)

			// Then: Canvas should have mouse listeners for editing
			val listeners = canvas.mouseListeners
			assertThat(listeners.size > 0).isEqualTo(true)
		}
	}
}
