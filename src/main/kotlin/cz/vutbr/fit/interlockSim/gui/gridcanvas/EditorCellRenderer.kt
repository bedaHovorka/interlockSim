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

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import java.awt.Graphics2D

/**
 * Cell renderer for editing mode - renders all railway elements
 */
class EditorCellRenderer(
	cellWidth: Int,
	cellHeight: Int
) : CellRenderer(cellWidth, cellHeight) {
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

	// EXTENSION - additional railway element renderers can be added here
}
