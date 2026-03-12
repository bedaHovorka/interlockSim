/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

/**
 * Represents the result of a validation operation on a railway network.
 *
 * This data structure supports extensibility for future validators:
 * - Current: XML schema and structural validation (InOut count)
 * - Future Goal 4: Interlocking safety validation
 * - Future Goal 14: Train type parameter validation
 *
 * @property isValid Whether the validation passed
 * @property errors List of validation errors (empty if valid)
 * @property warnings List of validation warnings (non-critical issues)
 */
data class ValidationResult(
	val isValid: Boolean,
	val errors: List<ValidationError> = emptyList(),
	val warnings: List<ValidationWarning> = emptyList()
) {
	companion object {
		/**
		 * Creates a successful validation result with no errors.
		 */
		fun success(): ValidationResult = ValidationResult(true)

		/**
		 * Creates a failed validation result with a single error.
		 */
		fun error(error: ValidationError): ValidationResult = ValidationResult(false, listOf(error))

		/**
		 * Creates a failed validation result with multiple errors.
		 */
		fun errors(errors: List<ValidationError>): ValidationResult = ValidationResult(false, errors)
	}
}

/**
 * Represents a validation error.
 *
 * @property category Category of the error (structural, safety, configuration, performance)
 * @property severity Severity level (ERROR or WARNING)
 * @property message Brief error message
 * @property location Optional location information (file line, element ID, etc.)
 * @property explanation Detailed explanation of why this error matters (educational)
 */
data class ValidationError(
	val category: ErrorCategory,
	val severity: ValidationSeverity,
	val message: String,
	val location: String? = null,
	val explanation: String
) {
	/**
	 * Formats the error for display in a dialog.
	 */
	fun format(): String =
		buildString {
			append("❌ ")
			append(message)
			if (location != null) {
				append("\n   Location: ")
				append(location)
			}
			append("\n\n")
			append(explanation)
		}
}

/**
 * Represents a validation warning (non-critical issue).
 *
 * @property message Brief warning message
 * @property explanation Detailed explanation
 */
data class ValidationWarning(
	val message: String,
	val explanation: String
) {
	/**
	 * Formats the warning for display in a dialog.
	 */
	fun format(): String =
		buildString {
			append("⚠ ")
			append(message)
			append("\n\n")
			append(explanation)
		}
}

/**
 * Categories of validation errors.
 * Extensible for future validators (Goal 4: interlocking safety, Goal 14: train types).
 */
enum class ErrorCategory {
	/**
	 * Structural errors: XML schema violations, missing required elements.
	 * Current scope: Issue #258
	 */
	STRUCTURAL,

	/**
	 * Safety errors: Interlocking violations, route conflicts.
	 * Future scope: Goal 4 (Interlocking Validation)
	 */
	SAFETY,

	/**
	 * Configuration errors: Invalid parameters, train type violations.
	 * Future scope: Goal 14 (Custom Train Types)
	 */
	CONFIGURATION,

	/**
	 * Performance warnings: Suboptimal settings, inefficient configurations.
	 * Future scope: Performance analysis features
	 */
	PERFORMANCE
}

/**
 * Severity levels for validation errors.
 */
enum class ValidationSeverity {
	/**
	 * Critical error that prevents the network from being used.
	 */
	ERROR,

	/**
	 * Non-critical warning that should be addressed but doesn't prevent usage.
	 */
	WARNING
}
