/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.Main
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JFileChooser
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JOptionPane

/**
 * Application menu bar with File and Help menus
 */
class MenuBar(
	private val frame: Frame
) : JMenuBar() {
	private inner class SaveAction : AbstractAction("Save as...") {
		override fun actionPerformed(e: ActionEvent) {
			val fileChooser = JFileChooser(System.getProperty("user.dir"))
			var returnValue = JFileChooser.CANCEL_OPTION

			while (returnValue != JFileChooser.APPROVE_OPTION) {
				returnValue = fileChooser.showSaveDialog(this@MenuBar)
				if (returnValue == JFileChooser.CANCEL_OPTION) return
			}

			// EXTENSION: temporary hack to get factory
			val editingContextFactory = Main.getInstance().getContextFactory() as EditingContextFactory
			val editingContext = frame.getRailwayNetGridCanvas().getEditingContext()
			editingContextFactory.saveContext(editingContext, fileChooser.selectedFile)
		}
	}

	private inner class ExitAction : AbstractAction("Exit") {
		override fun actionPerformed(e: ActionEvent) {
			System.exit(0)
		}
	}

	private inner class InfoAction(
		private val infoName: String,
		private val text: String
	) : AbstractAction(infoName) {
		override fun actionPerformed(e: ActionEvent) {
			JOptionPane.showMessageDialog(this@MenuBar, text, infoName, JOptionPane.INFORMATION_MESSAGE)
		}
	}

	init {
		add(fileMenu())
		add(helpMenu())
	}

	private fun fileMenu(): JMenu {
		val menu = JMenu("File")
		menu.add(SaveAction())
		menu.addSeparator()
		menu.add(ExitAction())
		return menu
	}

	private fun helpMenu(): JMenu {
		val menu = JMenu("Help")
		menu.add(
			InfoAction(
				"Usage",
				"<html>Left mouse inserts nodes and joins them. <br> Middle deletes them <br> Right mouse - popup menu</html>"
			)
		)
		menu.add(
			InfoAction("About", "<html><b>Author</b>:<br> Bedrich Hovorka <br> <em>xhovor07@stud.fit.vutbr.cz</em></html>")
		)
		return menu
	}
}
