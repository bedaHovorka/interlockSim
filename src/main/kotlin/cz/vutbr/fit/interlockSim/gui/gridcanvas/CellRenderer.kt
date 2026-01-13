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

import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.lang.Math.round
import java.util.Collection
import java.util.EnumMap

/**
 * "Zobrazovac bunek" - Cell rendering component for railway grid visualization
 */
abstract class CellRenderer(
	cellWidth: Int,
	cellHeight: Int
) {
	// Rotation angles for different track orientations
	private var cellWidth: Int = cellWidth
		private set
	private var cellHeight: Int = cellHeight
		private set

	init {
		restart(cellWidth, cellHeight)
	}

	// Update cell dimensions
	private fun restart(
		cellWidth: Int,
		cellHeight: Int
	) {
		this.cellWidth = cellWidth
		this.cellHeight = cellHeight
	}

	// Draw line segments from center to endpoints
	protected fun drawSegments(
		g: Graphics2D,
		vararg segments: Segment
	) {
		val oldStroke = g.stroke
		g.stroke = BasicStroke(2f)
		for (s in segments) {
			g.drawLine(
				round(cellWidth * s.getRx()).toInt(),
				round(cellHeight * s.getRy()).toInt(),
				cellWidth / 2,
				cellHeight / 2
			)
		}
		g.stroke = oldStroke
	}

	// Draw segments from a collection
	protected fun drawSegments(
		g: Graphics2D,
		segments: Collection<Segment>?
	) {
		if (segments == null || segments.isEmpty()) return
		val segsArray: Array<Segment> = segments.toMutableList().toTypedArray()
		drawSegments(g, *segsArray)
	}

	// Draw a line based on spatial type
	protected fun drawLine(
		g: Graphics2D,
		type: Cell.SpatialType
	) {
		val segs = type.segments
		if (segs != null) {
			val segsArray: Array<Segment> = segs.toMutableList().toTypedArray()
			drawSegments(g, *segsArray)
		}
	}

	// Draw triangle indicator for switches and semaphores
	protected fun drawTriangle(
		g: Graphics2D,
		cell: OrientedPathSeparator
	) {
		val thetaObj = ANGLES[cell.getSpatialType()]
		requireValidState(thetaObj != null) { "No angle defined for spatial type: ${cell.getSpatialType()}" }

		val theta = if (cell.getOrientation()) thetaObj!! + Math.PI else thetaObj!!
		val xs = intArrayOf(cellWidth / 4, cellWidth / 4, 3 * cellWidth / 4)
		val ys = intArrayOf(cellHeight / 4, 3 * cellHeight / 4, cellHeight / 2)

		val transform = g.transform
		g.rotate(theta, (cellWidth / 2).toDouble(), (cellHeight / 2).toDouble())
		g.fillPolygon(xs, ys, 3)
		g.drawPolygon(xs, ys, 3)
		g.transform = transform
	}

	// Render cell using reflection to find appropriate draw method
	fun draw(
		g: Graphics2D,
		cell: Cell
	) {
		requireValidState(cell != null) { "Cell cannot be null" }

		try {
			// Use reflection to find and invoke the specific draw method for this cell type
			val method = javaClass.getMethod("draw", Graphics2D::class.java, cell.javaClass)
			method.invoke(this, g, cell)
		} catch (e: Exception) {
			error("Failed to draw cell: $e")
		}
	}

	// Abstract draw methods for different cell types - to be implemented by subclasses
	abstract fun draw(
		g: Graphics2D,
		cell: RailSwitch
	)

	abstract fun draw(
		g: Graphics2D,
		cell: RailSemaphore
	)

	abstract fun draw(
		g: Graphics2D,
		cell: TrackBlockPart
	)

	abstract fun draw(
		g: Graphics2D,
		cell: InOut
	)

	fun getCellHeight(): Int = cellHeight

	fun getCellWidth(): Int = cellWidth

	companion object {
		// Static initialization of rotation angles for different track orientations
		private val ANGLES =
			EnumMap<Cell.SpatialType, Double>(Cell.SpatialType::class.java).apply {
				put(Cell.SpatialType.HORIZONTAL, 0.0)
				put(Cell.SpatialType.VERTICAL, Math.PI / 2)
				put(Cell.SpatialType.DIAGONAL1, -Math.PI / 4)
				put(Cell.SpatialType.DIAGONAL2, Math.PI / 4)
			}
	}
}
