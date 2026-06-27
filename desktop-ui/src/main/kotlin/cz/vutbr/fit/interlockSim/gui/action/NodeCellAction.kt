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

import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.gui.Frame
import cz.vutbr.fit.interlockSim.gui.RailwayNetGridCanvas
import cz.vutbr.fit.interlockSim.gui.gridcanvas.EditorCellRenderer
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.Cell
import org.koin.mp.KoinPlatform.getKoin
import java.awt.Color
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 *
 */
class NodeCellAction(
	private val cellClass: Class<out NodeCell>,
	context: EditingContext,
	private val args: Array<Any>
) : AbstractAction("Insert " + cellClass.simpleName, paintIcon(cellClass, context, args)) {
	override fun actionPerformed(e: ActionEvent) {
		// Cast args to nullable array as expected by setNodeOnToolbar
		@Suppress("UNCHECKED_CAST")
		getRailwayNetGridCanvas().setNodeOnToolbar(cellClass, args as Array<Any?>?)
	}

	companion object {
		private const val ICON_SIZE: Int = 20
		private val editorCellRenderer = EditorCellRenderer(ICON_SIZE, ICON_SIZE)
		private val renderingHints: MutableMap<RenderingHints.Key, Any> =
			mutableMapOf<RenderingHints.Key, Any>().apply {
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
				val f = getKoin().get<JvmEditingContextFactory>()
				cell = f.createNew(context, cellClass, *args) as Cell
			} catch (e: Exception) {
				error("Failed to create cell icon: $e")
			}
			val img = BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_RGB)
			val g = img.createGraphics()
			g.setRenderingHints(renderingHints)
			g.color = Color.WHITE
			g.fillRect(0, 0, ICON_SIZE, ICON_SIZE)
			g.color = Color.BLACK
			editorCellRenderer.draw(g, cell)
			return ImageIcon(img)
		}

		private fun getRailwayNetGridCanvas(): RailwayNetGridCanvas = getKoin().get<Frame>().railwayNetGridCanvas
	}
}
