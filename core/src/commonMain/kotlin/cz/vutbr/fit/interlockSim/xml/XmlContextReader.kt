package cz.vutbr.fit.interlockSim.xml

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.readTextFile
import io.github.oshai.kotlinlogging.KotlinLogging
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

private val logger = KotlinLogging.logger {}

/**
 * KMP XML parser that replicates [XMLContextFactory] SAX handler logic using
 * xmlutil's [XmlReader] streaming API.
 *
 * Parses railway network XML (conforming to data.xsd) into a [DefaultEditingContext],
 * identically to the existing JVM SAX parser.
 *
 * @since 2026-03 (KMP Task 2c)
 */
class XmlContextReader {

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

		private const val MIN_INOUT_ELEMENTS = 1
		private const val MAX_NAME_LENGTH = 50
		private val NAME_PATTERN = Regex("[a-zA-Z0-9_-]+")
	}

	/**
	 * Parse an XML file into a [DefaultEditingContext].
	 *
	 * Reads the file using [readTextFile] (platform-agnostic file I/O),
	 * then delegates to [parse].
	 *
	 * @param path Path to the XML file (absolute or relative)
	 * @param skipStructuralValidation if true, skip InOut count validation
	 * @return parsed DefaultEditingContext
	 * @throws IllegalStateException if the file cannot be opened or read
	 * @throws IllegalArgumentException on name validation errors
	 * @throws IllegalStateException on parse or structural validation errors
	 */
	fun parseFile(
		path: String,
		skipStructuralValidation: Boolean = false
	): DefaultEditingContext = parse(readTextFile(path), skipStructuralValidation)

	/**
	 * Parse XML string into a [DefaultEditingContext].
	 *
	 * @param xmlContent the XML string
	 * @param skipStructuralValidation if true, skip InOut count validation
	 * @return parsed DefaultEditingContext
	 * @throws IllegalArgumentException on name validation errors
	 * @throws IllegalStateException on parse or structural validation errors
	 */
	fun parse(
		xmlContent: String,
		skipStructuralValidation: Boolean = false
	): DefaultEditingContext {
		val reader = xmlStreaming.newReader(xmlContent)
		var editingContext: DefaultEditingContext? = null
		var netElementDepth = 0

		try {
			while (reader.hasNext()) {
				when (reader.next()) {
					EventType.START_ELEMENT -> {
						val localName = reader.localName
						val attrs = readAttributes(reader)

						val pair = pair(localName, netElementDepth, attrs, editingContext)
						editingContext = pair.first
						netElementDepth = pair.second
					}

					EventType.END_ELEMENT -> {
						if (reader.localName == ROOT_ELEMENT_NAME) {
							netElementDepth--
						}
					}

					else -> {
						// Ignore other events
					}
				}
			}
		} finally {
			reader.close()
		}

		val ctx = checkNotNull(editingContext) { "Context not initialized — no <net> element found" }

		if (!skipStructuralValidation) {
			val inOutsCount = ctx.getInOuts().size
			if (inOutsCount < MIN_INOUT_ELEMENTS) {
				ctx.close()
				error(
					"Railway network must have at least $MIN_INOUT_ELEMENTS InOut elements " +
						"(entry and exit points). Found: $inOutsCount"
				)
			}
		}

		return ctx
	}

	private fun pair(
		localName: String,
		netElementDepth: Int,
		attrs: Map<String, String>,
		editingContext: DefaultEditingContext?
	): Pair<DefaultEditingContext?, Int> {
		var netElementDepth1 = netElementDepth
		var editingContext1 = editingContext
		when (localName) {
			ROOT_ELEMENT_NAME -> {
				netElementDepth1++
				check(netElementDepth1 <= 1) {
					"Nested net elements are not allowed"
				}
				val cols = requireInt(attrs, X)
				val rows = requireInt(attrs, Y)
				editingContext1 = DefaultEditingContext(cols, rows)
			}

			"InOut" -> {
				val ctx = requireContext(editingContext1)
				putNodeCell(ctx, attrs, parseInOut(attrs))
			}

			"RailSemaphore" -> {
				val ctx = requireContext(editingContext1)
				putNodeCell(ctx, attrs, parseRailSemaphore(attrs))
			}

			"RailSwitch" -> {
				val ctx = requireContext(editingContext1)
				putNodeCell(ctx, attrs, parseRailSwitch(attrs))
			}

			"SimpleTrackBlock" -> {
				val ctx = requireContext(editingContext1)
				parseAndInsertTrackBlock(ctx, attrs)
			}

			else -> {
				// Behavioral note: the old JVM SAX handler threw SAXException on
				// unknown elements. This KMP reader intentionally warns and skips.
				// Rationale:
				//   1. data.xsd declares <net> with no content model, so XSD
				//      validation cannot restrict which child elements appear —
				//      unknown tags cannot be caught at the schema layer.
				//   2. Warn-and-skip is consistent with lenient KMP XML parsing;
				//      the WARN log is the diagnostic signal for typos.
				// A typo in a tag name produces an empty network + a WARN log.
				if (!NodeCellFactory.isKnownTag(localName)) {
					logger.warn {
						"Unrecognized XML element <$localName> — " +
							"skipping (possible typo?)"
					}
				}
			}
		}
		return Pair(editingContext1, netElementDepth1)
	}

	// --- Element helpers ---

	private fun putNodeCell(
		ctx: DefaultEditingContext,
		attrs: Map<String, String>,
		node: NodeCell
	) {
		val key = parsePoint(attrs, prefix = null)
		ctx.putCell(key, node)
	}

	// --- Element parsers ---

	private fun parseInOut(attrs: Map<String, String>): InOut {
		val spatialType = requireEnum<SpatialType>(attrs, "SpatialType")
		val orientation = attrs[ATR_ORIENT_NAME]?.toBoolean() ?: false
		val name = requireAttr(attrs, NAME)
		return InOut(name, orientation, spatialType)
	}

	private fun parseRailSemaphore(attrs: Map<String, String>): RailSemaphore {
		val spatialType = requireEnum<SpatialType>(attrs, "SpatialType")
		val orientation = attrs[ATR_ORIENT_NAME]?.toBoolean() ?: false
		val name = parseOptionalName(attrs)
		return if (name != null) {
			RailSemaphore(name, orientation, spatialType)
		} else {
			RailSemaphore(orientation, spatialType)
		}
	}

	private fun parseRailSwitch(attrs: Map<String, String>): RailSwitch {
		val spatialType = requireEnum<SpatialType>(attrs, "SpatialType")
		val type = requireEnum<RailSwitch.Type>(attrs, "Type")
		val name = parseOptionalName(attrs)
		return if (name != null) {
			RailSwitch(name, spatialType, type)
		} else {
			RailSwitch(spatialType, type)
		}
	}

	private fun parseAndInsertTrackBlock(
		ctx: DefaultEditingContext,
		attrs: Map<String, String>
	) {
		val from = parsePoint(attrs, FROM)
		val to = parsePoint(attrs, TO)
		check(from != to) { "from and to points must be different" }

		val grid = ctx.getRailWayNetGrid()
		val fromCell = grid[from]
			?: error("Cell at 'from' position $from not found")
		val toCell = grid[to]
			?: error("Cell at 'to' position $to not found")
		val fromNode = CellUtilities.assertNodeCell(fromCell)
		val toNode = CellUtilities.assertNodeCell(toCell)

		val segmentFrom = requireEnum<Segment>(attrs, FROM + "Segment")
		val segmentTo = requireEnum<Segment>(attrs, TO + "Segment")

		val maxSpeed1 = requireDouble(attrs, ATR_MAX_SPEED + FROM)
		val maxSpeed2 = requireDouble(attrs, ATR_MAX_SPEED + TO)
		val length = requireDouble(attrs, ATR_LENGTH)
		val trackBlock = SimpleTrackBlock(fromNode, toNode, length, maxSpeed1, maxSpeed2)

		val result = ctx.hardJoin(segmentFrom, segmentTo, from, to, trackBlock)
		check(result) { "Track block was not inserted between $from and $to" }
	}

	// --- Attribute helpers ---

	/**
	 * Read all attributes from the current element into a map.
	 */
	private fun readAttributes(reader: XmlReader): Map<String, String> {
		val map = mutableMapOf<String, String>()
		for (i in 0 until reader.attributeCount) {
			val name = reader.getAttributeLocalName(i)
			val value = reader.getAttributeValue(i)
			map[name] = value
		}
		return map
	}

	private fun requireContext(ctx: DefaultEditingContext?): DefaultEditingContext =
		checkNotNull(ctx) { "Context not initialized — element found before <net>" }

	private fun requireAttr(
		attrs: Map<String, String>,
		name: String
	): String = attrs[name] ?: error("Missing required attribute: $name")

	private fun requireInt(
		attrs: Map<String, String>,
		name: String
	): Int {
		val value = requireAttr(attrs, name)
		return value.toIntOrNull()
			?: error("Invalid integer value for attribute '$name': $value")
	}

	private fun requireDouble(
		attrs: Map<String, String>,
		name: String
	): Double {
		val value = requireAttr(attrs, name)
		return value.toDoubleOrNull()
			?: error("Invalid double value for attribute '$name': $value")
	}

	private inline fun <reified E : Enum<E>> requireEnum(
		attrs: Map<String, String>,
		attrName: String
	): E {
		val value = requireAttr(attrs, attrName)
		return try {
			enumValueOf<E>(value)
		} catch (e: IllegalArgumentException) {
			error("Invalid ${E::class.simpleName} value for attribute '$attrName': $value")
		}
	}

	private fun parsePoint(
		attrs: Map<String, String>,
		prefix: String?
	): Point {
		val xAttr = if (prefix == null) X else prefix + X
		val yAttr = if (prefix == null) Y else prefix + Y
		val x = requireInt(attrs, xAttr)
		val y = requireInt(attrs, yAttr)
		return Point(x, y)
	}

	/**
	 * Parse optional name attribute with validation (same rules as [XMLContextFactory]).
	 */
	private fun parseOptionalName(attrs: Map<String, String>): String? {
		val name = attrs[NAME]
		if (name == null || name.isEmpty()) return null
		require(name.length <= MAX_NAME_LENGTH) {
			"Element name too long (max $MAX_NAME_LENGTH characters): $name"
		}
		require(name.matches(NAME_PATTERN)) {
			"Element name contains invalid characters (allowed: alphanumeric, -, _): $name"
		}
		return name
	}
}
