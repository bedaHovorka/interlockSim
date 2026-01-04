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

import java.awt.Graphics2D;

import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch;
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart;

/**
 *
 *
 */
public class EditorCellRenderer extends CellRenderer {
	/**
	 *
	 * @param cellWidth
	 * @param cellHeight
	 */
	public EditorCellRenderer(int cellWidth, int cellHeight) {
		super(cellWidth, cellHeight);
	}

	@Override
	public void draw(Graphics2D g, RailSwitch cell) {
		drawLine(g, cell.getSpatialType());
		drawSegments(g, cell.getBranchSegments());
	}

	@Override
	public void draw(Graphics2D g, RailSemaphore cell) {
		drawLine(g, cell.getSpatialType());
		drawTriangle(g, cell);
	}

	@Override
	public void draw(Graphics2D g, TrackBlockPart cell) {
		drawSegments(g, cell.getSegments());
	}

	@Override
	public void draw(Graphics2D g, InOut cell) {
		drawSegments(g, cell.direction());
		g.fillOval(getCellWidth()/4, getCellHeight()/4, getCellWidth()/2, getCellHeight()/2);
	}

	 // EXTENSION dalsi prvky
}
