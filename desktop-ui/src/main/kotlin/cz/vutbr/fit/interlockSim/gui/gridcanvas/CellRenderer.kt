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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
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
				round(cellWidth * s.getRx()),
				round(cellHeight * s.getRy()),
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

		val theta = if (cell.getOrientation()) thetaObj + Math.PI else thetaObj
		val xs = intArrayOf(cellWidth / 4, cellWidth / 4, 3 * cellWidth / 4)
		val ys = intArrayOf(cellHeight / 4, 3 * cellHeight / 4, cellHeight / 2)

		val transform = g.transform
		g.rotate(theta, (cellWidth / 2).toDouble(), (cellHeight / 2).toDouble())
		g.fillPolygon(xs, ys, 3)
		g.drawPolygon(xs, ys, 3)
		g.transform = transform
	}

	// Protected helper methods for common static cell rendering logic
	protected fun drawStaticRailSwitch(
		g: Graphics2D,
		cell: RailSwitch
	) {
		drawLine(g, cell.getSpatialType())
		val segments = cell.getBranchSegments().toTypedArray()
		drawSegments(g, *segments)
	}

	protected fun drawStaticRailSemaphore(
		g: Graphics2D,
		cell: RailSemaphore
	) {
		drawLine(g, cell.getSpatialType())
		drawTriangle(g, cell)
	}

	protected fun drawStaticTrackBlockPart(
		g: Graphics2D,
		cell: TrackBlockPart
	) {
		drawSegments(g, *cell.getSegments())
	}

	protected fun drawStaticInOut(
		g: Graphics2D,
		cell: InOut
	) {
		drawSegments(g, cell.direction())
		g.fillOval(getCellWidth() / 4, getCellHeight() / 4, getCellWidth() / 2, getCellHeight() / 2)
	}

	// Render cell using reflection to find appropriate draw method
	fun draw(
		g: Graphics2D,
		cell: Cell
	) {
		try {
			// Try to find the most specific draw method for this cell type
			// Search up the class hierarchy to handle private subclasses
			val method =
				findDrawMethod(cell.javaClass)
					?: error("No draw method found for cell type: ${cell.javaClass.name}")
			method.invoke(this, g, cell)
		} catch (e: Exception) {
			error("Failed to draw cell: $e")
		}
	}

	/**
	 * Find a compatible draw method for the given cell class.
	 * Searches up the class hierarchy to handle private subclasses.
	 */
	private fun findDrawMethod(cellClass: Class<*>): java.lang.reflect.Method? {
		var currentClass: Class<*>? = cellClass

		// Walk up the class hierarchy until we find a compatible draw method
		while (currentClass != null && Cell::class.java.isAssignableFrom(currentClass)) {
			try {
				return javaClass.getMethod("draw", Graphics2D::class.java, currentClass)
			} catch (e: NoSuchMethodException) {
				// Try superclass
				currentClass = currentClass.superclass
			}
		}

		// Also try interfaces implemented by the cell
		for (iface in cellClass.interfaces) {
			if (Cell::class.java.isAssignableFrom(iface)) {
				try {
					return javaClass.getMethod("draw", Graphics2D::class.java, iface)
				} catch (e: NoSuchMethodException) {
					// Continue searching
				}
			}
		}

		return null
	}

	// Abstract draw methods for static cell types - to be implemented by subclasses
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

	// Abstract draw methods for dynamic cell types - to be implemented by subclasses
	abstract fun draw(
		g: Graphics2D,
		cell: DynamicRailSwitch
	)

	abstract fun draw(
		g: Graphics2D,
		cell: DynamicRailSemaphore
	)

	abstract fun draw(
		g: Graphics2D,
		cell: DynamicInOut
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
