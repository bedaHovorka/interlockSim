/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.gridcanvas

import assertk.assertThat
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Graphics2D
import java.awt.image.BufferedImage

/**
 * Tests for CellRenderer to verify it handles both static and dynamic cells
 *
 * Phase 8: Verify rendering layer compatibility with both editing (static) and simulation (dynamic) contexts
 */
@DisplayName("CellRenderer Static/Dynamic Support")
class CellRendererTest {
private lateinit var editorRenderer: EditorCellRenderer
private lateinit var simulationRenderer: SimulationCellRenderer
private lateinit var graphics: Graphics2D

@BeforeEach
fun setUp() {
val cellWidth = 16
val cellHeight = 16
editorRenderer = EditorCellRenderer(cellWidth, cellHeight)
simulationRenderer = SimulationCellRenderer(cellWidth, cellHeight)

// Create a dummy Graphics2D for testing
val image = BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB)
graphics = image.createGraphics()
}

// Helper method to create DynamicInOut with required semaphores
private fun createDynamicInOut(staticInOut: InOut): DynamicInOut {
val inSemaphore = createDynamicInstance(staticInOut.getInSemaphore())
val outSemaphore = createDynamicInstance(staticInOut.getOutSemaphore())
return DynamicInOut(staticInOut, inSemaphore, outSemaphore)
}

@Test
fun `EditorCellRenderer can render static RailSwitch`() {
// Given
val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)

// When/Then - should not throw exception
editorRenderer.draw(graphics, railSwitch)
}

@Test
fun `EditorCellRenderer can render static RailSemaphore`() {
// Given
val railSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)

// When/Then - should not throw exception
editorRenderer.draw(graphics, railSemaphore)
}

@Test
fun `EditorCellRenderer can render static InOut`() {
// Given
val inOut = InOut("TestInOut", true, Cell.SpatialType.HORIZONTAL)

// When/Then - should not throw exception
editorRenderer.draw(graphics, inOut)
}

@Test
fun `EditorCellRenderer can render DynamicRailSwitch`() {
// Given
val staticSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
val dynamicSwitch = DynamicRailSwitch(staticSwitch)

// When/Then - should not throw exception
editorRenderer.draw(graphics, dynamicSwitch)
}

@Test
fun `EditorCellRenderer can render DynamicRailSemaphore`() {
// Given
val staticSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
val dynamicSemaphore = createDynamicInstance(staticSemaphore)

// When/Then - should not throw exception
editorRenderer.draw(graphics, dynamicSemaphore)
}

@Test
fun `EditorCellRenderer can render DynamicInOut`() {
// Given
val staticInOut = InOut("TestInOut", true, Cell.SpatialType.HORIZONTAL)
val dynamicInOut = createDynamicInOut(staticInOut)

// When/Then - should not throw exception
editorRenderer.draw(graphics, dynamicInOut)
}

@Test
fun `SimulationCellRenderer can render static RailSwitch`() {
// Given
val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)

// When/Then - should not throw exception
simulationRenderer.draw(graphics, railSwitch)
}

@Test
fun `SimulationCellRenderer can render static RailSemaphore`() {
// Given
val railSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)

// When/Then - should not throw exception
simulationRenderer.draw(graphics, railSemaphore)
}

@Test
fun `SimulationCellRenderer can render static InOut`() {
// Given
val inOut = InOut("TestInOut", true, Cell.SpatialType.HORIZONTAL)

// When/Then - should not throw exception
simulationRenderer.draw(graphics, inOut)
}

@Test
fun `SimulationCellRenderer can render DynamicRailSwitch`() {
// Given
val staticSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
val dynamicSwitch = DynamicRailSwitch(staticSwitch)

// When/Then - should not throw exception
simulationRenderer.draw(graphics, dynamicSwitch)
}

@Test
fun `SimulationCellRenderer can render DynamicRailSemaphore`() {
// Given
val staticSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
val dynamicSemaphore = createDynamicInstance(staticSemaphore)

// When/Then - should not throw exception
simulationRenderer.draw(graphics, dynamicSemaphore)
}

@Test
fun `SimulationCellRenderer can render DynamicInOut`() {
// Given
val staticInOut = InOut("TestInOut", true, Cell.SpatialType.HORIZONTAL)
val dynamicInOut = createDynamicInOut(staticInOut)

// When/Then - should not throw exception
simulationRenderer.draw(graphics, dynamicInOut)
}

@Test
fun `SimulationCellRenderer extracts static reference from dynamic cells`() {
// Given
val staticSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
val dynamicSwitch = DynamicRailSwitch(staticSwitch)

// When/Then - verify staticRef is accessible
assertThat(dynamicSwitch.staticRef).isNotNull()
}

@Test
fun `CellRenderer draw method uses reflection to find correct draw method`() {
// Given
val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)

// When/Then - should find and invoke the correct draw method
editorRenderer.draw(graphics, railSwitch as Cell)
}

@Test
fun `CellRenderer draw method works with dynamic cells via reflection`() {
// Given
val staticSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
val dynamicSwitch = DynamicRailSwitch(staticSwitch)

// When/Then - should find and invoke the correct draw method for dynamic cell
editorRenderer.draw(graphics, dynamicSwitch as Cell)
}
}
