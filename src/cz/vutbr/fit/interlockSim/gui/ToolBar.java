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

import java.awt.Dimension;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;

import cz.vutbr.fit.interlockSim.Main;
import cz.vutbr.fit.interlockSim.context.EditingContext;
import cz.vutbr.fit.interlockSim.context.EditingContextFactory;
import cz.vutbr.fit.interlockSim.gui.action.NodeCellAction;
import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType;

/**
 * 
 *
 */
public class ToolBar extends JToolBar {
    private final class GridSwitch extends AbstractAction {
	private final JToggleButton toggleButton2;

	/**
	 * @param toggleButton2
	 */
	public GridSwitch(final JToggleButton toggleButton2) {
	    super("Show Grid");	    
	    this.toggleButton2 = toggleButton2;
	    final boolean showGrid = frame.getRailwayNetGridCanvas().isShowGrid();
	    this.toggleButton2.setSelected(showGrid);
	}

	public void actionPerformed(ActionEvent e) {
	    final RailwayNetGridCanvas c = frame.getRailwayNetGridCanvas();
	    c.setShowGrid(!c.isShowGrid());
	    c.repaint(100);
	    this.toggleButton2.setSelected(c.isShowGrid());
	}
    }

	private final ButtonGroup buttonGroup = new ButtonGroup();
	private final Frame frame;
	private EditingContext pseudoContext;

	public ToolBar(Frame frame) {
		this.frame = frame;
		this.pseudoContext = ((EditingContextFactory) Main.getInstance().getContextFactory()).createEmptyContext();
		// EXTENSION Auto-generated constructor stub
		setPreferredSize(new Dimension(100, 30));
		setFloatable(false);
		
		//semafory
		
		for (Cell.SpatialType t : Cell.SpatialType.values()) {
		    add(RailSemaphore.class, false, t);
		    add(RailSemaphore.class, true, t);
		}
		
		addSeparator();
		//Vyhybky
		
		final SpatialType[] spatialTypes = RailSwitch.SUPPORTED_SIMPLE_SPATIAL_TYPES;
		for (Cell.SpatialType t : spatialTypes) {
		    for (RailSwitch.Type st : RailSwitch.Type.values()) {
		    	add(RailSwitch.class, t, st);
		    }
		}
		
		addSeparator();
		
		for (Cell.SpatialType t : spatialTypes) {
			add(InOut.class, "", false, t);
			add(InOut.class, "", true, t);
		}
		
		addSeparator();
		
		final JToggleButton toggleButton2 = new JToggleButton();
		toggleButton2.setAction(new GridSwitch(toggleButton2));
		add(toggleButton2);
	}
	
	public void add(Class<? extends NodeCell> cellClass, Object... args) {
	    final JToggleButton tb = new JToggleButton(new NodeCellAction(this, cellClass, pseudoContext, args));
	    tb.setToolTipText(tb.getText());
	    tb.setText("");
	    buttonGroup.add(tb);
	    add(tb);
	}

	public Frame getFrame() {
	    return frame;
	}
}
