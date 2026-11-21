package cz.vutbr.fit.interlockSim.xml

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.Doubleton
import cz.vutbr.fit.interlockSim.util.Point

/**
 * KMP XML serializer that generates XML from a [DefaultEditingContext],
 * replicating the `generateXML()` logic from [XMLContextFactory].
 *
 * Produces railway network XML conforming to data.xsd, identically
 * to the existing JVM serializer.
 *
 * A semaphore whose name is the [GeneratedSemaphoreName] of its own position is written without
 * a `name` attribute, so a loaded network saves back without names the user never wrote
 * (Issue #1273); the next load regenerates the same name. The exception is a name that another
 * node also carries: omitting it there would make the next load regenerate a name that then
 * clashes, so the attribute is kept.
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
		val writtenNames = namesWrittenByNodes(context, railwayNetGrid)

		writeHeader(builder, context)
		writeNodes(builder, context, railwayNetGrid, writtenNames)
		writeEdges(builder, context)
		writeFooter(builder)

		return builder.toString()
	}

	private fun writeHeader(
		builder: StringBuilder,
		context: DefaultEditingContext
	) {
		val grid = context.getRailWayNetGrid()
		builder.append("<?xml version=\"1.0\"?>\n<!DOCTYPE ")
		builder.append(ROOT_ELEMENT_NAME).append(">\n")
		builder.append('<').append(ROOT_ELEMENT_NAME).append(' ')
		appendAttribute(builder, X, grid.cols)
		appendAttribute(builder, Y, grid.rows)
		// Numeric properties are always emitted (even when they have their default values)
		// so that a future reader can always rely on their presence. The string property is
		// only emitted when non-empty to avoid spurious empty-attribute noise in saved files.
		appendAttribute(builder, "currentMaxSpeed", context.currentMaxSpeed)
		appendAttribute(builder, "currentTrackLength", context.currentTrackLength)
		if (context.currentNameString.isNotEmpty()) {
			appendAttribute(builder, "currentNameString", context.currentNameString)
		}
		builder.append(">\n")
	}

	private fun writeFooter(builder: StringBuilder) {
		builder.append("</").append(ROOT_ELEMENT_NAME).append(">\n")
	}

	private fun collectNodes(
		context: DefaultEditingContext,
		railwayNetGrid: RailwayNetGrid<*>
	): Set<Point> {
		val allNodes = linkedSetOf<Point>()
		allNodes.addAll(context.getGraph().nodeSet())

		val cellGrid = railwayNetGrid
		for (entry in cellGrid) {
			val cell = entry.value
			if (cell is NodeCell) {
				allNodes.add(entry.key)
			}
		}
		return allNodes
	}

	/**
	 * Names that [writeNodes] will emit: the non-empty names of the nodes it writes, without the
	 * ones a semaphore omits as its own position's [GeneratedSemaphoreName]. The writer may drop
	 * a generated-form name only when it is absent here — if another node writes the same name,
	 * keeping the attribute is the only way the saved file reloads.
	 */
	private fun namesWrittenByNodes(
		context: DefaultEditingContext,
		railwayNetGrid: RailwayNetGrid<*>
	): Set<String> {
		val names = mutableSetOf<String>()
		for (point in collectNodes(context, railwayNetGrid)) {
			val cell = railwayNetGrid[point]
			if (cell !is NodeCell) continue
			val name = cell.getName()
			if (name.isEmpty()) continue
			// The same rule writeNodeCell applies, without the kept-name check: a candidate cell
			// contributes nothing, and candidates at different grid positions generate different
			// names, so one pass is the fixed point.
			if (cell is RailSemaphore && name == GeneratedSemaphoreName.at(point)) continue
			names.add(name)
		}
		return names
	}

	private fun writeNodes(
		builder: StringBuilder,
		context: DefaultEditingContext,
		railwayNetGrid: RailwayNetGrid<*>,
		writtenNames: Set<String>
	) {
		for (point in collectNodes(context, railwayNetGrid)) {
			val cell = railwayNetGrid[point]
			if (cell is NodeCell) {
				writeNodeCell(builder, point, cell, writtenNames)
			}
		}
	}

	private fun writeEdges(
		builder: StringBuilder,
		context: DefaultEditingContext
	) {
		for (entry in context.getGraph().entrySet()) {
			val key = entry.key
			val value = entry.value
			// Doubleton guarantees exactly 2 elements; destructure via toList().
			val (p1, p2) = key.toList()
			writeTrackBlock(builder, p1, p2, key, value)
		}
	}

	private fun writeNodeCell(
		builder: StringBuilder,
		point: Point,
		cell: NodeCell,
		writtenNames: Set<String>
	) {
		builder.append('\t')
		builder.append('<').append(NodeCellFactory.tagNameFor(cell)).append(' ')
		appendAttribute(builder, X, point.x)
		appendAttribute(builder, Y, point.y)
		cell.getSpatialType().let {
			appendEnumAttribute(builder, "SpatialType", it)
		}
		if (cell is OrientedPathSeparator) {
			appendAttribute(
				builder,
				ATR_ORIENT_NAME,
				cell.getOrientation().toString()
			)
		}
		if (cell is RailSwitch) {
			appendEnumAttribute(builder, "Type", cell.type)
		}
		val name = cell.getName()
		val generated =
			cell is RailSemaphore && name == GeneratedSemaphoreName.at(point) && name !in writtenNames
		if (name.isNotEmpty() && !generated) {
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

		// The graph contract guarantees Doubleton<Point, Segment> keys —
		// each edge maps two Points to their respective Segments.
		@Suppress("UNCHECKED_CAST")
		val doubleton =
			key as Doubleton<Point, Cell.Segment>
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
		builder
			.append(name)
			.append("=\"")
			.append(value)
			.append("\" ")
	}

	private fun appendAttribute(
		builder: StringBuilder,
		name: String,
		value: Double
	) {
		builder
			.append(name)
			.append("=\"")
			.append(value)
			.append("\" ")
	}

	private fun appendAttribute(
		builder: StringBuilder,
		name: String,
		value: String
	) {
		builder
			.append(name)
			.append("=\"")
			.append(escapeXml(value))
			.append("\" ")
	}

	private fun appendEnumAttribute(
		builder: StringBuilder,
		name: String,
		value: Enum<*>
	) {
		builder
			.append(name)
			.append("=\"")
			.append(value.name)
			.append("\" ")
	}

	private fun escapeXml(value: String): String =
		value
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&apos;")
}
