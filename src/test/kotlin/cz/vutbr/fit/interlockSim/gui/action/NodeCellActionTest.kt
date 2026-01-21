/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit tests for NodeCellAction class
 * Phase 1.1 High-Impact Quick Wins - Test Coverage Improvement
 */

package cz.vutbr.fit.interlockSim.gui.action

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.gui.AbstractFrameTestBase
import cz.vutbr.fit.interlockSim.gui.Frame
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.get
import java.awt.event.ActionEvent
import javax.swing.ImageIcon

/**
 * Integration tests for NodeCellAction class
 *
 * Tests cover:
 * - Action construction with cell class and arguments
 * - Action name formatting
 * - Icon generation via paintIcon() companion method
 * - Action performed delegation to RailwayNetGridCanvas
 * - Integration with Koin DI (Frame, EditingContextFactory)
 * - Error handling in icon creation
 *
 * Note: These are integration tests that use real Koin dependencies since
 * NodeCellAction uses getKoin() directly to resolve EditingContextFactory and Frame.
 * Tests are skipped in headless CI environments (no X11 display).
 *
 * Coverage target: 80%+ for gui.action package (156 instructions)
 * GitHub Issue: #111 (CI headless environment support)
 */
@DisplayName("NodeCellAction")
class NodeCellActionTest : AbstractFrameTestBase() {
	private lateinit var context: EditingContext
	private lateinit var factory: EditingContextFactory

	@BeforeEach
	override fun setUp() {
		// Check for headless environment (AbstractFrameTestBase)
		super.setUp()

		// Get real Koin dependencies (required since NodeCellAction uses getKoin())
		factory = get<EditingContextFactory>()
		context = factory.createEmptyContext()
	}

/**
 * Test 1: Constructor creates action with correct name format
 */
@Test
fun `constructor creates action with correct name format`() {
val cellClass = RailSwitch::class.java
val args = arrayOf<Any>(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)

val action = NodeCellAction(cellClass, context, args)

val actionName = action.getValue(javax.swing.Action.NAME) as String
assertThat(actionName).isEqualTo("Insert RailSwitch")
}

/**
 * Test 2: Action has valid icon generated from cell
 */
@Test
fun `action has valid icon generated from cell`() {
val cellClass = InOut::class.java
val args = arrayOf<Any>("Entry", true, Cell.SpatialType.HORIZONTAL)

val action = NodeCellAction(cellClass, context, args)

val icon = action.getValue(javax.swing.Action.SMALL_ICON)
assertThat(icon).isNotNull()
assertThat(icon).isInstanceOf(ImageIcon::class)
}

/**
 * Test 3: actionPerformed delegates to RailwayNetGridCanvas.setNodeOnToolbar
 */
@Test
fun `actionPerformed delegates to RailwayNetGridCanvas setNodeOnToolbar`() {
val cellClass = RailSemaphore::class.java
val args = arrayOf<Any>(true, Cell.SpatialType.HORIZONTAL)
val action = NodeCellAction(cellClass, context, args)

val frame = get<Frame>()
val canvas = frame.railwayNetGridCanvas

val mockEvent = ActionEvent(action, ActionEvent.ACTION_PERFORMED, "")
action.actionPerformed(mockEvent)

// Verify setNodeOnToolbar was called with correct arguments
val actualCellClass = canvas.getToolbarCellClass()
val actualArgs = canvas.getToolbarArgs()

assertThat(actualCellClass).isEqualTo(cellClass)
assertThat(actualArgs).isNotNull()
assertThat(actualArgs!!.size).isEqualTo(args.size)
}

/**
 * Test 4: Icon generation works for different cell types
 */
@Test
fun `icon generation works for different cell types`() {
val testCases = listOf(
RailSwitch::class.java to arrayOf<Any>(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_FALSE),
RailSemaphore::class.java to arrayOf<Any>(true, Cell.SpatialType.HORIZONTAL),
InOut::class.java to arrayOf<Any>("Exit", false, Cell.SpatialType.HORIZONTAL)
)

testCases.forEach { (cellClass, args) ->
val action = NodeCellAction(cellClass, context, args)
val icon = action.getValue(javax.swing.Action.SMALL_ICON)
assertThat(icon).isNotNull()
assertThat(icon).isInstanceOf(ImageIcon::class)
}
}

/**
 * Test 5: Error handling in paintIcon when cell creation fails
 */
@Test
fun `paintIcon handles cell creation failure with error`() {
val cellClass = RailSwitch::class.java
val invalidArgs = arrayOf<Any>() // Missing required constructor arguments

assertFailure {
NodeCellAction(cellClass, context, invalidArgs)
}.isInstanceOf(IllegalStateException::class)
}

/**
 * Test 6: Action properly handles RailSemaphore with required arguments
 */
@Test
fun `action properly handles RailSemaphore with required arguments`() {
val cellClass = RailSemaphore::class.java
val args = arrayOf<Any>(false, Cell.SpatialType.VERTICAL)

val action = NodeCellAction(cellClass, context, args)
val frame = get<Frame>()
val canvas = frame.railwayNetGridCanvas

val mockEvent = ActionEvent(action, ActionEvent.ACTION_PERFORMED, "")
action.actionPerformed(mockEvent)

// Verify toolbar state was updated
val actualCellClass = canvas.getToolbarCellClass()

assertThat(actualCellClass).isEqualTo(cellClass)
}

/**
 * Test 7: Action properly handles multiple arguments
 */
@Test
fun `action properly handles multiple arguments`() {
val cellClass = RailSwitch::class.java
val args = arrayOf<Any>(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)

val action = NodeCellAction(cellClass, context, args)
val frame = get<Frame>()
val canvas = frame.railwayNetGridCanvas

val mockEvent = ActionEvent(action, ActionEvent.ACTION_PERFORMED, "")
action.actionPerformed(mockEvent)

// Verify toolbar state and icon
assertThat(action.getValue(javax.swing.Action.SMALL_ICON)).isNotNull()

val actualCellClass = canvas.getToolbarCellClass()

assertThat(actualCellClass).isEqualTo(cellClass)
}

/**
 * Test 8: Multiple actions can be created for same cell type
 */
@Test
fun `multiple actions can be created for same cell type`() {
val cellClass = RailSwitch::class.java
val args1 = arrayOf<Any>(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_FALSE)
val args2 = arrayOf<Any>(Cell.SpatialType.VERTICAL, RailSwitch.Type.SIMPLE_RIGHT_TRUE)

val action1 = NodeCellAction(cellClass, context, args1)
val action2 = NodeCellAction(cellClass, context, args2)

assertThat(action1.getValue(javax.swing.Action.SMALL_ICON)).isNotNull()
assertThat(action2.getValue(javax.swing.Action.SMALL_ICON)).isNotNull()
assertThat(action1.getValue(javax.swing.Action.NAME)).isEqualTo("Insert RailSwitch")
assertThat(action2.getValue(javax.swing.Action.NAME)).isEqualTo("Insert RailSwitch")
}

/**
 * Test 9: Action name contains cell class simple name
 */
@Test
fun `action name contains cell class simple name`() {
val testCases = mapOf(
RailSwitch::class.java to "RailSwitch",
RailSemaphore::class.java to "RailSemaphore",
InOut::class.java to "InOut"
)

testCases.forEach { (cellClass, expectedName) ->
val args = when (cellClass) {
RailSwitch::class.java -> arrayOf<Any>(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_FALSE)
InOut::class.java -> arrayOf<Any>("Station", true, Cell.SpatialType.HORIZONTAL)
else -> arrayOf<Any>(true, Cell.SpatialType.HORIZONTAL)
}

val action = NodeCellAction(cellClass, context, args)
val actionName = action.getValue(javax.swing.Action.NAME) as String

assertThat(actionName).contains(expectedName)
}
}
}
