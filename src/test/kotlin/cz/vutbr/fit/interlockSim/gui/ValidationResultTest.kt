/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for ValidationResult data structures
	Phase 4: GUI and Simulation Tests - 2026
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for ValidationResult and related data classes.
 *
 * Tests factory methods, error formatting, and data structure correctness.
 */
@DisplayName("ValidationResult")
class ValidationResultTest {
	@Test
	@DisplayName("success creates valid result with no errors")
	fun successCreatesValidResult() {
		val result = ValidationResult.success()
		assertThat(result.isValid).isTrue()
		assertThat(result.errors).isEmpty()
		assertThat(result.warnings).isEmpty()
	}

	@Test
	@DisplayName("error creates invalid result with single error")
	fun errorCreatesInvalidResult() {
		val error = ValidationError(
			category = ErrorCategory.STRUCTURAL,
			severity = Severity.ERROR,
			message = "Test error",
			location = "Line 10",
			explanation = "Test explanation"
		)
		val result = ValidationResult.error(error)
		assertThat(result.isValid).isFalse()
		assertThat(result.errors).hasSize(1)
		assertThat(result.errors[0]).isEqualTo(error)
	}

	@Test
	@DisplayName("errors creates invalid result with multiple errors")
	fun errorsCreatesInvalidResultWithMultiple() {
		val error1 = ValidationError(
			category = ErrorCategory.STRUCTURAL,
			severity = Severity.ERROR,
			message = "Error 1",
			explanation = "Explanation 1"
		)
		val error2 = ValidationError(
			category = ErrorCategory.SAFETY,
			severity = Severity.ERROR,
			message = "Error 2",
			explanation = "Explanation 2"
		)
		val result = ValidationResult.errors(listOf(error1, error2))
		assertThat(result.isValid).isFalse()
		assertThat(result.errors).hasSize(2)
		assertThat(result.errors[0]).isEqualTo(error1)
		assertThat(result.errors[1]).isEqualTo(error2)
	}

	@Test
	@DisplayName("ValidationError format includes emoji")
	fun validationErrorFormatIncludesEmoji() {
		val error = ValidationError(
			category = ErrorCategory.STRUCTURAL,
			severity = Severity.ERROR,
			message = "Test error",
			explanation = "Test explanation"
		)
		val formatted = error.format()
		assertThat(formatted).contains("❌")
	}

	@Test
	@DisplayName("ValidationError format includes message")
	fun validationErrorFormatIncludesMessage() {
		val error = ValidationError(
			category = ErrorCategory.STRUCTURAL,
			severity = Severity.ERROR,
			message = "Test error",
			explanation = "Test explanation"
		)
		val formatted = error.format()
		assertThat(formatted).contains("Test error")
	}

	@Test
	@DisplayName("ValidationError format includes explanation")
	fun validationErrorFormatIncludesExplanation() {
		val error = ValidationError(
			category = ErrorCategory.STRUCTURAL,
			severity = Severity.ERROR,
			message = "Test error",
			explanation = "Test explanation"
		)
		val formatted = error.format()
		assertThat(formatted).contains("Test explanation")
	}

	@Test
	@DisplayName("ValidationError format includes location when present")
	fun validationErrorFormatIncludesLocation() {
		val error = ValidationError(
			category = ErrorCategory.STRUCTURAL,
			severity = Severity.ERROR,
			message = "Test error",
			location = "Line 42, Column 10",
			explanation = "Test explanation"
		)
		val formatted = error.format()
		assertThat(formatted).contains("Location: Line 42, Column 10")
	}

	@Test
	@DisplayName("ValidationError format excludes location when null")
	fun validationErrorFormatExcludesNullLocation() {
		val error = ValidationError(
			category = ErrorCategory.STRUCTURAL,
			severity = Severity.ERROR,
			message = "Test error",
			location = null,
			explanation = "Test explanation"
		)
		val formatted = error.format()
		assertThat(!formatted.contains("Location:"))
	}

	@Test
	@DisplayName("ValidationWarning format includes emoji")
	fun validationWarningFormatIncludesEmoji() {
		val warning = ValidationWarning(
			message = "Test warning",
			explanation = "Warning explanation"
		)
		val formatted = warning.format()
		assertThat(formatted).contains("⚠")
	}

	@Test
	@DisplayName("ValidationWarning format includes message")
	fun validationWarningFormatIncludesMessage() {
		val warning = ValidationWarning(
			message = "Test warning",
			explanation = "Warning explanation"
		)
		val formatted = warning.format()
		assertThat(formatted).contains("Test warning")
	}

	@Test
	@DisplayName("ValidationWarning format includes explanation")
	fun validationWarningFormatIncludesExplanation() {
		val warning = ValidationWarning(
			message = "Test warning",
			explanation = "Warning explanation"
		)
		val formatted = warning.format()
		assertThat(formatted).contains("Warning explanation")
	}

	@Test
	@DisplayName("ErrorCategory has STRUCTURAL category")
	fun errorCategoryHasStructural() {
		assertThat(ErrorCategory.STRUCTURAL).isEqualTo(ErrorCategory.STRUCTURAL)
	}

	@Test
	@DisplayName("ErrorCategory has SAFETY category")
	fun errorCategoryHasSafety() {
		assertThat(ErrorCategory.SAFETY).isEqualTo(ErrorCategory.SAFETY)
	}

	@Test
	@DisplayName("ErrorCategory has CONFIGURATION category")
	fun errorCategoryHasConfiguration() {
		assertThat(ErrorCategory.CONFIGURATION).isEqualTo(ErrorCategory.CONFIGURATION)
	}

	@Test
	@DisplayName("ErrorCategory has PERFORMANCE category")
	fun errorCategoryHasPerformance() {
		assertThat(ErrorCategory.PERFORMANCE).isEqualTo(ErrorCategory.PERFORMANCE)
	}

	@Test
	@DisplayName("Severity has ERROR level")
	fun severityHasError() {
		assertThat(Severity.ERROR).isEqualTo(Severity.ERROR)
	}

	@Test
	@DisplayName("Severity has WARNING level")
	fun severityHasWarning() {
		assertThat(Severity.WARNING).isEqualTo(Severity.WARNING)
	}

	@Test
	@DisplayName("ValidationResult can have both errors and warnings")
	fun validationResultCanHaveBothErrorsAndWarnings() {
		val error = ValidationError(
			category = ErrorCategory.STRUCTURAL,
			severity = Severity.ERROR,
			message = "Test error",
			explanation = "Error explanation"
		)
		val warning = ValidationWarning(
			message = "Test warning",
			explanation = "Warning explanation"
		)
		val result = ValidationResult(
			isValid = false,
			errors = listOf(error),
			warnings = listOf(warning)
		)
		assertThat(result.isValid).isFalse()
		assertThat(result.errors).hasSize(1)
		assertThat(result.warnings).hasSize(1)
	}
}
