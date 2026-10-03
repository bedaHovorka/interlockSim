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

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ValidationDialog.buildErrorMessage], the pure text builder behind the dialog's text area (Issue #1005).
 *
 * Kept apart from [ValidationDialogTest]: that class builds real dialogs, is tagged `integration-test` and
 * skips headless, while these tests need no display and run in the plain `test` task.
 */
@DisplayName("ValidationDialog.buildErrorMessage")
class ValidationDialogMessageTest {
	private fun error(n: Int) =
		ValidationError(
			category = ErrorCategory.STRUCTURAL,
			severity = Severity.ERROR,
			message = "Error $n",
			explanation = "Explanation $n"
		)

	private fun warning(n: Int) = ValidationWarning(message = "Warning $n", explanation = "Warning explanation $n")

	private val errorSeparator = "\n\n" + "─".repeat(50) + "\n\n"
	private val warningSectionSeparator = "\n\n" + "═".repeat(50) + "\n\n"

	@Test
	@DisplayName("buildErrorMessage with errors only separates errors with a thin rule and adds no warning section")
	fun buildErrorMessageErrorsOnly() {
		val result = ValidationResult(isValid = false, errors = listOf(error(1), error(2)), warnings = emptyList())

		assertThat(ValidationDialog.buildErrorMessage(result))
			.isEqualTo(error(1).format() + errorSeparator + error(2).format())
	}

	@Test
	@DisplayName("buildErrorMessage with a single error has no separator")
	fun buildErrorMessageSingleError() {
		val result = ValidationResult.error(error(1))

		assertThat(ValidationDialog.buildErrorMessage(result)).isEqualTo(error(1).format())
	}

	@Test
	@DisplayName("buildErrorMessage with warnings only starts directly with the Warnings heading")
	fun buildErrorMessageWarningsOnly() {
		val result = ValidationResult(isValid = true, errors = emptyList(), warnings = listOf(warning(1), warning(2)))

		assertThat(ValidationDialog.buildErrorMessage(result))
			.isEqualTo("Warnings:\n\n" + warning(1).format() + "\n\n" + warning(2).format())
	}

	@Test
	@DisplayName("buildErrorMessage with errors and warnings separates the sections with a thick rule")
	fun buildErrorMessageErrorsAndWarnings() {
		val result =
			ValidationResult(isValid = false, errors = listOf(error(1), error(2)), warnings = listOf(warning(1), warning(2)))

		assertThat(ValidationDialog.buildErrorMessage(result))
			.isEqualTo(
				error(1).format() + errorSeparator + error(2).format() +
					warningSectionSeparator +
					"Warnings:\n\n" + warning(1).format() + "\n\n" + warning(2).format()
			)
	}

	@Test
	@DisplayName("buildErrorMessage with neither errors nor warnings is empty")
	fun buildErrorMessageEmpty() {
		val result = ValidationResult(isValid = true, errors = emptyList(), warnings = emptyList())

		assertThat(ValidationDialog.buildErrorMessage(result)).isEqualTo("")
	}
}
