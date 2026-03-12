package cz.vutbr.fit.interlockSim.util


/**
 * Validates railway element names according to XML schema pattern.
 *
 * Validation rules (from data.xsd):
 * - Pattern: [a-zA-Z0-9_-]+
 * - Length: 1-50 characters
 * - No empty strings
 * - No whitespace
 *
 * Used by:
 * - XMLContextFactory (XML parsing)
 * - RenameDialog (GUI validation)
 * - AutoNameGenerator (name generation)
 * - Unit tests
 *
 * Thread-safety: Thread-safe (stateless singleton).
 */
object NameValidator {
	private val VALID_NAME_PATTERN = Regex("^[a-zA-Z0-9_-]+$")
	private const val MAX_NAME_LENGTH = 50

	/**
	 * Validates a name and returns structured result.
	 *
	 * @param name The name to validate
	 * @return ValidationResult with errors if validation fails
	 */
	fun validate(name: String): ValidationResult {
		val errors = mutableListOf<ValidationError>()

		// Check empty/blank
		if (name.isBlank()) {
			errors.add(
				ValidationError(
					category = ErrorCategory.CONFIGURATION,
					severity = ValidationSeverity.ERROR,
					message = "Name cannot be empty",
					explanation = "Railway element names must contain at least one character."
				)
			)
		}

		// Check length
		if (name.length > MAX_NAME_LENGTH) {
			errors.add(
				ValidationError(
					category = ErrorCategory.CONFIGURATION,
					severity = ValidationSeverity.ERROR,
					message = "Name too long (max $MAX_NAME_LENGTH characters, got ${name.length})",
					explanation =
						"Railway element names are limited to $MAX_NAME_LENGTH characters " +
							"to ensure compatibility with XML schema and database storage."
				)
			)
		}

		// Check pattern (alphanumeric, underscore, hyphen only)
		if (name.isNotBlank() && !name.matches(VALID_NAME_PATTERN)) {
			errors.add(
				ValidationError(
					category = ErrorCategory.CONFIGURATION,
					severity = ValidationSeverity.ERROR,
					message = "Name contains invalid characters",
					explanation =
						"Railway element names may only contain letters (a-z, A-Z), digits (0-9), " +
							"underscores (_), and hyphens (-). No spaces or special characters are allowed."
				)
			)
		}

		return if (errors.isEmpty()) {
			ValidationResult.success()
		} else {
			ValidationResult.errors(errors)
		}
	}

	/**
	 * Simple boolean check (for backward compatibility).
	 *
	 * @param name The name to validate
	 * @return true if name is valid, false otherwise
	 */
	fun isValid(name: String): Boolean = validate(name).isValid
}
