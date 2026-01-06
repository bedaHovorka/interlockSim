/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.gridcanvas;

import java.awt.Point;
import java.awt.event.ActionEvent;

import cz.vutbr.fit.interlockSim.context.EditingContext;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;

public class GridCanvasEditingPopupMenu extends GridCanvasPopupMenu {

	private  class DeleteAction extends PopupMenuAction {

	public DeleteAction() {
		super("Delete");
	}

	@Override
	protected void nodeCellAction(ActionEvent e) {
		final EditingContext editingContext = canvas.getEditingContext();
		editingContext.removeCell(key);
	}

	@Override
	protected void trackLineAction(ActionEvent e) {
		final EditingContext editingContext = canvas.getEditingContext();
		editingContext.removeLine(trackBlock);
	}

	}

	private Point  key;
	private NodeCell nodeCell;
	private TrackBlock trackBlock;

	public GridCanvasEditingPopupMenu() {
		add(new DeleteAction());
	}

	@Override
	protected void reorganizeMenu(TrackBlock line) {
		this.trackBlock = line;
	}

	@Override
	protected void reorganizeMenu(Point keyx, NodeCell cell) {
		this.key = keyx;
		this.nodeCell = cell;
	}

}
