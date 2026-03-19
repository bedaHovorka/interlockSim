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

import cz.hovorka.kdisco.engine.Process
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Exception thrown during simulation - at start, between start and end of simulation.
 *
 * @property severity Severity level of the exception
 */
open class SimulationException(
	val severity: Severity,
	message: String,
	cause: Throwable?,
	private val obj: Any?
) : Exception(message, cause) {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	private val time: Double = runCatching { Process.time() }
		.onFailure { e ->
			if (e !is IllegalStateException) logger.debug(e) { "Process.time() failed unexpectedly" }
		}
		.getOrDefault(0.0)

	/**
	 * Create SimulationException with default FATAL severity
	 */
	constructor() : this(Severity.FATAL, "", null, null)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param obj Object associated with the exception
	 */
	constructor(obj: Any?) : this(Severity.FATAL, "", null, obj)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param message Error message
	 */
	constructor(message: String) : this(Severity.FATAL, message, null, null)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param message Error message
	 * @param obj Object associated with the exception
	 */
	constructor(message: String, obj: Any?) : this(Severity.FATAL, message, null, obj)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param cause Underlying cause
	 */
	constructor(cause: Throwable?) : this(Severity.FATAL, "", cause, null)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param cause Underlying cause
	 * @param obj Object associated with the exception
	 */
	constructor(cause: Throwable?, obj: Any?) : this(Severity.FATAL, "", cause, obj)

	/**
	 * Create SimulationException with default FATAL severity
	 * @param message Error message
	 * @param cause Underlying cause
	 * @param obj Object associated with the exception
	 */
	constructor(message: String, cause: Throwable?, obj: Any?) : this(Severity.FATAL, message, cause, obj)

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
