/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.beans.PropertyChangeEvent;

import javax.swing.JLabel;

import cz.vutbr.fit.interlockSim.Main;
import cz.vutbr.fit.interlockSim.context.ContextChangeListener;

/**
 * Collect and show status information
 *
 */
public class StatusBar extends JLabel implements ContextChangeListener {
	private class MouseL implements MouseMotionListener {
		public void mouseDragged(MouseEvent e) {
			// EMPTY
		}

		public void mouseMoved(MouseEvent e) {
			Object source = e.getSource();
			assert source instanceof StatusProducer;
			final String status = ((StatusProducer) source).getStatus(e);
			if (status != null) setText(status);
		}

	}

	private final MouseL mouseL = new MouseL();

	public StatusBar() {
		setPreferredSize(new Dimension(100, 25));
		setText("Welcome to " + Main.PROGRAM_NAME);
	}

	private Component checkComponent(StatusProducer producer) {
		assert producer instanceof Component : producer;
		Component producerComponent = (Component) producer;
		return producerComponent;
	}

	public void registerProducer(StatusProducer producer) {
		Component producerComponent = checkComponent(producer);
		producerComponent.addMouseMotionListener(mouseL);
	}

	public void unregisterProducer(StatusProducer producer) {
		Component producerComponent = checkComponent(producer);
		producerComponent.removeMouseMotionListener(mouseL);
	}

	@Override
	public void propertyChange(PropertyChangeEvent evt) {
		Object newValue = evt.getNewValue();
		if (newValue instanceof CharSequence) {
			setText(newValue.toString());
		} else if (newValue != null) {
			setText(newValue.toString());
		}
	}
}
