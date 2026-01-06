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
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.gui.action.NodeCellAction
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JToggleButton
import javax.swing.JToolBar

/**
 * Tool palette for railway editor with tools for semaphores, switches, and entry/exit points
 */
class ToolBar(
	private val frame: Frame
) : JToolBar() {
	private inner class GridSwitch(
		private val toggleButton: JToggleButton
	) : AbstractAction("Show Grid") {
		init {
			val showGrid = frame.getRailwayNetGridCanvas().isShowGrid()
			toggleButton.isSelected = showGrid
		}

		override fun actionPerformed(e: ActionEvent) {
			val canvas = frame.getRailwayNetGridCanvas()
			canvas.setShowGrid(!canvas.isShowGrid())
			canvas.repaint(100)
			toggleButton.isSelected = canvas.isShowGrid()
		}
	}

	private val buttonGroup: ButtonGroup = ButtonGroup()
	private val pseudoContext: EditingContext

	init {
		pseudoContext = (Main.getInstance().getContextFactory() as EditingContextFactory).createEmptyContext()
		preferredSize = Dimension(100, 30)
		isFloatable = false

		// Add semaphore buttons for all spatial types
		for (spatialType in Cell.SpatialType.values()) {
			addButton(RailSemaphore::class.java, false, spatialType)
			addButton(RailSemaphore::class.java, true, spatialType)
		}

		addSeparator()

		// Add switch buttons for all supported spatial types
		val spatialTypes = RailSwitch.SUPPORTED_SIMPLE_SPATIAL_TYPES
		for (spatialType in spatialTypes) {
			for (switchType in RailSwitch.Type.values()) {
				addButton(RailSwitch::class.java, spatialType, switchType)
			}
		}

		addSeparator()

		// Add entry/exit point buttons
		for (spatialType in spatialTypes) {
			addButton(InOut::class.java, "", false, spatialType)
			addButton(InOut::class.java, "", true, spatialType)
		}

		addSeparator()

		// Add grid toggle button
		val gridToggleButton = JToggleButton()
		gridToggleButton.action = GridSwitch(gridToggleButton)
		add(gridToggleButton)
	}

	/**
	 * Add a tool button for creating a cell of the specified type
	 *
	 * @param cellClass the NodeCell subclass to create
	 * @param args construction arguments for the cell
	 */
	private fun addButton(
		cellClass: Class<out NodeCell>,
		vararg args: Any
	) {
		@Suppress("UNCHECKED_CAST")
		val toggleButton = JToggleButton(NodeCellAction(this, cellClass, pseudoContext, args as Array<Any>))
		toggleButton.toolTipText = toggleButton.text
		toggleButton.text = ""
		buttonGroup.add(toggleButton)
		add(toggleButton)
	}

	fun getFrame(): Frame = frame
}
