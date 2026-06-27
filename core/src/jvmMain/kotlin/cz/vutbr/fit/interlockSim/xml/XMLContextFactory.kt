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

import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.util.Util
import cz.vutbr.fit.interlockSim.util.ValidationResult
import cz.vutbr.fit.interlockSim.util.ValidationUtils
import io.github.oshai.kotlinlogging.KotlinLogging
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

/**
 * XML implementation of {@link EditingContextFactory}.
 *
 * Delegates XML parsing, writing, and validation to KMP components:
 * - [XmlSchemaValidator] for XSD schema validation
 * - [XmlContextReader] for XML parsing
 * - [XmlContextWriter] for XML serialization
 */
class XMLContextFactory : JvmEditingContextFactory {
	// Issue #60: Track length validation implemented in Train constructor
	// Validation occurs at train creation time when both train length and topology are available

	private val xmlValidator = XmlSchemaValidator()

	companion object {
		private val logger = KotlinLogging.logger {}

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
			FileReader(file).use { createContext(it) }
		} catch (e: FileNotFoundException) {
			throw ContextCreationException(e)
		}

	@Throws(ContextCreationException::class)
	private fun createContext(reader: Reader): DefaultEditingContext =
		try {
			val xmlContent = reader.readText()

			// Validate against XSD schema
			val validationResult = xmlValidator.validate(xmlContent)
			if (!validationResult.isValid) {
				val message = validationResult.errors.joinToString("; ")
				throw ContextCreationException(message)
			}

			// Parse XML into context
			XmlContextReader().parse(xmlContent)
		} catch (e: Exception) {
			if (e is ContextCreationException) throw e
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
	override fun createContext(stream: InputStream): Context<*, *> = InputStreamReader(stream).use { createContext(it) }

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
		// First attempt: Parse with full validation
		try {
			val validationResult = xmlValidator.validate(xmlContent)
			val context = XmlContextReader().parse(xmlContent, skipStructuralValidation = false)

			return if (validationResult.isValid) {
				LenientParseResult(
					context = context,
					validationResult = ValidationResult.success(),
					isParseable = true
				)
			} else {
				// XSD validation errors but parsing succeeded
				LenientParseResult(
					context = context,
					validationResult =
						ValidationUtils.fromException(
							ContextCreationException(validationResult.errors.joinToString("; "))
						),
					isParseable = true
				)
			}
		} catch (e: Exception) {
			// Parsing failed — try without structural validation
			val validationError =
				if (e is ContextCreationException) e else ContextCreationException(e)

			return try {
				val context = XmlContextReader().parse(xmlContent, skipStructuralValidation = true)
				LenientParseResult(
					context = context,
					validationResult = ValidationUtils.fromException(validationError),
					isParseable = true
				)
			} catch (parseException: Exception) {
				LenientParseResult(
					context = null,
					validationResult =
						ValidationUtils.fromException(
							if (parseException is ContextCreationException) {
								parseException
							} else {
								ContextCreationException(parseException)
							}
						),
					isParseable = false
				)
			}
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
		val editingContext = Util.assertInstanceOf<DefaultEditingContext>(context)
		return XmlContextWriter().generate(editingContext)
	}

	/**
	 * Saves an editing context to an output stream.
	 *
	 * Pre-save validation (Issue #80, PR #357):
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

	/**
	 * Saves an editing context to a file.
	 *
	 * Pre-save validation (Issue #80, PR #357):
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
