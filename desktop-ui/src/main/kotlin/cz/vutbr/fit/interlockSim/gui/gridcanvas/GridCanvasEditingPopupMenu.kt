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

import cz.vutbr.fit.interlockSim.gui.RenameDialog
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import java.awt.event.ActionEvent

/**
 * Popup menu for the editing mode - allows renaming and deleting railway elements
 */
class GridCanvasEditingPopupMenu : GridCanvasPopupMenu() {
	// Rename action for node cells
	private inner class RenameAction : PopupMenuAction("Rename...") {
		override fun nodeCellAction(e: ActionEvent) {
			// Show rename dialog
			val dialog =
				RenameDialog(
					canvas,
					nodeCell!!.getName(),
					nodeCell!!.javaClass.simpleName
				)

			val (result, newName) = dialog.showDialog()

			if (result == RenameDialog.DialogResult.OK && newName != nodeCell!!.getName()) {
				// Create updated cell via wither pattern and replace in grid without
				// triggering CELL_ADDED or corrupting graph/inouts bookkeeping
				val editingContext = canvas!!.getEditingContext()
				val updated = nodeCell!!.withName(newName)
				editingContext.replaceCell(key!!, updated)
				nodeCell = updated

				// Repaint canvas to reflect changes
				canvas!!.repaint()
			}
		}

		override fun trackLineAction(e: ActionEvent) {
			// Track lines don't have names - no action
		}
	}

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

	private var key: cz.vutbr.fit.interlockSim.util.Point? = null
	private var nodeCell: NodeCell? = null
	private var trackBlock: TrackBlock? = null

	init {
		add(RenameAction())
		add(DeleteAction())
	}

	override fun reorganizeMenu(line: TrackBlock) {
		trackBlock = line
	}

	override fun reorganizeMenu(
		key: cz.vutbr.fit.interlockSim.util.Point,
		cell: NodeCell
	) {
		this.key = key
		this.nodeCell = cell
	}
}
