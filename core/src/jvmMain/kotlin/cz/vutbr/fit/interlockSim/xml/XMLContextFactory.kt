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

import cz.vutbr.fit.interlockSim.MyResourceBundle
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Type
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.ValidationResult
import cz.vutbr.fit.interlockSim.util.ValidationUtils
import cz.vutbr.fit.interlockSim.util.Doubleton
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.mp.KoinPlatform.getKoin
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
import kotlin.getValue

/**
 * XML implementation of {@link EditingContextFactory}
 */
class XMLContextFactory : EditingContextFactory {
	private val myResourceBundle: MyResourceBundle by getKoin().inject()

	// TODO: Validate track length >= train length - see issue #60 (relates to Goals 3 & 4)

	private inner class Handler(private val skipStructuralValidation: Boolean = false) : DefaultHandler() {
		private var editingContext: DefaultEditingContext? = null
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
				editingContext = DefaultEditingContext(cols, rows)
				return
			}

			val ctx = editingContext ?: throw SAXException("Context not initialized")
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
									val name = parseNameAttribute(uri, attributes) // Use helper
									if (name != null) {
										arrayOf(name as Any, orientation as Any, spatialType as Any)
									} else {
										arrayOf(orientation as Any, spatialType as Any)
									}
								}
								else -> error("Unexpected OrientedPathSeparator: $clazz")
							}
						}
						clazz == RailSwitch::class.java -> {
							val type = getEnum(uri, attributes, Type::class.java)
							val name = parseNameAttribute(uri, attributes) // Use helper
							if (name != null) {
								arrayOf(name as Any, spatialType as Any, type as Any)
							} else {
								arrayOf(spatialType as Any, type as Any)
							}
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
				val fromCell = railwayNetGrid[from] ?: throw SAXException("Cell at 'from' position $from not found")
				val toCell = railwayNetGrid[to] ?: throw SAXException("Cell at 'to' position $to not found")
				val fromNode = CellUtilities.assertNodeCell(fromCell)
				val toNode = CellUtilities.assertNodeCell(toCell)

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

		/**
		 * Gets an integer attribute value from XML attributes.
		 *
		 * Issue #258: Fixed NullPointerException when required attributes are missing.
		 * - Before: Called .toInt() on null value → NPE
		 * - After: Explicit null check with clear error message
		 *
		 * Example: Opening <net> without X or Y attributes now shows
		 * "Missing required attribute: X" instead of crashing.
		 *
		 * @throws SAXException if attribute is missing or not a valid integer
		 */
		private fun getInt(
			uri: String,
			attributes: Attributes,
			name: String
		): Int =
			try {
				val value =
					attributes.getValue(uri, name)
						?: throw SAXException("Missing required attribute: $name")
				value.toInt()
			} catch (e: NumberFormatException) {
				throw SAXException("Invalid integer value for attribute: $name", e)
			}

		/**
		 * Gets a double attribute value from XML attributes.
		 *
		 * Issue #258: Fixed NullPointerException when required attributes are missing.
		 * - Before: Called .toDouble() on null value → NPE
		 * - After: Explicit null check with clear error message
		 *
		 * Example: Opening track block without maxSpeed or length attributes
		 * now shows "Missing required attribute: maxSpeed" instead of crashing.
		 *
		 * @throws SAXException if attribute is missing or not a valid double
		 */
		private fun getDouble(
			uri: String,
			attributes: Attributes,
			name: String
		): Double =
			try {
				val value =
					attributes.getValue(uri, name)
						?: throw SAXException("Missing required attribute: $name")
				value.toDouble()
			} catch (e: NumberFormatException) {
				throw SAXException("Invalid double value for attribute: $name", e)
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

		/**
		 * Called when XML parsing completes. Validates the parsed railway network structure.
		 *
		 * **Validation Rules:**
		 * - Minimum [MIN_INOUT_ELEMENTS] InOut element required (entry/exit point)
		 * - InOut elements define where trains enter/exit the railway network
		 * - With bidirectional operation, a single InOut can serve as both entry and exit
		 * - Context must be initialized (non-null editingContext)
		 *
		 * This method is called automatically by the SAX parser after all XML elements
		 * have been processed via [startElement] and [endElement] callbacks.
		 *
		 * **Example Valid Network:**
		 * ```xml
		 * <net X="10" Y="10">
		 *   <InOut name="ENTRY" ... />
		 *   <!-- Optional: Additional InOut for dedicated exit -->
		 * </net>
		 * ```
		 *
		 * **Example Invalid Network (throws exception):**
		 * ```xml
		 * <net X="10" Y="10">
		 *   <!-- No InOut = invalid -->
		 * </net>
		 * ```
		 *
		 * @throws SAXException if:
		 *   - Context not initialized (null editingContext)
		 *   - InOut count < [MIN_INOUT_ELEMENTS] (minimum requirement)
		 * @since 2006-2007 (original thesis)
		 * @see startElement for XML element processing
		 * @see MIN_INOUT_ELEMENTS for validation threshold
		 */
		override fun endDocument() {
			// Strict validation: Railway networks must have at least 1 InOut element (entry/exit point)
			val ctx = editingContext ?: throw SAXException("Context not initialized")

			// Only validate InOut count if not skipping structural validation
			if (!skipStructuralValidation) {
				// Access inouts via public method from BaseContext
				val inOutsCount = ctx.getInOuts().size
				if (inOutsCount < MIN_INOUT_ELEMENTS) {
					throw SAXException(
						"Railway network must have at least $MIN_INOUT_ELEMENTS InOut elements (entry and exit points). " +
							"Found: $inOutsCount"
					)
				}
			}
			ended = true
		}

		/**
		 * Returns the parsed context as a DefaultEditingContext.
		 *
		 * When skipStructuralValidation is true, returns context even if validation would fail.
		 */
		fun getContext(): DefaultEditingContext? =
			if (ended && editingContext != null) {
				editingContext
			} else {
				null
			}
	}

	private var validator: Validator? = null

	init {
		try {
			val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
			val schemaStream = myResourceBundle.getSchema()
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

	/**
	 * Parses the optional name attribute from XML.
	 *
	 * @param uri XML namespace URI
	 * @param attributes XML attributes collection
	 * @return Name string if present and non-empty, null otherwise
	 * @throws SAXException if name validation fails
	 * @since 2026-01 (Issue #306)
	 */
	private fun parseNameAttribute(
		uri: String,
		attributes: Attributes
	): String? {
		val name = attributes.getValue(uri, NAME)
		validateName(name)
		return if (name != null && name.isNotEmpty()) name else null
	}

	/**
	 * Validates name format (alphanumeric, hyphens, underscores, max 50 chars).
	 *
	 * @param name Name to validate (null or empty is valid)
	 * @throws SAXException if validation fails
	 * @since 2026-01 (Issue #306)
	 */
	private fun validateName(name: String?) {
		if (name != null && name.isNotEmpty()) {
			if (name.length > 50) {
				throw SAXException("Element name too long (max 50 characters): $name")
			}
			if (!name.matches(Regex("[a-zA-Z0-9_-]+"))) {
				throw SAXException(
					"Element name contains invalid characters (allowed: alphanumeric, -, _): $name"
				)
			}
		}
	}

	companion object {
		private val logger = KotlinLogging.logger {}

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

		/**
		 * Minimum number of InOut elements required in a railway network.
		 *
		 * Railway networks must have at least 1 InOut element (entry/exit point).
		 * With bidirectional train operation (Issue #356), a single InOut can serve
		 * as both entry and exit point.
		 *
		 * @since 2026-01 (Issue #76 validation, Issue #77 code quality)
		 * @since 2026-02 (Issue #341, PR #356 - reduced from 2 to 1 for bidirectional support)
		 */
		const val MIN_INOUT_ELEMENTS = 1
	}

	override fun createEmptyContext(): EditingContext = DefaultEditingContext(DEFAULT_GRID_SIZE, DEFAULT_GRID_SIZE)

	/**
	 * Creates an EditingContext by parsing an XML file conforming to data.xsd schema.
	 *
	 * **Validation Requirements:**
	 * - Minimum 1 InOut element required (entry/exit point)
	 * - InOut elements define where trains enter/exit the railway network
	 * - With bidirectional operation, a single InOut can serve as both entry and exit
	 *
	 * @param file XML file containing railway network definition
	 * @return Parsed EditingContext with validated network structure
	 * @throws ContextCreationException if:
	 *   - File not found
	 *   - XML validation fails against schema
	 *   - InOut count < 1 (minimum requirement)
	 *   - Network structure is invalid
	 */
	@Throws(ContextCreationException::class)
	override fun createContext(file: File): Context<*, *> =
		try {
			createContext(FileReader(file))
		} catch (e: FileNotFoundException) {
			throw ContextCreationException(e)
		}

	@Throws(ContextCreationException::class)
	private fun createContext(reader: Reader): DefaultEditingContext {
		val validator = validator ?: throw ContextCreationException("Validator not initialized")
		return try {
			val inputSource = InputSource(reader)
			val handler = Handler()
			validator.validate(SAXSource(inputSource), SAXResult(handler))
			handler.getContext() ?: throw ContextCreationException("Failed to parse context from XML")
		} catch (e: SAXException) {
			// Enhance SAXException with more context
			val message = e.message ?: "Unknown XML parsing error"
			val enhancedMessage =
				if (message.contains("InOut")) {
					"$message\n\nInOut elements define entry/exit points for trains. " +
						"At least $MIN_INOUT_ELEMENTS is required for simulation.\n" +
						"Note: With bidirectional train operation (Issue #356), " +
						"a single InOut can serve as both entry and exit."
				} else {
					message
				}
			throw ContextCreationException(enhancedMessage)
		} catch (e: Exception) {
			if (e is ContextCreationException) throw e
			throw ContextCreationException(e)
		}
	}

	/**
	 * Creates an EditingContext by parsing an XML stream conforming to data.xsd schema.
	 *
	 * **Validation Requirements:**
	 * - Minimum 1 InOut element required (entry/exit point)
	 * - InOut elements define where trains enter/exit the railway network
	 * - With bidirectional operation, a single InOut can serve as both entry and exit
	 *
	 * @param stream InputStream containing XML railway network definition
	 * @return Parsed EditingContext with validated network structure
	 * @throws ContextCreationException if:
	 *   - XML validation fails against schema
	 *   - InOut count < 1 (minimum requirement)
	 *   - Network structure is invalid
	 */
	@Throws(ContextCreationException::class)
	override fun createContext(stream: InputStream): Context<*, *> = createContext(InputStreamReader(stream))

	/**
	 * Result of lenient XML parsing for editor mode.
	 *
	 * Separates unparseable XML (malformed syntax) from parseable XML with validation warnings.
	 *
	 * @property context The parsed context (null if XML is unparseable)
	 * @property validationResult Validation errors/warnings
	 * @property isParseable Whether the XML could be parsed (even if invalid)
	 */
	data class LenientParseResult(
		val context: DefaultEditingContext?,
		val validationResult: ValidationResult,
		val isParseable: Boolean
	)

	/**
	 * Attempts to parse XML file with lenient validation for editor mode.
	 *
	 * This method separates parsing from validation:
	 * 1. **Unparseable XML** (malformed syntax): Returns null context, isParseable = false
	 * 2. **Parseable XML with validation errors**: Returns context, isParseable = true, validationResult has errors
	 *
	 * This allows the editor to open files with validation errors so users can fix them.
	 *
	 * @param file XML file containing railway network definition
	 * @return LenientParseResult with context (if parseable) and validation result
	 */
	fun createContextLenient(file: File): LenientParseResult =
		try {
			// Read file content so we can retry parsing without validation if needed
			val xmlContent = file.readText()
			createContextLenient(xmlContent)
		} catch (e: FileNotFoundException) {
			LenientParseResult(
				context = null,
				validationResult = ValidationUtils.fromException(ContextCreationException(e)),
				isParseable = false
			)
		} catch (e: IOException) {
			LenientParseResult(
				context = null,
				validationResult = ValidationUtils.fromException(ContextCreationException(e)),
				isParseable = false
			)
		}

	/**
	 * Attempts to parse XML string with lenient validation for editor mode.
	 *
	 * @param xmlContent String containing XML railway network definition
	 * @return LenientParseResult with context (if parseable) and validation result
	 */
	private fun createContextLenient(xmlContent: String): LenientParseResult {
		val validator = validator
		if (validator == null) {
			return LenientParseResult(
				context = null,
				validationResult = ValidationUtils.fromException(ContextCreationException("Validator not initialized")),
				isParseable = false
			)
		}

		// First attempt: Parse with full validation (schema + structural)
		try {
			val inputSource = InputSource(java.io.StringReader(xmlContent))
			val handler = Handler(skipStructuralValidation = false)

			// Try to validate with schema - this will throw on both parse and validation errors
			validator.validate(SAXSource(inputSource), SAXResult(handler))

			val context = handler.getContext()
			if (context == null) {
				return LenientParseResult(
					context = null,
					validationResult = ValidationUtils.fromException(ContextCreationException("Failed to parse context from XML")),
					isParseable = false
				)
			} else {
				// Successfully parsed and validated
				return LenientParseResult(
					context = context,
					validationResult = ValidationResult.success(),
					isParseable = true
				)
			}
		} catch (e: org.xml.sax.SAXParseException) {
			// SAXParseException = unparseable XML (malformed syntax)
			// Line/column info available - definitely a parse error
			return LenientParseResult(
				context = null,
				validationResult = ValidationUtils.fromException(ContextCreationException(e)),
				isParseable = false
			)
		} catch (e: SAXException) {
			// SAXException (not SAXParseException) = validation error
			// Could be schema validation or structural validation (e.g., InOut count < 2)
			// Try parsing without validation to see if we can get a context

			val validationError = ContextCreationException(e)

			// Second attempt: Parse without schema validation but WITH structural validation disabled
			return try {
				val inputSource = InputSource(java.io.StringReader(xmlContent))
				val handler = Handler(skipStructuralValidation = true)

				// Parse without schema validation - use SAX parser directly
				val saxParserFactory = javax.xml.parsers.SAXParserFactory.newInstance()
				saxParserFactory.isNamespaceAware = true  // Keep namespace awareness
				val saxParser = saxParserFactory.newSAXParser()
				saxParser.parse(inputSource, handler)

				val context = handler.getContext()

				if (context != null) {
					// Successfully parsed without validation - it's parseable with errors
					LenientParseResult(
						context = context,
						validationResult = ValidationUtils.fromException(validationError),
						isParseable = true
					)
				} else {
					// Couldn't get context even without validation
					LenientParseResult(
						context = null,
						validationResult = ValidationUtils.fromException(validationError),
						isParseable = false
					)
				}
			} catch (parseException: Exception) {
				// Even without validation, we couldn't parse - it's malformed
				LenientParseResult(
					context = null,
					validationResult = ValidationUtils.fromException(ContextCreationException(parseException)),
					isParseable = false
				)
			}
		} catch (e: Exception) {
			// Other exceptions (e.g., context creation errors)
			return LenientParseResult(
				context = null,
				validationResult = ValidationUtils.fromException(
					if (e is ContextCreationException) e else ContextCreationException(e)
				),
				isParseable = false
			)
		}
	}

	/**
	 * Generates XML representation of a Context.
	 * Accepts any Context type (only EditingContext) because only saves
	 * the static network structure (nodes, tracks, connections, grid dimensions).
	 *
	 * @param context The context to serialize
	 * @return XML string matching data.xsd schema
	 * @throws IOException if serialization fails
	 */
	private fun generateXML(context: Context<*, *>): String {
		val xmlContext = Util.assertInstanceOf<EditingContext>(context)
		val railwayNetGrid = xmlContext.getRailWayNetGrid()
		val builder = StringBuilder()

		// XML declaration and DOCTYPE
		builder.append("<?xml version=\"1.0\"?>\n<!DOCTYPE ")
		builder.append(ROOT_ELEMENT_NAME).append(">\n")

		// Root element with grid dimensions
		builder.append('<').append(ROOT_ELEMENT_NAME).append(' ')
		appendAttribute(builder, X, railwayNetGrid.cols)
		appendAttribute(builder, Y, railwayNetGrid.rows)
		builder.append(">\n")

		// Save all NodeCells from the grid (including isolated nodes)
		// Use LinkedHashSet to avoid duplicates while preserving insertion order
		val allNodes = linkedSetOf<Point>()

		// Add all nodes from the graph (nodes with connections)
		allNodes.addAll(xmlContext.getGraph().nodeSet())

		// Add any isolated NodeCells from the grid that aren't in the graph
		// Grid parameterization: Grid is typed as RailwayNetGrid<NodeCell> but internally contains Cell (NodeCell + TrackBlockPart)
		// Cast to Cell grid to iterate without ClassCastException
		@Suppress("UNCHECKED_CAST")
		val cellGrid = railwayNetGrid as RailwayNetGrid<Cell>
		for (entry in cellGrid) {
			val point = entry.key
			val cell = entry.value
			// Check for only static NodeCell
			if (cell is NodeCell) {
				allNodes.add(point)
			}
		}

		// Write all nodes to XML
		for (p in allNodes) {
			val cell = railwayNetGrid[p]
			// Check for only static NodeCell
			if (cell is NodeCell) {
				val tag = tagFor(p, cell)
				spacing(tag, 1)
				builder.append(tag)
			}
		}

		// Write all track blocks (edges in the graph)
		for (entry in xmlContext.getGraph().entrySet()) {
			val key = entry.key
			val value = entry.value
			val keyIterator = key.iterator()
			check(keyIterator.hasNext()) { "Doubleton should have at least one element" }
			val p1 = keyIterator.next()
			check(keyIterator.hasNext()) { "Doubleton should have two elements" }
			val p2 = keyIterator.next()

			val tag = tagFor(p1, p2, key, value)
			spacing(tag, 1)
			builder.append(tag)
		}

		// Close root element
		builder.append("</").append(ROOT_ELEMENT_NAME).append(">\n")

		return builder.toString()
	}

	/**
	 * Saves an editing context to an output stream.
	 *
	 * Pre-save validation (Issue #XXX, PR #358):
	 * - Validates InOut count before serialization
	 * - Prevents saving invalid contexts (< MIN_INOUT_ELEMENTS InOuts)
	 * - Returns false if validation fails
	 *
	 * @param context The editing context to save
	 * @param stream Target output stream
	 * @return true if saved successfully, false if validation failed or IO error occurred
	 */
	override fun saveContext(
		context: Context<*, *>,
		stream: OutputStream
	): Boolean {
		// Pre-save validation: Check InOut count
		val editingContext = Util.assertInstanceOf<EditingContext>(context)
		val inOutsCount = editingContext.getInOuts().size
		if (inOutsCount < MIN_INOUT_ELEMENTS) {
			logger.warn {
				"Cannot save context: Insufficient InOut elements. " +
					"Found: $inOutsCount, Required: $MIN_INOUT_ELEMENTS"
			}
			return false
		}

		// Existing save logic
		return try {
			val xml = generateXML(context)
			// Use UTF-8 encoding for consistent XML output
			stream.write(xml.toByteArray(Charsets.UTF_8))
			stream.flush()
			true
		} catch (e: IOException) {
			// Log error and return false (do not throw to allow caller to handle gracefully)
			logger.error(e) { "Failed to save context to output stream" }
			false
		}
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

	/**
	 * Saves an editing context to a file.
	 *
	 * Pre-save validation (Issue #XXX, PR #358):
	 * - Validates InOut count before serialization
	 * - Prevents saving invalid contexts (< MIN_INOUT_ELEMENTS InOuts)
	 * - Returns false if validation fails
	 *
	 * @param context The editing context to save
	 * @param file Target file path
	 * @return true if saved successfully, false if validation failed or IO error occurred
	 */
	override fun saveContext(
		context: Context<*, *>,
		file: File
	): Boolean {
		// Pre-save validation: Check InOut count
		val editingContext = Util.assertInstanceOf<EditingContext>(context)
		val inOutsCount = editingContext.getInOuts().size
		if (inOutsCount < MIN_INOUT_ELEMENTS) {
			logger.warn {
				"Cannot save context: Insufficient InOut elements. " +
					"Found: $inOutsCount, Required: $MIN_INOUT_ELEMENTS"
			}
			return false
		}

		// Existing save logic
		return try {
			val xml = generateXML(context)
			FileWriter(file).use { writer ->
				writer.write(xml)
			}
			true
		} catch (e: IOException) {
			// Log error and return false (do not throw to allow caller to handle gracefully)
			logger.error(e) { "Failed to save context to file: ${file.absolutePath}" }
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
		cell.getSpatialType()?.let { appendAttribute(builder, it) }
		if (cell is OrientedPathSeparator) {
			appendAttribute(builder, ATR_ORIENT_NAME, (cell as OrientedPathSeparator).getOrientation().toString())
		}
		when (clazz) {
			RailSwitch::class.java -> {
				appendAttribute(builder, (cell as RailSwitch).type)
				val name = cell.getName()
				if (name.isNotEmpty()) {
					appendAttribute(builder, NAME, name)
				}
			}
			RailSemaphore::class.java -> {
				val name = (cell as RailSemaphore).getName()
				if (name.isNotEmpty()) {
					appendAttribute(builder, NAME, name)
				}
			}
			InOut::class.java -> {
				val name = (cell as InOut).getName()
				if (name.isNotEmpty()) {
					appendAttribute(builder, NAME, name)
				}
			}
		}
		closingEndOfTag(builder)
		return builder
	}

	private fun closingEndOfTag(builder: StringBuilder): StringBuilder = builder.append("/>\n")

	private fun beginOfTag(
		builder: StringBuilder,
		clazz: Class<*>
	): StringBuilder = builder.append('<').append(classToString(clazz)).append(' ')

	@Throws(Exception::class)
	override fun createNew(
		context: EditingContext,
		clazz: Class<*>,
		vararg arguments: Any
	): Any {
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
			throw Util.assertInstanceOf<Exception>(e.targetException!!)
		}
	}
}
