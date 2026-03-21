package cz.vutbr.fit.interlockSim.xml

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.Point

/**
 * KMP XML serializer that generates XML from a [DefaultEditingContext],
 * replicating the `generateXML()` logic from [XMLContextFactory].
 *
 * Produces railway network XML conforming to data.xsd, identically
 * to the existing JVM serializer.
 *
 * @since 2026-03 (KMP Task 2d)
 */
class XmlContextWriter {

	companion object {
		private const val ROOT_ELEMENT_NAME = "net"
		private const val ATR_ORIENT_NAME = "orientation"
		private const val ATR_LENGTH = "length"
		private const val ATR_MAX_SPEED = "maxSpeed"
		private const val X = "X"
		private const val Y = "Y"
		private const val FROM = "from"
		private const val TO = "to"
		private const val NAME = "name"
	}

	/**
	 * Generate XML string from a [DefaultEditingContext].
	 *
	 * @param context the editing context to serialize
	 * @return XML string representing the railway network
	 */
	fun generate(context: DefaultEditingContext): String {
		val railwayNetGrid = context.getRailWayNetGrid()
		val builder = StringBuilder()

		// XML declaration and DOCTYPE
		builder.append("<?xml version=\"1.0\"?>\n<!DOCTYPE ")
		builder.append(ROOT_ELEMENT_NAME).append(">\n")

		// Root element with grid dimensions
		builder.append('<').append(ROOT_ELEMENT_NAME).append(' ')
		appendAttribute(builder, X, railwayNetGrid.cols)
		appendAttribute(builder, Y, railwayNetGrid.rows)
		builder.append(">\n")

		// Collect all Points that have NodeCells
		val allNodes = linkedSetOf<Point>()

		// Add all nodes from the graph (nodes with connections)
		allNodes.addAll(context.getGraph().nodeSet())

		// Add any isolated NodeCells from the grid that aren't in the graph
		@Suppress("UNCHECKED_CAST")
		val cellGrid = railwayNetGrid as RailwayNetGrid<Cell>
		for (entry in cellGrid) {
			val cell = entry.value
			if (cell is NodeCell) {
				allNodes.add(entry.key)
			}
		}

		// Write all NodeCells
		for (point in allNodes) {
			val cell = railwayNetGrid[point]
			if (cell is NodeCell) {
				writeNodeCell(builder, point, cell)
			}
		}

		// Write all TrackBlocks (edges in the graph)
		for (entry in context.getGraph().entrySet()) {
			val key = entry.key
			val value = entry.value
			val keyIterator = key.iterator()
			check(keyIterator.hasNext()) {
				"Doubleton should have at least one element"
			}
			val p1 = keyIterator.next()
			check(keyIterator.hasNext()) {
				"Doubleton should have two elements"
			}
			val p2 = keyIterator.next()

			writeTrackBlock(builder, p1, p2, key, value)
		}

		// Close root element
		builder.append("</").append(ROOT_ELEMENT_NAME).append(">\n")

		return builder.toString()
	}

	private fun writeNodeCell(
		builder: StringBuilder,
		point: Point,
		cell: NodeCell
	) {
		builder.append('\t')
		builder.append('<').append(NodeCellFactory.tagNameFor(cell)).append(' ')
		appendAttribute(builder, X, point.x)
		appendAttribute(builder, Y, point.y)
		cell.getSpatialType()?.let {
			appendEnumAttribute(builder, "SpatialType", it)
		}
		if (cell is OrientedPathSeparator) {
			appendAttribute(
				builder, ATR_ORIENT_NAME,
				cell.getOrientation().toString()
			)
		}
		if (cell is RailSwitch) {
			appendEnumAttribute(builder, "Type", cell.type)
		}
		val name = cell.getName()
		if (name.isNotEmpty()) {
			appendAttribute(builder, NAME, name)
		}
		builder.append("/>\n")
	}

	private fun writeTrackBlock(
		builder: StringBuilder,
		p1: Point,
		p2: Point,
		key: Iterable<Point>,
		value: TrackBlock
	) {
		builder.append('\t')
		builder.append("<SimpleTrackBlock ")
		appendAttribute(builder, FROM + X, p1.x)
		appendAttribute(builder, FROM + Y, p1.y)
		appendAttribute(builder, TO + X, p2.x)
		appendAttribute(builder, TO + Y, p2.y)

		// Get segments from Doubleton
		@Suppress("UNCHECKED_CAST")
		val doubleton =
			key as cz.vutbr.fit.interlockSim.util.Doubleton<Point, Cell.Segment>
		val seg1 = doubleton.getValue(p1)
		val seg2 = doubleton.getValue(p2)
		checkNotNull(seg1) { "Segment for point $p1 not found" }
		checkNotNull(seg2) { "Segment for point $p2 not found" }
		appendEnumAttribute(builder, FROM + "Segment", seg1)
		appendEnumAttribute(builder, TO + "Segment", seg2)

		appendAttribute(builder, ATR_LENGTH, value.length())
		val ends = value.ends()
		appendAttribute(builder, ATR_MAX_SPEED + FROM, value.maxSpeed(ends[0]))
		appendAttribute(builder, ATR_MAX_SPEED + TO, value.maxSpeed(ends[1]))

		builder.append("/>\n")
	}

	private fun appendAttribute(
		builder: StringBuilder,
		name: String,
		value: Int
	) {
		builder.append(name).append("=\"").append(value).append("\" ")
	}

	private fun appendAttribute(
		builder: StringBuilder,
		name: String,
		value: Double
	) {
		builder.append(name).append("=\"").append(value).append("\" ")
	}

	private fun appendAttribute(
		builder: StringBuilder,
		name: String,
		value: String
	) {
		builder.append(name).append("=\"").append(value).append("\" ")
	}

	private fun appendEnumAttribute(
		builder: StringBuilder,
		name: String,
		value: Enum<*>
	) {
		builder.append(name).append("=\"").append(value.name).append("\" ")
	}
}
