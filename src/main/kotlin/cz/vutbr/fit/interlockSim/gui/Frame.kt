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

import cz.vutbr.fit.interlockSim.PROGRAM_FULL_NAME
import cz.vutbr.fit.interlockSim.context.Context
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JFrame
import javax.swing.JScrollPane

/**
 * Program main window
 */
class Frame : JFrame(PROGRAM_FULL_NAME) {
	val railwayNetGridCanvas: RailwayNetGridCanvas = RailwayNetGridCanvas()
	private val statusBar: StatusBar = StatusBar()

	init {
		setSize(800, 600)
		setDefaultCloseOperation(EXIT_ON_CLOSE)
		setLayout(BorderLayout())
		jMenuBar = MenuBar()
		contentPane.add(JScrollPane(railwayNetGridCanvas), BorderLayout.CENTER)
		contentPane.add(ToolBar(), BorderLayout.NORTH)

		statusBar.registerProducer(railwayNetGridCanvas)
		contentPane.add(statusBar, BorderLayout.SOUTH)

		// Add component listener to refresh canvas when frame is resized
		addComponentListener(
			object : ComponentAdapter() {
				override fun componentResized(e: ComponentEvent) {
					// When frame is resized, refresh grid canvas to handle scrollbar appearance/disappearance
					railwayNetGridCanvas.revalidate()
				}
			}
		)
	}

	fun setContext(context: Context<*>) {
		context.addPropertyChangeListener(statusBar)
		railwayNetGridCanvas.setContext(context)
	}
}
