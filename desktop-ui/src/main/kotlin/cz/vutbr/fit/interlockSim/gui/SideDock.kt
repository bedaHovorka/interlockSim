package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.gui.conflict.ConflictResolutionPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * Right-hand column of the main window (Issue #1421): speed controls, the KPI dashboard, dispatcher
 * controls and the conflict resolution panel, one below the other.
 *
 * The column stays in place in both modes, so the layout never jumps when the mode changes. In
 * editing mode [setSimulationMode] disables the speed controls; the dispatcher controls are already
 * disabled there because [Frame] clears their mode state. [ConflictResolutionPanel] keeps its own
 * show/hide behaviour and takes the free height below the two control sections.
 *
 * The column scrolls vertically when the window is too low for all sections, and never horizontally.
 */
internal class SideDock(
	val speedPanel: SimulationControlPanel,
	val dashboardPanel: MetricsDashboardPanel,
	val dispatcherPanel: DispatcherControlPanel,
	val conflictPanel: ConflictResolutionPanel
) : JScrollPane(VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER) {
	/** The panel inside the viewport; it tracks the viewport width, and its height when taller. */
	private val content = DockContent()

	init {
		speedPanel.border = BorderFactory.createTitledBorder("Simulation Speed")
		dispatcherPanel.border = BorderFactory.createTitledBorder("Dispatcher")

		val controls = JPanel()
		controls.layout = BoxLayout(controls, BoxLayout.PAGE_AXIS)
		controls.add(speedPanel)
		controls.add(dashboardPanel)
		controls.add(dispatcherPanel)

		content.add(controls, BorderLayout.NORTH)
		content.add(conflictPanel, BorderLayout.CENTER)
		setViewportView(content)
		border = BorderFactory.createEmptyBorder()
		verticalScrollBar.unitIncrement = GridDimensions.MAX_UNIT_INCREMENT
		preferredSize = Dimension(DOCK_WIDTH, 0)
		minimumSize = Dimension(DOCK_WIDTH, 0)
	}

	/**
	 * Enables the speed controls in simulation mode ([active] `true`) and disables them in editing
	 * mode. Must be called from the EDT.
	 */
	fun setSimulationMode(active: Boolean) {
		speedPanel.speedControlsEnabled = active
	}

	/** Viewport content that follows the viewport width, and fills its height when there is room. */
	private class DockContent :
		JPanel(BorderLayout()),
		Scrollable {
		override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

		override fun getScrollableUnitIncrement(
			visibleRect: Rectangle,
			orientation: Int,
			direction: Int
		): Int = GridDimensions.MAX_UNIT_INCREMENT

		override fun getScrollableBlockIncrement(
			visibleRect: Rectangle,
			orientation: Int,
			direction: Int
		): Int = if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

		override fun getScrollableTracksViewportWidth(): Boolean = true

		override fun getScrollableTracksViewportHeight(): Boolean {
			val viewportHeight = (parent as? javax.swing.JViewport)?.height ?: return false
			return viewportHeight > preferredSize.height
		}
	}

	companion object {
		/** Width of the column: the width the conflict panel was designed for (Issue #590). */
		const val DOCK_WIDTH: Int = GuiConstants.CONFLICT_RESOLUTION_PANEL_WIDTH
	}
}
