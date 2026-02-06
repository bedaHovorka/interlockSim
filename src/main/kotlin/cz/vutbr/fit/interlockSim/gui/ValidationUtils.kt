/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.context.ContextCreationException
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.FileNotFoundException

/**
 * Utility functions for converting exceptions to ValidationResult objects.
 */
object ValidationUtils {
	/**
	 * Converts a ContextCreationException to a ValidationResult.
	 *
	 * Extracts structured error information from the exception and its cause.
	 */
	fun fromException(exception: ContextCreationException): ValidationResult {
		val cause = exception.cause
		return when (cause) {
			is SAXParseException -> fromSAXParseException(cause)
			is SAXException -> fromSAXException(cause)
			is FileNotFoundException -> fromFileNotFoundException(cause)
			null -> fromGenericException(exception)
			else -> fromGenericException(cause)
		}
	}

	/**
	 * Converts a SAXParseException to ValidationResult with line/column information.
	 */
	private fun fromSAXParseException(exception: SAXParseException): ValidationResult {
		val location = "Line ${exception.lineNumber}, Column ${exception.columnNumber}"
		val message = exception.message ?: "XML parsing error"

		val error =
			ValidationError(
				category = ErrorCategory.STRUCTURAL,
				severity = Severity.ERROR,
				message = "XML Schema Violation",
				location = location,
				explanation = parseErrorMessage(message)
			)

		return ValidationResult.error(error)
	}

	/**
	 * Converts a SAXException to ValidationResult.
	 */
	private fun fromSAXException(exception: SAXException): ValidationResult {
		val message = exception.message ?: "XML validation error"
		val error =
			ValidationError(
				category = ErrorCategory.STRUCTURAL,
				severity = Severity.ERROR,
				message = "Validation Failed",
				location = null,
				explanation = parseErrorMessage(message)
			)

		return ValidationResult.error(error)
	}

	/**
	 * Converts a FileNotFoundException to ValidationResult.
	 */
	private fun fromFileNotFoundException(exception: FileNotFoundException): ValidationResult {
		val error =
			ValidationError(
				category = ErrorCategory.STRUCTURAL,
				severity = Severity.ERROR,
				message = "File Not Found",
				location = exception.message,
				explanation = "The selected file could not be found or accessed. Please check the file path and permissions."
			)

		return ValidationResult.error(error)
	}

	/**
	 * Converts a generic exception to ValidationResult.
	 */
	private fun fromGenericException(exception: Throwable): ValidationResult {
		val message = exception.message ?: "Unknown error"
		val error =
			ValidationError(
				category = ErrorCategory.STRUCTURAL,
				severity = Severity.ERROR,
				message = "Loading Failed",
				location = null,
				explanation = parseErrorMessage(message)
			)

		return ValidationResult.error(error)
	}

	/**
	 * Parses exception message to extract meaningful explanation.
	 * Handles special cases like InOut count validation.
	 */
	private fun parseErrorMessage(message: String): String =
		when {
			message.contains("InOut") && message.contains("at least 1") -> {
				"""
				|Minimum 1 InOut element required (found none).
				|
				|InOut elements define entry/exit points for trains. At least 1 is required for simulation.
				|With bidirectional train operation, a single InOut can serve as both entry and exit point.
				|
				|Please add an InOut element to your railway network.
				""".trimMargin()
			}

			message.contains("Nested net elements") -> {
				"""
				|Nested <net> elements are not allowed.
				|
				|The XML file should have exactly one root <net> element containing all railway components.
				|Check for duplicate or nested <net> tags in your XML file.
				""".trimMargin()
			}

			message.contains("Wrong tag name") -> {
				"""
				|Invalid XML element name.
				|
				|The XML file contains an element name that is not recognized by the railway network schema.
				|Valid elements include: RailSemaphore, RailSwitch, InOut, SimpleTrackBlock.
				|
				|$message
				""".trimMargin()
			}

			message.contains("Context not initialized") -> {
				"""
				|XML structure error: Context not initialized before parsing elements.
				|
				|This usually means the XML file is missing the required <net> root element or
				|its X/Y dimension attributes.
				""".trimMargin()
			}

			else -> message
		}
}
