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

import static java.lang.Math.round;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.EnumMap;

import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch;
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator;

/**
 * "Zobrazovac bunek"
 *
 */
public abstract class CellRenderer {
	protected final static EnumMap<Cell.SpatialType, Double> angles = new EnumMap<Cell.SpatialType, Double>(Cell.SpatialType.class);

	static {
	angles.put(Cell.SpatialType.HORIZONTAL, 0.0);
	angles.put(Cell.SpatialType.VERTICAL, Math.PI/2);
	angles.put(Cell.SpatialType.DIAGONAL1, - Math.PI/4);
	angles.put(Cell.SpatialType.DIAGONAL2, Math.PI/4);
	}

	private int cellWidth;
	private int cellHeight;

	public CellRenderer(int cellWidth, int cellHeight) {
		restart(cellWidth, cellHeight);
	}

	public final void restart(int cellWidth, int cellHeight) {
		this.cellWidth = cellWidth;
		this.cellHeight = cellHeight;
	}

	protected void drawSegments(Graphics2D g, Segment... segments) {
		Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(2));
		for (Segment s : segments) {
			g.drawLine(round(cellWidth*s.getRx()), round(cellHeight*s.getRy()), cellWidth/2, cellHeight/2);
		}
		g.setStroke(oldStroke);
	}

	protected void drawSegments(Graphics2D g, Collection<Segment> segments) {
		if (segments == null) return;
		drawSegments(g, segments.toArray(new Segment[0]));
	}

	protected void drawLine(Graphics2D g, Cell.SpatialType type) {
		drawSegments(g, type.getSegments());
	}

	protected void drawTriangle(Graphics2D g, OrientedPathSeparator cell) {
		final Double thetaObj = angles.get(cell.getSpatialType());
		assert (thetaObj != null);

		final double theta = (cell.getOrientation()) ? thetaObj.doubleValue() + Math.PI : thetaObj.doubleValue();
		final int[] xs = new int[]{cellWidth/4,cellWidth/4,3*cellWidth/4};
		final int[] ys = new int[]{cellHeight/4,3*cellHeight/4,cellHeight/2};

		final AffineTransform transform = g.getTransform();
		g.rotate(theta, cellWidth/2, cellHeight/2);
		g.fillPolygon(xs, ys, 3);
		g.drawPolygon(xs, ys, 3);
		g.setTransform(transform);
	}

	public final void draw(Graphics2D g, Cell cell) {
		assert cell != null;

		try { //EXTENSION efektivneji s Map
			Method method = getClass().getMethod("draw", Graphics2D.class, cell.getClass());
			method.invoke(this, g, cell);
		} catch (Exception e) {
			//e.printStackTrace();
			assert(false) : e;
		}
	}

	public abstract void draw(Graphics2D g, RailSwitch cell);

	public abstract void draw(Graphics2D g, RailSemaphore cell);

	public abstract void draw(Graphics2D g, TrackBlockPart cell);

	public abstract void draw(Graphics2D g, InOut cell);

	public int getCellHeight() {
		return cellHeight;
	}

	public int getCellWidth() {
		return cellWidth;
	}
}
