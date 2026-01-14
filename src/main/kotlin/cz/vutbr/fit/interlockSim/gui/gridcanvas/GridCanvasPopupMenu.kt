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

import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.gui.RailwayNetGridCanvas
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JPopupMenu

/**
 * Base popup menu for grid canvas context menus
 */
abstract class GridCanvasPopupMenu : JPopupMenu() {
	// State of the popup menu based on what cell was clicked
	protected enum class State {
		NODECELL,
		TRACKLINE
	}

	// Base class for popup menu actions with state-aware behavior
	protected abstract inner class PopupMenuAction(
		name: String
	) : AbstractAction(name) {
		final override fun actionPerformed(e: ActionEvent) {
			when (state) {
				State.NODECELL -> nodeCellAction(e)
				State.TRACKLINE -> trackLineAction(e)
				else -> error("Unknown state: $state")
			}
		}

		protected abstract fun nodeCellAction(e: ActionEvent)

		protected abstract fun trackLineAction(e: ActionEvent)
	}

	private var state: State? = null
	protected var canvas: RailwayNetGridCanvas? = null

	// Show the popup menu at the specified location
	fun show(
		canvas: RailwayNetGridCanvas,
		e: MouseEvent,
		key: cz.vutbr.fit.interlockSim.util.Point?,
		cell: Cell?
	) {
		if (key == null || cell == null) return
		this.canvas = canvas
		reorganizeMenu(key, cell)
		show(canvas, e.x, e.y)
	}

	// Reorganize menu based on the clicked cell type
	private fun reorganizeMenu(
		key: cz.vutbr.fit.interlockSim.util.Point,
		cell: Cell
	) {
		when (cell) {
			is NodeCell -> {
				state = State.NODECELL
				reorganizeMenu(key, cell)
			}
			is TrackBlockPart -> {
				state = State.TRACKLINE
				reorganizeMenu(cell.getTrackBlock())
			}
			else -> error("Unknown cell type: $cell")
		}
	}

	// Abstract methods to be implemented by subclasses
	protected abstract fun reorganizeMenu(line: TrackBlock)

	protected abstract fun reorganizeMenu(
		key: cz.vutbr.fit.interlockSim.util.Point,
		cell: NodeCell
	)

	protected fun getState(): State? = state
}
