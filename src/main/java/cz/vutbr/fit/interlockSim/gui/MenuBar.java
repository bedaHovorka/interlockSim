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

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;

import cz.vutbr.fit.interlockSim.Main;
import cz.vutbr.fit.interlockSim.context.EditingContext;
import cz.vutbr.fit.interlockSim.context.EditingContextFactory;

/**
 * EXTENSION
 *
 */
public class MenuBar extends JMenuBar {
	private final class SaveAction extends AbstractAction {


		public SaveAction() {
			super("Save as...");
		}

		public void actionPerformed(ActionEvent e) {
		// EXTENSION Auto-generated method stub
		final JFileChooser fileChooser = new JFileChooser(System.getProperty("user.dir"));

		int returnValue = JFileChooser.CANCEL_OPTION;
		while (returnValue != JFileChooser.APPROVE_OPTION) {
			returnValue = fileChooser.showSaveDialog(MenuBar.this);
			if (returnValue == JFileChooser.CANCEL_OPTION) return;
		}
		final EditingContextFactory editingContextFactory = (EditingContextFactory) Main.getInstance().getContextFactory(); // EXTENSION temporary hack
		final EditingContext editingContext = frame.getRailwayNetGridCanvas().getEditingContext();
		editingContextFactory.saveContext(editingContext, fileChooser.getSelectedFile());
		}
		}

	private final class ExitAction extends AbstractAction {
		private ExitAction() {
			super("Exit");
		}

		public void actionPerformed(ActionEvent e) {
			System.exit(0);
		}
	}

	private final class InfoAction extends AbstractAction {
		private final String text;
		private final String name;

		public InfoAction(final String name, final String text) {
			super(name);
			this.text = text;
			this.name = name;
		}

		public void actionPerformed(ActionEvent e) {
			JOptionPane.showMessageDialog(MenuBar.this, text, name, JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private final Frame frame;

	public MenuBar(final Frame frame) {
		super();
		this.frame = frame;
		add(fileMenu());
		add(helpMenu());
	}

	private JMenu fileMenu() {
		JMenu menu = new JMenu("File");
		//new open save save as
		menu.add(new SaveAction());
		menu.addSeparator();
		menu.add(new ExitAction());
		return menu;
	}

	private JMenu helpMenu() {
		JMenu menu = new JMenu("Help");
		menu.add(new InfoAction("Usage", "<html>Left mouse inserts nodes and joins them. <br> Middle deletes them <br> Right mouse - popup menu</html>"));
		menu.add(new InfoAction("About", "<html><b>Author</b>:<br> Bedrich Hovorka <br> <em>xhovor07@stud.fit.vutbr.cz</em></html>"));
		return menu;
	}
}
