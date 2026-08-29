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

import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.gui.action.NodeCellAction
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.SUPPORTED_SIMPLE_SPATIAL_TYPES
import cz.vutbr.fit.interlockSim.objects.core.Cell
import org.koin.mp.KoinPlatform.getKoin
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JToggleButton
import javax.swing.JToolBar

/**
 * Tool palette for railway editor with tools for semaphores, switches, and entry/exit points
 */
class ToolBar : JToolBar() {
	private inner class GridSwitch(
		private val toggleButton: JToggleButton
	) : AbstractAction("Show Grid") {
		init {
			toggleButton.isSelected = false

			toggleButton.addHierarchyListener {
				if (toggleButton.isShowing) {
					val canvas = getKoin().get<Frame>().railwayNetGridCanvas
					toggleButton.isSelected = canvas.showGrid
				}
			}
		}

		override fun actionPerformed(e: ActionEvent) {
			val canvas = getKoin().get<Frame>().railwayNetGridCanvas
			canvas.showGrid = !canvas.showGrid
			canvas.repaint(100)
			toggleButton.isSelected = canvas.showGrid
		}
	}

	private val buttonGroup: ButtonGroup = ButtonGroup()
	private val pseudoContext: EditingContext =
		getKoin().get<EditingContextFactory>().createEmptyContext()

	init {
		preferredSize = Dimension(100, 30)
		isFloatable = false

		// Add semaphore buttons for all spatial types
		for (spatialType in Cell.SpatialType.values()) {
			addButton(RailSemaphore::class.java, false, spatialType)
			addButton(RailSemaphore::class.java, true, spatialType)
		}

		addSeparator()

		// Add switch buttons for all supported spatial types
		val spatialTypes = SUPPORTED_SIMPLE_SPATIAL_TYPES
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
		val toggleButton = JToggleButton(NodeCellAction(cellClass, pseudoContext, args as Array<Any>))
		toggleButton.toolTipText = toggleButton.text
		toggleButton.text = ""
		buttonGroup.add(toggleButton)
		add(toggleButton)
	}

	/**
	 * Enable or disable all editing tools in the toolbar.
	 *
	 * @param enabled true to enable tools, false to disable them
	 */
	fun setToolsEnabled(enabled: Boolean) {
		for (i in 0 until componentCount) {
			val component = getComponent(i)
			component.isEnabled = enabled
		}
	}

	private val simControlsSeparator: JToolBar.Separator = JToolBar.Separator()
	private val simControlsLabel: JLabel = JLabel("▶ Simulation")

	/**
	 * Shows the simulation controls panel in the toolbar.
	 *
	 * Called when a simulation starts to indicate simulation mode visually.
	 * Idempotent: safe to call if controls are already showing.
	 * Must be called from the Event Dispatch Thread (EDT).
	 */
	fun showSimulationControls() {
		if (simControlsLabel.parent != null) return
		add(simControlsSeparator)
		add(simControlsLabel)
		revalidate()
		repaint()
	}

	/**
	 * Hides the simulation controls panel from the toolbar.
	 *
	 * Called when a simulation stops to return to editing mode appearance.
	 * Idempotent: safe to call if controls are not currently showing.
	 * Must be called from the Event Dispatch Thread (EDT).
	 */
	fun hideSimulationControls() {
		if (simControlsLabel.parent == null) return
		remove(simControlsSeparator)
		remove(simControlsLabel)
		revalidate()
		repaint()
	}
}
