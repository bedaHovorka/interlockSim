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

import java.awt.Component;
import java.awt.event.MouseEvent;

/**
 * Caution: Class implementing this interface must be {@link Component}
 * @see StatusBar
 */
public interface StatusProducer {
    	/**
    	 * get status of Component
    	 * @param e
    	 * @return status of Component
    	 */
	public String getStatus(MouseEvent e);
}
