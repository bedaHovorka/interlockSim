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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Graphics2D

/**
 * Tests for CellRenderer to verify it handles both static and dynamic cells
 *
 * Phase 8: Verify rendering layer compatibility with both editing (static) and simulation (dynamic) contexts
 */
@DisplayName("CellRenderer Static/Dynamic Support")
class CellRendererTest {
	private lateinit var editorRenderer: EditorCellRenderer
	private lateinit var simulationRenderer: SimulationCellRenderer

	@BeforeEach
	fun setUp() {
		val cellWidth = 16
		val cellHeight = 16
		editorRenderer = EditorCellRenderer(cellWidth, cellHeight)
		simulationRenderer = SimulationCellRenderer(cellWidth, cellHeight)
	}

// Helper method to create DynamicInOut with required semaphores
	private fun createDynamicInOut(staticInOut: InOut): DynamicInOut {
		val inSemaphore = createDynamicInstance(staticInOut.inSemaphore)
		val outSemaphore = createDynamicInstance(staticInOut.outSemaphore)
		return DynamicInOut(staticInOut, inSemaphore, outSemaphore)
	}

	@Test
	fun `EditorCellRenderer can render static RailSwitch`() {
		// Given
		val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		editorRenderer.draw(graphicsMock, railSwitch)

		// Then - verify drawing operations occurred with expected coordinates
		// HORIZONTAL draws line from left (0,8) to center (8,8) and right (16,8) to center
		verify(atLeast = 1) { graphicsMock.drawLine(0, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.drawLine(16, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.stroke = any() }
	}

	@Test
	fun `EditorCellRenderer can render static RailSemaphore`() {
		// Given
		val railSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		editorRenderer.draw(graphicsMock, railSemaphore)

		// Then - verify drawing operations occurred (semaphore draws line + triangle)
		verify(atLeast = 1) { graphicsMock.drawLine(0, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.drawLine(16, 8, 8, 8) }
		// Triangle coordinates: xs=[4,4,12], ys=[4,12,8] for 16x16 cell
		val expectedXs = intArrayOf(4, 4, 12)
		val expectedYs = intArrayOf(4, 12, 8)
		verify { graphicsMock.fillPolygon(expectedXs, expectedYs, 3) }
		verify { graphicsMock.drawPolygon(expectedXs, expectedYs, 3) }
	}

	@Test
	fun `EditorCellRenderer can render static InOut`() {
		// Given
		val inOut = InOut("TestInOut", true, Cell.SpatialType.HORIZONTAL)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		editorRenderer.draw(graphicsMock, inOut)

		// Then - verify drawing operations occurred (InOut draws segment + oval)
		verify { graphicsMock.drawLine(0, 8, 8, 8) }
		// fillOval at (cellWidth/4, cellHeight/4, cellWidth/2, cellHeight/2) = (4, 4, 8, 8)
		verify { graphicsMock.fillOval(4, 4, 8, 8) }
	}

	@Test
	fun `EditorCellRenderer can render DynamicRailSwitch`() {
		// Given
		val staticSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		val dynamicSwitch = DynamicRailSwitch(staticSwitch)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		editorRenderer.draw(graphicsMock, dynamicSwitch)

		// Then - verify drawing operations occurred (delegates to static switch rendering)
		verify(atLeast = 1) { graphicsMock.drawLine(0, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.drawLine(16, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.stroke = any() }
	}

	@Test
	fun `EditorCellRenderer can render DynamicRailSemaphore`() {
		// Given
		val staticSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		val dynamicSemaphore = createDynamicInstance(staticSemaphore)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		editorRenderer.draw(graphicsMock, dynamicSemaphore)

		// Then - verify drawing operations occurred (delegates to static semaphore rendering)
		verify(atLeast = 1) { graphicsMock.drawLine(0, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.drawLine(16, 8, 8, 8) }
		val expectedXs = intArrayOf(4, 4, 12)
		val expectedYs = intArrayOf(4, 12, 8)
		verify { graphicsMock.fillPolygon(expectedXs, expectedYs, 3) }
		verify { graphicsMock.drawPolygon(expectedXs, expectedYs, 3) }
	}

	@Test
	fun `EditorCellRenderer can render DynamicInOut`() {
		// Given
		val staticInOut = InOut("TestInOut", true, Cell.SpatialType.HORIZONTAL)
		val dynamicInOut = createDynamicInOut(staticInOut)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		editorRenderer.draw(graphicsMock, dynamicInOut)

		// Then - verify drawing operations occurred (delegates to static InOut rendering)
		verify { graphicsMock.drawLine(0, 8, 8, 8) }
		verify { graphicsMock.fillOval(4, 4, 8, 8) }
	}

	@Test
	fun `SimulationCellRenderer can render static RailSwitch`() {
		// Given
		val railSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		simulationRenderer.draw(graphicsMock, railSwitch)

		// Then - verify drawing operations occurred with expected coordinates
		verify(atLeast = 1) { graphicsMock.drawLine(0, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.drawLine(16, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.stroke = any() }
	}

	@Test
	fun `SimulationCellRenderer can render static RailSemaphore`() {
		// Given
		val railSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		simulationRenderer.draw(graphicsMock, railSemaphore)

		// Then - verify drawing operations occurred (semaphore draws line + triangle)
		verify(atLeast = 1) { graphicsMock.drawLine(0, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.drawLine(16, 8, 8, 8) }
		val expectedXs = intArrayOf(4, 4, 12)
		val expectedYs = intArrayOf(4, 12, 8)
		verify { graphicsMock.fillPolygon(expectedXs, expectedYs, 3) }
		verify { graphicsMock.drawPolygon(expectedXs, expectedYs, 3) }
	}

	@Test
	fun `SimulationCellRenderer can render static InOut`() {
		// Given
		val inOut = InOut("TestInOut", true, Cell.SpatialType.HORIZONTAL)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		simulationRenderer.draw(graphicsMock, inOut)

		// Then - verify drawing operations occurred (InOut draws segment + oval)
		verify { graphicsMock.drawLine(0, 8, 8, 8) }
		verify { graphicsMock.fillOval(4, 4, 8, 8) }
	}

	@Test
	fun `SimulationCellRenderer can render DynamicRailSwitch`() {
		// Given
		val staticSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		val dynamicSwitch = DynamicRailSwitch(staticSwitch)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		simulationRenderer.draw(graphicsMock, dynamicSwitch)

		// Then - verify drawing operations occurred
		verify(atLeast = 1) { graphicsMock.drawLine(0, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.drawLine(16, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.stroke = any() }
	}

	@Test
	fun `SimulationCellRenderer can render DynamicRailSemaphore`() {
		// Given
		val staticSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		val dynamicSemaphore = createDynamicInstance(staticSemaphore)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		simulationRenderer.draw(graphicsMock, dynamicSemaphore)

		// Then - verify drawing operations occurred
		verify(atLeast = 1) { graphicsMock.drawLine(0, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.drawLine(16, 8, 8, 8) }
		val expectedXs = intArrayOf(4, 4, 12)
		val expectedYs = intArrayOf(4, 12, 8)
		verify { graphicsMock.fillPolygon(expectedXs, expectedYs, 3) }
		verify { graphicsMock.drawPolygon(expectedXs, expectedYs, 3) }
	}

	@Test
	fun `SimulationCellRenderer can render DynamicInOut`() {
		// Given
		val staticInOut = InOut("TestInOut", true, Cell.SpatialType.HORIZONTAL)
		val dynamicInOut = createDynamicInOut(staticInOut)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When
		simulationRenderer.draw(graphicsMock, dynamicInOut)

		// Then - verify drawing operations occurred
		verify { graphicsMock.drawLine(0, 8, 8, 8) }
		verify { graphicsMock.fillOval(4, 4, 8, 8) }
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
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When - invoke via Cell interface (tests reflection dispatch)
		editorRenderer.draw(graphicsMock, railSwitch as Cell)

		// Then - verify correct polymorphic draw method was invoked
		verify(atLeast = 1) { graphicsMock.drawLine(0, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.drawLine(16, 8, 8, 8) }
	}

	@Test
	fun `CellRenderer draw method works with dynamic cells via reflection`() {
		// Given
		val staticSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		val dynamicSwitch = DynamicRailSwitch(staticSwitch)
		val graphicsMock = mockk<Graphics2D>(relaxed = true)

		// When - invoke via Cell interface (tests reflection dispatch for dynamic cell)
		editorRenderer.draw(graphicsMock, dynamicSwitch as Cell)

		// Then - verify correct polymorphic draw method was invoked
		verify(atLeast = 1) { graphicsMock.drawLine(0, 8, 8, 8) }
		verify(atLeast = 1) { graphicsMock.drawLine(16, 8, 8, 8) }
	}
}
