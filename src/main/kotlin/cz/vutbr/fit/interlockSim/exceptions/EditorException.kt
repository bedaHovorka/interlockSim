/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.exceptions

/**
 * Exception thrown during editor operations, validation, or user mistakes.
 * Examples: wrong move, bad element placing, invalid configuration.
 *
 * @property severity Severity level of the exception
 * @property obj Optional object associated with the exception
 */
class EditorException : Exception {
	val severity: Severity
	private val obj: Any?

	/**
	 * Create EditorException with default FATAL severity
	 */
	constructor() : this(Severity.FATAL, null as Any?)

	/**
	 * Create EditorException with default FATAL severity
	 * @param obj Object associated with the exception
	 */
	constructor(obj: Any?) : this(Severity.FATAL, "", obj)

	/**
	 * Create EditorException with default FATAL severity
	 * @param message Error message
	 */
	constructor(message: String) : this(Severity.FATAL, message, null as Any?)

	/**
	 * Create EditorException with specified severity
	 * @param severity Severity level
	 * @param message Error message
	 */
	constructor(severity: Severity, message: String) : this(severity, message, null as Any?)

	/**
	 * Create EditorException with specified severity and object
	 * @param severity Severity level
	 * @param message Error message
	 * @param obj Object associated with the exception
	 */
	constructor(severity: Severity, message: String, obj: Any?) : super(message) {
		this.severity = severity
		this.obj = obj
	}

	/**
	 * Create EditorException with default FATAL severity and cause
	 * @param cause Underlying cause
	 */
	constructor(cause: Throwable?) : this(Severity.FATAL, cause, null as Any?)

	/**
	 * Create EditorException with specified severity and cause
	 * @param severity Severity level
	 * @param cause Underlying cause
	 * @param obj Object associated with the exception
	 */
	constructor(severity: Severity, cause: Throwable?, obj: Any?) : this(severity, "", cause, obj)

	/**
	 * Create EditorException with all parameters
	 * @param severity Severity level
	 * @param message Error message
	 * @param cause Underlying cause
	 * @param obj Object associated with the exception
	 */
	constructor(severity: Severity, message: String, cause: Throwable?, obj: Any?) : super(message, cause) {
		this.severity = severity
		this.obj = obj
	}

	/**
	 * @return object getter
	 */
	fun getObject(): Any? = obj

	override fun toString(): String {
		val msg = message?.takeIf { it.isNotEmpty() } ?: ""
		return "${this::class.simpleName}[$severity]: $msg"
	}
}
