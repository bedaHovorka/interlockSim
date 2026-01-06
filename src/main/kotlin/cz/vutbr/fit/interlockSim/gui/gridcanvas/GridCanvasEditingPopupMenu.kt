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

import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import java.awt.Point
import java.awt.event.ActionEvent

/**
 * Popup menu for the editing mode - allows deleting railway elements
 */
class GridCanvasEditingPopupMenu : GridCanvasPopupMenu() {
	// Delete action for both node cells and track blocks
	private inner class DeleteAction : PopupMenuAction("Delete") {
		override fun nodeCellAction(e: ActionEvent) {
			val editingContext = canvas!!.getEditingContext()
			editingContext.removeCell(key!!)
		}

		override fun trackLineAction(e: ActionEvent) {
			val editingContext = canvas!!.getEditingContext()
			editingContext.removeLine(trackBlock!!)
		}
	}

	private var key: Point? = null
	private var nodeCell: NodeCell? = null
	private var trackBlock: TrackBlock? = null

	init {
		add(DeleteAction())
	}

	override fun reorganizeMenu(line: TrackBlock) {
		trackBlock = line
	}

	override fun reorganizeMenu(
		key: Point,
		cell: NodeCell
	) {
		this.key = key
		this.nodeCell = cell
	}
}
