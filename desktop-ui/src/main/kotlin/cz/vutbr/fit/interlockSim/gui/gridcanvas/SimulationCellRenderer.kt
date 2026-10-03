/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.gridcanvas

import cz.vutbr.fit.interlockSim.gui.animation.AnimationColors
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.core.anti
import java.awt.Color
import java.awt.Graphics2D

/**
 * Cell renderer for simulation mode - renders railway elements with dynamic state
 *
 * This class is the static-state renderer: it draws each cell's current configuration with basic
 * dynamic state indicators. [AnimatedSimulationCellRenderer] adds the animated layer on top of it.
 */
open class SimulationCellRenderer(
	cellWidth: Int,
	cellHeight: Int
) : CellRenderer(cellWidth, cellHeight) {
	// Static cell rendering (supported for flexibility but uncommon in simulation grid)
	override fun draw(
		g: Graphics2D,
		cell: RailSwitch
	) {
		drawStaticRailSwitch(g, cell)
	}

	override fun draw(
		g: Graphics2D,
		cell: RailSemaphore
	) {
		drawStaticRailSemaphore(g, cell)
	}

	override fun draw(
		g: Graphics2D,
		cell: TrackBlockPart
	) {
		drawStaticTrackBlockPart(g, cell)
	}

	override fun draw(
		g: Graphics2D,
		cell: InOut
	) {
		drawStaticInOut(g, cell)
	}

	// Dynamic cell rendering - render configuration from static ref with dynamic state
	override fun draw(
		g: Graphics2D,
		cell: DynamicRailSwitch
	) {
		// Get active segments for current configuration
		val activeSegments = cell.getActiveSegments()

		// Draw only the active direction to indicate switch position
		drawSegments(g, *activeSegments.toTypedArray())

		if (cell.locked) drawLockMark(g, cell.staticRef)
	}

	override fun draw(
		g: Graphics2D,
		cell: DynamicRailSemaphore
	) {
		// Render base configuration from static reference
		val staticRef = cell.staticRef
		drawLine(g, staticRef.getSpatialType())

		// Render signal state with color coding. Direction-aware (Issue #812): the canvas shows the
		// semaphore from its static forward-facing side, so a proceed aspect cleared for the
		// opposite (reverse) reservation direction must be shown RED, not the false GREEN. This
		// mirrors AnimationStateCapture.captureSignalState — the guard is meaningful only because
		// isAllowingFor compares against the stored reservation direction, not the static one.
		val d = cell.direction()
		val effectiveSignal = if (cell.isAllowingFor(anti(d), d)) cell.signal else Signal.STOP
		val oldColor = g.color
		g.color =
			when {
				effectiveSignal.isAllowing() -> Color.GREEN
				else -> Color.RED
			}
		drawTriangle(g, staticRef)
		g.color = oldColor

		// Signal aspect rendering is basic (no sophisticated variants). Tracked in issue #669.
		// Signal changes are not animated.
	}

	override fun draw(
		g: Graphics2D,
		cell: DynamicInOut
	) {
		// A train waiting in the entry queue tints the cell; the connector is drawn on top
		if (cell.occupied) drawOccupancyTint(g, AnimationColors.TRACK_OCCUPIED)

		// Render base configuration from static reference
		drawStaticInOut(g, cell.staticRef)
	}

	// EXTENSION - additional railway element renderers can be added here
}
