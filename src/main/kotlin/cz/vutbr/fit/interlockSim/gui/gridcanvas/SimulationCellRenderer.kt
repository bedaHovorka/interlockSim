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

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import java.awt.Color
import java.awt.Graphics2D

/**
 * Cell renderer for simulation mode - renders railway elements with dynamic state
 *
 * NOTE (Issue #153): Future enhancement will add animation support for simulation context.
 * Current implementation renders static configuration with basic dynamic state indicators.
 */
class SimulationCellRenderer(
	cellWidth: Int,
	cellHeight: Int
) : CellRenderer(cellWidth, cellHeight) {
	// Static cell rendering (should not normally appear in simulation grid)
	override fun draw(
		g: Graphics2D,
		cell: RailSwitch
	) {
		drawLine(g, cell.getSpatialType())
		val segments = cell.getBranchSegments().toTypedArray()
		drawSegments(g, *segments)
	}

	override fun draw(
		g: Graphics2D,
		cell: RailSemaphore
	) {
		drawLine(g, cell.getSpatialType())
		drawTriangle(g, cell)
	}

	override fun draw(
		g: Graphics2D,
		cell: TrackBlockPart
	) {
		drawSegments(g, *cell.getSegments())
	}

	override fun draw(
		g: Graphics2D,
		cell: InOut
	) {
		drawSegments(g, cell.direction())
		g.fillOval(getCellWidth() / 4, getCellHeight() / 4, getCellWidth() / 2, getCellHeight() / 2)
	}

	// Dynamic cell rendering - render configuration from static ref with dynamic state
	override fun draw(
		g: Graphics2D,
		cell: DynamicRailSwitch
	) {
		// Render base configuration from static reference
		val staticRef = cell.staticRef
		drawLine(g, staticRef.getSpatialType())
		val segments = staticRef.getBranchSegments().toTypedArray()
		drawSegments(g, *segments)

		// TODO (Issue #153): Add visual indicator for switch position (cell.conf)
		// TODO (Issue #153): Add visual indicator for locked state (cell.locked)
		// Future: Animate switch transitions
	}

	override fun draw(
		g: Graphics2D,
		cell: DynamicRailSemaphore
	) {
		// Render base configuration from static reference
		val staticRef = cell.staticRef
		drawLine(g, staticRef.getSpatialType())

		// Render signal state with color coding
		val oldColor = g.color
		g.color = when {
			cell.signal.isAllowing() -> Color.GREEN
			else -> Color.RED
		}
		drawTriangle(g, staticRef)
		g.color = oldColor

		// TODO (Issue #153): Add more sophisticated signal aspect rendering
		// Future: Animate signal changes
	}

	override fun draw(
		g: Graphics2D,
		cell: DynamicInOut
	) {
		// Render base configuration from static reference
		val staticRef = cell.staticRef
		drawSegments(g, staticRef.direction())
		g.fillOval(getCellWidth() / 4, getCellHeight() / 4, getCellWidth() / 2, getCellHeight() / 2)

		// TODO (Issue #153): Add visual indicator for occupancy state
		// Future: Animate train entry/exit
	}

	// EXTENSION - additional railway element renderers can be added here
}
