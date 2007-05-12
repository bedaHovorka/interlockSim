/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.action;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.util.HashMap;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.ImageIcon;

import cz.vutbr.fit.interlockSim.Main;
import cz.vutbr.fit.interlockSim.context.EditingContext;
import cz.vutbr.fit.interlockSim.context.EditingContextFactory;
import cz.vutbr.fit.interlockSim.gui.RailwayNetGridCanvas;
import cz.vutbr.fit.interlockSim.gui.ToolBar;
import cz.vutbr.fit.interlockSim.gui.gridcanvas.EditorCellRenderer;
import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;

/**
 *
 */
public class NodeCellAction extends AbstractAction {
    private static int iconSize = 20;
    private static final EditorCellRenderer editorCellRenderer = new EditorCellRenderer(iconSize, iconSize);

    private final Component component;
    private final Class<? extends NodeCell> cellClass;
	private final Object[] args;
    private static final HashMap<RenderingHints.Key, Object> renderingHints = new HashMap<RenderingHints.Key, Object>();
    
    static  {
    	renderingHints.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    	renderingHints.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
    }
    
    /**
     * @param bar
     * @param cellClass
     * @param context pseudocontext for creating buttons
     * @param args 
     */
    public NodeCellAction(final Component bar, final Class<? extends NodeCell> cellClass, EditingContext context, Object... args) {
    	super("Insert " + cellClass.getSimpleName(), paintIcon(cellClass, context, args));
    	this.component = bar;
    	this.cellClass = cellClass;
    	this.args = args;
    }
    
    private static Icon paintIcon(final Class<? extends NodeCell> cellClass, EditingContext context, Object[] args) {
    	final EditingContextFactory f = (EditingContextFactory) Main.getInstance().getContextFactory();
    	Cell cell = null;
		try {
			cell = (Cell) f.createNew(context, cellClass, args);
		} catch (Exception e) {
			assert false : e;
			e.printStackTrace();
		}
    	
    	final BufferedImage img = new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_RGB);
    	final Graphics2D g = img.createGraphics();
    	g.setRenderingHints(renderingHints);
    	g.setColor(Color.WHITE);
    	g.fillRect(0, 0, iconSize, iconSize);
    	g.setColor(Color.BLACK);
    	
    	editorCellRenderer.draw(g, cell);
		return new ImageIcon(img);
    }
    
    private static RailwayNetGridCanvas getRailwayNetGridCanvas(Component component) {
    	assert component instanceof ToolBar : component;
		return ((ToolBar) component).getFrame().getRailwayNetGridCanvas();
    }

    public void actionPerformed(ActionEvent e) {
    	getRailwayNetGridCanvas(component).setNodeOnToolbar(cellClass, args);
    }
}