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

import java.awt.BorderLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.JFrame;
import javax.swing.JScrollPane;

import cz.vutbr.fit.interlockSim.Main;
import cz.vutbr.fit.interlockSim.context.Context;

/**
 *	Program main window
 */
public class Frame extends JFrame {

	private final RailwayNetGridCanvas railwayNetGridCanvas = new RailwayNetGridCanvas();
	private final StatusBar statusBar = new StatusBar();

	/**
	 * Create program window
	 */
	public Frame() {
		super(Main.PROGRAM_FULL_NAME);
		setSize(800, 600);
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setLayout(new BorderLayout());
		setJMenuBar(new MenuBar(this));
		getContentPane().add(new JScrollPane(railwayNetGridCanvas), BorderLayout.CENTER);
		getContentPane().add(new ToolBar(this), BorderLayout.NORTH);

		statusBar.registerProducer(railwayNetGridCanvas);
		getContentPane().add(statusBar, BorderLayout.SOUTH);
		addComponentListener(new ComponentAdapter() {

			@Override
			public void componentResized(ComponentEvent arg0) {
				//zmeni-li se velikost okna je treba nektere komponety obnovit
				railwayNetGridCanvas.revalidate();//mizeni a navraceni scrollbaru...
			}

		});
	}

	public RailwayNetGridCanvas getRailwayNetGridCanvas() {
		return railwayNetGridCanvas;
	}

	public void setContext(Context context) {
		context.addObserver(statusBar);//EXTENSION a co se starym?
		railwayNetGridCanvas.setContext(context);
	}
}
