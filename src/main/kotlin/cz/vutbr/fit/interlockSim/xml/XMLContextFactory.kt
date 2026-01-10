/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.xml

import cz.vutbr.fit.interlockSim.MyResourceBoundle
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.context.DefaultContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Type
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.Doubleton
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Util
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.lang.reflect.InvocationTargetException
import javax.xml.XMLConstants
import javax.xml.transform.Source
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import javax.xml.validation.Validator

/**
 * XML implementation of {@link EditingContextFactory}
 */
class XMLContextFactory :
	EditingContextFactory,
	SimulationContextFactory {
	// EXTENSION co kdyz je delka koleje mezi InOuty mensi nez delka vlaku

	private inner class XMLContext(
		cols: Int,
		rows: Int
	) : DefaultContext(cols, rows) {
		// No additional implementation needed
	}

	private inner class Handler : DefaultHandler() {
		private var context: XMLContext? = null
		private var ended: Boolean = false
		private var netElementDepth: Int = 0

		// Array of classes to handle in XML parsing
		private val classes: Array<Class<out PathElement>> =
			arrayOf(
				RailSemaphore::class.java,
				RailSwitch::class.java,
				InOut::class.java,
				SimpleTrackBlock::class.java
			)

		override fun startElement(
			uri: String?,
			localName: String?,
			qName: String?,
			attributes: Attributes?
		) {
			if (localName == ROOT_ELEMENT_NAME) {
				netElementDepth++
				if (netElementDepth > 1) {
					throw SAXException("Nested net elements are not allowed")
				}
				val cols = getInt(uri!!, attributes!!, X)
				val rows = getInt(uri, attributes, Y)
				context = XMLContext(cols, rows)
				return
			}

			val ctx = context ?: throw SAXException("Context not initialized")
			val clazz = classification(localName!!)

			if (NodeCell::class.java.isAssignableFrom(clazz)) {
				val spatialType = getEnum(uri!!, attributes!!, SpatialType::class.java)

				val args: Array<Any> =
					when {
						OrientedPathSeparator::class.java.isAssignableFrom(clazz) -> {
							val orientation = getBoolean(uri, attributes, ATR_ORIENT_NAME)
							when (clazz) {
								InOut::class.java -> {
									val name = attributes.getValue(uri, NAME)
									arrayOf(name as Any, orientation as Any, spatialType as Any)
								}
								RailSemaphore::class.java -> {
									arrayOf(orientation as Any, spatialType as Any)
								}
								else -> error("Unexpected OrientedPathSeparator: $clazz")
							}
						}
						clazz == RailSwitch::class.java -> {
							val type = getEnum(uri, attributes, Type::class.java)
							arrayOf(spatialType as Any, type as Any)
						}
						else -> error("Unexpected PathElement class: $clazz")
					}

				val key = getPoint(uri, attributes, null)

				try {
					val node = createNew(ctx, clazz, *args) as NodeCell
					ctx.putCell(key, node)
				} catch (e: Exception) {
					throw SAXException(e)
				}
			} else if (TrackBlock::class.java.isAssignableFrom(clazz)) {
				check(clazz == SimpleTrackBlock::class.java) { "Expected SimpleTrackBlock, got $clazz" }

				// Parse from/to points
				val from = getPoint(uri!!, attributes!!, FROM)
				val to = getPoint(uri, attributes, TO)
				check(from != to) { "from and to points must be different" }

				val railwayNetGrid = ctx.getRailWayNetGrid()
				val fromNode = Util.assertNodeCell(railwayNetGrid[from] as Any)
				val toNode = Util.assertNodeCell(railwayNetGrid[to] as Any)

				val segmentFrom =
					getEnum(uri, attributes, Segment::class.java, FROM)
						?: throw SAXException("Missing segment from")
				val segmentTo =
					getEnum(uri, attributes, Segment::class.java, TO)
						?: throw SAXException("Missing segment to")

				// Parse track block properties
				val maxSpeed1 = getDouble(uri, attributes, ATR_MAX_SPEED + FROM)
				val maxSpeed2 = getDouble(uri, attributes, ATR_MAX_SPEED + TO)
				val length = getDouble(uri, attributes, ATR_LENGTH)
				val trackBlock = SimpleTrackBlock(fromNode, toNode, length, maxSpeed1, maxSpeed2)

				// Insert track block into context
				val result = ctx.hardJoin(segmentFrom, segmentTo, from, to, trackBlock)
				if (!result) {
					throw SAXException("track block were not inserted")
				}
			} else {
				error("Unexpected class type: $clazz")
			}
		}

		private fun classification(localName: String): Class<out PathElement> {
			for (c in classes) {
				if (localName == classToString(c)) {
					return c
				}
			}
			throw SAXException("Wrong tag name: $localName")
		}

		private fun <E : Enum<E>> getEnum(
			uri: String,
			attributes: Attributes,
			enumClass: Class<E>
		): E? = getEnum(uri, attributes, enumClass, "")

		private fun <E : Enum<E>> getEnum(
			uri: String,
			attributes: Attributes,
			enumClass: Class<E>,
			name: String
		): E? {
			val value = attributes.getValue(uri, name + classToString(enumClass)) ?: return null
			return try {
				@Suppress("UNCHECKED_CAST")
				java.lang.Enum.valueOf(enumClass as Class<E>, value)
			} catch (e: IllegalArgumentException) {
				null
			}
		}

		private fun getBoolean(
			uri: String,
			attributes: Attributes,
			name: String
		): Boolean = attributes.getValue(uri, name)?.toBoolean() ?: false

		private fun getInt(
			uri: String,
			attributes: Attributes,
			name: String
		): Int =
			try {
				attributes.getValue(uri, name).toInt()
			} catch (e: NumberFormatException) {
				throw SAXException("Wrong parameter: $name", e)
			}

		private fun getDouble(
			uri: String,
			attributes: Attributes,
			name: String
		): Double =
			try {
				attributes.getValue(uri, name).toDouble()
			} catch (e: NumberFormatException) {
				throw SAXException("Wrong parameter: $name", e)
			}

		private fun getPoint(
			uri: String,
			attributes: Attributes,
			name: String?
		): Point {
			val x = getInt(uri, attributes, if (name == null) X else name + X)
			val y = getInt(uri, attributes, if (name == null) Y else name + Y)
			return Point(x, y)
		}

		override fun endElement(
			uri: String?,
			localName: String?,
			qName: String?
		) {
			if (localName == ROOT_ELEMENT_NAME) {
				netElementDepth--
			}
		}

		override fun endDocument() {
			// Note: InOut validation removed - contexts can be created without InOut elements
			// for editing purposes. Simulation validation happens when run() is called.
			ended = true
		}

		fun getContext(): DefaultContext? = if (ended) context else null
	}

	private var validator: Validator? = null

	init {
		try {
			val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
			val schemaStream = MyResourceBoundle.getInstance().getSchema()
			check(schemaStream != null) { "Schema stream is null" }
			val schemaFile: Source = StreamSource(schemaStream)

			val schema = schemaFactory.newSchema(schemaFile)
			validator = schema.newValidator()
		} catch (e: SAXException) {
			e.printStackTrace()
		} catch (e: Exception) {
			check(false) { "Failed to initialize XML validator: $e" }
		}
	}

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

		private const val DEFAULT_GRID_SIZE = 100

		private val instance = XMLContextFactory()

		/**
		 * Get factory for creating editing and simulation context from XML
		 */
		@JvmStatic
		fun getInstance(): XMLContextFactory = instance
	}

	override fun createEmptyContext(): DefaultContext = XMLContext(DEFAULT_GRID_SIZE, DEFAULT_GRID_SIZE)

	@Throws(ContextCreationException::class)
	override fun createContext(file: File): DefaultContext =
		try {
			createContext(FileReader(file))
		} catch (e: FileNotFoundException) {
			throw ContextCreationException(e)
		}

	@Throws(ContextCreationException::class)
	private fun createContext(reader: Reader): DefaultContext {
		val validator = validator ?: throw ContextCreationException("Validator not initialized")
		return try {
			val inputSource = InputSource(reader)
			val handler = Handler()
			validator.validate(SAXSource(inputSource), SAXResult(handler))
			handler.getContext() ?: throw ContextCreationException("Failed to parse context from XML")
		} catch (e: Exception) {
			if (e is ContextCreationException) throw e
			throw ContextCreationException(e)
		}
	}

	@Throws(ContextCreationException::class)
	override fun createContext(stream: InputStream): DefaultContext = createContext(InputStreamReader(stream))

	override fun saveContext(
		context: Context,
		stream: OutputStream
	): Boolean {
		// TODO Auto-generated method stub
		return false
	}

	private fun appendAttribute(
		builder: StringBuilder,
		name: String,
		value: Int
	): StringBuilder = appendAttribute(builder, name, value.toString())

	private fun appendAttribute(
		builder: StringBuilder,
		name: String,
		value: Double
	): StringBuilder = appendAttribute(builder, name, value.toString())

	private fun appendAttribute(
		builder: StringBuilder,
		value: Enum<*>
	): StringBuilder {
		val clazz = value.javaClass
		return builder
			.append(classToString(clazz))
			.append("=\"")
			.append(value.name)
			.append("\" ")
	}

	private fun appendAttribute(
		builder: StringBuilder,
		name: String,
		value: Enum<*>
	): StringBuilder {
		builder.append(name)
		return appendAttribute(builder, value)
	}

	private fun appendAttribute(
		builder: StringBuilder,
		name: String,
		value: String
	): StringBuilder =
		builder
			.append(name)
			.append("=\"")
			.append(value)
			.append("\" ")

	private fun appendAttribute(
		builder: StringBuilder,
		name: String,
		value: Point
	): StringBuilder {
		builder.append(name)
		appendAttribute(builder, X, value.x)
		builder.append(name)
		return appendAttribute(builder, Y, value.y)
	}

	override fun saveContext(
		context: Context,
		file: File
	): Boolean {
		val xmlContext = Util.assertInstanceOf(DefaultContext::class.java, context) // zatim
		val railwayNetGrid = xmlContext.getRailWayNetGrid()
		return try {
			val fileWriter = FileWriter(file)
			val stringBuilder = StringBuilder("<?xml version=\"1.0\"?>\n<!DOCTYPE ")
			stringBuilder.append(ROOT_ELEMENT_NAME).append(">\n")
			stringBuilder.append('<').append(ROOT_ELEMENT_NAME).append(' ')
			appendAttribute(stringBuilder, X, railwayNetGrid.getCols())
			appendAttribute(stringBuilder, Y, railwayNetGrid.getRows())
			stringBuilder.append(">\n")
			fileWriter.write(stringBuilder.toString())

			// Save all NodeCells from the grid (including isolated nodes)
			// Use LinkedHashSet to avoid duplicates while preserving insertion order
			val allNodes = LinkedHashSet<Point>()

			// Add all nodes from the graph (nodes with connections)
			allNodes.addAll(xmlContext.getGraph().nodeSet())

			// Add any isolated NodeCells from the grid that aren't in the graph
			for (entry in railwayNetGrid) {
				val point = entry.key
				val cell = entry.value
				if (cell is NodeCell) {
					allNodes.add(point)
				}
			}

			// Write all nodes to XML
			for (p in allNodes) {
				val cell = railwayNetGrid[p]
				if (cell is NodeCell) {
					val builder = tagFor(p, cell)
					spacing(builder, 1)
					fileWriter.write(builder.toString())
				}
			}

			for (entry in xmlContext.getGraph().entrySet()) {
				val key = entry.key
				val value = entry.value
				val keyIterator = key.iterator()
				check(keyIterator.hasNext()) { "Doubleton should have at least one element" }
				val p1 = keyIterator.next()
				check(keyIterator.hasNext()) { "Doubleton should have two elements" }
				val p2 = keyIterator.next()

				val builder = tagFor(p1, p2, key, value)
				spacing(builder, 1)
				fileWriter.write(builder.toString())
			}

			fileWriter.write("</" + ROOT_ELEMENT_NAME + ">\n")
			fileWriter.close()
			true
		} catch (e: IOException) {
			check(false) { "Failed to save context: $e" }
			false
		}
	}

	// Helper method to get simple class name
	private fun classToString(clazz: Class<*>): String = clazz.simpleName

	// Helper method to add indentation
	private fun spacing(
		builder: StringBuilder,
		count: Int
	) {
		check(count > 0) { "count must be positive" }
		repeat(count) { builder.insert(0, '\t') }
	}

	private fun tagFor(
		p1: Point,
		p2: Point,
		key: Doubleton<Point, Segment>,
		value: TrackBlock
	): StringBuilder {
		val builder = StringBuilder()
		val clazz = value.javaClass
		beginOfTag(builder, clazz)
		appendAttribute(builder, FROM, p1)
		appendAttribute(builder, TO, p2)
		appendAttribute(builder, FROM, key.getValue(p1)!!)
		appendAttribute(builder, TO, key.getValue(p2)!!)
		appendAttribute(builder, ATR_LENGTH, value.length())
		appendAttribute(builder, ATR_MAX_SPEED + FROM, value.maxSpeed(value.ends()[0]))
		appendAttribute(builder, ATR_MAX_SPEED + TO, value.maxSpeed(value.ends()[1]))
		closingEndOfTag(builder)
		return builder
	}

	private fun tagFor(
		key: Point,
		cell: Cell
	): StringBuilder {
		val builder = StringBuilder()
		val clazz = cell.javaClass
		beginOfTag(builder, clazz)
		appendAttribute(builder, "", key)
		appendAttribute(builder, cell.getSpatialType())
		if (cell is OrientedPathSeparator) {
			appendAttribute(builder, ATR_ORIENT_NAME, (cell as OrientedPathSeparator).getOrientation().toString())
		}
		when (clazz) {
			RailSwitch::class.java -> appendAttribute(builder, (cell as RailSwitch).type)
			InOut::class.java -> appendAttribute(builder, NAME, (cell as InOut).getName())
		}
		closingEndOfTag(builder)
		return builder
	}

	private fun closingEndOfTag(builder: StringBuilder): StringBuilder = builder.append("/>\n")

	private fun beginOfTag(
		builder: StringBuilder,
		clazz: Class<*>
	): StringBuilder = builder.append('<').append(classToString(clazz)).append(' ')

	override fun createContext(editingContext: EditingContext): DefaultContext {
		return Util.assertInstanceOf(DefaultContext::class.java, editingContext) // zatim
	}

	@Throws(Exception::class)
	override fun createNew(
		context: EditingContext,
		clazz: Class<*>,
		vararg arguments: Any
	): Any {
		check(context != null) { "Context cannot be null" }
		check(clazz != null) { "Class cannot be null" }

		val argumentClasses =
			arguments
				.map { arg ->
					// Convert wrapper classes to primitive types for constructor lookup
					when (arg.javaClass) {
						java.lang.Boolean::class.java -> Boolean::class.javaPrimitiveType
						java.lang.Integer::class.java -> Int::class.javaPrimitiveType
						java.lang.Double::class.java -> Double::class.javaPrimitiveType
						java.lang.Float::class.java -> Float::class.javaPrimitiveType
						java.lang.Long::class.java -> Long::class.javaPrimitiveType
						java.lang.Short::class.java -> Short::class.javaPrimitiveType
						java.lang.Byte::class.java -> Byte::class.javaPrimitiveType
						java.lang.Character::class.java -> Char::class.javaPrimitiveType
						else -> arg.javaClass
					}
				}.toTypedArray()
		val constructor = clazz.getConstructor(*argumentClasses)

		return try {
			constructor.newInstance(*arguments)
		} catch (e: InvocationTargetException) {
			throw Util.assertInstanceOf(Exception::class.java, e.targetException!!)
		}
	}
}
