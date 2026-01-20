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
		drawStaticRailSwitch(g, cell)
	}

	override fun draw(
		g: Graphics2D,
		cell: RailSemaphore
	) {
		drawStaticRailSemaphore(g, cell)
	}

	override fun draw(
		g: Graphics2D,
		cell: TrackBlockPart
	) {
		drawStaticTrackBlockPart(g, cell)
	}

	override fun draw(
		g: Graphics2D,
		cell: InOut
	) {
		drawStaticInOut(g, cell)
	}

	// Dynamic cell rendering - extract static reference and delegate to static methods
	override fun draw(
		g: Graphics2D,
		cell: DynamicRailSwitch
	) {
		draw(g, cell.staticRef)
	}

	override fun draw(
		g: Graphics2D,
		cell: DynamicRailSemaphore
	) {
		draw(g, cell.staticRef)
	}

	override fun draw(
		g: Graphics2D,
		cell: DynamicInOut
	) {
		draw(g, cell.staticRef)
	}

	// EXTENSION - additional railway element renderers can be added here
}
