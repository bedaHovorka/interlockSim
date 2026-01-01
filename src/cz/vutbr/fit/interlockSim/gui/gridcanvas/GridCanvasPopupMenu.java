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
import java.awt.event.MouseEvent;

import javax.swing.AbstractAction;
import javax.swing.JPopupMenu;

import cz.vutbr.fit.interlockSim.gui.RailwayNetGridCanvas;
import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;

/**
 * EXTENSION
 */
public abstract class GridCanvasPopupMenu extends JPopupMenu {
	protected enum State {
		NODECELL,
		TRACKLINE
	}

	protected abstract class PopupMenuAction extends AbstractAction {

	public PopupMenuAction(String n) {
		super(n);
	}

	public final void actionPerformed(ActionEvent e) {
		if (getState() == State.NODECELL) {
		nodeCellAction(e);
		} else if (getState() == State.TRACKLINE) {
		trackLineAction(e);
		} else assert false;
	}

	protected abstract void nodeCellAction(ActionEvent e);
	protected abstract void trackLineAction(ActionEvent e);
	}

	private State state;
	protected RailwayNetGridCanvas canvas;

	public final void show(RailwayNetGridCanvas canvas, MouseEvent e, Point key, Cell cell) {
	assert canvas != null && e != null;
	if (key == null || cell == null) return;
	this.canvas = canvas;
	reorganizeMenu(key, cell);
	show(canvas, e.getX(), e.getY());
	}

	private final void reorganizeMenu(Point key, Cell cell) {
	if (cell instanceof NodeCell) {
		state = State.NODECELL;
		reorganizeMenu(key, (NodeCell) cell);
	} else if (cell instanceof TrackBlockPart) {
		TrackBlockPart linePart = (TrackBlockPart) cell;
		state = State.TRACKLINE;
		reorganizeMenu(linePart.getTrackBlock());
	} else assert(false) : cell;
	}

	protected abstract void reorganizeMenu(TrackBlock line);

	protected abstract void reorganizeMenu(Point key, NodeCell cell);

	protected State getState() {
		return state;
	}
}
