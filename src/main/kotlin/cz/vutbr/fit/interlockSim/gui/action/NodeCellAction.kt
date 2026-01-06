/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.action

import cz.vutbr.fit.interlockSim.Main
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.gui.RailwayNetGridCanvas
import cz.vutbr.fit.interlockSim.gui.ToolBar
import cz.vutbr.fit.interlockSim.gui.gridcanvas.EditorCellRenderer
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import java.awt.Color
import java.awt.Component
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.util.HashMap
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 *
 */
class NodeCellAction(
	private val component: Component,
	private val cellClass: Class<out NodeCell>,
	context: EditingContext,
	private val args: Array<Any>
) : AbstractAction("Insert " + cellClass.simpleName, paintIcon(cellClass, context, args)) {
	override fun actionPerformed(e: ActionEvent) {
		// Cast args to nullable array as expected by setNodeOnToolbar
		@Suppress("UNCHECKED_CAST")
		getRailwayNetGridCanvas(component).setNodeOnToolbar(cellClass, args as Array<Any?>?)
	}

	companion object {
		private const val iconSize: Int = 20
		private val editorCellRenderer = EditorCellRenderer(iconSize, iconSize)
		private val renderingHints: HashMap<RenderingHints.Key, Any> =
			HashMap<RenderingHints.Key, Any>().apply {
				put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
				put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
			}

		private fun paintIcon(
			cellClass: Class<out NodeCell>,
			context: EditingContext,
			args: Array<Any>
		): Icon {
			var cell: Cell? = null
			try {
				val f = Main.getInstance().getContextFactory() as EditingContextFactory
				// Call createNew directly with spread operator instead of using reflection
				cell = f.createNew(context, cellClass, *args) as Cell
			} catch (e: Exception) {
				assert(false) { e }
				e.printStackTrace()
			}

			val img = BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_RGB)
			val g = img.createGraphics()
			g.setRenderingHints(renderingHints)
			g.color = Color.WHITE
			g.fillRect(0, 0, iconSize, iconSize)
			g.color = Color.BLACK

			// Draw cell if successfully created
			if (cell != null) {
				editorCellRenderer.draw(g, cell)
			}
			return ImageIcon(img)
		}

		private fun getRailwayNetGridCanvas(component: Component): RailwayNetGridCanvas {
			assert(component is ToolBar) { component }
			return (component as ToolBar).getFrame().getRailwayNetGridCanvas()
		}
	}
}
