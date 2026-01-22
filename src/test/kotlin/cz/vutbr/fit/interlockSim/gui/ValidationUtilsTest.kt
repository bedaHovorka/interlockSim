/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for ValidationUtils
	Phase 4: GUI and Simulation Tests - 2026
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.FileNotFoundException

/**
 * Tests for ValidationUtils exception conversion.
 *
 * Tests conversion of various exception types to ValidationResult objects
 * with detailed error information.
 */
@DisplayName("ValidationUtils")
class ValidationUtilsTest {
	@Test
	@DisplayName("fromException with SAXParseException includes line and column")
	fun fromExceptionWithSAXParseExceptionIncludesLineAndColumn() {
		val saxException = SAXParseException("XML error", null, null, 42, 10)
		val exception = ContextCreationException("Failed to create context", saxException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.isValid).isFalse()
		assertThat(result.errors).hasSize(1)
		assertThat(result.errors[0].location).isEqualTo("Line 42, Column 10")
		assertThat(result.errors[0].category).isEqualTo(ErrorCategory.STRUCTURAL)
		assertThat(result.errors[0].severity).isEqualTo(Severity.ERROR)
	}

	@Test
	@DisplayName("fromException with SAXParseException has correct message")
	fun fromExceptionWithSAXParseExceptionHasCorrectMessage() {
		val saxException = SAXParseException("XML error", null, null, 42, 10)
		val exception = ContextCreationException("Failed to create context", saxException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.errors[0].message).isEqualTo("XML Schema Violation")
	}

	@Test
	@DisplayName("fromException with SAXException without location")
	fun fromExceptionWithSAXException() {
		val saxException = SAXException("Validation failed")
		val exception = ContextCreationException("Failed to create context", saxException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.isValid).isFalse()
		assertThat(result.errors).hasSize(1)
		assertThat(result.errors[0].location).isNull()
		assertThat(result.errors[0].message).isEqualTo("Validation Failed")
		assertThat(result.errors[0].category).isEqualTo(ErrorCategory.STRUCTURAL)
	}

	@Test
	@DisplayName("fromException with FileNotFoundException")
	fun fromExceptionWithFileNotFoundException() {
		val fileException = FileNotFoundException("/path/to/missing/file.xml")
		val exception = ContextCreationException("Failed to create context", fileException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.isValid).isFalse()
		assertThat(result.errors).hasSize(1)
		assertThat(result.errors[0].message).isEqualTo("File Not Found")
		assertThat(result.errors[0].location).isEqualTo("/path/to/missing/file.xml")
		assertThat(result.errors[0].explanation).contains("could not be found")
	}

	@Test
	@DisplayName("fromException with null cause uses generic handling")
	fun fromExceptionWithNullCause() {
		val exception = ContextCreationException("Failed to create context", null)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.isValid).isFalse()
		assertThat(result.errors).hasSize(1)
		assertThat(result.errors[0].message).isEqualTo("Loading Failed")
	}

	@Test
	@DisplayName("fromException with unknown cause uses generic handling")
	fun fromExceptionWithUnknownCause() {
		val unknownException = IllegalArgumentException("Unknown error")
		val exception = ContextCreationException("Failed to create context", unknownException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.isValid).isFalse()
		assertThat(result.errors).hasSize(1)
		assertThat(result.errors[0].message).isEqualTo("Loading Failed")
	}

	@Test
	@DisplayName("parseErrorMessage handles InOut validation error")
	fun parseErrorMessageHandlesInOutValidation() {
		val saxException = SAXParseException("InOut must have at least 2 elements", null, null, 10, 5)
		val exception = ContextCreationException("Failed", saxException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.errors[0].explanation).contains("Minimum 2 InOut elements required")
		assertThat(result.errors[0].explanation).contains("entry/exit points for trains")
	}

	@Test
	@DisplayName("parseErrorMessage handles nested net elements error")
	fun parseErrorMessageHandlesNestedNetElements() {
		val saxException = SAXParseException("Nested net elements are not allowed", null, null, 5, 1)
		val exception = ContextCreationException("Failed", saxException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.errors[0].explanation).contains("Nested <net> elements are not allowed")
		assertThat(result.errors[0].explanation).contains("exactly one root <net> element")
	}

	@Test
	@DisplayName("parseErrorMessage handles wrong tag name error")
	fun parseErrorMessageHandlesWrongTagName() {
		val saxException = SAXParseException("Wrong tag name: InvalidElement", null, null, 15, 3)
		val exception = ContextCreationException("Failed", saxException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.errors[0].explanation).contains("Invalid XML element name")
		assertThat(result.errors[0].explanation).contains("RailSemaphore, RailSwitch, InOut")
	}

	@Test
	@DisplayName("parseErrorMessage handles context not initialized error")
	fun parseErrorMessageHandlesContextNotInitialized() {
		val saxException = SAXParseException("Context not initialized before parsing", null, null, 2, 1)
		val exception = ContextCreationException("Failed", saxException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.errors[0].explanation).contains("Context not initialized")
		assertThat(result.errors[0].explanation).contains("missing the required <net> root element")
	}

	@Test
	@DisplayName("parseErrorMessage returns original message for unknown errors")
	fun parseErrorMessageReturnsOriginalForUnknown() {
		val saxException = SAXParseException("Some other validation error", null, null, 20, 5)
		val exception = ContextCreationException("Failed", saxException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.errors[0].explanation).isEqualTo("Some other validation error")
	}

	@Test
	@DisplayName("ValidationError has correct category for structural errors")
	fun validationErrorHasCorrectCategoryForStructuralErrors() {
		val saxException = SAXParseException("XML error", null, null, 1, 1)
		val exception = ContextCreationException("Failed", saxException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.errors[0].category).isEqualTo(ErrorCategory.STRUCTURAL)
	}

	@Test
	@DisplayName("ValidationError has ERROR severity")
	fun validationErrorHasErrorSeverity() {
		val saxException = SAXParseException("XML error", null, null, 1, 1)
		val exception = ContextCreationException("Failed", saxException)

		val result = ValidationUtils.fromException(exception)

		assertThat(result.errors[0].severity).isEqualTo(Severity.ERROR)
	}

	@Test
	@DisplayName("fromException preserves exception message content")
	fun fromExceptionPreservesMessageContent() {
		val saxException = SAXParseException("Custom error message", null, null, 99, 88)
		val exception = ContextCreationException("Failed", saxException)

		val result = ValidationUtils.fromException(exception)

		// The explanation should contain the original error message or a parsed version
		assertThat(result.errors[0].explanation).isNotNull()
	}
}
