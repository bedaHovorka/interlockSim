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

import jDisco.Process

/**
 * Exception thrown during simulation - at start, between start and end of simulation.
 *
 * @property severity Severity level of the exception
 */
open class SimulationException : Exception {
	val severity: Severity
	private val obj: Any?
	private val time: Double

	/**
	 * Create SimulationException with default FATAL severity
	 */
	constructor() : this(Severity.FATAL, null as Any?)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param obj Object associated with the exception
	 */
	constructor(obj: Any?) : this(Severity.FATAL, "", obj)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param message Error message
	 */
	constructor(message: String) : this(Severity.FATAL, message, null as Any?)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param message Error message
	 * @param obj Object associated with the exception
	 */
	constructor(message: String, obj: Any?) : this(Severity.FATAL, message, null as Throwable?, obj)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param cause Underlying cause
	 */
	constructor(cause: Throwable?) : this(Severity.FATAL, cause, null as Any?)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param cause Underlying cause
	 * @param obj Object associated with the exception
	 */
	constructor(cause: Throwable?, obj: Any?) : this(Severity.FATAL, "", cause, obj)

	/**
	 * Create SimulationException with specified severity
	 * @param severity Severity level
	 * @param message Error message
	 * @param cause Underlying cause
	 * @param obj Object associated with the exception
	 */
	constructor(severity: Severity, message: String, cause: Throwable?, obj: Any?) : super(message, cause) {
		this.severity = severity
		this.obj = obj
		this.time = Process.time()
	}

	/**
	 * @return object getter
	 */
	open fun getObject(): Any? = obj

	/**
	 * @return model time of exception
	 */
	fun getTime(): Double = time

	override fun toString(): String {
		val msg = message?.takeIf { it.isNotEmpty() } ?: ""
		return "${this::class.simpleName}[$severity]: $msg at time $time"
	}
}
