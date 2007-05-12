/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.Observable;
import java.util.Observer;
import java.util.Map.Entry;

import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

import cz.vutbr.fit.interlockSim.Main;
import cz.vutbr.fit.interlockSim.context.Context;
import cz.vutbr.fit.interlockSim.context.EditingContext;
import cz.vutbr.fit.interlockSim.context.EditingContextFactory;
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid;
import cz.vutbr.fit.interlockSim.context.SimulationContext;
import cz.vutbr.fit.interlockSim.gui.gridcanvas.CellRenderer;
import cz.vutbr.fit.interlockSim.gui.gridcanvas.EditorCellRenderer;
import cz.vutbr.fit.interlockSim.gui.gridcanvas.GridCanvasEditingPopupMenu;
import cz.vutbr.fit.interlockSim.gui.gridcanvas.GridCanvasPopupMenu;
import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;

/**
 * This is a main GUI component in a program. It show railway elements in a grid.
 *
 */
public class RailwayNetGridCanvas extends JComponent implements Scrollable, MouseMotionListener, StatusProducer, Observer {
	private static final int maxUnitIncrement = 35;
	private static final int cellWidth = 16;
	private static final int cellHeight = 16;
	private boolean showGrid = false;
	private Context context;
	private GridMouseEditListener editListener = new GridMouseEditListener();
	private GridMouseSimulationControlListener simulationControlListener = new GridMouseSimulationControlListener();
	
	private State state;
	private Class<? extends NodeCell> toolbarCellClass;
	private Object[] toolbarArgs;
	private Point selectedkey;
	
	private abstract class GridMouseAdapter implements MouseMotionListener, MouseListener {

		public void mouseDragged(MouseEvent e) {
			//EMPTY			
		}

		public void mouseMoved(MouseEvent e) {
//			EMPTY
		}

		public final void mouseClicked(MouseEvent e) {
		    switch (e.getButton()) {
		    case MouseEvent.BUTTON1:
			leftMouseClicked(e);
			break;
		    case MouseEvent.BUTTON2:
			middleMouseClicked(e);
			break;
		    case MouseEvent.BUTTON3:
			rightMouseClicked(e);
		    	break;
		    default:
		    	assert(false);
		    }
		}
		
		public final void rightMouseClicked(MouseEvent e) {
		    state.getPopupMenu().show(RailwayNetGridCanvas.this, e, currentKey(e), cellOn(e));
		}
		
		public abstract void middleMouseClicked(MouseEvent e);
		public abstract void leftMouseClicked(MouseEvent e);

		public void mouseEntered(MouseEvent e) {
//			EMPTY
		}

		public void mouseExited(MouseEvent e) {
//			EMPTY
		}

		public void mousePressed(MouseEvent e) {
//			EMPTY
		}

		public void mouseReleased(MouseEvent e) {
//			EMPTY
		}
		
	}
	
	private class GridMouseEditListener extends GridMouseAdapter {

	    @Override
	    public void leftMouseClicked(MouseEvent e) {
		final Cell cellOn = cellOn(e);
		final EditingContext editingContext = getEditingContext();
		final Point currentKey = currentKey(e);
		if (cellOn == null) {
		    if (toolbarCellClass == null) {
		    	selectedkey = null;
		    	repaint();
		    	return;
		    }
		    try {
		    	final NodeCell clone = (NodeCell) getEditingContextFactory().createNew(editingContext, toolbarCellClass, toolbarArgs);
		    	if (clone instanceof InOut) ((InOut) clone).setName(editingContext.getCurrentNameString());
				editingContext.putCell(currentKey,clone);
		    } catch (Exception e1) {
		    	assert false : e1;
		    	e1.printStackTrace();
		    }
		} else {
		    if (selectedkey != null) {
			if (selectedkey.equals(currentKey)) return;
				Point sk = selectedkey;
				selectedkey = null;
				
				if (cellOn instanceof NodeCell) {
					TrackBlock tl = new SimpleTrackBlock((NodeCell) cellOn(sk.x, sk.y), (NodeCell) cellOn, 
							editingContext.getCurrentTrackLength(), editingContext.getCurrentMaxSpeed());
					editingContext.joinCells(sk, currentKey, tl);
				}
		    } else if (cellOn instanceof NodeCell){
		    	selectedkey = currentKey;
		    	repaint();
		    }
		}
	    }

	    @Override
	    public void middleMouseClicked(MouseEvent e) {
	    	final EditingContext editingContext = getEditingContext();
	    	editingContext.removeCell(currentKey(e));
	    }

	}
	
	private class GridMouseSimulationControlListener extends GridMouseAdapter {

	    @Override
	    public void leftMouseClicked(MouseEvent e) {
	    	//EMPTY
	    }

	    @Override
	    public void middleMouseClicked(MouseEvent e) {
	    	//EMPTY
	    }

	}
	
	private enum State { //EXTENSION jinam?
	    EDITING(new EditorCellRenderer(cellWidth, cellHeight), new GridCanvasEditingPopupMenu()),
	    SIMULATION(null, null);
	    
	    private final CellRenderer cellRenderer;
	    private final GridCanvasPopupMenu popupMenu;
	    
	    State(CellRenderer cellRenderer, GridCanvasPopupMenu popupMenu) {
		this.cellRenderer = cellRenderer;
		this.popupMenu = popupMenu;
	    }
	    public CellRenderer getCellRenderer() {
	        return cellRenderer;
	    }
	    public GridCanvasPopupMenu getPopupMenu() {
	        return popupMenu;
	    }
	}

	
	/**
	 * Create object for painting reailway Grid on screen
	 */
	public RailwayNetGridCanvas() {
	    state = State.EDITING;
	    setBackground(Color.BLACK);
	    setAutoscrolls(true);
	    addMouseMotionListener(this);
	}

	public void setContext(Context context) {
		if (context instanceof EditingContext) {
		    	state = State.EDITING;
		    	changeListeners(simulationControlListener, editListener);
		} else if (context instanceof SimulationContext) {
		    	state = State.SIMULATION;
		    	changeListeners(editListener, simulationControlListener);
		} else assert(false);
		changeContext(context);
	}
	
	private void changeListeners(GridMouseAdapter oldListener, GridMouseAdapter newListener) {
	    removeListener(oldListener);
	    addListener(newListener);
	}
	
	private void addListener(GridMouseAdapter listener) {
	    addMouseMotionListener(listener);
	    addMouseListener(listener);
	}
	
	private void removeListener(GridMouseAdapter listener) {
	    removeMouseMotionListener(listener);
	    removeMouseListener(listener);
	}

	private void changeContext(Context cont) {
		assert(cont != null);
		if (context != null) context.deleteObserver(this);
		cont.addObserver(this);
	    	context = cont;
		RailwayNetGrid nt = cont.getRailWayNetGrid();
		setPreferredSize(new Dimension(cellWidth*nt.getCols(), cellHeight*nt.getRows()));
		setSize(getPreferredSize());
		revalidate();
	}
	
	@Override
	public final void paintComponent(Graphics g) {
		assert (g instanceof Graphics2D);
		paint((Graphics2D) g);
	}
	
	/**
	 * @see java.awt.Component#paint(java.awt.Graphics)
	 * @param g graphics context
	 */
	public void paint(Graphics2D g) {
		if (context == null) return;
		cancelClip(g);
		
		int x = 0, y = 0;
		for (Entry<Point, Cell> cellEntry : context.getRailWayNetGrid()) {
			Point key = cellEntry.getKey();
			assert key != null && cellEntry.getValue() != null : cellEntry;
			
			x = key.x*cellWidth; y = key.y*cellHeight;
			
			g.translate(x, y);
			g.clipRect(0, 0, cellWidth+1, cellHeight+1);
			state.getCellRenderer().draw(g, cellEntry.getValue());
			g.translate(-x, -y);
			cancelClip(g);
		}
		
		if (showGrid) paintGrid(g);
		if (selectedkey != null) paintMarkSelected(g);
	}
	
	private void paintMarkSelected(Graphics2D g) {
	    final Cell cell = context.getRailWayNetGrid().get(selectedkey);
	    if (!(cell instanceof NodeCell)) {
		selectedkey = null;
		return;
	    }
	    cancelClip(g);
	    g.setColor(Color.RED);
	    g.drawRect(selectedkey.x*cellWidth, selectedkey.y*cellHeight, getCellWidth(), getCellHeight());
	}

	private void cancelClip(Graphics2D g) {
		g.setClip(getVisibleRect());
	}
	
	private void paintGrid(Graphics2D g) {
		RailwayNetGrid nt = context.getRailWayNetGrid();
		g.setColor(Color.GRAY);
		//		svisle cary
		for (int i = 0; i <= nt.getCols(); i++) {
			int x = i*cellWidth;
			g.drawLine(x, 0, x, getHeight());
		}
		
		//vodorovne cary
		for (int i = 0; i <= nt.getRows(); i++) {
			int y = i*cellHeight;
			g.drawLine(0, y, getWidth(), y);
		}
	}
	
	public String getStatus(MouseEvent e) {
		Cell cell = cellOn(e.getX(), e.getY());
		return cell==null ? "" : cell.toString();
	}
	
	protected Point currentKey(MouseEvent e) {
	    return new Point(e.getX()/cellWidth, e.getY()/cellHeight);
	}
	
	private Cell cellOn(int x, int y) {
		return context.getRailWayNetGrid().getCellAt(x/cellWidth, y/cellHeight);
	} 
	
	protected Cell cellOn(MouseEvent e) {
	    return cellOn(e.getX(), e.getY());
	}
	
	/**
	 * @deprecated asi jinde
	 * @param editingContext
	 */
	public void setEditing(EditingContext editingContext) {
	    setContext(editingContext);
	}
	
	/**
	 * @deprecated asi jinde
	 * @param editingContext
	 */
	public void setSimulation(SimulationContext simulationContext) {
	    setContext(simulationContext);
	}
	
//	nasledujicich 8 metod osetruje spravne rolovani zobrazovani scrollbaru
	public Dimension getPreferredScrollableViewportSize() {
		return getPreferredSize();
	}
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
		 if (orientation == SwingConstants.HORIZONTAL) return visibleRect.width - maxUnitIncrement;
		 return visibleRect.height - maxUnitIncrement;
	}

	public boolean getScrollableTracksViewportHeight() {
		return false;
	}

	public boolean getScrollableTracksViewportWidth() {
		return false;
	}

	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
	    int currentPosition = 0;
	    if (orientation == SwingConstants.HORIZONTAL) {
	        currentPosition = visibleRect.x;
	    } else {
	        currentPosition = visibleRect.y;
	    }

	    if (direction < 0) {
	        int newPosition = currentPosition -
	                         (currentPosition / maxUnitIncrement)
	                          * maxUnitIncrement;
	        return (newPosition == 0) ? maxUnitIncrement : newPosition;
	    } 
	    return ((currentPosition / maxUnitIncrement) + 1) * maxUnitIncrement- currentPosition;
	}

	public void mouseDragged(MouseEvent ev) {
		mouseMoveScroll(ev);
	}

	public void mouseMoved(MouseEvent ev) {
		// EXTENSION v modu simulator scrollovat...
		if (false) mouseMoveScroll(ev);
	}
	private void mouseMoveScroll(MouseEvent ev) {
		Rectangle r = new Rectangle(ev.getX(), ev.getY(), 1, 1);
        scrollRectToVisible(r);
	}

	public EditingContext getEditingContext() {//kontrolni metoda :o)
	    //assert state == State.EDITING && context instanceof EditingContext : "Getting editing context in non-editing mode";
	    return (EditingContext) context;
	}
	
	public boolean isShowGrid() {
	    return showGrid;    
	}

	public void setShowGrid(boolean b) {
	    this.showGrid = b;
	}

	public static int getCellHeight() {
	    return cellHeight;
	}

	public static int getCellWidth() {
	    return cellWidth;
	}

	public void update(Observable o, Object arg) {
	    if (arg instanceof Point) {
		Point point = (Point) arg;
		repaint(10, point.x*cellWidth, point.y*cellHeight, getCellWidth(), getCellHeight());
	    } else repaint(100);
	}

	
	public void setNodeOnToolbar(Class<? extends NodeCell> cellClass, Object[] args) {
		this.toolbarArgs = args;
		this.toolbarCellClass = cellClass;
	}
	
	private EditingContextFactory getEditingContextFactory() {
		return (EditingContextFactory) Main.getInstance().getContextFactory();
	}
}
